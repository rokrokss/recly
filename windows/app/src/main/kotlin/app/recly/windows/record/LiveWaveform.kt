package app.recly.windows.record

/**
 * What the tray popup's strip draws: the loudest sample of each tenth of a second of the track being
 * written, oldest first, for the last thirty seconds (docs/09 화면 원칙 6).
 *
 * The Mac's `RecKit.LiveWaveform` keeps the same ring off its own recorder; here the numbers arrive
 * as `HelperEvent.Level` and this is only the ring they land in. Thirty seconds of them is more
 * windows than any strip is wide, so the newest end is always the one on screen and the oldest
 * simply falls off.
 *
 * Both halves are synchronized: the helper's reader coroutine writes them and the composition reads
 * them ten times a second, and a list rebuilt under a growing ring would be one bar short of itself.
 */
class LiveWaveform(private val capacity: Int = CAPACITY) {
    private val ring = FloatArray(capacity)
    private var next = 0
    private var filled = 0

    /** One helper line's worth of finished windows, oldest first. */
    fun add(peaks: List<Float>) = synchronized(this) {
        for (peak in peaks) {
            ring[next] = peak
            next = (next + 1) % capacity
            filled = minOf(filled + 1, capacity)
        }
    }

    /** The windows that are in the ring, oldest first. */
    val peaks: List<Float>
        get() = synchronized(this) {
            val start = (next - filled + capacity) % capacity
            List(filled) { ring[(start + it) % capacity] }
        }

    companion object {
        /** Thirty seconds at the helper's tenth-of-a-second window. */
        const val CAPACITY: Int = 300
    }
}
