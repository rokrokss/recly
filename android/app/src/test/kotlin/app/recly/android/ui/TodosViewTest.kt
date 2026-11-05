package app.recly.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import recly.core.notes.NoteItem
import recly.core.notes.NoteKind
import recly.core.notes.RecordingTodos
import recly.core.notes.TodoGroups

/**
 * docs/09 "To-dos view" on the phone: when the view goes back by itself, what it holds in place, what the header counts
 * and how a checked row's words are drawn — the decisions the screen makes, as the pure steps it applies.
 */
class TodosViewTest {

    @Test
    fun `a view left with no to-do at all goes back by itself`() {
        val open = JobsUiState(todosView = TodosViewState(held = setOf("a"))).withTodos(listOf(group("r1", todo("a"))))
        assertNotNull(open.todosView)

        val none = open.withTodos(emptyList())
        assertNull(none.todosView)
        assertEquals(emptyList(), none.todos)
    }

    @Test
    fun `to-dos arriving do not open the view`() {
        assertNull(JobsUiState().withTodos(listOf(group("r1", todo("a")))).todosView)
    }

    @Test
    fun `a row checked in the view stays among the open ones until it is left`() {
        val checked = listOf(group("r1", todo("a", done = true), todo("b")))
        val held = JobsUiState(todosView = TodosViewState()).holding("a").withTodos(checked)

        val here = TodoGroups(held.todos, held.todosView!!.held)
        assertEquals(listOf("a", "b"), here.open.single().items.map { it.id })
        assertEquals(0, here.doneCount)

        // Leaving and coming back opens a new view, which holds nothing: the row is under `Done`.
        val back = TodoGroups(held.todos, TodosViewState().held)
        assertEquals(listOf("b"), back.open.single().items.map { it.id })
        assertEquals(listOf("a"), back.done.single().items.map { it.id })
    }

    @Test
    fun `a box checked with the view closed holds nothing`() {
        assertNull(JobsUiState().holding("a").todosView)
    }

    @Test
    fun `the header counts what is still open, across recordings, and not a row just checked`() {
        val todos = listOf(group("r1", todo("a", done = true), todo("b")), group("r2", todo("c")), group("r3", todo("d", done = true)))

        assertEquals(2, openTodoCount(todos))
        assertEquals(0, openTodoCount(listOf(group("r3", todo("d", done = true)))))
    }

    @Test
    fun `a done row's words are struck through in the secondary colour, its deadline with them`() {
        val item = todo("a", due = "Friday", done = true)

        val struck = noteText(item, Color.Gray, done = true)
        val strike = struck.spanStyles.single { it.item.textDecoration == TextDecoration.LineThrough }
        assertEquals(Color.Gray, strike.item.color)
        assertEquals(0 to struck.text.length, strike.start to strike.end)
    }

    @Test
    fun `without its box a done item's words are as they were`() {
        val item = todo("a", done = true)

        assertEquals(emptyList(), noteText(item, Color.Gray).spanStyles)
    }

    private fun group(id: String, vararg items: NoteItem) = RecordingTodos(id, "Title", "2026-11-06T09:00:00Z", items.toList())

    private fun todo(id: String, due: String? = null, done: Boolean = false) =
        NoteItem(id, NoteKind.TODO, "text $id", null, due, atSec = 10.0, quote = "text", provisional = false, done = done)
}
