@file:OptIn(ExperimentalTime::class)

package app.recly.android.work

import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import recly.core.job.Job

/**
 * The three unique work names, behind a seam. `WorkScheduler` is the only implementation that
 * talks to WorkManager; everything that decides *when* to schedule is above this line and testable
 * without a device.
 */
interface JobScheduler {

    /** `rec-jobs`: run a pass. [expedited] is "upload now". */
    suspend fun runNow(expedited: Boolean = false)

    /**
     * `rec-jobs-next`: the successor, always exactly one, always replaced.
     *
     * There is deliberately no way to cancel it. A pass that finds an empty queue arms nothing and
     * leaves whatever is standing alone: cancelling on the strength of a queue read is the race
     * this class exists to avoid, and the cost of a stale timer is one pass that finds nothing to
     * do and arms nothing in turn — which terminates.
     */
    suspend fun armNext(delay: Duration)

    /** `rec-jobs-periodic`: the six-hour insurance. */
    suspend fun armPeriodic(replace: Boolean = false)

    /**
     * Something just became runnable — a stop that queued a job, a retry, a sign-in that unparked
     * one. Three steps, and the order is the contract:
     *
     * 1. [signalDue] first, so a pass already in flight can tell that its own view of the queue is
     *    stale even if the job landed after it read the table.
     * 2. a pass, for the normal case.
     * 3. a zero-delay successor, for the case where a pass is already running and `rec-jobs` (KEEP)
     *    therefore woke nothing at all.
     */
    suspend fun onJobsDue(expedited: Boolean = false) {
        signalDue()
        runNow(expedited = expedited)
        armNext(Duration.ZERO)
    }

    companion object {
        /**
         * Ticks once per "something became runnable". Both sides of the race live in the app
         * process — the ViewModels and the recorder host that signal, and the worker that reads —
         * so a process-wide counter is enough, and it is why a pass can distinguish "the queue is
         * genuinely empty" from "the queue was empty when I looked".
         *
         * Monotonic and never reset: a worker only ever compares its own before-and-after.
         */
        private val generation = AtomicLong()

        fun dueGeneration(): Long = generation.get()

        fun signalDue(): Long = generation.incrementAndGet()
    }
}

/**
 * A queued request keeps the constraint it was built with, so flipping "Wi-Fi only" has to rebuild
 * the two standing names or the toggle would not take effect until the next recording. The
 * successor is recomputed from the job table rather than reused: the old one is replaced anyway.
 *
 * `rec-jobs` is deliberately *not* replaced. REPLACE cancels a pass that is in flight, and the
 * emulator shows what that costs: the upload in progress is stopped and the step spends a retry
 * attempt on a failure the user's tap caused. The re-armed `rec-jobs-next` carries the queue with
 * the new constraint instead. The residual is one pass: a `rec-jobs` request that was already
 * queued may still run once on the constraint it was built with.
 */
suspend fun applyNetworkSetting(scheduler: JobScheduler, jobs: List<Job>, now: Instant) {
    scheduler.armPeriodic(replace = true)
    NextRun.delay(jobs, now)?.let { scheduler.armNext(it) }
    scheduler.runNow()
}
