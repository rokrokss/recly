package recly.core.recording

import kotlin.test.Test
import kotlin.test.assertEquals

/** docs/10 "Shared rules for the shells": what "Skip silence" skips. */
class SilenceRangesTest {
    /** [seconds] of 0.25 s windows at [peak]. */
    private fun stretch(seconds: Double, peak: Float) = List((seconds / WaveformPeaks.WINDOW_SEC).toInt()) { peak }

    private val speech = 0.3f
    private val pause = 0.01f

    @Test
    fun `a pause of a second or more is skipped, less its padding at both ends`() {
        val peaks = stretch(5.0, speech) + stretch(2.0, pause) + stretch(3.0, speech) + stretch(0.75, pause) + stretch(2.0, speech)

        assertEquals(listOf(SilentRange(5.25, 6.75)), SilenceRanges.compute(peaks))
    }

    @Test
    fun `a recording that is all silence is one range`() {
        assertEquals(listOf(SilentRange(0.25, 9.75)), SilenceRanges.compute(stretch(10.0, 0.001f)))
        assertEquals(listOf(SilentRange(0.25, 3.75)), SilenceRanges.compute(stretch(4.0, 0f)), "digital silence too")
    }

    @Test
    fun `a recording that never pauses skips nothing`() {
        assertEquals(emptyList(), SilenceRanges.compute(stretch(30.0, speech)))
        assertEquals(emptyList(), SilenceRanges.compute(stretch(10.0, speech) + stretch(10.0, 0.25f)))
        assertEquals(emptyList(), SilenceRanges.compute(emptyList()))
    }

    @Test
    fun `silences at the start and the end count`() {
        val peaks = stretch(1.5, pause) + stretch(4.0, speech) + stretch(2.0, pause)

        assertEquals(listOf(SilentRange(0.25, 1.25), SilentRange(5.75, 7.25)), SilenceRanges.compute(peaks))
    }

    @Test
    fun `the threshold follows the room, not a fixed level`() {
        // A noisy room: its pauses are well above −45 dBFS, and they are still its pauses.
        val noisy = stretch(6.0, 0.4f) + stretch(3.0, 0.05f) + stretch(6.0, 0.45f)

        assertEquals(listOf(SilentRange(6.25, 8.75)), SilenceRanges.compute(noisy))
    }
}
