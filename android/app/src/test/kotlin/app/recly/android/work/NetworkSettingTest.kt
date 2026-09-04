@file:OptIn(ExperimentalTime::class)

package app.recly.android.work

import androidx.work.NetworkType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import recly.core.job.JobStatus

/**
 * Review finding 4. A queued request keeps the constraint it was built with, so the toggle is only
 * real if everything standing is rebuilt — which is a scheduling decision, and testable here.
 * That the rebuilt requests actually carry the new `NetworkType` is WorkManager's side of the line
 * and is checked on the emulator with `dumpsys jobscheduler`.
 */
class NetworkSettingTest {

    private val now = Instant.parse("2026-08-27T10:00:00Z")

    @Test
    fun `the toggle picks the network type docs 11 A5 asks for`() {
        assertEquals(NetworkType.UNMETERED, WorkScheduler.networkType(wifiOnly = true))
        assertEquals(NetworkType.CONNECTED, WorkScheduler.networkType(wifiOnly = false))
    }

    @Test
    fun `changing it rebuilds both standing names and re-arms the successor`() = runTest {
        val scheduler = FakeScheduler()
        val jobs = listOf(
            job("waiting", JobStatus.WAITING, nextRunAt = now + 8.minutes),
            job("done", JobStatus.DONE, nextRunAt = null),
        )

        applyNetworkSetting(scheduler, jobs, now)

        assertEquals(listOf(true), scheduler.periodic, "rec-jobs-periodic is replaced, not kept")
        assertEquals(listOf(8.minutes), scheduler.next, "rec-jobs-next is re-armed from the table")
        // KEEP, not REPLACE: cancelling `rec-jobs` would stop an upload in flight, and the
        // re-armed successor carries the queue with the new constraint anyway.
        assertEquals(listOf(false), scheduler.runs)
    }

    @Test
    fun `changing it with an empty queue still rebuilds the insurance`() = runTest {
        val scheduler = FakeScheduler()

        applyNetworkSetting(scheduler, emptyList(), now)

        assertEquals(listOf(true), scheduler.periodic)
        assertTrue(scheduler.next.isEmpty(), "nothing to come back for, and nothing to cancel either")
    }

    @Test
    fun `a due signal ticks the generation before anything else happens`() = runTest {
        // The order is the contract: a pass in flight has to be able to see the signal even if the
        // job itself landed after it read the table.
        val scheduler = FakeScheduler()
        val before = JobScheduler.dueGeneration()

        scheduler.onJobsDue()

        // Observed inside runNow()/armNext(): the signal must already be visible to a pass in
        // flight when either call happens, not merely after onJobsDue() returns.
        assertTrue(scheduler.runGens.isNotEmpty() && scheduler.runGens.all { it > before }, "runNow saw ${scheduler.runGens}, before=$before")
        assertTrue(scheduler.nextGens.isNotEmpty() && scheduler.nextGens.all { it > before }, "armNext saw ${scheduler.nextGens}, before=$before")
    }

    @Test
    fun `a job becoming due wakes a pass and guarantees a successor`() = runTest {
        // Review finding 5(a): `rec-jobs` is KEEP, so an enqueue during an active pass may wake
        // nothing at all. The zero-delay successor is what makes that safe.
        val scheduler = FakeScheduler()

        scheduler.onJobsDue()

        assertEquals(listOf(false), scheduler.runs)
        assertEquals(listOf(Duration.ZERO), scheduler.next)
    }

    @Test
    fun `upload now asks for the same thing, expedited`() = runTest {
        val scheduler = FakeScheduler()

        scheduler.onJobsDue(expedited = true)

        assertEquals(listOf(true), scheduler.runs)
        assertEquals(listOf(Duration.ZERO), scheduler.next)
    }
}
