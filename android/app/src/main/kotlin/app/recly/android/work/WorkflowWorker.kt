@file:OptIn(ExperimentalTime::class)

package app.recly.android.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.recly.android.core.CoreModule
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import recly.core.job.Job
import recly.core.job.JobStatus

/**
 * docs/11 A5: the only thing that runs the queue on Android. `ReclyCore.runDueJobs()` does the work
 * and persists after every step, so a `stopWork` costs at most the chunk in flight — which is why
 * this can be a plain worker and not a `dataSync` foreground service (docs/11 "주의").
 *
 * Two invariants, and they are the whole design:
 *
 * 1. **Every pass leaves a successor behind it.** The queue is only ever woken by an event or a
 *    timer, so a pass that ends without arming the next one is a queue that stalls until the user
 *    opens the app. The successor is recomputed from the job table after the pass ([NextRun]),
 *    with [JobScheduler.dueGeneration] catching whatever landed while the pass was running. A pass
 *    never cancels the successor: a queue that reads empty may simply have been read too early.
 * 2. **What a pass reports is not what decides the result.** A step that failed on the network is
 *    already parked `WAITING` on the core's own backoff, and a `NEEDS_AUTH` job is parked until
 *    the user signs in — neither is WorkManager's to retry, and retrying the auth one would drain
 *    the battery against a wall. Only an exception, meaning the pass itself could not happen,
 *    becomes `Result.retry()` and takes the 30-second exponential backoff.
 */
class WorkflowWorker @JvmOverloads constructor(
    appContext: Context,
    params: WorkerParameters,
    /** Supplied by the test's `WorkerFactory`; production builds one from [CoreModule]. */
    private val injected: JobFacade? = null,
) : CoroutineWorker(appContext, params) {

    /** True for the six-hour insurance instance. See [giveUp]. */
    private val periodic: Boolean get() = inputData.getBoolean(KEY_PERIODIC, false)

    override suspend fun doWork(): Result = try {
        val facade = injected ?: CoreJobFacade(
            CoreModule.get(applicationContext).core,
            WorkScheduler(applicationContext),
        )
        // Snapshotted before the pass and compared after the queue is read, never in between: a
        // job that is enqueued while this pass runs lands in neither the pass nor the read, and
        // the counter is the only evidence left that it happened.
        val generation = JobScheduler.dueGeneration()
        val summary = facade.runDueJobs()
        if (summary.alreadyRunning) {
            // Another pass holds the core's lock and its own snapshot of the queue. This one saw
            // nothing, so it must not reshape anything on the strength of that — it only
            // guarantees that *someone* comes back, shortly after the pass in flight should be done.
            val delay = if (signalled(generation)) Duration.ZERO else FOLLOW_UP
            facade.scheduleNext(delay)
            Log.i(TAG, "alreadyRunning follow-up=${delay.inWholeSeconds}s")
        } else {
            val now = facade.now()
            val jobs = facade.jobs()
            // The compare has to come after the read. A signal that lands while the queue is being
            // read is exactly the race, and comparing first would look right and miss it.
            val delay = if (signalled(generation)) Duration.ZERO else NextRun.delay(jobs, now)
            // Nothing to arm is not the same as "cancel what is there": a stale successor costs one
            // pass that finds nothing and arms nothing in turn, and cancelling would throw away a
            // successor that a concurrent enqueue armed a moment ago.
            delay?.let { facade.scheduleNext(it) }
            Log.i(TAG, "ran=${summary.jobIds.size} ${counts(jobs)} next=${delay?.inWholeSeconds ?: "-"}")
        }
        Result.success()
    } catch (e: CancellationException) {
        // A stopped worker is not a failed one: WorkManager reschedules it against its own
        // constraints and the core resumes from the last persisted step.
        throw e
    } catch (e: Throwable) {
        Log.e(TAG, "run failed (attempt ${runAttemptCount + 1})", e)
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else giveUp()
    }

    /**
     * The retry budget is WorkManager's, not the core's: a pass that keeps blowing up is a broken
     * device, not a job owed another attempt.
     *
     * What happens at the ceiling depends on which instance this is. A *periodic* worker that
     * returns failure is dropped by WorkManager and its unique name never runs again — the six-hour
     * insurance would be gone for the life of the install, which is the one outcome worse than a
     * stalled queue. So it reports success and simply comes back in six hours. A one-time request
     * may fail: the periodic worker and the next foreground still cover the queue.
     */
    private fun giveUp(): Result {
        Log.w(TAG, "job.worker.gaveUp periodic=$periodic attempts=${runAttemptCount + 1}")
        return if (periodic) Result.success() else Result.failure()
    }

    /** True when something became runnable after this pass started, so the queue read is stale. */
    private fun signalled(generation: Long): Boolean = JobScheduler.dueGeneration() != generation

    private fun counts(jobs: List<Job>): String = JobStatus.entries
        .mapNotNull { status -> jobs.count { it.status == status }.takeIf { it > 0 }?.let { "$status=$it" } }
        .joinToString(" ")

    companion object {
        /** Input data: set on the periodic request only. */
        const val KEY_PERIODIC: String = "periodic"

        internal const val TAG = "WorkflowWorker"

        /** Six attempts at 30s exponential is a little over four hours of trying. */
        internal const val MAX_ATTEMPTS = 5

        /**
         * How long after a pass that found the core already busy to come back. Bounded and short:
         * long enough that the pass in flight has usually finished and armed its own successor
         * (which replaces this one), short enough that a pass killed mid-flight is picked up again.
         */
        internal val FOLLOW_UP: Duration = 60.seconds
    }
}
