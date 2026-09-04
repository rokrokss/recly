@file:OptIn(ExperimentalTime::class)

package app.recly.android.work

import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import recly.core.job.Job
import recly.core.job.JobStatus
import recly.core.job.RunSummary
import recly.core.model.Workflow

/** Records what the worker asked of the core, and can be told to blow up in the middle of it. */
class FakeJobFacade(
    private val now: Instant = NOW,
    private val summary: RunSummary = RunSummary(jobIds = listOf("job-1")),
    /** The job table as the worker will find it *after* the pass. */
    private val queueAfterRun: List<Job> = emptyList(),
    private val failWith: Throwable? = null,
    /**
     * Runs while `jobs()` is being answered — the window in which a real `onJobsDue()` can land
     * between the pass and the recompute.
     */
    private val whileReadingJobs: () -> Unit = {},
) : JobFacade {

    var runs: Int = 0
        private set

    /** Every `scheduleNext` argument, in order. The API has no cancel, by design. */
    val scheduled: MutableList<Duration> = mutableListOf()

    override fun now(): Instant = now

    override suspend fun runDueJobs(): RunSummary {
        runs++
        failWith?.let { throw it }
        return summary
    }

    override suspend fun jobs(): List<Job> {
        whileReadingJobs()
        return queueAfterRun
    }

    override suspend fun scheduleNext(delay: Duration) {
        scheduled += delay
    }

    companion object {
        val NOW: Instant = Instant.parse("2026-08-27T10:00:00Z")
    }
}

/** Records the three unique names' worth of decisions without a WorkManager anywhere near it. */
class FakeScheduler : JobScheduler {
    /** Each `runNow`, recorded by its expedited flag. */
    val runs: MutableList<Boolean> = mutableListOf()
    val next: MutableList<Duration> = mutableListOf()
    val periodic: MutableList<Boolean> = mutableListOf()

    /** The generation observed INSIDE each call — the ordering contract of `onJobsDue`. */
    val runGens: MutableList<Long> = mutableListOf()
    val nextGens: MutableList<Long> = mutableListOf()

    override suspend fun runNow(expedited: Boolean) {
        runGens += JobScheduler.dueGeneration()
        runs += expedited
    }

    override suspend fun armNext(delay: Duration) {
        nextGens += JobScheduler.dueGeneration()
        next += delay
    }

    override suspend fun armPeriodic(replace: Boolean) {
        periodic += replace
    }
}

/** The only fields [NextRun] and the worker look at; the rest is a valid but empty workflow. */
fun job(id: String, status: JobStatus, nextRunAt: Instant?, createdAt: Instant = FakeJobFacade.NOW): Job = Job(
    id = id,
    recordingId = "rec-$id",
    workflowId = "wf",
    workflow = Workflow(
        id = "wf",
        name = "wf",
        updatedAt = "2026-08-27T00:00:00.000Z",
        steps = emptyList(),
    ),
    status = status,
    createdAt = createdAt,
    updatedAt = createdAt,
    nextRunAt = nextRunAt,
)
