package recly.core.platform

import okio.Path

/**
 * The one audio operation the core needs and cannot do itself (docs/08 "Audio preparation"): joining the
 * parts of one track back into a single file for the STT provider.
 *
 * Every shell has a native muxer for it — `MediaMuxer`, `AVMutableComposition`, bundled ffmpeg —
 * and all of them copy the AAC frames as they are. Re-encoding is not allowed: the segment
 * boundaries are frame-aligned, so a lossless join is both possible and what the timestamps in
 * `transcript.json` assume.
 */
interface AudioTools {
    /** Writes [parts], in the given order, to [out] as one file. */
    suspend fun concat(parts: List<Path>, out: Path)

    /**
     * The loudest sample of every [windowSec] window of [file], 0–1, from its start — what the waveform is drawn
     * from ([recly.core.recording.WaveformPeaks]). The core asks it of a desktop recording's `mic` and `sys` parts
     * to tell the person who made the recording from the others (docs/08 "Me and others"). Null when this shell
     * cannot decode it; a shell that never records both tracks may always answer null.
     */
    suspend fun levels(file: Path, windowSec: Double): List<Float>?
}
