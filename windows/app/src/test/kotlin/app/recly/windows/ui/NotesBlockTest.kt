@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package app.recly.windows.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.StringTable
import app.recly.windows.ui.theme.ReclyDesktopTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import recly.core.chatgpt.SummaryState
import recly.core.notes.NoteItem
import recly.core.notes.NoteKind
import recly.core.notes.NoteLine
import recly.core.notes.NotesStage
import recly.core.notes.RecordingNotes

/**
 * docs/08 "Organizing" on the PC: the notes block in the Summary view — the groups it shows and hides, what a row
 * says, which line under an opened row is the note's, and the row's click, time and context menu.
 */
class NotesBlockTest {

    private fun item(id: String, kind: NoteKind, atSec: Double = 75.0, owner: String? = null, due: String? = null, quote: String = "") =
        NoteItem(id, kind, "Send the quote", owner, due, atSec, quote, provisional = false)

    private fun notes(
        overview: String? = null,
        decisions: List<NoteItem> = emptyList(),
        todos: List<NoteItem> = emptyList(),
        questions: List<NoteItem> = emptyList(),
    ) = RecordingNotes("rec-1", NotesStage.FINAL, overview, decisions, todos, questions, organizing = false)

    private fun line(atSec: Double, text: String, name: String? = null, me: Boolean = false) =
        NoteLine(atSec, atSec + 4, "S1", name, me, text, provisional = false)

    @Test
    fun `the groups come in their order, and a group with nothing is not there`() {
        val decision = item("d", NoteKind.DECISION)
        val question = item("q", NoteKind.QUESTION)

        assertEquals(
            listOf(Str.NOTES_DECISIONS to listOf(decision), Str.NOTES_QUESTIONS to listOf(question)),
            noteGroups(notes(decisions = listOf(decision), questions = listOf(question))),
        )
        assertEquals(emptyList(), noteGroups(notes(overview = "We met.")))
    }

    @Test
    fun `nothing at all is drawn for notes that are empty`() {
        var height = -1
        val scene = ImageComposeScene(400, 300, Density(1f)) {
            ReclyDesktopTheme(dark = false, highContrast = false) {
                Box(Modifier.onGloballyPositioned { height = it.size.height }) {
                    NotesBlock(notes(), canPlay = true, onPlay = {}, linesAround = { emptyList() }, onDelete = {}, strings = StringTable.of(StringTable.BASE))
                }
            }
        }
        try {
            scene.render()
            assertEquals(0, height)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `a row says the text, then its owner, then its deadline`() {
        assertEquals("Send the quote", noteText(item("a", NoteKind.TODO), Color.Gray).text)
        assertEquals("Send the quote — Minji", noteText(item("a", NoteKind.TODO, owner = "Minji"), Color.Gray).text)
        assertEquals("Send the quote — Minji · Friday", noteText(item("a", NoteKind.TODO, owner = "Minji", due = "Friday"), Color.Gray).text)
        assertEquals("Send the quote · Friday", noteText(item("a", NoteKind.TODO, due = "Friday"), Color.Gray).text)
    }

    @Test
    fun `a line under an opened row names who said it only when they have a name`() {
        assertEquals("Minji  Hello", noteLineText(line(1.0, "Hello", name = "Minji"), "Me", Color.Gray).text)
        assertEquals("Me  Hello", noteLineText(line(1.0, "Hello", me = true), "Me", Color.Gray).text)
        assertEquals("Hello", noteLineText(line(1.0, "Hello"), "Me", Color.Gray).text)
    }

    @Test
    fun `the note's line is the one its quote is in, else the one its time is on`() {
        val lines = listOf(
            line(60.4, "We should send it."),
            line(75.6, "Okay, I'll send the quote by Friday."),
            line(82.0, "Great, send the quote then."),
        )
        // The quote, case and punctuation aside — and of two lines that have it, the one nearest the time.
        assertEquals(1, notedLine(lines, 75.0, "send the Quote"))
        assertEquals(2, notedLine(lines, 82.0, "send the quote"))
        // No quote to find: the line the `[00:01:15]` was read from, whose start is 75.6.
        assertEquals(1, notedLine(lines, 75.0, "not said here"))
        assertEquals(1, notedLine(lines, 75.0, ""))
        assertNull(notedLine(lines, 10.0, ""))
        assertNull(notedLine(emptyList(), 10.0, "x"))
    }

    @Test
    fun `the Summary chips are there for notes alone`() {
        assertTrue(showsSummaryChips(SummaryState.None, summaryChosen = false, editing = false, notes = true))
        assertFalse(showsSummaryChips(SummaryState.None, summaryChosen = false, editing = true, notes = true))
        assertFalse(showsSummaryChips(SummaryState.None, summaryChosen = false, editing = false, notes = false))
    }

    @Test
    fun `the menu key and Shift+F10 open a row's menu`() {
        assertTrue(opensContextMenu(Key.Menu, shift = false))
        assertTrue(opensContextMenu(Key.F10, shift = true))
        assertFalse(opensContextMenu(Key.F10, shift = false))
        assertFalse(opensContextMenu(Key.Enter, shift = true))
    }

    /**
     * A click opens the row and asks for the lines around its time; the time plays from there; a right click opens
     * the menu and not the row, and its Delete deletes without asking.
     */
    @Test
    fun `a row opens on a click, plays from its time, and deletes from its context menu`() {
        val note = item("n1", NoteKind.TODO, atSec = 75.0)
        val asked = mutableListOf<Double>()
        val played = mutableListOf<Double>()
        val deleted = mutableListOf<String>()
        var toggles = 0
        var expanded by mutableStateOf(false)
        val strings = StringTable.of(StringTable.BASE)
        val scene = ImageComposeScene(WIDTH, 400, Density(1f)) {
            ReclyDesktopTheme(dark = false, highContrast = false) {
                Column {
                    NoteRow(
                        item = note,
                        expanded = expanded,
                        onToggle = {
                            toggles++
                            expanded = !expanded
                        },
                        playable = true,
                        onPlay = { played += it },
                        linesAround = { at -> asked += at; listOf(line(74.0, "I'll send the quote")) },
                        onDelete = { deleted += it.id },
                        strings = strings,
                        spanSec = 600.0,
                    )
                }
            }
        }
        fun click(at: Offset, button: PointerButton = PointerButton.Primary) {
            val down = if (button == PointerButton.Secondary) PointerButtons(isSecondaryPressed = true) else PointerButtons(isPrimaryPressed = true)
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at, buttons = down, button = button)
            scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = button)
            scene.render()
            scene.render()
        }
        try {
            scene.render()
            // On the text: the row opens and asks for its lines once.
            click(Offset(40f, ROW_MIDDLE))
            assertEquals(1, toggles)
            assertEquals(listOf(75.0), asked)
            // Again: it closes.
            click(Offset(40f, ROW_MIDDLE))
            assertEquals(2, toggles)
            assertFalse(expanded)
            // The time at the row's end plays, and is not the row's click.
            click(Offset(WIDTH - 6f, ROW_MIDDLE))
            assertEquals(listOf(75.0), played)
            assertEquals(2, toggles)
            // A right click: the menu, not the row.
            val at = Offset(60f, ROW_MIDDLE)
            click(at, PointerButton.Secondary)
            assertEquals(2, toggles, "the right click opened the row")
            assertEquals(emptyList(), deleted)
            // Delete is the menu's one row, hung from the click.
            click(Offset(at.x + 30f, at.y + 22f))
            assertEquals(listOf("n1"), deleted)
            assertEquals(2, toggles)
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val WIDTH = 500

        /** The row is the time button's 44 and 4 above and below it. */
        const val ROW_MIDDLE = 26f
    }
}
