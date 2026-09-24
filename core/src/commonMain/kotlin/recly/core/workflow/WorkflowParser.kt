@file:OptIn(ExperimentalTime::class)

package recly.core.workflow

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import recly.core.ids.Ulid
import recly.core.model.Retry
import recly.core.model.Step
import recly.core.model.Workflow

/**
 * What a `transcribe` step's `invokeUrl` means for its provider (docs/08 provider table): the
 * address the provider only exists at, an override of a public default, or a field it never reads.
 */
enum class InvokeUrlUse { REQUIRED, OPTIONAL, NONE }

/**
 * The docs/02 rules for the one workflow this build runs — the fixed processing plan, validated
 * before its settings are stored ([recly.core.processing.ProcessingSettingsParser]) — and the
 * provider facts the settings screens share with it.
 */
object WorkflowParser {
    /**
     * docs/08 provider table, in the order the editor offers them — a list, because that order is
     * the contract and a set loses it on the way into Swift. A provider this build cannot run is
     * still a valid definition — `SttProviders` decides what this device can execute.
     */
    val STT_PROVIDERS: List<String> = listOf(
        "elevenlabs", "clova", "assemblyai", "rtzr",
        "openai", "groq", "together", "mistral",
        "deepgram", "azure",
        "daglo", "speechmatics", "rev", "gladia",
    )

    /** docs/08: the providers addressed by an app- or resource-specific URL, which the step must carry. */
    private val INVOKE_URL_REQUIRED = setOf("clova", "azure")

    /** docs/08: the providers with a public default endpoint that `invokeUrl` may replace. */
    private val INVOKE_URL_OPTIONAL = setOf("openai", "groq", "together", "mistral", "speechmatics")

    /** What `invokeUrl` means for [provider] — the one rule the parser and the three editors share. */
    fun invokeUrlUse(provider: String): InvokeUrlUse = when (provider) {
        in INVOKE_URL_REQUIRED -> InvokeUrlUse.REQUIRED
        in INVOKE_URL_OPTIONAL -> InvokeUrlUse.OPTIONAL
        else -> InvokeUrlUse.NONE
    }

    /**
     * docs/08: the shape a [InvokeUrlUse.REQUIRED] provider's URL takes, with the parts that are
     * the user's own in braces (verified against both API references on 2026-09-03: CLOVA's console
     * hands out `{appId}` as a number and `{invokeKey}` as 64 hex characters in one opaque "Invoke
     * URL"; Azure's "Keys and Endpoint" page shows the custom-subdomain form, and the regional
     * `https://{region}.api.cognitive.microsoft.com` is accepted interchangeably). The three editors
     * put it into an empty field when the provider is picked, so the user edits a URL instead of
     * composing one; the parser refuses a URL still carrying a brace, so a template left as it was
     * is caught here and not by the provider.
     */
    fun invokeUrlTemplate(provider: String): String? = when (provider) {
        "clova" -> "https://clovaspeech-gw.ncloud.com/external/v1/{appId}/{invokeKey}"
        "azure" -> "https://{resourceName}.cognitiveservices.azure.com"
        else -> null
    }

    const val INVOKE_URL_PLACEHOLDER = "InvokeUrlPlaceholder"

    /** Validation error token the UI branches on (docs/02 "검증 규칙"). */
    const val UNKNOWN_PROVIDER = "UnknownProvider"

    private const val MAX_STEPS = 10
    private const val MAX_NAME = 40
    private const val MAX_FOLDER = 200
    private const val MAX_SPEAKERS = 10
    private val STEP_ID = Regex("^[a-z][a-z0-9_]{0,31}$")
    private val RFC3339 = Regex("^\\d{4}-\\d{2}-\\d{2}[Tt]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?([Zz]|[+-]\\d{2}:\\d{2})$")
    private val TEMPLATE_VAR = Regex("\\{\\{([^}]*)\\}\\}")
    private val TEMPLATE_VARS = setOf(
        "yyyy", "MM", "dd", "HH", "mm", "title", "source", "recordingId", "workflowName", "device",
    )

    /** Every docs/02 rule [workflow] breaks, as the sentences the settings screens show. */
    fun validate(workflow: Workflow): List<String> {
        val errors = mutableListOf<String>()
        val where = "workflow ${workflow.id}"
        if (!Ulid.isValid(workflow.id)) errors += "$where: id is not a ULID"
        if (workflow.name.isEmpty() || workflow.name.length > MAX_NAME) {
            errors += "$where: name must be 1..$MAX_NAME characters, was ${workflow.name.length}"
        }
        if (!isTimestamp(workflow.updatedAt)) {
            errors += "$where: updatedAt is not an ISO-8601 instant: '${workflow.updatedAt}'"
        }
        if (workflow.minDurationSec < 0) {
            errors += "$where: minDurationSec must be >= 0, was ${workflow.minDurationSec}"
        }
        if (workflow.steps.isEmpty() || workflow.steps.size > MAX_STEPS) {
            errors += "$where: steps must be 1..$MAX_STEPS, was ${workflow.steps.size}"
        }
        val seen = mutableSetOf<String>()
        workflow.steps.forEach { step ->
            if (!STEP_ID.matches(step.id)) errors += "$where: step id '${step.id}' does not match $STEP_ID"
            if (!seen.add(step.id)) errors += "$where: duplicate step id '${step.id}'"
            validateRetry(step.retry, "$where: step '${step.id}'", errors)
            validateStep(step, where, errors)
        }
        return errors
    }

    private fun validateRetry(retry: Retry, where: String, errors: MutableList<String>) {
        if (retry.maxAttempts !in 1..20) {
            errors += "$where: retry.maxAttempts must be 1..20, was ${retry.maxAttempts}"
        }
        if (retry.initialDelaySec < 1) {
            errors += "$where: retry.initialDelaySec must be >= 1, was ${retry.initialDelaySec}"
        }
        if (retry.maxDelaySec < 1) {
            errors += "$where: retry.maxDelaySec must be >= 1, was ${retry.maxDelaySec}"
        }
    }

    private fun validateStep(step: Step, where: String, errors: MutableList<String>) {
        when (step) {
            is Step.LocalTranscribe, is Step.TranscriptPublish -> Unit
            is Step.DriveUpload -> {
                if (step.folder.isEmpty() || step.folder.length > MAX_FOLDER) {
                    errors += "$where: step '${step.id}' folder must be 1..$MAX_FOLDER characters, " +
                        "was ${step.folder.length}"
                }
                TEMPLATE_VAR.findAll(step.folder)
                    .map { it.groupValues[1].trim() }
                    .filterNot { it in TEMPLATE_VARS }
                    .forEach { errors += "$where: step '${step.id}' uses unknown template variable '{{$it}}'" }
            }

            is Step.Transcribe -> {
                val at = "$where: step '${step.id}'"
                if (step.provider !in STT_PROVIDERS) {
                    errors += "$at $UNKNOWN_PROVIDER: '${step.provider}' is not one of $STT_PROVIDERS"
                }
                validateSecretRef(step.secretRef, at, errors)
                // The invoke URL is an addressing scheme, not a general field: on a provider that
                // never reads it, allowing it would silently do nothing.
                when (invokeUrlUse(step.provider)) {
                    InvokeUrlUse.REQUIRED -> if (step.invokeUrl == null) {
                        errors += "$at requires invokeUrl for provider '${step.provider}'"
                    } else if ('{' in step.invokeUrl || '}' in step.invokeUrl) {
                        errors += "$at $INVOKE_URL_PLACEHOLDER: invokeUrl still has a {placeholder} to replace"
                    }
                    InvokeUrlUse.OPTIONAL -> Unit
                    InvokeUrlUse.NONE -> if (step.invokeUrl != null) {
                        errors += "$at invokeUrl is not allowed for provider '${step.provider}'"
                    }
                }
                if (step.speakers.min !in 1..MAX_SPEAKERS || step.speakers.max !in 1..MAX_SPEAKERS) {
                    errors += "$at speakers must be 1..$MAX_SPEAKERS, was ${step.speakers}"
                }
                if (step.speakers.min > step.speakers.max) {
                    errors += "$at speakers.min must be <= speakers.max, was ${step.speakers}"
                }
                if (step.model != null && step.model.isEmpty()) errors += "$at model must not be empty"
            }
        }
    }

    private fun validateSecretRef(ref: String, at: String, errors: MutableList<String>) {
        if (!STEP_ID.matches(ref)) errors += "$at secretRef '$ref' does not match $STEP_ID"
    }

    /** RFC 3339 lexical form first: `Instant.parse` also accepts ISO-8601 forms the schema rejects. */
    private fun isTimestamp(value: String): Boolean =
        RFC3339.matches(value) &&
            try {
                Instant.parse(value)
                true
            } catch (_: IllegalArgumentException) {
                false
            }
}
