@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package app.recly.windows.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.recly.windows.i18n.StringTable
import app.recly.windows.ui.theme.ReclyDesktopTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * docs/09 "Summary view": a time in a summary plays the recording from there — pressed on its words, or on the
 * invisible target of at least 44 each way centred on them — while the line keeps its height and the words around
 * it do nothing.
 */
class CitedTextTest {

    @Test
    fun `a time plays from its second, and the text around it does not`() {
        val seeks = mutableListOf<Double>()
        var canSeek by mutableStateOf(true)
        var seekable by mutableStateOf(Double.POSITIVE_INFINITY)
        var text = Rect.Zero
        var plain = Rect.Zero
        var below = Rect.Zero
        // At Density(1f) a dp is a pixel, so the 44 target is 44 pixels.
        val scene = ImageComposeScene(600, 300, Density(1f)) {
            ReclyDesktopTheme(dark = false, highContrast = false) {
                Column {
                    Spacer(Modifier.height(60.dp).width(10.dp))
                    Column(Modifier.onGloballyPositioned { text = it.boundsInRoot() }) {
                        CitedText("[00:31] Ship the release on Friday.", canSeek, { seeks += it }, StringTable.of(StringTable.BASE), seekable)
                    }
                    Text("next line", Modifier.onGloballyPositioned { below = it.boundsInRoot() })
                    // The same words with no time in them: the height a line of the summary has.
                    Text("Ship the release on Friday.", Modifier.onGloballyPositioned { plain = it.boundsInRoot() }, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        // The targets are drawn from the text's layout, a frame after it.
        fun settle() {
            scene.render()
            while (scene.hasInvalidations()) scene.render()
        }
        fun click(at: Offset) {
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary)
            settle()
        }
        fun seeks(at: Offset): Boolean {
            seeks.clear()
            click(at)
            return seeks.isNotEmpty()
        }
        try {
            settle()
            // One line, as tall as a line with no time in it: the 44 target takes no room.
            assertTrue(text.height in 14f..26f, "line height ${text.height}")
            assertEquals(plain.height, text.height, "a line with a time against one without")
            assertEquals(text.bottom, below.top)

            val middle = text.top + text.height / 2
            // The time's own words start the line; how far right they reach, and how far up and down the target does.
            val inside = Offset(text.left + 20f, middle)
            assertTrue(seeks(inside), "on the time's words")
            assertEquals(listOf(31.0), seeks)
            val right = (0..200).last { seeks(Offset(text.left + it, middle)) }
            val up = (0..40).last { seeks(Offset(inside.x, middle - it)) }
            val down = (0..40).last { seeks(Offset(inside.x, middle + it)) }
            println("CITATION target: x ${text.left}..${text.left + right}, y ${middle - up}..${middle + down} (${right + 1} × ${up + down + 1}, line ${text.height})")
            assertTrue(up + down + 1 >= 44, "target height ${up + down + 1}")
            assertTrue(right + 1 >= 44, "target width ${right + 1}")
            // Centred on the line: the same reach above as below, give or take the half pixel.
            assertTrue(kotlin.math.abs(up - down) <= 1, "above $up, below $down")

            // 20 above and below the time's centre, inside the target laid over the lines next to it.
            assertTrue(seeks(Offset(inside.x, middle - 20f)), "20 above the time")
            assertTrue(seeks(Offset(inside.x, middle + 20f)), "20 below the time")
            // Plain words 30 to the right of the time, and 30 above it, are text.
            assertTrue(!seeks(Offset(text.left + right + 30f, middle)), "the words 30 after the time")
            assertTrue(!seeks(Offset(inside.x, middle - 30f)), "30 above the time")

            // Where the recording cannot be played, a time is only text.
            canSeek = false
            settle()
            assertTrue(!seeks(inside), "a time where nothing plays")
            assertTrue(!seeks(Offset(inside.x, middle - 20f)), "above a time where nothing plays")
            // Nor can one past the recording's end, as a transcript time button past it is off.
            canSeek = true
            seekable = 30.0
            settle()
            assertTrue(!seeks(inside), "a time past the end")
            seekable = 31.5
            settle()
            assertTrue(seeks(inside), "a time before the end")
            assertEquals(listOf(31.0), seeks)
        } finally {
            scene.close()
        }
    }

    /**
     * 2026-10-10: three times on three lines, one under another. Each one's 44 target reaches over its neighbours',
     * and a press plays the time whose words are nearest — the vertical centre of each line plays that line's time.
     */
    @Test
    fun `stacked times each play their own from the centre of their line`() {
        val plays = mutableListOf<Double>()
        var text = Rect.Zero
        val scene = ImageComposeScene(600, 300, Density(1f)) {
            ReclyDesktopTheme(dark = false, highContrast = false) {
                Column {
                    Spacer(Modifier.height(60.dp).width(10.dp))
                    Column(Modifier.onGloballyPositioned { text = it.boundsInRoot() }) {
                        CitedText("[00:01] one\n[00:02] two\n[00:03] three", true, { plays += it }, StringTable.of(StringTable.BASE))
                    }
                }
            }
        }
        fun settle() {
            scene.render()
            while (scene.hasInvalidations()) scene.render()
        }
        fun click(at: Offset) {
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary)
            settle()
        }
        try {
            settle()
            val line = text.height / 3
            for (index in 0 until 3) {
                plays.clear()
                click(Offset(text.left + 20f, text.top + line * index + line / 2))
                assertEquals(listOf(index + 1.0), plays, "the centre of line ${index + 1}")
            }
        } finally {
            scene.close()
        }
    }

    /** The rule itself: inside more than one target, the nearest words win; inside none, nothing plays. */
    @Test
    fun `a press inside two targets plays the time whose words are nearest`() {
        val first = CitationTarget(0, Rect(0f, 0f, 60f, 20f))
        val second = CitationTarget(2, Rect(0f, 20f, 60f, 40f))
        val targets = listOf(first, second)
        assertEquals(first, citationHit(Offset(10f, 10f), targets, reach = 44f))
        assertEquals(second, citationHit(Offset(10f, 30f), targets, reach = 44f))
        // Between the two, a little nearer the second.
        assertEquals(second, citationHit(Offset(10f, 21f), targets, reach = 44f))
        // Above the first, inside its reach alone.
        assertEquals(first, citationHit(Offset(10f, -8f), targets, reach = 44f))
        assertEquals(null, citationHit(Offset(100f, 10f), targets, reach = 44f))
    }
}
