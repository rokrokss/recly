@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package app.recly.windows.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.Density
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.StringTable
import app.recly.windows.i18n.UiMessage
import app.recly.windows.ui.theme.ReclyDesktopTheme
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import recly.core.notes.NotesStage
import recly.core.notes.RecordingNotes
import recly.core.recording.NotesMatch
import recly.core.recording.SearchHit
import recly.core.recording.SearchRange
import recly.core.recording.SearchSnippet

/** docs/08 "Memo": where the detail shows a recording's memo, what the tray's row says of it, and how its field saves. */
class MemoTest {
    private val base = StringTable.of(StringTable.BASE)
    private val noNotes = RecordingNotes("r1", NotesStage.FINAL, null, emptyList(), emptyList(), emptyList(), organizing = false)

    @Test
    fun `the chips are there for a memo or its view, the Memo chip stays while chosen, and never over the transcript's editor`() {
        assertFalse(showsDetailChips(noNotes, notesChosen = false, memo = "", memoChosen = false, editing = false))
        assertFalse(showsDetailChips(null, notesChosen = false, memo = " \n ", memoChosen = false, editing = false), "a blank memo is none")
        assertTrue(showsDetailChips(null, notesChosen = false, memo = "Agenda", memoChosen = false, editing = false))
        assertTrue(showsDetailChips(null, notesChosen = false, memo = "", memoChosen = true, editing = false))
        assertTrue(showsDetailChips(null, notesChosen = true, memo = null, memoChosen = false, editing = false))
        assertFalse(showsDetailChips(null, notesChosen = false, memo = "Agenda", memoChosen = false, editing = true))

        assertFalse(showsMemoChip(null, memoChosen = false))
        assertTrue(showsMemoChip("Agenda", memoChosen = false))
        assertTrue(showsMemoChip("", memoChosen = true), "emptied while on it: the view stays")
        assertFalse(hasMemo(""))
        assertTrue(hasMemo(" a "))

        val detail = RecordingDetail("r1", UiMessage.Text("Weekly"))
        assertEquals(DetailView.TRANSCRIPT, detail.view)
        assertEquals(DetailView.NOTES, detail.copy(showingNotes = true).view)
        assertEquals(DetailView.MEMO, detail.copy(showingMemo = true).view)
    }

    @Test
    fun `a hit found in the memo alone opens on the memo, with Notes in front of its line`() {
        val match = NotesMatch("Ask Mina about pricing", listOf(SearchRange(4, 4)))
        fun hit(title: Boolean = false, snippets: Boolean = false, notes: NotesMatch? = null, memo: NotesMatch? = match) = SearchHit(
            "r1", "Weekly", "2026-10-09T10:00:00Z", title, if (title) listOf(SearchRange(0, 6)) else emptyList(),
            if (snippets) listOf(SearchSnippet(31.0, "Mina said", listOf(SearchRange(0, 4)))) else emptyList(), notes, memo,
        )
        assertTrue(opensOnMemo(hit()))
        assertFalse(opensOnMemo(hit(title = true)))
        assertFalse(opensOnMemo(hit(snippets = true)))
        assertFalse(opensOnMemo(hit(notes = match)), "found in the notes as well: the notes")
        assertTrue(opensOnNotes(hit(notes = match)))
        assertFalse(opensOnMemo(hit(memo = null)))

        val line = notesSnippet(base[Str.MEMO_TITLE], match)
        assertEquals("Notes · Ask Mina about pricing", line.text)
        assertEquals("Mina", line.text.substring(line.ranges.single().offset, line.ranges.single().offset + line.ranges.single().length))
    }

    @Test
    fun `the tray's row shows the memo's first line that says something`() {
        assertNull(memoPreview(null))
        assertNull(memoPreview(""))
        assertNull(memoPreview(" \n\t\n"))
        assertEquals("Agenda: launch", memoPreview("\n  Agenda: launch  \nMina, Joon"))
    }

    @Test
    fun `the memo saves after the typing stops and when the field goes, and the core's value replaces only what is saved`() {
        val saves = CopyOnWriteArrayList<String>()
        var stored by mutableStateOf<String?>("")
        var shown by mutableStateOf(true)
        var box = Rect.Zero
        val scene = ImageComposeScene(600, 400, Density(1f)) {
            ReclyDesktopTheme(dark = false, highContrast = false) {
                Column(Modifier.onGloballyPositioned { box = it.boundsInRoot() }) {
                    if (shown) MemoField("r1", stored, { saves += it }, agreed = true, strings = base, modifier = Modifier.fillMaxWidth())
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
            fun settle() {
                Thread.sleep(SAVE_AFTER_MS + 500)
                scene.render()
            }
            click(Offset(box.left + 40f, box.top + 20f))
            type("Agenda")
            assertEquals(emptyList(), saves.toList(), "not while it is typed")
            // Another device's memo while the typing waits: what was typed wins, and is what goes.
            stored = "Theirs"
            scene.render()
            settle()
            assertEquals(listOf("Agenda"), saves.toList())

            // Its own save comes back, and then another device's: nothing typed waits, so that one is shown.
            stored = "Agenda"
            scene.render()
            stored = "From the phone"
            // One frame composes the change, and the next runs the effect that takes it.
            scene.render()
            scene.render()
            // At the end of the line, a change, and the field leaves before the wait is over.
            press(Key.MoveEnd, java.awt.event.KeyEvent.VK_END)
            type("!")
            shown = false
            scene.render()
            assertEquals(listOf("Agenda", "From the phone!"), saves.toList())
            settle()
            assertEquals(2, saves.size, "saved once, when it left")
        } finally {
            scene.close()
        }
    }
}
