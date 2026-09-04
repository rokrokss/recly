@file:OptIn(ExperimentalTime::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.recly.windows.jobs

import app.recly.windows.NOW
import app.recly.windows.SilentLogger
import app.recly.windows.job
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import recly.core.job.Job
import recly.core.job.JobStatus
import recly.core.job.RunSummary

/**
 * Deliverable 7: the two invariants of [JobRunner] — every pass leaves a successor behind it, and a
 * pass that found the core busy reshapes nothing. The queue is a double, which is the point: what is
 * under test is the wiring, not the executor.
 */
class JobRunnerTest {

    @Test
    fun `a parked job arms the successor at its backoff`() = runTest {
        val queue = FakeQueue(after = listOf(job("waiting", JobStatus.WAITING, nextRunAt = NOW + 12.minutes)))
        val armed = mutableListOf<Duration>()

        runner(queue, armed).pass()

        assertEquals(1, queue.runs)
        assertEquals(listOf(12.minutes), armed)
    }

    @Test
    fun `a queue nothing will unblock arms nothing and cancels nothing`() = runTest {
        val queue = FakeQueue(after = listOf(job("auth", JobStatus.NEEDS_AUTH)))
        val armed = mutableListOf<Duration>()

        runner(queue, armed).pass()

        assertTrue(armed.isEmpty(), "nothing to come back for — and a standing successor is left alone")
    }

    @Test
    fun `a pass that found the core busy comes back in a minute`() = runTest {
        // It saw no queue of its own, so all it may promise is that someone comes back.
        val queue = FakeQueue(summary = RunSummary(alreadyRunning = true), after = listOf(job("p", JobStatus.PENDING)))
        val armed = mutableListOf<Duration>()

        runner(queue, armed).pass()

        assertEquals(listOf(JobRunner.FOLLOW_UP), armed)
        assertEquals(0, queue.reads, "a busy pass does not read the queue it never ran")
    }

    @Test
    fun `a job enqueued while a busy pass ran is not left waiting a minute`() = runTest {
        val armed = mutableListOf<Duration>()
        lateinit var runner: JobRunner
        val queue = FakeQueue(
            summary = RunSummary(alreadyRunning = true),
            whileRunning = { runner.signalDue() },
        )
        runner = runner(queue, armed)

        runner.pass()

        assertEquals(listOf(Duration.ZERO), armed)
    }

    @Test
    fun `a job enqueued while the queue was being read arms an immediate successor`() = runTest {
        // The generation counter is the only evidence the pass has: the job landed in neither the
        // run nor the read, and comparing before the read would look right and miss it.
        val armed = mutableListOf<Duration>()
        lateinit var runner: JobRunner
        val queue = FakeQueue(after = emptyList(), whileReading = { runner.signalDue() })
        runner = runner(queue, armed)

        runner.pass()

        assertEquals(listOf(Duration.ZERO), armed)
    }

    @Test
    fun `a pass that could not run arms nothing`() = runTest {
        // The five-minute timer is the retry; a successor armed off a queue we failed to read would
        // be armed off nothing.
        val queue = FakeQueue(failWith = IOException("no database"))
        val armed = mutableListOf<Duration>()

        runner(queue, armed).pass()

        assertTrue(armed.isEmpty())
    }

    @Test
    fun `the queue after the pass is handed to the tray`() = runTest {
        val jobs = listOf(job("auth", JobStatus.NEEDS_AUTH))
        val queue = FakeQueue(after = jobs)
        var seen: List<Job>? = null

        JobRunner(queue, this, SilentLogger, arm = {}, onPass = { seen = it }).pass()

        assertEquals(jobs, seen)
    }

    @Test
    fun `a busy pass does not cancel the pass that is already running`() = runTest {
        // Sol review 2 #1. The successor is a timer until it fires and a pass afterwards, and
        // `armNext` replaces timers. Cancelling the pass in flight would abort the core step it is
        // in the middle of and leave that row RUNNING for the next pass to recover.
        val held = CompletableDeferred<Unit>()
        var completed = false
        val queue = SequencedQueue(
            onRun = { call ->
                when (call) {
                    // The first pass finds one PENDING job and arms a successor for it.
                    1 -> RunSummary(jobIds = listOf("j1"))
                    // The successor's own pass, held open while something else comes in.
                    2 -> {
                        held.await()
                        completed = true
                        RunSummary(jobIds = listOf("j1"))
                    }
                    // The overlapping `jobsDue`, which finds the core busy.
                    3 -> RunSummary(alreadyRunning = true)
                    else -> RunSummary()
                }
            },
        )
        // The real successor, not the test double: what is under test is the timer bookkeeping.
        val runner = JobRunner(queue, this, SilentLogger)

        runner.pass()
        runCurrent()
        runner.jobsDue()
        runCurrent()
        held.complete(Unit)
        advanceUntilIdle()

        assertTrue(completed, "the pass in flight ran to the end")
        // The busy pass armed one of its own, and it ran: the queue was not left without a successor.
        assertTrue(queue.runs >= 4, "a successor was still armed after the busy pass")
    }

    private fun CoroutineScope.runner(queue: FakeQueue, armed: MutableList<Duration>) =
        JobRunner(queue, this, SilentLogger, arm = { armed += it })
}

/** Answers each pass by its ordinal, so a test can hold exactly one of them open. */
private class SequencedQueue(private val onRun: suspend (Int) -> RunSummary) : JobQueue {
    private val calls = AtomicInteger()

    val runs: Int get() = calls.get()

    override fun now(): Instant = NOW

    override suspend fun runDueJobs(): RunSummary = onRun(calls.incrementAndGet())

    /** One PENDING job for the first pass to arm a successor from, and nothing afterwards. */
    override suspend fun jobs(): List<Job> =
        if (calls.get() == 1) listOf(job("pending", JobStatus.PENDING)) else emptyList()
}

private class FakeQueue(
    private val summary: RunSummary = RunSummary(jobIds = listOf("j1")),
    /** The job table as the pass will find it *after* running. */
    private val after: List<Job> = emptyList(),
    private val failWith: Throwable? = null,
    private val whileRunning: () -> Unit = {},
    /** Runs inside `jobs()` — the window a real `jobsDue()` can land in. */
    private val whileReading: () -> Unit = {},
) : JobQueue {
    var runs = 0
        private set
    var reads = 0
        private set

    override fun now(): Instant = NOW

    override suspend fun runDueJobs(): RunSummary {
        runs++
        whileRunning()
        failWith?.let { throw it }
        return summary
    }

    override suspend fun jobs(): List<Job> {
        reads++
        whileReading()
        return after
    }
}
