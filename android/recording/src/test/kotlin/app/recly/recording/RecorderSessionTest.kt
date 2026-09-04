@file:OptIn(ExperimentalTime::class)

package app.recly.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.fakefilesystem.FakeFileSystem
import recly.core.model.Range

private const val ID = "01M10N83M5TAQ396AT8F9PGWFX"

/** What the recorder does at the boundary the session drives it across. */
private class FakeCapture(private val result: StopResult) : Capture {
    var stops: Int = 0

    override suspend fun stop(title: String?): StopResult {
        stops++
        return result
    }
}

private fun finalized(parts: Int = 2, durationSec: Double = 61.0) =
    StopResult.Finalized(RecordingOutcome(ID, durationSec, parts, emptyList<Range>()))

/**
 * The service's state machine without the service. Two things are being pinned down here and both
 * cost the user a recording when they are wrong: who a finished recording is handed to (never
 * `enqueue` from in here — docs/11 "주의" means the same finalize has to mean a job on the phone and
 * a transfer on the watch), and what a stop does when it lands while the microphone is still
 * opening, which on a watch is one impatient double-tap away.
 */
class RecorderSessionTest {

    private val core = testCore(FakeFileSystem())
    private val host = TestHost(core, enqueues = false)
    private val state = MutableStateFlow<RecorderState>(RecorderState.Idle)
    private val events = MutableSharedFlow<RecorderEvent>(extraBufferCapacity = 8)
    private var idles = 0

    // Unconfined: the finalize runs inline, so a test reads the outcome on the line after the stop.
    private val session = RecorderSession(
        host = host,
        state = state,
        events = events,
        completionScope = CoroutineScope(Dispatchers.Unconfined),
        onIdle = { idles++ },
    )

    private fun record(capture: Capture) {
        assertTrue(session.begin())
        session.started(capture, ID, Instant.parse("2026-08-26T01:00:00.000Z"), null)
    }

    @Test
    fun `the phone's own stop hands the recording over with the job held back`() = runBlocking {
        val capture = FakeCapture(finalized())
        record(capture)

        session.stop(title = null, enqueue = false)

        assertEquals(listOf(ID to false), host.ready)
        assertEquals(RecorderState.Idle, state.value)
        assertEquals(1, capture.stops)
        assertEquals(1, idles)
    }

    @Test
    fun `the notification's stop hands it over ready`() = runBlocking {
        record(FakeCapture(finalized()))

        session.stop(title = null, enqueue = true)

        assertEquals(listOf(ID to true), host.ready)
        assertEquals(RecorderState.Idle, state.value)
    }

    /** A fatal recorder error has nobody to ask for a title either. */
    @Test
    fun `a fatal error stops the recording and hands it over ready`() = runBlocking {
        record(FakeCapture(finalized()))

        session.failed(ID, "mic died")

        assertEquals(listOf(ID to true), host.ready)
        assertEquals(RecorderState.Idle, state.value)
        assertEquals(1, idles)
    }

    /**
     * The bug this pins: a stop that arrives while the microphone is opening used to leave the
     * state at Starting and call `stopSelf`, which makes the button dead until the process is
     * killed — and the recording it abandoned kept running.
     */
    @Test
    fun `a stop that arrives while the microphone is opening is served when it opens`() = runBlocking {
        val capture = FakeCapture(finalized())
        assertTrue(session.begin())

        session.stop(title = null, enqueue = false)

        // Nothing yet: there is no recording to finalize, and no reason to give up the service.
        assertEquals(RecorderState.Starting, state.value)
        assertEquals(emptyList(), host.ready)
        assertEquals(0, idles)

        session.started(capture, ID, Instant.parse("2026-08-26T01:00:00.000Z"), null)

        assertEquals(1, capture.stops)
        assertEquals(listOf(ID to false), host.ready)
        assertEquals(RecorderState.Idle, state.value)
    }

    @Test
    fun `a start that fails after a stop was asked for finalizes nothing`() = runBlocking {
        assertTrue(session.begin())
        session.stop(title = null, enqueue = true)

        session.startFailed()

        assertEquals(RecorderState.Idle, state.value)
        assertEquals(emptyList(), host.ready)
        // And the recorder is free again: the next tap is a start, not a wedged button.
        assertTrue(session.begin())
    }

    @Test
    fun `nothing starts while something is already running`() {
        record(FakeCapture(finalized()))

        assertFalse(session.begin())
    }

    /** A second tap lands on the button and the notification action alike; one finalize only. */
    @Test
    fun `a second stop is ignored`() = runBlocking {
        val capture = FakeCapture(finalized())
        record(capture)

        session.stop(title = null, enqueue = true)
        session.stop(title = null, enqueue = true)

        assertEquals(1, capture.stops)
        assertEquals(1, host.ready.size)
    }

    /** The stop job must not outlive its stop: a second recording has to be stoppable too. */
    @Test
    fun `a second recording can be stopped after the first`() = runBlocking {
        val first = FakeCapture(finalized())
        record(first)
        session.stop(title = null, enqueue = true)
        assertEquals(RecorderState.Idle, state.value)

        val second = FakeCapture(finalized())
        record(second)
        session.stop(title = null, enqueue = true)

        assertEquals(1, first.stops)
        assertEquals(1, second.stops, "the second stop must not be refused by a stale stop job")
        assertEquals(2, host.ready.size)
        assertEquals(RecorderState.Idle, state.value)
    }

    /** Not finalized: the parts could not be filed, so there is nothing to hand over yet. */
    @Test
    fun `a deferred stop hands nothing over`() = runBlocking {
        record(FakeCapture(StopResult.Deferred(ID, pending = 1)))

        session.stop(title = null, enqueue = true)

        assertEquals(emptyList(), host.ready)
        assertEquals(RecorderState.Idle, state.value)
    }

    /** What the UI reads: the flag the stop was made with, so the phone knows to ask for a title. */
    @Test
    fun `the finish event carries what the stop asked for`() = runBlocking {
        val seen = mutableListOf<RecorderEvent>()
        // The flow has no replay: the collector has to be up before the emit.
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { events.collect { seen += it } }
        record(FakeCapture(finalized(parts = 3, durationSec = 12.5)))

        session.stop(title = null, enqueue = false)
        collector.cancel()

        assertEquals(
            listOf<RecorderEvent>(RecorderEvent.Finished(ID, 12.5, 3, emptyList(), enqueue = false)),
            seen,
        )
    }
}
