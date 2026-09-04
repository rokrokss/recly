@file:OptIn(ExperimentalTime::class)

package app.recly.android.work

import android.content.Context
import android.content.ContextWrapper
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import recly.core.job.JobStatus
import recly.core.job.RunSummary

/**
 * Deliverable 6. The worker is built for real — `TestListenableWorkerBuilder` gives it the same
 * `WorkerParameters` WorkManager would — and only the core and the scheduler are doubles, which is
 * the point: what is under test is the mapping from a pass to a `Result` and to a successor.
 *
 * The `Context` is a stub the worker never calls into: production reaches `CoreModule` through it,
 * and the injected facade is exactly the branch that skips that.
 */
class WorkflowWorkerTest {

    private val context: Context = ContextWrapper(null)

    @Test
    fun `a pass that completes succeeds and runs the queue exactly once`() = runTest {
        val facade = FakeJobFacade(summary = RunSummary(jobIds = listOf("j1", "j2")))

        val result = worker(facade).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, facade.runs, "runDueJobs is the whole job of the worker")
    }

    @Test
    fun `a parked job arms the successor at its backoff`() = runTest {
        val now = FakeJobFacade.NOW
        val facade = FakeJobFacade(
            now = now,
            queueAfterRun = listOf(
                job("waiting", JobStatus.WAITING, nextRunAt = now + 12.minutes),
                job("auth", JobStatus.NEEDS_AUTH, nextRunAt = null),
            ),
        )

        assertEquals(ListenableWorker.Result.success(), worker(facade).doWork())
        assertEquals(listOf(12.minutes), facade.scheduled)
    }

    @Test
    fun `a job parked for sign-in is not retried by WorkManager`() = runTest {
        // NEEDS_AUTH is unblocked by the user, not by the scheduler: a retry here would spin the
        // radio against a wall until the backoff ceiling.
        val facade = FakeJobFacade(queueAfterRun = listOf(job("auth", JobStatus.NEEDS_AUTH, nextRunAt = null)))

        assertEquals(ListenableWorker.Result.success(), worker(facade).doWork())
        assertTrue(facade.scheduled.isEmpty(), "nothing to arm — and nothing is cancelled either")
    }

    @Test
    fun `an empty queue with no signal arms nothing and cancels nothing`() = runTest {
        // Round 2 (d). A stale successor left standing costs one pass that finds nothing; a cancel
        // would throw away a successor a concurrent enqueue may have just armed.
        val facade = FakeJobFacade(summary = RunSummary(), queueAfterRun = emptyList())

        assertEquals(ListenableWorker.Result.success(), worker(facade).doWork())
        assertTrue(facade.scheduled.isEmpty())
    }

    @Test
    fun `a due signal during the pass beats an empty queue read`() = runTest {
        // Round 2 (a), the P1. The job was enqueued after this pass read the table, so the read is
        // empty and honest — and stale. The generation is the only evidence left that it happened.
        val facade = FakeJobFacade(
            summary = RunSummary(),
            queueAfterRun = emptyList(),
            whileReadingJobs = { JobScheduler.signalDue() },
        )

        assertEquals(ListenableWorker.Result.success(), worker(facade).doWork())
        assertEquals(listOf(Duration.ZERO), facade.scheduled, "the successor the enqueue is owed")
    }

    @Test
    fun `a due signal during the pass beats a computed backoff`() = runTest {
        // Round 2 (b). Ten minutes is right for what the read saw and wrong for what arrived: the
        // new job would otherwise wait out someone else's backoff.
        val now = FakeJobFacade.NOW
        val facade = FakeJobFacade(
            now = now,
            queueAfterRun = listOf(job("waiting", JobStatus.WAITING, nextRunAt = now + 10.minutes)),
            whileReadingJobs = { JobScheduler.signalDue() },
        )

        assertEquals(ListenableWorker.Result.success(), worker(facade).doWork())
        assertEquals(listOf(Duration.ZERO), facade.scheduled)
    }

    @Test
    fun `no signal leaves the computed backoff alone`() = runTest {
        // Round 2 (c), and the negative that matters: the generation check must not swallow every
        // computed delay into zero and wake the device on every backoff.
        val now = FakeJobFacade.NOW
        val facade = FakeJobFacade(
            now = now,
            queueAfterRun = listOf(job("waiting", JobStatus.WAITING, nextRunAt = now + 10.minutes)),
        )

        assertEquals(ListenableWorker.Result.success(), worker(facade).doWork())
        assertEquals(listOf(10.minutes), facade.scheduled)
    }

    @Test
    fun `a signal from before the pass is not mistaken for one during it`() = runTest {
        // The counter is process-wide and never resets, so what matters is only whether it moved
        // *while this pass ran*. A signal that landed before the snapshot is already accounted for
        // by the pass itself and must not collapse every delay to zero.
        val facade = FakeJobFacade(summary = RunSummary(alreadyRunning = true))
        JobScheduler.signalDue()

        assertEquals(ListenableWorker.Result.success(), worker(facade).doWork())
        assertEquals(listOf(WorkflowWorker.FOLLOW_UP), facade.scheduled)
    }

    @Test
    fun `a job enqueued while the pass was in flight gets a zero-delay successor`() = runTest {
        // Review finding 5(b). `rec-jobs` is KEEP, so the enqueue that landed mid-pass may not have
        // woken anything of its own; the post-pass recompute is what guarantees it is not stranded.
        val facade = FakeJobFacade(
            summary = RunSummary(jobIds = listOf("the-pass")),
            queueAfterRun = listOf(
                job("done", JobStatus.DONE, nextRunAt = null),
                job("arrivedLate", JobStatus.PENDING, nextRunAt = null),
            ),
        )

        assertEquals(ListenableWorker.Result.success(), worker(facade).doWork())

        assertEquals(listOf(Duration.ZERO), facade.scheduled)
    }

    @Test
    fun `a pass that found the core busy arms a bounded follow-up and cancels nothing`() = runTest {
        // Review finding 1. This pass saw an empty run because another one holds the core's lock;
        // deciding anything from that snapshot would throw away the real pass's successor.
        val facade = FakeJobFacade(summary = RunSummary(alreadyRunning = true))

        val result = worker(facade).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf(WorkflowWorker.FOLLOW_UP), facade.scheduled)
        assertTrue(WorkflowWorker.FOLLOW_UP in Duration.ZERO..5.minutes, "the follow-up must stay bounded")
    }

    @Test
    fun `a pass that throws is retried`() = runTest {
        val facade = FakeJobFacade(failWith = IOException("no network"))

        assertEquals(ListenableWorker.Result.retry(), worker(facade).doWork())
        assertTrue(facade.scheduled.isEmpty(), "a pass that never finished says nothing about the next")
    }

    @Test
    fun `a one-time pass that keeps throwing eventually gives up`() = runTest {
        val facade = FakeJobFacade(failWith = IllegalStateException("database is gone"))

        val result = worker(facade, runAttemptCount = WorkflowWorker.MAX_ATTEMPTS).doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
    }

    @Test
    fun `the periodic worker never fails, so its six-hour cadence survives`() = runTest {
        // Review finding 2: WorkManager drops a periodic worker that returns failure, and the
        // insurance would be gone for the life of the install.
        val facade = FakeJobFacade(failWith = IllegalStateException("database is gone"))

        val result = worker(
            facade,
            runAttemptCount = WorkflowWorker.MAX_ATTEMPTS,
            input = workDataOf(WorkflowWorker.KEY_PERIODIC to true),
        ).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `a periodic pass is not a special case while it is working`() = runTest {
        val now = FakeJobFacade.NOW
        val facade = FakeJobFacade(now = now, queueAfterRun = listOf(job("w", JobStatus.WAITING, now + 3.minutes)))

        val result = worker(facade, input = workDataOf(WorkflowWorker.KEY_PERIODIC to true)).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf(3.minutes), facade.scheduled)
    }

    private fun worker(
        facade: JobFacade,
        runAttemptCount: Int = 0,
        input: Data = Data.EMPTY,
    ): WorkflowWorker =
        TestListenableWorkerBuilder<WorkflowWorker>(context)
            .setRunAttemptCount(runAttemptCount)
            .setInputData(input)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = WorkflowWorker(appContext, workerParameters, facade)
                },
            )
            .build()
}
