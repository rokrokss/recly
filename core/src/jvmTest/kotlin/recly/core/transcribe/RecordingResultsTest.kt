@file:OptIn(ExperimentalTime::class)

package recly.core.transcribe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import recly.core.drive.DriveApi
import recly.core.platform.CoreDeps
import recly.core.platform.HttpPlan
import recly.core.platform.HttpResult
import recly.core.platform.Transport
import recly.core.recording.RecordingRecord
import recly.core.testing.FakeDrive

/** M7-L3 deliverable 3: what the detail screen reads back, local copy first and Drive after. */
class RecordingResultsTest {

    private fun record(h: TranscribeHarness) = RecordingRecord(h.recordingId, h.meta, h.dir)

    /** `files.get?alt=media` — the only call that reads a file's bytes back out of Drive. */
    private val FakeDrive.downloads: Int get() = requests.count { it.query["alt"] == "media" }

    /** Runs the step, so the local copies and the Drive files are the ones a real job wrote. */
    private suspend fun ran(h: TranscribeHarness): List<JsonObject> = listOf(h.runToDone(h.transcribeStep()).json)

    @Test
    fun `the local copies are read without touching Drive`() = runBlocking {
        val h = TranscribeHarness()
        val outputs = ran(h)
        val downloadsBefore = h.drive.downloads

        val result = RecordingResults(h.api, h.deps).load(record(h), outputs)

        assertEquals(2, result.transcript?.speakers?.size, "two speakers came back")
        assertEquals("안녕하세요", result.transcript?.segments?.first()?.text)
        assertEquals(downloadsBefore, h.drive.downloads, "nothing was fetched: the copy is on disk")
    }

    @Test
    fun `a missing local copy is downloaded from Drive and kept`() = runBlocking {
        val h = TranscribeHarness()
        val outputs = ran(h)
        // What a device that did not run the step has: the recording, and no result next to it.
        h.fs.delete(h.dir / TranscribeRunner.jsonFileName(h.base))
        val downloadsBefore = h.drive.downloads

        val result = RecordingResults(h.api, h.deps).load(record(h), outputs)

        assertEquals(2, result.transcript?.speakers?.size)
        assertEquals(downloadsBefore + 1, h.drive.downloads, "one round trip per file")
        // The second opening is offline: the download was kept as the local copy (docs/08).
        val again = RecordingResults(h.api, h.deps).load(record(h), outputs)
        assertEquals(result, again)
        assertEquals(downloadsBefore + 1, h.drive.downloads)
    }

    /**
     * docs/08: the local copy is the truth. A `transcribe` rerun that finishes while a download is
     * in flight must not be overwritten by the older bytes that were already on the way.
     */
    @Test
    fun `a local copy written while the download is in flight is not overwritten`() = runBlocking {
        val h = TranscribeHarness()
        val outputs = ran(h)
        val name = TranscribeRunner.jsonFileName(h.base)
        h.fs.delete(h.dir / name)
        val downloadsBefore = h.drive.downloads
        val racing = RacingTransport(h.deps.transport) { h.fs.write(h.dir / name) { writeUtf8(RERUN) } }

        val result = RecordingResults(DriveApi(h.deps.with(racing)), h.deps).load(record(h), outputs)

        assertEquals(downloadsBefore + 1, h.drive.downloads, "the download did go out")
        assertEquals(
            "다시 실행",
            result.transcript?.segments?.single()?.text,
            "the newer local copy is what the screen shows",
        )
        assertEquals(RERUN, h.localContent(name), "and it is still what is on disk")
    }

    @Test
    fun `nothing local and nothing in the outputs is an empty screen, not a failure`() = runBlocking {
        val h = TranscribeHarness()

        val result = RecordingResults(h.api, h.deps).load(record(h), emptyList())

        assertNull(result.transcript)
    }

    private companion object {
        /** What a rerun wrote next to the recording while the older copy was being fetched. */
        val RERUN = """
            {"schema":1,"recordingId":"01J9ABCDEF0123456789ABCDEF","track":"mono","language":"ko",
             "provider":{"name":"assemblyai"},"createdAt":"2026-08-26T02:00:00.000Z","durationSec":1.0,
             "speakers":[{"id":"S1"}],
             "segments":[{"start":0.0,"end":1.0,"speaker":"S1","text":"다시 실행"}]}
        """.trimIndent()
    }
}

/** A Drive whose download takes long enough for something else to write the local copy. */
private class RacingTransport(
    private val inner: Transport,
    private val duringDownload: () -> Unit,
) : Transport {
    override suspend fun execute(plan: HttpPlan): HttpResult {
        if (plan.url.contains("alt=media")) duringDownload()
        return inner.execute(plan)
    }
}

/** [CoreDeps] is not a data class, and the transport is the one field this test has to swap. */
private fun CoreDeps.with(transport: Transport) = CoreDeps(
    clock = clock,
    logger = logger,
    secureStore = secureStore,
    tokenProvider = tokenProvider,
    transport = transport,
    fileSystem = fileSystem,
    audio = audio,
    dataDir = dataDir,
    device = device,
    appVersion = appVersion,
    io = io,
)
