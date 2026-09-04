@file:OptIn(ExperimentalTime::class)

package app.recly.windows.workflow

import app.recly.windows.core.isoUtc
import app.recly.windows.i18n.Str
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import recly.core.model.Language
import recly.core.model.OnError
import recly.core.model.Retry
import recly.core.model.Speakers
import recly.core.model.Step
import recly.core.model.Workflow
import recly.core.model.WorkflowsDocument
import recly.core.workflow.InvokeUrlUse
import recly.core.workflow.WorkflowParser

/**
 * One workflow as the editor holds it: the docs/02 shape with every number kept as the text the
 * user typed, so a half-written field is not silently rounded into something valid. The mapping
 * back turns unparseable text into a value the parser is guaranteed to reject ([INVALID]) — the
 * editor's only judge of what is valid is `save()`, which is the parser (docs/02 "검증 규칙").
 *
 * The phone (`android/app/.../workflow/WorkflowEdit.kt`) and the Mac (`WorkflowWindow.swift`) each
 * carry this same shape, because each of the three editors binds it to a different UI toolkit. Only
 * the rules that decide what is *written* — the mutex, staleness, the freeze — are shared code
 * (`recly.core.workflow.WorkflowMutator`).
 */
data class WorkflowEdit(
    val id: String,
    val name: String,
    val minDurationSec: String,
    val steps: List<StepEdit>,
)

data class RetryEdit(
    val maxAttempts: String = Retry().maxAttempts.toString(),
    val initialDelaySec: String = Retry().initialDelaySec.toString(),
    val maxDelaySec: String = Retry().maxDelaySec.toString(),
)

/** The docs/02 defaults for `drive.upload`, read off the model rather than repeated here. */
private val DRIVE = Step.DriveUpload(id = "upload")

sealed interface StepEdit {
    val id: String
    val onError: OnError
    val retry: RetryEdit

    fun withCommon(onError: OnError = this.onError, retry: RetryEdit = this.retry): StepEdit

    data class Drive(
        override val id: String,
        override val onError: OnError = OnError.ABORT,
        override val retry: RetryEdit = RetryEdit(),
        val folder: String = DRIVE.folder,
        val includeMeta: Boolean = DRIVE.includeMeta,
    ) : StepEdit {
        override fun withCommon(onError: OnError, retry: RetryEdit) = copy(onError = onError, retry = retry)
    }

    data class Hook(
        override val id: String,
        override val onError: OnError = OnError.ABORT,
        override val retry: RetryEdit = RetryEdit(),
        val url: String = "",
        val secretRef: String? = null,
    ) : StepEdit {
        override fun withCommon(onError: OnError, retry: RetryEdit) = copy(onError = onError, retry = retry)
    }

    /**
     * `transcribe` (docs/08). [invokeUrl] and [model] are empty rather than null while being
     * edited, and become null again on the way out — a field the user cleared is a field the
     * document does not carry.
     *
     * [model] has no form of its own (docs/08 leaves the STT model to the provider's default), but
     * a definition written elsewhere may set one, so it is carried through rather than dropped.
     */
    data class Transcribe(
        override val id: String,
        override val onError: OnError = OnError.ABORT,
        override val retry: RetryEdit = RetryEdit(),
        val provider: String = DEFAULT_STT_PROVIDER,
        val secretRef: String = "",
        val invokeUrl: String = "",
        val language: Language = TRANSCRIBE.language,
        val diarize: Boolean = TRANSCRIBE.diarize,
        val speakersMin: String = TRANSCRIBE.speakers.min.toString(),
        val speakersMax: String = TRANSCRIBE.speakers.max.toString(),
        val model: String = "",
    ) : StepEdit {
        override fun withCommon(onError: OnError, retry: RetryEdit) = copy(onError = onError, retry = retry)
    }

}

/** The docs/08 defaults for `transcribe`, read off the model rather than repeated here. */
private val TRANSCRIBE = Step.Transcribe(id = "stt", provider = "", secretRef = "")

/**
 * What a step the user has just added starts as — the provider table's first row (docs/08).
 */
const val DEFAULT_STT_PROVIDER: String = "assemblyai"

/** What the editor's "add a step" menu offers — one per docs/02·docs/08 step type. */
enum class StepKind { DRIVE, HOOK, TRANSCRIBE }

/**
 * Whether the editor offers this kind on top of [steps]. A second `drive.upload` has nothing to do:
 * the same folder is a no-op, a different one a copy the later steps never see (only the last
 * upload's folder gets the transcript and the webhook payload). Webhooks may repeat — each is
 * another endpoint.
 */
fun StepKind.canAdd(steps: List<StepEdit>): Boolean =
    this != StepKind.DRIVE || steps.none { it is StepEdit.Drive }

/** A step the user has just asked for, with the defaults docs/08 says to start from. */
fun StepKind.newStep(taken: Set<String>): StepEdit = when (this) {
    StepKind.DRIVE -> StepEdit.Drive(id = nextStepId("upload", taken))
    StepKind.HOOK -> StepEdit.Hook(id = nextStepId("hook", taken))
    StepKind.TRANSCRIBE -> StepEdit.Transcribe(id = nextStepId("stt", taken))
}

/** The `secretRef`s a workflow needs, whichever kind of step asked for them (docs/05 "새 기기"). */
fun Workflow.secretRefs(): List<String> = steps.mapNotNull {
    when (it) {
        is Step.Webhook -> it.secretRef
        is Step.Transcribe -> it.secretRef
        is Step.DriveUpload -> null
    }
}.distinct()

/**
 * An optional string on the way out: only a field the user actually cleared becomes null. What they
 * did not touch goes back exactly as it came in — trimming it here would rewrite a step nobody
 * edited, and would turn a whitespace-only value the parser accepts into an absent one.
 */
private fun String.orNull(): String? = takeIf { it.isNotEmpty() }

/** Not a number the schema can hold, so "" and "3 " and "abc" all come back as one validation error. */
private const val INVALID = -1

private fun String.asInt(blank: Int): Int = if (isBlank()) blank else trim().toIntOrNull() ?: INVALID

fun Workflow.toEdit(): WorkflowEdit = WorkflowEdit(
    id = id,
    name = name,
    minDurationSec = minDurationSec.toString(),
    steps = steps.map { it.toEdit() },
)

fun Step.toEdit(): StepEdit = when (this) {
    is Step.DriveUpload -> StepEdit.Drive(id, onError, retry.toEdit(), folder, includeMeta)
    is Step.Webhook -> StepEdit.Hook(id, onError, retry.toEdit(), url, secretRef)
    is Step.Transcribe -> StepEdit.Transcribe(
        id = id,
        onError = onError,
        retry = retry.toEdit(),
        provider = provider,
        secretRef = secretRef,
        invokeUrl = invokeUrl.orEmpty(),
        language = language,
        diarize = diarize,
        speakersMin = speakers.min.toString(),
        speakersMax = speakers.max.toString(),
        model = model.orEmpty(),
    )
}

/** The docs/02 spelling. `Language.wire` is the core's own and is internal to it. */
fun Language.tag(): String = name.lowercase().replace('_', '-')

private fun Retry.toEdit() = RetryEdit(
    maxAttempts = maxAttempts.toString(),
    initialDelaySec = initialDelaySec.toString(),
    maxDelaySec = maxDelaySec.toString(),
)

fun WorkflowEdit.toWorkflow(updatedAt: String): Workflow = Workflow(
    id = id,
    name = name.trim(),
    updatedAt = updatedAt,
    minDurationSec = minDurationSec.asInt(blank = 0),
    steps = steps.map { it.toStep() },
)

fun StepEdit.toStep(): Step = when (this) {
    is StepEdit.Drive -> Step.DriveUpload(
        id = id,
        onError = onError,
        retry = retry.toRetry(),
        folder = folder.trim(),
        includeMeta = includeMeta,
    )

    is StepEdit.Hook -> Step.Webhook(
        id = id,
        onError = onError,
        retry = retry.toRetry(),
        url = url.trim(),
        secretRef = secretRef?.takeIf { it.isNotBlank() },
    )

    is StepEdit.Transcribe -> Step.Transcribe(
        id = id,
        onError = onError,
        retry = retry.toRetry(),
        provider = provider,
        secretRef = secretRef.trim(),
        // docs/08: a provider either is addressed by an `invokeUrl`, may be, or never reads one —
        // and the form hides the field for the last kind, so carrying a leftover URL out would fail
        // validation for a field nobody can see.
        invokeUrl = invokeUrl.orNull()
            ?.takeIf { WorkflowParser.invokeUrlUse(provider) != InvokeUrlUse.NONE },
        language = language,
        diarize = diarize,
        speakers = Speakers(
            min = speakersMin.asInt(blank = INVALID),
            max = speakersMax.asInt(blank = INVALID),
        ),
        model = model.orNull(),
    )
}

private fun RetryEdit.toRetry() = Retry(
    maxAttempts = maxAttempts.asInt(blank = INVALID),
    initialDelaySec = initialDelaySec.asInt(blank = INVALID),
    maxDelaySec = maxDelaySec.asInt(blank = INVALID),
)

/**
 * The edited workflow, and only it, goes back into the document with a fresh `updatedAt` — every
 * other workflow keeps the timestamp it had, because docs/05 merges per workflow and restamping an
 * untouched one would let this device win a race it never entered.
 */
fun WorkflowsDocument.with(edit: WorkflowEdit, now: Instant): WorkflowsDocument {
    val edited = edit.toWorkflow(now.isoUtc())
    return copy(
        workflows = if (workflows.none { it.id == edited.id }) {
            workflows + edited
        } else {
            workflows.map { if (it.id == edited.id) edited else it }
        },
    )
}

/** docs/05: v1 has no tombstone, so a deletion is simply an absence. */
fun WorkflowsDocument.without(id: String): WorkflowsDocument =
    copy(workflows = workflows.filterNot { it.id == id })

/**
 * Step ids are docs/02 identity, not decoration: the job's `step_run` rows are keyed by them, so
 * the editor mints one that matches `^[a-z][a-z0-9_]{0,31}$` and never lets it change afterwards.
 */
fun nextStepId(base: String, taken: Set<String>): String {
    if (base !in taken) return base
    var n = 2
    while ("$base$n" in taken) n++
    return "$base$n"
}

fun Step.label(): Str = when (this) {
    is Step.DriveUpload -> Str.LABEL_DRIVE
    is Step.Webhook -> Str.LABEL_WEBHOOK
    is Step.Transcribe -> Str.LABEL_TRANSCRIBE
}

fun StepEdit.label(): Str = when (this) {
    is StepEdit.Drive -> Str.LABEL_DRIVE_UPLOAD
    is StepEdit.Hook -> Str.LABEL_WEBHOOK
    is StepEdit.Transcribe -> Str.LABEL_TRANSCRIBE
}
