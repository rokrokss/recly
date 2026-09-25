package recly.core.processing

import recly.core.model.Language
import recly.core.transcribe.SttProviders
import recly.core.transcribe.TranscriptionLanguages
import recly.core.workflow.WorkflowParser

/** UI-only input. Invalid/unfinished values never enter the persisted settings document. */
data class ProcessingDraft(
    var folder: String,
    var minimumSeconds: String,
    var mode: TranscriptionMode,
    var language: Language,
    var provider: String,
    var invokeUrl: String,
    var model: String,
    private val retainedExternal: ExternalTranscription?,
) {
    fun snapshot(): ProcessingDraft = copy()

    /**
     * docs/05 "시크릿": each provider's key is kept under the provider's own id, so switching provider
     * never sends one company's key to another and nobody has to name a secret.
     */
    val secretRef: String get() = provider

    val languages: List<Language> get() = if (mode == TranscriptionMode.EXTERNAL)
        TranscriptionLanguages.supported(provider, chosenModel) else TranscriptionLanguages.explicit

    /** Whether the model field means anything for [provider]; the shells hide it otherwise. */
    val acceptsModel: Boolean get() = SttProviders.acceptsModel(provider)

    private val chosenModel: String? get() = model.takeIf { it.isNotBlank() && acceptsModel }

    fun settings(): ProcessingSettings = ProcessingSettings(
        storage = ProcessingStorage(folder, minimumSeconds.trim().ifEmpty { "0" }.toIntOrNull() ?: -1),
        transcription = ProcessingTranscription(mode = mode, language = language,
            external = if (mode == TranscriptionMode.EXTERNAL) ExternalTranscription(provider, secretRef,
                invokeUrl.takeIf { it.isNotEmpty() }, model.takeIf { it.isNotEmpty() }) else retainedExternal),
    ).forNewRecordings()

    /** Model names and addresses belong to one provider; carrying them over sends them to another. */
    fun selectProvider(value: String) {
        if (value == provider) return
        provider = value
        model = ""
        invokeUrl = WorkflowParser.invokeUrlTemplate(value).orEmpty()
    }

    companion object {
        fun from(settings: ProcessingSettings): ProcessingDraft {
            val t = settings.transcription
            return ProcessingDraft(settings.storage.folder, settings.storage.minDurationSec.toString(), t.mode,
                t.language, t.external?.provider ?: "elevenlabs",
                t.external?.invokeUrl.orEmpty(), t.external?.model.orEmpty(), t.external)
        }
    }
}
