@file:OptIn(ExperimentalTime::class)

package app.recly.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.recly.android.R
import app.recly.android.core.CoreModule
import app.recly.android.core.UiMessage
import app.recly.android.ui.component.ProcessingState
import app.recly.android.work.JobScheduler
import app.recly.android.work.WorkScheduler
import app.recly.recording.RecorderService
import app.recly.recording.RecorderState
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import recly.core.ReclyCore
import recly.core.job.Job
import recly.core.job.JobStatus
import recly.core.job.StepReport
import recly.core.job.StepRun
import recly.core.model.RecordingStatus
import recly.core.platform.Logger
import recly.core.recording.DeleteResult
import recly.core.recording.RecordingRecord
import recly.core.transcribe.TranscribeRunner
import recly.core.transcribe.Transcript

/** docs/11 A4: what a recording looks like in the list, whether or not it has a job yet. */
data class JobItem(
    val recordingId: String,
    val jobId: String?,
    /** The workflow the job runs, so a key that was refused can be fixed where it is defined. */
    val workflowId: String?,
    /** Null when the recording was never named; the screen says so in the app's language. */
    val title: String?,
    /** ISO-8601 UTC, as `meta.json` holds it — the screen formats it for the locale (docs/07). */
    val startedAt: String,
    val durationSec: Double?,
    val state: ItemState,
    /**
     * docs/03 "다른 기기의 녹음": another device recorded this and uploaded it, and this one adopted
     * the folder from Drive. The row says nothing of it — it is a finished recording like any other
     * — but the delete dialog does: there is no local half to keep.
     */
    val remote: Boolean = false,
    /** `step_run.last_error` of whatever step is holding the job up — a `CoreMessage` code. */
    val error: String?,
    /**
     * How long the transcription has been in flight (docs/08 "폴링 · 상태"). Non-null only while a
     * `transcribe` step is waiting on a provider — a plain retry backoff has no submission behind
     * it and says something else.
     */
    val waitingMinutes: Int?,
    /** The `drive.upload` output's folder link, once there is one. */
    val link: String?,
    val nextRunAt: Instant?,
    /** docs/10: why this job is the user's to fix, or null when it is not (banner, notification). */
    val alert: AlertReason? = null,
)

enum class ItemState {
    /** The row is still open — the recorder is writing into it. */
    RECORDING,

    /** docs/03 "워치 → 폰 전송 계약": the watch is handing this recording over to this phone now. */
    RECEIVING,

    /** docs/03 "다른 기기의 녹음": another device is still uploading it — a pull's provisional row. */
    REMOTE_UPLOADING,

    /** docs/03 "다른 기기의 녹음": another device uploaded it and is transcribing it (the marker). */
    REMOTE_TRANSCRIBING,

    /** Finalized but nothing queued it: neither its own pick nor this device's default resolved. */
    NO_JOB,
    PENDING,
    RUNNING,
    WAITING,
    DONE,
    FAILED,
    NEEDS_AUTH,

    /** docs/10 "Drive 용량 초과": parked, not retried, until the user frees space and asks again. */
    NEEDS_SPACE,
    SKIPPED_SHORT,
}

/**
 * docs/03 "앱에서 지우기": what the delete dialog has to know before it can ask. [unuploaded] is
 * how many parts are still only on this phone, which the dialog says first. [remote] is a recording
 * this device only adopted from Drive: there is no local half to keep, so there is no choice to
 * make either — it is carried here rather than read again in the dialog.
 */
data class DeleteRequest(
    val recordingId: String,
    val title: String?,
    val unuploaded: Int,
    val remote: Boolean,
)

data class JobsUiState(
    val loading: Boolean = true,
    val items: List<JobItem> = emptyList(),
    /** docs/10: the user-fixable failures, folded one line per reason — the list's top banner. */
    val alerts: List<JobAlert> = emptyList(),
    /** Non-null while the docs/03 delete dialog is up. */
    val confirmDelete: DeleteRequest? = null,
    /** docs/08 "결과 파일": the transcript of one recording, while it is being read. */
    val detail: DetailState? = null,
    /** Where the row action the user last asked for is (docs/09), for the button that asked. */
    val action: ProcessingState = ProcessingState.IDLE,
    /** Named, not resolved: this outlives the screen the language setting recreates (docs/07). */
    val message: UiMessage? = null,
)

/** The recording detail screen (docs/08 deliverable 3). */
data class DetailState(
    val recordingId: String,
    /** Null when the recording was never named, exactly as in [JobItem]. */
    val title: String?,
    val loading: Boolean = true,
    val transcript: Transcript? = null,
    /** docs/08 "결과 파일": the audio beside the transcript, where this phone still has it. */
    val audio: RecordingPlaylist.Selection = RecordingPlaylist.Selection.EMPTY,
    /**
     * docs/09 화면 원칙 2: the recording as a shape, one peak per
     * [RecordingWaveform.WINDOW_SEC] window of `meta.json`'s own timeline — empty until the decode
     * is through, and empty for good if it could not be.
     */
    val waveform: FloatArray = FloatArray(0),
    /** A take still being written to has nothing whole to play yet, so the page offers nothing. */
    val writing: Boolean = false,
    /**
     * Whether *any* recording on this phone is being written right now — which is not the same
     * question as [writing], and is the one that decides whether Play may be offered at all: the
     * recorder owns the microphone while it runs, and playing over it is not what a tap means.
     */
    val deviceRecording: Boolean = false,
    /** docs/03 ADR-017: how the trip to Drive for the parts the sweep took is going. */
    val driveFetch: DriveFetch = DriveFetch.DECIDING,
)

/** What the player bar has to say while the parts are on their way back, and after. */
enum class DriveFetch {
    /**
     * Whether there is a trip to make is not known yet — asking Drive whether it holds the
     * recording is itself a round trip. The bar keeps its clock and offers no Play until this is
     * over: what Play would start is not settled while it lasts.
     */
    DECIDING,

    /** Nothing to fetch, or the fetch is over: what the bar shows is what there is. */
    IDLE,
    FETCHING,
    FAILED,
}

/**
 * The list is a join the core does not do for us: jobs live in one table, the recordings they are
 * about in another, and the reason a job is stuck in a third. `jobs.observe()` drives it, with the
 * recorder's state folded in so a recording that has only just started — and so has no job to
 * change any row — still appears the moment it does.
 */
class JobsViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(JobsUiState())
    val state: StateFlow<JobsUiState> = _state.asStateFlow()

    /** Everything the open detail is reading, as one thing to stop when the page goes. */
    private var detailJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            val core = core()
            // docs/03 "다른 기기의 녹음": a pull adopts and drops rows without touching a job, and
            // `jobs.observe()` never fires for one of those — the recordings themselves have to be
            // watched as well.
            combine(
                core.jobs.observe(),
                RecorderService.state,
                core.recordings.observe(),
            ) { jobs, recorder, _ -> jobs to recorder }
                .collect { (jobs, recorder) ->
                    val items = items(core, jobs)
                    _state.update {
                        it.copy(
                            loading = false,
                            items = items,
                            alerts = foldAlerts(items.map { row -> AlertSource(row.alert, row.workflowId) }),
                            // The open detail's Play goes away for as long as the recorder holds
                            // the microphone, wherever the recording was started from.
                            detail = it.detail?.copy(deviceRecording = capturing(recorder)),
                        )
                    }
                }
        }
        pullRemote()
    }

    /**
     * docs/03 "다른 기기의 녹음": the ledger is back on screen, so what the other devices have
     * uploaded since is asked for again — the job pass asks too, but on a throttle of its own.
     */
    fun refresh() = pullRemote()

    /**
     * Fire and forget: the list draws what this phone already knows and gains the rest when Drive
     * answers. The pull never throws — a phone with nobody signed in simply has nothing to adopt.
     */
    private fun pullRemote() {
        viewModelScope.launch {
            val core = core()
            withContext(core.deps.io) { core.pullRemoteRecordings(force = true) }
        }
    }

    /** docs/10: `FAILED`·`SKIPPED_SHORT`·`NEEDS_AUTH` go back to `PENDING` with a fresh budget. */
    fun retry(item: JobItem) = action {
        val jobId = item.jobId ?: return@action false
        if (!core().jobs.retry(jobId)) {
            _state.update { it.copy(message = UiMessage.Res(R.string.jobs_retry_unavailable)) }
            return@action false
        }
        scheduler().onJobsDue(expedited = true)
        true
    }

    /**
     * docs/03: the dialog is asked every time, because the Drive half of it is a separate question
     * and its answer is never remembered. The part count is read here rather than carried on every
     * row — only the recording being deleted needs it.
     *
     * Only the newest ask becomes the question. The count is a trip to the core, so two taps in a
     * row are two reads in flight, and the slower one finishing last would otherwise replace the
     * dialog the user is looking at with the row they left behind — or put back one they dismissed.
     * The Mac's `deleteAsked` counter is the same three lines.
     */
    fun confirmDelete(item: JobItem) = launch {
        val asked = deleteAsks.ask()
        val unuploaded = Retention.unuploadedParts(core(), item.recordingId)
        if (!deleteAsks.isCurrent(asked)) return@launch
        _state.update {
            it.copy(
                confirmDelete = DeleteRequest(
                    recordingId = item.recordingId,
                    title = item.title,
                    unuploaded = unuploaded,
                    remote = item.remote,
                ),
            )
        }
    }

    /**
     * The answer that deletes nothing — and the answer to a question that was never asked, which is
     * what a dismissal is while a count is still being read: the generation moves on, so the read
     * that comes back has nothing left to ask about.
     */
    fun cancelDelete() {
        deleteAsks.cancel()
        _state.update { it.copy(confirmDelete = null) }
    }

    private val deleteAsks = DeleteAsks()

    /**
     * docs/03: local always, Drive only when the user asked for it — and a Drive that refused does
     * not undo the local deletion, so what is left to say is that the folder is still there.
     */
    fun delete(request: DeleteRequest, deleteDrive: Boolean) = action {
        // Through [cancelDelete], so a count still on its way back cannot put a dialog up over the
        // deletion it was asked for.
        cancelDelete()
        when (val result = core().recordings.delete(request.recordingId, deleteDrive)) {
            DeleteResult.Busy -> {
                _state.update { it.copy(message = UiMessage.Res(R.string.delete_busy)) }
                false
            }

            is DeleteResult.Deleted -> {
                result.driveError?.let { error ->
                    _state.update {
                        it.copy(message = UiMessage.Res(R.string.delete_drive_failed, listOf(error)))
                    }
                }
                result.driveError == null
            }

            DeleteResult.NotFound -> false
        }
    }

    /**
     * docs/08 "결과 파일": the local copy if the step ran here, and Drive's if it ran on another
     * device — the core decides which, and keeps what it downloads.
     */
    fun openDetail(item: JobItem) {
        // One open detail at a time, and one job behind it: what the page it replaces was still
        // reading — Drive, and then every byte of the recording for the waveform — is work for a
        // page nobody is looking at any more.
        detailJob?.cancel()
        detailJob = viewModelScope.launch { openDetail(item.recordingId, item.title) }
    }

    private suspend fun openDetail(recordingId: String, title: String?) {
        _state.update {
            it.copy(
                detail = DetailState(
                    recordingId = recordingId,
                    title = title,
                    deviceRecording = capturing(RecorderService.state.value),
                ),
            )
        }
        val core = core()
        val result = core.results(recordingId)
        val record = core.recordings.get(recordingId)
        val audio = record?.let { local(core, it) } ?: RecordingPlaylist.Selection.EMPTY
        updateDetail(recordingId) {
            it.copy(
                loading = false,
                transcript = result.transcript,
                audio = audio,
                writing = record?.meta?.status == RecordingStatus.RECORDING,
            )
        }
        // Out of `loading` before the fetch, because the player bar is where the fetch is said —
        // and the bar stays on DECIDING until this has decided, so the seconds it spends asking
        // Drive are not seconds in which Play is offered.
        fetchFromDrive(core, recordingId, record, audio)
        decodeWaveform(core, recordingId)
    }

    /**
     * docs/09 화면 원칙 2: the picture, last and inside the load rather than beside it. Last because
     * the trip to Drive is what settles which parts there are, and a decode of the local prefix
     * would be a picture of a different recording than the one that plays. Inside because this is
     * the job [closeDetail] and the next [openDetail] cancel: reading a whole recording for a bar
     * nobody is looking at is work the next page would be waiting behind.
     *
     * A decode that could not be made leaves the bar with its baseline and nothing to say — the
     * shape is what the clock beside it is drawn on, not something the page is about.
     */
    private suspend fun decodeWaveform(core: ReclyCore, recordingId: String) {
        val audio = _state.value.detail?.takeIf { it.recordingId == recordingId }?.audio ?: return
        if (audio.isEmpty) return
        val peaks = try {
            RecordingWaveform.peaks(audio)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            core.deps.logger.log(Logger.Level.ERROR, "detail.waveform.failed", error = e)
            return
        }
        updateDetail(recordingId) { it.copy(waveform = peaks) }
    }

    /** The parts of this recording that are still on this phone. */
    private fun local(core: ReclyCore, record: RecordingRecord): RecordingPlaylist.Selection =
        RecordingPlaylist.select(record.meta.parts, record.dir) { core.deps.fileSystem.exists(it) }

    /**
     * docs/03 ADR-017: the parts the retention sweep took, fetched back from Drive so the page can
     * play the recording it is about. A take still being written to is left alone — it has nothing
     * whole to play yet, and nothing of it has reached Drive either.
     *
     * A failure is a sentence in the player bar and nothing more. What a missing token needs is a
     * sign-in, and the app already carries one — a dialog from here would be a second way to say
     * what is already on screen.
     */
    private suspend fun fetchFromDrive(
        core: ReclyCore,
        recordingId: String,
        record: RecordingRecord?,
        local: RecordingPlaylist.Selection,
    ) {
        if (record == null || record.meta.status == RecordingStatus.RECORDING) {
            updateDetail(recordingId) { it.copy(driveFetch = DriveFetch.IDLE) }
            return
        }
        val parts = RecordingPlaylist.played(record.meta.parts)
        val uploaded = driveHasEveryPart(core, recordingId)
        if (!RecordingPlaylist.fetchesFromDrive(local.paths.size, parts.size, uploaded)) {
            updateDetail(recordingId) { it.copy(driveFetch = DriveFetch.IDLE) }
            return
        }
        updateDetail(recordingId) { it.copy(driveFetch = DriveFetch.FETCHING) }
        try {
            val fetched = core.audio(recordingId)
            updateDetail(recordingId) {
                it.copy(
                    audio = RecordingPlaylist.fetched(
                        parts = parts,
                        files = fetched.paths.map { path -> path.name }.toSet(),
                        dir = record.dir,
                    ),
                    // A part that stayed missing is a gap the playlist stops at, so the trip did
                    // not bring the recording back whole — the same sentence as one that failed.
                    driveFetch = if (fetched.missing.isEmpty()) DriveFetch.IDLE else DriveFetch.FAILED,
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // A trip that threw on one part may already have written the ones before it, so what
            // this phone has is asked of the disk again rather than left at what it was before the
            // trip: the prefix that did come back is playable, and a later part that was local all
            // along is not — its clock would start at zero (`RecordingPlaylist.fetched`).
            val restored = RecordingPlaylist.restored(parts, record.dir) {
                core.deps.fileSystem.exists(it)
            }
            updateDetail(recordingId) { it.copy(audio = restored, driveFetch = DriveFetch.FAILED) }
        }
    }

    /**
     * Whether Drive holds every part of this recording — the question that decides whether there is
     * anything to fetch. It needs the account, and a phone that cannot answer it is a phone with
     * nothing to fetch: the bar then says what is here rather than what could not be got.
     */
    private suspend fun driveHasEveryPart(core: ReclyCore, recordingId: String): Boolean = try {
        core.uploaded(recordingId)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /**
     * The open detail, if it is still the one this answer is about: the user may have closed it, or
     * opened another one, while Drive was answering.
     */
    private fun updateDetail(recordingId: String, block: (DetailState) -> DetailState) =
        _state.update { state ->
            val detail = state.detail
            if (detail == null || detail.recordingId != recordingId) {
                state
            } else {
                state.copy(detail = block(detail))
            }
        }

    /**
     * docs/03 "제목": the detail screen's rename. Blank is not a title — it clears the name back to
     * the timestamp one, which is what the core reads a null as.
     *
     * The ledger row behind the page follows on its own (`recordings.observe()`); the open page's
     * own header does not, so the title it draws is set here rather than waited for.
     */
    fun rename(recordingId: String, title: String) = launch {
        val named = title.trim().takeIf { it.isNotEmpty() }
        val core = core()
        if (withContext(core.deps.io) { core.rename(recordingId, named) }) {
            updateDetail(recordingId) { it.copy(title = named) }
        }
    }

    fun closeDetail() {
        detailJob?.cancel()
        detailJob = null
        _state.update { it.copy(detail = null) }
    }

    private suspend fun items(core: ReclyCore, jobs: List<Job>): List<JobItem> {
        val byRecording = jobs.groupBy { it.recordingId }
        val now = core.deps.clock.now()
        return core.recordings.list(LIMIT).map { record ->
            // One job per (recording, workflow); the newest is the one the user last asked for.
            val job = byRecording[record.id]?.maxByOrNull { it.createdAt }
            val steps = job?.let { core.jobs.steps(it.id) }.orEmpty()
            // A snapshot this build cannot read is the whole reason the job stopped, and the steps
            // it left behind say nothing about it (docs/10 "잡 스냅샷").
            val error = job?.snapshotError ?: blockingError(steps)
            JobItem(
                recordingId = record.id,
                jobId = job?.id,
                workflowId = job?.workflowId,
                title = record.meta.title?.takeIf { it.isNotBlank() },
                startedAt = record.meta.startedAt,
                durationSec = record.meta.durationSec,
                state = stateOf(record, job),
                remote = record.remote,
                error = error,
                waitingMinutes = StepReport.waitingMinutes(steps, now),
                link = linkOf(steps),
                nextRunAt = job?.nextRunAt,
                alert = job?.let { alertReasonOf(it.status, error) },
            )
        }
    }

    /**
     * Whether the microphone is taken. Anything but [RecorderState.Idle] counts: a start that has
     * not reached its first sample, and a stop that has not finished filing, both hold it.
     */
    private fun capturing(recorder: RecorderState): Boolean = recorder != RecorderState.Idle

    private fun linkOf(steps: List<StepRun>): String? = steps
        .mapNotNull { it.output?.get("folderWebViewLink")?.jsonPrimitive?.contentOrNull }
        .firstOrNull()

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    /**
     * A row button's window ([processing]): the action reports its own outcome, so a retry that
     * could not be made due shows no ✓.
     */
    private fun action(block: suspend () -> Boolean) =
        viewModelScope.processing({ phase -> _state.update { it.copy(action = phase) } }, block)

    private suspend fun core(): ReclyCore = CoreModule.get(getApplication<Application>()).core

    private fun scheduler(): JobScheduler = WorkScheduler(getApplication())

    private companion object {
        /** Deep enough for months of daily recordings, shallow enough to join in one pass. */
        const val LIMIT = 100
    }

}

/**
 * docs/09 화면 원칙 2: which row state one recording is in. Outside the ViewModel because it is a
 * decision about a record and a job and nothing else — the same shape the other three shells make
 * it in, and one a JVM test can ask directly.
 */
internal fun stateOf(record: RecordingRecord, job: Job?): ItemState = when {
    // docs/03 "다른 기기의 녹음": the three things happening somewhere else come first. None of them
    // is a job of this device's, and each would otherwise be read as one of this device's own
    // states — a recording coming in from the watch, and one another device is uploading, are both
    // `status = recording` and would show as `REC`.
    record.receiving -> ItemState.RECEIVING
    record.remoteUploading -> ItemState.REMOTE_UPLOADING
    // A marker that names only `webhook` is not something to say: what is left is a request this
    // phone will never see the answer to, and the recording itself is done.
    record.remote && TranscribeRunner.TYPE in record.remotePending -> ItemState.REMOTE_TRANSCRIBING
    record.meta.status == RecordingStatus.RECORDING -> ItemState.RECORDING
    // Before the job question, because a row adopted from Drive has no job here by definition —
    // "no workflow" would be a thing for the user to fix, and there is nothing to fix: another
    // device already did the work, so this is a finished recording (docs/03 "다른 기기의 녹음").
    record.remote -> ItemState.DONE
    job == null -> ItemState.NO_JOB
    else -> when (job.status) {
        JobStatus.PENDING -> ItemState.PENDING
        JobStatus.RUNNING -> ItemState.RUNNING
        JobStatus.WAITING -> ItemState.WAITING
        JobStatus.DONE -> ItemState.DONE
        JobStatus.FAILED -> ItemState.FAILED
        JobStatus.NEEDS_AUTH -> ItemState.NEEDS_AUTH
        JobStatus.NEEDS_SPACE -> ItemState.NEEDS_SPACE
        JobStatus.SKIPPED_SHORT -> ItemState.SKIPPED_SHORT
    }
}

/**
 * The generation a [JobsViewModel.confirmDelete] carries across its read of the part count. A cancel
 * or a newer ask moves it on, and a read that comes back to an older generation says nothing: a late
 * count must not replace the dialog the user is looking at, nor reopen one they have dismissed. The
 * Mac's `MenuModel.deleteAsked` is the same counter.
 */
internal class DeleteAsks {

    private var asked = 0L

    /** A new ask; nothing started before it may put a dialog up any more. */
    fun ask(): Long {
        asked += 1
        return asked
    }

    /** True while [ask]'s own answer is still the one the screen is waiting for. */
    fun isCurrent(ask: Long): Boolean = ask == asked

    /** A dismissal, which is also the answer to a question nobody is asking any more. */
    fun cancel() {
        asked += 1
    }
}
