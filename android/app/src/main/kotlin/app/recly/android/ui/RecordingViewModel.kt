@file:OptIn(ExperimentalTime::class)

package app.recly.android.ui

import android.app.Application
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.recly.android.R
import app.recly.android.core.CoreModule
import app.recly.android.core.UiMessage
import app.recly.android.settings.AppSettings
import app.recly.android.work.WorkScheduler
import app.recly.recording.RecorderEvent
import app.recly.recording.RecorderService
import app.recly.recording.RecorderState
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import recly.core.ReclyCore
import recly.core.job.EnqueueResult
import recly.core.sync.WorkflowRepository
import recly.core.sync.WorkflowSummary

/** A recording that has stopped and is waiting to be named before it is queued. */
data class UntitledRecording(
    val recordingId: String,
    val durationSec: Double,
    val parts: Int,
)

data class RecordingUiState(
    val workflows: List<WorkflowSummary> = emptyList(),
    /**
     * ADR-016: the workflow this phone runs, which is its own local pointer and not a field of the
     * document. It is a mirror of that pointer and never a pick of its own — the picker writes the
     * pointer, and this arrives back from it. Null when this phone has no pointer, or one the
     * document no longer resolves; both are "choose one", and the screen says so.
     */
    val selectedWorkflowId: String? = null,
    /** Non-null while the title dialog is up; the recording is already finalized on disk. */
    val untitled: UntitledRecording? = null,
    /**
     * When an entry point (tile, widget, shortcut) asked for a recording, on the monotonic clock —
     * null when none has. See [RecordingViewModel.requestAutoStart] and [autoStartStillWanted].
     */
    val autoStart: Long? = null,
    /** docs/12 M8: true while the consent reminder is up, and the recording is waiting on it. */
    val consentPrompt: Boolean = false,
    /**
     * What just happened, as names rather than sentences — a stop reports two or three things at
     * once, and this ViewModel outlives the screen the language setting recreates (docs/07).
     */
    val messages: List<UiMessage> = emptyList(),
    /**
     * docs/13 deliverable 1: the microphone was asked for and refused. It is kept here rather than
     * on the screen because a tab the user has left is taken out of the composition and everything
     * it remembered goes with it — and the refusal outlives the visit to the list. What ends it is
     * the permission itself, which the screen re-reads on every resume ([micGranted]).
     */
    val micRefused: Boolean = false,
)

/**
 * The recording screen's half of `MainActivity`. It owns the workflow pick and the title prompt;
 * the recording itself belongs to [RecorderService], which outlives this ViewModel.
 *
 * The order matters and is the lead's call: Stop stops and finalizes straight away, and only then
 * is the user asked for a title. Naming it first would keep the microphone open for as long as the
 * user thought about it. The queue waits for the answer, because a job reads the meta and the
 * title has to be in it — and if this process dies while the dialog is up, `RecordingRecovery`
 * enqueues the finalized-but-jobless recording next time round.
 */
class RecordingViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = AppSettings(application)

    private val _state = MutableStateFlow(RecordingUiState())
    val state: StateFlow<RecordingUiState> = _state.asStateFlow()

    val recorder: StateFlow<RecorderState> = RecorderService.state

    init {
        viewModelScope.launch {
            // ADR-016: every workflow the document has is offered — a definition says nothing about
            // which device may run it — and the one the picker shows as selected is this phone's
            // own pointer, which is what a Start runs (docs/09 화면 원칙 1). The pointer is not in
            // the document, so both are watched.
            //
            // The first read seeds the docs/05 starters on a phone that has never had a document,
            // and points this phone at 메모: what a phone records is far more often one.
            val workflows = core().workflows
            workflows.seed(WorkflowRepository.MEMO_ID)
            combine(workflows.observe(), workflows.observeDeviceDefault()) { document, default ->
                document.workflows.map { WorkflowSummary(it.id, it.name) } to default
            }.collect { (summaries, selected) ->
                _state.update { it.copy(workflows = summaries, selectedWorkflowId = selected) }
            }
        }
        viewModelScope.launch {
            RecorderService.events.collect { event -> onEvent(event) }
        }
    }

    /**
     * ADR-016: the picker's one control. Choosing a workflow moves this phone's pointer — nothing
     * is written to the document, and the state is not touched here: `observeDeviceDefault` says
     * what the pointer is now, and the screen reads it from there.
     */
    fun selectWorkflow(id: String) {
        viewModelScope.launch { core().workflows.setDeviceDefault(id) }
    }

    /**
     * docs/12 M8 · ADR-011: a local capture shows the other participants nothing at all, so the app
     * says so once before it starts recording for the first time. The Mac asks before every meeting
     * recording; a phone cannot tell a meeting from anything else, so the trigger is the first
     * recording and the setting says as much.
     *
     * The store is read here rather than kept in a field: a tap can land before the first value of
     * a `DataStore` flow has arrived, and defaulting to "ask" would put the dialog in front of
     * someone who had already turned it off.
     */
    fun start() {
        _state.update { it.copy(messages = emptyList()) }
        // Asked before the reminder as well as after it: a disconnect that is running would only
        // have to refuse the answer, and a question whose answer cannot count is not worth asking.
        if (refusedByDisconnect()) return
        viewModelScope.launch {
            if (settings.askConsent.first()) {
                _state.update { it.copy(consentPrompt = true) }
            } else {
                begin()
            }
        }
    }

    /**
     * The reminder's answer. [suppress] is the "do not ask again" box, which — as on the Mac —
     * applies whichever button was pressed; only [confirmed] starts the recording, because this is
     * a question and Cancel has to mean something.
     */
    fun consentAnswered(confirmed: Boolean, suppress: Boolean) {
        _state.update { it.copy(consentPrompt = false) }
        viewModelScope.launch {
            if (suppress) settings.setConsentReminder(false)
            if (confirmed) {
                settings.markConsentAsked()
                begin()
            }
        }
    }

    private fun begin() {
        if (refusedByDisconnect()) return
        // ADR-016: no workflow is chosen for the recording — the phone's own pointer decides when
        // the recording is enqueued, and the picker is what moved it (`WorkflowSelector.select`).
        RecorderService.start(getApplication(), workflowId = null)
    }

    /**
     * docs/03: no recording starts while a disconnect is running — its "also delete the recordings"
     * walks the directory this one would be writing into, and a capture has no job yet, so the
     * core's own `Busy` guard would not see it.
     *
     * @return whether the start was refused, having said so.
     */
    private fun refusedByDisconnect(): Boolean {
        val blocked = DisconnectGate.startBlocker() ?: return false
        _state.update { it.copy(messages = listOf(blocked)) }
        return true
    }

    /** Stops now. The job is held back until [saveTitle] or [skipTitle] answers the prompt. */
    fun stop() = RecorderService.stop(getApplication(), title = null, enqueue = false)

    /**
     * docs/11 A9. A while-in-use foreground service may only be started from something the user can
     * see, so the tile, the widget and the launcher shortcut all open this activity and land here
     * rather than touching `RecorderService` themselves. The flag lives on the ViewModel, not on the
     * intent: the ViewModel survives rotation and the intent is redelivered, so consuming it here is
     * what makes one tap mean exactly one recording.
     *
     * @param requestedAt the tap itself, on the monotonic clock — `MainActivity.EXTRA_REQUESTED_AT`
     * when the entry point could stamp it, and the moment the activity read the intent when it
     * could not. Stamping it here instead measured the age of the request from the wrong end: the
     * cold start the user was waiting through was free, and [autoStartStillWanted] could never see
     * a tap that had gone stale before the app was even up.
     */
    fun requestAutoStart(requestedAt: Long) = _state.update { it.copy(autoStart = requestedAt) }

    /**
     * Spends the request whichever way this goes — one tap is one recording, now, or none at all.
     *
     * @return whether it is still worth acting on (see [autoStartStillWanted]).
     */
    fun consumeAutoStart(): Boolean {
        val requestedAt = _state.value.autoStart ?: return false
        _state.update { it.copy(autoStart = null) }
        return autoStartStillWanted(requestedAt, SystemClock.elapsedRealtime())
    }

    /** The user went somewhere else in the app: a tap they have moved on from is not a recording. */
    fun dropAutoStart() = _state.update { it.copy(autoStart = null) }

    fun micDenied() = _state.update {
        it.copy(micRefused = true, messages = listOf(res(R.string.recording_mic_denied)))
    }

    /**
     * The permission is there — asked for and given, or given back in the system settings, which is
     * what the screen's resume re-read finds. The refusal and the line it put up both go: the note
     * is about a start that cannot happen, and this one can.
     */
    fun micGranted() = _state.update { state ->
        if (!state.micRefused) {
            state
        } else {
            state.copy(
                micRefused = false,
                messages = state.messages - res(R.string.recording_mic_denied),
            )
        }
    }

    /**
     * @param participants how many people were in the room, or null for "unknown" — docs/03's
     * `context.participants`, which docs/08 lets override the workflow's speaker hint.
     */
    fun saveTitle(title: String, participants: Int? = null) {
        val untitled = _state.value.untitled ?: return
        val trimmed = title.trim().takeIf { it.isNotEmpty() }
        finish(untitled) { core ->
            val nothingToSay = trimmed == null && participants == null
            if (!nothingToSay && !core.recordings.updateTitle(untitled.recordingId, trimmed, participants)) {
                res(R.string.recording_title_too_late)
            } else {
                null
            }
        }
    }

    fun skipTitle() {
        val untitled = _state.value.untitled ?: return
        finish(untitled) { null }
    }

    private fun onEvent(event: RecorderEvent) = when (event) {
        is RecorderEvent.Finished -> {
            if (event.deferred) {
                // The recording is on disk but its meta is still open; nothing to name or queue.
                _state.update { it.copy(messages = listOf(res(R.string.recording_finish_deferred))) }
            } else if (!event.enqueue) {
                // Stopped from the app: finalized, not queued, waiting for a name.
                _state.update {
                    it.copy(
                        untitled = UntitledRecording(event.recordingId, event.durationSec, event.parts),
                        messages = listOf(summary(event.parts, event.durationSec)),
                    )
                }
            } else {
                // Stopped from the notification, or by a failure: there was nobody to ask, so
                // `RecApp.onRecordingReady` has already queued it. What the queue made of it is not
                // reported here — the service hands the recording over and does not wait for an
                // answer, and the job list is where a recording's job is looked at anyway.
                _state.update { it.copy(messages = listOf(summary(event.parts, event.durationSec))) }
            }
        }

        is RecorderEvent.Failed ->
            _state.update { it.copy(messages = listOf(res(R.string.recording_failed, event.reason))) }
    }

    /** Clears the prompt first: whatever the queue says, the recording is safe on disk already. */
    private fun finish(untitled: UntitledRecording, apply: suspend (ReclyCore) -> UiMessage?) {
        _state.update { it.copy(untitled = null) }
        viewModelScope.launch {
            val core = core()
            val problem = apply(core)
            val enqueued = core.enqueue(untitled.recordingId)
            // docs/11 A5 trigger (a). This is the deferred half of the stop: the service could not
            // wake the scheduler because there was no job yet, so it happens here.
            if (enqueued is EnqueueResult.Enqueued) WorkScheduler(getApplication()).onJobsDue()
            _state.update {
                it.copy(
                    messages = listOfNotNull(
                        summary(untitled.parts, untitled.durationSec),
                        problem,
                        enqueued.describe(),
                    ),
                )
            }
        }
    }

    private suspend fun core(): ReclyCore = CoreModule.get(getApplication<Application>()).core

    private fun summary(parts: Int, durationSec: Double): UiMessage =
        res(R.string.recording_saved, parts, durationSec.toInt())

    private fun res(@StringRes id: Int, vararg args: Any): UiMessage = UiMessage.Res(id, args.toList())
}

/**
 * docs/11 A9, the "spend or drop" half: a tile, widget or shortcut tap is a request to record
 * *now*. The request is spent the moment it is looked at, and this says whether it still means
 * anything. A tap the app could not act on within [AUTO_START_TTL_MS] — the activity never reached
 * the Record screen, the process was busy coming up — is one the user has long since given up on,
 * and honouring it later would be the app opening the microphone by itself. An age below zero is a
 * clock that went backwards and is dropped for the same reason.
 */
internal fun autoStartStillWanted(requestedAt: Long, now: Long): Boolean =
    now - requestedAt in 0..AUTO_START_TTL_MS

/** Long enough for a cold start on a slow phone, short enough that nobody has forgotten the tap. */
internal const val AUTO_START_TTL_MS: Long = 10_000

/** ADR-016's selection rules, in the words of the person who just stopped a recording. */
internal fun EnqueueResult.describe(): UiMessage = UiMessage.Res(
    when (this) {
        EnqueueResult.NoWorkflow -> R.string.enqueue_no_workflow
        EnqueueResult.PartsPurged -> R.string.enqueue_parts_purged
        is EnqueueResult.SkippedShort -> R.string.enqueue_skipped_short
        is EnqueueResult.AlreadyDone -> R.string.enqueue_already_done
        is EnqueueResult.Enqueued -> R.string.enqueue_queued
    },
)
