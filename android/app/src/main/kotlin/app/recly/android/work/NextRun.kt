@file:OptIn(ExperimentalTime::class)

package app.recly.android.work

import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import recly.core.job.Job
import recly.core.job.JobStatus

/**
 * When the queue has to be looked at again (docs/11 A5 deliverable 3). Pure on purpose: it is the
 * one piece of the executor wiring with arithmetic in it, and the one piece a JVM test can pin
 * down exactly.
 *
 * Every pass ends by arming exactly one successor from this, so the only way the queue can stall
 * is for this to return null — which it does only when there is nothing left to run.
 */
object NextRun {

    /**
     * The earliest moment a job could next make progress: a `PENDING` job is due immediately, a
     * `WAITING` one when its backoff elapses — including a backoff that already elapsed while a
     * long upload held the pass, which is why past instants are kept rather than filtered out.
     * Everything else (`DONE`, `FAILED`, `NEEDS_AUTH`, `SKIPPED_SHORT`) waits for a person.
     */
    fun at(jobs: List<Job>, now: Instant): Instant? = jobs.mapNotNull { job ->
        when (job.status) {
            JobStatus.PENDING -> now
            // A WAITING row with no instant is a lost write, not a job to strand: treat it as due.
            JobStatus.WAITING -> job.nextRunAt ?: now
            // RUNNING is deliberately not here: a row left RUNNING by a kill is recovered by the
            // next pass, and the boot receiver, the foreground and the six-hour worker all bring
            // one. Arming a zero-delay timer for it would wake the device to do the same nothing.
            else -> null
        }
    }.minOrNull()

    /** What WorkManager wants instead of an instant. Never negative — an overdue job means "now". */
    fun delay(at: Instant, now: Instant): Duration = (at - now).coerceAtLeast(Duration.ZERO)

    /** null means there is nothing to come back for, and only then is the timer cancelled. */
    fun delay(jobs: List<Job>, now: Instant): Duration? = at(jobs, now)?.let { delay(it, now) }
}
