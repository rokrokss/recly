@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package app.recly.windows.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import app.recly.windows.i18n.StringTable
import app.recly.windows.ui.theme.ReclyDesktopTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import recly.core.chatgpt.AskPreset

/**
 * docs/09 "Ask" (2026-10-10): in the composer, Enter sends and Shift+Enter starts a new line where the cursor is; sending
 * empties the field, and an empty one sends nothing. With the cursor in an empty field the presets are chips over it, and
 * a chip asks at once.
 */
class AskKeysTest {

    private val strings = StringTable.of(StringTable.BASE)

    @Test
    fun `Enter sends the request and empties the field, Shift+Enter adds a line to it`() = composer { scene, asked, field ->
        // Nothing typed: Enter sends nothing.
        scene.enter(shift = false)
        assertEquals(emptyList(), asked)
        scene.type("When")
        scene.enter(shift = true)
        scene.type("who?")
        assertEquals(emptyList(), asked, "Shift+Enter sent")
        scene.enter(shift = false)
        assertEquals(listOf<Pair<AskPreset?, String?>>(null to "When\nwho?"), asked)
        assertEquals("", field().text, "sending empties the field")
    }

    @Test
    fun `with the cursor in the empty field the presets are chips, and one asks at once`() = composer { scene, asked, _ ->
        // The chips sit over the field once it has the cursor: the first one, at the composer's top left.
        scene.click(Offset(chips.left + 20f, chips.top + 20f))
        assertEquals(listOf<Pair<AskPreset?, String?>>(AskPreset.ACTION_ITEMS to null), asked)
    }

    @Test
    fun `a line break replaces what is selected and leaves the cursor after it`() {
        assertEquals(TextFieldValue("ab\n", TextRange(3)), insertLineBreak(TextFieldValue("ab", TextRange(2))))
        assertEquals(TextFieldValue("a\nd", TextRange(2)), insertLineBreak(TextFieldValue("abcd", TextRange(1, 3))))
        assertEquals(TextFieldValue("a\nd", TextRange(2)), insertLineBreak(TextFieldValue("abcd", TextRange(3, 1))))
    }

    /** Where the composer was drawn once the field had the cursor — the chips are at its top. */
    private var chips = Rect.Zero

    /** The composer over nothing running, the cursor put in its field; what it asked, and the field as it is now. */
    private fun composer(body: (ImageComposeScene, List<Pair<AskPreset?, String?>>, () -> TextFieldValue) -> Unit) {
        val asked = mutableListOf<Pair<AskPreset?, String?>>()
        var bounds = Rect.Zero
        var current = TextFieldValue("")
        val scene = ImageComposeScene(460, 400, Density(1f)) {
            ReclyDesktopTheme(dark = false, highContrast = false) {
                var field by remember { mutableStateOf(TextFieldValue("")) }
                current = field
                Column(Modifier.onGloballyPositioned { bounds = it.boundsInRoot() }) {
                    AskComposer(
                        field, { field = it }, listOf(AskPreset.ACTION_ITEMS, AskPreset.OPEN_QUESTIONS), reason = null, running = false,
                        onAsk = { preset, question -> asked += preset to question }, strings = strings,
                    )
                }
            }
        }
        try {
            scene.render()
            // Into the field: the composer is the field alone until it has the cursor.
            scene.click(Offset(bounds.left + 40f, bounds.top + 20f))
            chips = bounds
            body(scene, asked) { current }
        } finally {
            scene.close()
        }
    }

    private fun ImageComposeScene.click(at: Offset) {
        sendPointerEvent(PointerEventType.Move, at)
        sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary)
        render()
    }

    private fun ImageComposeScene.type(text: String) {
        val source = java.awt.Canvas()
        text.forEach { char ->
            val awt = java.awt.event.KeyEvent(source, java.awt.event.KeyEvent.KEY_TYPED, 0L, 0, java.awt.event.KeyEvent.VK_UNDEFINED, char)
            sendKeyEvent(KeyEvent(Key.Unknown, KeyEventType.Unknown, codePoint = char.code, nativeEvent = awt))
        }
        render()
    }

    private fun ImageComposeScene.enter(shift: Boolean) {
        val source = java.awt.Canvas()
        val mask = if (shift) java.awt.event.InputEvent.SHIFT_DOWN_MASK else 0
        for ((type, kind) in listOf(java.awt.event.KeyEvent.KEY_PRESSED to KeyEventType.KeyDown, java.awt.event.KeyEvent.KEY_RELEASED to KeyEventType.KeyUp)) {
            val awt = java.awt.event.KeyEvent(source, type, 0L, mask, java.awt.event.KeyEvent.VK_ENTER, java.awt.event.KeyEvent.CHAR_UNDEFINED)
            sendKeyEvent(KeyEvent(Key.Enter, kind, isShiftPressed = shift, nativeEvent = awt))
        }
        render()
    }
}
