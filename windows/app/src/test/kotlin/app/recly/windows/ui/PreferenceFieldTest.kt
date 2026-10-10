@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package app.recly.windows.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import app.recly.windows.ui.theme.ReclyDesktopTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * docs/08 "Summaries": Settings → My format and About you have no Save — what was typed is saved when the editing
 * ends: the field left for another, Return in the one-line field, the window put behind another, the window closed.
 */
class PreferenceFieldTest {

    @Test
    fun `a preference is saved when its editing ends, and not while it is typed`() {
        val custom = mutableListOf<String>()
        val about = mutableListOf<String>()
        var windowFocused by mutableStateOf(true)
        var first = Rect.Zero
        var second = Rect.Zero
        val window = object : WindowInfo {
            override val isWindowFocused: Boolean get() = windowFocused
            override val containerSize: IntSize get() = IntSize(600, 600)
        }
        val scene = ImageComposeScene(600, 600, Density(1f)) {
            CompositionLocalProvider(LocalWindowInfo provides window) {
                ReclyDesktopTheme(dark = false, highContrast = false) {
                    Column {
                        Column(Modifier.onGloballyPositioned { first = it.boundsInRoot() }) {
                            PreferenceField("", { custom += it }, "My format", "Sections", "Used when", max = 10, singleLine = false)
                        }
                        Column(Modifier.onGloballyPositioned { second = it.boundsInRoot() }) {
                            PreferenceField("", { about += it }, "About you", "e.g.", "Summaries use this", max = 300, singleLine = true)
                        }
                    }
                }
            }
        }
        try {
            scene.render()
            fun click(at: Offset) {
                scene.sendPointerEvent(PointerEventType.Move, at)
                scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary)
                scene.render()
            }
            fun type(text: String) {
                val source = java.awt.Canvas()
                text.forEach { char ->
                    val awt = java.awt.event.KeyEvent(source, java.awt.event.KeyEvent.KEY_TYPED, 0L, 0, java.awt.event.KeyEvent.VK_UNDEFINED, char)
                    scene.sendKeyEvent(KeyEvent(Key.Unknown, KeyEventType.Unknown, codePoint = char.code, nativeEvent = awt))
                }
                scene.render()
            }
            fun press(key: Key, code: Int) {
                val source = java.awt.Canvas()
                for ((type, kind) in listOf(java.awt.event.KeyEvent.KEY_PRESSED to KeyEventType.KeyDown, java.awt.event.KeyEvent.KEY_RELEASED to KeyEventType.KeyUp)) {
                    val awt = java.awt.event.KeyEvent(source, type, 0L, 0, code, java.awt.event.KeyEvent.CHAR_UNDEFINED)
                    scene.sendKeyEvent(KeyEvent(key, kind, nativeEvent = awt))
                }
                scene.render()
            }
            // Below the field's label, in its box.
            click(Offset(first.left + 40f, first.top + 40f))
            type("Risks, owners and more")
            assertEquals(emptyList(), custom.filter { it.isNotEmpty() }, "saved while it was typed")
            // Into the next field: My format is left, kept to its 10 characters.
            click(Offset(second.left + 40f, second.top + 30f))
            assertEquals("Risks, own", custom.last())
            type("PM")
            press(Key.Enter, java.awt.event.KeyEvent.VK_ENTER)
            assertEquals("PM", about.last(), "Return in the one-line field")
            // Back in at the end of the text (elsewhere than before: twice in one place is a double click, which
            // selects the word), a change, and the window put behind another.
            click(Offset(second.left + 200f, second.top + 30f))
            type("!")
            windowFocused = false
            scene.render()
            assertEquals("PM!", about.last(), "the window behind another")
        } finally {
            scene.close()
        }
    }
}
