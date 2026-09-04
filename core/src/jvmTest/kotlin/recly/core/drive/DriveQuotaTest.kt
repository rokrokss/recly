@file:OptIn(ExperimentalTime::class)

package recly.core.drive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import recly.core.job.Executor
import recly.core.job.JobService
import recly.core.job.JobStatus
import recly.core.job.JobStore
import recly.core.job.StepFailure
import recly.core.job.StepStatus
import recly.core.job.defaultRunners
import recly.core.message.CoreMessage
import recly.core.message.CoreMessageRef
import recly.core.recording.DeleteResult
import recly.core.testing.FakeDrive
import recly.core.testing.START
import recly.core.transcribe.TranscribeHarness

/**
 * docs/10 "Drive 용량 초과": a full Drive is not a failure to retry, it is a state to park in. The
 * lane's acceptance criteria 1, 2 and 3 are the three tests at the top.
 */
class DriveQuotaTest {

    @Test
    fun `a storageQuotaExceeded 403 parks the job in NEEDS_SPACE without spending an attempt`() = runBlocking {
        val h = DriveHarness(partCount = 1)
        h.register()
        h.drive.failNext(403, times = FOREVER, body = QUOTA_BODY) { it.url.startsWith(FakeDrive.SESSION_PREFIX) }
        val store = JobStore(h.db, h.deps)
        val executor = Executor(h.deps, store, h.recordings, defaultRunners(h.db, h.deps))
        val jobs = JobService(h.deps, store, h.recordings, executor)
        val job = assertNotNull(store.enqueue(h.recordingId, h.workflow, START))

        executor.runDueJobs(START)

        val parked = assertNotNull(store.get(job.id))
        assertEquals(JobStatus.NEEDS_SPACE, parked.status)
        assertNull(parked.nextRunAt, "a parked job must not be due again on its own")
        val step = store.stepsOf(job.id).single()
        assertEquals(StepStatus.NEEDS_SPACE, step.status)
        assertEquals(0, step.attempts, "waiting is not what fixes this, so no attempt is spent")
        assertNull(step.nextAttemptAt)
        assertNull(step.state, "the resumable session is dropped, not resumed a week later")
        val ref = assertNotNull(CoreMessageRef.parse(assertNotNull(step.lastError)))
        assertEquals(CoreMessage.DRIVE_STORAGE_FULL, ref.message)
        assertNull(ref.arg)
        assertTrue(ref.detail.orEmpty().contains("storageQuotaExceeded"), "Drive's own words ride along")

        // The scheduler must not pick it up again — `runDueJobs` selects PENDING and WAITING only.
        val requests = h.drive.requests.size
        repeat(10) { executor.runDueJobs(START) }
        assertEquals(requests, h.drive.requests.size, "nothing was sent on the ten passes after the park")
        assertEquals(JobStatus.NEEDS_SPACE, assertNotNull(store.get(job.id)).status)

        // "다시 시도" after the user made room: the upload starts over and finishes.
        h.drive.clearFaults()
        assertTrue(jobs.retry(job.id))
        executor.runDueJobs(START)

        assertEquals(JobStatus.DONE, assertNotNull(store.get(job.id)).status)
        assertEquals(h.partBytes, h.drive.byName(h.partName(1))?.content?.size?.toLong())
    }

    /** ADR-017: `NEEDS_SPACE` is not DONE, so the parts that could not go up are still here. */
    @Test
    fun `the parts of a job waiting for space are neither deleted nor claimed`() = runBlocking {
        val h = DriveHarness(partCount = 2)
        h.register()
        h.drive.failNext(403, times = FOREVER, body = QUOTA_BODY) { it.url.startsWith(FakeDrive.SESSION_PREFIX) }
        val store = JobStore(h.db, h.deps)
        val executor = Executor(h.deps, store, h.recordings, defaultRunners(h.db, h.deps))
        val job = assertNotNull(store.enqueue(h.recordingId, h.workflow, START))

        executor.runDueJobs(START)

        assertEquals(JobStatus.NEEDS_SPACE, assertNotNull(store.get(job.id)).status)
        val parts = h.db.recQueries.selectPartsByRecording(h.recordingId).executeAsList()
        assertEquals(2, parts.size)
        assertTrue(parts.all { it.deleted == 0L }, "no purge claim on a job that is not DONE")
        assertTrue(h.fs.exists(h.dir / h.partName(1)))
        assertTrue(h.fs.exists(h.dir / h.partName(2)))
    }

    /** The other 403s are about permission, and those stay on the path they were already on. */
    @Test
    fun `a permission 403 is not NEEDS_SPACE`() = runBlocking {
        val h = DriveHarness(partCount = 1)
        h.register()
        h.drive.failNext(403, times = FOREVER, body = PERMISSION_BODY) {
            it.url.startsWith(FakeDrive.SESSION_PREFIX)
        }
        val store = JobStore(h.db, h.deps)
        val executor = Executor(h.deps, store, h.recordings, defaultRunners(h.db, h.deps))
        val job = assertNotNull(store.enqueue(h.recordingId, h.workflow, START))

        executor.runDueJobs(START)

        assertEquals(JobStatus.FAILED, assertNotNull(store.get(job.id)).status)
        val step = store.stepsOf(job.id).single()
        assertEquals(StepStatus.FAILED, step.status)
        assertEquals(1, step.attempts, "an ordinary failure does spend one")
        val ref = assertNotNull(CoreMessageRef.parse(assertNotNull(step.lastError)))
        assertEquals(CoreMessage.STEP_FAILED, ref.message)
        assertTrue(h.fs.exists(h.dir / h.partName(1)), "a failed job keeps its audio")
    }

    /**
     * The judgement is [DriveApi]'s and not the upload runner's, so every call it makes is covered
     * — including the `meta.json`/small-part multipart and the folder creation.
     */
    @Test
    fun `the same body parks a multipart upload and a folder creation`() = runBlocking {
        val h = DriveHarness(partCount = 1, partBytes = DriveHarness.SMALL_BYTES)
        h.drive.failNext(403, times = FOREVER, body = QUOTA_BODY) {
            it.path == "/upload/drive/v3/files" || (it.method == "POST" && it.path == "/drive/v3/files")
        }

        val onCreate = assertFailsWith<StepFailure> { h.api.createFolder("2026", "root") }
        assertTrue(onCreate.needsSpace)
        assertFalse(onCreate.retryable)
        val onUpload = assertFailsWith<StepFailure> {
            h.api.multipartUpload(DriveFileMeta("f.m4a", listOf("root"), "audio/mp4"), ByteArray(8))
        }
        assertTrue(onUpload.needsSpace)
    }

    /** docs/10: "이 판정은 `transcribe`의 Drive 쓰기에도 같이 적용된다." */
    @Test
    fun `a transcribe result file that will not fit is NEEDS_SPACE too`() = runBlocking {
        val h = TranscribeHarness(partCount = 1)
        h.drive.failNext(403, times = FOREVER, body = QUOTA_BODY) { it.path == "/upload/drive/v3/files" }

        val failure = assertFailsWith<StepFailure> { h.runToDone(h.transcribeStep()) }

        assertTrue(failure.needsSpace)
        assertFalse(failure.retryable)
        assertEquals(CoreMessage.DRIVE_STORAGE_FULL, assertNotNull(CoreMessageRef.parse(failure.reason)).message)
    }

    /**
     * docs/03 "Drive에서도 삭제" after a park: `parkNeedsSpace` drops `state_json`, so the folder the
     * upload created has to have been written somewhere that survives — before the first chunk
     * went out, because that is the request that failed.
     */
    @Test
    fun `the Drive box still finds the folder of a recording parked for space`() = runBlocking {
        val h = DriveHarness(partCount = 1)
        h.register()
        h.drive.failNext(403, times = FOREVER, body = QUOTA_BODY) { it.url.startsWith(FakeDrive.SESSION_PREFIX) }
        val store = JobStore(h.db, h.deps)
        val executor = Executor(h.deps, store, h.recordings, defaultRunners(h.db, h.deps))
        val job = assertNotNull(store.enqueue(h.recordingId, h.workflow, START))

        executor.runDueJobs(START)

        assertEquals(JobStatus.NEEDS_SPACE, assertNotNull(store.get(job.id)).status)
        val step = store.stepsOf(job.id).single()
        assertNull(step.state, "the resume state, folder id and all, went with the park")
        assertEquals(
            h.drive.idOf(h.base),
            assertNotNull(step.output)["folderId"]?.jsonPrimitive?.content,
            "and the folder id is in the output instead",
        )
        val folderId = assertNotNull(h.drive.idOf(h.base))
        h.drive.clearFaults()

        val result = h.recordings.delete(h.recordingId, deleteDrive = true)

        assertEquals(DeleteResult.Deleted(driveDeleted = true), result)
        assertEquals(listOf(folderId), h.drive.deleted, "the recording's own folder, after the park")
        assertNull(h.recordings.get(h.recordingId))
    }

    private companion object {
        /** More than any one test sends, so the fault stands for the whole scenario. */
        const val FOREVER = 1000

        /** What Drive answers a write with when the account is full. */
        val QUOTA_BODY = """
            {"error":{"errors":[{"domain":"usageLimits","reason":"storageQuotaExceeded",
            "message":"The user's Drive storage quota has been exceeded."}],
            "code":403,"message":"The user's Drive storage quota has been exceeded."}}
        """.trimIndent().replace("\n", "")

        /** The 403 that is about the grant, not the space. */
        val PERMISSION_BODY = """
            {"error":{"errors":[{"domain":"global","reason":"insufficientFilePermissions",
            "message":"The user does not have sufficient permissions for this file."}],"code":403}}
        """.trimIndent().replace("\n", "")
    }
}
