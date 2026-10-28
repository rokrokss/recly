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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import app.recly.android.R
import app.recly.android.ui.component.BlueprintButton
import app.recly.android.ui.component.BlueprintField
import app.recly.android.ui.component.ButtonTone
import app.recly.android.ui.component.HairLine
import app.recly.android.ui.component.LoadingText
import app.recly.android.ui.component.SectionHeader
import app.recly.android.ui.component.focusRing
import app.recly.android.ui.theme.MinTouch
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import app.recly.android.ui.theme.mono
import recly.core.notes.NoteEdit
import recly.core.notes.NoteItem
import recly.core.notes.NoteLine
import recly.core.notes.RecordingNotes
import recly.core.storage.StorageKind

/**
 * docs/08 "Organizing": one group of a recording's notes — its [header], or for one of the user's own sections its
 * [name] — and its items, newest first as the core gives them.
 */
internal data class NoteGroup(@param:StringRes val header: Int?, val items: List<NoteItem>, val name: String? = null)

/**
 * docs/09 "Notes view": Key points · Decisions · To-dos · Questions, then the user's sections in the order the notes
 * were made with. A group with nothing in it is left out, header and all. Without [keyPoints] — the record screen —
 * there are none: they come after Stop.
 */
internal fun noteGroups(notes: RecordingNotes, keyPoints: Boolean = true): List<NoteGroup> = (
    listOfNotNull(
        NoteGroup(R.string.notes_key_points, notes.points).takeIf { keyPoints },
        NoteGroup(R.string.notes_decisions, notes.decisions),
        NoteGroup(R.string.notes_todos, notes.todos),
        NoteGroup(R.string.notes_questions, notes.questions),
    ) + notes.sections.map { NoteGroup(null, it.items, it.name) }
).filter { it.items.isNotEmpty() }

/** The group's header: the app's word for a built-in group, the user's own name for a section. */
@Composable
private fun NoteGroup.title(): String = header?.let { stringResource(it) } ?: name.orEmpty()

/** The two or three lines over the whole recording, when the final pass wrote any. */
internal fun noteOverview(notes: RecordingNotes): String? = notes.overview?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Whether there is a block to draw at all — no empty state is ever shown. With [overview] (the detail) the overview
 * and the key points count; the record screen shows the other groups alone.
 */
internal fun showsNotes(notes: RecordingNotes?, overview: Boolean): Boolean =
    notes != null && ((overview && noteOverview(notes) != null) || noteGroups(notes, keyPoints = overview).isNotEmpty())

/** docs/09 "AI view": the recording has notes — Organize says `again`, Edit notes is offered and the chips are there. */
internal fun hasNotes(notes: RecordingNotes?): Boolean = showsNotes(notes, overview = true)

/**
 * Transcript | Notes, once the recording has notes or the user has just asked for them — never while the transcript
 * is edited, nor while the notes editor has the keyboard up ([typing], N6): the fields keep that room, and the chips
 * are back when the keyboard goes.
 */
internal fun showsNotesChips(notes: Boolean, asked: Boolean, editing: Boolean, typing: Boolean = false): Boolean =
    !editing && !typing && (asked || notes)

/** The line under the notes: `ChatGPT · <model>`, or ChatGPT alone for notes that do not say which. */
@Composable
private fun notesFooter(model: String?): String =
    model?.let { stringResource(R.string.chatgpt_footer, it) } ?: stringResource(R.string.chatgpt_title)

/** An item's owner and deadline as the editor shows them under its field, muted; null when it has neither. */
internal fun ownerAndDue(item: NoteItem): String? =
    listOfNotNull(item.owner?.trim(), item.due?.trim()).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { null }

/**
 * The notes editor's note on where a save goes: Drive and iCloud carry the notes to the other devices; a local folder,
 * or a recording not uploaded yet, keeps them here (docs/09 "AI view").
 */
@StringRes
internal fun notesEditNote(storage: StorageKind?): Int = when (storage) {
    StorageKind.DRIVE, StorageKind.ICLOUD -> R.string.notes_edit_note_shared
    StorageKind.FOLDER, null -> R.string.notes_edit_note_local
}

/**
 * docs/09 "AI view": Edit notes' working copy — the overview's words and each item's, by its id, as typed. [original]
 * is the notes the editor opened on; Save sends only what differs from it, trimmed as the core keeps it.
 */
internal data class NotesDraft(val original: RecordingNotes, val overview: String, val texts: Map<String, String>) {
    val changed: Boolean get() = overviewEdit() != null || edits().isNotEmpty()

    /** The overview as Save sends it — null when it is as it was, empty when the user cleared it. */
    fun overviewEdit(): String? = overview.trim().takeIf { noteOverview(original) != null && it != original.overview?.trim() }

    /** The items whose words changed; an emptied one is deleted by the core. */
    fun edits(): List<NoteEdit> = noteGroups(original).flatMap { it.items }.mapNotNull { item ->
        texts[item.id]?.takeIf { it.trim() != item.text.trim() }?.let { NoteEdit(item.id, it.trim()) }
    }

    fun type(itemId: String, text: String): NotesDraft = copy(texts = texts + (itemId to text))

    companion object {
        fun of(notes: RecordingNotes) =
            NotesDraft(notes, notes.overview.orEmpty(), noteGroups(notes).flatMap { it.items }.associate { it.id to it.text })
    }
}

/** A row's words: the item, its owner after a dash (`Send the quote — Minji`), then its deadline as it was said, [muted]. */
internal fun noteText(item: NoteItem, muted: Color): AnnotatedString = buildAnnotatedString {
    append(item.text.trim())
    item.owner?.trim()?.takeIf { it.isNotEmpty() }?.let { append(" — "); append(it) }
    item.due?.trim()?.takeIf { it.isNotEmpty() }?.let { withStyle(SpanStyle(color = muted)) { append(" · "); append(it) } }
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
 * screen, the groups alone, without key points. Each group is its section header over table rows: the item's words, and at the end its
 * time in monospace. A tap on a row opens the transcript around it under the row, the note's own line marked as the
 * reader marks the line it plays; a second tap closes it, and opening another closes the first. A swipe from the end
 * deletes the item for good, with no question; a screen reader has the same Delete as an action of the row.
 *
 * [onSeek] makes the time a button that moves the playhead to it — a transcript time's way, playing or paused as it
 * was — where [canSeek] allows; without it (while recording) the time is a label. [scaleSec] picks the time format
 * ([clock]).
 */
@Composable
internal fun NotesBlock(
    notes: RecordingNotes,
    overview: Boolean,
    scaleSec: Long?,
    onDelete: (NoteItem) -> Unit,
    lines: suspend (Double) -> List<NoteLine>,
    modifier: Modifier = Modifier,
    canSeek: (Double) -> Boolean = { false },
    onSeek: ((Double) -> Unit)? = null,
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
        noteGroups(notes, keyPoints = overview).forEach { group ->
            SectionHeader(group.title(), Modifier.padding(horizontal = Space.m))
            HairLine()
            group.items.forEach { item ->
                key(item.id) {
                    NoteRow(
                        item = item,
                        expanded = open == item.id,
                        onToggle = { open = if (open == item.id) null else item.id },
                        scaleSec = scaleSec,
                        canSeek = canSeek,
                        onSeek = onSeek,
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
    canSeek: (Double) -> Boolean,
    onSeek: ((Double) -> Unit)?,
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
                    noteText(item, palette.textMuted),
                    Modifier.weight(1f).padding(vertical = Space.sm),
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                    color = palette.text,
                )
                NoteTime(item.atSec, scaleSec, canSeek, onSeek)
            }
            if (expanded) NoteLines(item, lines)
            HairLine()
        }
    }
}

/**
 * The note's time at the row's end: a quiet, compact monospace button that moves the playhead to it, as a transcript
 * time does (2026-10-28), or a label.
 */
@Composable
private fun NoteTime(atSec: Double, scaleSec: Long?, canSeek: (Double) -> Boolean, onSeek: ((Double) -> Unit)?) {
    val stamp = clock(atSec.toLong(), scaleSec)
    if (onSeek == null) {
        Text(stamp, style = mono.bodySmall, color = blueprint.textMuted, modifier = Modifier.clearAndSetSemantics { contentDescription = hms(atSec.toLong()) })
    } else {
        val label = stringResource(R.string.transcript_seek, hms(atSec.toLong()))
        BlueprintButton(stamp, { onSeek(atSec) }, enabled = canSeek(atSec), tone = ButtonTone.QUIET, monospace = true, compact = true,
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

/**
 * docs/09 "AI view": the `Notes` chip's body — `Organizing…` with the square loader while a pass of the notes runs
 * ([organizing]), or the failure of the last Organize with the one button that helps ([onRetry] for Retry), over the
 * notes there are (§9 "Notes view"); under them `ChatGPT · <model>` and Copy all, which copies [text] — `notes.text`.
 * Nothing at all for notes with nothing in them: there is no empty state.
 */
@Composable
internal fun NotesPane(
    notes: RecordingNotes?,
    organizing: Boolean,
    text: String?,
    onRetry: () -> Unit,
    scaleSec: Long?,
    onDelete: (NoteItem) -> Unit,
    lines: suspend (Double) -> List<NoteLine>,
    canSeek: (Double) -> Boolean,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shown = notes?.takeIf { showsNotes(it, overview = true) }
    val failure = notes?.failure?.takeIf { !organizing }
    Column(modifier) {
        if (organizing) {
            LoadingText(stringResource(R.string.notes_organizing), MaterialTheme.typography.bodySmall, blueprint.textMuted,
                Modifier.padding(horizontal = Space.m, vertical = Space.s).testTag("notes-organizing"))
            HairLine()
        }
        if (failure != null) {
            if (shown == null) {
                ChatGptFailure(failure, R.string.notes_failed, onRetry, Modifier.fillMaxSize(), retryTag = "notes-retry")
                return@Column
            }
            ChatGptFailure(failure, R.string.notes_failed, onRetry, Modifier.fillMaxWidth(), retryTag = "notes-retry")
            HairLine()
        }
        if (shown != null) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                NotesBlock(shown, overview = true, scaleSec = scaleSec, onDelete = onDelete, lines = lines, canSeek = canSeek, onSeek = onSeek)
            }
            HairLine()
            CopyFooter(notesFooter(shown.model), text.orEmpty(), Modifier.testTag("notes-copy"))
        }
    }
}

/**
 * docs/09 "AI view": Edit notes — the transcript editor's way with the notes. The overview, when there is one, as one
 * plain field; then each group's header over its items, each one field of its words with its owner and deadline muted
 * under it, which are not the user's to change. No time buttons: what an item points at stays the model's.
 */
@Composable
internal fun NotesEditor(draft: NotesDraft, onDraft: (NotesDraft) -> Unit, modifier: Modifier = Modifier) {
    val palette = blueprint
    val style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content)
    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = Space.s).testTag("notes-editor")) {
        if (noteOverview(draft.original) != null) {
            BlueprintField(draft.overview, { onDraft(draft.copy(overview = it)) }, Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.s),
                textStyle = style, fieldModifier = Modifier.testTag("notes-edit-overview"), maxLines = Int.MAX_VALUE)
        }
        noteGroups(draft.original).forEach { group ->
            SectionHeader(group.title(), Modifier.padding(horizontal = Space.m))
            Column(Modifier.padding(horizontal = Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                group.items.forEach { item ->
                    key(item.id) {
                        Column(verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                            BlueprintField(draft.texts[item.id].orEmpty(), { onDraft(draft.type(item.id, it)) }, Modifier.fillMaxWidth(),
                                textStyle = style, fieldModifier = Modifier.testTag("notes-edit-${item.id}"), maxLines = Int.MAX_VALUE)
                            ownerAndDue(item)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = palette.textMuted) }
                        }
                    }
                }
            }
        }
    }
}
