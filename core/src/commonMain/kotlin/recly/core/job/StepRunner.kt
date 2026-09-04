package recly.core.job

import kotlinx.serialization.json.JsonObject
import recly.core.model.Step
import recly.core.model.Workflow
import recly.core.platform.CoreDeps
import recly.core.recording.RecordingRecord

/** The wire name of a step type — the key the [Executor] looks a runner up by. */
val Step.type: String
    get() = when (this) {
        is Step.DriveUpload -> "drive.upload"
        is Step.Webhook -> "webhook"
        is Step.Transcribe -> "transcribe"
    }

interface StepRunner {
    val type: String

    /** Returns the step's outcome, or throws [StepFailure]. Call `ctx.saveState` often enough that
     * a kill between chunks costs at most one chunk. */
    suspend fun run(ctx: StepContext): StepOutcome
}

/**
 * What a runner hands back. [Waiting] is the polling case (docs/10): the work is with a provider
 * and there is nothing to do until it answers, which is not a failure — so no attempt is spent.
 */
sealed interface StepOutcome {
    data class Done(val output: StepOutput) : StepOutcome

    /**
     * Come back in [retryAfterSec] seconds. The job parks in `WAITING(next_run_at)`, the step stays
     * `PENDING` with its `attempts` untouched, and [state] — the submission ref and when it was
     * sent — is saved so the next pass polls the same job instead of re-submitting.
     */
    data class Waiting(val retryAfterSec: Int, val state: JsonObject) : StepOutcome
}

class StepContext(
    val job: Job,
    /**
     * The job's snapshot, decoded. Separate from [job] because [Job.workflow] is null for a
     * snapshot this build cannot read, and such a job never reaches a runner (docs/10 "잡 스냅샷").
     */
    val workflow: Workflow,
    /** The `step_run` row's ULID. The webhook sends it as `webhook-id` so a retry dedupes (docs/04). */
    val stepRunId: String,
    val step: Step,
    val recording: RecordingRecord,
    /** Outputs of the steps that already succeeded in this job, keyed by step id. */
    val prior: Map<String, StepOutput>,
    val state: JsonObject?,
    val saveState: suspend (JsonObject) -> Unit,
    /**
     * Part of the step's own output, written before it is finished. Unlike [saveState] it is not
     * dropped when the job parks, so it is where a fact the *recording* needs later goes — the
     * Drive folder id, which "Drive에서도 삭제" (docs/03) has to find even after a `NEEDS_SPACE`
     * park threw the resume state away. The final [StepOutput] replaces whatever it wrote.
     */
    val saveOutput: suspend (JsonObject) -> Unit,
    val deps: CoreDeps,
)

data class StepOutput(val json: JsonObject)

/**
 * The output of the last step of [type] among those that already succeeded — "last" in workflow
 * order, which is the one whose files are freshest. Null when the job ran none of them.
 */
internal fun Workflow.priorOutput(prior: Map<String, StepOutput>, type: String): JsonObject? =
    steps.lastOrNull { it.type == type && it.id in prior }?.let { prior.getValue(it.id).json }

/** [priorOutput] over the job the step is running in. */
internal fun StepContext.priorOutput(type: String): JsonObject? = workflow.priorOutput(prior, type)

/**
 * A step failure the executor knows how to route: [retryable] decides backoff versus `onError`,
 * [needsAuth] parks the whole job until the user signs in without spending a retry.
 *
 * [needsSpace] is the same kind of park for a full Drive (docs/10 "Drive 용량 초과"): nothing but
 * the user clearing space changes the answer, so no attempt is spent there either.
 *
 * [retryAfterSec] is a `Retry-After` the server sent (429, 503); it replaces the computed backoff,
 * capped by `retry.maxDelaySec` so a hostile or confused header cannot park a job for a month.
 */
class StepFailure(
    val retryable: Boolean,
    val reason: String,
    val needsAuth: Boolean = false,
    val retryAfterSec: Long? = null,
    val needsSpace: Boolean = false,
) : Exception(reason)
