package app.recly.recording

/**
 * Wall-clock length of each segment, used only when the container cannot say. Its whole job is to
 * read the boundary *before* it moves: measuring after the fact gives every segment a length of
 * roughly zero, which would then be written into the meta as the part's duration.
 */
internal class SegmentTimer(private val nowMs: () -> Long) {
    private var startedMs: Long = nowMs()

    /** How long the running segment has been going at [atMs], in seconds — without closing it. */
    fun elapsedSec(atMs: Long = nowMs()): Double = (atMs - startedMs).coerceAtLeast(0) / 1000.0

    /** Closes the running segment at [atMs] and opens the next one there. */
    fun advance(atMs: Long = nowMs()): Double {
        val seconds = (atMs - startedMs).coerceAtLeast(0) / 1000.0
        startedMs = atMs
        return seconds
    }
}
