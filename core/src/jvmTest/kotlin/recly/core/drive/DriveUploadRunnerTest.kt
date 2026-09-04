package recly.core.drive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import recly.core.job.StepFailure
import recly.core.model.Step
import recly.core.model.Track
import recly.core.testing.FakeDrive

class DriveUploadRunnerTest {
    @Test
    fun `uploads every part then the meta, into a folder it creates once`() = runBlocking {
        val h = DriveHarness()

        val output = h.run()

        assertEquals(
            listOf(h.partName(1), h.partName(2), h.partName(3), h.metaName()),
            h.drive.uploadOrder(),
        )
        // rec / 2026 / 2026-08 (Asia/Seoul) and then the recording's own folder.
        assertEquals(4, h.folderCreations().size)
        val folder = assertNotNull(h.drive.byName(h.base))
        assertEquals(FakeDrive.FOLDER_MIME, folder.mimeType)
        assertEquals("주간 회의", folder.description)
        assertEquals(
            mapOf("recordingId" to h.recordingId, "workflowId" to h.workflow.id),
            folder.appProperties,
        )
        h.sessionStarts().forEach {
            assertEquals("id,name,md5Checksum,webViewLink", it.query["fields"])
            assertEquals(DriveHarness.RESUMABLE_BYTES.toString(), it.headers["X-Upload-Content-Length"])
        }

        val json = output.json
        assertEquals(assertNotNull(h.drive.idOf(h.base)), json["folderId"]?.jsonPrimitive?.content)
        assertEquals("recly/2026/2026-08/${h.base}", json["path"]?.jsonPrimitive?.content)
        val files = assertNotNull(json["files"]).jsonArray
        assertEquals(4, files.size)
        assertEquals("mono", files[0].jsonObject["track"]?.jsonPrimitive?.content)
        assertEquals(1, files[0].jsonObject["part"]?.jsonPrimitive?.content?.toInt())
        assertEquals("meta", files[3].jsonObject["track"]?.jsonPrimitive?.content)
        assertEquals(0, files[3].jsonObject["part"]?.jsonPrimitive?.content?.toInt())
        assertEquals(h.metaName(), files[3].jsonObject["name"]?.jsonPrimitive?.content)
        files.forEach { assertNotNull(it.jsonObject["fileId"]) }

        // A second recording under the same path finds all three folders in the local cache.
        h.state = null
        val creationsBefore = h.folderCreations().size
        h.run()
        assertEquals(creationsBefore, h.folderCreations().size)
    }

    @Test
    fun `a 5xx mid-chunk saves the offset and the next run resumes from it`() = runBlocking {
        val h = DriveHarness(partCount = 2)
        val second = h.partName(2)
        h.drive.failNext(503) { r ->
            r.url.startsWith(FakeDrive.SESSION_PREFIX) &&
                h.drive.sessionName(r.url) == second &&
                h.drive.chunksSoFar(r.url) == 1
        }

        val failure = assertFailsWith<StepFailure> { h.run() }
        assertTrue(failure.retryable)

        val saved = DriveUploadState.from(h.state).files.getValue("p002_mono")
        assertNotNull(saved.sessionUri)
        assertEquals(1024L * 1024, saved.offset)
        assertNull(h.drive.byName(second))

        h.clearFaultsAndRerun()

        assertEquals(1, h.queryRequests().size)
        assertNotNull(h.drive.byName(second))
        assertNotNull(h.drive.byName(h.metaName()))
        // The part that had already finished is not sent again.
        assertEquals(1, h.drive.uploadOrder().count { it == h.partName(1) })
    }

    @Test
    fun `a session Drive has forgotten is started over`() = runBlocking {
        val h = DriveHarness(partCount = 1)
        h.drive.failNext(503) { r ->
            r.url.startsWith(FakeDrive.SESSION_PREFIX) && h.drive.chunksSoFar(r.url) == 1
        }
        assertFailsWith<StepFailure> { h.run() }
        h.drive.clearFaults()
        h.drive.failNext(404) { r -> r.headers["Content-Range"]?.startsWith("bytes */") == true }

        h.run()

        assertEquals(2, h.sessionStarts().count { it.body.decodeToString().contains(h.partName(1)) })
        assertEquals(
            FakeDrive.md5(h.fs.read(h.dir / h.partName(1)) { readByteArray() }),
            FakeDrive.md5(assertNotNull(h.drive.byName(h.partName(1))).content),
        )
    }

    @Test
    fun `a file that comes back with the wrong md5 is deleted and retried`() = runBlocking {
        val h = DriveHarness(partCount = 1, partBytes = DriveHarness.SMALL_BYTES)
        h.drive.corruptMd5 += h.partName(1)

        val failure = assertFailsWith<StepFailure> { h.run() }

        assertTrue(failure.retryable)
        assertTrue(failure.reason.contains("md5 mismatch"))
        assertEquals(1, h.drive.deleted.size)
        assertNull(h.drive.byName(h.partName(1)))

        h.drive.corruptMd5.clear()
        h.run()

        assertEquals(listOf(h.partName(1), h.partName(1), h.metaName()), h.drive.uploadOrder())
    }

    @Test
    fun `running twice uploads nothing the second time`() = runBlocking {
        val h = DriveHarness(partCount = 2, partBytes = DriveHarness.SMALL_BYTES)
        h.run()
        val uploads = h.drive.uploadOrder().size

        val output = h.run()

        assertEquals(uploads, h.drive.uploadOrder().size)
        assertEquals(3, assertNotNull(output.json["files"]).jsonArray.size)
    }

    @Test
    fun `a file that is already on Drive under the same name is adopted without a state entry`() = runBlocking {
        val h = DriveHarness(partCount = 1, partBytes = DriveHarness.SMALL_BYTES)
        h.run()
        // A fresh job for the same recording: no saved state, but Drive already has everything.
        h.state = null
        val uploads = h.drive.uploadOrder().size

        h.run()

        assertEquals(uploads, h.drive.uploadOrder().size)
    }

    @Test
    fun `a small file goes up in a single multipart request`() = runBlocking {
        val h = DriveHarness(partCount = 1, partBytes = DriveHarness.SMALL_BYTES)

        h.run()

        assertEquals(0, h.sessionStarts().size)
        assertEquals(2, h.multipartUploads().size)
        assertEquals(
            FakeDrive.md5(h.fs.read(h.dir / h.partName(1)) { readByteArray() }),
            FakeDrive.md5(assertNotNull(h.drive.byName(h.partName(1))).content),
        )
    }

    @Test
    fun `a 401 is retried once with the token invalidate produced`() = runBlocking {
        val h = DriveHarness(partCount = 1, partBytes = DriveHarness.SMALL_BYTES)
        // Only the post-invalidate token opens the door, so the retry cannot pass by accident.
        h.drive.acceptedToken = ScriptedTokenProvider.SECOND

        h.run()

        assertEquals(1, h.tokens.invalidations)
        val sent = h.drive.requests.map { it.headers["Authorization"] }
        assertEquals("Bearer ${ScriptedTokenProvider.FIRST}", sent.first())
        assertEquals("Bearer ${ScriptedTokenProvider.SECOND}", sent[1])
        assertEquals(h.drive.requests[0].url, h.drive.requests[1].url)
        assertTrue(sent.drop(1).all { it == "Bearer ${ScriptedTokenProvider.SECOND}" })
        assertEquals(listOf(h.partName(1), h.metaName()), h.drive.uploadOrder())
    }

    @Test
    fun `a refresh that changes nothing parks the job for sign-in`() = runBlocking {
        val h = DriveHarness(
            partCount = 1,
            partBytes = DriveHarness.SMALL_BYTES,
            tokens = ScriptedTokenProvider(rotates = false),
        )
        h.drive.acceptedToken = ScriptedTokenProvider.SECOND

        val failure = assertFailsWith<StepFailure> { h.run() }

        assertTrue(failure.needsAuth)
        assertFalse(failure.retryable)
        assertEquals(1, h.tokens.invalidations)
        assertEquals(2, h.drive.requests.size)
        assertTrue(h.drive.requests.all { it.headers["Authorization"] == "Bearer ${ScriptedTokenProvider.FIRST}" })
        assertTrue(h.drive.uploadOrder().isEmpty())
    }

    @Test
    fun `parts go up part number first, then track order`() = runBlocking {
        val h = DriveHarness(
            partCount = 2,
            partBytes = DriveHarness.SMALL_BYTES,
            tracks = listOf(Track.MIX, Track.SYS, Track.MIC),
        )

        h.run()

        assertEquals(
            listOf(
                h.partName(1, Track.MIC), h.partName(1, Track.SYS), h.partName(1, Track.MIX),
                h.partName(2, Track.MIC), h.partName(2, Track.SYS), h.partName(2, Track.MIX),
                h.metaName(),
            ),
            h.drive.uploadOrder(),
        )
    }

    @Test
    fun `includeMeta false leaves the meta on disk`() = runBlocking {
        val h = DriveHarness(partCount = 1, partBytes = DriveHarness.SMALL_BYTES)

        val output = h.run(Step.DriveUpload(id = "up", includeMeta = false))

        assertEquals(listOf(h.partName(1)), h.drive.uploadOrder())
        assertEquals(1, assertNotNull(output.json["files"]).jsonArray.size)
    }

    @Test
    fun `the local md5 is hashed once and kept on the part row`() = runBlocking {
        val h = DriveHarness(partCount = 1, partBytes = DriveHarness.SMALL_BYTES)
        h.register()

        h.run()

        assertEquals(
            FakeDrive.md5(h.fs.read(h.dir / h.partName(1)) { readByteArray() }),
            h.store.md5(h.recordingId, 1, Track.MONO),
        )
    }

    @Test
    fun `among same-name children the one whose md5 matches is adopted`() = runBlocking {
        val h = DriveHarness(partCount = 1, partBytes = DriveHarness.SMALL_BYTES)
        h.run()
        val adopted = h.decoyThenReal()
        val uploads = h.drive.uploadOrder().size
        h.state = null

        val output = h.run()

        assertEquals(uploads, h.drive.uploadOrder().size)
        assertEquals(adopted, output.fileId(0))
        // The stranger with the same name is not ours to delete.
        assertEquals(2, h.drive.files.values.count { it.name == h.partName(1) })
        assertTrue(h.drive.deleted.isEmpty())
    }

    @Test
    fun `a listing that spans several pages is read to the end`() = runBlocking {
        val h = DriveHarness(partCount = 1, partBytes = DriveHarness.SMALL_BYTES)
        h.run()
        val adopted = h.decoyThenReal()
        val uploads = h.drive.uploadOrder().size
        h.state = null
        h.drive.listPageSize = 1

        val output = h.run()

        assertTrue(h.drive.requests.any { it.query["pageToken"] != null })
        assertEquals(uploads, h.drive.uploadOrder().size)
        assertEquals(adopted, output.fileId(0))
    }

    @Test
    fun `a cleanup delete that fails is recorded and finished on the next run`() = runBlocking {
        val h = DriveHarness(partCount = 1, partBytes = DriveHarness.SMALL_BYTES)
        h.drive.corruptMd5 += h.partName(1)
        h.drive.failNext(503) { it.method == "DELETE" }

        val failure = assertFailsWith<StepFailure> { h.run() }

        assertTrue(failure.retryable)
        assertTrue(failure.reason.contains("503"))
        val pending = assertNotNull(DriveUploadState.from(h.state).files.getValue("p001_mono").pendingDelete)
        assertNotNull(h.drive.files[pending])

        h.drive.clearFaults()
        h.drive.corruptMd5.clear()
        h.run()

        assertTrue(h.drive.deleted.contains(pending))
        assertNull(DriveUploadState.from(h.state).files.getValue("p001_mono").pendingDelete)
        assertEquals(listOf(h.partName(1), h.partName(1), h.metaName()), h.drive.uploadOrder())
    }

    @Test
    fun `a cleanup delete that 404s has already happened`() = runBlocking {
        val h = DriveHarness(partCount = 1, partBytes = DriveHarness.SMALL_BYTES)
        h.drive.corruptMd5 += h.partName(1)
        h.drive.failNext(503) { it.method == "DELETE" }
        assertFailsWith<StepFailure> { h.run() }
        val pending = assertNotNull(DriveUploadState.from(h.state).files.getValue("p001_mono").pendingDelete)

        // Something else removed it in the meantime: the DELETE we resume with really does 404.
        h.drive.files.remove(pending)
        h.drive.clearFaults()
        h.drive.corruptMd5.clear()
        h.run()

        val cleanup = h.drive.requests.indexOfLast { it.method == "DELETE" && it.path.endsWith("/$pending") }
        assertEquals(404, h.drive.statusAt(cleanup))
        // Gone is gone: the intent is dropped before the upload runs, not left for the next run to
        // find. Only the resume can save that state — the successful upload writes a fileId.
        assertTrue(h.savedCleanSlateFor("p001_mono"))
        assertNull(DriveUploadState.from(h.state).files.getValue("p001_mono").pendingDelete)
        assertEquals(listOf(h.partName(1), h.partName(1), h.metaName()), h.drive.uploadOrder())
    }

    @Test
    fun `a folder deleted between runs is recreated and refilled`() = runBlocking {
        val h = DriveHarness(partCount = 1, partBytes = DriveHarness.SMALL_BYTES)
        h.run()
        val gone = assertNotNull(h.drive.idOf(h.base))
        h.drive.files.remove(gone)

        val output = h.run()

        val fresh = assertNotNull(h.drive.idOf(h.base))
        assertNotEquals(gone, fresh)
        assertEquals(fresh, output.json["folderId"]?.jsonPrimitive?.content)
        assertEquals(
            listOf(h.partName(1), h.metaName(), h.partName(1), h.metaName()),
            h.drive.uploadOrder(),
        )
    }

    @Test
    fun `a trashed folder counts as gone`() = runBlocking {
        val h = DriveHarness(partCount = 1, partBytes = DriveHarness.SMALL_BYTES)
        h.run()
        val binned = assertNotNull(h.drive.idOf(h.base))
        h.drive.trashed += binned

        val output = h.run()

        assertNotEquals(binned, output.json["folderId"]?.jsonPrimitive?.content)
        assertEquals(2, h.drive.files.values.count { it.name == h.base })
        assertEquals(
            listOf(h.partName(1), h.metaName(), h.partName(1), h.metaName()),
            h.drive.uploadOrder(),
        )
    }

    @Test
    fun `a cached path folder that Drive has lost is re-resolved once inside the run`() = runBlocking {
        val h = DriveHarness(partCount = 1, partBytes = DriveHarness.SMALL_BYTES)
        h.run()
        val month = assertNotNull(h.drive.idOf("2026-08"))
        h.drive.files.remove(assertNotNull(h.drive.idOf(h.base)))
        h.drive.files.remove(month)
        // A fresh job: nothing in the step state, but the folder cache still points at the dead id.
        h.state = null

        val output = h.run()

        assertNotEquals(month, assertNotNull(h.drive.idOf("2026-08")))
        assertEquals("recly/2026/2026-08/${h.base}", output.json["path"]?.jsonPrimitive?.content)
        assertEquals(2, h.drive.uploadOrder().count { it == h.partName(1) })
    }

    @Test
    fun `a parent that stays lost is retried exactly once, then handed to the backoff`() = runBlocking {
        val h = DriveHarness(partCount = 1, partBytes = DriveHarness.SMALL_BYTES)
        h.run()
        h.drive.files.remove(assertNotNull(h.drive.idOf(h.base)))
        h.drive.files.remove(assertNotNull(h.drive.idOf("2026-08")))
        h.state = null
        val creations = h.folderCreations().size
        val uploads = h.drive.uploadOrder().size
        // Creating a folder keeps failing, so invalidating the cache cannot rescue the run.
        h.drive.failNext(404, times = 99) { it.method == "POST" && it.path == "/drive/v3/files" }

        val failure = assertFailsWith<StepFailure> { h.run() }

        assertTrue(failure.retryable)
        assertTrue(failure.reason.contains("404"))
        // One attempt with the stale cache, one with a clean one — and no third.
        assertEquals(2, h.folderCreations().size - creations)
        assertEquals(uploads, h.drive.uploadOrder().size)
    }

    /** Replaces the uploaded part with a wrong-md5 stranger listed *before* a correct copy. */
    private fun DriveHarness.decoyThenReal(): String {
        val name = partName(1)
        val folder = assertNotNull(drive.idOf(base))
        val realId = assertNotNull(drive.idOf(name))
        val content = drive.files.getValue(realId).content
        drive.files.remove(realId)
        drive.put(name, folder, "not the recording".encodeToByteArray())
        return drive.put(name, folder, content)
    }

    private fun recly.core.job.StepOutput.fileId(index: Int): String? =
        json["files"]?.jsonArray?.get(index)?.jsonObject?.get("fileId")?.jsonPrimitive?.content

    private suspend fun DriveHarness.clearFaultsAndRerun() {
        drive.clearFaults()
        run()
    }
}
