@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package app.recly.windows.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.unit.Density
import app.recly.windows.i18n.StringTable
import app.recly.windows.i18n.WORD_JOINER
import app.recly.windows.ui.theme.ReclyDesktopTheme
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import recly.core.notes.NoteEdit
import recly.core.notes.NoteItem
import recly.core.notes.NoteKind
import recly.core.notes.NoteNames
import recly.core.notes.NoteSectionItems
import recly.core.notes.NotesStage
import recly.core.notes.RecordingNotes

/**
 * docs/08 "Organizing" on the PC: the notes block in the Notes chip's body — the groups it shows and hides, what a row
 * says, its selection, and the row's time and context menu; and what Edit notes sends when it is saved.
 */
class NotesBlockTest {

    private fun item(id: String, kind: NoteKind, atSec: Double? = 75.0, owner: String? = null, due: String? = null, quote: String = "") =
        NoteItem(id, kind, "Send the quote", owner, due, atSec, quote, provisional = false)

    private val base = StringTable.of(StringTable.BASE)

    private fun notes(
        overview: String? = null,
        decisions: List<NoteItem> = emptyList(),
        todos: List<NoteItem> = emptyList(),
        questions: List<NoteItem> = emptyList(),
        points: List<NoteItem> = emptyList(),
        sections: List<NoteSectionItems> = emptyList(),
    ) = RecordingNotes("rec-1", NotesStage.FINAL, overview, decisions, todos, questions, organizing = false, points = points, sections = sections)

    @Test
    fun `the groups come in their order, the user's sections last, and a group with nothing is not there`() {
        val point = item("p", NoteKind.POINT)
        val decision = item("d", NoteKind.DECISION)
        val question = item("q", NoteKind.QUESTION)
        val risk = item("r", NoteKind.SECTION).copy(section = "s1")

        assertEquals(
            listOf("Decisions" to listOf(decision), "Key points" to listOf(point), "Questions" to listOf(question), "Risks" to listOf(risk)),
            noteGroups(
                notes(
                    points = listOf(point),
                    decisions = listOf(decision),
                    questions = listOf(question),
                    sections = listOf(NoteSectionItems("s1", "Risks", listOf(risk)), NoteSectionItems("s2", "Budget", emptyList())),
                ),
                base,
            ),
        )
        assertEquals(emptyList(), noteGroups(notes(overview = "We met."), base))
    }

    @Test
    fun `a built-in group the user renamed is headed by that name, the others by the app's word`() {
        val decision = item("d", NoteKind.DECISION)
        val todo = item("t", NoteKind.TODO)
        assertEquals(
            listOf("Agreed" to listOf(decision), "To-dos" to listOf(todo)),
            noteGroups(notes(decisions = listOf(decision), todos = listOf(todo)).copy(names = NoteNames(decisions = "Agreed")), base),
        )
    }

    @Test
    fun `Edit notes sends only what changed, trimmed, and an emptied item to be deleted`() {
        val decision = item("d", NoteKind.DECISION).copy(text = "Ship on Friday")
        val todo = item("t", NoteKind.TODO)
        val draft = NotesDraft("rec-1", notes(overview = "We met.", decisions = listOf(decision), todos = listOf(todo)))
        assertFalse(draft.changed)

        draft.overview = " We met. "
        draft.texts["d"] = "Ship on Friday "
        assertFalse(draft.changed, "whitespace alone is no edit")

        draft.overview = "We met on Monday."
        draft.texts["d"] = " Ship on Monday"
        draft.texts["t"] = "  "
        assertEquals("We met on Monday.", draft.overviewEdit)
        assertEquals(listOf(NoteEdit("d", "Ship on Monday"), NoteEdit("t", "")), draft.itemEdits)
        assertTrue(draft.changed)

        draft.overview = ""
        assertEquals("", draft.overviewEdit, "an emptied overview is removed")
    }

    @Test
    fun `the headings handed to the core are the language's words without the joiners drawing puts in`() {
        val korean = notesHeadings(StringTable.of(StringTable.KOREAN))
        assertEquals("요약", korean.notes)
        assertEquals("핵심", korean.keyPoints)
        assertEquals("나", korean.me, "an unnamed `me` owns their to-dos under this word")
        assertFalse(korean.toString().contains(WORD_JOINER))
        assertEquals("Key points", notesHeadings(base).keyPoints)
    }

    @Test
    fun `nothing at all is drawn for notes that are empty`() {
        var height = -1
        val scene = ImageComposeScene(400, 300, Density(1f)) {
            ReclyDesktopTheme(dark = false, highContrast = false) {
                Box(Modifier.onGloballyPositioned { height = it.size.height }) {
                    NotesBlock(notes(), canSeek = true, onSeek = {}, onDelete = {}, strings = StringTable.of(StringTable.BASE))
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
    fun `a row says the text, then its owner, then its deadline, then its time`() {
        fun text(owner: String? = null, due: String? = null) = noteText(item("a", NoteKind.TODO, owner = owner, due = due), "01:15", Color.Gray).text
        assertEquals("Send the quote\u00A001:15", text())
        assertEquals("Send the quote —\u00A0Minji\u00A001:15", text(owner = "Minji"))
        assertEquals("Send the quote —\u00A0Minji\u00A0·\u00A0Friday\u00A001:15", text(owner = "Minji", due = "Friday"))
        assertEquals("Send the quote ·\u00A0Friday\u00A001:15", text(due = "Friday"))
    }

    @Test
    fun `a row with no time ends at its words, with no space left for one`() {
        assertEquals("Send the quote", noteText(item("a", NoteKind.POINT, atSec = null), null, Color.Gray).text)
    }

    @Test
    fun `the menu key and Shift+F10 open a row's menu`() {
        assertTrue(opensContextMenu(Key.Menu, shift = false))
        assertTrue(opensContextMenu(Key.F10, shift = true))
        assertFalse(opensContextMenu(Key.F10, shift = false))
        assertFalse(opensContextMenu(Key.Enter, shift = true))
    }

    /**
     * The time moves the playhead there, the transcript's seek and not the answer's play (2026-10-28); a click anywhere
     * else does nothing; a right click opens the menu, whose Delete deletes without asking.
     */
    @Test
    fun `a row seeks to its time and deletes from its context menu`() {
        val note = item("n1", NoteKind.TODO, atSec = 75.0)
        val seeks = mutableListOf<Double>()
        val deleted = mutableListOf<String>()
        val scene = block(notes(todos = listOf(note)), onSeek = { seeks += it }, onDelete = { deleted += it.id })
        try {
            scene.render()
            val row = ROW_MIDDLE
            click(scene, Offset(40f, row))
            click(scene, Offset(WIDTH - 6f, row))
            assertEquals(emptyList(), seeks)
            // The time follows the words: found as a press finds it.
            val time = (WIDTH - 6 downTo 40 step 4).first { x ->
                click(scene, Offset(x.toFloat(), row))
                seeks.isNotEmpty()
            }.toFloat()
            assertEquals(listOf(75.0), seeks)
            // Its target is the touch minimum though the row is shorter: a press above the row's words still seeks.
            click(scene, Offset(time - 8f, row - 18f))
            assertEquals(listOf(75.0, 75.0), seeks)
            // A right click: the menu. Delete is its one row, hung from the click.
            val at = Offset(60f, row)
            click(scene, at, PointerButton.Secondary)
            assertEquals(emptyList(), deleted)
            click(scene, Offset(at.x + 30f, at.y + 22f))
            assertEquals(listOf("n1"), deleted)
        } finally {
            scene.close()
        }
    }

    /** An item no single line carries: nothing to seek to, and its context menu still deletes it. */
    @Test
    fun `a row with no time seeks nowhere and deletes from its context menu`() {
        val deleted = mutableListOf<String>()
        val scene = block(
            notes(points = listOf(item("n1", NoteKind.POINT, atSec = null))),
            onSeek = { error("there is no time to seek to") },
            onDelete = { deleted += it.id },
        )
        try {
            scene.render()
            val row = ROW_MIDDLE
            click(scene, Offset(40f, row))
            click(scene, Offset(WIDTH - 6f, row))
            val at = Offset(60f, row)
            click(scene, at, PointerButton.Secondary)
            click(scene, Offset(at.x + 30f, at.y + 22f))
            assertEquals(listOf("n1"), deleted)
        } finally {
            scene.close()
        }
    }

    /** A drag selects across the rows, the group's header among them, and the copy key takes what is selected. */
    @Test
    fun `a drag selects the notes across their rows and the copy key copies them`() {
        var copied: String? = null
        val clipboard = object : Clipboard {
            override suspend fun getClipEntry(): ClipEntry? = null
            override suspend fun setClipEntry(clipEntry: ClipEntry?) {
                copied = (clipEntry?.nativeClipEntry as? Transferable)?.getTransferData(DataFlavor.stringFlavor) as? String
            }
            override val nativeClipboard: NativeClipboard get() = error("not asked for")
        }
        val first = item("d1", NoteKind.DECISION, atSec = null).copy(text = "Ship on Friday")
        val second = item("d2", NoteKind.DECISION, atSec = 75.0).copy(text = "Book the room")
        val scene = ImageComposeScene(WIDTH, 400, Density(1f)) {
            ReclyDesktopTheme(dark = false, highContrast = false) {
                CompositionLocalProvider(LocalClipboard provides clipboard) {
                    NotesBlock(notes(decisions = listOf(first, second)), canSeek = true, onSeek = {}, onDelete = {}, strings = base, spanSec = 600.0)
                }
            }
        }
        try {
            scene.render()
            val top = ROW_MIDDLE
            val press = Offset(2f, top)
            scene.sendPointerEvent(PointerEventType.Move, press)
            scene.sendPointerEvent(PointerEventType.Press, press, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.render()
            for (y in top.toInt()..(top.toInt() + 60) step 6) {
                scene.sendPointerEvent(PointerEventType.Move, Offset(WIDTH - 4f, y.toFloat()), buttons = PointerButtons(isPrimaryPressed = true))
                scene.render()
            }
            val end = Offset(WIDTH - 4f, top + 60f)
            scene.sendPointerEvent(PointerEventType.Release, end, buttons = PointerButtons(), button = PointerButton.Primary)
            scene.render()
            val mac = System.getProperty("os.name").startsWith("Mac")
            scene.sendKeyEvent(KeyEvent(Key.C, KeyEventType.KeyDown, isCtrlPressed = !mac, isMetaPressed = mac))
            scene.render()
            assertEquals("Ship on Friday\nBook the room\u00A001:15", copied)
        } finally {
            scene.close()
        }
    }

    private fun block(notes: RecordingNotes, onSeek: (Double) -> Unit, onDelete: (NoteItem) -> Unit) =
        ImageComposeScene(WIDTH, 400, Density(1f)) {
            ReclyDesktopTheme(dark = false, highContrast = false) {
                Column {
                    NotesBlock(notes, canSeek = true, onSeek = onSeek, onDelete = onDelete, strings = base, spanSec = 600.0)
                }
            }
        }

    private fun click(scene: ImageComposeScene, at: Offset, button: PointerButton = PointerButton.Primary) {
        val down = if (button == PointerButton.Secondary) PointerButtons(isSecondaryPressed = true) else PointerButtons(isPrimaryPressed = true)
        scene.sendPointerEvent(PointerEventType.Move, at)
        scene.sendPointerEvent(PointerEventType.Press, at, buttons = down, button = button)
        scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = button)
        scene.render()
        scene.render()
    }

    private companion object {
        const val WIDTH = 500

        /** A group's header, then its first row. */
        const val ROW_MIDDLE = 52f
    }
}
