package recly.core.recording

import kotlin.math.max
import kotlin.math.min

/** A stretch playback skips with "Skip silence" on, in seconds on the recording's own axis. */
data class SilentRange(val startSec: Double, val endSec: Double)

/**
 * docs/10 "Shared rules for the shells": the quiet stretches of a recording, worked out the same way on every shell from
 * its [WaveformPeaks] — the loudest sample of each window, 0–1.
 *
 * A window is silent when its peak is below the recording's own threshold: 6 dB above its quiet level
 * (twice the 8th percentile of the non-zero peaks), but no higher than 12 dB under its loud level (a
 * quarter of the 95th percentile) — so a recording that never pauses has no quiet level to speak of and
 * skips nothing — and never below [FLOOR], about −45 dBFS. A run of silent windows that lasts
 * [MIN_SILENCE_SEC] or more is a silence; each comes back [PADDING_SEC] shorter at both ends, so the
 * first syllable after a pause and the last before it are still heard.
 */
object SilenceRanges {
    /** About −45 dBFS as a peak amplitude (10^(−45/20)): below this nothing is speech. */
    const val FLOOR: Float = 0.0056234f

    const val MIN_SILENCE_SEC: Double = 1.0

    const val PADDING_SEC: Double = 0.25

    fun compute(peaks: List<Float>, windowSec: Double = WaveformPeaks.WINDOW_SEC): List<SilentRange> {
        if (peaks.isEmpty() || windowSec <= 0) return emptyList()
        val threshold = threshold(peaks)
        val ranges = mutableListOf<SilentRange>()
        var runStart = -1
        for (index in 0..peaks.size) {
            val silent = index < peaks.size && peaks[index] < threshold
            if (silent && runStart < 0) runStart = index
            if (!silent && runStart >= 0) {
                if ((index - runStart) * windowSec >= MIN_SILENCE_SEC) {
                    ranges += SilentRange(runStart * windowSec + PADDING_SEC, index * windowSec - PADDING_SEC)
                }
                runStart = -1
            }
        }
        return ranges
    }

    internal fun threshold(peaks: List<Float>): Float {
        val sounding = peaks.filter { it > 0f }.sorted()
        if (sounding.isEmpty()) return FLOOR
        val quiet = percentile(sounding, 0.08) * 2
        val loud = percentile(sounding, 0.95) / 4
        return max(FLOOR, min(quiet, loud))
    }

    private fun percentile(sorted: List<Float>, p: Double): Float = sorted[((sorted.size - 1) * p).toInt()]
}
