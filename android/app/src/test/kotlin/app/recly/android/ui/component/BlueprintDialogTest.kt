package app.recly.android.ui.component

import androidx.compose.ui.unit.dp
import app.recly.android.ui.theme.BlueprintColors
import app.recly.android.ui.theme.blueprintColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What [BlueprintDialog] decides without a screen: how tall it lets itself get, when its answers
 * stop fitting on one line, and what colour a chosen option is. A Compose UI test would need an
 * instrumented device; these are the pieces of the component that are answerable on their own.
 */
class BlueprintDialogTest {

    /** docs/09: a dialog sits *on* the screen. It never becomes the screen, and it never overflows it. */
    @Test
    fun `the card is most of the window and never all of it`() {
        listOf(480, 640, 800, 1280).forEach { screen ->
            val max = dialogMaxHeight(screen)
            assertTrue(max < screen.dp, "a $screen dp window gave the dialog $max")
            assertTrue(max > (screen / 2).dp, "a $screen dp window gave the dialog only $max")
        }
    }

    /** A taller window is allowed a taller card, so a long body scrolls less rather than more. */
    @Test
    fun `a taller window allows a taller card`() {
        assertTrue(dialogMaxHeight(800) > dialogMaxHeight(480))
    }

    /**
     * docs/09 §유동 타이포: the font size is the user's, so two answers that fit side by side at
     * scale 1.0 need not fit at 1.3 — and a clipped answer makes the question unanswerable. The
     * gaps between them count towards the row, or a row that only just fits would be judged to.
     */
    @Test
    fun `the actions stack exactly when the row they would make no longer fits`() {
        assertFalse(stackActions(available = 300, widths = listOf(120, 160), spacing = 8))
        assertTrue(stackActions(available = 300, widths = listOf(140, 160), spacing = 8))
        // The same two answers at a larger font size: the card did not change, the labels did.
        assertTrue(stackActions(available = 300, widths = listOf(160, 200), spacing = 8))
    }

    /** One action is a row whatever it costs; there is nothing beside it to be pushed into. */
    @Test
    fun `a single action never stacks while it fits`() {
        assertFalse(stackActions(available = 300, widths = listOf(300), spacing = 8))
        assertTrue(stackActions(available = 300, widths = listOf(301), spacing = 8))
    }

    /**
     * docs/09 "모든 상태는 색 + 텍스트": the mark is filled with the accent when it is chosen and
     * drawn in the quiet border colour when it is not — in both palettes.
     */
    @Test
    fun `a chosen option is the accent and an unchosen one is not`() {
        palettes().forEach { (name, palette) ->
            assertEquals(palette.accent, selectionInk(palette, selected = true), "$name selected")
            assertEquals(palette.textMuted, selectionInk(palette, selected = false), "$name unselected")
        }
    }

    private fun palettes(): List<Pair<String, BlueprintColors>> = listOf(
        "light" to blueprintColors(dark = false),
        "dark" to blueprintColors(dark = true),
    )
}
