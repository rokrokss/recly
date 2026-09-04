@file:OptIn(ExperimentalTime::class)

package app.recly.android.work

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import recly.core.job.JobStatus

/** Deliverable 3: the arithmetic behind `rec-jobs-next`. */
class NextRunTest {

    private val now = Instant.parse("2026-08-27T10:00:00Z")

    @Test
    fun `an empty queue arms nothing`() {
        assertNull(NextRun.delay(emptyList(), now))
    }

    @Test
    fun `only a job a person can unblock arms nothing`() {
        // The one case in which the timer is allowed to be cancelled: nothing here comes back on
        // its own, and waking the device for it would be waking it forever.
        val jobs = listOf(
            job("done", JobStatus.DONE, nextRunAt = null),
            job("failed", JobStatus.FAILED, nextRunAt = null),
            job("auth", JobStatus.NEEDS_AUTH, nextRunAt = null),
            job("short", JobStatus.SKIPPED_SHORT, nextRunAt = null),
        )
        assertNull(NextRun.delay(jobs, now))
    }

    @Test
    fun `a pending job is due immediately`() {
        val jobs = listOf(job("pending", JobStatus.PENDING, nextRunAt = null))
        assertEquals(Duration.ZERO, NextRun.delay(jobs, now))
    }

    @Test
    fun `the earliest future backoff wins`() {
        val jobs = listOf(
            job("late", JobStatus.WAITING, nextRunAt = now + 30.minutes),
            job("soon", JobStatus.WAITING, nextRunAt = now + 2.minutes),
            job("later", JobStatus.WAITING, nextRunAt = now + 5.minutes),
        )
        assertEquals(2.minutes, NextRun.delay(jobs, now))
    }

    @Test
    fun `a backoff that elapsed during a long pass is due now, not never`() {
        // Review finding 3, and the negative that goes with it: an overdue WAITING job must NOT
        // fall out of the calculation the way a filter on `> now` would drop it.
        val jobs = listOf(job("overdue", JobStatus.WAITING, nextRunAt = now - 5.minutes))

        val delay = NextRun.delay(jobs, now)

        assertNotNull(delay, "an overdue job still needs a successor")
        assertEquals(Duration.ZERO, delay)
    }

    @Test
    fun `an overdue job beats a future one`() {
        val jobs = listOf(
            job("overdue", JobStatus.WAITING, nextRunAt = now - 1.minutes),
            job("future", JobStatus.WAITING, nextRunAt = now + 90.seconds),
        )
        assertEquals(Duration.ZERO, NextRun.delay(jobs, now))
    }

    @Test
    fun `a pending job beats a future backoff`() {
        val jobs = listOf(
            job("waiting", JobStatus.WAITING, nextRunAt = now + 30.minutes),
            job("pending", JobStatus.PENDING, nextRunAt = null),
        )
        assertEquals(Duration.ZERO, NextRun.delay(jobs, now))
    }

    @Test
    fun `a waiting job with no instant is treated as due rather than stranded`() {
        // A lost paired write (docs/10 "짝 전이") must not cost the job its scheduler.
        val jobs = listOf(job("halfWritten", JobStatus.WAITING, nextRunAt = null))
        assertEquals(Duration.ZERO, NextRun.delay(jobs, now))
    }

    @Test
    fun `a running row is left to the periodic worker`() {
        // Recovering it needs a pass, not a timer; boot, foreground and the six-hour insurance all
        // bring one, and a zero-delay timer here would wake the device to do the same nothing.
        assertNull(NextRun.delay(listOf(job("running", JobStatus.RUNNING, nextRunAt = null)), now))
    }

    @Test
    fun `the delay is the distance to the instant`() {
        assertEquals(90.seconds, NextRun.delay(now + 90.seconds, now))
    }

    @Test
    fun `a delay in the past is clamped to zero, not negative`() {
        // WorkManager rejects a negative initial delay; a stale instant must mean "run now".
        assertEquals(Duration.ZERO, NextRun.delay(now - 5.minutes, now))
    }
}
