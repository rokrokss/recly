@file:OptIn(ExperimentalTime::class)

package app.recly.recording

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import recly.core.model.Range

/** What [RecorderService] is doing, for any UI that cares. Process-wide, survives the screen. */
sealed interface RecorderState {
    data object Idle : RecorderState

    /**
     * Between the start intent and the first sample: the recording id does not exist yet, and a
     * second start must not be accepted in the meantime.
     */
    data object Starting : RecorderState

    data class Recording(
        val recordingId: String,
        val startedAt: Instant,
        val workflowId: String?,
    ) : RecorderState

    data object Stopping : RecorderState
}

/** One-shot outcomes — a state flow would replay them onto every new subscriber. */
sealed interface RecorderEvent {
    /**
     * The recording has stopped. [enqueue] is what the stop asked the shell for and what it was
     * handed over as (see [RecorderHost.onRecordingReady]): false means the job is being held back
     * because the caller is going to name the recording first — the phone UI does that (docs/03)
     * and enqueues itself once the title is settled.
     *
     * [deferred] means it did *not* finalize — audio is on disk that could not be filed, the row is
     * still open, and the next [RecordingRecovery] pass finishes it. There is nothing to name and
     * nothing to hand over yet, so the UI says so instead of asking for a title.
     */
    data class Finished(
        val recordingId: String,
        val durationSec: Double,
        val parts: Int,
        val silenced: List<Range>,
        val enqueue: Boolean,
        val deferred: Boolean = false,
    ) : RecorderEvent

    /** The parts written so far are kept — a failed recording is still a recording (docs/03). */
    data class Failed(val recordingId: String?, val reason: String) : RecorderEvent
}

/**
 * `MediaRecorder` failures, surfaced instead of thrown at whatever thread the callback used.
 *
 * [fatal] separates "the capture is over" from "one segment could not be filed": the second must
 * not cost the user the rest of a three-hour recording, so it is reported and the encoder keeps
 * running (the part is picked up by [RecordingRecovery]).
 */
class RecorderError(
    message: String,
    cause: Throwable? = null,
    val fatal: Boolean = true,
) : Exception(message, cause)
