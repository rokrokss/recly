@file:OptIn(ExperimentalTime::class)

package recly.core.job

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.runBlocking
import recly.core.model.Track
import recly.core.testing.driveStep
import recly.core.testing.testMeta
import recly.core.testing.webhookStep

/**
 * "Does Drive hold every part of this recording?" — the signal the delete dialog and the disconnect
 * warning need now that a part still on disk may be nothing but a cache of one Drive already has
 * ([Retention]). A different question from the retention rule, and answered on its own terms.
 */
class UploadedTest {
    /** What a mixed-down desktop recording leaves on disk (docs/12): three tracks, not one. */
    private val threeTracks = listOf(Track.MIC, Track.SYS, Track.MIX)

    private fun uploader() = ScriptedRunner("drive.upload") { ctx, _ -> uploadOutput(ctx) }

    @Test
    fun `a recording whose upload job is DONE is uploaded`() = runBlocking {
        val f = Fixture(listOf(uploader()))
        val recording = f.seed()
        f.enqueue(recording, driveStep("up"))

        assertFalse(f.store.uploaded(recording.id), "the job has not run yet")

        f.service.runDueJobs()

        assertTrue(f.store.uploaded(recording.id))
        assertEquals(setOf(recording.id), f.store.uploadedRecordings())
        // The parts are still here — a cache of what Drive has, which is the whole point.
        recording.meta.parts.forEach { assertTrue(f.fs.exists(recording.dir / it.file)) }
    }

    /**
     * The two questions coming apart: the upload landed, so Drive holds the audio and the delete
     * dialog must not claim otherwise — while the job that failed after it still holds the local
     * parts for `retry()`, so the sweep leaves them alone however old they get.
     */
    @Test
    fun `an upload that landed under a job that failed afterwards is still uploaded`() = runBlocking {
        val webhook = ScriptedRunner("webhook") { _, _ ->
            throw StepFailure(retryable = false, reason = "500 from the hook")
        }
        val f = Fixture(listOf(uploader(), webhook))
        val recording = f.seed()
        val jobId = f.enqueue(recording, driveStep("up"), webhookStep("hook"))

        f.service.runDueJobs()

        assertEquals(JobStatus.FAILED, f.store.get(jobId)!!.status)
        assertEquals(
            listOf(StepStatus.SUCCEEDED, StepStatus.FAILED),
            f.store.stepsOf(jobId).map { it.status },
        )
        assertTrue(f.store.uploaded(recording.id), "the parts did reach Drive")
        assertEquals(setOf(recording.id), f.store.uploadedRecordings())

        f.clock.advance(Retention.WINDOW * 10)
        f.service.runDueJobs()

        recording.meta.parts.forEach {
            assertTrue(f.fs.exists(recording.dir / it.file), "a FAILED job's parts are retry()'s")
        }
    }

    @Test
    fun `a recording whose upload itself failed is not uploaded`() = runBlocking {
        val failing = ScriptedRunner("drive.upload") { _, _ ->
            throw StepFailure(retryable = false, reason = "MISSING_SECRET")
        }
        val f = Fixture(listOf(failing))
        val recording = f.seed()
        val jobId = f.enqueue(recording, driveStep("up"))

        f.service.runDueJobs()

        assertEquals(JobStatus.FAILED, f.store.get(jobId)!!.status)
        assertFalse(f.store.uploaded(recording.id))
        assertEquals(emptySet<String>(), f.store.uploadedRecordings())
    }

    /** A DONE job that never had a `drive.upload` step proves nothing about Drive. */
    @Test
    fun `a recording nothing ever uploaded is not uploaded`() = runBlocking {
        val f = Fixture(listOf(ScriptedRunner("webhook") { _, _ -> output("status" to "200") }))
        val withoutJob = f.seed()
        val withWebhookOnly = f.seed(testMeta(recordingId = "01J9ZZZZZZ0123456789ABCDEF"))
        f.enqueue(withWebhookOnly, webhookStep("hook"))

        f.service.runDueJobs()

        assertFalse(f.store.uploaded(withoutJob.id), "a recording with no job at all")
        assertFalse(f.store.uploaded(withWebhookOnly.id), "a DONE job with nothing that uploads")
        assertEquals(emptySet<String>(), f.store.uploadedRecordings())
    }

    /**
     * The step succeeded and the job is DONE, but its output names one of the three tracks the
     * recorder made: the other parts never went anywhere, and here is still the only
     * place they are. A succeeded status is not the claim — what the output says it sent is.
     */
    @Test
    fun `an upload that skipped a track the recording has is not uploaded`() = runBlocking {
        val mixOnly = ScriptedRunner("drive.upload") { ctx, _ -> uploadOutput(ctx, tracks = listOf(Track.MIX)) }
        val f = Fixture(listOf(mixOnly))
        val recording = f.seed(tracks = threeTracks)
        val jobId = f.enqueue(recording, driveStep("up"))

        f.service.runDueJobs()

        assertEquals(JobStatus.DONE, f.store.get(jobId)!!.status)
        assertFalse(f.store.uploaded(recording.id), "the mic and system parts are still only here")
        assertEquals(emptySet<String>(), f.store.uploadedRecordings())

        f.clock.advance(Retention.WINDOW * 10)
        f.service.runDueJobs()

        recording.meta.parts.forEach {
            assertTrue(f.fs.exists(recording.dir / it.file), "${it.file} was swept without ever going up")
        }
        assertEquals(
            listOf("upload_not_succeeded", "upload_not_succeeded"),
            f.logger.fieldsOf("rec.retained").map { it["reason"] },
        )
    }

    /** The same recording under a workflow that names every track: uploaded, and swept in time. */
    @Test
    fun `an upload that sent every track is uploaded, and its parts go a week on`() = runBlocking {
        val f = Fixture(listOf(uploader()))
        val recording = f.seed(tracks = threeTracks)
        f.enqueue(recording, driveStep("up"))

        f.service.runDueJobs()

        assertTrue(f.store.uploaded(recording.id))
        assertEquals(setOf(recording.id), f.store.uploadedRecordings())

        f.clock.advance(Retention.WINDOW)
        f.service.runDueJobs()

        recording.meta.parts.forEach { assertFalse(f.fs.exists(recording.dir / it.file)) }
    }

    /**
     * The other half of the same hole: the track is named, but one part number is missing from the
     * output — a segment that never made it up under a step that reported success anyway.
     */
    @Test
    fun `an output that names only some of the parts is not uploaded`() = runBlocking {
        val partial = ScriptedRunner("drive.upload") { ctx, _ -> uploadOutput(ctx, skip = listOf(2)) }
        val f = Fixture(listOf(partial))
        val recording = f.seed()
        f.enqueue(recording, driveStep("up"))

        f.service.runDueJobs()

        assertFalse(f.store.uploaded(recording.id), "part 2 never went up")
        assertEquals(emptySet<String>(), f.store.uploadedRecordings())
    }

    @Test
    fun `the bulk answer is the per-recording one for every recording there is`() = runBlocking {
        val f = Fixture(listOf(uploader()))
        val done = f.seed()
        val untouched = f.seed(testMeta(recordingId = "01J9ZZZZZZ0123456789ABCDEF"))
        val pending = f.seed(testMeta(recordingId = "01J9YYYYYY0123456789ABCDEF"))
        f.enqueue(done, driveStep("up"))
        f.service.runDueJobs()
        f.enqueue(pending, driveStep("up"))

        val bulk = f.store.uploadedRecordings()

        assertEquals(setOf(done.id), bulk)
        listOf(done, untouched, pending).forEach {
            assertEquals(it.id in bulk, f.store.uploaded(it.id), "the two answers disagree for ${it.id}")
        }
    }
}
