@file:OptIn(ExperimentalTime::class)

package app.recly.android.work

import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import recly.core.ReclyCore
import recly.core.job.Job
import recly.core.job.RunSummary

/**
 * Everything [WorkflowWorker] needs from the core and the scheduler, and so the whole of what a
 * JVM test has to stand in for. A worker that reached for `CoreModule` directly could only be
 * tested on a device.
 */
interface JobFacade {
    fun now(): Instant

    suspend fun runDueJobs(): RunSummary

    /** The queue as it stands after a pass — read to arm the successor. */
    suspend fun jobs(): List<Job>

    /** Arms the successor after [delay]. There is no cancel — see [JobScheduler.armNext]. */
    suspend fun scheduleNext(delay: Duration)
}

/**
 * The real one. Every core call is hopped onto `CoreDeps.io`: a `CoroutineWorker` runs `doWork` on
 * `Dispatchers.Default`, and the core's DB and file work belongs on the dispatcher the shell gave
 * it (docs/10 "동시성 · 스레딩").
 */
class CoreJobFacade(
    private val core: ReclyCore,
    private val scheduler: JobScheduler,
) : JobFacade {

    override fun now(): Instant = core.deps.clock.now()

    override suspend fun runDueJobs(): RunSummary = withContext(core.deps.io) { core.runDueJobs() }

    override suspend fun jobs(): List<Job> = withContext(core.deps.io) { core.jobs.observe().first() }

    override suspend fun scheduleNext(delay: Duration) = scheduler.armNext(delay)
}
