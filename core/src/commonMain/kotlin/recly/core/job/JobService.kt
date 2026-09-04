@file:OptIn(ExperimentalTime::class)

package recly.core.job

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import recly.core.model.WorkflowsDocument
import recly.core.platform.CoreDeps
import recly.core.recording.RecordingRepository
import recly.core.workflow.WorkflowSelector

sealed interface EnqueueResult {
    /**
     * Neither the recording's own pick nor this device's default resolves (ADR-016) — the recording
     * stays in the list, unprocessed, until the user picks a default and runs it.
     */
    data object NoWorkflow : EnqueueResult

    /** The parts are already uploaded and deleted: there is nothing left for a new job to do. */
    data object PartsPurged : EnqueueResult

    data class SkippedShort(val jobId: String) : EnqueueResult

    /** This workflow already ran to completion for this recording; a DONE job is not rerun. */
    data class AlreadyDone(val jobId: String) : EnqueueResult

    data class Enqueued(val jobId: String) : EnqueueResult
}

/** What the shell talks to: enqueue after a recording is finalized, run, retry, observe. */
class JobService(
    private val deps: CoreDeps,
    private val store: JobStore,
    private val recordings: RecordingRepository,
    private val executor: Executor,
) {
    private val retention = Retention(deps, store, recordings)

    /**
     * [chosenWorkflowId] is the pick made in the UI at stop time; without one the workflow the
     * recording itself was started with wins, then [deviceDefaultWorkflowId] — this device's own
     * default, which the caller reads from
     * [recly.core.sync.WorkflowRepository.deviceDefault] (ADR-016).
     */
    suspend fun enqueue(
        recordingId: String,
        doc: WorkflowsDocument,
        chosenWorkflowId: String? = null,
        deviceDefaultWorkflowId: String? = null,
    ): EnqueueResult {
        val record = recordings.get(recordingId)
            ?: throw IllegalArgumentException("unknown recording '$recordingId'")
        // docs/03 "다른 기기의 녹음": Drive already holds it and this device has no original to send.
        // The answer a purged recording gets, for the same reason: nothing for a job to do.
        if (record.remote) return EnqueueResult.PartsPurged
        val meta = record.meta
        val workflow =
            WorkflowSelector.select(doc, chosenWorkflowId ?: meta.workflowId, deviceDefaultWorkflowId)
                ?: return EnqueueResult.NoWorkflow
        // Too short to be worth uploading, but the job row exists so the list can offer a manual run.
        val short = (meta.durationSec ?: 0.0) < workflow.minDurationSec
        val status = if (short) JobStatus.SKIPPED_SHORT else JobStatus.PENDING
        val job = store.enqueue(recordingId, workflow, deps.clock.now(), status)
            ?: return EnqueueResult.PartsPurged
        return when {
            job.status == JobStatus.DONE -> EnqueueResult.AlreadyDone(job.id)
            short -> EnqueueResult.SkippedShort(job.id)
            else -> EnqueueResult.Enqueued(job.id)
        }
    }

    /**
     * `@Throws` for the same reason [recly.core.ReclyCore.runDueJobs] has it: a step resolving its
     * `secretRef` reads the shell's store, a store that will not be read throws, and on
     * Kotlin/Native an undeclared exception out of an exported suspend function ends the process
     * rather than reaching the caller.
     *
     * Every pass ends with the [Retention] sweep — the only thing that deletes local audio now, so
     * it has to be somewhere every shell already calls on a schedule (docs/11 A5, docs/12 "실행기").
     */
    @Throws(Throwable::class)
    suspend fun runDueJobs(now: Instant = deps.clock.now()): RunSummary {
        val summary = executor.runDueJobs(now)
        // The pass already in flight sweeps when it ends; two of them would only race for the
        // same claims, and this one has run nothing.
        if (!summary.alreadyRunning) retention.sweep(now)
        return summary
    }

    /** "연결 해제" (docs/03) runs in here; see [Executor.quiesced]. Internal: the shells never
     * quiesce the queue themselves, they call `ReclyCore.disconnect`. */
    internal suspend fun <T> quiesced(block: suspend () -> T): T = executor.quiesced(block)

    /**
     * Manual re-run from the list, and what an app calls after a successful sign-in.
     *
     * Two different things, deliberately under one name because the list offers one button. A job
     * parked on a backoff ([JobStatus.WAITING]) is only impatient: the wait is dropped and it
     * becomes due now, keeping the attempts it has already spent, so "Upload now" cannot be used to
     * refill a retry budget forever. Everything else in [RETRYABLE] has actually stopped, and gets
     * a fresh budget — `state_json` survives either way, so a half-finished upload resumes.
     */
    suspend fun retry(jobId: String): Boolean {
        val job = store.get(jobId) ?: return false
        val now = deps.clock.now()
        return when {
            job.status == JobStatus.WAITING -> {
                store.clearBackoff(jobId, now)
                true
            }

            job.status in RETRYABLE -> {
                store.resetForRerun(jobId, now)
                true
            }

            else -> false
        }
    }

    fun observe(): Flow<List<Job>> = store.observeJobs()

    /**
     * The queue as it stands, once. Every shell has to re-read it after a pass to arm the next run
     * (docs/11 A5, docs/12 "실행기"); Android takes the first emission of [observe], and a shell whose
     * Obj-C bridge does not carry `Flow` — the Apple one — has no way to do that.
     */
    suspend fun list(): List<Job> = store.list()

    /**
     * Read-only, for the list screen: the failure reason a job shows is the last error of its
     * steps, and the Drive link it offers lives in the `drive.upload` step's output.
     */
    suspend fun steps(jobId: String): List<StepRun> = store.stepsOf(jobId)

    private companion object {
        val RETRYABLE = setOf(
            JobStatus.NEEDS_AUTH,
            // docs/10: nothing tells the core that space was freed, so "다시 시도" is the only way out.
            JobStatus.NEEDS_SPACE,
            JobStatus.FAILED,
            JobStatus.SKIPPED_SHORT,
        )
    }
}
