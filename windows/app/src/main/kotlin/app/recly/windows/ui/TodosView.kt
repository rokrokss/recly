package app.recly.windows.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import app.recly.windows.ui.component.HairLine
import app.recly.windows.ui.component.focusRing
import app.recly.windows.ui.component.pressCursor
import app.recly.windows.ui.theme.MinTouch
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import app.recly.windows.ui.theme.mono
import recly.core.notes.NoteItem
import recly.core.notes.RecordingTodos
import recly.core.notes.TodoGroups

/**
 * docs/09 "To-dos view": whether the Details list's area is the view, what was checked since it opened, which stays where
 * it is until the view is left ([TodoGroups]), and whether its `Done` row is open. All of it goes on leaving.
 */
internal class TodosViewState {
    var shown: Boolean by mutableStateOf(false)
        private set
    var held: Set<String> by mutableStateOf(emptySet())
        private set
    var doneOpen: Boolean by mutableStateOf(false)
        private set

    fun open() {
        shown = true
    }

    fun leave() {
        shown = false
        held = emptySet()
        doneOpen = false
    }

    fun toggleDone() {
        doneOpen = !doneOpen
    }

    /** A box pressed, here or in the detail beside it. Ahead of the write, so that the row never goes from its place. */
    fun checking(itemId: String, done: Boolean) {
        if (shown && done) held = held + itemId
    }
}

/** The number the view's header says: a row checked here stays in its place, but is no longer open. */
internal fun openTodoCount(groups: TodoGroups): Int = groups.open.sumOf { group -> group.items.count { !it.done } }

/**
 * The view's body, in the list's `LazyColumn`: the recordings with something open, then the `Done` row and, while it is
 * open, the rest — or the rest directly, with no such row, when nothing is open.
 */
internal fun LazyListScope.todosList(
    groups: TodoGroups,
    doneOpen: Boolean,
    onToggleDone: () -> Unit,
    strings: Strings,
    onDone: (RecordingTodos, NoteItem, Boolean) -> Unit,
    onOpen: (RecordingTodos) -> Unit,
) {
    fun list(section: String, shown: List<RecordingTodos>) = shown.forEach { group ->
        item(key = "todos-$section-${group.recordingId}") { TodoGroupHeader(group, strings[Str.UNTITLED]) { onOpen(group) } }
        // One key wherever it is listed, so that the focus stays on a row unchecked under `Done`.
        items(group.items, key = { "todo-${group.recordingId}-${it.id}" }) { item ->
            TodoRow(item, onDone = { done -> onDone(group, item, done) }, onOpen = { onOpen(group) })
        }
        item(key = "todos-$section-${group.recordingId}-end") {
            Spacer(Modifier.height(Space.s))
            HairLine()
        }
    }
    list("open", groups.open)
    when {
        groups.open.isEmpty() -> list("done", groups.done)
        groups.doneCount > 0 -> {
            item(key = "todos-done") {
                TodosDoneRow(strings[Str.STATE_DONE], groups.doneCount, onToggleDone)
                HairLine()
            }
            if (doneOpen) list("done", groups.done)
        }
    }
}

/** A recording in the view: when it started and its title as its row in the list says it. A click opens it. */
@Composable
private fun TodoGroupHeader(group: RecordingTodos, untitled: String, onOpen: () -> Unit) {
    val palette = blueprint
    Row(
        Modifier
            .fillMaxWidth()
            .semantics { heading() }
            .defaultMinSize(minHeight = MinTouch)
            .focusRing()
            .pressCursor()
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = Space.m, vertical = Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${LedgerFormat.date(group.startedAt)} ${LedgerFormat.time(group.startedAt)}",
            style = mono.small.copy(textDirection = TextDirection.Ltr),
            color = palette.textMuted,
            maxLines = 1,
        )
        Text(
            group.title?.takeIf { it.isNotBlank() } ?: untitled,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            color = palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The box and the words of a row of the notes, without its time. A click anywhere but on the box opens the recording. */
@Composable
internal fun TodoRow(item: NoteItem, onDone: (Boolean) -> Unit, onOpen: () -> Unit) {
    NoteWords(
        item, onDone,
        Modifier
            .fillMaxWidth()
            .focusRing()
            .pressCursor()
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = Space.m, vertical = Space.s),
    )
}

/** Under the open ones: `Done` and how many, which opens and closes the rest. */
@Composable
private fun TodosDoneRow(label: String, count: Int, onClick: () -> Unit) {
    val palette = blueprint
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MinTouch)
            .focusRing()
            .pressCursor()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.m, vertical = Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = palette.textMuted)
        Text(count.toString(), style = mono.small.copy(textDirection = TextDirection.Ltr), color = palette.textMuted)
    }
}
