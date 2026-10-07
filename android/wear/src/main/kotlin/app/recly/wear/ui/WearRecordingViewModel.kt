@file:OptIn(ExperimentalTime::class)

package app.recly.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.recly.recording.RecorderEvent
import app.recly.recording.RecorderState
import app.recly.wear.transfer.TransferQueue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the watch screen shows. [recorder] is the state machine — Idle, Starting, Recording,
 * Stopping — and it lives in `RecorderService`, not here: a recording survives this ViewModel, the
 * activity and the process being swiped away, so the screen reads it rather than owning it.
 */
data class WearUiState(
    val recorder: RecorderState = RecorderState.Idle,
    val pending: Int = 0,
    /**
     * docs/11 W2: those [pending] recordings are going over right now. The badge says so instead of
     * "waiting", which is what the same count means with no phone in range.
     */
    val sending: Boolean = false,
    /** Recordings the phone refused outright. Their audio is still here — docs/11 W4. */
    val failed: Int = 0,
    val message: WearMessage? = null,
    /** The second the Highlight button just marked, for the two seconds the status line says so. */
    val highlightedSec: Long? = null,
) {
    /** Only a settled recorder takes a tap; Starting and Stopping are both "wait". */
    val canStart: Boolean get() = recorder == RecorderState.Idle

    val canStop: Boolean get() = recorder is RecorderState.Recording

    val busy: Boolean get() = !canStart && !canStop

    val startedAt: Instant? get() = (recorder as? RecorderState.Recording)?.startedAt

    /**
     * docs/11 W2: the badge reads "sending" only while there is something in flight to say it
     * about — the last recording of a pass is removed from the queue before the pass ends, and
     * `Sending` over an empty queue would be the badge saying so.
     */
    val handingOver: Boolean get() = sending && pending > 0
}

/**
 * What the line under the clock says about the recording that just ended — only when there is news:
 * a stop that went as asked is not announced (docs/09 §7). The ViewModel has no `Context` — it is a
 * plain `ViewModel`, so the whole of it runs on the JVM — so it names the string and the screen
 * looks it up in the watch's language (docs/07).
 */
sealed interface WearMessage {
    data object SaveDeferred : WearMessage

    data class Failed(val reason: String) : WearMessage

    data object MicDenied : WearMessage
}

/**
 * The screen's half of the watch app: the two taps and the badge. The recording
 * belongs to `RecorderService` and the transfer to [TransferQueue], which this only reads; all it
 * decides is when a tap counts and what the user is told about the one that just stopped.
 *
 * Everything is injected because a watch is a bad place to find out that a state transition was
 * wrong — the whole of this runs on the JVM in `WearRecordingViewModelTest`.
 */
class WearRecordingViewModel(
    private val recorder: RecorderControl,
    private val queue: TransferQueue,
    private val haptics: Haptics,
) : ViewModel() {

    private val _state = MutableStateFlow(WearUiState())
    val state: StateFlow<WearUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { recorder.state.collect { onRecorder(it) } }
        viewModelScope.launch { recorder.events.collect { onEvent(it) } }
        viewModelScope.launch { queue.pending.collect { count -> _state.update { it.copy(pending = count) } } }
        viewModelScope.launch { queue.failed.collect { count -> _state.update { it.copy(failed = count) } } }
        viewModelScope.launch { queue.sending.collect { now -> _state.update { it.copy(sending = now) } } }
    }

    private fun onRecorder(recorder: RecorderState) = _state.update {
        it.copy(recorder = recorder, highlightedSec = it.highlightedSec.takeIf { recorder is RecorderState.Recording })
    }

    /**
     * docs/11 W6: the haptic fires on the tap, not on the service confirming. The user has already
     * put their wrist down by then, and a start that fails says so through [RecorderEvent.Failed].
     */
    fun start() {
        if (!_state.value.canStart) return
        _state.update { it.copy(message = null) }
        haptics.click()
        recorder.start()
    }

    fun stop() {
        if (!_state.value.canStop) return
        haptics.doubleClick()
        recorder.stop()
    }

    fun micDenied() = _state.update { it.copy(message = WearMessage.MicDenied) }

    /** Marks kept so far: only the newest one's two seconds end the news. */
    private var marks = 0

    /**
     * docs/03 "Metadata": marks this moment of the running recording. A mark the core kept is said on the
     * status line for [HIGHLIGHT_NEWS_MS], then the line goes back to its own rule.
     */
    fun highlight(now: Instant = Clock.System.now()) {
        val recording = _state.value.recorder as? RecorderState.Recording ?: return
        val atSec = (now - recording.startedAt).inWholeMilliseconds / 1000.0
        viewModelScope.launch {
            if (!recorder.highlight(recording.recordingId, atSec)) return@launch
            // A slow write may land after that recording stopped and another started: its time is not the new one's news.
            if ((_state.value.recorder as? RecorderState.Recording)?.recordingId != recording.recordingId) return@launch
            val mark = ++marks
            _state.update { it.copy(highlightedSec = atSec.toLong()) }
            delay(HIGHLIGHT_NEWS_MS)
            if (mark == marks) _state.update { it.copy(highlightedSec = null) }
        }
    }

    /**
     * Only what the user is told. The recording itself is already the shell's business by the time
     * this arrives — `RecWearApp.onRecordingReady` put it on the transfer queue — and it has to be:
     * a recording stopped from the watch-face chip finishes with no screen alive to see the event,
     * and one this ViewModel queued would be one the queue never heard about.
     */
    private fun onEvent(event: RecorderEvent) {
        when (event) {
            // A deferred stop did not finalize: the parts are on disk, the meta is still open and
            // the next recovery scan is what finishes it. Nothing was handed over. A stop that did
            // finalize says nothing, and leaves standing the failure that may have caused it — the
            // line goes on to the count of what is still on this watch.
            is RecorderEvent.Finished -> if (event.deferred) {
                _state.update { it.copy(message = WearMessage.SaveDeferred) }
            }

            is RecorderEvent.Failed -> _state.update { it.copy(message = WearMessage.Failed(event.reason)) }
        }
    }
}

/** docs/09 §7: how long the status line says what the Highlight button marked. */
internal const val HIGHLIGHT_NEWS_MS: Long = 2_000
