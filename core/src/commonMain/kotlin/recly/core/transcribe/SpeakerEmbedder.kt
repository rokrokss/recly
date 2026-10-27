package recly.core.transcribe

import okio.Path

/** [startSec] to [endSec] of [file], one of the recording's part files, on that file's own axis. */
data class AudioSpan(val file: Path, val startSec: Double, val endSec: Double)

/**
 * docs/08 "Length · size limits": voices on this device, for the speakers of a long recording's pieces that the
 * part two pieces share did not pair — from the shell's own speaker-embedding model, where it has one. The core
 * asks once per merge, for every voice it needs; the answer is used in memory for that merge and never stored or
 * sent anywhere.
 */
interface SpeakerEmbedder {
    /**
     * One centroid for each key of [spans]: the mean of the unit-normalized embeddings of its spans, which are
     * stretches of the recording's part files. A key without enough usable speech is left out of the answer; null
     * means this device cannot embed now — its speaker model is not here. The core treats a throw like null.
     */
    @Throws(Throwable::class)
    suspend fun centroids(spans: Map<String, List<AudioSpan>>): Map<String, FloatArray>?
}
