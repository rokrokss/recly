package recly.core.drive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okio.fakefilesystem.FakeFileSystem
import recly.core.job.StepFailure
import recly.core.platform.HttpBody
import recly.core.platform.HttpPlan
import recly.core.testing.FakeDrive
import recly.core.testing.testDeps

/**
 * docs/08 "Result files": a transcript file's content and its `reclyTranscript` mark change in one
 * `files.update` — the multipart form on the upload URI — and the fake answers it the way Drive does.
 */
class TranscriptMarksTest {
    private val drive = FakeDrive()
    private val fs = FakeFileSystem()
    private val deps = testDeps(fileSystem = fs, tokenProvider = ScriptedTokenProvider(), transport = mockTransport(drive, fs))
    private val api = DriveApi(deps)

    @Test
    fun `an update with properties is one multipart PATCH whose metadata names no parents`() = runBlocking {
        val id = drive.put("a.transcript.txt", "root", "old".encodeToByteArray(), "text/plain")
        drive.files.getValue(id).appProperties = mapOf("kept" to "yes")

        api.updateMedia(id, "new".encodeToByteArray(), "text/plain", mapOf("reclyTranscript" to "edited"))

        val request = drive.requests.last()
        assertEquals("PATCH", request.method)
        assertEquals("/upload/drive/v3/files/$id", request.path)
        assertEquals("multipart", request.uploadType)
        assertTrue(request.contentType!!.startsWith("multipart/related; boundary="))
        val metadata = request.body.decodeToString().substringAfter("\r\n\r\n").substringBefore("\r\n--")
        assertEquals("""{"appProperties":{"reclyTranscript":"edited"}}""", metadata)
        assertEquals("new", drive.files.getValue(id).content.decodeToString())
        assertEquals(mapOf("kept" to "yes", "reclyTranscript" to "edited"), drive.files.getValue(id).appProperties, "merged, not replaced")
    }

    @Test
    fun `an update without properties stays the media-only form`() = runBlocking {
        val id = drive.put("a.meta.json", "root", "{}".encodeToByteArray(), "application/json")

        api.updateMedia(id, "{\"a\":1}".encodeToByteArray(), "application/json")

        assertEquals("media", drive.requests.last().uploadType)
    }

    @Test
    fun `a new file carries its properties in the create`() = runBlocking {
        api.multipartUpload(DriveFileMeta("b.transcript.json", listOf("root"), "application/json", mapOf("reclyTranscript" to "transcribed")), "{}".encodeToByteArray())

        assertEquals(mapOf("reclyTranscript" to "transcribed"), drive.byName("b.transcript.json")!!.appProperties)
    }

    @Test
    fun `the fake refuses what Drive refuses`() = runBlocking {
        val id = drive.put("c.txt", "root", "x".encodeToByteArray(), "text/plain")
        val boundary = "b0"
        val body = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n{\"parents\":[\"root\"]}\r\n--$boundary\r\nContent-Type: text/plain\r\n\r\ny\r\n--$boundary--"
        val moved = deps.transport.execute(
            HttpPlan(
                method = "PATCH",
                url = "https://www.googleapis.com/upload/drive/v3/files/$id?uploadType=multipart",
                headers = mapOf("Authorization" to "Bearer token1"),
                body = HttpBody.Bytes(body.encodeToByteArray(), "multipart/related; boundary=$boundary"),
            ),
        )
        assertEquals(403, moved.status, "parents are not writable in an update")
        assertEquals("x", drive.files.getValue(id).content.decodeToString())

        assertFailsWith<StepFailure> {
            api.updateMedia(id, "z".encodeToByteArray(), "text/plain", mapOf("k" to "v".repeat(130)))
        }
        assertFalse("k" in drive.files.getValue(id).appProperties, "a property over 124 bytes is refused")
    }
}
