@file:OptIn(ExperimentalTime::class)

package recly.core.workflow

import io.ktor.http.Url
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import recly.core.ids.Ulid
import recly.core.model.Retry
import recly.core.model.Step
import recly.core.model.Workflow
import recly.core.model.WorkflowsDocument
import recly.core.model.recJson

sealed interface ParseResult {
    /**
     * [document] is always at [WorkflowParser.SCHEMA]. [migratedFrom] is the older schema it was
     * written with, when it was written with one: the migration happened in memory here, and the
     * stored bytes are still the old ones until something saves over them (docs/05 §스키마
     * "Outdated").
     */
    data class Ok(val document: WorkflowsDocument, val migratedFrom: Int? = null) : ParseResult

    /** The document is well-formed but written by a newer spec: read-only, never overwrite. */
    data class UnsupportedSchema(val schema: Int) : ParseResult

    /**
     * An older document carrying [fields] this build has no model for: valid, readable, and not
     * migrated. Migrating it means writing it back at [WorkflowParser.SCHEMA], and every write is a
     * re-serialization of the typed models — which cannot carry a field they cannot name. So the
     * migration would store a copy with these fields silently deleted, and the document is refused
     * the same way one from a newer spec is (docs/05 §스키마): an import says so and a stored copy
     * is left alone, until a build that knows the fields rewrites it.
     *
     * The fields a schema is *known* to have dropped are not this case — see
     * [WorkflowParser.LEGACY_WORKFLOW_FIELDS].
     */
    data class MigrationBlocked(val schema: Int, val fields: List<String>) : ParseResult

    data class Invalid(val errors: List<String>) : ParseResult
}

/**
 * What a `transcribe` step's `invokeUrl` means for its provider (docs/08 provider table): the
 * address the provider only exists at, an override of a public default, or a field it never reads.
 */
enum class InvokeUrlUse { REQUIRED, OPTIONAL, NONE }

object WorkflowParser {
    /** ADR-021: `transcribe` made this 2. ADR-016: the device-local default made it 3. */
    const val SCHEMA = 3

    /**
     * The oldest schema still readable. docs/05 §스키마 splits the two directions: only a *newer*
     * document freezes writing, an older one is migrated and written back at [SCHEMA].
     *
     * Schema 1 is schema 2 without `transcribe` (ADR-021, commit 220a447) — every rule the schema-2
     * parser applies to the rest is a rule schema 1 had too, so reading one with the current rules
     * is reading it with its own. Schema 2 is schema 3 plus [LEGACY_WORKFLOW_FIELDS].
     */
    const val MIN_SCHEMA = 1

    /**
     * What a schema 1..2 workflow carried and a schema 3 one does not (ADR-016): `enabled` and
     * `isDefault` are gone with the source-based selection rules, and `trigger` is gone with them —
     * its `minDurationSec` moved up to the workflow, its `sources` mean nothing any more.
     *
     * Dropping them is the migration, not a reason to refuse it: unlike a field this build has
     * never heard of ([ParseResult.MigrationBlocked]), these are fields it knows it is dropping.
     */
    private val LEGACY_WORKFLOW_FIELDS = setOf("enabled", "isDefault", "trigger")

    private val LEGACY_FIELD_PATH = Regex("^workflows\\[\\d+]\\.(${LEGACY_WORKFLOW_FIELDS.joinToString("|")})$")

    /** What a legacy `trigger` was allowed to hold — anything else inside one still blocks. */
    private val LEGACY_TRIGGER_FIELDS = setOf("sources", "minDurationSec")

    /**
     * `drive.upload` used to take a `tracks` list; every track the recording has goes up now, so
     * a legacy step's list is dropped the way [LEGACY_WORKFLOW_FIELDS] are.
     */
    private val LEGACY_STEP_FIELD_PATH = Regex("^workflows\\[\\d+]\\.steps\\[\\d+]\\.tracks$")

    /**
     * docs/08 provider table, in the order the editor offers them — a list, because that order is
     * the contract and a set loses it on the way into Swift. A provider this build cannot run is
     * still a valid definition — `SttProviders` decides what this device can execute.
     */
    val STT_PROVIDERS: List<String> = listOf(
        "assemblyai", "clova", "rtzr",
        "openai", "groq", "together", "mistral",
        "elevenlabs", "deepgram", "azure",
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

    /** Validation error tokens the UI branches on (docs/02 "검증 규칙"). */
    const val UNKNOWN_PROVIDER = "UnknownProvider"
    const val TRANSCRIBE_NEEDS_UPLOAD = "TranscribeNeedsUpload"

    private const val MAX_WORKFLOWS = 50
    private const val MAX_STEPS = 10
    private const val MAX_NAME = 40
    private const val MAX_FOLDER = 200
    private const val MAX_SPEAKERS = 10
    private val STEP_ID = Regex("^[a-z][a-z0-9_]{0,31}$")
    private val WEBHOOK_URL_TEXT = Regex("^https?://[A-Za-z0-9._~:/?#\\[\\]@!\$&'()*+,;=%-]+$")
    private val BAD_PERCENT_ESCAPE = Regex("%(?![0-9A-Fa-f]{2})")
    private val RFC3339 = Regex("^\\d{4}-\\d{2}-\\d{2}[Tt]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?([Zz]|[+-]\\d{2}:\\d{2})$")
    private val TEMPLATE_VAR = Regex("\\{\\{([^}]*)\\}\\}")
    private val TEMPLATE_VARS = setOf(
        "yyyy", "MM", "dd", "HH", "mm", "title", "source", "recordingId", "workflowName", "device",
    )
    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1")

    /**
     * Reads the root `schema` before decoding, so a document from a future spec is reported as
     * [ParseResult.UnsupportedSchema] rather than as validation errors about fields this version
     * does not know.
     *
     * A document from an *older* spec is not that case: it is read with the rules of [SCHEMA]
     * (see [MIN_SCHEMA]) and comes back as [ParseResult.Ok] stamped at [SCHEMA], with
     * [ParseResult.Ok.migratedFrom] saying where it came from — [LEGACY_WORKFLOW_FIELDS] dropped
     * on the way, anything else it carries and this build cannot name refused instead.
     */
    fun parse(json: String): ParseResult {
        val root = try {
            recJson.parseToJsonElement(json)
        } catch (e: SerializationException) {
            return ParseResult.Invalid(listOf("malformed JSON: ${e.message}"))
        }
        val schema = (root as? JsonObject)?.get("schema")
            ?.let { it as? JsonPrimitive }
            ?.takeIf { !it.isString }
            ?.intOrNull
            ?: return ParseResult.Invalid(listOf("schema is missing or not an integer"))
        if (schema > SCHEMA) return ParseResult.UnsupportedSchema(schema)
        if (schema < MIN_SCHEMA) {
            return ParseResult.Invalid(listOf("schema must be $MIN_SCHEMA..$SCHEMA, was $schema"))
        }
        if (containsNull(root)) return ParseResult.Invalid(listOf("null values are not allowed"))

        val decoded = try {
            recJson.decodeFromString<WorkflowsDocument>(json)
        } catch (e: SerializationException) {
            return ParseResult.Invalid(listOf("malformed document: ${e.message}"))
        }
        val document = if (schema < SCHEMA) liftLegacyTriggers(decoded, root) else decoded
        val errors = validate(document)
        if (errors.isNotEmpty()) return ParseResult.Invalid(errors)
        if (schema == SCHEMA) return ParseResult.Ok(document)
        // A document with fields we would drop cannot be migrated: the write that follows the
        // migration is the typed document and nothing else. Except the ones this build drops on
        // purpose — those it can name, so it is not deleting anything it does not understand.
        val dropped = unknownFields(root, recJson.encodeToJsonElement(document), "")
            .filterNot { LEGACY_STEP_FIELD_PATH.matches(it) }
            .flatMap { path -> if (LEGACY_FIELD_PATH.matches(path)) legacyLeftovers(path, root) else listOf(path) }
        if (dropped.isNotEmpty()) return ParseResult.MigrationBlocked(schema, dropped)
        return ParseResult.Ok(document.copy(schema = SCHEMA), migratedFrom = schema)
    }

    /**
     * A legacy field is dropped whole only when every member of it is one this build knows it is
     * dropping: `enabled` and `isDefault` are primitives, but an unknown member riding inside
     * `trigger` (anything besides `sources`/`minDurationSec`) is still data this build cannot name,
     * and deleting it would be exactly what [ParseResult.MigrationBlocked] exists to prevent.
     */
    private fun legacyLeftovers(path: String, root: JsonElement): List<String> {
        if (!path.endsWith(".trigger")) return emptyList()
        val index = path.removePrefix("workflows[").substringBefore(']').toIntOrNull() ?: return listOf(path)
        val trigger = ((root as? JsonObject)?.get("workflows") as? JsonArray)
            ?.let { it.getOrNull(index) as? JsonObject }
            ?.let { it["trigger"] as? JsonObject }
            ?: return listOf(path)
        return trigger.keys.filterNot { it in LEGACY_TRIGGER_FIELDS }.map { "$path.$it" }
    }

    /**
     * The one part of a schema 1..2 `trigger` that survives into schema 3: `minDurationSec` moves
     * up to the workflow, where the decode of a schema-3 model could not see it. Everything else
     * the legacy shape carried is dropped by [LEGACY_WORKFLOW_FIELDS].
     */
    private fun liftLegacyTriggers(document: WorkflowsDocument, root: JsonElement): WorkflowsDocument {
        val raw = (root as? JsonObject)?.get("workflows") as? JsonArray ?: return document
        return document.copy(
            workflows = document.workflows.mapIndexed { index, workflow ->
                val legacy = (raw.getOrNull(index) as? JsonObject)
                    ?.let { it["trigger"] as? JsonObject }
                    ?.let { it["minDurationSec"] as? JsonPrimitive }
                    ?.takeIf { !it.isString }
                    ?.intOrNull
                if (legacy == null) workflow else workflow.copy(minDurationSec = legacy)
            },
        )
    }

    /**
     * The fields [original] has and [encoded] — the same document as this build would write it —
     * does not: exactly what the typed models dropped on the way in. Comparing the two trees rather
     * than a list of known keys keeps this true for every level and every step type on its own.
     *
     * `encodeDefaults` writes back every field the models know, even one left out of the input, and
     * an explicit `null` is already rejected above — so nothing but an unknown field can be missing
     * from [encoded].
     */
    private fun unknownFields(original: JsonElement, encoded: JsonElement, at: String): List<String> = when {
        original is JsonObject && encoded is JsonObject ->
            original.keys.filterNot { it in encoded }.map { path(at, it) } +
                original.entries.flatMap { (key, value) ->
                    encoded[key]?.let { unknownFields(value, it, path(at, key)) }.orEmpty()
                }

        original is JsonArray && encoded is JsonArray ->
            original.indices.take(encoded.size).flatMap { unknownFields(original[it], encoded[it], "$at[$it]") }

        else -> emptyList()
    }

    private fun path(at: String, key: String): String = if (at.isEmpty()) key else "$at.$key"

    fun serialize(doc: WorkflowsDocument): String = recJson.encodeToString(doc)

    private fun validate(doc: WorkflowsDocument): List<String> {
        val errors = mutableListOf<String>()
        if (doc.revision < 0) errors += "revision must be >= 0, was ${doc.revision}"
        if (!isTimestamp(doc.updatedAt)) errors += "updatedAt is not an ISO-8601 instant: '${doc.updatedAt}'"
        if (doc.updatedBy.isEmpty()) errors += "updatedBy must not be empty"
        if (doc.workflows.size > MAX_WORKFLOWS) {
            errors += "workflows: at most $MAX_WORKFLOWS, was ${doc.workflows.size}"
        }
        doc.workflows.forEach { validateWorkflow(it, errors) }
        return errors
    }

    private fun validateWorkflow(workflow: Workflow, errors: MutableList<String>) {
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
        errors += orderErrors(workflow)
    }

    /**
     * docs/08 order constraint: a `transcribe` writes into the folder an earlier `drive.upload`
     * made.
     *
     * Public and on its own because an editor has to say so *before* it saves (M7-L3): moving a
     * step is the one edit that breaks a workflow without any field being wrong, and the sentences
     * are the parser's own rather than a second copy of the rule in three shells.
     */
    fun orderErrors(workflow: Workflow): List<String> {
        val where = "workflow ${workflow.id}"
        val errors = mutableListOf<String>()
        var uploaded = false
        workflow.steps.forEach { step ->
            when (step) {
                is Step.DriveUpload -> uploaded = true
                is Step.Transcribe ->
                    if (!uploaded) {
                        errors += "$where: step '${step.id}' $TRANSCRIBE_NEEDS_UPLOAD: " +
                            "a 'drive.upload' step must come before a 'transcribe' step"
                    }

                is Step.Webhook -> Unit
            }
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

            is Step.Webhook -> {
                if (!isAllowedWebhookUrl(step.url)) {
                    errors += "$where: step '${step.id}' url must be https, or http on localhost/127.0.0.1"
                }
                if (step.secretRef != null && !STEP_ID.matches(step.secretRef)) {
                    errors += "$where: step '${step.id}' secretRef '${step.secretRef}' does not match $STEP_ID"
                }
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

    /** The schema has no nullable property, so an explicit `null` anywhere is a schema violation. */
    private fun containsNull(element: JsonElement): Boolean = when (element) {
        is JsonNull -> true
        is JsonObject -> element.values.any(::containsNull)
        is JsonArray -> element.any(::containsNull)
        else -> false
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

    /**
     * Two layers, both needed.
     *
     * The raw-text pass pins what the spec's own pattern and `format: uri` pin — a lowercase scheme,
     * RFC 3986 characters only, well-formed percent escapes — before any parser gets to normalise
     * the string. The parsed pass then catches
     * what a prefix match cannot: `http://localhost:80@evil.example/` and
     * `http://127.0.0.1.evil.example/` both start with an allowed prefix but resolve elsewhere.
     *
     * The scheme guard is load-bearing, not redundant: Ktor's [Url] never rejects input — it
     * resolves `""` and `"not a url"` to `http://localhost`, which would otherwise pass the
     * loopback rule.
     */
    private fun isAllowedWebhookUrl(raw: String): Boolean {
        if (!WEBHOOK_URL_TEXT.matches(raw)) return false
        if (BAD_PERCENT_ESCAPE.containsMatchIn(raw)) return false
        val url = try {
            Url(raw)
        } catch (_: Exception) {
            return false
        }
        if (!raw.startsWith("${url.protocol.name}://")) return false
        return when (url.protocol.name) {
            "https" -> true
            "http" -> url.user == null && url.password == null && url.host in LOOPBACK_HOSTS
            else -> false
        }
    }
}
