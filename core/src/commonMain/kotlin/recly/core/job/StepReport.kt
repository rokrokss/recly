@file:OptIn(ExperimentalTime::class)

package recly.core.job

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import recly.core.message.CoreMessage
import recly.core.message.CoreMessageRef

/**
 * What a shell has to know about a `step_run` to say something useful about it (docs/08 "오류",
 * "폴링 · 상태"). The wording is each shell's own, in its own resources; which of the sentences to
 * show is decided by [CoreMessageRef.parse] (docs/07 §5), and what is left here is the two things
 * a `last_error` alone does not answer.
 */
object StepReport {

    /**
     * Whether the thing to do about [lastError] is to look at the key: this device holds no value
     * for the secret, or the provider refused the one it holds (docs/08 "오류"). Every other
     * failure — and a message older than the keys — has nothing to check.
     */
    fun needsKey(lastError: String?): Boolean {
        val message = lastError?.let { CoreMessageRef.parse(it) }?.message
        return message == CoreMessage.MISSING_SECRET || message == CoreMessage.AUTH_REJECTED
    }

    /**
     * How long the transcription has been in flight, in whole minutes — docs/08 parks the job in
     * `WAITING` while the provider works, and "n분 경과" is the only honest thing to say about a
     * wait with no progress in it. Null when nothing has been submitted.
     *
     * The submission time is the `transcribe` runner's own state (`submittedAt`), because that is
     * when the provider started counting, not when this device last polled.
     */
    fun waitingMinutes(steps: List<StepRun>, now: Instant): Int? {
        // Only a step that is still to run can be waiting on a provider: a transcribe that
        // succeeded keeps its `submittedAt` (state is never cleared on success), and read off
        // that row it would call a later webhook's backoff "transcribing" — and hide the retry
        // that backoff is entitled to (Sol, 2026-09-04).
        val submitted = steps.asReversed()
            .filter { it.status == StepStatus.PENDING }
            .firstNotNullOfOrNull { (it.state?.get(SUBMITTED_AT) as? JsonPrimitive)?.contentOrNull }
            ?: return null
        val at = runCatching { Instant.parse(submitted) }.getOrNull() ?: return null
        return ((now - at).inWholeMinutes).coerceAtLeast(0).toInt()
    }

    /** `TranscribeState.submittedAt`, which is serialized state and so is a wire name. */
    private const val SUBMITTED_AT = "submittedAt"
}
