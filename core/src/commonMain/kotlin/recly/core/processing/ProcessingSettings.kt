package recly.core.processing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import recly.core.model.Language
import recly.core.model.Speakers
import recly.core.transcribe.SttProviders

/** Device-local processing preferences. Secrets are references, never exported values. */
@Serializable
data class ProcessingSettingsDocument(
    val schema: Int = 1,
    val revision: Int,
    val updatedAt: String,
    val updatedBy: String,
    val settings: ProcessingSettings,
)

@Serializable
data class ProcessingSettings(
    val storage: ProcessingStorage = ProcessingStorage(),
    val transcription: ProcessingTranscription = ProcessingTranscription(),
) {
    /** Applied to current preferences and new plans, never to an already queued workflow. */
    internal fun forNewRecordings(): ProcessingSettings = copy(
        transcription = transcription.copy(
            diarize = when (transcription.mode) {
                TranscriptionMode.LOCAL -> true // The native runtime resolves its capability.
                TranscriptionMode.EXTERNAL -> transcription.external?.let {
                    SttProviders.supportsDiarization(it.provider, it.model)
                } == true
                TranscriptionMode.OFF -> false
            },
            speakers = Speakers(),
        ),
    )

    companion object { fun defaults(): ProcessingSettings = ProcessingSettings() }
}

@Serializable
data class ProcessingStorage(
    val folder: String = "recly/memo/{{yyyy}}-{{MM}}",
    val minDurationSec: Int = 0,
)

@Serializable
enum class TranscriptionMode {
    @SerialName("local") LOCAL,
    @SerialName("external") EXTERNAL,
    @SerialName("off") OFF,
}

@Serializable
data class ProcessingTranscription(
    val mode: TranscriptionMode = TranscriptionMode.LOCAL,
    val language: Language = Language.KO,
    /** Compatibility field; current preferences derive this from the engine/provider capability. */
    val diarize: Boolean = false,
    val speakers: Speakers = Speakers(),
    /** Retained while another mode is selected, so switching off does not discard configuration. */
    val external: ExternalTranscription? = null,
)

@Serializable
data class ExternalTranscription(
    val provider: String,
    val secretRef: String,
    val invokeUrl: String? = null,
    val model: String? = null,
)
