package app.recly.android.ui.component

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where [BlueprintDropdown]'s list opens: under the box and end-aligned with it (the box sits at the
 * end of its row), above it when there is no room below, and never off the window.
 */
class DropdownTest {

    private val window = IntSize(1080, 2000)
    private val menu = IntSize(600, 400)
    private val position = MenuPosition(gap = 8)

    @Test
    fun `the list opens under the box, end-aligned with it`() {
        val box = IntRect(left = 800, top = 500, right = 1040, bottom = 600)

        assertEquals(IntOffset(1040 - 600, 608), position.at(box))
    }

    @Test
    fun `right to left, it lines up with the box's other end`() {
        val box = IntRect(left = 40, top = 500, right = 280, bottom = 600)

        assertEquals(IntOffset(40, 608), position.at(box, LayoutDirection.Rtl))
    }

    @Test
    fun `with no room below, it opens above`() {
        val box = IntRect(left = 800, top = 1700, right = 1040, bottom = 1800)

        assertEquals(IntOffset(440, 1700 - 8 - 400), position.at(box))
    }

    @Test
    fun `a list wider than the room at its end is moved in, not cut off`() {
        val box = IntRect(left = 100, top = 500, right = 300, bottom = 600)

        assertEquals(0, position.at(box).x)
        assertEquals(32, MenuPosition(gap = 8, margin = 32).at(box).x, "and kept off the edge by the gutter")
    }

    private fun MenuPosition.at(box: IntRect, direction: LayoutDirection = LayoutDirection.Ltr): IntOffset =
        calculatePosition(box, window, direction, menu)
}
