@file:OptIn(ExperimentalSerializationApi::class)

package recly.core.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
    /** Where the recording went in the user's Drive; written by `drive.upload` once the folder is known (docs/03 "Metadata"). */
    val drive: DriveLocation? = null,
    val status: RecordingStatus,
    /**
     * Moments the user marked, on the recording's own axis — ascending, none within [Highlight.MERGE_SEC]
     * of another, at most [Highlight.MAX] (docs/03 "Metadata"). Left out of the file while there are none.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val highlights: List<Highlight> = emptyList(),
)

/** One marked moment (docs/03 "Metadata"). */
@Serializable
data class Highlight(val atSec: Double) {
    companion object {
        /** Two marks closer than this are one. */
        const val MERGE_SEC: Double = 1.0

        const val MAX: Int = 500

        /**
         * The list a meta may hold: finite, not before the start, ascending, the first of any two within
         * [MERGE_SEC] kept, and no more than [MAX].
         */
        fun normalize(atSecs: List<Double>): List<Highlight> {
            val kept = mutableListOf<Double>()
            for (at in atSecs.filter { it.isFinite() && it >= 0 }.sorted()) {
                if (kept.size == MAX) break
                if (kept.isEmpty() || at - kept.last() >= MERGE_SEC) kept += at
            }
            return kept.map(::Highlight)
        }
    }
}

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
