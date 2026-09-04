@file:OptIn(ExperimentalTime::class)

package recly.core.job

import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import recly.core.message.CoreMessage
import recly.core.model.OnError
import recly.core.model.Step
import recly.core.model.Workflow
import recly.core.model.WorkflowsDocument
import recly.core.platform.AuthRequiredException
import recly.core.platform.CoreDeps
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
    /** The workflow document as it is now, for [liveSteps]; null when the shell has none to offer. */
    private val live: suspend () -> WorkflowsDocument? = { null },
) {
    private val mutex = Mutex()

    /** Set by [quiesced] while a "연결 해제" waits for the gate; read between steps, from the thread
     * the run is on rather than the one disconnecting. */
    @Volatile
    private var disconnecting = false

    /** One job at a time, oldest first (docs/10 "동시성"). Re-entrant calls return immediately —
     * a scheduler that fires while a run is in flight must not double-run a step. */
    suspend fun runDueJobs(now: Instant = deps.clock.now()): RunSummary {
        if (!mutex.tryLock()) return RunSummary(alreadyRunning = true)
        try {
            if (disconnecting) return RunSummary()
            store.recoverRunning(deps.clock.now())
            val ran = mutableListOf<String>()
            for (job in store.selectDue(now)) {
                if (disconnecting) break
                currentCoroutineContext().ensureActive()
                runJob(job, now)
                ran += job.id
            }
            return RunSummary(jobIds = ran)
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Runs [block] with nothing of the queue in flight — what "연결 해제" (docs/03) needs before it
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

    private suspend fun runJob(job: Job, now: Instant) {
        // Before the claim, so a job [JobStore.selectDue] would never have handed over is left as
        // it is rather than parked in RUNNING: a snapshot this build cannot decode has nothing to
        // run against, and the list already shows it as failed (docs/10 "잡 스냅샷").
        val workflow = job.workflow ?: return
        // The claim before the work, and transactional: "녹음 삭제" refuses a recording whose job is
        // RUNNING in a transaction of its own, so between the two of them a run and a deletion of
        // what it reads cannot both happen. A job the deletion won is simply gone.
        if (!store.claimRunning(job.id, deps.clock.now())) return
        val recording = recordings.get(job.recordingId)
        if (recording == null) {
            fail(job, "recording '${job.recordingId}' is gone")
            return
        }
        val defined = liveSteps(workflow)
        val prior = mutableMapOf<String, StepOutput>()
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
            val outcome = if (step == null) {
                // The snapshot and the rows disagree: nothing can run this, so it is terminal.
                terminal(job, run, OnError.ABORT, CoreMessage.STEP_MISSING.code(run.stepId))
            } else {
                runStep(job, workflow, run, step, recording, prior)
            }
            when (outcome) {
                is Outcome.Ok -> prior[run.stepId] = outcome.output
                Outcome.Continue -> Unit
                Outcome.Stop -> return
            }
        }
        store.updateJob(job.id, JobStatus.DONE, null, deps.clock.now())
        deps.logger.log(Level.INFO, "job.done", mapOf("jobId" to job.id, "recordingId" to job.recordingId))
        // Nothing is deleted here any more: once the upload has succeeded the parts are a cache
        // with a window on it, which Retention sweeps at the end of the pass (ADR-017).
    }

    /**
     * docs/10 "잡 스냅샷": the snapshot is what the job *is*; the document is what the user *means*
     * now, and a user who fixes a step's URL or key after it failed expects the next attempt to use
     * the fix (Z Fold7, 2026-09-04: a parked transcribe kept calling the Free Clova domain the
     * snapshot named while the workflow had pointed at the Basic one for half an hour). So a step
     * that is still in the current document — same workflow id, same step id, same type — runs with
     * the document's definition; a workflow that is gone, a step that was removed or given another
     * type, and a document the shell cannot read all leave the snapshot in charge, exactly as before.
     */
    private suspend fun liveSteps(snapshot: Workflow): Map<String, Step> {
        val defined = snapshot.steps.associateBy { it.id }.toMutableMap()
        val current = runCatching { live() }.getOrNull()
            ?.workflows?.firstOrNull { it.id == snapshot.id }
            ?: return defined
        for (step in current.steps) {
            if (defined[step.id]?.type == step.type) defined[step.id] = step
        }
        return defined
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
            deps = deps,
        )
        val outcome = try {
            runner.run(ctx)
        } catch (e: CancellationException) {
            throw e // The row stays RUNNING; the next run resets and repeats it from its saved state.
        } catch (e: AuthRequiredException) {
            return needsAuth(job, running, e.message ?: CoreMessage.NEEDS_AUTH.code())
        } catch (e: StepFailure) {
            return when {
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
            run.copy(status = StepStatus.PENDING, nextAttemptAt = next, lastError = null),
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
            // within the step's own ceiling (docs/04 "429의 Retry-After … maxDelaySec 상한").
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
     * docs/10 "Drive 용량 초과": no attempt is spent either, because retrying a full Drive only
     * produces the same 403 — the user has to clear space and press "다시 시도". The resumable
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

}
