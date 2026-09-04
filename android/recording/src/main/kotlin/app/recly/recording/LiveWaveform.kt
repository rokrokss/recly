package app.recly.recording

/**
 * What the strip under the recording screen's timer draws: the loudest sample of each tenth of a
 * second of the track being written, newest last, for the last thirty seconds. RecKit's
 * `LiveWaveform` is the same ring — the Apple shells count the windows themselves off the tap,
 * while here `MediaRecorder` counts them for us and [RecorderService] only has to ask on time.
 *
 * Thirty seconds of windows is more than any strip is wide, so the newest end is always the one on
 * screen and the oldest simply falls off.
 *
 * [synchronized] because the two ends are two threads: the service's poller writes and the
 * composition reads, ten times a second each, and neither may wait on the other for long.
 */
class LiveWaveform(private val capacity: Int = CAPACITY) {
    init {
        require(capacity > 0) { "a ring of no windows holds no levels" }
    }

    /** [next] is where the window after this one goes, [filled] how many slots were ever written. */
    private val ring = FloatArray(capacity)
    private var next = 0
    private var filled = 0

    /** One finished window. Clamped: a bar taller than the row is not louder. */
    fun add(peak: Float) {
        synchronized(this) {
            ring[next] = peak.coerceIn(0f, 1f)
            next = (next + 1) % capacity
            filled = minOf(filled + 1, capacity)
        }
    }

    /** The windows that have been recorded, oldest first; empty until there is one. */
    val peaks: List<Float>
        get() = synchronized(this) {
            val start = (next - filled + capacity) % capacity
            List(filled) { ring[(start + it) % capacity] }
        }

    fun reset() {
        synchronized(this) {
            next = 0
            filled = 0
        }
    }

    companion object {
        /** Thirty seconds of tenths. */
        const val CAPACITY: Int = 300
    }
}
