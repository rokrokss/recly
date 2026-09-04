package recly.core.testing

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A scripted Drive v3 that remembers what it was sent. Real enough to exercise the runner end to
 * end — it computes md5, keeps resumable sessions and their offsets, and honours the `q`/`spaces`
 * filters — and scriptable enough to inject the failures the lane spec asks for.
 */
class FakeDrive {
    data class Recorded(
        val method: String,
        val url: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val contentType: String?,
        val body: ByteArray,
    ) {
        val uploadType: String? get() = query["uploadType"]
    }

    class Entry(
        var name: String,
        var parents: List<String>,
        var mimeType: String,
        var appProperties: Map<String, String>,
        var description: String?,
        var content: ByteArray,
        var version: Long = 1,
        val createdTime: String = "",
    ) {
        /** Drive keeps one revision per write, oldest first — what a lost-update replay reads. */
        val revisions: MutableList<Revision> = mutableListOf()

        val headRevisionId: String? get() = revisions.lastOrNull()?.id
    }

    class Revision(val id: String, val modifiedTime: String, val content: ByteArray)

    val requests = mutableListOf<Recorded>()
    private val responses = mutableListOf<Int>()
    val files = LinkedHashMap<String, Entry>()

    /** Names whose reported `md5Checksum` is a lie, to exercise the verify-then-delete path. */
    val corruptMd5 = mutableSetOf<String>()
    /** Ids a DELETE was issued for, whether or not anything was there to remove. */
    val deleted = mutableListOf<String>()

    /** Ids the user moved to the bin: still readable by id, but never listed as a child. */
    val trashed = mutableSetOf<String>()

    /** When set, `files.list` answers in pages of this size and hands out a `nextPageToken`. */
    var listPageSize: Int? = null

    /** When set, every request must carry exactly this bearer token or it is answered with 401. */
    var acceptedToken: String? = null

    /** Run just before a request is answered: the seam where "another device wrote" happens. */
    val before = mutableListOf<(Recorded) -> Unit>()

    private val sessions = LinkedHashMap<String, Session>()
    private val chunkCount = mutableMapOf<String, Int>()
    private val faults = mutableListOf<Fault>()
    private var nextId = 1
    private var nextSession = 1
    private var tick = 0

    fun engine(): MockEngine = MockEngine { request ->
        val recorded = record(request)
        before.forEach { it(recorded) }
        val expected = acceptedToken
        val fault = faults.firstOrNull { it.remaining > 0 && it.match(recorded) }
        val response = when {
            expected != null && recorded.headers[HttpHeaders.Authorization] != "Bearer $expected" ->
                respond("""{"error":"invalid credentials"}""", HttpStatusCode.Unauthorized)

            fault != null -> {
                fault.remaining--
                respond(fault.body, HttpStatusCode.fromValue(fault.status), headers(fault.headers))
            }

            else -> handle(recorded)
        }
        responses += response.statusCode.value
        response
    }

    /** The status the request at this index of [requests] was answered with. */
    fun statusAt(index: Int): Int = responses[index]

    /** The next [times] requests that [match] answer with [status] instead of doing the work. */
    fun failNext(
        status: Int,
        times: Int = 1,
        headers: Map<String, String> = emptyMap(),
        body: String = "",
        match: (Recorded) -> Boolean,
    ) {
        faults += Fault(status, headers, body, times, match)
    }

    fun clearFaults() = faults.clear()

    /** Which file a session URI is uploading — the hook the chunk-failure scripts match on. */
    fun sessionName(url: String): String? = sessions[url]?.meta?.name

    fun chunksSoFar(url: String): Int = chunkCount[url] ?: 0

    fun byName(name: String): Entry? = files.values.firstOrNull { it.name == name }

    /** Puts a file there without anyone uploading it — a decoy, or a leftover from another app. */
    fun put(name: String, parentId: String, content: ByteArray, mimeType: String = "audio/mp4"): String =
        create(DriveMeta(name, listOf(parentId), mimeType, emptyMap(), null), content)

    /** A folder as another device's `drive.upload` leaves it: stamped and described (ADR-014). */
    fun putFolder(
        name: String,
        parentId: String,
        appProperties: Map<String, String> = emptyMap(),
        description: String? = null,
    ): String = create(DriveMeta(name, listOf(parentId), FOLDER_MIME, appProperties, description), ByteArray(0))

    fun idOf(name: String): String? = files.entries.firstOrNull { it.value.name == name }?.key

    /** The name of every file an upload was started for, in order — resumable or multipart. */
    fun uploadOrder(): List<String> = requests.mapNotNull {
        when {
            it.path == "/upload/drive/v3/files" && it.uploadType == "resumable" -> metaOf(it.body).name
            it.path == "/upload/drive/v3/files" && it.uploadType == "multipart" -> multipartMeta(it).name
            else -> null
        }
    }

    private suspend fun record(request: HttpRequestData): Recorded = Recorded(
        method = request.method.value,
        url = request.url.toString(),
        path = request.url.encodedPath,
        query = request.url.parameters.entries().associate { it.key to it.value.first() },
        headers = request.headers.entries().associate { it.key to it.value.first() },
        contentType = request.body.contentType?.toString() ?: request.headers[HttpHeaders.ContentType],
        body = request.body.toByteArray(),
    ).also { requests += it }

    private fun MockRequestHandleScope.handle(r: Recorded): HttpResponseData = when {
        r.url.startsWith(SESSION_PREFIX) -> chunk(r)

        r.path == "/upload/drive/v3/files" && r.uploadType == "resumable" -> startSession(r)

        r.path == "/upload/drive/v3/files" && r.uploadType == "multipart" -> {
            val meta = multipartMeta(r)
            if (unknownParent(meta)) {
                respond("""{"error":"parent not found"}""", HttpStatusCode.NotFound)
            } else {
                val id = create(meta, multipartContent(r))
                json(fileJson(id, files.getValue(id)))
            }
        }

        r.path.startsWith("/upload/drive/v3/files/") && r.uploadType == "media" -> {
            val entry = files[r.path.substringAfterLast('/')]
            if (entry == null) {
                respond("", HttpStatusCode.NotFound)
            } else {
                overwrite(r.path.substringAfterLast('/'), r.body)
                json(fileJson(r.path.substringAfterLast('/'), entry))
            }
        }

        // `files.update` metadata: the description a rename writes (docs/03 "제목").
        r.method == "PATCH" && r.path.startsWith("/drive/v3/files/") -> {
            val id = r.path.substringAfterLast('/')
            val entry = files[id]
            if (entry == null) {
                respond("", HttpStatusCode.NotFound)
            } else {
                val body = Json.parseToJsonElement(r.body.decodeToString()) as JsonObject
                (body["description"] as? JsonPrimitive)?.let { entry.description = it.content }
                json(fileJson(id, entry))
            }
        }

        r.method == "POST" && r.path == "/drive/v3/files" -> {
            val meta = metaOf(r.body)
            if (unknownParent(meta)) {
                respond("""{"error":"parent not found"}""", HttpStatusCode.NotFound)
            } else {
                val id = create(meta, ByteArray(0))
                json(fileJson(id, files.getValue(id)))
            }
        }

        r.method == "GET" && r.path == "/drive/v3/files" -> json(list(r))

        r.method == "GET" && REVISION_MEDIA.matches(r.path) -> {
            val match = REVISION_MEDIA.find(r.path)!!
            val revision = files[match.groupValues[1]]?.revisions?.firstOrNull { it.id == match.groupValues[2] }
            if (revision == null) {
                respond("", HttpStatusCode.NotFound)
            } else {
                respond(revision.content, HttpStatusCode.OK)
            }
        }

        r.method == "GET" && REVISION_LIST.matches(r.path) -> {
            val entry = files[REVISION_LIST.find(r.path)!!.groupValues[1]]
            if (entry == null) respond("", HttpStatusCode.NotFound) else json(revisionsJson(entry))
        }

        r.method == "GET" && r.path.startsWith("/drive/v3/files/") -> {
            val id = r.path.substringAfterLast('/')
            val entry = files[id]
            when {
                entry == null -> respond("", HttpStatusCode.NotFound)
                r.query["alt"] == "media" -> respond(entry.content, HttpStatusCode.OK)
                else -> json(fileJson(id, entry))
            }
        }

        r.method == "DELETE" && r.path.startsWith("/drive/v3/files/") -> {
            val id = r.path.substringAfterLast('/')
            deleted += id
            if (files.remove(id) == null) {
                respond("""{"error":"file not found"}""", HttpStatusCode.NotFound)
            } else {
                respond("", HttpStatusCode.NoContent)
            }
        }

        else -> respond("""{"error":"unrouted ${r.method} ${r.path}"}""", HttpStatusCode.BadRequest)
    }

    private fun MockRequestHandleScope.startSession(r: Recorded): HttpResponseData {
        val meta = metaOf(r.body)
        if (unknownParent(meta)) return respond("""{"error":"parent not found"}""", HttpStatusCode.NotFound)
        val uri = "$SESSION_PREFIX${nextSession++}"
        sessions[uri] = Session(meta, r.headers["X-Upload-Content-Length"]?.toLong() ?: 0)
        return respond("", HttpStatusCode.OK, headers(mapOf(HttpHeaders.Location to uri)))
    }

    private fun MockRequestHandleScope.chunk(r: Recorded): HttpResponseData {
        val session = sessions[r.url] ?: return respond("", HttpStatusCode.NotFound)
        val range = r.headers["Content-Range"].orEmpty()
        if (range.startsWith("bytes */")) return progress(session)

        chunkCount[r.url] = (chunkCount[r.url] ?: 0) + 1
        val start = range.removePrefix("bytes ").substringBefore('-').toLong()
        if (start != session.received.size.toLong()) return respond("", HttpStatusCode.BadRequest)
        session.received += r.body
        if (session.received.size.toLong() < session.total) return progress(session)

        val id = create(session.meta, session.received)
        sessions.remove(r.url)
        return json(fileJson(id, files.getValue(id)))
    }

    private fun MockRequestHandleScope.progress(session: Session): HttpResponseData {
        val stored = session.received.size
        val headers = if (stored == 0) emptyMap() else mapOf("Range" to "bytes=0-${stored - 1}")
        return respond("", HttpStatusCode.fromValue(308), headers(headers))
    }

    private fun MockRequestHandleScope.json(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headers(mapOf(HttpHeaders.ContentType to "application/json")))

    private fun revisionsJson(entry: Entry): String = entry.revisions.joinToString(
        separator = ",",
        prefix = "{\"revisions\":[",
        postfix = "]}",
    ) { "{\"id\":\"${it.id}\",\"modifiedTime\":\"${it.modifiedTime}\"}" }

    private fun list(r: Recorded): String {
        val q = r.query["q"].orEmpty()
        val spaces = r.query["spaces"].orEmpty()
        val parent = PARENT.find(q)?.groupValues?.get(1)?.unescape()
        val name = NAME.find(q)?.groupValues?.get(1)?.unescape()
        val mime = MIME.find(q)?.groupValues?.get(1)?.unescape()
        val property = PROPERTY.find(q)?.groupValues?.get(1)
        val matches = files.entries.filter { (id, e) ->
            id !in trashed &&
                (parent == null || parent in e.parents) &&
                (name == null || name == e.name) &&
                (mime == null || mime == e.mimeType) &&
                (property == null || property in e.appProperties) &&
                (spaces != "appDataFolder" || "appDataFolder" in e.parents)
        }
        val size = listPageSize ?: return """{"files":[${matches.render()}]}"""
        val from = r.query["pageToken"]?.toInt() ?: 0
        val page = matches.drop(from).take(size)
        val next = if (from + size < matches.size) ""","nextPageToken":"${from + size}"""" else ""
        return """{"files":[${page.render()}]$next}"""
    }

    private fun List<Map.Entry<String, Entry>>.render(): String =
        joinToString(",") { fileJson(it.key, it.value) }

    /** Drive 404s a create whose parent does not exist; the two aliases always do. */
    private fun unknownParent(meta: DriveMeta): Boolean =
        meta.parents.any { it != "root" && it != "appDataFolder" && it !in files }

    private fun create(meta: DriveMeta, content: ByteArray): String {
        val id = "id${nextId++}"
        val entry = Entry(
            meta.name,
            meta.parents,
            meta.mimeType,
            meta.appProperties,
            meta.description,
            content,
            createdTime = stamp(),
        )
        entry.revisions += Revision("$id-r1", stamp(), content)
        files[id] = entry
        return id
    }

    /** Replaces a file's content the way `files.update` does: new version, new revision. */
    fun overwrite(id: String, content: ByteArray) {
        val entry = files.getValue(id)
        entry.content = content
        entry.version += 1
        entry.revisions += Revision("$id-r${entry.revisions.size + 1}", stamp(), content)
    }

    /** Ordering is all these timestamps carry, so one monotonic counter drives them all. */
    private fun stamp(): String = "2026-08-26T00:00:" + (tick++).toString().padStart(2, '0') + ".000Z"

    private fun fileJson(id: String, entry: Entry): String {
        val link = if (entry.mimeType == FOLDER_MIME) {
            "https://drive.google.com/drive/folders/$id"
        } else {
            "https://drive.google.com/file/d/$id/view"
        }
        val md5 = if (entry.name in corruptMd5) "0".repeat(32) else md5(entry.content)
        val properties = entry.appProperties.entries.joinToString(",") { (k, v) -> """"$k":"$v"""" }
        val description = entry.description?.let { """"description":"$it",""" }.orEmpty()
        return """{"id":"$id","name":"${entry.name}","mimeType":"${entry.mimeType}",""" +
            """"trashed":${id in trashed},"md5Checksum":"$md5","appProperties":{$properties},$description""" +
            """"createdTime":"${entry.createdTime}","headRevisionId":"${entry.headRevisionId}",""" +
            """"webViewLink":"$link","version":"${entry.version}"}"""
    }

    /** `--B\r\nContent-Type: …\r\n\r\n{json}\r\n--B\r\nContent-Type: …\r\n\r\n{bytes}\r\n--B--`. */
    private fun multipartMeta(r: Recorded): DriveMeta {
        val body = r.body
        val start = body.indexOfSub(BLANK, 0) + BLANK.size
        val end = body.indexOfSub(separator(r), start)
        return metaOf(body.copyOfRange(start, end))
    }

    private fun multipartContent(r: Recorded): ByteArray {
        val body = r.body
        val afterMeta = body.indexOfSub(separator(r), 0) + separator(r).size
        val start = body.indexOfSub(BLANK, afterMeta) + BLANK.size
        val end = body.size - "\r\n--${boundary(r)}--".length
        return body.copyOfRange(start, end)
    }

    private fun boundary(r: Recorded): String = r.contentType.orEmpty().substringAfter("boundary=").trim('"')

    private fun separator(r: Recorded): ByteArray = "\r\n--${boundary(r)}\r\n".toByteArray()

    private fun metaOf(json: ByteArray): DriveMeta = metaOf(json.decodeToString())

    private fun metaOf(json: String): DriveMeta {
        val obj = Json.parseToJsonElement(json) as JsonObject
        fun str(key: String) = (obj[key] as? JsonPrimitive)?.content
        return DriveMeta(
            name = str("name").orEmpty(),
            parents = (obj["parents"] as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty(),
            mimeType = str("mimeType").orEmpty(),
            appProperties = (obj["appProperties"] as? JsonObject)
                ?.mapValues { (_, v) -> (v as JsonPrimitive).content }.orEmpty(),
            description = str("description"),
        )
    }

    private fun headers(map: Map<String, String>): Headers = Headers.build {
        map.forEach { (k, v) -> append(k, v) }
    }

    data class DriveMeta(
        val name: String,
        val parents: List<String>,
        val mimeType: String,
        val appProperties: Map<String, String>,
        val description: String?,
    )

    private class Session(val meta: DriveMeta, val total: Long, var received: ByteArray = ByteArray(0))

    private class Fault(
        val status: Int,
        val headers: Map<String, String>,
        val body: String,
        var remaining: Int,
        val match: (Recorded) -> Boolean,
    )

    companion object {
        const val SESSION_PREFIX = "https://upload.example/session/"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"

        private val REVISION_LIST = Regex("^/drive/v3/files/([^/]+)/revisions$")
        private val REVISION_MEDIA = Regex("^/drive/v3/files/([^/]+)/revisions/([^/]+)$")
        private val PARENT = Regex("'((?:[^'\\\\]|\\\\.)*)' in parents")
        private val NAME = Regex("name = '((?:[^'\\\\]|\\\\.)*)'")
        private val MIME = Regex("mimeType = '((?:[^'\\\\]|\\\\.)*)'")
        private val PROPERTY = Regex("appProperties has \\{ key='([^']*)' \\}")
        private val BLANK = "\r\n\r\n".toByteArray()

        fun md5(bytes: ByteArray): String =
            MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

        private fun String.unescape(): String = replace("\\'", "'").replace("\\\\", "\\")

        private fun ByteArray.indexOfSub(needle: ByteArray, from: Int): Int {
            outer@ for (i in from..size - needle.size) {
                for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }
    }
}

