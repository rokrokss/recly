@file:OptIn(ExperimentalTime::class)

package recly.core.drive

import kotlin.random.Random
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okio.Buffer
import okio.Path
import recly.core.drive.ResumableUploadPlanner.Outcome
import recly.core.job.StepFailure
import recly.core.message.CoreMessage
import recly.core.model.isoUtc
import recly.core.platform.CoreDeps
import recly.core.platform.HttpBody
import recly.core.platform.HttpPlan
import recly.core.platform.HttpResult

/**
 * The slice of Drive v3 this app needs, expressed as [HttpPlan]s handed to [CoreDeps.transport].
 * Every non-2xx answer becomes a [StepFailure] the executor already knows how to route, so the
 * runners above only ever deal with files.
 */
class DriveApi(
    private val deps: CoreDeps,
    private val random: Random = Random.Default,
) {
    /** Bigger than this and a single request is a bad bet on a phone connection (docs/10). */
    val multipartLimit: Long get() = MULTIPART_LIMIT

    suspend fun createFolder(
        name: String,
        parentId: String,
        description: String? = null,
        appProperties: Map<String, String> = emptyMap(),
    ): DriveFile {
        val meta = DriveFileMeta(name, listOf(parentId), FOLDER_MIME, appProperties, description)
        val result = send("drive.createFolder") { token ->
            HttpPlan(
                method = "POST",
                url = "$FILES_URL?fields=${urlEncode(FOLDER_FIELDS)}",
                headers = mapOf("Authorization" to "Bearer $token"),
                body = HttpBody.Text(meta.toJson().toString(), ResumableUploadPlanner.JSON_TYPE),
            )
        }
        return file("drive.createFolder", result)
    }

    /**
     * Every non-trashed child of [parentId] with this name. Drive allows duplicates, so this is a
     * list and not a single file: only the caller knows which of them (by md5) is the one it
     * meant to upload.
     */
    suspend fun findChildren(parentId: String, name: String, mimeType: String? = null): List<DriveFile> {
        val q = buildString {
            append("'").append(escapeQuery(parentId)).append("' in parents")
            append(" and name = '").append(escapeQuery(name)).append("'")
            if (mimeType != null) append(" and mimeType = '").append(escapeQuery(mimeType)).append("'")
            append(" and trashed = false")
        }
        return list(q, spaces = "drive", fields = CHILD_FIELDS).mapNotNull { DriveFile.from(it) }
    }

    /** For the callers where any match will do — a folder, which we never create twice. */
    suspend fun findChild(parentId: String, name: String, mimeType: String? = null): DriveFile? =
        findChildren(parentId, name, mimeType).firstOrNull()

    /**
     * Every recording folder this app made, from any device (docs/03 "다른 기기의 녹음"): the
     * `{base}/` folders, which are the only folders it stamps a `recordingId` on (ADR-014). One
     * query whatever the folder templates were, since it does not walk the path at all.
     *
     * Drive's `appProperties has { … }` needs both a key and a value — a key alone is `400 Invalid
     * Value` (found on a real account, 2026-09-04) — so the query asks for every folder the app can
     * see (under `drive.file` that is only what it made: the path folders and these) and the
     * recording ones are picked out here by the property they carry.
     */
    suspend fun recordingFolders(): List<JsonObject> = list(
        q = "mimeType = '$FOLDER_MIME' and trashed = false",
        spaces = "drive",
        fields = RECORDING_FOLDER_FIELDS,
    ).filter { it["appProperties"]?.jsonObject?.get("recordingId") != null }

    /** Every non-trashed file in a folder, by id and name. */
    suspend fun children(parentId: String): List<DriveFile> = list(
        q = "'${escapeQuery(parentId)}' in parents and trashed = false",
        spaces = "drive",
        fields = CHILD_FIELDS,
    ).mapNotNull { DriveFile.from(it) }

    /** Follows `nextPageToken` to the end: a half-read listing would look like a missing file. */
    suspend fun list(q: String, spaces: String, fields: String): List<JsonObject> =
        paged("drive.list", "files") { page ->
            "$FILES_URL?q=${urlEncode(q)}&spaces=${urlEncode(spaces)}" +
                "&fields=${urlEncode("nextPageToken,$fields")}" +
                if (page == null) "" else "&pageToken=${urlEncode(page)}"
        }

    /** One GET per page, [url] built from the token of the last one, until Drive stops sending one. */
    private suspend fun paged(what: String, key: String, url: (String?) -> String): List<JsonObject> {
        val all = mutableListOf<JsonObject>()
        var pageToken: String? = null
        do {
            val page = pageToken
            val result = send(what) { token ->
                HttpPlan(method = "GET", url = url(page), headers = mapOf("Authorization" to "Bearer $token"))
            }
            val json = result.json()
            all += json?.get(key)?.jsonArray?.map { it.jsonObject }.orEmpty()
            pageToken = json?.string("nextPageToken")
        } while (pageToken != null)
        return all
    }

    /** Null when Drive says the file is gone — a cached folder id that no longer resolves. */
    suspend fun getFile(id: String, fields: String): JsonObject? {
        val result = send("drive.get", allow404 = true) { token ->
            HttpPlan(
                method = "GET",
                url = "$FILES_URL/$id?fields=${urlEncode(fields)}",
                headers = mapOf("Authorization" to "Bearer $token"),
            )
        }
        if (result.status == 404) return null
        return result.json()
    }

    suspend fun download(id: String): ByteArray =
        send("drive.download") { token ->
            HttpPlan(
                method = "GET",
                url = "$FILES_URL/$id?alt=media",
                headers = mapOf("Authorization" to "Bearer $token"),
            )
        }.body

    suspend fun delete(id: String) {
        send("drive.delete", allow404 = true) { token ->
            HttpPlan(
                method = "DELETE",
                url = "$FILES_URL/$id",
                headers = mapOf("Authorization" to "Bearer $token"),
            )
        }
    }

    /** One request, metadata and media together — worth it only for the small files (docs/10). */
    suspend fun multipartUpload(meta: DriveFileMeta, bytes: ByteArray): DriveFile {
        val boundary = "rec_${random.nextLong().toULong().toString(16)}"
        val body = Buffer()
            .writeUtf8("--$boundary\r\nContent-Type: ${ResumableUploadPlanner.JSON_TYPE}\r\n\r\n")
            .writeUtf8(meta.toJson().toString())
            .writeUtf8("\r\n--$boundary\r\nContent-Type: ${meta.mimeType}\r\n\r\n")
            .write(bytes)
            .writeUtf8("\r\n--$boundary--")
            .readByteArray()
        val result = send("drive.multipartUpload") { token ->
            HttpPlan(
                method = "POST",
                url = "${ResumableUploadPlanner.UPLOAD_URL}?uploadType=multipart" +
                    "&fields=${urlEncode(ResumableUploadPlanner.FILE_FIELDS)}",
                headers = mapOf("Authorization" to "Bearer $token"),
                body = HttpBody.Bytes(body, "multipart/related; boundary=$boundary"),
            )
        }
        return file("drive.multipartUpload", result)
    }

    /** The folder's `description` — where a recording's title lives on Drive (ADR-014). */
    suspend fun updateDescription(fileId: String, description: String) {
        val body = buildJsonObject { put("description", description) }.toString().encodeToByteArray()
        send("drive.updateDescription") { token ->
            HttpPlan(
                method = "PATCH",
                url = "$FILES_URL/$fileId?fields=id",
                headers = mapOf("Authorization" to "Bearer $token"),
                body = HttpBody.Bytes(body, "application/json"),
            )
        }
    }

    /**
     * Merges keys into a file's `appProperties` — what the `pending` marker of docs/03 "다른 기기의
     * 녹음" is written with. Drive merges rather than replaces, so the `recordingId`/`workflowId` the
     * folder was stamped with when it was created survive every call of this.
     */
    suspend fun updateAppProperties(fileId: String, appProperties: Map<String, String>) {
        val body = buildJsonObject {
            putJsonObject("appProperties") { appProperties.forEach { (key, value) -> put(key, value) } }
        }.toString().encodeToByteArray()
        send("drive.updateAppProperties") { token ->
            HttpPlan(
                method = "PATCH",
                url = "$FILES_URL/$fileId?fields=id",
                headers = mapOf("Authorization" to "Bearer $token"),
                body = HttpBody.Bytes(body, "application/json"),
            )
        }
    }

    /** Replaces an existing file's content, leaving its id and parents alone. */
    suspend fun updateMedia(fileId: String, bytes: ByteArray, mimeType: String): DriveFile {
        val result = send("drive.updateMedia") { token ->
            HttpPlan(
                method = "PATCH",
                url = "${ResumableUploadPlanner.UPLOAD_URL}/$fileId?uploadType=media" +
                    "&fields=${urlEncode(ResumableUploadPlanner.FILE_FIELDS)}",
                headers = mapOf("Authorization" to "Bearer $token"),
                body = HttpBody.Bytes(bytes, mimeType),
            )
        }
        return file("drive.updateMedia", result)
    }

    /**
     * Drives [ResumableUploadPlanner] against the transport. [saveState] is called after every
     * chunk, so a kill costs one chunk; a 5xx saves the offset and fails retryably instead of
     * spinning here, which lets the executor's backoff (and the job queue) own the waiting.
     */
    suspend fun uploadResumable(
        meta: DriveFileMeta,
        path: Path,
        total: Long,
        state: UploadState?,
        saveState: suspend (UploadState) -> Unit,
    ): DriveFile {
        val chunk = ResumableUploadPlanner.chunkSize(deps.device.platform)
        val resumed = state?.takeUnless { it.sessionUri == null || expired(it) }
        var uri: String? = resumed?.sessionUri
        var offset = resumed?.offset ?: 0L
        var startedAt = resumed?.startedAt
        // A session picked up from a saved state has to be asked how far it got: the crash may
        // have happened after Drive stored a chunk but before we wrote the new offset.
        var query = resumed != null

        while (true) {
            val session = uri
            if (session == null) {
                val outcome = ResumableUploadPlanner.onResponse(
                    send("drive.uploadStart", raw = true) { ResumableUploadPlanner.startRequest(meta, total, it) },
                )
                when (outcome) {
                    // Nothing has been uploaded yet, so a "session gone" here is really the parent.
                    is Outcome.Restart ->
                        throw DriveNotFound("drive.uploadStart: parent '${meta.parents.firstOrNull()}' is gone")

                    is Outcome.SessionStarted -> {
                        uri = outcome.uri
                        offset = 0
                        startedAt = deps.clock.now().isoUtc()
                        query = false
                        saveState(UploadState(uri, 0, null, startedAt))
                    }

                    is Outcome.Done -> return outcome.file
                    else -> throw failure("drive.uploadStart", outcome, UploadState())
                }
                continue
            }
            if (query) {
                val outcome = ResumableUploadPlanner.onResponse(
                    send("drive.uploadQuery", raw = true) { ResumableUploadPlanner.queryRequest(session, total, it) },
                )
                when (outcome) {
                    is Outcome.Continue -> {
                        offset = outcome.nextOffset
                        query = false
                    }

                    is Outcome.Done -> {
                        saveState(UploadState(fileId = outcome.file.id))
                        return outcome.file
                    }

                    is Outcome.Restart -> {
                        uri = null
                        offset = 0
                        query = false
                        saveState(UploadState())
                    }

                    else -> throw failure("drive.uploadQuery", outcome, UploadState(session, offset, null, startedAt))
                }
                continue
            }
            val length = minOf(chunk, total - offset)
            val outcome = ResumableUploadPlanner.onResponse(
                send("drive.uploadChunk", raw = true) {
                    ResumableUploadPlanner.chunkRequest(session, offset, length, total, path, it)
                },
            )
            when (outcome) {
                is Outcome.Continue -> {
                    offset = outcome.nextOffset
                    saveState(UploadState(session, offset, null, startedAt))
                }

                is Outcome.Done -> {
                    saveState(UploadState(fileId = outcome.file.id))
                    return outcome.file
                }

                is Outcome.Restart -> {
                    uri = null
                    offset = 0
                    saveState(UploadState())
                }

                else -> {
                    val parked = UploadState(session, offset, null, startedAt)
                    saveState(parked)
                    throw failure("drive.uploadChunk", outcome, parked)
                }
            }
        }
    }

    /** Drive keeps a resumable session for a week (docs/10); an older one would 404 anyway. */
    private fun expired(state: UploadState): Boolean {
        val started = state.startedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return true
        return deps.clock.now() - started > SESSION_TTL
    }

    private fun failure(what: String, outcome: Outcome, state: UploadState): StepFailure = when (outcome) {
        is Outcome.RetryAfter -> StepFailure(
            retryable = true,
            reason = CoreMessage.STEP_FAILED.code("$what: server asked to retry (offset ${state.offset})"),
            retryAfterSec = outcome.sec?.toLong(),
        )

        is Outcome.Fail -> StepFailure(
            retryable = false,
            reason = CoreMessage.STEP_FAILED.code("$what: ${outcome.reason}"),
        )
        Outcome.Unauthorized -> StepFailure(retryable = false, reason = CoreMessage.NEEDS_AUTH.code(), needsAuth = true)
        else -> StepFailure(retryable = false, reason = CoreMessage.STEP_FAILED.code("$what: unexpected outcome $outcome"))
    }

    /**
     * One round trip with the one retry that is worth doing inline: a 401 means the token went
     * stale mid-job, and re-signing it costs nothing. A second 401 is a real sign-in problem, so
     * the job parks in `NEEDS_AUTH` rather than burning its retry budget.
     */
    private suspend fun send(
        what: String,
        allow404: Boolean = false,
        /** The resumable calls read 308/404/5xx themselves, so they take the answer unjudged. */
        raw: Boolean = false,
        plan: (String) -> HttpPlan,
    ): HttpResult {
        var result = deps.transport.execute(plan(deps.tokenProvider.accessToken()))
        if (result.status == 401) {
            deps.tokenProvider.invalidate()
            result = deps.transport.execute(plan(deps.tokenProvider.accessToken()))
        }
        // Before [check], because the resumable calls read their own answers and would otherwise
        // turn a full Drive into an ordinary non-retryable failure.
        storageFull(result, what)?.let { throw it }
        if (!raw) check(result, what, allow404)
        return result
    }

    /**
     * docs/10 "Drive 용량 초과": the one 403 that retrying cannot fix. It is judged on the body and
     * not the status, because a permission 403 looks identical from outside and stays on the
     * `DRIVE_REAUTH` / retry path. Every request goes through it — session start, chunk PUT,
     * multipart, `meta.json`, and the `transcribe` result files.
     */
    private fun storageFull(result: HttpResult, what: String): StepFailure? {
        if (result.status != 403) return null
        val body = result.body.decodeToString()
        val root = runCatching { driveJson.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        val errors = (root["error"] as? JsonObject)?.get("errors") as? JsonArray ?: return null
        if (errors.none { (it as? JsonObject)?.string("reason") == STORAGE_QUOTA_EXCEEDED }) return null
        return StepFailure(
            retryable = false,
            reason = CoreMessage.DRIVE_STORAGE_FULL.code(detail = "$what: ${body.take(200)}"),
            needsSpace = true,
        )
    }

    private fun check(result: HttpResult, what: String, allow404: Boolean) {
        if (result.status in 200..299) return
        if (allow404 && result.status == 404) return
        throw when {
            result.status == 401 -> StepFailure(
                retryable = false,
                reason = CoreMessage.NEEDS_AUTH.code(detail = "$what: unauthorized"),
                needsAuth = true,
            )

            result.status == 404 -> DriveNotFound("$what: HTTP 404 ${result.body.decodeToString().take(200)}")
            result.status == 408 || result.status == 429 || result.status >= 500 -> StepFailure(
                retryable = true,
                reason = CoreMessage.STEP_FAILED.code("$what: HTTP ${result.status}"),
                retryAfterSec = result.header("Retry-After")?.trim()?.toLongOrNull(),
            )

            else -> StepFailure(
                retryable = false,
                reason = CoreMessage.STEP_FAILED.code(
                    "$what: HTTP ${result.status} ${result.body.decodeToString().take(200)}",
                ),
            )
        }
    }

    /** The file a write answered with, or the retryable failure a body without an id is. */
    private fun file(what: String, result: HttpResult): DriveFile =
        DriveFile.from(result.json())
            ?: throw StepFailure(
                retryable = true,
                reason = CoreMessage.STEP_FAILED.code("$what: no id in response"),
            )

    private fun HttpResult.json(): JsonObject? =
        body.decodeToString().takeIf { it.isNotBlank() }
            ?.let { runCatching { driveJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    companion object {
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        internal const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
        internal const val FOLDER_FIELDS = "id,name,mimeType,webViewLink"
        internal const val CHILD_FIELDS = "files(id,name,md5Checksum,mimeType,webViewLink)"

        /** Drive returns only what is asked for: `description` is the title (docs/03 "제목"). */
        internal const val RECORDING_FOLDER_FIELDS = "files(id,name,appProperties,createdTime,description)"
        private const val MULTIPART_LIMIT = 5L * 1024 * 1024

        /** What Drive calls a full account in `error.errors[].reason`. */
        private const val STORAGE_QUOTA_EXCEEDED = "storageQuotaExceeded"
        private val SESSION_TTL = 7.days
    }
}

/**
 * Drive says a file or one of its parents is gone. Distinct from [StepFailure] because it is
 * often recoverable: a cached folder id that no longer resolves just has to be resolved again.
 * A caller that cannot recover turns it into a retryable [StepFailure].
 */
class DriveNotFound(message: String) : Exception(message)

internal fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
