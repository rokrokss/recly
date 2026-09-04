package app.recly.windows.helper

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import recly.core.model.Track

/**
 * docs/14 "구조": one JSON object per line each way, the app on stdin and the helper on stdout.
 *
 * The wire names are the discriminator values docs/14 lists (`start`/`stop`/`detect` ↔ `part_done`/
 * `mic_in_use`/`error`), so this file *is* the contract the Rust helper (M6-L2) is written against.
 *
 * [Start.base] is the one field docs/14's sketch does not name. It has to be here: the file names
 * are docs/03's ("이름 규칙", `{base}_p001_mic.m4a`) and the base is derived from the recording's
 * own meta, which only the app has. A helper that invented names would put the naming rule in two
 * places and the second one in Rust.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("command")
sealed class HelperCommand {

    @Serializable
    @SerialName("start")
    data class Start(
        val dir: String,
        val base: String,
        val segmentSec: Int,
        val tracks: List<Track>,
    ) : HelperCommand()

    @Serializable
    @SerialName("stop")
    data object Stop : HelperCommand()

    /** docs/14 "감지": mic-in-use monitoring, on or off. The events arrive as [HelperEvent.MicInUse]. */
    @Serializable
    @SerialName("detect")
    data class Detect(val on: Boolean) : HelperCommand()
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("event")
sealed class HelperEvent {

    /**
     * A segment is closed and on disk. The fields are docs/03's `parts[]` entry — the helper hashes
     * the file it just closed, because it is the only side that knows when the last frame landed.
     */
    @Serializable
    @SerialName("part_done")
    data class PartDone(
        val part: Int,
        val track: Track,
        val file: String,
        val bytes: Long,
        val sha256: String,
        val startOffsetSec: Double,
        val durationSec: Double,
    ) : HelperEvent()

    /**
     * docs/14 "감지". [inUse] is the transition: true when [app] took the microphone, false when it
     * gave it back — the microphone going quiet is what docs/14's sixty-second idle offer is read off, and
     * it defaults to true only so an older helper that reports the taking alone still parses.
     */
    @Serializable
    @SerialName("mic_in_use")
    data class MicInUse(val app: String, val inUse: Boolean = true) : HelperEvent()

    /**
     * docs/09 화면 원칙 6: the peak of every tenth of a second the helper finished writing since the
     * last line, oldest first — the track the user hears (the mix in a meeting, the microphone when
     * that is the only track). It is what the popup's strip draws while a recording runs, and it is
     * the recorder's own write path rather than a second tap on the microphone, so a strip that
     * moves is the capture itself moving.
     */
    @Serializable
    @SerialName("level")
    data class Level(val peaks: List<Float>) : HelperEvent()

    @Serializable
    @SerialName("error")
    data class Failed(val message: String, val fatal: Boolean = true) : HelperEvent()
}

/** Unknown fields are ignored on purpose: a newer helper may say more than this version reads. */
internal val helperJson: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
