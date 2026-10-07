@file:OptIn(ExperimentalSerializationApi::class)

package recly.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * What a job runs: the fixed processing plan ([recly.core.processing.ProcessingPlan]) compiled from
 * the recording's frozen settings, stored with the job as its snapshot (docs/10 "job snapshot").
 */
@Serializable
data class Workflow(
    val id: String,
    val name: String,
    val updatedAt: String,
    /** Shorter recordings never make a job — `SKIPPED_SHORT` instead (docs/06). */
    val minDurationSec: Int = 0,
    val steps: List<Step>,
)

@Serializable
data class Retry(
    val maxAttempts: Int = 8,
    val initialDelaySec: Int = 30,
    val maxDelaySec: Int = 3600,
)

@Serializable
@JsonClassDiscriminator("type")
sealed class Step {
    abstract val id: String
    abstract val onError: OnError
    abstract val retry: Retry

    @Serializable
    @SerialName("drive.upload")
    data class DriveUpload(
        override val id: String,
        override val onError: OnError = OnError.ABORT,
        override val retry: Retry = Retry(),
        val folder: String = "recly/{{yyyy}}/{{yyyy}}-{{MM}}",
        val includeMeta: Boolean = true,
        /**
         * docs/03 "Storage location": which storage the files go to — the type keeps its `drive.upload` wire
         * name for both, and a snapshot written before this field reads as Drive.
         */
        val store: recly.core.storage.StorageKind = recly.core.storage.StorageKind.DRIVE,
    ) : Step()

    /**
     * `transcribe` (docs/08). [provider] is a string, not an enum: an unknown one has to reach
     * validation as `UnknownProvider` instead of failing the decode as a malformed document.
     */
    @Serializable
    @SerialName("transcribe")
    data class Transcribe(
        override val id: String,
        override val onError: OnError = OnError.ABORT,
        override val retry: Retry = Retry(),
        val provider: String,
        val secretRef: String,
        /** `clova` only: the app-specific invoke URL. Any other provider rejects it. */
        val invokeUrl: String? = null,
        val language: Language = Language.KO,
        val diarize: Boolean = true,
        val speakers: Speakers = Speakers(),
        /** Free-form; the provider validates it, not the core. */
        val model: String? = null,
        /** Words and names to expect (docs/05 "Fixed processing settings"), handed to the provider's adapter. */
        val vocabulary: List<String> = emptyList(),
    ) : Step()

    /** On-device transcription (docs/08); the fixed plan's `local` mode. */
    @Serializable
    @SerialName("local.transcribe")
    data class LocalTranscribe(
        override val id: String,
        override val onError: OnError = OnError.ABORT,
        override val retry: Retry = Retry(),
        val language: Language = Language.KO,
        val diarize: Boolean = false,
        /** Words and names to expect, handed to the on-device engine (docs/05 "Fixed processing settings"). */
        val vocabulary: List<String> = emptyList(),
    ) : Step()

    /**
     * Publishes a durable local result. Retrying this step never invokes an ASR engine.
     *
     * [folderId] is where to: null means the folder the job's own `drive.upload` made; a
     * re-transcription, which uploads nothing, names the recording's folder here (docs/10 "Re-transcription").
     */
    @Serializable
    @SerialName("transcript.publish")
    data class TranscriptPublish(
        override val id: String,
        override val onError: OnError = OnError.ABORT,
        override val retry: Retry = Retry(),
        val folderId: String? = null,
    ) : Step()
}

/** Speaker-count hint. `context.participants` overrides both when the recording carries one. */
@Serializable
data class Speakers(val min: Int = 1, val max: Int = 10)

@Serializable
enum class Language {
    @SerialName("ko")
    KO,

    @SerialName("en")
    EN,

    @SerialName("ja")
    JA,

    @SerialName("zh-cn")
    ZH_CN,

    @SerialName("zh-tw")
    ZH_TW,

    @SerialName("es")
    ES,

    @SerialName("fr")
    FR,

    @SerialName("de")
    DE,

    @SerialName("pt")
    PT,

    @SerialName("ar")
    AR,

    @SerialName("hi")
    HI,

    @SerialName("ru")
    RU,

    @SerialName("it")
    IT,

    @SerialName("id")
    ID,

    @SerialName("tr")
    TR,

    @SerialName("vi")
    VI,

    @SerialName("th")
    TH,

    @SerialName("nl")
    NL,

    @SerialName("pl")
    PL,

    @SerialName("uk")
    UK,

    @SerialName("ko-en")
    KO_EN,

    @SerialName("auto")
    AUTO,
}

@Serializable
enum class Source {
    @SerialName("watch")
    WATCH,

    @SerialName("phone")
    PHONE,

    @SerialName("desktop")
    DESKTOP,

    /** A file the user brought in from outside, transcoded into parts on this device (docs/03 "Naming rules"). */
    @SerialName("import")
    IMPORT,
}

@Serializable
enum class Track {
    @SerialName("mono")
    MONO,

    @SerialName("mic")
    MIC,

    @SerialName("sys")
    SYS,

    @SerialName("mix")
    MIX,
}

@Serializable
enum class OnError {
    @SerialName("abort")
    ABORT,

    @SerialName("continue")
    CONTINUE,
}
