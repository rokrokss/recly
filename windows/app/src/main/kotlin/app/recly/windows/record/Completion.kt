package app.recly.windows.record

import app.recly.windows.i18n.Str
import kotlin.coroutines.cancellation.CancellationException
import recly.core.ReclyCore
import recly.core.job.EnqueueResult
import recly.core.platform.Logger

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
 * @param fill the dialog's ChatGPT box was there and ticked: the recording's publication asks for its title and
 * speaker names (docs/08 "Titles and speaker names").
 */
suspend fun completeRecording(
    core: ReclyCore,
    recordingId: String,
    title: String?,
    participants: Int? = null,
    fill: Boolean = false,
): Completion {
    val named = title?.trim()?.takeIf { it.isNotEmpty() }
    return completeInOrder(
        name = { if (named != null || participants != null) core.recordings.updateTitle(recordingId, named, participants) else true },
        requestFill = if (fill) ({ core.requestFill(recordingId, title = true, names = true) }) else null,
        enqueue = { core.enqueue(recordingId) },
        fillFailed = { core.deps.logger.log(Logger.Level.ERROR, "rec.fill.failed", mapOf("recordingId" to recordingId), it) },
    )
}

/**
 * [completeRecording]'s order: the name, then — the ChatGPT box ticked — the fill request, then the job. The request
 * is there before the job because the job's publication is what reads it (docs/08 "Titles and speaker names"); one
 * that could not be kept costs the fill, never the job. [name] answers whether the name was saved.
 */
internal suspend fun completeInOrder(
    name: suspend () -> Boolean,
    requestFill: (suspend () -> Unit)?,
    enqueue: suspend () -> EnqueueResult,
    fillFailed: (Exception) -> Unit,
): Completion {
    val saved = name()
    if (requestFill != null) {
        try {
            requestFill()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fillFailed(e)
        }
    }
    return Completion(enqueue(), titleTooLate = !saved)
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
