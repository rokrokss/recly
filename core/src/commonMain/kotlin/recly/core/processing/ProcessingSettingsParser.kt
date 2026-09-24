package recly.core.processing

import io.ktor.http.Url
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import recly.core.model.Step
import recly.core.model.Language
import recly.core.transcribe.TranscriptionLanguages
import recly.core.model.Workflow
import recly.core.model.recJson
import recly.core.workflow.WorkflowParser

sealed interface ProcessingParseResult {
    data class Valid(val document: ProcessingSettingsDocument) : ProcessingParseResult
    data class UnsupportedSchema(val schema: Int) : ProcessingParseResult
    data class Invalid(val errors: List<String>) : ProcessingParseResult
}

/** A strict, independent format: unknown settings must never disappear during an import/save. */
object ProcessingSettingsParser {
    const val SCHEMA = 1
    internal val strictJson = Json(recJson) { ignoreUnknownKeys = false }

    fun parse(json: String): ProcessingParseResult {
        val root = try {
            strictJson.parseToJsonElement(json)
        } catch (_: SerializationException) {
            return ProcessingParseResult.Invalid(listOf("malformed settings JSON"))
        }
        val schema = ((root as? JsonObject)?.get("schema") as? JsonPrimitive)
            ?.takeUnless { it.isString }?.intOrNull
            ?: return ProcessingParseResult.Invalid(listOf("schema is missing or not an integer"))
        if (schema > SCHEMA) return ProcessingParseResult.UnsupportedSchema(schema)
        if (schema != SCHEMA) return ProcessingParseResult.Invalid(listOf("schema must be $SCHEMA"))
        if (hasNull(root)) return ProcessingParseResult.Invalid(listOf("null values are not allowed"))
        val document = try {
            strictJson.decodeFromString<ProcessingSettingsDocument>(json)
        } catch (_: SerializationException) {
            // Parser diagnostics can contain the user's JSON, including accidentally pasted keys.
            return ProcessingParseResult.Invalid(listOf("settings contain unknown or invalid fields"))
        }
        val errors = validate(document)
        return if (errors.isEmpty()) ProcessingParseResult.Valid(document) else ProcessingParseResult.Invalid(errors)
    }

    fun serialize(document: ProcessingSettingsDocument): String = strictJson.encodeToString(document)

    fun validate(document: ProcessingSettingsDocument): List<String> {
        val errors = mutableListOf<String>()
        if (document.schema != SCHEMA) errors += "schema must be $SCHEMA"
        if (document.revision < 0) errors += "revision must be >= 0, was ${document.revision}"
        if (document.updatedBy.isEmpty()) errors += "updatedBy must not be empty"
        val settings = document.settings
        if (WORKFLOW_VARIABLE.containsMatchIn(settings.storage.folder)) {
            errors += "folder cannot depend on workflowName or an absent recording title"
        }
        val transcription = settings.transcription
        if (transcription.mode == TranscriptionMode.EXTERNAL && transcription.external == null) {
            errors += "external mode requires external settings"
        }
        val external = transcription.external
        if (transcription.mode == TranscriptionMode.EXTERNAL && external != null &&
            transcription.language !in listOf(Language.KO, Language.EN, Language.KO_EN, Language.AUTO) &&
            transcription.language !in TranscriptionLanguages.supported(external.provider, external.model)) {
            errors += "the selected provider or model does not support this language"
        }
        if (external?.model != null && external.model.length !in 1..100) errors += "model must be 1..100 characters"
        external?.invokeUrl?.let { url ->
            val valid = runCatching {
                url.startsWith("https://") && url.none(Char::isWhitespace) && Url(url).host.isNotEmpty()
            }.getOrDefault(false)
            if (!valid) errors += "invokeUrl must be an absolute https URL"
        }
        // Reuse the plan's provider, language, speaker, template and timestamp rules. This synthetic
        // workflow is validation only; it is never stored or executed.
        errors += WorkflowParser.validate(Workflow(
            id = "00000000000000000000RECSET",
            name = "Recording",
            updatedAt = document.updatedAt,
            minDurationSec = settings.storage.minDurationSec,
            steps = listOf(
                Step.DriveUpload("upload", folder = settings.storage.folder),
                Step.Transcribe(
                    id = "transcribe",
                    provider = external?.provider ?: "assemblyai",
                    secretRef = external?.secretRef ?: "validation_only",
                    invokeUrl = external?.invokeUrl,
                    model = external?.model,
                    language = transcription.language,
                    diarize = transcription.diarize,
                    speakers = transcription.speakers,
                ),
            ),
        ))
        return errors
    }

    private fun hasNull(value: JsonElement): Boolean = when (value) {
        JsonNull -> true
        is JsonObject -> value.values.any(::hasNull)
        is JsonArray -> value.any(::hasNull)
        else -> false
    }

    /** Both variables resolve to the plan's name when a recording has no explicit title. */
    internal val WORKFLOW_VARIABLE = Regex("\\{\\{\\s*(workflowName|title)\\s*\\}\\}")
}
