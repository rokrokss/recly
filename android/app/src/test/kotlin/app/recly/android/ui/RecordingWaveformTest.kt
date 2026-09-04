package app.recly.android.ui

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * docs/09 화면 원칙 2: the shape under the detail's clock. [RecordingWaveform.bins] is the half that
 * has to be right at every width the row can be, and [RecordingWaveform.Windows] the half that cuts
 * what the codec hands over into the recording's own quarter-seconds — both without a file or a
 * screen, which is why they are apart from the decode at all. RecKit's `RecordingWaveformTests` and
 * the Windows shell's `RecordingWaveformTest` pin the same arithmetic.
 */
class RecordingWaveformTest {

    // --- The bars actually drawn ---------------------------------------------------------------

    /**
     * Fewer bars than windows: each bar is the loudest window under it, so a transient is still
     * visible at the widths where several seconds share one column.
     */
    @Test
    fun `fewer bars than windows take the loudest of each`() {
        // Halves and quarters throughout, so what the assertion checks is the resampling and not
        // the last bit of a `Float` division.
        val bins = RecordingWaveform.bins(floatArrayOf(0.125f, 1f, 0.25f, 0.5f, 0.375f, 0.0625f), count = 3)

        assertContentEquals(floatArrayOf(1f, 0.5f, 0.375f), bins, "the loudest of each pair, normalised")
    }

    /**
     * More bars than windows: the window is repeated across the bars that fall inside it rather
     * than read off the end of the peaks.
     */
    @Test
    fun `more bars than windows repeat the window`() {
        assertContentEquals(
            floatArrayOf(1f, 1f, 1f, 0.5f, 0.5f),
            RecordingWaveform.bins(floatArrayOf(1f, 0.5f), count = 5),
        )
    }

    /**
     * The bars fill the row whatever the recording's own level was: what a waveform says is where
     * the sound is, not how many decibels it had.
     */
    @Test
    fun `the loudest bar fills the row`() {
        assertContentEquals(
            floatArrayOf(1f, 0.5f),
            RecordingWaveform.bins(floatArrayOf(0.03125f, 0.015625f), count = 2),
        )
    }

    /**
     * …except a recording with nothing in it at all, which has no loudest bar to normalise by and
     * stays flat rather than being blown up into a full-height wall of noise.
     */
    @Test
    fun `silence stays silent`() {
        assertContentEquals(
            floatArrayOf(0f, 0f, 0f),
            RecordingWaveform.bins(floatArrayOf(0f, 0f, 0f), count = 3),
        )
    }

    /**
     * Nothing decoded yet, and a row with no room in it: both are the baseline the bar draws
     * instead, and neither is a crash.
     */
    @Test
    fun `nothing to draw is no bars`() {
        assertTrue(RecordingWaveform.bins(FloatArray(0), count = 8).isEmpty())
        assertTrue(RecordingWaveform.bins(floatArrayOf(1f, 0.5f), count = 0).isEmpty())
        assertTrue(RecordingWaveform.bins(floatArrayOf(1f, 0.5f), count = -3).isEmpty())
    }

    /** One bar for the whole recording is the narrowest the row ever gets, and it is its loudest. */
    @Test
    fun `one bar is the whole recording`() {
        assertContentEquals(floatArrayOf(1f), RecordingWaveform.bins(floatArrayOf(0.1f, 0.9f, 0.3f), count = 1))
    }

    /**
     * A width that does not divide the windows evenly still gets exactly as many bars as it asked
     * for, and every window is under one of them.
     */
    @Test
    fun `every width gets the bars it asked for`() {
        val peaks = FloatArray(7) { 0.5f }
        for (count in 1..20) {
            assertEquals(count, RecordingWaveform.bins(peaks, count).size, "$count bars")
        }
    }

    // --- The PCM the decoder hands over --------------------------------------------------------

    /**
     * A window is a quarter second of the *recording* — 4000 samples at 16 kHz — however the codec
     * happened to break its buffers up. The cut here falls in the middle of the second `add`, so
     * what is checked is that the count carries across.
     */
    @Test
    fun `a window is cut at its own sample, not at the end of a buffer`() {
        val windows = RecordingWaveform.Windows(4000)
        // The loudest of the first window is in the first buffer, the second's in the second.
        windows.add(ShortArray(3000) { 8192 }, count = 3000)
        windows.add(ShortArray(3000) { if (it < 1000) 16384 else 4096 }, count = 3000)

        assertContentEquals(floatArrayOf(0.5f, 0.125f), windows.finish())
    }

    /** The tail of the part is a window like any other, however short it came out. */
    @Test
    fun `the last window is however long the part left it`() {
        val windows = RecordingWaveform.Windows(4000)
        windows.add(ShortArray(4500) { if (it < 4000) 8192 else 16384 }, count = 4500)

        assertContentEquals(floatArrayOf(0.25f, 0.5f), windows.finish())
    }

    /** Only the first [count] samples are the decoder's; the rest of the array is its slack. */
    @Test
    fun `the samples past the count are not part of the window`() {
        val windows = RecordingWaveform.Windows(2)
        windows.add(shortArrayOf(4096, 4096, 32767, 32767), count = 2)

        assertContentEquals(floatArrayOf(0.125f), windows.finish())
    }

    /**
     * A peak is how far the sample is from silence, either way — and the widened sample is what is
     * measured, because `Short.MIN_VALUE` has no positive of its own to take an absolute of.
     */
    @Test
    fun `a trough is as loud as a crest`() {
        val windows = RecordingWaveform.Windows(2)
        windows.add(shortArrayOf(4096, -32768), count = 2)

        assertContentEquals(floatArrayOf(1f), windows.finish())
    }

    /** Nothing decoded is no windows, rather than one empty one. */
    @Test
    fun `a part with no samples has no windows`() {
        assertTrue(RecordingWaveform.Windows(4000).finish().isEmpty())
    }

    // --- The part on the recording's clock -----------------------------------------------------

    /** A part the codec came up short on is padded with silence rather than pulling the rest in. */
    @Test
    fun `a short decode is padded to what meta says the part is`() {
        assertContentEquals(
            floatArrayOf(1f, 0.5f, 0f, 0f),
            RecordingWaveform.fit(floatArrayOf(1f, 0.5f), durationSec = 1.0, windowSec = 0.25),
        )
    }

    /** And one that ran long is cut, so the next part still starts where the clock says it does. */
    @Test
    fun `a long decode is truncated to what meta says the part is`() {
        assertContentEquals(
            floatArrayOf(1f, 0.5f),
            RecordingWaveform.fit(floatArrayOf(1f, 0.5f, 0.25f, 0.125f), durationSec = 0.5, windowSec = 0.25),
        )
    }

    /** A duration that is not whole windows rounds up: the last part-window is still on the clock. */
    @Test
    fun `a part that is not whole windows keeps its last one`() {
        assertEquals(
            5,
            RecordingWaveform.fit(FloatArray(8) { 1f }, durationSec = 1.1, windowSec = 0.25).size,
        )
    }
}
