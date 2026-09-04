package app.recly.android.ui.component

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * docs/09 화면 원칙 6: the live strip's one piece of arithmetic. A room that is being recorded has
 * to look like one, and linear amplitude does not — which is what the curve is for.
 */
class LiveWaveformTest {

    @Test
    fun `full scale fills the row and silence draws nothing`() {
        assertEquals(1f, barHeight(1f))
        assertEquals(0f, barHeight(0f))
    }

    /** A quiet room at 0.05 is a fifth of the row, not a line indistinguishable from silence. */
    @Test
    fun `a quiet level is still a visible bar`() {
        assertTrue(barHeight(0.05f) > 0.2f, "a recorded room reads as silence")
    }

    @Test
    fun `louder is taller`() {
        assertTrue(barHeight(0.2f) < barHeight(0.5f))
        assertTrue(barHeight(0.5f) < barHeight(0.9f))
    }

    /** A mix can exceed full scale, and a bar taller than the row is not louder. */
    @Test
    fun `a level outside full scale still fits the row`() {
        assertEquals(1f, barHeight(1.5f))
        assertEquals(0f, barHeight(-0.3f))
    }
}
