package app.recly.windows.record

import recly.core.ReclyCore
import recly.core.job.EnqueueResult

/**
 * docs/03 · docs/14 "감지": the title is asked for *after* the recording has ended, and the job is
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
): EnqueueResult {
    val named = title?.trim()?.takeIf { it.isNotEmpty() }
    if (named != null || participants != null) {
        core.recordings.updateTitle(recordingId, named, participants)
    }
    return core.enqueue(recordingId)
}
