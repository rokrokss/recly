package app.recly.android.ui.component

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * docs/09 화면 원칙 5: where a [BlueprintMenu] lands. `DropdownMenu` did this itself, along with a
 * shadow and a scale animation docs/09 does not have; a `Popup` places nothing on its own, so the
 * rule is here — and here is where it can be checked without a window.
 */
class MenuOffsetTest {

    @Test
    fun `a menu opens under its anchor, left edges aligned`() {
        val at = menuOffset(anchor = IntRect(40, 100, 300, 160), window = WINDOW, menu = IntSize(200, 300))

        assertEquals(IntOffset(40, 160), at)
    }

    /** No room below is not a reason to hang off the bottom of the screen. */
    @Test
    fun `a menu with no room below it flips above the anchor`() {
        val at = menuOffset(anchor = IntRect(0, 1_700, 300, 1_760), window = WINDOW, menu = IntSize(200, 300))

        assertEquals(IntOffset(0, 1_400), at, "the menu did not close on the anchor's top edge")
    }

    /** …and a menu taller than the whole window still starts at the top of it. */
    @Test
    fun `a menu taller than the window is not pushed off the top`() {
        val at = menuOffset(anchor = IntRect(0, 900, 300, 960), window = WINDOW, menu = IntSize(200, 2_000))

        assertEquals(0, at.y)
    }

    @Test
    fun `a menu wider than the space to its right is pulled back inside the window`() {
        val menu = IntSize(400, 200)
        val at = menuOffset(anchor = IntRect(900, 100, 1_000, 160), window = WINDOW, menu = menu)

        assertEquals(WINDOW.width - menu.width, at.x)
        assertTrue(at.x + menu.width <= WINDOW.width, "the menu ran off the right edge")
    }

    /** A window narrower than the menu has no good answer; the left edge is the least bad one. */
    @Test
    fun `a menu wider than the window starts at its left edge`() {
        val at = menuOffset(anchor = IntRect(600, 100, 700, 160), window = WINDOW, menu = IntSize(2_000, 200))

        assertEquals(0, at.x)
    }

    private companion object {
        val WINDOW = IntSize(width = 1_080, height = 1_920)
    }
}
