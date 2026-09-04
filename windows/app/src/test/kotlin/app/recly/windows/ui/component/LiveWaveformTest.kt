package app.recly.windows.ui.component

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The curve the strip's bars are drawn on. The rest of the drawing is a `Canvas` and needs a window
 * to assert anything about (`FieldsTest` says the same about this module); this is the part of it
 * that is arithmetic, and it is the part that decides whether a quiet room looks like silence.
 */
class LiveWaveformTest {

    /** Full scale fills the row, and nothing is taller than the row. */
    @Test
    fun `the loudest window is the whole row`() {
        assertEquals(1f, barHeight(1f))
        assertEquals(1f, barHeight(1.4f), "a mix past full scale is not a taller bar")
    }

    @Test
    fun `silence is no bar at all`() {
        assertEquals(0f, barHeight(0f))
        // The helper clamps to the range, but a negative would be a bar drawn upwards from nowhere.
        assertEquals(0f, barHeight(-0.2f))
    }

    /**
     * docs/09 화면 원칙 6: `sqrt` and not the peak itself. A room at 0.05 is a quarter of the row
     * rather than a twentieth of it — the difference between "quiet" and "this is not recording",
     * which is the one thing the strip must not get wrong.
     */
    @Test
    fun `a quiet room is visibly not silence`() {
        val quiet = barHeight(0.05f)

        assertTrue(quiet > 0.2f, "a quiet room drew a line: $quiet")
        assertTrue(abs(quiet - 0.2236f) < 0.001f, "$quiet")
        assertTrue(barHeight(0.25f) > barHeight(0.05f), "and louder is still taller")
    }
}
