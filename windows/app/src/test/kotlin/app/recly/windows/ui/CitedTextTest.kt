@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package app.recly.windows.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
 * invisible 44 target around them — while the line keeps its height and the words around it do nothing.
 */
class CitedTextTest {

    @Test
    fun `a time plays from its second, and the text around it does not`() {
        val seeks = mutableListOf<Double>()
        var canSeek by mutableStateOf(true)
        var text = Rect.Zero
        var below = Rect.Zero
        val scene = ImageComposeScene(600, 300, Density(1f)) {
            ReclyDesktopTheme(dark = false, highContrast = false) {
                Column {
                    Spacer(Modifier.height(40.dp).width(10.dp))
                    Column(Modifier.onGloballyPositioned { text = it.boundsInRoot() }) {
                        CitedText("[00:31] Ship the release on Friday.", canSeek, { seeks += it }, StringTable.of(StringTable.BASE))
                    }
                    Text("next line", Modifier.onGloballyPositioned { below = it.boundsInRoot() })
                }
            }
        }
        try {
            scene.render()
            // One line, as tall as the body type: the 44 target does not make it taller.
            assertTrue(text.height in 14f..26f, "line height ${text.height}")
            assertEquals(text.bottom, below.top)

            fun click(at: Offset) {
                scene.sendPointerEvent(PointerEventType.Move, at)
                scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary)
                scene.render()
            }
            val middle = text.top + text.height / 2
            click(Offset(text.left + 20f, middle))
            // Above and below the words, inside the target laid over the lines next to it.
            click(Offset(text.left + 20f, text.top - 8f))
            click(Offset(text.left + 20f, text.bottom + 8f))
            assertEquals(listOf(31.0, 31.0, 31.0), seeks)
            // The words after it, and far above it, are text.
            click(Offset(text.left + 250f, middle))
            assertEquals(3, seeks.size, "text after the time")
            click(Offset(text.left + 20f, text.top - 30f))
            assertEquals(3, seeks.size, "far above the time $text")
            // Where the recording cannot be played, a time is only text.
            canSeek = false
            scene.render()
            click(Offset(text.left + 20f, middle))
            assertEquals(3, seeks.size, "a time where nothing plays")
        } finally {
            scene.close()
        }
    }
}
