@file:OptIn(ExperimentalTime::class)

package recly.core.drive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import recly.core.job.Executor
import recly.core.job.JobStatus
import recly.core.job.JobStore
import recly.core.job.Retention
import recly.core.job.StepStatus
import recly.core.job.defaultRunners
import recly.core.model.Retry
import recly.core.model.Step
import recly.core.testing.START

/** The lane's end-to-end check: a real job, a real executor, a scripted Drive. */
class DriveJobTest {
    @Test
    fun `a drive upload step runs to DONE and the sweep takes the parts a week on`() = runBlocking {
        val h = DriveHarness(partCount = 2, partBytes = DriveHarness.SMALL_BYTES)
        h.register()
        val store = JobStore(h.db, h.deps)
        val executor = Executor(h.deps, store, h.recordings, defaultRunners(h.db, h.deps))
        val job = assertNotNull(store.enqueue(h.recordingId, h.workflow, START))

        executor.runDueJobs(START)

        assertEquals(JobStatus.DONE, assertNotNull(store.get(job.id)).status)
        val step = store.stepsOf(job.id).single()
        assertEquals(StepStatus.SUCCEEDED, step.status)
        val output = assertNotNull(step.output)
        assertEquals(h.drive.idOf(h.base), output["folderId"]?.jsonPrimitive?.content)
        assertEquals(3, assertNotNull(output["files"]).jsonArray.size)

        // ADR-017: uploaded, so the audio may go — but as a cache with a window on it, not at
        // the moment the job finishes.
        assertTrue(h.fs.exists(h.dir / h.partName(1)))
        Retention(h.deps, store, h.recordings).sweep(START + Retention.WINDOW)

        assertFalse(h.fs.exists(h.dir / h.partName(1)))
        assertFalse(h.fs.exists(h.dir / h.partName(2)))
        assertTrue(h.fs.exists(h.dir / h.metaName()))
        assertTrue(h.logger.events.contains("job.done"))
    }

    @Test
    fun `a 5xx parks the job in WAITING with its resume point saved`() = runBlocking {
        val h = DriveHarness(partCount = 1)
        h.register()
        h.drive.failNext(503) { r ->
            r.url.startsWith(recly.core.testing.FakeDrive.SESSION_PREFIX) && h.drive.chunksSoFar(r.url) == 1
        }
        val store = JobStore(h.db, h.deps)
        val executor = Executor(h.deps, store, h.recordings, defaultRunners(h.db, h.deps))
        val job = assertNotNull(store.enqueue(h.recordingId, h.workflow, START))

        executor.runDueJobs(START)

        val parked = assertNotNull(store.get(job.id))
        assertEquals(JobStatus.WAITING, parked.status)
        val step = store.stepsOf(job.id).single()
        assertEquals(1, step.attempts)
        val saved = DriveUploadState.from(assertNotNull(step.state)).files.getValue("p001_mono")
        assertEquals(1024L * 1024, saved.offset)
        assertNotNull(saved.sessionUri)

        // The retry resumes from the saved offset instead of starting the file again.
        h.drive.clearFaults()
        executor.runDueJobs(assertNotNull(parked.nextRunAt))

        assertEquals(JobStatus.DONE, assertNotNull(store.get(job.id)).status)
        assertEquals(1, h.sessionStarts().size)
        assertEquals(1, h.queryRequests().size)
    }

    @Test
    fun `a Retry-After under the cap is taken at face value`() = runBlocking {
        // The default backoff for a first attempt is 30 s +- 20 %, so 45 can only come from the header.
        assertEquals(START + 45.seconds, parkedByRetryAfter("45"))
    }

    @Test
    fun `a Retry-After beyond the default cap is trimmed to maxDelaySec`() = runBlocking {
        assertEquals(START + 3600.seconds, parkedByRetryAfter("7200"))
    }

    @Test
    fun `the cap is the step's own maxDelaySec, not the default one`() = runBlocking {
        assertEquals(START + 120.seconds, parkedByRetryAfter("600", maxDelaySec = 120))
    }

    /**
     * Runs a job whose only upload is answered with `429` + this `Retry-After`, and returns when
     * the queue will pick it up again. The step row and the job row have to agree on that instant.
     */
    private suspend fun parkedByRetryAfter(retryAfter: String, maxDelaySec: Int? = null): Instant {
        val h = DriveHarness(partCount = 1, partBytes = DriveHarness.SMALL_BYTES)
        h.register()
        h.drive.failNext(429, headers = mapOf("Retry-After" to retryAfter)) {
            it.path == "/upload/drive/v3/files" && it.uploadType == "multipart"
        }
        val workflow = if (maxDelaySec == null) {
            h.workflow
        } else {
            h.workflow.copy(steps = listOf(Step.DriveUpload(id = "up", retry = Retry(maxDelaySec = maxDelaySec))))
        }
        val store = JobStore(h.db, h.deps)
        val executor = Executor(h.deps, store, h.recordings, defaultRunners(h.db, h.deps))
        val job = assertNotNull(store.enqueue(h.recordingId, workflow, START))

        executor.runDueJobs(START)

        val parked = assertNotNull(store.get(job.id))
        assertEquals(JobStatus.WAITING, parked.status)
        val step = store.stepsOf(job.id).single()
        assertEquals(1, step.attempts)
        assertEquals(parked.nextRunAt, step.nextAttemptAt)
        return assertNotNull(step.nextAttemptAt)
    }
}
