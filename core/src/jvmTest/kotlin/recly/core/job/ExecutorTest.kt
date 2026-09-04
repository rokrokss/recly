@file:OptIn(ExperimentalTime::class)

package recly.core.job

import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import recly.core.db.RecDatabase
import recly.core.model.OnError
import recly.core.model.RecordingMeta
import recly.core.model.Retry
import recly.core.model.Source
import recly.core.model.Track
import recly.core.model.isoUtc
import recly.core.model.wire
import recly.core.message.CoreMessage
import recly.core.platform.AuthRequiredException
import recly.core.recording.MetaWriter
import recly.core.recording.RecordingRecord
import recly.core.recording.RecordingRepository
import recly.core.testing.FakeClock
import recly.core.testing.FakeLogger
import recly.core.testing.driveStep
import recly.core.testing.transcribeStep
import recly.core.testing.inMemoryDriver
import recly.core.testing.seedFiles
import recly.core.testing.testDeps
import recly.core.testing.testDocument
import recly.core.testing.testMeta
import recly.core.testing.testPart
import recly.core.testing.testWorkflow
import recly.core.testing.webhookStep

internal fun output(vararg pairs: Pair<String, String>): StepOutcome =
    StepOutcome.Done(StepOutput(buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }))

/**
 * What a real `drive.upload` reports (`DriveUploadRunner`): the folder, plus a `files[]` entry per
 * `{part, track}` it actually sent. [tracks] narrows that to some of the recording's tracks and
 * [skip] leaves a part number out, so a test can script the upload that succeeds without ever
 * sending part of the recording — the whole difference between a step that ran and audio Drive has.
 */
internal fun uploadOutput(
    ctx: StepContext,
    tracks: List<Track>? = null,
    skip: List<Int> = emptyList(),
): StepOutcome =
    StepOutcome.Done(
        StepOutput(
            buildJsonObject {
                put("folderId", "F1")
                putJsonArray("files") {
                    ctx.recording.meta.parts
                        .filter { (tracks == null || it.track in tracks) && it.part !in skip }
                        .forEach { part ->
                            add(
                                buildJsonObject {
                                    put("part", part.part)
                                    put("track", part.track.wire)
                                    put("fileId", "F1-${part.part}-${part.track.wire}")
                                },
                            )
                        }
                }
            },
        ),
    )

internal class ScriptedRunner(
    override val type: String,
    private val script: suspend (StepContext, Int) -> StepOutcome,
) : StepRunner {
    var calls = 0
        private set
    val states = mutableListOf<JsonObject?>()
    val priors = mutableListOf<Map<String, StepOutput>>()

    override suspend fun run(ctx: StepContext): StepOutcome {
        calls++
        states += ctx.state
        priors += ctx.prior
        return script(ctx, calls)
    }
}

internal class Fixture(
    runners: List<StepRunner>,
    random: Random = Random(42),
    /** The disk is dated by the same clock as the queue: the retention sweep reads part mtimes. */
    val clock: FakeClock = FakeClock(),
    val fs: FakeFileSystem = FakeFileSystem(clock),
) {
    val logger = FakeLogger()
    val deps = testDeps(clock, fs, logger)

    /** The driver is kept as well as the database: [JobSnapshotTest] writes a `job` row no query
     * in `Rec.sq` covers. */
    val driver = inMemoryDriver()
    val db = RecDatabase(driver)
    val recordings = RecordingRepository(db, deps)
    val store = JobStore(db, deps)
    val service = JobService(deps, store, recordings, executorWith(runners, random))

    /** A fresh executor over the same database — what a process restart looks like. */
    fun executorWith(runners: List<StepRunner>, random: Random = Random(42)): Executor =
        Executor(deps, store, recordings, runners.associateBy { it.type }, random)

    /** Two parts per track: [tracks] is what the recorder made, not what a workflow uploads. */
    suspend fun seed(
        meta: RecordingMeta = testMeta(),
        durationSec: Double = 2700.0,
        tracks: List<Track> = listOf(Track.MONO),
    ): RecordingRecord {
        val withParts = meta.copy(
            tracks = tracks,
            parts = (1..2).flatMap { number -> tracks.map { testPart(meta, number, it) } },
        )
        val dir = "/data/recordings/${MetaWriter.baseName(meta)}".toPath()
        recordings.create(withParts, dir)
        seedFiles(fs, dir, withParts)
        return recordings.finalize(meta.recordingId, clock.now(), durationSec)
    }

    suspend fun enqueue(recording: RecordingRecord, vararg steps: recly.core.model.Step): String =
        (enqueue(recording, testDocument(testWorkflow(steps = steps.toList()))) as EnqueueResult.Enqueued).jobId

    /**
     * These tests run one workflow, and after ADR-016 something has to say so: it is this device's
     * default rather than a pick made at stop time.
     */
    suspend fun enqueue(recording: RecordingRecord, doc: recly.core.model.WorkflowsDocument): EnqueueResult =
        service.enqueue(recording.id, doc, deviceDefaultWorkflowId = doc.workflows.single().id)
}

class ExecutorTest {
    private val twoStepWorkflow = arrayOf(driveStep("up"), webhookStep("hook"))

    @Test
    fun runsEveryStepAndLeavesThePartsToTheRetentionSweep() = runBlocking {
        val upload = ScriptedRunner("drive.upload") { ctx, _ -> uploadOutput(ctx) }
        val webhook = ScriptedRunner("webhook") { _, _ -> output("status" to "200") }
        val f = Fixture(listOf(upload, webhook))
        val recording = f.seed()
        val jobId = f.enqueue(recording, *twoStepWorkflow)

        val summary = f.service.runDueJobs()

        assertEquals(listOf(jobId), summary.jobIds)
        assertFalse(summary.alreadyRunning)
        assertEquals(JobStatus.DONE, f.store.get(jobId)!!.status)
        val steps = f.store.stepsOf(jobId)
        assertEquals(listOf(StepStatus.SUCCEEDED, StepStatus.SUCCEEDED), steps.map { it.status })
        assertEquals("F1", steps[0].output!!["folderId"]!!.jsonPrimitive.content)
        assertEquals("200", steps[1].output!!["status"]!!.jsonPrimitive.content)
        // The webhook step sees the upload's output (docs/02: files[].drive comes from it).
        assertEquals("F1", webhook.priors.single()["up"]!!.json["folderId"]!!.jsonPrimitive.content)
        // A finished job deletes nothing any more: the audio is a cache with a window on it, and
        // the sweep at the end of the pass says so rather than taking it (ADR-017).
        recording.meta.parts.forEach {
            assertTrue(f.fs.exists(recording.dir / it.file), "${it.file} was purged at completion")
        }
        assertTrue(f.fs.exists(recording.dir / MetaWriter.metaFileName(MetaWriter.baseName(recording.meta))))
        assertEquals(
            listOf(
                "rec.finalize", "job.step.start", "job.step.ok", "job.step.start", "job.step.ok",
                "job.done", "rec.retained",
            ),
            f.logger.events,
        )
        assertEquals(
            listOf(mapOf("recordingId" to recording.id, "reason" to "within_window")),
            f.logger.fieldsOf("rec.retained"),
        )
    }

    @Test
    fun aRetryableFailureParksTheJobUntilTheBackoffElapses() = runBlocking {
        val upload = ScriptedRunner("drive.upload") { ctx, calls ->
            if (calls == 1) throw StepFailure(retryable = true, reason = "503 from Drive") else uploadOutput(ctx)
        }
        val f = Fixture(listOf(upload))
        val recording = f.seed()
        val jobId = f.enqueue(recording, driveStep("up"))
        val expected = Backoff.delaySec(1, Retry(), Random(42))

        f.service.runDueJobs()

        val job = f.store.get(jobId)!!
        assertEquals(JobStatus.WAITING, job.status)
        assertEquals(f.clock.now() + expected.seconds, job.nextRunAt)
        val step = f.store.stepsOf(jobId).single()
        assertEquals(StepStatus.PENDING, step.status)
        assertEquals(1, step.attempts)
        assertEquals(f.clock.now() + expected.seconds, step.nextAttemptAt)
        assertEquals("503 from Drive", step.lastError)

        // Still parked: nothing due before next_run_at.
        assertEquals(emptyList(), f.service.runDueJobs().jobIds)
        assertEquals(1, upload.calls)

        f.clock.advance(expected.seconds)
        assertEquals(listOf(jobId), f.service.runDueJobs().jobIds)
        assertEquals(2, upload.calls)
        assertEquals(JobStatus.DONE, f.store.get(jobId)!!.status)
        assertEquals(StepStatus.SUCCEEDED, f.store.stepsOf(jobId).single().status)
    }

    @Test
    fun retryOnAWaitingJobRunsItNowWithoutRefillingItsRetryBudget() = runBlocking {
        // "upload now" on a job that is only waiting out a backoff. The wait goes; the attempts it
        // has already spent stay, so the button cannot be used to keep a doomed job alive forever.
        val upload = ScriptedRunner("drive.upload") { ctx, calls ->
            if (calls == 1) throw StepFailure(retryable = true, reason = "503 from Drive") else uploadOutput(ctx)
        }
        val f = Fixture(listOf(upload))
        val recording = f.seed()
        val jobId = f.enqueue(recording, driveStep("up"))

        f.service.runDueJobs()
        assertEquals(JobStatus.WAITING, f.store.get(jobId)!!.status)
        assertTrue(f.store.get(jobId)!!.nextRunAt!! > f.clock.now(), "the job is parked in the future")

        assertTrue(f.service.retry(jobId))

        val parked = f.store.get(jobId)!!
        assertEquals(JobStatus.PENDING, parked.status)
        assertEquals(null, parked.nextRunAt)
        val step = f.store.stepsOf(jobId).single()
        assertEquals(1, step.attempts, "the spent attempt is not forgiven")
        assertEquals(null, step.nextAttemptAt, "the backoff is what the user dropped")
        assertEquals("503 from Drive", step.lastError, "why it was parked is still worth showing")

        // The clock has not moved: without the retry this run would have found nothing due.
        assertEquals(listOf(jobId), f.service.runDueJobs().jobIds)
        assertEquals(2, upload.calls)
        assertEquals(JobStatus.DONE, f.store.get(jobId)!!.status)
    }

    @Test
    fun retryOnAWaitingJobCannotOutrunTheStepsRetryCeiling() = runBlocking {
        // The negative half of the rule above: a job whose budget is spent still reaches onError,
        // however many times the user taps.
        val upload = ScriptedRunner("drive.upload") { _, _ -> throw StepFailure(retryable = true, reason = "503") }
        val f = Fixture(listOf(upload))
        val recording = f.seed()
        val jobId = f.enqueue(recording, driveStep("up", retry = Retry(maxAttempts = 2)))

        f.service.runDueJobs()
        repeat(3) {
            if (f.store.get(jobId)!!.status == JobStatus.WAITING) {
                assertTrue(f.service.retry(jobId))
                f.service.runDueJobs()
            }
        }

        assertEquals(JobStatus.FAILED, f.store.get(jobId)!!.status)
        assertEquals(2, upload.calls, "the ceiling held: two attempts, not one per tap")
    }

    @Test
    fun retriesStopAtMaxAttempts() = runBlocking {
        val upload = ScriptedRunner("drive.upload") { _, _ -> throw StepFailure(retryable = true, reason = "503") }
        val f = Fixture(listOf(upload))
        val recording = f.seed()
        val jobId = f.enqueue(recording, driveStep("up", retry = Retry(maxAttempts = 3)))

        repeat(5) {
            f.service.runDueJobs()
            f.clock.advance(3601.seconds)
        }

        assertEquals(3, upload.calls)
        assertEquals(JobStatus.FAILED, f.store.get(jobId)!!.status)
        val step = f.store.stepsOf(jobId).single()
        assertEquals(StepStatus.FAILED, step.status)
        assertEquals(3, step.attempts)
    }

    @Test
    fun abortLeavesTheRemainingStepsUntouched() = runBlocking {
        val upload = ScriptedRunner("drive.upload") { _, _ ->
            throw StepFailure(retryable = false, reason = "MISSING_SECRET")
        }
        val webhook = ScriptedRunner("webhook") { _, _ -> output("status" to "200") }
        val f = Fixture(listOf(upload, webhook))
        val recording = f.seed()
        val jobId = f.enqueue(recording, *twoStepWorkflow)

        f.service.runDueJobs()

        assertEquals(JobStatus.FAILED, f.store.get(jobId)!!.status)
        val steps = f.store.stepsOf(jobId)
        assertEquals(listOf(StepStatus.FAILED, StepStatus.PENDING), steps.map { it.status })
        assertEquals("MISSING_SECRET", steps[0].lastError)
        assertEquals(0, webhook.calls)
        assertTrue("job.failed" in f.logger.events)
        // A failed job keeps its parts: the user can still retry or export them (docs/03).
        recording.meta.parts.forEach { assertTrue(f.fs.exists(recording.dir / it.file)) }
    }

    @Test
    fun continueRunsTheRestOfTheJob() = runBlocking {
        val upload = ScriptedRunner("drive.upload") { _, _ ->
            throw StepFailure(retryable = false, reason = "MISSING_SECRET")
        }
        val webhook = ScriptedRunner("webhook") { _, _ -> output("status" to "200") }
        val f = Fixture(listOf(upload, webhook))
        val recording = f.seed()
        val jobId = f.enqueue(recording, driveStep("up", onError = OnError.CONTINUE), webhookStep("hook"))

        f.service.runDueJobs()

        assertEquals(JobStatus.DONE, f.store.get(jobId)!!.status)
        assertEquals(
            listOf(StepStatus.FAILED, StepStatus.SUCCEEDED),
            f.store.stepsOf(jobId).map { it.status },
        )
        assertEquals(1, webhook.calls)
        assertTrue(webhook.priors.single().isEmpty(), "a failed step contributes no output")
        // The upload never landed, so the parts stay even though the job is DONE.
        recording.meta.parts.forEach { assertTrue(f.fs.exists(recording.dir / it.file)) }
        assertEquals(
            listOf(mapOf("recordingId" to recording.id, "reason" to "upload_not_succeeded")),
            f.logger.fieldsOf("rec.retained"),
        )
    }

    @Test
    fun authFailureParksTheJobWithoutSpendingAnAttempt() = runBlocking {
        val upload = ScriptedRunner("drive.upload") { ctx, calls ->
            if (calls == 1) throw AuthRequiredException("token revoked") else uploadOutput(ctx)
        }
        val f = Fixture(listOf(upload))
        val recording = f.seed()
        val jobId = f.enqueue(recording, driveStep("up"))

        f.service.runDueJobs()

        assertEquals(JobStatus.NEEDS_AUTH, f.store.get(jobId)!!.status)
        val step = f.store.stepsOf(jobId).single()
        assertEquals(StepStatus.NEEDS_AUTH, step.status)
        assertEquals(0, step.attempts)
        assertEquals("token revoked", step.lastError)
        assertEquals(emptyList(), f.service.runDueJobs().jobIds, "NEEDS_AUTH is not due until sign-in")

        assertTrue(f.service.retry(jobId))
        assertEquals(JobStatus.PENDING, f.store.get(jobId)!!.status)
        assertEquals(listOf(jobId), f.service.runDueJobs().jobIds)
        assertEquals(JobStatus.DONE, f.store.get(jobId)!!.status)
        assertFalse(f.service.retry(jobId), "a DONE job is not retryable")
    }

    @Test
    fun aStepFailureCanAlsoAskForAuth() = runBlocking {
        val upload = ScriptedRunner("drive.upload") { _, _ ->
            throw StepFailure(retryable = false, reason = "401", needsAuth = true)
        }
        val f = Fixture(listOf(upload))
        val jobId = f.enqueue(f.seed(), driveStep("up"))

        f.service.runDueJobs()

        assertEquals(JobStatus.NEEDS_AUTH, f.store.get(jobId)!!.status)
        assertEquals(0, f.store.stepsOf(jobId).single().attempts)
    }

    @Test
    fun aKilledStepResumesFromItsSavedStateAfterRestart() = runBlocking {
        val saved = buildJsonObject { put("offset", 1_310_720) }
        val killed = ScriptedRunner("drive.upload") { ctx, _ ->
            ctx.saveState(saved)
            throw CancellationException("process killed")
        }
        val f = Fixture(listOf(killed))
        val recording = f.seed()
        val jobId = f.enqueue(recording, driveStep("up"))

        assertFailsWith<CancellationException> { f.service.runDueJobs() }

        // Killed mid-step: both rows are left RUNNING, and the resume point is on disk.
        assertEquals(JobStatus.RUNNING, f.store.get(jobId)!!.status)
        val interrupted = f.store.stepsOf(jobId).single()
        assertEquals(StepStatus.RUNNING, interrupted.status)
        assertEquals(saved, interrupted.state)
        assertEquals(0, interrupted.attempts)

        val resumed = ScriptedRunner("drive.upload") { ctx, _ -> uploadOutput(ctx) }
        val summary = f.executorWith(listOf(resumed)).runDueJobs(f.clock.now())

        assertEquals(listOf(jobId), summary.jobIds)
        assertEquals(saved, resumed.states.single(), "the runner must see the state it saved")
        assertEquals(JobStatus.DONE, f.store.get(jobId)!!.status)
        // Straight through the executor, which purges nothing at all: only the sweep does.
        recording.meta.parts.forEach { assertTrue(f.fs.exists(recording.dir / it.file)) }
    }

    @Test
    fun aSecondConcurrentRunReturnsImmediately() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val upload = ScriptedRunner("drive.upload") { ctx, _ ->
            entered.complete(Unit)
            release.await()
            uploadOutput(ctx)
        }
        val f = Fixture(listOf(upload))
        val jobId = f.enqueue(f.seed(), driveStep("up"))

        val first = async(Dispatchers.Default) { f.service.runDueJobs(f.clock.now()) }
        entered.await()
        val second = f.service.runDueJobs(f.clock.now())
        release.complete(Unit)

        assertTrue(second.alreadyRunning)
        assertEquals(emptyList(), second.jobIds)
        assertEquals(listOf(jobId), first.await().jobIds)
        assertEquals(1, upload.calls)
        assertEquals(JobStatus.DONE, f.store.get(jobId)!!.status)
    }

    /**
     * docs/03 "연결 해제" runs inside [JobService.quiesced], which is the gate [runDueJobs] holds
     * while it runs: the step in flight is allowed to finish, the rest of the job is not started —
     * nothing external is called over an account that is being emptied — and only then does the
     * disconnect get the gate and the secrets, tokens and queue rows it is about to clear.
     */
    @Test
    fun aDisconnectWaitsForTheStepInFlightAndTheRestOfTheJobIsNotRun() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val upload = ScriptedRunner("drive.upload") { ctx, _ ->
            entered.complete(Unit)
            release.await()
            order += "step"
            uploadOutput(ctx)
        }
        val webhook = ScriptedRunner("webhook") { _, _ -> output("status" to "200") }
        val f = Fixture(listOf(upload, webhook))
        val jobId = f.enqueue(f.seed(), *twoStepWorkflow)

        val run = async(Dispatchers.Default) { f.service.runDueJobs(f.clock.now()) }
        entered.await()
        val disconnect = async(Dispatchers.Default) { f.service.quiesced { order += "disconnect" } }
        assertNull(
            withTimeoutOrNull(200) { disconnect.await() },
            "the gate is not handed over while a step is in flight",
        )
        release.complete(Unit)
        run.await()
        disconnect.await()

        assertEquals(listOf("step", "disconnect"), order)
        assertEquals(1, upload.calls)
        assertEquals(0, webhook.calls, "the rest of the job is not run over an account being emptied")
        // Stopped the way a kill stops it: the row stays RUNNING for the next recoverRunning.
        assertEquals(JobStatus.RUNNING, f.store.get(jobId)!!.status)
        assertEquals(StepStatus.PENDING, f.store.stepsOf(jobId)[1].status)
    }

    @Test
    fun enqueueIsIdempotentPerRecordingAndWorkflow() = runBlocking {
        val f = Fixture(listOf(ScriptedRunner("drive.upload") { ctx, _ -> uploadOutput(ctx) }))
        val recording = f.seed()
        val doc = testDocument(testWorkflow(steps = listOf(driveStep("up"))))

        val first = f.enqueue(recording, doc) as EnqueueResult.Enqueued
        val second = f.enqueue(recording, doc) as EnqueueResult.Enqueued

        assertEquals(first.jobId, second.jobId)
        assertEquals(1, f.service.observe().first().size)
    }

    @Test
    fun reEnqueuingADoneJobReportsAlreadyDoneAndCreatesNothing() = runBlocking {
        val f = Fixture(listOf(ScriptedRunner("drive.upload") { ctx, _ -> uploadOutput(ctx) }))
        val recording = f.seed()
        val doc = testDocument(testWorkflow(steps = listOf(driveStep("up"))))
        val jobId = (f.enqueue(recording, doc) as EnqueueResult.Enqueued).jobId
        f.service.runDueJobs()
        assertEquals(JobStatus.DONE, f.store.get(jobId)!!.status)

        assertEquals(EnqueueResult.AlreadyDone(jobId), f.enqueue(recording, doc))
        assertEquals(JobStatus.DONE, f.store.get(jobId)!!.status)
        assertEquals(listOf(jobId), f.service.observe().first().map { it.id })
    }

    @Test
    fun reEnqueuingAFailedJobResumesItFromTheFailedStep() = runBlocking {
        val upload = ScriptedRunner("drive.upload") { ctx, calls ->
            if (calls == 1) throw StepFailure(retryable = false, reason = "bad request") else uploadOutput(ctx)
        }
        val f = Fixture(listOf(upload))
        val recording = f.seed()
        val doc = testDocument(testWorkflow(steps = listOf(driveStep("up"))))
        val jobId = (f.enqueue(recording, doc) as EnqueueResult.Enqueued).jobId
        f.service.runDueJobs()
        assertEquals(JobStatus.FAILED, f.store.get(jobId)!!.status)

        val again = f.enqueue(recording, doc) as EnqueueResult.Enqueued

        assertEquals(jobId, again.jobId)
        assertEquals(JobStatus.PENDING, f.store.get(jobId)!!.status)
        assertEquals(0, f.store.stepsOf(jobId).single().attempts)
        f.service.runDueJobs()
        assertEquals(JobStatus.DONE, f.store.get(jobId)!!.status)
    }

    @Test
    fun aShortRecordingIsSkippedUntilItIsRunByHand() = runBlocking {
        val upload = ScriptedRunner("drive.upload") { ctx, _ -> uploadOutput(ctx) }
        val f = Fixture(listOf(upload))
        val recording = f.seed(durationSec = 10.0)
        val doc = testDocument(testWorkflow(minDurationSec = 30, steps = listOf(driveStep("up"))))

        val result = f.enqueue(recording, doc)

        val jobId = (result as EnqueueResult.SkippedShort).jobId
        assertEquals(JobStatus.SKIPPED_SHORT, f.store.get(jobId)!!.status)
        assertEquals(emptyList(), f.service.runDueJobs().jobIds)
        assertEquals(0, upload.calls)
        // Re-enqueuing must not silently un-skip it…
        assertEquals(jobId, (f.enqueue(recording, doc) as EnqueueResult.SkippedShort).jobId)
        assertEquals(JobStatus.SKIPPED_SHORT, f.store.get(jobId)!!.status)

        // …only an explicit retry does.
        assertTrue(f.service.retry(jobId))
        assertEquals(listOf(jobId), f.service.runDueJobs().jobIds)
        assertEquals(JobStatus.DONE, f.store.get(jobId)!!.status)
    }

    /** ADR-016: no pick and no device default is the only way a recording gets no job. */
    @Test
    fun reportsNoWorkflowWhenNeitherThePickNorTheDeviceDefaultResolves() = runBlocking {
        val f = Fixture(emptyList())
        val recording = f.seed(testMeta(source = Source.DESKTOP))
        val doc = testDocument(testWorkflow())

        assertEquals(EnqueueResult.NoWorkflow, f.service.enqueue(recording.id, doc))
        assertEquals(
            EnqueueResult.NoWorkflow,
            f.service.enqueue(recording.id, doc, deviceDefaultWorkflowId = "01ZZZZZZZZZZZZZZZZZZZZZZZZ"),
        )
        assertEquals(0, f.service.observe().first().size)
    }

    /** …and the device default is what runs when the recording carries no pick of its own. */
    @Test
    fun runsTheDeviceDefaultWhenTheRecordingHasNoPick() = runBlocking {
        val f = Fixture(emptyList())
        val recording = f.seed(testMeta(source = Source.DESKTOP))
        val workflow = testWorkflow()
        val doc = testDocument(workflow)

        val jobId = (f.service.enqueue(recording.id, doc, deviceDefaultWorkflowId = workflow.id)
            as EnqueueResult.Enqueued).jobId

        assertEquals(workflow.id, f.store.get(jobId)!!.workflowId)
    }

    @Test
    fun dueJobsRunOldestFirst() = runBlocking {
        val order = mutableListOf<String>()
        val upload = ScriptedRunner("drive.upload") { ctx, _ ->
            order += ctx.recording.id
            uploadOutput(ctx)
        }
        val f = Fixture(listOf(upload))
        val ids = listOf("01J9AAAAAAAAAAAAAAAAAAAAAA", "01J9BBBBBBBBBBBBBBBBBBBBBB", "01J9CCCCCCCCCCCCCCCCCCCCCC")
        val jobIds = ids.map { id ->
            val recording = f.seed(testMeta(recordingId = id, startedAt = f.clock.now().toString()))
            val jobId = f.enqueue(recording, driveStep("up"))
            f.clock.advance(60.seconds)
            jobId
        }

        val summary = f.service.runDueJobs()

        assertEquals(jobIds, summary.jobIds)
        assertEquals(ids, order)
    }

    @Test
    fun partsSurviveUntilEveryJobOfTheRecordingIsFinished() = runBlocking {
        val clock = FakeClock()
        val fs = FakeFileSystem(clock)
        var partPath: okio.Path? = null
        val presentAtEachRun = mutableListOf<Boolean>()
        val upload = ScriptedRunner("drive.upload") { ctx, _ ->
            presentAtEachRun += fs.exists(partPath!!)
            uploadOutput(ctx)
        }
        val f = Fixture(listOf(upload), clock = clock, fs = fs)
        val recording = f.seed()
        partPath = recording.dir / recording.meta.parts.first().file
        val first = testWorkflow(id = "01J9AAAAAAAAAAAAAAAAAAAAAA", steps = listOf(driveStep("up")))
        val second = testWorkflow(id = "01J9BBBBBBBBBBBBBBBBBBBBBB", steps = listOf(driveStep("up")))
        val doc = testDocument(first, second)
        val firstJob = (f.service.enqueue(recording.id, doc, first.id) as EnqueueResult.Enqueued).jobId
        val secondJob = (f.service.enqueue(recording.id, doc, second.id) as EnqueueResult.Enqueued).jobId

        val summary = f.service.runDueJobs()

        assertEquals(listOf(firstJob, secondJob), summary.jobIds)
        // Neither job started with the parts already deleted.
        assertEquals(listOf(true, true), presentAtEachRun)
        // One sweep at the end of the pass, by which time both jobs are DONE — and the window is
        // what holds the parts now, not the queue.
        assertEquals(
            listOf(mapOf("recordingId" to recording.id, "reason" to "within_window")),
            f.logger.fieldsOf("rec.retained"),
        )

        clock.advance(Retention.WINDOW)
        f.service.runDueJobs()

        recording.meta.parts.forEach {
            assertFalse(fs.exists(recording.dir / it.file), "${it.file} survived the sweep")
        }
    }

    @Test
    fun aFailedSiblingJobKeepsThePartsUntilItIsRetried() = runBlocking {
        val clock = FakeClock()
        val fs = FakeFileSystem(clock)
        // Enqueued first, so it has already FAILED by the time the other job reaches DONE.
        val failing = testWorkflow(id = "01J9AAAAAAAAAAAAAAAAAAAAAA", steps = listOf(driveStep("up")))
        val healthy = testWorkflow(id = "01J9BBBBBBBBBBBBBBBBBBBBBB", steps = listOf(driveStep("up")))
        var failOnce = true
        val upload = ScriptedRunner("drive.upload") { ctx, _ ->
            if (ctx.job.workflowId == failing.id && failOnce) {
                failOnce = false
                throw StepFailure(retryable = false, reason = "bad request")
            }
            uploadOutput(ctx)
        }
        val f = Fixture(listOf(upload), clock = clock, fs = fs)
        val recording = f.seed()
        val doc = testDocument(failing, healthy)
        val failedJob = (f.service.enqueue(recording.id, doc, failing.id) as EnqueueResult.Enqueued).jobId
        val doneJob = (f.service.enqueue(recording.id, doc, healthy.id) as EnqueueResult.Enqueued).jobId

        f.service.runDueJobs()

        assertEquals(JobStatus.FAILED, f.store.get(failedJob)!!.status)
        assertEquals(JobStatus.DONE, f.store.get(doneJob)!!.status)
        // A FAILED sibling holds the parts even though this job uploaded them: retry() has to
        // have something left to work with (docs/03 "로컬 저장").
        recording.meta.parts.forEach {
            assertTrue(fs.exists(recording.dir / it.file), "${it.file} was purged while a sibling was FAILED")
        }
        assertEquals(
            listOf(mapOf("recordingId" to recording.id, "reason" to "other_jobs_pending")),
            f.logger.fieldsOf("rec.retained"),
        )

        assertTrue(f.service.retry(failedJob))
        f.service.runDueJobs()

        assertEquals(JobStatus.DONE, f.store.get(failedJob)!!.status)
        // Both DONE, so only the window is left — counted from the retry, which is the newest
        // thing that happened to these jobs.
        recording.meta.parts.forEach { assertTrue(fs.exists(recording.dir / it.file)) }

        clock.advance(Retention.WINDOW)
        f.service.runDueJobs()

        recording.meta.parts.forEach {
            assertFalse(fs.exists(recording.dir / it.file), "${it.file} survived the sweep")
        }
    }

    @Test
    fun aJobEnqueuedWhileTheLastOneFinishesKeepsItsParts() = runBlocking {
        // The race the claim closes: a job appears between "every job is DONE" and the deletion.
        // The runner enqueues it at the worst possible moment — just before its own job completes.
        val clock = FakeClock()
        val fs = FakeFileSystem(clock)
        val second = testWorkflow(id = "01J9BBBBBBBBBBBBBBBBBBBBBB", steps = listOf(driveStep("up")))
        var service: JobService? = null
        var recordingId: String? = null
        var raced = false
        val upload = ScriptedRunner("drive.upload") { ctx, _ ->
            if (!raced) {
                raced = true
                service!!.enqueue(recordingId!!, testDocument(second), second.id)
            }
            uploadOutput(ctx)
        }
        val f = Fixture(listOf(upload), clock = clock, fs = fs)
        service = f.service
        val recording = f.seed()
        recordingId = recording.id
        val firstJob = f.enqueue(recording, driveStep("up"))

        assertEquals(listOf(firstJob), f.service.runDueJobs().jobIds)

        // The first job is DONE, but the job born mid-run still needs the audio.
        assertEquals(JobStatus.DONE, f.store.get(firstJob)!!.status)
        recording.meta.parts.forEach {
            assertTrue(fs.exists(recording.dir / it.file), "${it.file} was purged out from under a new job")
        }
        assertEquals(listOf("other_jobs_pending"), f.logger.fieldsOf("rec.retained").map { it["reason"] })

        val summary = f.service.runDueJobs()

        assertEquals(1, summary.jobIds.size, "the job enqueued mid-run runs on the next pass")
        clock.advance(Retention.WINDOW)
        f.service.runDueJobs()
        recording.meta.parts.forEach { assertFalse(fs.exists(recording.dir / it.file)) }
    }

    @Test
    fun claimPurgeKeepsTheRowsWhileAnyJobIsUnfinished() = runBlocking {
        val f = Fixture(emptyList())
        val recording = f.seed()
        f.enqueue(recording, driveStep("up"))

        assertEquals(PurgeClaim.OTHER_JOBS_PENDING, f.store.claimPurge(recording.id) { _, _ -> true })

        assertEquals(
            listOf(0L, 0L),
            f.db.recQueries.selectPartsByRecording(recording.id).executeAsList().map { it.deleted },
            "a refused claim must not mark any part deleted",
        )
    }

    @Test
    fun enqueueingAfterAPurgeIsRefused() = runBlocking {
        val upload = ScriptedRunner("drive.upload") { ctx, _ -> uploadOutput(ctx) }
        val f = Fixture(listOf(upload))
        val recording = f.seed()
        val jobId = f.enqueue(recording, driveStep("up"))
        f.service.runDueJobs()
        f.clock.advance(Retention.WINDOW)
        f.service.runDueJobs()
        recording.meta.parts.forEach { assertFalse(f.fs.exists(recording.dir / it.file)) }

        val later = testWorkflow(id = "01J9BBBBBBBBBBBBBBBBBBBBBB", steps = listOf(driveStep("up")))
        val result = f.service.enqueue(recording.id, testDocument(later), later.id)

        assertEquals(EnqueueResult.PartsPurged, result)
        assertEquals(listOf(jobId), f.service.observe().first().map { it.id }, "no job row was created")
    }

    @Test
    fun aWorkflowWithoutADriveUploadNeverPurgesTheParts() = runBlocking {
        val webhook = ScriptedRunner("webhook") { _, _ -> output("status" to "200") }
        val f = Fixture(listOf(webhook))
        val recording = f.seed()
        val jobId = f.enqueue(recording, webhookStep("hook"))

        f.service.runDueJobs()

        assertEquals(JobStatus.DONE, f.store.get(jobId)!!.status)
        recording.meta.parts.forEach { assertTrue(f.fs.exists(recording.dir / it.file)) }
        assertEquals(
            listOf(mapOf("recordingId" to recording.id, "reason" to "upload_not_succeeded")),
            f.logger.fieldsOf("rec.retained"),
        )
    }

    @Test
    fun aFailedStepIsNeverHandedToARunnerAgain() = runBlocking {
        val upload = ScriptedRunner("drive.upload") { _, _ ->
            throw StepFailure(retryable = false, reason = "MISSING_SECRET")
        }
        val webhook = ScriptedRunner("webhook") { _, calls ->
            if (calls == 1) throw StepFailure(retryable = true, reason = "503") else output("status" to "200")
        }
        val f = Fixture(listOf(upload, webhook))
        val jobId = f.enqueue(f.seed(), driveStep("up", onError = OnError.CONTINUE), webhookStep("hook"))

        f.service.runDueJobs()

        assertEquals(JobStatus.WAITING, f.store.get(jobId)!!.status)
        assertEquals(listOf(StepStatus.FAILED, StepStatus.PENDING), f.store.stepsOf(jobId).map { it.status })

        f.clock.advance(3601.seconds)
        f.service.runDueJobs()

        assertEquals(1, upload.calls, "a FAILED step must not be run again inside the same job")
        assertEquals(2, webhook.calls)
        assertEquals(JobStatus.DONE, f.store.get(jobId)!!.status)
        assertEquals(listOf(StepStatus.FAILED, StepStatus.SUCCEEDED), f.store.stepsOf(jobId).map { it.status })
    }

    @Test
    fun aPendingStepStillInBackoffIsNotRunEvenIfTheJobRowDisagrees() = runBlocking {
        val upload = ScriptedRunner("drive.upload") { ctx, _ -> uploadOutput(ctx) }
        val f = Fixture(listOf(upload))
        val jobId = f.enqueue(f.seed(), driveStep("up"))
        val stepId = f.store.stepsOf(jobId).single().id
        val later = f.clock.now() + 300.seconds
        // The half of a park that survived a crash: the step waits, the job row says RUNNING.
        f.db.recQueries.updateStepRun(StepStatus.PENDING.name, 1, later.isoUtc(), "503", null, stepId)
        f.db.recQueries.updateJobStatus(JobStatus.RUNNING.name, null, f.clock.now().isoUtc(), jobId)

        val summary = f.service.runDueJobs()

        assertEquals(0, upload.calls)
        assertEquals(emptyList(), summary.jobIds)
        val job = f.store.get(jobId)!!
        assertEquals(JobStatus.WAITING, job.status)
        assertEquals(later, job.nextRunAt)
    }

    @Test
    fun aNeedsAuthStepReParksTheJobEvenIfTheJobRowDisagrees() = runBlocking {
        val upload = ScriptedRunner("drive.upload") { ctx, _ -> uploadOutput(ctx) }
        val f = Fixture(listOf(upload))
        val jobId = f.enqueue(f.seed(), driveStep("up"))
        val stepId = f.store.stepsOf(jobId).single().id
        f.db.recQueries.updateStepRun(StepStatus.NEEDS_AUTH.name, 0, null, "401", null, stepId)
        f.db.recQueries.updateJobStatus(JobStatus.RUNNING.name, null, f.clock.now().isoUtc(), jobId)

        f.service.runDueJobs()

        assertEquals(0, upload.calls)
        assertEquals(JobStatus.NEEDS_AUTH, f.store.get(jobId)!!.status)
        assertEquals(StepStatus.NEEDS_AUTH, f.store.stepsOf(jobId).single().status)
    }

    /** docs/10 `StepOutcome.Waiting`: the provider is still working, which is not a failure. */
    @Test
    fun aWaitingStepParksTheJobWithoutSpendingAnAttempt() = runBlocking {
        val submitted = buildJsonObject {
            put("ref", "t-0001")
            put("submittedAt", "2026-08-26T01:00:00.000Z")
        }
        val stt = ScriptedRunner("transcribe") { _, calls ->
            if (calls == 1) StepOutcome.Waiting(30, submitted) else output("jsonFileId" to "F1")
        }
        val f = Fixture(listOf(stt))
        val jobId = f.enqueue(f.seed(), transcribeStep("stt"))

        f.service.runDueJobs()

        val job = f.store.get(jobId)!!
        assertEquals(JobStatus.WAITING, job.status)
        assertEquals(f.clock.now() + 30.seconds, job.nextRunAt)
        val step = f.store.stepsOf(jobId).single()
        assertEquals(StepStatus.PENDING, step.status)
        assertEquals(0, step.attempts, "polling does not spend the retry budget")
        assertEquals(f.clock.now() + 30.seconds, step.nextAttemptAt)
        assertEquals(null, step.lastError, "waiting is not an error to show")
        assertEquals(submitted, step.state)

        // Nothing is due before then, and the poll that follows sees the same submission ref.
        assertEquals(emptyList(), f.service.runDueJobs().jobIds)
        f.clock.advance(30.seconds)
        assertEquals(listOf(jobId), f.service.runDueJobs().jobIds)
        assertEquals(listOf(null, submitted), stt.states)
        assertEquals(JobStatus.DONE, f.store.get(jobId)!!.status)
        assertEquals(StepStatus.SUCCEEDED, f.store.stepsOf(jobId).single().status)
    }

    @Test
    fun aWaitingStepKeepsPollingTheSameJobAfterARestart() = runBlocking {
        val submitted = buildJsonObject { put("ref", "t-0001") }
        val f = Fixture(listOf(ScriptedRunner("transcribe") { _, _ -> StepOutcome.Waiting(30, submitted) }))
        val jobId = f.enqueue(f.seed(), transcribeStep("stt"))
        f.service.runDueJobs()

        // A fresh executor over the same database: the ref survives in `state_json`, so the audio
        // is never submitted (and paid for) twice.
        val resumed = ScriptedRunner("transcribe") { _, _ -> output("jsonFileId" to "F1") }
        f.clock.advance(30.seconds)
        f.executorWith(listOf(resumed)).runDueJobs(f.clock.now())

        assertEquals(listOf<JsonObject?>(submitted), resumed.states)
        assertEquals(JobStatus.DONE, f.store.get(jobId)!!.status)
    }

    @Test
    fun retryOnAJobThatIsWaitingForAProviderPollsNowAndKeepsItsState() = runBlocking {
        val submitted = buildJsonObject { put("ref", "t-0001") }
        val stt = ScriptedRunner("transcribe") { _, calls ->
            if (calls == 1) StepOutcome.Waiting(30, submitted) else output("jsonFileId" to "F1")
        }
        val f = Fixture(listOf(stt))
        val jobId = f.enqueue(f.seed(), transcribeStep("stt"))
        f.service.runDueJobs()

        assertTrue(f.service.retry(jobId))

        val parked = f.store.get(jobId)!!
        assertEquals(JobStatus.PENDING, parked.status)
        assertEquals(null, parked.nextRunAt)
        assertEquals(submitted, f.store.stepsOf(jobId).single().state, "the submission is not thrown away")

        // The clock has not moved: without the retry nothing would have been due.
        assertEquals(listOf(jobId), f.service.runDueJobs().jobIds)
        assertEquals(listOf(null, submitted), stt.states)
        assertEquals(JobStatus.DONE, f.store.get(jobId)!!.status)
    }

    @Test
    fun aCancelledPollLeavesTheSubmissionForTheNextPass() = runBlocking {
        val submitted = buildJsonObject { put("ref", "t-0001") }
        val killed = ScriptedRunner("transcribe") { ctx, _ ->
            ctx.saveState(submitted)
            throw CancellationException("process killed")
        }
        val f = Fixture(listOf(killed))
        val jobId = f.enqueue(f.seed(), transcribeStep("stt"))

        assertFailsWith<CancellationException> { f.service.runDueJobs() }

        val resumed = ScriptedRunner("transcribe") { _, _ -> output("jsonFileId" to "F1") }
        f.executorWith(listOf(resumed)).runDueJobs(f.clock.now())

        assertEquals(listOf<JsonObject?>(submitted), resumed.states, "the poll resumes from the saved ref")
        assertEquals(0, f.store.stepsOf(jobId).single().attempts)
        assertEquals(JobStatus.DONE, f.store.get(jobId)!!.status)
    }

    @Test
    fun anUnknownStepTypeFailsTheJobInsteadOfHanging() = runBlocking {
        val f = Fixture(emptyList())
        val jobId = f.enqueue(f.seed(), driveStep("up"))

        f.service.runDueJobs()

        assertEquals(JobStatus.FAILED, f.store.get(jobId)!!.status)
        assertEquals(
            CoreMessage.NO_RUNNER.code("drive.upload"),
            f.store.stepsOf(jobId).single().lastError,
        )
    }
}
