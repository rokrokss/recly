package app.recly.windows.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import app.recly.windows.i18n.WORD_JOINER
import app.recly.windows.i18n.text
import app.recly.windows.ui.component.BlueprintButton
import app.recly.windows.ui.component.BlueprintChip
import app.recly.windows.ui.component.ButtonTone
import app.recly.windows.ui.component.HairLine
import app.recly.windows.ui.component.LoadingText
import app.recly.windows.ui.component.SectionHeader
import app.recly.windows.ui.component.fieldBox
import app.recly.windows.ui.component.focusRing
import app.recly.windows.ui.component.pressCursor
import app.recly.windows.ui.theme.MinTouch
import app.recly.windows.ui.theme.Radius
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import app.recly.windows.ui.theme.mono
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import recly.core.notes.NoteEdit
import recly.core.notes.NoteItem
import recly.core.notes.NotesHeadings
import recly.core.notes.RecordingNotes

/** docs/09 "AI view": Transcript | Notes, under the detail's header. One is always chosen. */
@Composable
internal fun NotesChips(showingNotes: Boolean, onShowNotes: (Boolean) -> Unit, strings: Strings) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.m).padding(bottom = Space.s),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        BlueprintChip(strings[Str.EXPORT_TRANSCRIPT], selected = !showingNotes, onClick = { onShowNotes(false) })
        BlueprintChip(strings[Str.NOTES_TAB], selected = showingNotes, onClick = { onShowNotes(true) })
    }
    HairLine()
}

/**
 * docs/09 "AI view" · "Notes view": the Notes chip's body. On top, the square loader and `Organizing…` while the
 * recording is organized here, or the last Organize's failure; under it the notes there are ([NotesBlock]), whose
 * times move the playhead there ([canSeek], [onSeek]); under them which model made them, and Copy all.
 */
@Composable
internal fun NotesPane(
    model: ShellModel,
    detail: RecordingDetail,
    canSeek: Boolean,
    onSeek: (Double) -> Unit,
    strings: Strings,
    modifier: Modifier = Modifier,
) {
    val notes = detail.notes
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        val failure = notes?.failure
        when {
            notes?.organizing == true -> LoadingText(strings[Str.NOTES_ORGANIZING], MaterialTheme.typography.bodyMedium, blueprint.textMuted)
            // Retry is Organize again: it asks nothing either.
            failure != null -> ChatGptFailureNotice(chatGptFailure(failure, Str.NOTES_FAILED), { model.chatGpt?.openUsage() }, model::organize, strings)
        }
        if (notes != null && !notes.isEmpty) {
            NotesBlock(
                notes = notes,
                canSeek = canSeek,
                onSeek = onSeek,
                linesAround = { atSec -> model.noteLines(detail.recordingId, atSec) },
                onDelete = { item -> model.deleteNote(detail.recordingId, item.id) },
                strings = strings,
                seekableDurationSec = detail.audio.totalSec,
                spanSec = detail.spanSec,
            )
            ChatGptFooter(notes.model?.let { strings[Str.SUMMARY_MODEL, it] }.orEmpty(), detail.recordingId, strings) {
                model.notesText(detail.recordingId)
            }
        }
    }
}

/**
 * Which model wrote it, and Copy all, which copies [text] — read when it is pressed — and says Copied. Under the notes,
 * and under an answer.
 */
@Composable
internal fun ChatGptFooter(source: String, recordingId: String, strings: Strings, text: suspend () -> String?) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember(recordingId) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(COPIED_MS); copied = false } }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(source, style = MaterialTheme.typography.bodySmall, color = blueprint.textMuted, modifier = Modifier.weight(1f))
        CopyAllButton(copied, strings) {
            scope.launch {
                text()?.let {
                    clipboard.setText(AnnotatedString(it))
                    copied = true
                }
            }
        }
    }
}

/**
 * docs/09 "AI view": Copy all as the copy icon (2026-10-28; until then a quiet worded button) — no box, the muted ink of
 * the shell's other marks (the find bar's, a field's ×), the [MinTouch] target — and for as long as it would have said
 * Copied, the check mark, which says so to a reader; a press does nothing then. The shell has no icon font, so both are
 * drawn, in the icons' thin line (docs/09 "Icons"), at the body size, as the Mac's SF Symbol is.
 */
@Composable
private fun CopyAllButton(copied: Boolean, strings: Strings, onCopy: () -> Unit) {
    val ink = blueprint.textMuted
    val label = strings[if (copied) Str.TRANSCRIPT_COPIED else Str.TRANSCRIPT_COPY]
    val side = with(LocalDensity.current) { MaterialTheme.typography.bodyLarge.fontSize.toDp() }
    Box(
        Modifier
            .size(MinTouch)
            .focusRing(RoundedCornerShape(Radius.node))
            // Copied, it cannot be pressed: the arrow.
            .pressCursor(!copied)
            .clickable(role = Role.Button) { if (!copied) onCopy() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(side)) {
            // On a grid of 16 across, as the mark is drawn at 16.
            val unit = size.width / ICON_GRID
            val stroke = Stroke(ICON_LINE.toPx())
            if (copied) {
                val check = Path().apply {
                    moveTo(2.5f * unit, 8.5f * unit)
                    lineTo(6.25f * unit, 12.25f * unit)
                    lineTo(13.5f * unit, 4f * unit)
                }
                drawPath(check, ink, style = stroke)
            } else {
                // The sheet in front at the top right and the corner of the one behind it at the bottom left, as Material's
                // content_copy and SF's doc.on.doc are — and, as theirs, not mirrored in a right-to-left language.
                drawRect(ink, Offset(5.75f * unit, 0.75f * unit), Size(9f * unit, 10.5f * unit), style = stroke)
                val behind = Path().apply {
                    moveTo(1.75f * unit, 4.5f * unit)
                    lineTo(1.75f * unit, 15f * unit)
                    lineTo(10.75f * unit, 15f * unit)
                }
                drawPath(behind, ink, style = stroke)
            }
        }
    }
}

/**
 * A centred notice and at most one centred button under it (docs/09 screen principle 8): Manage usage for a
 * spent plan, nothing where the sentence already says where to go or where trying again cannot help, Retry for the
 * rest. Organize's, and Ask's.
 */
@Composable
internal fun ChatGptFailureNotice(failure: ChatGptFailure, onManageUsage: () -> Unit, onRetry: () -> Unit, strings: Strings) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.s),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // A sentence that says where to go is something to attend to; anything else failed (as on the Mac).
        Text(
            failure.headline.text(strings),
            style = MaterialTheme.typography.bodyMedium,
            color = if (failure.attention) blueprint.warningInk else blueprint.danger,
            textAlign = TextAlign.Center,
        )
        failure.detail?.let {
            Text(it, style = mono.small.copy(textDirection = TextDirection.Ltr), color = blueprint.textMuted, textAlign = TextAlign.Center)
        }
        when (failure.recovery) {
            ChatGptRecovery.MANAGE_USAGE -> BlueprintButton(strings[Str.CHATGPT_MANAGE_USAGE], onManageUsage, tone = ButtonTone.PRIMARY)
            // Retry repeats what the user already asked for, as it was asked.
            ChatGptRecovery.RETRY -> BlueprintButton(strings[Str.RECENT_RETRY], onRetry, tone = ButtonTone.QUIET)
            ChatGptRecovery.NONE -> Unit
        }
    }
}

/** Every item of [notes], in the order the Notes view lists them ([noteGroups]). */
internal fun noteItems(notes: RecordingNotes): List<NoteItem> =
    notes.points + notes.decisions + notes.todos + notes.questions + notes.sections.flatMap { it.items }

/**
 * docs/09 "AI view" — "Editing": [recordingId]'s notes as Edit notes holds them until Save — the overview and each
 * item's words. [original] is what it opened on, which leaving compares against and Save sends the changes from.
 */
internal class NotesDraft(val recordingId: String, val original: RecordingNotes) {
    var overview: String by mutableStateOf(original.overview.orEmpty())

    /** Each item's words by its id. */
    val texts: SnapshotStateMap<String, String> = mutableStateMapOf<String, String>().apply {
        noteItems(original).forEach { put(it.id, it.text) }
    }

    /** The overview as Save sends it — null while it is as it was, trimmed as the core keeps it, empty to remove it. */
    val overviewEdit: String? get() = overview.trim().takeIf { it != original.overview.orEmpty().trim() }

    /** The items whose words changed, trimmed; one emptied is deleted when it is saved. */
    val itemEdits: List<NoteEdit>
        get() = noteItems(original).mapNotNull { item ->
            texts[item.id]?.trim()?.takeIf { it != item.text.trim() }?.let { NoteEdit(item.id, it) }
        }

    val changed: Boolean get() = overviewEdit != null || itemEdits.isNotEmpty()
}

/**
 * docs/09 "AI view" — "Editing": the notes as fields, in the place they were — the overview, when there is one, as one
 * field; then each group's header over its items, each item a field of its words with its owner and due date quieter
 * under it, not editable, and no time. Under them what Save does: an emptied item is deleted, and the notes in storage
 * change and the other devices show them, or they stay on this PC ([note]).
 */
@Composable
internal fun NotesEditor(draft: NotesDraft, note: Str, enabled: Boolean, strings: Strings, modifier: Modifier = Modifier) {
    val palette = blueprint
    Column(modifier) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            if (draft.original.overview != null) {
                NoteField(draft.overview, { draft.overview = it }, strings[Str.NOTES_TAB], enabled)
            }
            noteGroups(draft.original, strings).forEach { (header, items) ->
                SectionHeader(header)
                items.forEach { item ->
                    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        NoteField(draft.texts[item.id].orEmpty(), { draft.texts[item.id] = it }, header, enabled)
                        listOfNotNull(item.owner, item.due).takeIf { it.isNotEmpty() }?.let { said ->
                            Text(
                                said.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                                color = palette.textMuted,
                            )
                        }
                    }
                }
            }
        }
        HairLine()
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
            Text(strings[Str.NOTES_EDIT_DELETES], style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
            Text(strings[note], style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
        }
    }
}

/** One of the editor's plain fields, the transcript editor's: the accent while it has the focus, its words' direction. */
@Composable
private fun NoteField(value: String, onValueChange: (String) -> Unit, label: String, enabled: Boolean) {
    val palette = blueprint
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = label }
            .fieldBox()
            // docs/09 "Component measurements": a field's inset, 12 × 8.
            .padding(horizontal = Space.sm, vertical = Space.s),
        enabled = enabled,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = palette.text, textDirection = TextDirection.Content),
        cursorBrush = SolidColor(palette.accent),
    )
}

/**
 * docs/08 "Organizing" — "Copy and share": the app's words over the notes as text (Copy all, Share → Notes), in the
 * language on show. The table's Korean holds its words together for Skia ([WORD_JOINER]), which a copied text must not
 * carry.
 */
internal fun notesHeadings(strings: Strings): NotesHeadings {
    fun plain(key: Str) = strings[key].replace(WORD_JOINER.toString(), "")
    return NotesHeadings(
        plain(Str.NOTES_TAB), plain(Str.NOTES_KEY_POINTS), plain(Str.NOTES_DECISIONS), plain(Str.NOTES_TODOS), plain(Str.NOTES_QUESTIONS),
        plain(Str.SPEAKER_ME),
    )
}

private const val COPIED_MS = 3_000L

/** The copy and check marks' grid, and their line: docs/09 "Icons", geometric thin lines of 1.5. */
private const val ICON_GRID = 16f
private val ICON_LINE: Dp = 1.5.dp
