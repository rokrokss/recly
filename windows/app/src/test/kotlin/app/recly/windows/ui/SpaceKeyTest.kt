@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package app.recly.windows.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.recly.windows.ui.component.BlueprintTextField
import app.recly.windows.ui.component.LocalTypingFocus
import app.recly.windows.ui.component.TypingFocus
import app.recly.windows.ui.theme.ReclyDesktopTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Details window's Space ([spaceKey]): it plays and pauses after a click, it leaves a field's space in the field, and
 * under the keyboard it leaves a focused button its own Space.
 */
class SpaceKeyTest {

    @Test
    fun `Space plays after a click, types in a field, and presses a button the keyboard is on`() {
        var plays = 0
        var presses = 0
        var typed = ""
        val scene = ImageComposeScene(400, 400, Density(1f)) {
            val typing = remember { TypingFocus() }
            var value by remember { mutableStateOf("") }
            ReclyDesktopTheme(dark = false, highContrast = false) {
                CompositionLocalProvider(LocalTypingFocus provides typing) {
                    Column(Modifier.spaceKey(typing) { plays++; true }) {
                        Box(Modifier.size(100.dp).clickable { presses++ })
                        BlueprintTextField(value, { value = it; typed = it }, "Name")
                    }
                }
            }
        }
        val source = java.awt.Canvas()
        fun key(key: Key, code: Int, char: Char, shift: Boolean = false) {
            val mask = if (shift) java.awt.event.InputEvent.SHIFT_DOWN_MASK else 0
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, codePoint = char.code, isShiftPressed = shift,
                nativeEvent = java.awt.event.KeyEvent(source, java.awt.event.KeyEvent.KEY_PRESSED, 0L, mask, code, char)))
            if (key == Key.Spacebar) {
                scene.sendKeyEvent(KeyEvent(Key.Unknown, KeyEventType.Unknown, codePoint = char.code,
                    nativeEvent = java.awt.event.KeyEvent(source, java.awt.event.KeyEvent.KEY_TYPED, 0L, 0, java.awt.event.KeyEvent.VK_UNDEFINED, char)))
            }
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, codePoint = char.code, isShiftPressed = shift,
                nativeEvent = java.awt.event.KeyEvent(source, java.awt.event.KeyEvent.KEY_RELEASED, 0L, mask, code, char)))
            scene.render()
        }
        fun space() = key(Key.Spacebar, java.awt.event.KeyEvent.VK_SPACE, ' ')
        fun tab(shift: Boolean = false) = key(Key.Tab, java.awt.event.KeyEvent.VK_TAB, '\t', shift)
        try {
            scene.render()
            val at = Offset(50f, 50f)
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary)
            scene.render()
            assertEquals(1, presses)

            // The click focused the box, and Space is the window's, not a second press of it.
            space()
            assertEquals(1, plays)
            assertEquals(1, presses)

            // In the field the space is typed.
            tab()
            space()
            assertEquals(" ", typed)
            assertEquals(1, plays)

            // Back on the box under the keyboard, Space presses it.
            tab(shift = true)
            space()
            assertEquals(2, presses)
            assertEquals(1, plays)
        } finally {
            scene.close()
        }
    }
}
