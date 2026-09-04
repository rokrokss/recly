@file:OptIn(ExperimentalTime::class)

package recly.core.recording

import app.cash.sqldelight.Query
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import recly.core.drive.DriveHarness
import recly.core.job.Executor
import recly.core.job.JobStatus
import recly.core.job.JobStore
import recly.core.job.defaultRunners
import recly.core.testing.START

/**
 * docs/03 "앱에서 지우기": what one recording leaves behind, and what the Drive half of the dialog
 * does — including the case where Drive says no and the local half has already happened.
 */
class RecordingDeleteTest {

    @Test
    fun `deleting a recording that was never uploaded takes its files and every row`() = runBlocking {
        val h = DriveHarness(partCount = 2, partBytes = DriveHarness.SMALL_BYTES)
        h.register()
        val store = JobStore(h.db, h.deps)
        val job = assertNotNull(store.enqueue(h.recordingId, h.workflow, START))

        val result = h.recordings.delete(h.recordingId, deleteDrive = false)

        assertEquals(DeleteResult.Deleted(driveDeleted = false), result)
        assertNull(h.recordings.get(h.recordingId))
        assertNull(store.get(job.id))
        assertEquals(emptyList(), store.stepsOf(job.id))
        assertEquals(emptyList(), h.db.recQueries.selectPartsByRecording(h.recordingId).executeAsList())
        assertFalse(h.fs.exists(h.dir), "the directory goes with the parts, the meta and the results")
        assertEquals(emptyList(), h.drive.deleted, "nothing on Drive was asked about")
    }

    /** The dialog's default is "로컬만 삭제", and that must never reach `files.delete`. */
    @Test
    fun `an uploaded recording deleted without the Drive box leaves the Drive folder alone`() = runBlocking {
        val h = uploaded()

        val result = h.recordings.delete(h.recordingId, deleteDrive = false)

        assertEquals(DeleteResult.Deleted(driveDeleted = false), result)
        assertEquals(emptyList(), h.drive.deleted)
        assertNotNull(h.drive.idOf(h.base), "the folder is still there")
        assertFalse(h.fs.exists(h.dir))
    }

    @Test
    fun `the Drive box deletes the recording's own folder`() = runBlocking {
        val h = uploaded()
        val folderId = assertNotNull(h.drive.idOf(h.base))

        val result = h.recordings.delete(h.recordingId, deleteDrive = true)

        assertEquals(DeleteResult.Deleted(driveDeleted = true), result)
        assertEquals(listOf(folderId), h.drive.deleted, "the recording's folder, not the month's")
        assertFalse(h.fs.exists(h.dir))
    }

    @Test
    fun `a Drive that refuses does not hold up the local deletion, and says so`() = runBlocking {
        val h = uploaded()
        h.drive.failNext(500, times = 2) { it.method == "DELETE" }

        val result = h.recordings.delete(h.recordingId, deleteDrive = true)

        val deleted = assertIs<DeleteResult.Deleted>(result)
        assertFalse(deleted.driveDeleted)
        assertTrue(assertNotNull(deleted.driveError).contains("500"), "what Drive said, for the notice")
        assertNull(h.recordings.get(h.recordingId))
        assertFalse(h.fs.exists(h.dir), "the local copy goes: there is nothing left to retry from")
    }

    @Test
    fun `a recording with a RUNNING job is refused and keeps everything`() = runBlocking {
        val h = DriveHarness(partCount = 1, partBytes = DriveHarness.SMALL_BYTES)
        h.register()
        val store = JobStore(h.db, h.deps)
        val job = assertNotNull(store.enqueue(h.recordingId, h.workflow, START))
        store.updateJob(job.id, JobStatus.RUNNING, null, START)

        assertEquals(DeleteResult.Busy, h.recordings.delete(h.recordingId, deleteDrive = true))

        assertNotNull(h.recordings.get(h.recordingId))
        assertNotNull(store.get(job.id))
        assertTrue(h.fs.exists(h.dir / h.partName(1)))
        assertEquals(emptyList(), h.drive.deleted)
    }

    /**
     * The commit and the files it orphans are one pass. A caller that goes away the instant the
     * rows land — the WorkManager job stopped, the screen closed — must not leave a directory
     * behind that nothing points at any more: the cleanup does not get to be skipped.
     *
     * The hook is a query listener, which SQLDelight fires on the committing thread the moment the
     * transaction commits: precisely the gap this is about.
     */
    @Test
    fun `a cancellation the moment the rows commit still takes the directory`() = runBlocking {
        val h = DriveHarness(partCount = 1, partBytes = DriveHarness.SMALL_BYTES)
        h.register()
        var deleting: Deferred<DeleteResult>? = null
        h.db.recQueries.selectRecordings(1).addListener(Query.Listener { deleting?.cancel() })

        deleting = async(start = CoroutineStart.LAZY) { h.recordings.delete(h.recordingId, deleteDrive = false) }
        deleting.join()

        assertTrue(deleting.isCancelled, "the caller went away with the commit")
        assertNull(h.recordings.get(h.recordingId), "the rows are gone")
        assertFalse(h.fs.exists(h.dir), "and so is the directory they named")
    }

    @Test
    fun `deleting a recording that is not there is NotFound`() = runBlocking {
        val h = DriveHarness(partCount = 1, partBytes = DriveHarness.SMALL_BYTES)

        assertEquals(DeleteResult.NotFound, h.recordings.delete(h.recordingId, deleteDrive = true))
    }

    /**
     * docs/10 "동시성": the executor's claim and this deletion are each one transaction, and SQLite
     * has one writer — so one of them commits first and the other sees it. Both orders here, and
     * neither of them ends with a run over files that are gone.
     */
    @Test
    fun `a job claimed and a delete cannot both win`() = runBlocking {
        // The claim first: the deletion that follows finds a RUNNING job and touches nothing.
        val claimed = DriveHarness(partCount = 1, partBytes = DriveHarness.SMALL_BYTES)
        claimed.register()
        val claimedStore = JobStore(claimed.db, claimed.deps)
        val claimedJob = assertNotNull(claimedStore.enqueue(claimed.recordingId, claimed.workflow, START))
        assertTrue(claimedStore.claimRunning(claimedJob.id, START))

        assertEquals(DeleteResult.Busy, claimed.recordings.delete(claimed.recordingId, deleteDrive = true))
        assertNotNull(claimed.recordings.get(claimed.recordingId))
        assertNotNull(claimedStore.get(claimedJob.id))
        assertTrue(claimed.fs.exists(claimed.dir / claimed.partName(1)))
        assertEquals(emptyList(), claimed.drive.deleted)

        // The deletion first: there is no job row left to claim, so nothing runs over the gap.
        val gone = DriveHarness(partCount = 1, partBytes = DriveHarness.SMALL_BYTES)
        gone.register()
        val goneStore = JobStore(gone.db, gone.deps)
        val goneJob = assertNotNull(goneStore.enqueue(gone.recordingId, gone.workflow, START))

        assertIs<DeleteResult.Deleted>(gone.recordings.delete(gone.recordingId, deleteDrive = false))
        assertFalse(goneStore.claimRunning(goneJob.id, START))
        assertNull(goneStore.get(goneJob.id))
        val executor = Executor(gone.deps, goneStore, gone.recordings, defaultRunners(gone.db, gone.deps))
        assertEquals(emptyList(), executor.runDueJobs(START).jobIds, "nothing is due any more")
        assertEquals(emptyList(), gone.drive.requests, "and nothing was uploaded")
    }

    /** A recording whose `drive.upload` step ran to completion — the folder id is in its output. */
    private suspend fun uploaded(): DriveHarness {
        val h = DriveHarness(partCount = 1, partBytes = DriveHarness.SMALL_BYTES)
        h.register()
        val store = JobStore(h.db, h.deps)
        val executor = Executor(h.deps, store, h.recordings, defaultRunners(h.db, h.deps))
        val job = assertNotNull(store.enqueue(h.recordingId, h.workflow, START))
        executor.runDueJobs(START)
        assertEquals(JobStatus.DONE, assertNotNull(store.get(job.id)).status)
        return h
    }
}
