@file:OptIn(ExperimentalTime::class)

package recly.core.job

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import recly.core.message.CoreMessage
import recly.core.model.Workflow
import recly.core.model.isoUtc
import recly.core.model.recJson
import recly.core.recording.DeleteResult
import recly.core.recording.RecordingRecord
import recly.core.testing.driveStep
import recly.core.testing.testWorkflow

/** A step type only a newer app knows — the whole reason its snapshot will not decode here. */
private const val FUTURE_TYPE = "translate"

private const val POISONED_JOB = "01J9POISONED00000000000000"
private const val POISONED_STEP_RUN = "01J9POISONEDSTEPRUN0000000"
private const val POISONED_WORKFLOW = "01J9POISONEDWORKFLOW000000"
private const val STEP_ID = "up"

/** The workflow the newer app queued, as this build would write it if it knew the type. */
private val readable: Workflow =
    testWorkflow(id = POISONED_WORKFLOW, name = "미래", steps = listOf(driveStep(STEP_ID)))

private val readableJson: String = recJson.encodeToString(readable)

/** The same snapshot with the one thing this build cannot read: an unknown step `type`. */
private val poisonedJson: String =
    readableJson.replace("\"type\":\"drive.upload\"", "\"type\":\"$FUTURE_TYPE\"")

/**
 * docs/10 "잡 스냅샷의 미지 스텝": a job queued on a newer app names a step type this build's
 * serializer has never heard of. It has to stay *one* job — the list, the queue and "녹음 삭제" all
 * go on working — and its `workflow_json` has to survive untouched, because the same row is what an
 * updated build will run.
 */
class JobSnapshotTest {

    @Test
    fun `a snapshot this build cannot decode fails its own job and no other`() = runBlocking {
        val f = Fixture(listOf(ScriptedRunner("drive.upload") { ctx, _ -> uploadOutput(ctx) }))
        val recording = f.seed()
        val healthy = f.enqueue(recording, driveStep(STEP_ID))
        f.poison(recording)

        // The flow the app starts with: it emits, rather than taking the whole list down with it.
        val jobs = f.service.observe().first()

        assertEquals(setOf(healthy, POISONED_JOB), jobs.map { it.id }.toSet())
        val poisoned = jobs.first { it.id == POISONED_JOB }
        assertNull(poisoned.workflow, "nothing here can read the snapshot")
        assertEquals(JobStatus.FAILED, poisoned.status)
        assertEquals(CoreMessage.UNSUPPORTED_STEP.code(FUTURE_TYPE), poisoned.snapshotError)
        val other = jobs.first { it.id == healthy }
        assertNotNull(other.workflow)
        assertNull(other.snapshotError)
        assertEquals(JobStatus.PENDING, other.status)
        assertEquals(jobs, f.store.list(), "the one-shot read says the same as the flow")
    }

    /**
     * The same isolation for a step type this build *used* to know: a job queued before `summarize`
     * was removed decodes no better than one from a newer app, and must fail on its own.
     */
    @Test
    fun `a snapshot holding a removed summarize step fails its own job and no other`() = runBlocking {
        val f = Fixture(listOf(ScriptedRunner("drive.upload") { ctx, _ -> uploadOutput(ctx) }))
        val recording = f.seed()
        val healthy = f.enqueue(recording, driveStep(STEP_ID))
        f.poison(recording, snapshot = readableJson.replace("\"type\":\"drive.upload\"", "\"type\":\"summarize\""))

        val jobs = f.service.observe().first()

        val poisoned = jobs.first { it.id == POISONED_JOB }
        assertNull(poisoned.workflow, "nothing here can read the snapshot")
        assertEquals(JobStatus.FAILED, poisoned.status)
        assertEquals(CoreMessage.UNSUPPORTED_STEP.code("summarize"), poisoned.snapshotError)
        assertEquals(listOf(healthy), f.service.runDueJobs().jobIds, "the rest of the queue goes on running")
    }

    @Test
    fun `a run pass skips it, runs the rest, and leaves the snapshot exactly as it was`() = runBlocking {
        val f = Fixture(listOf(ScriptedRunner("drive.upload") { ctx, _ -> uploadOutput(ctx) }))
        val recording = f.seed()
        val healthy = f.enqueue(recording, driveStep(STEP_ID))
        f.poison(recording)

        val summary = f.service.runDueJobs()

        assertEquals(listOf(healthy), summary.jobIds, "the poisoned job is never due")
        assertEquals(JobStatus.DONE, assertNotNull(f.store.get(healthy)).status)
        assertEquals(poisonedJson, f.rawSnapshot(), "the newer app's bytes are the only copy there is")
        assertEquals(JobStatus.PENDING.name, f.rawStatus(), "and its row was not written either")
    }

    /** docs/03 "녹음 삭제": the dialog must not be held up by a job nothing here can read. */
    @Test
    fun `the recording of a poisoned job can still be deleted`() = runBlocking {
        val f = Fixture(listOf(ScriptedRunner("drive.upload") { ctx, _ -> uploadOutput(ctx) }))
        val recording = f.seed()
        f.enqueue(recording, driveStep(STEP_ID))
        f.poison(recording)

        val result = f.recordings.delete(recording.id, deleteDrive = false)

        assertEquals(DeleteResult.Deleted(driveDeleted = false), result)
        assertNull(f.recordings.get(recording.id))
        assertEquals(emptyList(), f.store.list())
        assertEquals(emptyList(), f.store.stepsOf(POISONED_JOB))
    }

    @Test
    fun `retry arms the row without rewriting it, and the updated build runs what the newer app queued`() =
        runBlocking {
            val runner = ScriptedRunner("drive.upload") { ctx, _ -> uploadOutput(ctx) }
            val f = Fixture(listOf(runner))
            val recording = f.seed()
            f.poison(recording)

            assertTrue(f.service.retry(POISONED_JOB), "a FAILED job is retryable")

            assertEquals(poisonedJson, f.rawSnapshot(), "retry must not rewrite the snapshot")
            assertEquals(JobStatus.PENDING.name, f.rawStatus(), "the row is armed for the build that can read it")
            assertEquals(listOf(StepStatus.PENDING), f.store.stepsOf(POISONED_JOB).map { it.status })
            assertEquals(emptyList(), f.service.runDueJobs().jobIds, "this build still cannot run it")
            assertEquals(0, runner.calls)

            // The app update, modelled the only way a test can: in life the bytes stay and the
            // serializer learns the type, and here it is the other way round. Everything else about
            // the job — its id, its row, its `step_run` — is what it was before the update.
            f.updateSnapshot(readableJson)

            assertEquals(listOf(POISONED_JOB), f.service.runDueJobs().jobIds)
            assertEquals(JobStatus.DONE, assertNotNull(f.store.get(POISONED_JOB)).status)
            assertEquals(listOf(StepStatus.SUCCEEDED), f.store.stepsOf(POISONED_JOB).map { it.status })
        }

    /**
     * docs/03 "로컬 저장": the parts are the only copy of the audio, and a snapshot nothing here can
     * read is a job this build cannot say is finished — whatever its row claims. A sibling that
     * uploaded everything is evidence about itself and nothing else.
     */
    @Test
    fun `a poisoned sibling holds the parts even when its own row says DONE`() = runBlocking {
        val f = Fixture(listOf(ScriptedRunner("drive.upload") { ctx, _ -> uploadOutput(ctx) }))
        val recording = f.seed()
        val healthy = f.enqueue(recording, driveStep(STEP_ID))
        f.poison(recording, JobStatus.DONE)

        f.service.runDueJobs()

        assertEquals(JobStatus.DONE, assertNotNull(f.store.get(healthy)).status)
        recording.meta.parts.forEach {
            assertTrue(f.fs.exists(recording.dir / it.file), "${it.file} was purged under an unreadable job")
        }
        assertEquals(
            listOf(0L, 0L),
            f.db.recQueries.selectPartsByRecording(recording.id).executeAsList().map { it.deleted },
            "a refused claim must not mark any part deleted",
        )
        assertEquals(listOf("snapshot_unreadable"), f.logger.fieldsOf("rec.retained").map { it["reason"] })
    }

    /** The same shape with a snapshot this build *can* read: the sweep still takes the parts. */
    @Test
    fun `a readable DONE sibling does not hold the parts`() = runBlocking {
        val f = Fixture(listOf(ScriptedRunner("drive.upload") { ctx, _ -> uploadOutput(ctx) }))
        val recording = f.seed()
        val healthy = f.enqueue(recording, driveStep(STEP_ID))
        f.poison(recording, JobStatus.DONE, snapshot = readableJson)

        f.service.runDueJobs()
        f.clock.advance(Retention.WINDOW)
        f.service.runDueJobs()

        assertEquals(JobStatus.DONE, assertNotNull(f.store.get(healthy)).status)
        recording.meta.parts.forEach {
            assertFalse(f.fs.exists(recording.dir / it.file), "${it.file} survived the sweep")
        }
        assertEquals(
            listOf(1L, 1L),
            f.db.recQueries.selectPartsByRecording(recording.id).executeAsList().map { it.deleted },
        )
        assertEquals(listOf("within_window"), f.logger.fieldsOf("rec.retained").map { it["reason"] })
    }

    /** The row a newer app left behind: a valid job, with a snapshot this build cannot decode. */
    private fun Fixture.poison(
        recording: RecordingRecord,
        status: JobStatus = JobStatus.PENDING,
        snapshot: String = poisonedJson,
    ) {
        val now = clock.now().isoUtc()
        db.recQueries.insertJob(
            POISONED_JOB,
            recording.id,
            POISONED_WORKFLOW,
            snapshot,
            status.name,
            now,
            now,
            null,
        )
        db.recQueries.insertStepRun(
            POISONED_STEP_RUN,
            POISONED_JOB,
            STEP_ID,
            0L,
            StepStatus.PENDING.name,
            0L,
            null,
            null,
            null,
            null,
        )
    }

    private fun Fixture.rawSnapshot(): String =
        db.recQueries.selectJobById(POISONED_JOB).executeAsOne().workflow_json

    private fun Fixture.rawStatus(): String =
        db.recQueries.selectJobById(POISONED_JOB).executeAsOne().status

    private fun Fixture.updateSnapshot(json: String) {
        driver.execute(null, "UPDATE job SET workflow_json = ? WHERE id = ?", 2) {
            bindString(0, json)
            bindString(1, POISONED_JOB)
        }.value
    }
}
