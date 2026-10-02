package recly.core.recording

import kotlin.math.roundToInt

/**
 * docs/09 screen principle 2: a recording's waveform — the loudest sample of every [WINDOW_SEC] window,
 * 0–1, on the recording's own timeline — computed once by the shell's decoder and kept beside the
 * parts as [FILE], so opening the recording again draws it at once, even after its parts were
 * cleaned up and have to come back from Drive. It stays on the device: nothing uploads it, and it
 * goes with the recording's folder.
 *
 * The file is `RWF1`, the window count as a little-endian UInt32, then one little-endian UInt16 per
 * window (the peak × 65535). Sixteen bits and not eight: the drawing normalises to the loudest
 * window, and a quiet recording scaled up from eight bits would come out in a handful of steps.
 */
object WaveformPeaks {
    const val FILE: String = "waveform.v1"
    const val WINDOW_SEC: Double = 0.25
    private val MAGIC = "RWF1".encodeToByteArray()
    private const val HEADER = 8

    fun encode(peaks: List<Float>): ByteArray {
        val bytes = ByteArray(HEADER + peaks.size * 2)
        MAGIC.copyInto(bytes)
        val count = peaks.size
        for (i in 0 until 4) bytes[4 + i] = (count ushr (8 * i)).toByte()
        peaks.forEachIndexed { index, peak ->
            val value = (peak.coerceIn(0f, 1f) * 65535f).roundToInt()
            bytes[HEADER + index * 2] = value.toByte()
            bytes[HEADER + index * 2 + 1] = (value ushr 8).toByte()
        }
        return bytes
    }

    /** Null for anything that is not a whole file of this version — the shell then decodes again. */
    fun decode(bytes: ByteArray): List<Float>? {
        if (bytes.size < HEADER || !bytes.copyOfRange(0, 4).contentEquals(MAGIC)) return null
        var count = 0L
        for (i in 0 until 4) count = count or ((bytes[4 + i].toLong() and 0xFF) shl (8 * i))
        if (bytes.size.toLong() != HEADER + count * 2) return null
        return List(count.toInt()) { index ->
            val low = bytes[HEADER + index * 2].toInt() and 0xFF
            val high = bytes[HEADER + index * 2 + 1].toInt() and 0xFF
            (low or (high shl 8)) / 65535f
        }
    }
}
