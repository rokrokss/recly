package recly.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RecordingMeta(
    val schema: Int,
    val recordingId: String,
    val source: Source,
    val platform: Platform,
    val deviceId: String,
    val deviceName: String,
    val workflowId: String? = null,
    val title: String? = null,
    val startedAt: String,
    val endedAt: String? = null,
    val durationSec: Double? = null,
    val timezone: String,
    val audio: AudioSettings,
    val tracks: List<Track>,
    val parts: List<Part>,
    val gaps: List<Range> = emptyList(),
    val silenced: List<Range> = emptyList(),
    val context: Context? = null,
    /** Where the recording went in the user's Drive; written by `drive.upload` once the folder is known (docs/03 "메타데이터"). */
    val drive: DriveLocation? = null,
    val status: RecordingStatus,
)

/** The recording's own Drive folder (ADR-014) — the link an agent puts next to the notes it makes. */
@Serializable
data class DriveLocation(
    val folderId: String,
    val folderUrl: String,
)

@Serializable
data class AudioSettings(
    val codec: Codec,
    val container: Container,
    val sampleRateHz: Int,
    val channels: Int,
    val bitrateKbps: Int,
    val segmentSec: Int,
)

@Serializable
data class Part(
    val part: Int,
    val track: Track,
    val file: String,
    val bytes: Long,
    val sha256: String,
    val startOffsetSec: Double,
    val durationSec: Double,
)

@Serializable
data class Range(
    val startSec: Double,
    val endSec: Double,
    val reason: String? = null,
)

@Serializable
data class Context(
    val app: String? = null,
    /** People in the room, the recorder included — the `transcribe` speaker hint (docs/03, docs/08). */
    val participants: Int? = null,
)

@Serializable
enum class Codec {
    @SerialName("aac-lc")
    AAC_LC,
}

@Serializable
enum class Container {
    @SerialName("m4a")
    M4A,
}

@Serializable
enum class Platform {
    @SerialName("wearos")
    WEAROS,

    @SerialName("android")
    ANDROID,

    @SerialName("watchos")
    WATCHOS,

    @SerialName("ios")
    IOS,

    @SerialName("macos")
    MACOS,

    @SerialName("windows")
    WINDOWS,
}

@Serializable
enum class RecordingStatus {
    @SerialName("recording")
    RECORDING,

    @SerialName("finalized")
    FINALIZED,

    @SerialName("transferred")
    TRANSFERRED,
}
