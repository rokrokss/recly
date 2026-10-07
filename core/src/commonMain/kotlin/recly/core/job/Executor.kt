@file:OptIn(ExperimentalTime::class)

package recly.core.job

import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import recly.core.transcribe.StorefrontUnavailableException
import recly.core.drive.DriveUploadRunner
import recly.core.drive.FolderMarker
import recly.core.drive.string
import recly.core.message.CoreMessage
import recly.core.model.OnError
import recly.core.model.Step
import recly.core.storage.StorageKind
import recly.core.model.Workflow
import recly.core.platform.AuthRequiredException
import recly.core.platform.CoreDeps
import recly.core.privacy.TransferConsents
import recly.core.platform.Logger.Level
import recly.core.recording.RecordingRecord
import recly.core.recording.RecordingRepository

data class RunSummary(
    val alreadyRunning: Boolean = false,
    /** Jobs the executor took through [Executor.runDueJobs], oldest first. */
    val jobIds: List<String> = emptyList(),
)

/**
 * Runs due jobs one step at a time, persisting after every transition so a kill (WorkManager
 * stop, app exit) costs at most the step in flight. Step runners are looked up by [Step.type].
 */
class Executor(
    private val deps: CoreDeps,
    private val store: JobStore,
    private val recordings: RecordingRepository,
    private val runners: Map<String, StepRunner>,
    private val random: Random = Random.Default,
    /**
     * docs/03 "Recordings from other devices": what this device still has to do, written on the recording's Drive
     * folder so the other devices' lists can say so. Advisory — the default writes nothing.
     */
    private val marker: FolderMarker = FolderMarker.NONE,
    private val transferConsents: TransferConsents? = null,
    private val prepare: suspend () -> Unit = {},
    private val requireAccess: suspend (Job) -> Unit = {},
) {
    private val mutex = Mutex()

    /** Set by [quiesced] while a "Disconnect" waits for the gate; read between steps, from the thread
     * the run is on rather than the one disconnecting. */
    @Volatile
    private var disconnecting = false

    /** The marker last written in the job being run, so a job that ends on a successful step does
     * not write the same value again for its DONE. Reset per job; runs are serialized by [mutex]. */
    private var lastMark: Pair<String, List<String>>? = null

    /**
     * The run in flight and the recordings being deleted ([stopping]), under a lock of their own
     * rather than [mutex]: "Disconnect" deletes recordings inside [quiesced], which holds [mutex]
     * for the whole of it.
     */
    private val runs = Mutex()
    private var current: Run? = null
    private val deleting = mutableListOf<String>()

    /** One job's run ([run]). [stopped] is set by [stopping], under [runs], when the run is let go. */
    private class Run(val job: Job, val task: Deferred<Unit>, val done: CompletableDeferred<Unit>) {
        var stopped = false
    }

    /** One job at a time, oldest first (docs/10 "Concurrency"). Re-entrant calls return immediately —
     * a scheduler that fires while a run is in flight must not double-run a step. */
    suspend fun runDueJobs(now: Instant = deps.clock.now()): RunSummary = runFiltered(now, false)

    internal suspend fun runLocalJobs(now: Instant): RunSummary = runFiltered(now, true)

    private suspend fun runFiltered(now: Instant, localOnly: Boolean): RunSummary {
        if (!mutex.tryLock()) return RunSummary(alreadyRunning = true)
        try {
            if (disconnecting) return RunSummary()
            if (!localOnly) prepare()
            store.recoverRunning(deps.clock.now())
            val ran = mutableListOf<String>()
            for (job in store.selectDue(now)) {
                if (disconnecting) break
                currentCoroutineContext().ensureActive()
                if (localOnly && !isLocalNext(job)) continue
                run(job, now, localOnly)
                ran += job.id
            }
            return RunSummary(jobIds = ran)
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Runs [block] with nothing of the queue in flight — what "Disconnect" (docs/03) needs before it
     * empties the secrets, the tokens and the queue rows a run would otherwise still be reading.
     *
     * Two halves: [disconnecting] stops a run that is already going between its steps — the step in
     * flight finishes, nothing external is called after it, and the job row is left `RUNNING` for
     * `JobStore.recoverRunning` exactly the way a killed process leaves it — and the gate itself is
     * only handed over once that run has returned. A [runDueJobs] that arrives meanwhile finds the
     * gate taken and reports [RunSummary.alreadyRunning].
     */
    internal suspend fun <T> quiesced(block: suspend () -> T): T {
        disconnecting = true
        try {
            return mutex.withLock { block() }
        } finally {
            disconnecting = false
        }
    }

    /**
     * docs/03 "Deleting in the app": [body] — the deletion of [recordingId] — with no run of that
     * recording left to write anything. A run in flight is cancelled and let go of: the deletion
     * gives it [STOP_GRACE] to wind down, and does not wait for a step that cancelling does not
     * reach ([run]). For as long as [body] runs no job of the recording is started, so the rows it
     * deletes are nobody's, `RUNNING` or not.
     */
    internal suspend fun <T> stopping(recordingId: String, body: suspend () -> T): T {
        val run = runs.withLock {
            deleting += recordingId
            current?.takeIf { it.job.recordingId == recordingId }?.also {
                it.stopped = true
                it.task.cancel()
                it.done.complete(Unit)
            }
        }
        try {
            if (run != null) {
                val settled = withTimeoutOrNull(STOP_GRACE) { run.task.join() } != null
                deps.logger.log(
                    Level.INFO,
                    "job.stopped",
                    mapOf("jobId" to run.job.id, "recordingId" to recordingId, "settled" to settled),
                )
            }
            return body()
        } finally {
            withContext(NonCancellable) { runs.withLock { deleting -= recordingId } }
        }
    }

    /**
     * [runJob] as a coroutine of its own rather than a part of the pass, so that deleting the
     * recording ([stopping]) stops it without stopping the pass — and without the pass waiting for
     * it. A step can be suspended where cancelling does not reach: the iPhone's background upload
     * session answers a chunk only once it is sent (docs/13 I4), and a pass that waited for that
     * would hold every other recording's job behind one that no longer exists.
     *
     * A run let go of does nothing once it wakes up. It is cancelled; every row it would write goes
     * through `withContext(deps.io)` (JobStore, RecordingRepository), which does not start for a
     * cancelled coroutine; and those writes are `UPDATE … WHERE id`, so even one already under way
     * cannot bring a deleted row back. The resumable upload saves its state after every chunk
     * (`DriveApi.uploadResumable`), so it stops at the first chunk that comes back.
     *
     * Everything else is as it was: a failure of the run comes out of the pass, and a pass that is
     * itself cancelled (WorkManager, a background task's expiry) cancels the run and returns only
     * once the run has, leaving the job row `RUNNING` for [JobStore.recoverRunning].
     */
    private suspend fun run(job: Job, now: Instant, localOnly: Boolean) {
        val task = CoroutineScope(currentCoroutineContext().minusKey(kotlinx.coroutines.Job))
            .async(start = CoroutineStart.LAZY) { runJob(job, now, localOnly) }
        val done = CompletableDeferred<Unit>()
        task.invokeOnCompletion { done.complete(Unit) }
        val run = Run(job, task, done)
        val admitted = runs.withLock { (job.recordingId !in deleting).also { if (it) current = run } }
        if (!admitted) {
            task.cancel()
            return
        }
        try {
            task.start()
            try {
                done.await()
            } catch (e: CancellationException) {
                task.cancel()
                withContext(NonCancellable) { done.await() }
                throw e
            }
        } finally {
            withContext(NonCancellable) { runs.withLock { if (current === run) current = null } }
        }
        if (!run.stopped) task.await()
    }

    internal suspend fun isLocalNext(job: Job): Boolean {
        val run = store.stepsOf(job.id).firstOrNull { it.status !in setOf(StepStatus.SUCCEEDED, StepStatus.SKIPPED) } ?: return false
        val workflow = job.workflow ?: return false
        return workflow.steps.firstOrNull { it.id == run.stepId }?.let { offline(it, workflow) } == true
    }

    /**
     * What the offline pass may run: on-device transcription, and the copies into a local folder
     * (docs/03 "Storage location") — writing to a folder on the device is no network request, so it
     * does not wait for a connection, or for Wi-Fi.
     */
    private fun offline(step: Step, workflow: Workflow): Boolean = when (step) {
        is Step.LocalTranscribe -> true
        is Step.DriveUpload -> step.store == StorageKind.FOLDER
        is Step.TranscriptPublish -> (step.folderId?.let(StorageKind::ofId) ?: workflow.steps.firstNotNullOfOrNull {
            (it as? Step.DriveUpload)?.store
        }) == StorageKind.FOLDER
        else -> false
    }

    private suspend fun runJob(job: Job, now: Instant, localOnly: Boolean) {
        // Before the claim, so a job [JobStore.selectDue] would never have handed over is left as
        // it is rather than parked in RUNNING: a snapshot this build cannot decode has nothing to
        // run against, and the list already shows it as failed (docs/10 "job snapshot").
        val workflow = job.workflow ?: return
        // The claim before the work, and transactional: a job the deletion of its recording won is
        // simply gone. A deletion that comes later stops this run first ([stopping]).
        if (!store.claimRunning(job.id, deps.clock.now())) return
        val recording = recordings.get(job.recordingId)
        if (recording == null) {
            fail(job, "recording '${job.recordingId}' is gone")
            return
        }
        val defined = workflow.steps.associateBy { it.id }
        val prior = mutableMapOf<String, StepOutput>()
        lastMark = null
        for (run in store.stepsOf(job.id)) {
            if (run.status == StepStatus.SUCCEEDED || run.status == StepStatus.SKIPPED) {
                run.output?.let { prior[run.stepId] = StepOutput(it) }
                continue
            }
            // FAILED is terminal. A failed step only survives inside a job that still runs because
            // its onError was `continue`, so it is already dealt with — and it contributes no
            // output. Only retry() turns it back into PENDING.
            if (run.status == StepStatus.FAILED) continue
            // The two guards below re-derive the parked state from the step row alone, so a lost
            // job-row write cannot make the executor jump a backoff or re-run a step that is
            // waiting for sign-in.
            if (run.status == StepStatus.NEEDS_AUTH) {
                store.park(run, JobStatus.NEEDS_AUTH, null, deps.clock.now())
                return
            }
            if (run.status == StepStatus.NEEDS_SPACE) {
                store.park(run, JobStatus.NEEDS_SPACE, null, deps.clock.now())
                return
            }
            if (run.status == StepStatus.NEEDS_CONSENT) {
                store.park(run, JobStatus.NEEDS_CONSENT, null, deps.clock.now())
                return
            }
            if (run.status == StepStatus.NEEDS_MODEL) {
                store.park(run, JobStatus.NEEDS_MODEL, null, deps.clock.now())
                return
            }
            val waitUntil = run.nextAttemptAt
            if (waitUntil != null && waitUntil > now) {
                store.park(run, JobStatus.WAITING, waitUntil, deps.clock.now())
                return
            }
            // A disconnect is waiting for the gate: the step that just finished was the last one
            // this run makes an external call from. The rows are left the way a kill leaves them.
            if (disconnecting) return
            currentCoroutineContext().ensureActive()
            val step = defined[run.stepId]
            if (localOnly && (step == null || !offline(step, workflow))) {
                store.updateJob(job.id, JobStatus.PENDING, null, deps.clock.now())
                return
            }
            val outcome = if (step == null) {
                // The snapshot and the rows disagree: nothing can run this, so it is terminal.
                terminal(job, run, OnError.ABORT, CoreMessage.STEP_MISSING.code(run.stepId))
            } else {
                runStep(job, workflow, run, step, recording, prior)
            }
            when (outcome) {
                is Outcome.Ok -> {
                    prior[run.stepId] = outcome.output
                    // After the step, not before: the marker says what is *left*.
                    if (!localOnly) mark(job, workflow, prior, after = run.stepId)
                }

                Outcome.Continue -> Unit
                Outcome.Stop -> {
                    // A job parked in FAILED is not coming back on its own, so nothing it promised
                    // will ever run: the other devices are told to stop waiting (docs/03). The
                    // other parks — WAITING, NEEDS_AUTH, NEEDS_SPACE — do come back, and keep it.
                    if (!localOnly && store.get(job.id)?.status == JobStatus.FAILED) mark(job, workflow, prior, after = null)
                    return
                }
            }
        }
        store.updateJob(job.id, JobStatus.DONE, null, deps.clock.now())
        if (!localOnly) mark(job, workflow, prior, after = null)
        deps.logger.log(Level.INFO, "job.done", mapOf("jobId" to job.id, "recordingId" to job.recordingId))
        // Nothing is deleted here any more: once the upload has succeeded the parts are a cache
        // with a window on it, which Retention sweeps at the end of the pass (ADR-017).
    }

    /**
     * The folder marker of docs/03 "Recordings from other devices": the types of the steps that come after [after],
     * or none at all when the job is over one way or the other. The folder is the one the
     * `drive.upload` step left in its output — a job that has not uploaded yet has no folder to
     * write on, and a workflow without an upload has nothing to say to anybody.
     */
    private suspend fun mark(job: Job, workflow: Workflow, prior: Map<String, StepOutput>, after: String?) {
        if (after == null) {
            // Over, one way or the other: every folder any upload of this job made is told so. The
            // folders come from the persisted output of each upload's own row — `saveStepOutput`
            // wrote the folder before the first byte went up and a failure never clears it
            // (docs/10) — so an upload that failed for good after marking its folder, and a second
            // upload that failed after a first one succeeded, are both taken down (Sol, 2026-09-04).
            val uploads = workflow.steps.filter { it.type == DriveUploadRunner.TYPE }.map { it.id }.toSet()
            val folders = store.stepsOf(job.id)
                .filter { it.stepId in uploads }
                .mapNotNull { it.output?.string("folderId") }
                .toSet() + listOfNotNull(namedFolder(workflow))
            for (folderId in folders) send(folderId, emptyList())
            return
        }
        val folderId = workflow.priorOutput(prior, DriveUploadRunner.TYPE)?.string("folderId")
            ?: namedFolder(workflow) ?: return
        send(folderId, workflow.steps.dropWhile { it.id != after }.drop(1).map {
            if (it is Step.LocalTranscribe || it is Step.TranscriptPublish) "transcribe" else it.type
        }.distinct())
    }

    /** The folder a re-transcription publishes into, which its plan names since it uploads nothing (docs/10). */
    private fun namedFolder(workflow: Workflow): String? =
        workflow.steps.firstNotNullOfOrNull { (it as? Step.TranscriptPublish)?.folderId }

    private suspend fun send(folderId: String, pending: List<String>) {
        if (lastMark == folderId to pending) return
        lastMark = folderId to pending
        marker.mark(folderId, pending)
    }

    private suspend fun runStep(
        job: Job,
        workflow: Workflow,
        run: StepRun,
        step: Step,
        recording: RecordingRecord,
        prior: Map<String, StepOutput>,
    ): Outcome {
        if (run.attempts >= step.retry.maxAttempts) {
            return terminal(job, run, step.onError, CoreMessage.RETRY_BUDGET_SPENT.code(run.lastError))
        }
        val runner = runners[step.type]
            ?: return terminal(job, run, step.onError, CoreMessage.NO_RUNNER.code(step.type))

        deps.logger.log(
            Level.INFO,
            "job.step.start",
            mapOf("jobId" to job.id, "stepId" to step.id, "attempt" to run.attempts + 1),
        )
        val running = run.copy(status = StepStatus.RUNNING)
        store.updateStep(running)
        val ctx = StepContext(
            job = job,
            workflow = workflow,
            stepRunId = run.id,
            step = step,
            recording = recording,
            prior = prior.toMap(),
            state = run.state,
            saveState = { store.saveStepState(run.id, it) },
            saveOutput = { store.saveStepOutput(run.id, it) },
            deps = deps.transcriptionPolicy.guardedDeps(step, transferConsents?.guardedDeps(step) ?: deps),
        )
        val outcome = try {
            if (step !is Step.LocalTranscribe) requireAccess(job)
            deps.transcriptionPolicy.requireAllowed(step)
            transferConsents?.requireAllowed(step)
            runner.run(ctx)
        } catch (e: CancellationException) {
            throw e // The row stays RUNNING; the next run resets and repeats it from its saved state.
        } catch (_: StorefrontUnavailableException) {
            val state = store.stepsOf(job.id).first { it.id == run.id }.state ?: JsonObject(emptyMap())
            return waiting(job, running, step, StepOutcome.Waiting(
                60, state, CoreMessage.STOREFRONT_UNAVAILABLE.code(),
            ))
        } catch (e: AuthRequiredException) {
            return needsAuth(job, running, e.message ?: CoreMessage.NEEDS_AUTH.code())
        } catch (e: StepFailure) {
            return when {
                e.needsConsent -> needsConsent(running, e.reason)
                e.needsModel -> needsModel(running, e.reason)
                e.needsAuth -> needsAuth(job, running, e.reason)
                e.needsSpace -> needsSpace(job, running, e.reason)
                else -> failed(job, running, step, e.retryable, e.reason, e.retryAfterSec)
            }
        } catch (e: Throwable) {
            return failed(
                job,
                running,
                step,
                retryable = true,
                reason = CoreMessage.STEP_FAILED.code(e.message ?: "${e::class.simpleName}"),
            )
        }
        if (outcome is StepOutcome.Waiting) return waiting(job, running, step, outcome)
        val output = (outcome as StepOutcome.Done).output
        store.updateStep(
            running.copy(status = StepStatus.SUCCEEDED, lastError = null, nextAttemptAt = null, output = output.json),
        )
        deps.logger.log(Level.INFO, "job.step.ok", mapOf("jobId" to job.id, "stepId" to step.id))
        return Outcome.Ok(output)
    }

    /**
     * Polling, not failing: the step goes back to `PENDING` with the attempts it already had, and
     * the job waits out [StepOutcome.Waiting.retryAfterSec]. The state is written before the pair
     * of rows, so a crash in between cannot lose the submission ref and re-submit the audio.
     */
    private suspend fun waiting(job: Job, run: StepRun, step: Step, outcome: StepOutcome.Waiting): Outcome {
        val now = deps.clock.now()
        val next = now + outcome.retryAfterSec.seconds
        store.saveStepState(run.id, outcome.state)
        store.park(
            run.copy(status = StepStatus.PENDING, nextAttemptAt = next, lastError = outcome.reason),
            JobStatus.WAITING,
            next,
            now,
        )
        deps.logger.log(
            Level.INFO,
            "job.step.waiting",
            mapOf(
                "jobId" to job.id,
                "stepId" to step.id,
                "attempts" to run.attempts,
                "retryAfterSec" to outcome.retryAfterSec,
            ),
        )
        return Outcome.Stop
    }

    /** Consent is not a failure: onError cannot bypass it and no retry attempt is spent. */
    private suspend fun needsConsent(run: StepRun, reason: String): Outcome {
        store.park(
            run.copy(status = StepStatus.NEEDS_CONSENT, nextAttemptAt = null, lastError = reason),
            JobStatus.NEEDS_CONSENT,
            null,
            deps.clock.now(),
        )
        return Outcome.Stop
    }

    /** The on-device model is not downloaded yet: a wait like consent, resumed by the download. */
    private suspend fun needsModel(run: StepRun, reason: String): Outcome {
        store.park(
            run.copy(status = StepStatus.NEEDS_MODEL, nextAttemptAt = null, lastError = reason),
            JobStatus.NEEDS_MODEL,
            null,
            deps.clock.now(),
        )
        return Outcome.Stop
    }

    private suspend fun failed(
        job: Job,
        run: StepRun,
        step: Step,
        retryable: Boolean,
        reason: String,
        retryAfterSec: Long? = null,
    ): Outcome {
        val now = deps.clock.now()
        val attempts = run.attempts + 1
        deps.logger.log(
            Level.WARN,
            "job.step.fail",
            mapOf(
                "jobId" to job.id,
                "stepId" to step.id,
                "attempts" to attempts,
                "retryable" to retryable,
                "reason" to reason,
            ),
        )
        if (retryable && attempts < step.retry.maxAttempts) {
            // A server that says when to come back knows better than our backoff curve — but only
            // within the step's own ceiling (docs/04 "a 429's Retry-After … capped at maxDelaySec").
            val delay = retryAfterSec?.coerceIn(1L, step.retry.maxDelaySec.toLong())
                ?: Backoff.delaySec(attempts, step.retry, random)
            val next = now + delay.seconds
            store.park(
                run.copy(
                    status = StepStatus.PENDING,
                    attempts = attempts,
                    nextAttemptAt = next,
                    lastError = reason,
                ),
                JobStatus.WAITING,
                next,
                now,
            )
            return Outcome.Stop
        }
        return end(
            job,
            run.copy(status = StepStatus.FAILED, attempts = attempts, nextAttemptAt = null, lastError = reason),
            step.onError,
            reason,
        )
    }

    /** No attempt was spent: signing in again, not waiting, is what unblocks this. */
    private suspend fun needsAuth(job: Job, run: StepRun, reason: String): Outcome {
        store.park(
            run.copy(status = StepStatus.NEEDS_AUTH, nextAttemptAt = null, lastError = reason),
            JobStatus.NEEDS_AUTH,
            null,
            deps.clock.now(),
        )
        deps.logger.log(
            Level.WARN,
            "job.step.fail",
            mapOf("jobId" to job.id, "stepId" to run.stepId, "needsAuth" to true, "reason" to reason),
        )
        return Outcome.Stop
    }

    /**
     * docs/10 "Drive out of space": no attempt is spent either, because retrying a full Drive only
     * produces the same 403 — the user has to clear space and press "Retry". The resumable
     * session in `state_json` goes with it: Drive keeps one for a week, and by the time somebody
     * has made room a fresh session is the surer bet.
     */
    private suspend fun needsSpace(job: Job, run: StepRun, reason: String): Outcome {
        store.parkNeedsSpace(
            run.copy(status = StepStatus.NEEDS_SPACE, nextAttemptAt = null, lastError = reason),
            deps.clock.now(),
        )
        deps.logger.log(
            Level.WARN,
            "job.step.fail",
            mapOf("jobId" to job.id, "stepId" to run.stepId, "needsSpace" to true, "reason" to reason),
        )
        return Outcome.Stop
    }

    private suspend fun terminal(job: Job, run: StepRun, onError: OnError, reason: String): Outcome {
        deps.logger.log(
            Level.WARN,
            "job.step.fail",
            mapOf("jobId" to job.id, "stepId" to run.stepId, "attempts" to run.attempts, "reason" to reason),
        )
        return end(job, run.copy(status = StepStatus.FAILED, nextAttemptAt = null, lastError = reason), onError, reason)
    }

    /** Writes the failed step row, and with `abort` the job row that goes with it, atomically. */
    private suspend fun end(job: Job, failedStep: StepRun, onError: OnError, reason: String): Outcome =
        when (onError) {
            OnError.ABORT -> {
                store.park(failedStep, JobStatus.FAILED, null, deps.clock.now())
                deps.logger.log(Level.ERROR, "job.failed", mapOf("jobId" to job.id, "reason" to reason))
                Outcome.Stop
            }

            OnError.CONTINUE -> {
                store.updateStep(failedStep)
                Outcome.Continue
            }
        }

    private suspend fun fail(job: Job, reason: String) {
        store.updateJob(job.id, JobStatus.FAILED, null, deps.clock.now())
        deps.logger.log(Level.ERROR, "job.failed", mapOf("jobId" to job.id, "reason" to reason))
    }

    private sealed interface Outcome {
        data class Ok(val output: StepOutput) : Outcome

        /** The step failed but `onError: continue` says the rest of the job still runs. */
        data object Continue : Outcome

        /** The job is parked (WAITING / FAILED / NEEDS_AUTH); leave the remaining steps alone. */
        data object Stop : Outcome
    }

    private companion object {
        /** How long a deletion waits for the run it stopped to wind down before it goes on regardless. */
        val STOP_GRACE = 2.seconds
    }
}
