@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package app.recly.wear.ui

import app.recly.recording.RecorderEvent
import app.recly.recording.RecorderState
import app.recly.wear.transfer.TransferQueue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

private class FakeRecorder : RecorderControl {
    private val _state = MutableStateFlow<RecorderState>(RecorderState.Idle)
    override val state: StateFlow<RecorderState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RecorderEvent>(extraBufferCapacity = 8)
    override val events: SharedFlow<RecorderEvent> = _events.asSharedFlow()

    var starts: Int = 0
    var stops: Int = 0

    override fun start() {
        starts++
        // What the service does: Starting first, then Recording once the microphone is open.
        _state.value = RecorderState.Starting
    }

    override fun stop() {
        stops++
        _state.value = RecorderState.Stopping
    }

    fun recording(id: String = "01J9") {
        _state.value = RecorderState.Recording(id, Instant.fromEpochSeconds(0))
    }

    fun idle() {
        _state.value = RecorderState.Idle
    }

    suspend fun emit(event: RecorderEvent) = _events.emit(event)
}

private class FakeQueue : TransferQueue {
    private val _pending = MutableStateFlow(0)
    override val pending: StateFlow<Int> = _pending.asStateFlow()

    val failedCount: MutableStateFlow<Int> = MutableStateFlow(0)
    override val failed: StateFlow<Int> = failedCount.asStateFlow()

    val sendingNow: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val sending: StateFlow<Boolean> = sendingNow.asStateFlow()

    val added: MutableList<String> = mutableListOf()

    override suspend fun add(recordingId: String) {
        added += recordingId
        _pending.value = added.size
    }

    override suspend fun reconcile() = Unit
}

private class FakeHaptics : Haptics {
    val fired: MutableList<String> = mutableListOf()

    override fun click() {
        fired += "click"
    }

    override fun doubleClick() {
        fired += "doubleClick"
    }
}

/**
 * The whole of the watch screen's decision-making, on the JVM. It is worth this much test for one
 * reason: on a watch, a start that quietly did not happen looks exactly like one that did, and the
 * user finds out three hours later.
 */
class WearRecordingViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val recorder = FakeRecorder()
    private val queue = FakeQueue()
    private val haptics = FakeHaptics()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = WearRecordingViewModel(recorder, queue, haptics)

    @Test
    fun `idle takes a start, and a start is a click`() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()

        assertTrue(vm.state.value.canStart)
        vm.start()

        assertEquals(1, recorder.starts)
        assertEquals(listOf("click"), haptics.fired)
    }

    @Test
    fun `starting and stopping are both busy — neither takes a tap`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.start()
        runCurrent()

        // Starting: the recording id does not exist yet, so there is nothing to stop.
        assertTrue(vm.state.value.busy)
        assertFalse(vm.state.value.canStart)
        assertFalse(vm.state.value.canStop)

        vm.start()
        vm.stop()
        assertEquals(1, recorder.starts)
        assertEquals(0, recorder.stops)
    }

    @Test
    fun `a second stop is ignored while the first one is finalizing`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.start()
        recorder.recording()
        runCurrent()

        assertTrue(vm.state.value.canStop)
        vm.stop()
        runCurrent()
        vm.stop()

        assertEquals(1, recorder.stops)
        assertEquals(listOf("click", "doubleClick"), haptics.fired)
    }

    @Test
    fun `the recorder coming back to idle makes the button a start again`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.start()
        recorder.recording()
        runCurrent()
        vm.stop()
        runCurrent()
        recorder.idle()
        runCurrent()

        assertTrue(vm.state.value.canStart)
    }

    /**
     * The screen says what happened and queues nothing. `RecWearApp.onRecordingReady` has already
     * put the recording on the transfer queue by the time this event lands — which is the point: a
     * recording stopped from the watch-face chip finishes with no screen alive to see it.
     */
    @Test
    fun `a finished recording is reported, not queued`() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()

        recorder.emit(RecorderEvent.Finished("01J9REC", durationSec = 61.0, parts = 2, silenced = emptyList(), enqueue = true))
        runCurrent()

        assertEquals(emptyList(), queue.added)
        assertEquals(WearMessage.Saved(parts = 2, durationSec = 61), vm.state.value.message)
    }

    /** A deferred stop did not finalize: the meta is still open, so there is nothing to send yet. */
    @Test
    fun `a deferred finish says so`() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()

        recorder.emit(
            RecorderEvent.Finished("01J9REC", durationSec = 0.0, parts = 0, silenced = emptyList(), enqueue = true, deferred = true),
        )
        runCurrent()

        assertEquals(emptyList(), queue.added)
        assertEquals(WearMessage.SaveDeferred, vm.state.value.message)
    }

    /** The badge is the one thing the screen does read off the queue. */
    @Test
    fun `the badge follows the queue`() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()

        queue.add("01J9REC")
        runCurrent()

        assertEquals(1, vm.state.value.pending)
    }

    /**
     * docs/11 W2: the same count, said two ways. A pass that found a phone is handing the recording
     * over now; a queue nobody is sending is waiting on a worker Samsung may have parked.
     */
    @Test
    fun `the badge says sending only while a pass has a phone`() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        queue.add("01J9REC")
        runCurrent()

        assertFalse(vm.state.value.handingOver, "nothing is being sent yet")

        queue.sendingNow.value = true
        runCurrent()

        assertTrue(vm.state.value.sending)
        assertTrue(vm.state.value.handingOver)

        // The pass ended: the recording either went over — and the count with it — or it is
        // waiting again.
        queue.sendingNow.value = false
        runCurrent()

        assertFalse(vm.state.value.handingOver)
    }

    /** The last recording of a pass leaves the queue before the pass ends: no `전송 중 0개`. */
    @Test
    fun `an empty queue is never sending`() = runTest(dispatcher) {
        val vm = viewModel()
        queue.sendingNow.value = true
        runCurrent()

        assertTrue(vm.state.value.sending)
        assertFalse(vm.state.value.handingOver)
    }

    @Test
    fun `a failed recording says so and queues nothing`() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()

        recorder.emit(RecorderEvent.Failed("01J9REC", "mic busy"))
        runCurrent()

        assertEquals(emptyList(), queue.added)
        assertEquals(WearMessage.Failed("mic busy"), vm.state.value.message)
    }

    @Test
    fun `a denied microphone is said out loud, not swallowed`() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()

        vm.micDenied()
        assertEquals(WearMessage.MicDenied, vm.state.value.message)

        // The next start clears it: a stale refusal over a running recording would be a lie.
        vm.start()
        assertNull(vm.state.value.message)
    }
}
