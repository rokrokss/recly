package app.recly.android.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import app.recly.android.R
import app.recly.android.ui.component.BlueprintButton
import app.recly.android.ui.component.ButtonTone
import app.recly.android.ui.component.HairLine
import app.recly.android.ui.component.SectionHeader
import app.recly.android.ui.component.focusRing
import app.recly.android.ui.theme.MinTouch
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import app.recly.android.ui.theme.mono
import recly.core.notes.NoteItem
import recly.core.notes.NoteLine
import recly.core.notes.RecordingNotes

/** docs/08 "Organizing": one of a recording's three groups — its header and its items, newest first as the core gives them. */
internal data class NoteGroup(@param:StringRes val header: Int, val items: List<NoteItem>)

/** Decisions · To-dos · Questions, in that order. A group with nothing in it is left out, header and all. */
internal fun noteGroups(notes: RecordingNotes): List<NoteGroup> = listOf(
    NoteGroup(R.string.notes_decisions, notes.decisions),
    NoteGroup(R.string.notes_todos, notes.todos),
    NoteGroup(R.string.notes_questions, notes.questions),
).filter { it.items.isNotEmpty() }

/** The two or three lines over the whole recording, when the final pass wrote any. */
internal fun noteOverview(notes: RecordingNotes): String? = notes.overview?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Whether there is a block to draw at all — no empty state is ever shown. With [overview] (the detail) the overview
 * counts; the record screen shows the groups alone.
 */
internal fun showsNotes(notes: RecordingNotes?, overview: Boolean): Boolean =
    notes != null && ((overview && noteOverview(notes) != null) || noteGroups(notes).isNotEmpty())

/** A row's words: the item, its owner after a dash (`Send the quote — Minji`), then its deadline as it was said. */
internal fun noteText(item: NoteItem): String = buildString {
    append(item.text.trim())
    item.owner?.trim()?.takeIf { it.isNotEmpty() }?.let { append(" — ").append(it) }
    item.due?.trim()?.takeIf { it.isNotEmpty() }?.let { append(" · ").append(it) }
}

/**
 * Which of [lines] the note is on, for its emphasis: the line its quote is in, else the line its time falls in, else the
 * line nearest it — -1 for no lines. The quote is compared as letters and digits only, so case and punctuation the
 * transcript and the quote write differently do not hide it.
 */
internal fun noteLineIndex(lines: List<NoteLine>, atSec: Double, quote: String): Int {
    if (lines.isEmpty()) return -1
    val said = letters(quote)
    if (said.isNotEmpty()) lines.indexOfFirst { letters(it.text).contains(said) }.takeIf { it >= 0 }?.let { return it }
    lines.indexOfFirst { atSec >= it.atSec && atSec <= it.endSec }.takeIf { it >= 0 }?.let { return it }
    return lines.indices.minBy { kotlin.math.abs(lines[it].atSec - atSec) }
}

private fun letters(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

/**
 * docs/08 "Organizing" · docs/09: a recording's notes — on the detail, the overview and then the groups; on the record
 * screen, the groups alone. Each group is its section header over table rows: the item's words, and at the end its
 * time in monospace. A tap on a row opens the transcript around it under the row, the note's own line marked as the
 * reader marks the line it plays; a second tap closes it, and opening another closes the first. A swipe from the end
 * deletes the item for good, with no question; a screen reader has the same Delete as an action of the row.
 *
 * [onPlay] makes the time a button that plays from it — the summary's citations' way — where [canPlay] allows; without
 * it (while recording) the time is a label. [scaleSec] picks the time format ([clock]).
 */
@Composable
internal fun NotesBlock(
    notes: RecordingNotes,
    overview: Boolean,
    scaleSec: Long?,
    onDelete: (NoteItem) -> Unit,
    lines: suspend (Double) -> List<NoteLine>,
    modifier: Modifier = Modifier,
    canPlay: (Double) -> Boolean = { false },
    onPlay: ((Double) -> Unit)? = null,
) {
    val palette = blueprint
    var open by remember(notes.recordingId) { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxWidth().testTag("notes")) {
        if (overview) noteOverview(notes)?.let { text ->
            Text(
                text,
                Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.sm).testTag("notes-overview"),
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                color = palette.text,
            )
            HairLine()
        }
        noteGroups(notes).forEach { group ->
            SectionHeader(stringResource(group.header), Modifier.padding(horizontal = Space.m))
            HairLine()
            group.items.forEach { item ->
                key(item.id) {
                    NoteRow(
                        item = item,
                        expanded = open == item.id,
                        onToggle = { open = if (open == item.id) null else item.id },
                        scaleSec = scaleSec,
                        canPlay = canPlay,
                        onPlay = onPlay,
                        onDelete = onDelete,
                        lines = lines,
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteRow(
    item: NoteItem,
    expanded: Boolean,
    onToggle: () -> Unit,
    scaleSec: Long?,
    canPlay: (Double) -> Boolean,
    onPlay: ((Double) -> Unit)?,
    onDelete: (NoteItem) -> Unit,
    lines: suspend (Double) -> List<NoteLine>,
) {
    val palette = blueprint
    val delete = stringResource(R.string.action_delete)
    val toggle = stringResource(if (expanded) R.string.jobs_row_collapse else R.string.jobs_row_expand)
    val swipe = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) onDelete(item) },
        modifier = Modifier.testTag("note-${item.id}"),
        // What the swipe does, at the end it uncovers — red, because it deletes for good (docs/09 "Accessibility"). A screen
        // reader has the row's own Delete action instead.
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(palette.surface).padding(horizontal = Space.m).clearAndSetSemantics {},
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(delete, style = MaterialTheme.typography.labelLarge, color = palette.danger)
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().background(palette.surface)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .focusRing(palette)
                    .clickable(onClick = onToggle)
                    .semantics {
                        if (expanded) collapse(label = toggle) { onToggle(); true } else expand(label = toggle) { onToggle(); true }
                        customActions = listOf(CustomAccessibilityAction(delete) { onDelete(item); true })
                    }
                    .heightIn(min = MinTouch)
                    // docs/09 "Component measurements": a table row's 16 at the sides; its 12 top and bottom are the text's.
                    .padding(horizontal = Space.m),
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    noteText(item),
                    Modifier.weight(1f).padding(vertical = Space.sm),
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                    color = palette.text,
                )
                NoteTime(item.atSec, scaleSec, canPlay, onPlay)
            }
            if (expanded) NoteLines(item, lines)
            HairLine()
        }
    }
}

/** The note's time at the row's end: a quiet monospace button that plays from it, as a transcript time does, or a label. */
@Composable
private fun NoteTime(atSec: Double, scaleSec: Long?, canPlay: (Double) -> Boolean, onPlay: ((Double) -> Unit)?) {
    val stamp = clock(atSec.toLong(), scaleSec)
    if (onPlay == null) {
        Text(stamp, style = mono.bodySmall, color = blueprint.textMuted, modifier = Modifier.clearAndSetSemantics { contentDescription = hms(atSec.toLong()) })
    } else {
        val label = stringResource(R.string.summary_play_from, hms(atSec.toLong()))
        BlueprintButton(stamp, { onPlay(atSec) }, enabled = canPlay(atSec), tone = ButtonTone.QUIET, monospace = true,
            modifier = Modifier.semantics { contentDescription = label })
    }
}

/** The transcript around a note, under its row: who spoke when there is a name, what was said, the note's line marked. */
@Composable
private fun NoteLines(item: NoteItem, lines: suspend (Double) -> List<NoteLine>) {
    val palette = blueprint
    val around by produceState<List<NoteLine>>(emptyList(), item.id, item.atSec) { value = lines(item.atSec) }
    if (around.isEmpty()) return
    val at = noteLineIndex(around, item.atSec, item.quote)
    val me = stringResource(R.string.speaker_me)
    Column(
        Modifier.fillMaxWidth().padding(start = Space.m, end = Space.m, bottom = Space.sm).testTag("note-lines"),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        around.forEachIndexed { index, line ->
            Column(Modifier.fillMaxWidth().activeBar(palette.accent, index == at), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                (line.speakerName ?: me.takeIf { line.me })?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = palette.textMuted) }
                Text(line.text, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content), color = palette.text)
            }
        }
    }
}
