package app.recly.recording

import recly.core.model.Part
import recly.core.model.Track
import recly.core.recording.MetaWriter

/**
 * Segment bookkeeping kept away from `MediaRecorder` so it can be tested without a device: what
 * the open and the armed-next segment files are called, and where in the timeline each finished
 * part starts.
 *
 * `startOffsetSec` accumulates the durations actually written rather than `part * segmentSec`, so
 * a boundary that came in a little short does not shift every later part (docs/03 "parts").
 */
internal class SegmentLedger(
    private val base: String,
    private val track: Track = Track.MONO,
) {
    /** 1-based; the same number across tracks of one time slice (docs/03 "이름 규칙"). */
    var openPart: Int = 1
        private set

    /** Audio confirmed so far — the `durationSec` handed to `finalize` once the last part closes. */
    var recordedSec: Double = 0.0
        private set

    fun fileName(part: Int): String = MetaWriter.partFileName(base, part, track)

    /** The file the recorder is writing into now. */
    fun openFileName(): String = fileName(openPart)

    /** Closes the open segment and opens the next one. */
    fun close(bytes: Long, sha256: String, durationSec: Double): Part = Part(
        part = openPart,
        track = track,
        file = openFileName(),
        bytes = bytes,
        sha256 = sha256,
        startOffsetSec = recordedSec,
        durationSec = durationSec,
    ).also {
        recordedSec += durationSec
        openPart += 1
    }
}
