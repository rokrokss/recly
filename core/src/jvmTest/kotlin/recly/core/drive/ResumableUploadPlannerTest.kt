package recly.core.drive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toPath
import recly.core.drive.ResumableUploadPlanner.Outcome
import recly.core.model.Platform
import recly.core.platform.HttpBody

class ResumableUploadPlannerTest {
    private val meta = DriveFileMeta(
        name = "20260826T010000Z_desktop_01J9ABCD_p001_mic.m4a",
        parents = listOf("1FolderId"),
        mimeType = "audio/mp4",
        appProperties = mapOf("recordingId" to "01J9ABCDEF0123456789ABCDEF"),
        description = "주간 회의",
    )

    @Test
    fun `startRequest posts the metadata and announces the upload`() {
        val plan = ResumableUploadPlanner.startRequest(meta, 12_582_912, "tok")

        assertEquals("POST", plan.method)
        assertEquals(
            "https://www.googleapis.com/upload/drive/v3/files" +
                "?uploadType=resumable&fields=id%2Cname%2Cmd5Checksum%2CwebViewLink",
            plan.url,
        )
        assertEquals("Bearer tok", plan.headers["Authorization"])
        assertEquals("audio/mp4", plan.headers["X-Upload-Content-Type"])
        assertEquals("12582912", plan.headers["X-Upload-Content-Length"])

        val body = assertIs<HttpBody.Text>(plan.body)
        assertEquals("application/json; charset=UTF-8", body.contentType)
        val json = driveJson.parseToJsonElement(body.text).jsonObject
        assertEquals(meta.name, json["name"]?.jsonPrimitive?.content)
        assertEquals("1FolderId", json["parents"]?.jsonArray?.single()?.jsonPrimitive?.content)
        assertEquals("주간 회의", json["description"]?.jsonPrimitive?.content)
        assertEquals(
            "01J9ABCDEF0123456789ABCDEF",
            json["appProperties"]?.jsonObject?.get("recordingId")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `chunkRequest spells out the byte range it is sending`() {
        val path = "/data/part.m4a".toPath()
        val first = ResumableUploadPlanner.chunkRequest("https://upload/session", 0, 8_388_608, 12_582_912, path, "t")

        assertEquals("PUT", first.method)
        assertEquals("https://upload/session", first.url)
        assertEquals("bytes 0-8388607/12582912", first.headers["Content-Range"])
        val body = assertIs<HttpBody.FileRange>(first.body)
        assertEquals(path, body.path)
        assertEquals(0, body.offset)
        assertEquals(8_388_608, body.length)
    }

    @Test
    fun `the last chunk may be any length`() {
        val plan = ResumableUploadPlanner.chunkRequest(
            sessionUri = "https://upload/session",
            offset = 8_388_608,
            length = 4_194_304 - 7,
            total = 12_582_912 - 7,
            path = "/data/part.m4a".toPath(),
            token = "t",
        )

        assertEquals("bytes 8388608-12582904/12582905", plan.headers["Content-Range"])
    }

    @Test
    fun `a non-final chunk must be a multiple of 256 KiB`() {
        assertFailsWith<IllegalArgumentException> {
            ResumableUploadPlanner.chunkRequest("u", 0, 1000, 12_582_912, "/p".toPath(), "t")
        }
    }

    @Test
    fun `queryRequest asks for the stored offset with an empty body`() {
        val plan = ResumableUploadPlanner.queryRequest("https://upload/session", 12_582_912, "t")

        assertEquals("PUT", plan.method)
        assertEquals("0", plan.headers["Content-Length"])
        assertEquals("bytes */12582912", plan.headers["Content-Range"])
        assertEquals(null, plan.body)
    }

    @Test
    fun `200 with a Location starts the session`() {
        val outcome = ResumableUploadPlanner.onResponse(
            200,
            mapOf("Location" to listOf("https://upload/session/abc")),
            "",
        )

        assertEquals(Outcome.SessionStarted("https://upload/session/abc"), outcome)
    }

    @Test
    fun `308 with a Range continues after the stored bytes`() {
        val outcome = ResumableUploadPlanner.onResponse(308, mapOf("Range" to listOf("bytes=0-1310719")), null)

        assertEquals(Outcome.Continue(1_310_720), outcome)
    }

    @Test
    fun `308 without a Range means nothing is stored yet`() {
        assertEquals(Outcome.Continue(0), ResumableUploadPlanner.onResponse(308, emptyMap(), null))
    }

    @Test
    fun `200 with a file body is done`() {
        val body = """{"id":"1AbC","name":"p.m4a","md5Checksum":"d41d8","webViewLink":"https://drive/x"}"""

        val outcome = ResumableUploadPlanner.onResponse(200, emptyMap(), body)

        assertEquals(Outcome.Done(DriveFile("1AbC", "p.m4a", "d41d8", "https://drive/x")), outcome)
    }

    @Test
    fun `201 with a file body is done too`() {
        val outcome = ResumableUploadPlanner.onResponse(201, emptyMap(), """{"id":"1AbC","name":"p.m4a"}""")

        assertEquals(Outcome.Done(DriveFile("1AbC", "p.m4a", null, null)), outcome)
    }

    @Test
    fun `404 and 410 restart the session`() {
        assertEquals(Outcome.Restart, ResumableUploadPlanner.onResponse(404, emptyMap(), null))
        assertEquals(Outcome.Restart, ResumableUploadPlanner.onResponse(410, emptyMap(), null))
    }

    @Test
    fun `5xx 429 and 408 ask for a retry, honouring Retry-After`() {
        assertEquals(Outcome.RetryAfter(null), ResumableUploadPlanner.onResponse(500, emptyMap(), null))
        assertEquals(Outcome.RetryAfter(null), ResumableUploadPlanner.onResponse(503, emptyMap(), null))
        assertEquals(Outcome.RetryAfter(null), ResumableUploadPlanner.onResponse(408, emptyMap(), null))
        assertEquals(
            Outcome.RetryAfter(120),
            ResumableUploadPlanner.onResponse(429, mapOf("Retry-After" to listOf("120")), null),
        )
    }

    @Test
    fun `an HTTP-date Retry-After is not a number of seconds and is ignored`() {
        assertEquals(
            Outcome.RetryAfter(null),
            ResumableUploadPlanner.onResponse(429, mapOf("Retry-After" to listOf("Wed, 26 Aug 2026 01:00:00 GMT")), null),
        )
    }

    @Test
    fun `401 is unauthorized and other 4xx fail`() {
        assertEquals(Outcome.Unauthorized, ResumableUploadPlanner.onResponse(401, emptyMap(), null))
        val fail = assertIs<Outcome.Fail>(ResumableUploadPlanner.onResponse(400, emptyMap(), """{"error":"bad"}"""))
        assertTrue(fail.reason.contains("400"))
    }

    @Test
    fun `chunk sizes are multiples of the 256 KiB unit`() {
        assertEquals(1L * 1024 * 1024, ResumableUploadPlanner.chunkSize(Platform.WEAROS))
        assertEquals(1L * 1024 * 1024, ResumableUploadPlanner.chunkSize(Platform.ANDROID))
        assertEquals(1L * 1024 * 1024, ResumableUploadPlanner.chunkSize(Platform.IOS))
        assertEquals(1L * 1024 * 1024, ResumableUploadPlanner.chunkSize(Platform.WATCHOS))
        assertEquals(8L * 1024 * 1024, ResumableUploadPlanner.chunkSize(Platform.MACOS))
        assertEquals(8L * 1024 * 1024, ResumableUploadPlanner.chunkSize(Platform.WINDOWS))
        Platform.entries.forEach {
            assertEquals(0, ResumableUploadPlanner.chunkSize(it) % ResumableUploadPlanner.CHUNK_UNIT)
        }
    }

    @Test
    fun `q values escape the quote and the backslash`() {
        assertEquals("""a\'b""", escapeQuery("a'b"))
        assertEquals("""a\\b""", escapeQuery("a\\b"))
        assertEquals("""\\\'""", escapeQuery("\\'"))
    }
}
