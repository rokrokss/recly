@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, ExperimentalComposeUiApi::class)

package app.recly.windows.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
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
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import app.recly.windows.i18n.AppLanguage
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.StringTable
import app.recly.windows.ui.component.BlueprintButton
import app.recly.windows.ui.component.ButtonTone
import app.recly.windows.ui.component.ListHeader
import app.recly.windows.ui.component.SidebarWidth
import app.recly.windows.ui.theme.MinTouch
import app.recly.windows.ui.theme.ReclyDesktopTheme
import app.recly.windows.ui.theme.blueprint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import recly.core.notes.NoteItem
import recly.core.notes.NoteKind
import recly.core.notes.NotesStage
import recly.core.notes.RecordingNotes
import recly.core.notes.RecordingTodos
import recly.core.notes.TodoGroups

/**
 * docs/09 "To-dos view" on the PC: what the view holds until it is left, what its count says, the box and the rest of a row
 * under a click and the keyboard, what a reader hears, the box in the detail and not in the editor, and the header that
 * fits every language.
 */
class TodosViewTest {

    private fun todo(id: String, done: Boolean = false, kind: NoteKind = NoteKind.TODO) =
        NoteItem(id, kind, "Send the quote", null, null, 75.0, "", provisional = false, done = done)

    private fun group(id: String, vararg items: NoteItem) = RecordingTodos(id, "Weekly sync", "2026-11-06T10:00:00Z", items.toList())

    private fun TodoGroups.openIds() = open.flatMap { it.items }.map { it.id }

    private fun TodoGroups.doneIds() = done.flatMap { it.items }.map { it.id }

    @Test
    fun `a row checked in the view stays where it is until the view is left`() {
        val view = TodosViewState()
        view.open()
        view.checking("a", done = true)
        val checked = listOf(group("r1", todo("a", done = true), todo("b")), group("r2", todo("c", done = true)))

        val shown = TodoGroups(checked, view.held)
        assertEquals(listOf("a", "b"), shown.openIds())
        assertEquals(listOf("c"), shown.doneIds())
        assertEquals(1, openTodoCount(shown), "a held row is no longer open, though it stays in its place")

        view.toggleDone()
        view.leave()
        assertFalse(view.shown)
        assertFalse(view.doneOpen)
        val after = TodoGroups(checked, view.held)
        assertEquals(listOf("b"), after.openIds())
        assertEquals(listOf("a", "c"), after.doneIds())
    }

    @Test
    fun `only a check is held, and only while the view is shown`() {
        val view = TodosViewState()
        view.checking("a", done = true)
        assertEquals(emptySet(), view.held)
        view.open()
        view.checking("a", done = false)
        assertEquals(emptySet(), view.held)
        view.checking("a", done = true)
        assertEquals(setOf("a"), view.held)
    }

    @Test
    fun `the box checks and unchecks, and a click anywhere else on the row opens the recording`() {
        var item by mutableStateOf(todo("a"))
        val marks = mutableListOf<Boolean>()
        var opens = 0
        scene(
            {
                TodoRow(
                    item,
                    onDone = {
                        marks += it
                        item = item.copy(done = it)
                    },
                    onOpen = { opens++ },
                )
            },
        ) { scene ->
            scene.click(Offset(BOX_MIDDLE, ROW_MIDDLE))
            assertEquals(listOf(true), marks)
            assertEquals(0, opens)
            scene.click(Offset(BOX_MIDDLE, ROW_MIDDLE))
            assertEquals(listOf(true, false), marks)
            assertEquals(0, opens)
            // On the words' first letter, well into them, and past the end of them.
            listOf(Offset(WORDS_START, ROW_MIDDLE), Offset(WORDS, ROW_MIDDLE), Offset(WIDTH - 4f, ROW_MIDDLE)).forEachIndexed { index, at ->
                scene.click(at)
                assertEquals(index + 1, opens)
            }
            assertEquals(listOf(true, false), marks)
        }
    }

    /** As `BlueprintButton`'s `tight`: the box takes only the mark's room in the row, and Compose's touch target does the rest. */
    @Test
    fun `a touch finds the box at the touch minimum around it, a pointer on the mark`() {
        val marks = mutableListOf<Boolean>()
        var opens = 0
        scene({ TodoRow(todo("a"), onDone = { marks += it }, onOpen = { opens++ }) }) { scene ->
            scene.click(Offset(BESIDE_BOX, ROW_MIDDLE), PointerType.Touch)
            assertEquals(listOf(true), marks)
            assertEquals(0, opens)
            scene.click(Offset(BESIDE_BOX, ROW_MIDDLE))
            assertEquals(listOf(true), marks)
            assertEquals(1, opens)
        }
    }

    @Test
    fun `Space on the focused box checks it`() {
        val marks = mutableListOf<Boolean>()
        var opens = 0
        scene({ TodoRow(todo("a"), onDone = { marks += it }, onOpen = { opens++ }) }) { scene ->
            // The row takes the focus first, then its box.
            scene.press(Key.Tab, java.awt.event.KeyEvent.VK_TAB, '\t')
            scene.press(Key.Tab, java.awt.event.KeyEvent.VK_TAB, '\t')
            scene.press(Key.Spacebar, java.awt.event.KeyEvent.VK_SPACE, ' ')
            assertEquals(listOf(true), marks)
            assertEquals(0, opens)
        }
    }

    @Test
    fun `the box is a check box to a reader, named by the item's words`() {
        var item by mutableStateOf(todo("a"))
        scene({ TodoRow(item, onDone = {}, onOpen = {}) }) { scene ->
            fun box() = scene.nodes().single { it.config.getOrNull(SemanticsProperties.Role) == Role.Checkbox }
            assertEquals(ToggleableState.Off, box().config[SemanticsProperties.ToggleableState])
            assertEquals(listOf("Send the quote"), box().config[SemanticsProperties.ContentDescription])
            item = item.copy(done = true)
            scene.render()
            assertEquals(ToggleableState.On, box().config[SemanticsProperties.ToggleableState])
        }
    }

    @Test
    fun `a to-do in the detail has the box before its words, struck through once done, and no other item has one`() {
        val notes = RecordingNotes(
            "rec-1", NotesStage.FINAL, null,
            decisions = listOf(todo("d", kind = NoteKind.DECISION).copy(text = "Ship on Friday")),
            todos = listOf(todo("t1", done = true), todo("t2").copy(text = "Book the room")),
            questions = emptyList(), organizing = false,
        )
        val marks = mutableListOf<Pair<String, Boolean>>()
        var muted = Color.Unspecified
        scene(
            {
                muted = blueprint.textMuted
                NotesBlock(
                    notes, canSeek = true, onSeek = {}, linesAround = { emptyList() }, onDelete = {},
                    onDone = { item, done -> marks += item.id to done }, strings = StringTable.of(StringTable.BASE),
                )
            },
        ) { scene ->
            val boxes = scene.nodes().filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Checkbox }
            assertEquals(2, boxes.size, "the to-dos have one each, the decision none")
            assertEquals(ToggleableState.On, boxes.first().config[SemanticsProperties.ToggleableState])

            fun words(text: String) = scene.unmerged().first { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
            }.let { node ->
                val layout = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action!!(layout)
                layout.single().layoutInput.style
            }
            assertEquals(TextDecoration.LineThrough, words("Send the quote").textDecoration)
            assertEquals(muted, words("Send the quote").color)
            assertEquals(null, words("Book the room").textDecoration)
            assertEquals(null, words("Ship on Friday").textDecoration)

            val box = boxes.last().boundsInRoot
            scene.click(box.center)
            assertEquals(listOf("t2" to true), marks)
        }
    }

    @Test
    fun `the summary's editor has no box`() {
        val notes = RecordingNotes("rec-1", NotesStage.FINAL, null, emptyList(), listOf(todo("t1"), todo("t2", done = true)), emptyList(), organizing = false)
        scene({ NotesEditor(NotesDraft("rec-1", notes), Str.NOTES_EDIT, enabled = true, strings = StringTable.of(StringTable.BASE)) }) { scene ->
            assertTrue(scene.nodes().none { it.config.getOrNull(SemanticsProperties.Role) == Role.Checkbox })
        }
    }

    /**
     * Two worded buttons and a title are more than the list's width in half the languages: whatever a language's words, the
     * buttons go under the title rather than break its word.
     */
    @Test
    fun `the list's title stays on one line beside or above the buttons in every language`() {
        AppLanguage.entries.filter { it != AppLanguage.SYSTEM }.forEach { language ->
            val strings = StringTable.of(language.tag)
            scene(
                {
                    Box(Modifier.width(SidebarWidth)) {
                        ListHeader(strings[Str.WINDOW_RECORDINGS], "14 · 2 waiting · 1 failed") {
                            Row {
                                BlueprintButton(strings[Str.NOTES_TODOS], {}, tone = ButtonTone.QUIET, minWidth = MinTouch)
                                BlueprintButton(strings[Str.IMPORT_AUDIO], {}, tone = ButtonTone.QUIET)
                            }
                        }
                    }
                },
            ) { scene ->
                val title = scene.nodes().single { it.config.contains(SemanticsProperties.Heading) }
                val layout = mutableListOf<TextLayoutResult>()
                title.config[SemanticsActions.GetTextLayoutResult].action!!(layout)
                assertEquals(1, layout.single().lineCount, "${language.tag}: ${strings[Str.WINDOW_RECORDINGS]}")
            }
        }
    }

    private fun scene(content: @Composable () -> Unit, test: (ImageComposeScene) -> Unit) {
        val scene = ImageComposeScene(WIDTH, 400, Density(1f)) {
            ReclyDesktopTheme(dark = false, highContrast = false) { Column { content() } }
        }
        try {
            scene.render()
            scene.render()
            test(scene)
        } finally {
            scene.close()
        }
    }

    private fun ImageComposeScene.click(at: Offset, type: PointerType = PointerType.Mouse) {
        sendPointerEvent(PointerEventType.Move, at, type = type)
        sendPointerEvent(PointerEventType.Press, at, type = type, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        sendPointerEvent(PointerEventType.Release, at, type = type, buttons = PointerButtons(), button = PointerButton.Primary)
        render()
        render()
    }

    private fun ImageComposeScene.press(key: Key, code: Int, char: Char) {
        val source = java.awt.Canvas()
        for ((id, type) in listOf(java.awt.event.KeyEvent.KEY_PRESSED to KeyEventType.KeyDown, java.awt.event.KeyEvent.KEY_RELEASED to KeyEventType.KeyUp)) {
            sendKeyEvent(KeyEvent(key, type, codePoint = char.code, nativeEvent = java.awt.event.KeyEvent(source, id, 0L, 0, code, char)))
        }
        render()
    }

    /** Every node of the scene's merged tree — one per thing a screen reader stops on. */
    private fun ImageComposeScene.nodes(): List<SemanticsNode> = semanticsOwners.flatMap { it.rootSemanticsNode.withDescendants() }

    private fun ImageComposeScene.unmerged(): List<SemanticsNode> = semanticsOwners.flatMap { it.unmergedRootSemanticsNode.withDescendants() }

    private fun SemanticsNode.withDescendants(): List<SemanticsNode> = listOf(this) + children.flatMap { it.withDescendants() }

    private companion object {
        const val WIDTH = 500

        /** The box is 18 at the row's 16 and 8 inset, one below that to sit on the first line. */
        const val BOX_MIDDLE = 25f
        const val ROW_MIDDLE = 18f

        /** In the 8 between the box, which ends at 34, and the words. */
        const val BESIDE_BOX = 38f

        /** The words start at 42. */
        const val WORDS_START = 44f
        const val WORDS = 80f
    }
}
