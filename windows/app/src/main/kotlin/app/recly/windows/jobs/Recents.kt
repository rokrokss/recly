@file:OptIn(ExperimentalTime::class)

package app.recly.windows.jobs

import app.recly.windows.i18n.Str
import app.recly.windows.i18n.UiMessage
import app.recly.windows.i18n.message
import app.recly.windows.ui.blockingError
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import recly.core.ReclyCore
import recly.core.job.Job
import recly.core.job.JobStatus
import recly.core.job.StepReport
import recly.core.job.StepRun
import recly.core.model.RecordingStatus
import recly.core.recording.RecordingRecord
import recly.core.transcribe.TranscribeRunner

/**
 * One recent recording as the tray lists it (docs/14 "앱": the tray's … recents, the Mac's `RecentItem`).
 */
data class RecentItem(
    val id: String,
    val jobId: String?,
    /** Where the job stands, so the row can offer a retry on the failures and nothing else. */
    val jobStatus: JobStatus? = null,
    /** The title the user gave it, or [Str.UNTITLED] — a name, so a language change re-renders it. */
    val title: UiMessage,
    val startedAt: String,
    /**
     * docs/09 화면 원칙 2: the ledger's 길이 column, as `meta.json` records it. Null until the
     * recording is finalized — a take still being written to has no length yet, and the column says
     * so rather than showing a number that is about to change.
     */
    val durationSec: Double? = null,
    /** What the menu says about it — done, sign-in needed, … */
    val state: UiMessage,
    /**
     * The recording's Drive folder: the `drive.upload` step's link, or the folder the row knows on
     * its own — an adopted recording was read out of that folder (docs/03).
     */
    val link: String?,
    /** The workflow the job runs, so a key that was refused can be fixed where it is defined. */
    val workflowId: String? = null,
    /**
     * docs/07 §5: the code the core last wrote for whatever step is holding the job up — a
     * `CoreMessage`, or a sentence an older build stored. Turned into words where it is drawn, so
     * a popover already on screen follows a language change (docs/07 rule 3).
     *
     * It is `blockingError`'s reading, which is the banner's too: a ledger row that named a
     * different failure from the banner above it was two answers to one question.
     */
    val lastError: String? = null,
    /** Minutes since a `transcribe` step submitted, while it is waiting for the result (docs/08). */
    val waitingMinutes: Int? = null,
    /**
     * docs/03: another device recorded it and this PC read it out of Drive. What it changes here is
     * the delete — there is no local half to keep, so the dialog has no choice to offer.
     */
    val remote: Boolean = false,
) {
    /**
     * docs/09 화면 원칙 2 "삭제(녹음·업로드 중 제외)": a recording being written to or uploaded right
     * now is not one to delete — the core refuses it anyway, and offering the button would be
     * offering a refusal. The Mac's popover and its window draw the same two exceptions.
     *
     * docs/03 "다른 기기의 녹음" (2026-09-04): and the two that are in flight *elsewhere*, for the same
     * reason read from the other side — deleting the folder out from under another device's upload,
     * or the row a watch transfer is still filling, is a refusal waiting to happen.
     */
    val deletable: Boolean
        get() = when ((state as? UiMessage.Res)?.key) {
            Str.STATUS_RECORDING, Str.STATE_UPLOADING, Str.STATE_RECEIVING, Str.STATE_REMOTE_UPLOADING -> false
            else -> true
        }
}

/**
 * The join the core does not do: jobs are one table, the recordings they are about another, and the
 * Drive link is in a third.
 */
object Recents {
    /**
     * docs/09 화면 원칙 2: how many rows one reading adds. The ledger is not a top-five any more —
     * it is a page at a time, and scrolling onto the last row reads the next page
     * ([app.recly.windows.ui.ShellModel.loadMoreRecents]), which is what the Mac's popover does too.
     */
    const val PAGE = 20

    suspend fun load(core: ReclyCore, limit: Int = PAGE): List<RecentItem> {
        val byRecording = core.jobs.list().groupBy { it.recordingId }
        val now = core.deps.clock.now()
        return core.recordings.list(limit).map { record ->
            // One job per (recording, workflow); the newest is the one the user last asked for.
            val job = byRecording[record.id]?.maxByOrNull { it.createdAt }
            val steps = job?.let { core.jobs.steps(it.id) }.orEmpty()
            item(record, job, steps, now)
        }
    }

    /** Pure, so the mapping the menu shows can be pinned down without a database. */
    fun item(
        record: RecordingRecord,
        job: Job?,
        steps: List<StepRun>,
        now: Instant = Instant.DISTANT_PAST,
    ): RecentItem {
        // docs/08 "폴링 · 상태": while a provider is transcribing there is no "when" to give, only
        // how long it has been — and "waiting to retry" would be a different thing to say.
        val waiting = StepReport.waitingMinutes(steps, now)
            ?.takeIf { job?.status == JobStatus.WAITING }
        return RecentItem(
            id = record.id,
            jobId = job?.id,
            jobStatus = job?.status,
            title = record.meta.title?.takeIf { it.isNotBlank() }?.let { UiMessage.Text(it) }
                ?: Str.UNTITLED.message(),
            startedAt = record.meta.startedAt,
            durationSec = record.meta.durationSec,
            state = waiting?.let { Str.STATE_WAITING_TRANSCRIPTION.message(it) } ?: stateLabel(record, job),
            link = driveLink(steps) ?: record.driveFolderUrl,
            workflowId = job?.workflowId,
            // A snapshot this build cannot read is the whole reason the job stopped, and the steps
            // it left behind say nothing about it (docs/10 "잡 스냅샷").
            lastError = job?.snapshotError ?: blockingError(steps),
            waitingMinutes = waiting,
            remote = record.remote,
        )
    }

    /**
     * Whether a job is running right now, off the same key the ledger badge reads `UPLOADING` from
     * ([app.recly.windows.ui.LedgerStates]) — a row that says one thing and a State node that says
     * another would be two answers to one question.
     *
     * The ledger is the pages of rows that have been read so far, so a job still running on one
     * older than those is not seen here: the same scope the rows under it have, and the dashboard
     * says no more than they do.
     *
     * docs/03: the key and not the badge, because another device's upload wears the same `UPLOADING`
     * ([Str.STATE_REMOTE_UPLOADING]) — and this node is what *this* PC is doing.
     */
    fun uploading(items: List<RecentItem>): Boolean =
        items.any { (it.state as? UiMessage.Res)?.key == Str.STATE_UPLOADING }

    fun stateLabel(record: RecordingRecord, job: Job?): UiMessage {
        // docs/03 "다른 기기의 녹음": what is going on somewhere else is read off the recording row —
        // none of it is a job of this PC's, so none of it can be read off the queue — and it is read
        // *first*: a transfer still coming in and another device's upload both carry
        // `status = recording`, and the local `REC` below would answer for both of them.
        if (record.receiving) return Str.STATE_RECEIVING.message()
        if (record.remoteUploading) return Str.STATE_REMOTE_UPLOADING.message()
        // The upload landed and the device that made the recording still has a `transcribe` to run.
        // Anything else it has left (a `webhook`) is nothing this list has to report, so the row
        // reads as the finished one it is.
        if (record.remote && TranscribeRunner.TYPE in record.remotePending) {
            return Str.STATE_REMOTE_TRANSCRIBING.message()
        }
        if (record.meta.status == RecordingStatus.RECORDING) return Str.STATUS_RECORDING.message()
        // docs/03: another device recorded and uploaded it, and this PC read it out of Drive. There
        // is no job here, so "no workflow" would be the wrong answer — nothing was skipped. It is
        // finished work like any other finished row, and says exactly that.
        if (record.remote) return Str.STATE_DONE.message()
        return when (job?.status) {
            null -> Str.STATE_NO_WORKFLOW
            JobStatus.PENDING -> Str.STATUS_WAITING
            JobStatus.RUNNING -> Str.STATE_UPLOADING
            JobStatus.WAITING -> Str.STATE_RETRY_WAIT
            JobStatus.DONE -> Str.STATE_DONE
            JobStatus.FAILED -> Str.STATE_FAILED
            JobStatus.NEEDS_AUTH -> Str.STATUS_SIGN_IN_NEEDED
            // docs/10 "Drive 용량 초과": parked rather than failed, and the row says which — a retry
            // over a full Drive is the same 403 again, and freeing space is the only way past it.
            JobStatus.NEEDS_SPACE -> Str.STATE_NO_SPACE
            JobStatus.SKIPPED_SHORT -> Str.STATE_TOO_SHORT
        }.message()
    }

    fun driveLink(steps: List<StepRun>): String? = steps
        .mapNotNull { it.output?.link() }
        .firstOrNull()

    private fun JsonObject.link(): String? = this["folderWebViewLink"]?.jsonPrimitive?.content
}
