package recly.core.platform

import okio.Path

/**
 * The one audio operation the core needs and cannot do itself (docs/08 "오디오 준비"): joining the
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
}
