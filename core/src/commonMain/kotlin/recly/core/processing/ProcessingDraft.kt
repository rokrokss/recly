package recly.core.processing

import recly.core.model.Language
import recly.core.transcribe.Qwen3Asr
import recly.core.transcribe.SttProviders
import recly.core.transcribe.TranscriptionLanguages
import recly.core.workflow.InvokeUrlUse
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
    /** Every provider's entries as typed, this one's included once it is left (docs/05 "시크릿"). */
    private var details: Map<String, ProviderDetails> = emptyMap(),
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
                invokeUrl.takeIf { it.isNotEmpty() }, model.takeIf { it.isNotEmpty() }) else retainedExternal,
            // A value that would not save is not remembered either: it is the one being fixed, not
            // one to bring back.
            providerDetails = remembered().filter { (name, details) -> ProcessingSettingsParser.keeps(name, details) }),
    ).forNewRecordings()

    /**
     * Model names and addresses belong to one provider; carrying them over sends them to another. The
     * ones left behind are remembered, so coming back to that provider brings them back. The field
     * starts empty — the address's shape is the field's placeholder ([invokeUrlHint]), not a value
     * to edit character by character.
     */
    fun selectProvider(value: String) {
        if (value == provider) return
        details = remembered()
        provider = value
        invokeUrl = details[value]?.invokeUrl.orEmpty()
        model = details[value]?.model.orEmpty()
    }

    /** The address's shape for [provider] (`https://…/{appId}/{invokeKey}`), shown while the field is empty. */
    val invokeUrlHint: String? get() = WorkflowParser.invokeUrlTemplate(provider)

    private fun remembered(): Map<String, ProviderDetails> {
        val entry = ProviderDetails(
            invokeUrl.takeIf { it.isNotEmpty() && WorkflowParser.invokeUrlUse(provider) != InvokeUrlUse.NONE },
            model.takeIf { it.isNotEmpty() && acceptsModel },
        )
        return if (entry.invokeUrl == null && entry.model == null) details - provider else details + (provider to entry)
    }

    companion object {
        fun from(settings: ProcessingSettings): ProcessingDraft {
            val t = settings.transcription
            return ProcessingDraft(settings.storage.folder, settings.storage.minDurationSec.toString(), t.mode,
                t.language, t.external?.provider ?: "elevenlabs",
                t.external?.invokeUrl.orEmpty(), t.external?.model.orEmpty(), t.external, t.providerDetails)
        }
    }
}

/**
 * docs/05 "고정 처리 설정 도입": on Android and Windows on-device is Qwen3-ASR, whose list has no "Automatic" and
 * no "Korean and English" — both are an external provider's. Switching to it from either takes the
 * language of [deviceLocale] when the model has it, and English when it does not, rather than
 * leaving a Save that cannot be pressed. iPhone's `selectAppleTranscriptionMode`, the same rule; a
 * language the user chose explicitly stays, with the line that says the model lacks it.
 */
fun ProcessingDraft.selectTranscriptionMode(value: TranscriptionMode, deviceLocale: String) {
    mode = value
    if (value != TranscriptionMode.LOCAL) return
    if (language == Language.AUTO || language == Language.KO_EN) {
        language = TranscriptionLanguages.preferred(deviceLocale).takeIf { it in Qwen3Asr.languages } ?: Language.EN
    }
}
