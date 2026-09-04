@file:OptIn(ExperimentalTime::class)

package app.recly.windows.record

import app.recly.windows.SilentLogger
import app.recly.windows.core.AppModule
import app.recly.windows.detect.Detection
import app.recly.windows.helper.FakeHelperCommand
import app.recly.windows.helper.HelperClient
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path.Companion.toPath
import recly.core.ReclyCore
import recly.core.model.RecordingStatus
import recly.core.model.Track

/**
 * Deliverable 5 against the real database: what the helper reports is what `meta.json` and the row
 * end up saying, and a helper that dies still leaves a finalized recording behind — docs/14
 * "헬퍼가 죽으면 앱이 마지막 파트까지를 finalize한다".
 */
class WindowsRecorderTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `a stop files every part of every track and finalizes at the last one`() = runBlocking {
        val core = core()
        val finalized = CompletableDeferred<RecordingOutcome>()
        val recorder = recorder(core, finalized, "parts=2", "sec=2.0")

        val recordingId = assertNotNull(recorder.start(workflowId = null))
        recorder.stop()

        val outcome = withTimeout(TIMEOUT_MS) { finalized.await() }
        assertEquals(recordingId, outcome.recordingId)
        // Three tracks × two segments, and four seconds of audio — not twelve: the tracks cover the
        // same time (ADR-006).
        assertEquals(6, outcome.parts)
        assertEquals(4.0, outcome.durationSec)

        val record = assertNotNull(core.recordings.get(recordingId))
        assertEquals(RecordingStatus.FINALIZED, record.meta.status)
        assertEquals(4.0, record.meta.durationSec)
        assertEquals(listOf(Track.MIC, Track.SYS, Track.MIX), record.meta.tracks)
        assertEquals(6, record.meta.parts.size)
        // docs/03: `meta.json` is on disk next to the parts, whatever the database did.
        assertEquals(1, Files.list(java.nio.file.Path.of(record.dir.toString())).count())
    }

    @Test
    fun `a helper that dies mid-recording finalizes through its last part`() = runBlocking {
        val core = core()
        val finalized = CompletableDeferred<RecordingOutcome>()
        // One part per track, then the process exits without answering a stop.
        val recorder = recorder(core, finalized, "die", "sec=3.0")

        val recordingId = assertNotNull(recorder.start(workflowId = null))

        val outcome = withTimeout(TIMEOUT_MS) { finalized.await() }
        assertEquals(3, outcome.parts)
        assertEquals(3.0, outcome.durationSec, "the segment it never closed is not in the meta")
        assertEquals(
            RecordingStatus.FINALIZED,
            assertNotNull(core.recordings.get(recordingId)).meta.status,
        )
    }

    @Test
    fun `a part the database refused is marked and holds the finalize back`() = runBlocking {
        val core = core()
        val finalized = CompletableDeferred<RecordingOutcome>()
        // The parts arrive on the `stop`, so the row can be taken away in between — which is what
        // `addPart` failing looks like from in here, without a broken database to arrange.
        val recorder = recorder(core, finalized, "partsOnStop", "sec=2.0")

        val recordingId = assertNotNull(recorder.start(workflowId = null))
        val dir = assertNotNull(core.recordings.get(recordingId)).dir
        core.recordings.delete(recordingId)
        FileSystem.SYSTEM.createDirectories(dir)

        val result = recorder.stop()

        // docs/03: nothing is finalized while a part is on disk but not in the meta — a row that
        // says `finalized` is one nothing looks at the directory for again.
        val deferred = assertIs<StopResult.Deferred>(result)
        assertEquals(3, deferred.pending, "one marker per track")
        assertFalse(finalized.isCompleted, "a deferred stop has no outcome to enqueue")
        val markers = FileSystem.SYSTEM.list(dir).filter { it.name.endsWith(PartMarker.SUFFIX) }
        assertEquals(3, markers.size)
        // The marker carries what the row would have said, so recovery can register rather than
        // quarantine it.
        val part = assertNotNull(PartMarker.read(FileSystem.SYSTEM, markers.first()))
        assertEquals(2.0, part.durationSec)
        assertTrue(part.sha256.isNotEmpty())
    }

    @Test
    fun `a stop whose consumer is still running finalizes nothing`() = runBlocking {
        // The helper never answers the stop and never closes its stdout, so the app has to kill it
        // — and the reader is still filing what it flushed when the wait runs out. Finalizing there
        // would publish a meta over a consumer that is still writing to it.
        val core = core()
        val finalized = CompletableDeferred<RecordingOutcome>()
        val recorder = WindowsRecorder(
            core = core,
            scope = scope,
            helper = {
                HelperClient(FakeHelperCommand.command("hang", "sec=2.0"), Dispatchers.IO, SilentLogger)
            },
            onFinalized = { finalized.complete(it) },
            stopTimeout = Duration.ZERO,
            drainTimeout = Duration.ZERO,
        )

        val recordingId = assertNotNull(recorder.start(workflowId = null))
        val result = recorder.stop()

        val deferred = assertIs<StopResult.Deferred>(result)
        assertEquals(recordingId, deferred.recordingId)
        assertFalse(finalized.isCompleted)
        // Left open for `RecordingRecovery`, which is the one pass that may look at the directory.
        assertEquals(
            RecordingStatus.RECORDING,
            assertNotNull(core.recordings.get(recordingId)).meta.status,
        )
    }

    @Test
    fun `a part that could be neither filed nor marked still holds the finalize back`() = runBlocking {
        // The row and the directory are both gone, so `addPart` throws and the marker cannot be
        // written either. The part must not simply vanish from the recording.
        val core = core()
        val finalized = CompletableDeferred<RecordingOutcome>()
        val recorder = recorder(core, finalized, "partsOnStop", "sec=2.0")

        val recordingId = assertNotNull(recorder.start(workflowId = null))
        core.recordings.delete(recordingId)

        val deferred = assertIs<StopResult.Deferred>(recorder.stop())

        assertEquals(3, deferred.pending, "one per reported part, marker or no marker")
        assertFalse(finalized.isCompleted)
    }

    /**
     * `Detection`: the detect-only helper is closed **before** this one opens, and detection comes
     * back only once this one's stdout has ended. Two helpers alive at once would each report the
     * other's microphone as somebody else's meeting, and docs/14's sixty-second idle offer would never be
     * made (M6-L3).
     */
    @Test
    fun `detection is handed over before the helper opens and back when it has ended`() = runBlocking {
        val core = core()
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val detection = object : Detection {
            override suspend fun yieldToRecorder(): Long {
                order += "yield"
                return SESSION
            }

            override fun resume(token: Long) {
                order += if (token == SESSION) "resume" else "resume:stale"
            }

            override fun micInUse(token: Long, app: String, inUse: Boolean) {
                order += if (token == SESSION) "mic" else "mic:stale"
            }
        }
        val finalized = CompletableDeferred<RecordingOutcome>()
        val recorder = WindowsRecorder(
            core = core,
            scope = scope,
            helper = {
                HelperClient(
                    FakeHelperCommand.command("parts=1", "sec=1.0", "micInUse=Zoom.exe"),
                    Dispatchers.IO,
                    SilentLogger,
                )
            },
            onFinalized = { finalized.complete(it) },
            detection = detection,
        )

        assertNotNull(recorder.start(workflowId = null))
        recorder.stop()
        withTimeout(TIMEOUT_MS) { finalized.await() }

        // The `mic_in_use` in the middle is the proof that the helper whose events these are is the
        // one that opened between the two.
        assertEquals("yield", order.first())
        assertEquals("resume", order.last())
        assertTrue(order.contains("mic"), "the recording's helper is the one reporting")
        assertEquals(1, order.count { it == "yield" })
        assertEquals(1, order.count { it == "resume" })
        // Every call carries the token this session was handed (`Detection`).
        assertTrue(order.none { it.endsWith(":stale") })
    }

    private fun recorder(
        core: ReclyCore,
        finalized: CompletableDeferred<RecordingOutcome>,
        vararg helperArgs: String,
    ) = WindowsRecorder(
        core = core,
        scope = scope,
        helper = { HelperClient(FakeHelperCommand.command(*helperArgs), Dispatchers.IO, SilentLogger) },
        onFinalized = { finalized.complete(it) },
    )

    private suspend fun core() = AppModule.build(
        dataDir = Files.createTempDirectory("recly-recorder").toString().toPath(),
    ).core

    private companion object {
        const val TIMEOUT_MS = 60_000L

        /** The token the fake `Detection` hands this session; anything else is another session's. */
        const val SESSION = 7L
    }
}
