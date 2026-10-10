package app.recly.windows.ui.component

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * docs/09 (2026-10-10): a menu opens under the thing that was clicked — its start edge on the thing's start, or its
 * end on the thing's end — above it when the window has no room below, and never outside the window.
 */
class MenuPositionTest {

    private val window = IntSize(900, 600)
    private val button = IntRect(left = 800, top = 20, right = 860, bottom = 64)
    private val menu = IntSize(220, 300)

    @Test
    fun `a menu opens under its button, its end on the button's end`() {
        assertEquals(IntOffset(860 - 220, 64), MenuPosition(end = true).calculatePosition(button, window, LayoutDirection.Ltr, menu))
    }

    @Test
    fun `a start menu hangs from the button's start edge, and is kept inside the window`() {
        val near = IntRect(left = 100, top = 20, right = 160, bottom = 64)
        assertEquals(IntOffset(100, 64), MenuPosition(end = false).calculatePosition(near, window, LayoutDirection.Ltr, menu))
        // Its start edge would put its end past the window's.
        assertEquals(IntOffset(900 - 220, 64), MenuPosition(end = false).calculatePosition(button, window, LayoutDirection.Ltr, menu))
    }

    @Test
    fun `right to left, the end is the left edge`() {
        val left = IntRect(left = 40, top = 20, right = 100, bottom = 64)
        assertEquals(IntOffset(40, 64), MenuPosition(end = true).calculatePosition(left, window, LayoutDirection.Rtl, menu))
        // Its start (the right edge) would put it past the window's left edge.
        assertEquals(IntOffset(0, 64), MenuPosition(end = false).calculatePosition(left, window, LayoutDirection.Rtl, menu))
    }

    @Test
    fun `with no room below it opens above, and with room on neither side as far down as it fits`() {
        val low = IntRect(left = 100, top = 480, right = 160, bottom = 524)
        assertEquals(IntOffset(100, 480 - 300), MenuPosition(end = false).calculatePosition(low, window, LayoutDirection.Ltr, menu))
        val tall = IntSize(220, 560)
        assertEquals(IntOffset(100, 600 - 560), MenuPosition(end = false).calculatePosition(IntRect(100, 200, 160, 244), window, LayoutDirection.Ltr, tall))
    }
}
