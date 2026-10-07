@file:OptIn(ExperimentalTime::class)

package recly.core.job

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import recly.core.recording.DeleteResult
import recly.core.testing.CoreFixture
import recly.core.testing.driveStep
import recly.core.testing.testMeta

/**
 * docs/03 "Deleting in the app": a recording whose job is running — an upload, here or one that has
 * stopped answering — is deleted like any other. The run is stopped first, it writes nothing
 * afterwards, and the queue goes on with every other recording's job.
 */
class DeleteDuringRunTest {

    /** The whole core, the real `drive.upload` and transport, over a Drive whose upload never answers. */
    @Test
    fun `deleting a recording whose upload hangs cancels the upload, and the same pass runs the next job`() =
        runBlocking {
            val f = CoreFixture()
            val hung = f.record(id = FIRST, startedAt = "2026-08-26T01:00:00.000Z")
            f.record(id = SECOND, startedAt = "2026-08-26T02:00:00.000Z")
            assertIs<EnqueueResult.Enqueued>(f.core.enqueue(FIRST))
            f.clock.advance(1.seconds)
            assertIs<EnqueueResult.Enqueued>(f.core.enqueue(SECOND))
            val held = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val first = AtomicBoolean(true)
            f.drive.hold += { request ->
                // The oldest job runs first, so the first file upload is the first recording's part.
                if (request.uploadType != null && first.getAndSet(false)) {
                    held.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
            }

            val pass = async(Dispatchers.Default) { f.core.runDueJobs(f.clock.now()) }
            withTimeout(5.seconds) { held.await() }
            val asked = TimeSource.Monotonic.markNow()
            val result = f.core.recordings.delete(FIRST, deleteDrive = false)
            val took = asked.elapsedNow()

            assertEquals(DeleteResult.Deleted(driveDeleted = false), result)
            assertTrue(cancelled.isCompleted, "the upload request itself was cancelled")
            assertTrue(took < 2.seconds, "stopped by cancelling, not by waiting out the grace: $took")
            withTimeout(10.seconds) { pass.await() }
            assertNull(f.core.recordings.get(FIRST))
            assertFalse(f.fs.exists(f.dirOf(hung)))
            val left = f.core.jobs.list()
            assertEquals(listOf(SECOND), left.map { it.recordingId }, "the first recording's job went with it")
            assertEquals(
                StepStatus.SUCCEEDED,
                f.core.jobs.steps(left.single().id).first { it.stepId == "upload" }.status,
                "the next recording uploaded in the same pass",
            )
            assertEquals(listOf(true), f.logger.fieldsOf("job.stopped").map { it["settled"] })
        }

    /**
     * The iPhone's background upload session answers a chunk only once it is sent (docs/13 I4), and
     * cancelling the coroutine does not reach it. The deletion does not wait for it, the pass does
     * not either, and when the step finally wakes up its write is refused.
     */
    @Test
    fun `a run cancelling cannot reach is let go of, holds up no other job, and writes nothing when it wakes`() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val woke = CompletableDeferred<Throwable?>()
            val upload = ScriptedRunner("drive.upload") { ctx, _ ->
                if (ctx.recording.id == FIRST) {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    try {
                        ctx.saveState(buildJsonObject { put("offset", 1) })
                        woke.complete(null)
                    } catch (e: CancellationException) {
                        woke.complete(e)
                        throw e
                    }
                }
                uploadOutput(ctx)
            }
            val f = Fixture(listOf(upload))
            val stuck = f.seed(testMeta(recordingId = FIRST))
            val other = f.seed(testMeta(recordingId = SECOND, startedAt = "2026-08-26T02:00:00.000Z"))
            val stuckJob = f.enqueue(stuck, driveStep("up"))
            f.clock.advance(1.seconds)
            val otherJob = f.enqueue(other, driveStep("up"))

            val pass = async(Dispatchers.Default) { f.service.runDueJobs(f.clock.now()) }
            withTimeout(5.seconds) { entered.await() }

            assertEquals(DeleteResult.Deleted(driveDeleted = false), f.recordings.delete(FIRST, deleteDrive = false))
            assertEquals(listOf(stuckJob, otherJob), withTimeout(5.seconds) { pass.await() }.jobIds)
            assertEquals(JobStatus.DONE, f.store.get(otherJob)?.status, "the other recording was not held up")
            assertNull(f.store.get(stuckJob))
            assertNull(f.recordings.get(FIRST))
            assertFalse(f.fs.exists(stuck.dir))
            assertEquals(
                listOf(mapOf("jobId" to stuckJob, "recordingId" to FIRST, "settled" to false)),
                f.logger.fieldsOf("job.stopped"),
            )

            release.complete(Unit)
            assertIs<CancellationException>(withTimeout(5.seconds) { woke.await() }, "the late write is refused")
            assertEquals(emptyList(), f.store.stepsOf(stuckJob))
            assertNull(f.recordings.get(FIRST))
            assertFalse(f.fs.exists(stuck.dir))
            assertEquals(JobStatus.DONE, f.store.get(otherJob)?.status)
        }

    /** A process killed mid-upload leaves its job `RUNNING` with nothing running it; that is no reason to keep it. */
    @Test
    fun `a job left RUNNING with no run behind it goes with its recording`() = runBlocking {
        val f = Fixture(listOf(ScriptedRunner("drive.upload") { ctx, _ -> uploadOutput(ctx) }))
        val recording = f.seed()
        val jobId = f.enqueue(recording, driveStep("up"))
        assertTrue(f.store.claimRunning(jobId, f.clock.now()))

        assertEquals(DeleteResult.Deleted(driveDeleted = false), f.recordings.delete(recording.id, deleteDrive = false))

        assertNull(f.store.get(jobId))
        assertNull(f.recordings.get(recording.id))
        assertFalse(f.fs.exists(recording.dir))
        assertEquals(emptyList(), f.logger.fieldsOf("job.stopped"), "there was no run to stop")
    }

    private companion object {
        const val FIRST = "01J9ABCDEF0123456789ABCDEF"
        const val SECOND = "01J9ZZZZZZ0123456789ABCDEF"
    }
}
