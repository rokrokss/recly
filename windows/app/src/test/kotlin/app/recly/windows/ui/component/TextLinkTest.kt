@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package app.recly.windows.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
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
import app.recly.windows.ui.theme.ReclyDesktopTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * docs/09 (2026-10-09): a link in a row is as tall as its words, and still a [app.recly.windows.ui.theme.MinTouch]
 * target — the target is laid over the lines around it rather than added to the row.
 */
class TextLinkTest {

    @Test
    fun `the link takes its text's height and is clicked above and below it`() {
        var clicks = 0
        var link = Rect.Zero
        var below = Rect.Zero
        val scene = ImageComposeScene(400, 300, Density(1f)) {
            ReclyDesktopTheme(dark = false, highContrast = false) {
                Column {
                    Text("Using your ChatGPT plan")
                    TextLink("Manage usage", { clicks++ }, Modifier.onGloballyPositioned { link = it.boundsInRoot() })
                    Text("next line", Modifier.onGloballyPositioned { below = it.boundsInRoot() })
                }
            }
        }
        try {
            scene.render()
            // Laid out at the line's height, so the next line starts right under it.
            assertTrue(link.height in 10f..24f, "link height ${link.height}")
            assertEquals(link.bottom, below.top)

            fun click(at: Offset) {
                scene.sendPointerEvent(PointerEventType.Move, at)
                scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary)
                scene.render()
            }
            // 44 tall around the words: half the difference above them and half below.
            click(Offset(link.left + 10f, link.top - 8f))
            click(Offset(link.left + 10f, link.bottom + 8f))
            assertEquals(2, clicks)
            click(Offset(link.left + 10f, link.top - 30f))
            assertEquals(2, clicks)
        } finally {
            scene.close()
        }
    }
}
