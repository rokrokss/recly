package app.recly.windows.record

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * docs/09 화면 원칙 6: the ring the popup's strip is drawn from. The helper sends the windows it
 * finished and forgets them, so this is the only place thirty seconds of a recording exist.
 */
class LiveWaveformTest {

    @Test
    fun `the windows come back in the order they arrived`() {
        val waveform = LiveWaveform()

        waveform.add(listOf(0.1f, 0.2f))
        waveform.add(listOf(0.3f))

        assertEquals(listOf(0.1f, 0.2f, 0.3f), waveform.peaks)
    }

    /** Thirty seconds is what the strip keeps; the second before them has fallen off the left. */
    @Test
    fun `the oldest windows fall off a full ring`() {
        val waveform = LiveWaveform(capacity = 4)

        waveform.add(listOf(1f, 2f, 3f))
        waveform.add(listOf(4f, 5f))

        assertEquals(listOf(2f, 3f, 4f, 5f), waveform.peaks)
    }

    /** A line longer than the ring itself is still the newest windows, in order. */
    @Test
    fun `one line longer than the ring leaves the newest end of it`() {
        val waveform = LiveWaveform(capacity = 3)

        waveform.add(listOf(1f, 2f, 3f, 4f, 5f))

        assertEquals(listOf(3f, 4f, 5f), waveform.peaks)
    }

    /** Before the first line there is nothing to draw — not a row of silent bars. */
    @Test
    fun `a recording that has not filled a window yet has no bars`() {
        assertTrue(LiveWaveform().peaks.isEmpty())
    }

    /** Thirty seconds at the helper's tenth-of-a-second window. */
    @Test
    fun `the ring is thirty seconds long`() {
        val waveform = LiveWaveform()

        waveform.add(List(LiveWaveform.CAPACITY + 10) { it.toFloat() })

        assertEquals(LiveWaveform.CAPACITY, waveform.peaks.size)
        assertEquals(10f, waveform.peaks.first())
    }
}
