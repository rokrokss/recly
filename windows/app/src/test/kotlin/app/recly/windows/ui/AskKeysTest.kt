@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package app.recly.windows.ui

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import app.recly.windows.i18n.StringTable
import app.recly.windows.ui.theme.ReclyDesktopTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import recly.core.chatgpt.AskState

/** docs/09 "Ask" (2026-10-10): in the question, Enter asks and Shift+Enter starts a new line where the cursor is. */
class AskKeysTest {

    @Test
    fun `Enter asks the question, Shift+Enter adds a line to it`() {
        val asked = mutableListOf<String?>()
        var field = Rect.Zero
        val scene = ImageComposeScene(460, 400, Density(1f)) {
            ReclyDesktopTheme(dark = false, highContrast = false) {
                Column(Modifier.onGloballyPositioned { field = it.boundsInRoot() }) {
                    AskPanel(
                        AskState.None, emptyList(), { _, _ -> "" }, canPlay = true, onPlay = {},
                        onAsk = { _, question -> asked += question }, onManageUsage = {}, strings = StringTable.of(StringTable.BASE),
                    )
                }
            }
        }
        fun type(text: String) {
            val source = java.awt.Canvas()
            text.forEach { char ->
                val awt = java.awt.event.KeyEvent(source, java.awt.event.KeyEvent.KEY_TYPED, 0L, 0, java.awt.event.KeyEvent.VK_UNDEFINED, char)
                scene.sendKeyEvent(KeyEvent(Key.Unknown, KeyEventType.Unknown, codePoint = char.code, nativeEvent = awt))
            }
            scene.render()
        }
        fun enter(shift: Boolean) {
            val source = java.awt.Canvas()
            val mask = if (shift) java.awt.event.InputEvent.SHIFT_DOWN_MASK else 0
            for ((type, kind) in listOf(java.awt.event.KeyEvent.KEY_PRESSED to KeyEventType.KeyDown, java.awt.event.KeyEvent.KEY_RELEASED to KeyEventType.KeyUp)) {
                val awt = java.awt.event.KeyEvent(source, type, 0L, mask, java.awt.event.KeyEvent.VK_ENTER, java.awt.event.KeyEvent.CHAR_UNDEFINED)
                scene.sendKeyEvent(KeyEvent(Key.Enter, kind, isShiftPressed = shift, nativeEvent = awt))
            }
            scene.render()
        }
        try {
            scene.render()
            // Into the question's box, under its label.
            val at = Offset(field.left + 40f, field.top + 30f)
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary)
            scene.render()
            type("When")
            enter(shift = true)
            type("who?")
            assertEquals(emptyList(), asked, "Shift+Enter asked")
            enter(shift = false)
            assertEquals(listOf<String?>("When\nwho?"), asked)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `a line break replaces what is selected and leaves the cursor after it`() {
        assertEquals(TextFieldValue("ab\n", TextRange(3)), insertLineBreak(TextFieldValue("ab", TextRange(2))))
        assertEquals(TextFieldValue("a\nd", TextRange(2)), insertLineBreak(TextFieldValue("abcd", TextRange(1, 3))))
        assertEquals(TextFieldValue("a\nd", TextRange(2)), insertLineBreak(TextFieldValue("abcd", TextRange(3, 1))))
    }
}
