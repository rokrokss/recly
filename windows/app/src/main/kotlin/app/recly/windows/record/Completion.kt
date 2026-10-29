package app.recly.windows.record

import app.recly.windows.i18n.Str
import recly.core.ReclyCore
import recly.core.job.EnqueueResult

/**
 * What naming and queuing a finished recording came to: the queue's answer, and whether a name (or the count of
 * people in the room) arrived after the job had already read the meta, so it was not saved.
 */
data class Completion(val enqueued: EnqueueResult, val titleTooLate: Boolean = false)

/**
 * docs/03 · docs/14 "Detection": the title is asked for *after* the recording has ended, and the job is
 * only queued once the answer is in — the same order the Mac's `MenuModel.finish` and the phone's
 * stop dialog use. `updateTitle` refuses a recording whose job has already read the meta, so
 * enqueuing first would be a title the upload never carries.
 *
 * A blank answer ("Skip", or the dialog closed) is not a title: the recording keeps the one it was
 * started with, which is normally none.
 *
 * @param participants how many people were in the room, or null for "unknown" — docs/03's
 * `context.participants`, which docs/08 lets override the workflow's speaker hint. The same dialog
 * asks for it as on the phones, so both answers land in one write.
 */
suspend fun completeRecording(
    core: ReclyCore,
    recordingId: String,
    title: String?,
    participants: Int? = null,
): Completion {
    val named = title?.trim()?.takeIf { it.isNotEmpty() }
    val saved = if (named != null || participants != null) {
        core.recordings.updateTitle(recordingId, named, participants)
    } else {
        true
    }
    return Completion(core.enqueue(recordingId), titleTooLate = !saved)
}

/**
 * ADR-016's selection rules in the words of the person who just saved a recording — Android's `describe()`: the name
 * that came too late first, then what the queue did with it. A recording queued as asked is not news, so null.
 */
fun Completion.line(): Str? = when {
    titleTooLate -> Str.RECORDING_TITLE_TOO_LATE
    else -> when (enqueued) {
        EnqueueResult.NoWorkflow -> Str.ENQUEUE_NO_WORKFLOW
        EnqueueResult.PartsPurged -> Str.ENQUEUE_PARTS_PURGED
        is EnqueueResult.SkippedShort -> Str.ENQUEUE_SKIPPED_SHORT
        EnqueueResult.AlreadySynced, is EnqueueResult.AlreadyDone -> Str.ENQUEUE_ALREADY_DONE
        is EnqueueResult.Enqueued -> null
    }
}
