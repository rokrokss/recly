@file:OptIn(ExperimentalTime::class)

package app.recly.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.recly.recording.RecorderEvent
import app.recly.recording.RecorderState
import app.recly.wear.data.WatchDefault
import app.recly.wear.transfer.TransferQueue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import recly.core.sync.WorkflowSummary

/**
 * What the watch screen shows. [recorder] is the state machine — Idle, Starting, Recording,
 * Stopping — and it lives in `RecorderService`, not here: a recording survives this ViewModel, the
 * activity and the process being swiped away, so the screen reads it rather than owning it.
 */
data class WearUiState(
    val recorder: RecorderState = RecorderState.Idle,
    /** What the phone last published. Every one of them is a watch can start (ADR-016). */
    val workflows: List<WorkflowSummary> = emptyList(),
    /**
     * This watch's own default (ADR-016), as far as the published list can still resolve it. Null is
     * "Default": the recording carries no pick and the phone runs the phone's own default.
     */
    val selectedWorkflowId: String? = null,
    val pending: Int = 0,
    /**
     * docs/11 W2: those [pending] recordings are going over right now. The badge says so instead of
     * "waiting", which is what the same count means with no phone in range.
     */
    val sending: Boolean = false,
    /** Recordings the phone refused outright. Their audio is still here — docs/11 W4. */
    val failed: Int = 0,
    val message: WearMessage? = null,
) {
    /** Only a settled recorder takes a tap; Starting and Stopping are both "wait". */
    val canStart: Boolean get() = recorder == RecorderState.Idle

    val canStop: Boolean get() = recorder is RecorderState.Recording

    val busy: Boolean get() = !canStart && !canStop

    val startedAt: Instant? get() = (recorder as? RecorderState.Recording)?.startedAt

    /**
     * docs/11 W2: the badge reads "sending" only while there is something in flight to say it
     * about — the last recording of a pass is removed from the queue before the pass ends, and
     * `전송 중 0개` would be the badge saying so.
     */
    val handingOver: Boolean get() = sending && pending > 0

    val selected: WorkflowSummary? get() = workflows.firstOrNull { it.id == selectedWorkflowId }
}

/**
 * What the line under the clock says about the recording that just ended. The ViewModel has no
 * `Context` — it is a plain `ViewModel`, so the whole of it runs on the JVM — so it names the
 * string and the screen looks it up in the watch's language (docs/07).
 */
sealed interface WearMessage {
    data class Saved(val parts: Int, val durationSec: Int) : WearMessage

    data object SaveDeferred : WearMessage

    data class Failed(val reason: String) : WearMessage

    data object MicDenied : WearMessage
}

/**
 * The screen's half of the watch app: the workflow pick, the two taps and the badge. The recording
 * belongs to `RecorderService` and the transfer to [TransferQueue], which this only reads; all it
 * decides is when a tap counts and what the user is told about the one that just stopped.
 *
 * Everything is injected because a watch is a bad place to find out that a state transition was
 * wrong — the whole of this runs on the JVM in `WearRecordingViewModelTest`.
 */
class WearRecordingViewModel(
    private val recorder: RecorderControl,
    workflows: Flow<List<WorkflowSummary>>,
    private val queue: TransferQueue,
    private val haptics: Haptics,
    /** ADR-016: this watch's own default, which outlives the process. */
    private val defaults: WatchDefault,
) : ViewModel() {

    private val _state = MutableStateFlow(WearUiState())
    val state: StateFlow<WearUiState> = _state.asStateFlow()

    /**
     * The stored pointer, kept apart from the state so a workflow the phone has temporarily stopped
     * publishing does not silently lose the user's choice: what the screen shows is this resolved
     * against the last publish, and it comes back when the workflow does.
     */
    private var stored: String? = defaults.read()

    init {
        viewModelScope.launch { recorder.state.collect { onRecorder(it) } }
        viewModelScope.launch { recorder.events.collect { onEvent(it) } }
        viewModelScope.launch { queue.pending.collect { count -> _state.update { it.copy(pending = count) } } }
        viewModelScope.launch { queue.failed.collect { count -> _state.update { it.copy(failed = count) } } }
        viewModelScope.launch { queue.sending.collect { now -> _state.update { it.copy(sending = now) } } }
        viewModelScope.launch { workflows.collect { onWorkflows(it) } }
    }

    /**
     * A pick the user made outlives a republish, but only while the published list still has it: the
     * phone can delete the workflow this watch was pointing at, and silently recording against a
     * workflow that is gone would be worse than falling back to "Default".
     */
    private fun onWorkflows(published: List<WorkflowSummary>) = _state.update {
        it.copy(workflows = published, selectedWorkflowId = resolved(published))
    }

    private fun resolved(published: List<WorkflowSummary>): String? =
        stored?.takeIf { id -> published.any { it.id == id } }

    private fun onRecorder(recorder: RecorderState) = _state.update { it.copy(recorder = recorder) }

    /** The picker's only write: this watch's default, stored now so the next launch starts on it. */
    fun selectWorkflow(id: String?) {
        stored = id
        defaults.write(id)
        _state.update { it.copy(selectedWorkflowId = resolved(it.workflows), message = null) }
    }

    /**
     * docs/11 W6: the haptic fires on the tap, not on the service confirming. The user has already
     * put their wrist down by then, and a start that fails says so through [RecorderEvent.Failed].
     */
    fun start() {
        if (!_state.value.canStart) return
        _state.update { it.copy(message = null) }
        haptics.click()
        recorder.start(_state.value.selectedWorkflowId)
    }

    fun stop() {
        if (!_state.value.canStop) return
        haptics.doubleClick()
        recorder.stop()
    }

    fun micDenied() = _state.update { it.copy(message = WearMessage.MicDenied) }

    /**
     * Only what the user is told. The recording itself is already the shell's business by the time
     * this arrives — `RecWearApp.onRecordingReady` put it on the transfer queue — and it has to be:
     * a recording stopped from the watch-face chip finishes with no screen alive to see the event,
     * and one this ViewModel queued would be one the queue never heard about.
     */
    private fun onEvent(event: RecorderEvent) {
        when (event) {
            // A deferred stop did not finalize: the parts are on disk, the meta is still open and
            // the next recovery scan is what finishes it. Nothing was handed over.
            is RecorderEvent.Finished -> _state.update {
                it.copy(
                    message = if (event.deferred) {
                        WearMessage.SaveDeferred
                    } else {
                        WearMessage.Saved(event.parts, event.durationSec.toInt())
                    },
                )
            }

            is RecorderEvent.Failed -> _state.update { it.copy(message = WearMessage.Failed(event.reason)) }
        }
    }
}
