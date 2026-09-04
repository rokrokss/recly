@file:OptIn(ExperimentalSerializationApi::class)

package recly.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
data class WorkflowsDocument(
    val schema: Int,
    val revision: Int,
    val updatedAt: String,
    val updatedBy: String,
    val workflows: List<Workflow>,
)

/**
 * The definition, and only the definition (ADR-016): which workflow a device runs is that device's
 * own local pointer, never a field of the shared document.
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
    ) : Step()

    @Serializable
    @SerialName("webhook")
    data class Webhook(
        override val id: String,
        override val onError: OnError = OnError.ABORT,
        override val retry: Retry = Retry(),
        val url: String,
        val secretRef: String? = null,
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
