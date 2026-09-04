@file:OptIn(ExperimentalTime::class)

package app.recly.windows.jobs

import app.recly.windows.NOW
import app.recly.windows.job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import recly.core.job.JobStatus

/** Deliverable 7: the successor arithmetic (the phone's `NextRunTest`, same rules). */
class NextRunTest {

    @Test
    fun `an empty queue arms nothing`() {
        assertNull(NextRun.delay(emptyList(), NOW))
    }

    @Test
    fun `only jobs a person can unblock arm nothing`() {
        // The one case in which nothing is armed: none of these comes back on its own, and a timer
        // for them would fire forever without changing anything.
        val jobs = listOf(
            job("done", JobStatus.DONE),
            job("failed", JobStatus.FAILED),
            job("auth", JobStatus.NEEDS_AUTH),
            job("short", JobStatus.SKIPPED_SHORT),
            job("running", JobStatus.RUNNING),
        )
        assertNull(NextRun.delay(jobs, NOW))
    }

    @Test
    fun `a pending job is due immediately`() {
        assertEquals(Duration.ZERO, NextRun.delay(listOf(job("p", JobStatus.PENDING)), NOW))
    }

    @Test
    fun `the earliest of the waiting backoffs wins`() {
        val jobs = listOf(
            job("late", JobStatus.WAITING, nextRunAt = NOW + 30.minutes),
            job("soon", JobStatus.WAITING, nextRunAt = NOW + 2.minutes),
            job("later", JobStatus.WAITING, nextRunAt = NOW + 5.minutes),
        )
        assertEquals(2.minutes, NextRun.delay(jobs, NOW))
    }

    @Test
    fun `a pending job beats a future backoff`() {
        val jobs = listOf(
            job("waiting", JobStatus.WAITING, nextRunAt = NOW + 2.minutes),
            job("pending", JobStatus.PENDING),
        )
        assertEquals(Duration.ZERO, NextRun.delay(jobs, NOW))
    }

    @Test
    fun `a backoff that elapsed during a long pass is due now, not never`() {
        // A filter on `> now` would drop it and the queue would stall until the five-minute timer.
        val jobs = listOf(job("overdue", JobStatus.WAITING, nextRunAt = NOW - 5.minutes))
        assertEquals(Duration.ZERO, NextRun.delay(jobs, NOW))
    }

    @Test
    fun `a waiting job with no instant is treated as due`() {
        // A lost write, not a job to strand.
        assertEquals(Duration.ZERO, NextRun.delay(listOf(job("w", JobStatus.WAITING)), NOW))
    }
}
