package app.recly.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * docs/09 화면 원칙 6: the strip draws the last thirty seconds of windows, newest last. What this
 * holds is the ring's end of that bargain — the order it hands them back in, and what falls off.
 */
class LiveWaveformTest {

    @Test
    fun `the windows come back in the order they were recorded`() {
        val waveform = LiveWaveform(capacity = 4)

        waveform.add(0.1f)
        waveform.add(0.2f)

        assertEquals(listOf(0.1f, 0.2f), waveform.peaks)
    }

    /** Older than the strip is wide is older than anyone can see: the oldest simply falls off. */
    @Test
    fun `a full ring drops the oldest window and keeps the newest`() {
        val waveform = LiveWaveform(capacity = 3)

        listOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f).forEach(waveform::add)

        assertEquals(listOf(0.3f, 0.4f, 0.5f), waveform.peaks)
    }

    /** A bar taller than the row is not louder, and one below the floor is not quieter. */
    @Test
    fun `a level outside full scale is clamped, not dropped`() {
        val waveform = LiveWaveform(capacity = 2)

        waveform.add(1.4f)
        waveform.add(-0.2f)

        assertEquals(listOf(1f, 0f), waveform.peaks)
    }

    @Test
    fun `nothing recorded is an empty strip`() {
        assertEquals(emptyList(), LiveWaveform().peaks)
    }

    /** The next recording starts from silence — the last one's levels are not its own. */
    @Test
    fun `reset empties it`() {
        val waveform = LiveWaveform(capacity = 3)
        listOf(0.1f, 0.2f).forEach(waveform::add)

        waveform.reset()

        assertTrue(waveform.peaks.isEmpty())
        waveform.add(0.9f)
        assertEquals(listOf(0.9f), waveform.peaks)
    }

    /** Thirty seconds of tenths, which is more windows than any strip is wide. */
    @Test
    fun `the ring holds thirty seconds`() {
        val waveform = LiveWaveform()

        repeat(LiveWaveform.CAPACITY + 10) { waveform.add(it / 1000f) }

        assertEquals(LiveWaveform.CAPACITY, waveform.peaks.size)
        assertEquals(10 / 1000f, waveform.peaks.first())
    }
}
