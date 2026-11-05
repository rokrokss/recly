package app.recly.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import app.recly.android.R
import app.recly.android.ui.component.focusRing
import app.recly.android.ui.theme.MinTouch
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import app.recly.android.ui.theme.mono
import recly.core.notes.NoteItem
import recly.core.notes.RecordingTodos
import recly.core.notes.TodoGroups

/** docs/09 "To-dos view": the number the header says, which a row checked just now, still in its place, has left. */
internal fun openTodoCount(todos: List<RecordingTodos>): Int = todos.sumOf { group -> group.items.count { !it.done } }

/**
 * docs/09 "To-dos view": every recording's to-dos in the list's place — the open ones by recording, with what was checked
 * here still among them, and under them `Done`, which opens and closes the rest. With nothing open the rest is simply
 * there. On the page, as the notes' rows are.
 */
@Composable
internal fun TodosView(
    todos: List<RecordingTodos>,
    view: TodosViewState,
    onOpen: (RecordingTodos) -> Unit,
    onCheck: (recordingId: String, itemId: String, done: Boolean) -> Unit,
    onToggleDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = remember(todos, view.held) { TodoGroups(todos, view.held) }
    // A group that arrives on top while the view is at its top — a newer recording's row unchecked under `Done` — is
    // shown, not left above the first row the list was anchored on, as the ledger's. By then the anchor has moved past
    // the group: its header and its rows.
    val list = rememberLazyListState()
    val newest = groups.open.firstOrNull()
    LaunchedEffect(newest?.recordingId) { if (list.firstVisibleItemIndex <= (newest?.items?.size ?: 0) + 1) list.scrollToItem(0) }
    LazyColumn(modifier.testTag("todos"), state = list) {
        todoGroups("open", groups.open, onOpen, onCheck)
        if (groups.open.isEmpty()) {
            todoGroups("done", groups.done, onOpen, onCheck)
        } else if (groups.doneCount > 0) {
            item(key = "done-row") { DoneRow(groups.doneCount, view.doneOpen, onToggleDone) }
            if (view.doneOpen) todoGroups("done", groups.done, onOpen, onCheck)
        }
    }
}

/** [part] keeps the keys apart: a recording can have a group among the open ones and another under `Done`. */
private fun LazyListScope.todoGroups(
    part: String,
    groups: List<RecordingTodos>,
    onOpen: (RecordingTodos) -> Unit,
    onCheck: (recordingId: String, itemId: String, done: Boolean) -> Unit,
) {
    groups.forEach { group ->
        item(key = "$part-${group.recordingId}") { TodoGroupHeader(group) { onOpen(group) } }
        items(group.items, key = { "$part-${it.id}" }) { item ->
            TodoRow(item, onOpen = { onOpen(group) }, onDone = { onCheck(group.recordingId, item.id, it) })
        }
    }
}

/** A group's header: when the recording started, in mono, then its title as its row in the list says it. */
@Composable
private fun TodoGroupHeader(group: RecordingTodos, onOpen: () -> Unit) {
    val palette = blueprint
    Row(
        Modifier
            .fillMaxWidth()
            .focusRing(palette)
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics { heading() }
            // A section header's rhythm (docs/09 "Component measurements"), which the touch layer takes to the minimum.
            .padding(start = Space.m, end = Space.m, top = Space.m, bottom = Space.s)
            .testTag("todo-group-${group.recordingId}"),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(ledgerColumn(group.startedAt, LEDGER_DATE) + " " + ledgerColumn(group.startedAt, LEDGER_TIME),
            style = mono.small, color = palette.textMuted, maxLines = 1)
        Text(group.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.jobs_untitled), Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall, color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The box, then the words of a row of the notes, without its time. The box checks; the rest of the row opens the recording. */
@Composable
private fun TodoRow(item: NoteItem, onOpen: () -> Unit, onDone: (Boolean) -> Unit) {
    val palette = blueprint
    Row(
        Modifier
            .fillMaxWidth()
            .focusRing(palette)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = Space.m, vertical = Space.s)
            .testTag("todo-${item.id}"),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        TodoBox(item, onDone)
        Text(
            noteText(item, palette.textMuted, done = item.done),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
            color = palette.text,
        )
    }
}

/** The quiet row under the open ones: `Done` and how many, in mono. */
@Composable
private fun DoneRow(count: Int, open: Boolean, onToggle: () -> Unit) {
    val palette = blueprint
    val toggle = stringResource(if (open) R.string.jobs_row_collapse else R.string.jobs_row_expand)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouch)
            .focusRing(palette)
            .clickable(onClick = onToggle)
            .semantics { if (open) collapse(label = toggle) { onToggle(); true } else expand(label = toggle) { onToggle(); true } }
            .padding(horizontal = Space.m, vertical = Space.s)
            .testTag("todos-done"),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.job_state_done), style = MaterialTheme.typography.labelMedium, color = palette.textMuted)
        Text(count.toString(), style = mono.small, color = palette.textMuted)
    }
}
