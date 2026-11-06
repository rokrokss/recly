package app.recly.windows.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import app.recly.windows.ui.component.BlueprintMenu
import app.recly.windows.ui.component.BlueprintMenuColumn
import app.recly.windows.ui.component.BlueprintMenuRow
import app.recly.windows.ui.component.SectionHeader
import app.recly.windows.ui.component.focusRing
import app.recly.windows.ui.component.pressCursor
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import app.recly.windows.ui.theme.mono
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import recly.core.notes.NoteItem
import recly.core.notes.NoteLine
import recly.core.notes.RecordingNotes

/**
 * docs/09 "Notes view": a recording's notes in the Notes chip's body — the overview, then Decisions, To-dos, Key
 * points and Questions, then the user's own sections under their names, each only while it has items, and nothing at
 * all when there is nothing (no empty state, no label for how far they have come). A row opens the transcript's lines
 * around its moment under itself, one row at a time ([linesAround]); the time at its sentence's end moves the playhead there ([onSeek]) while
 * [canSeek]; its context menu — a right click, the menu key or Shift+F10 — has Delete, which asks nothing ([onDelete]).
 */
@Composable
internal fun NotesBlock(
    notes: RecordingNotes,
    canSeek: Boolean,
    onSeek: (Double) -> Unit,
    linesAround: suspend (Double) -> List<NoteLine>,
    onDelete: (NoteItem) -> Unit,
    strings: Strings,
    /** A time past it is off, as a transcript time past it is. */
    seekableDurationSec: Double = Double.POSITIVE_INFINITY,
    /** The recording's length, which picks the format of every time drawn ([LedgerFormat.clock]). */
    spanSec: Double? = null,
    /**
     * docs/09 "Ask": the items the answer on screen changed or added, and whether it rewrote the overview — their words
     * tinted as the transcript's are.
     */
    changed: Set<String> = emptySet(),
    overviewChanged: Boolean = false,
) {
    if (notes.isEmpty) return
    var open by remember(notes.recordingId) { mutableStateOf<String?>(null) }
    val tint = blueprint.accent.copy(alpha = MATCH_TINT)
    Column(Modifier.fillMaxWidth()) {
        notes.overview?.let {
            Text(
                if (overviewChanged) tinted(it, tint) else AnnotatedString(it),
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                color = blueprint.text,
            )
        }
        noteGroups(notes, strings).forEach { (header, items) ->
            SectionHeader(header)
            items.forEach { item ->
                NoteRow(
                    item = item,
                    expanded = open == item.id,
                    onToggle = { open = if (open == item.id) null else item.id },
                    seekable = canSeek && item.atSec?.let { it < seekableDurationSec } == true,
                    onSeek = onSeek,
                    linesAround = linesAround,
                    onDelete = onDelete,
                    strings = strings,
                    spanSec = spanSec,
                    tint = tint.takeIf { item.id in changed },
                )
            }
        }
    }
}

/**
 * The groups in their order, each with its header — the built-in ones, under the name the user gave one when the notes
 * were made or else the app's word, then the user's sections under the names the notes were made with. A group with
 * nothing in it is not there, header and all.
 */
internal fun noteGroups(notes: RecordingNotes, strings: Strings): List<Pair<String, List<NoteItem>>> =
    (
        listOf(
            (notes.names.decisions ?: strings[Str.NOTES_DECISIONS]) to notes.decisions,
            (notes.names.todos ?: strings[Str.NOTES_TODOS]) to notes.todos,
            (notes.names.points ?: strings[Str.NOTES_KEY_POINTS]) to notes.points,
            (notes.names.questions ?: strings[Str.NOTES_QUESTIONS]) to notes.questions,
        ) + notes.sections.map { it.name to it.items }
    ).filter { it.second.isNotEmpty() }

/** One note's row and, while [expanded], its lines under it; internal for `NotesBlockTest`, which clicks it. */
@Composable
internal fun NoteRow(
    item: NoteItem,
    expanded: Boolean,
    onToggle: () -> Unit,
    seekable: Boolean,
    onSeek: (Double) -> Unit,
    linesAround: suspend (Double) -> List<NoteLine>,
    onDelete: (NoteItem) -> Unit,
    strings: Strings,
    spanSec: Double?,
    /** docs/09 "Ask": the background of its words, where the answer on screen changed or added it. */
    tint: Color? = null,
) {
    val palette = blueprint
    // Where the context menu hangs: the click, or the row's foot for a key.
    var menuAt by remember(item.id) { mutableStateOf<Offset?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    // An item no single line carries has no time, and no transcript to open (docs/09 "Notes view").
    val atSec = item.atSec
    val stamp = atSec?.let { LedgerFormat.clock(it, spanSec) }
    var pressed by remember(item.id) { mutableStateOf(false) }
    var layout by remember(item.id) { mutableStateOf<TextLayoutResult?>(null) }
    val text = noteText(
        item, stamp, palette.textMuted, mono.body.fontFamily, tint,
        time = if (pressed) palette.textMuted.copy(alpha = PRESSED_ALPHA) else palette.textMuted,
    )
    Column(Modifier.fillMaxWidth()) {
        Box {
            Box(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { size = it }
                    // Ahead of the click: a right click opens the menu and is not also the row's click.
                    .pointerInput(item.id) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                    menuAt = event.changes.first().position
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        (event.type == KeyEventType.KeyDown && opensContextMenu(event.key, event.isShiftPressed))
                            .also { if (it) menuAt = Offset(0f, size.height.toFloat()) }
                    }
                    // Still a stop for Tab without a click, for the context menu's key.
                    .focusRing()
                    .then(if (atSec != null) Modifier.pressCursor().clickable(role = Role.Button, onClick = onToggle) else Modifier.focusable())
                    // As tall as its words, 8 above and below (2026-10-31): the touch minimum made a list of one-line
                    // items read double-spaced.
                    .padding(vertical = Space.s),
            ) {
                Text(
                    text,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                    color = palette.text,
                    onTextLayout = { layout = it },
                )
                // The time only moves the playhead, as the transcript's does (2026-10-28): playing or paused stays as it
                // was. An answer's time still plays. A time it cannot reach is only words, whose click is the row's.
                if (atSec != null && stamp != null) {
                    layout?.takeIf { seekable }?.let { lines ->
                        val label = strings[Str.TRANSCRIPT_SEEK, LedgerFormat.elapsed((atSec * 1000).toLong())]
                        CitationBoxes(
                            citationTargets(lines, 0, text.length - stamp.length, text.length),
                            label = { label },
                            onPressed = { pressed = it != null },
                            onPlay = { onSeek(atSec) },
                            keys = true,
                        )
                    }
                }
            }
            menuAt?.let { at ->
                // A point of the window, not of the reading order: placed from the left whatever the language.
                Box(Modifier.absoluteOffset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }) {
                    BlueprintMenu(true, { menuAt = null }) {
                        BlueprintMenuColumn {
                            // Red, as every Delete is: a deleted note never comes back (docs/09 "Accessibility").
                            BlueprintMenuRow(
                                strings[Str.DELETE],
                                {
                                    menuAt = null
                                    onDelete(item)
                                },
                                danger = true,
                            )
                        }
                    }
                }
            }
        }
        if (expanded && atSec != null) NoteLines(item, atSec, linesAround, strings)
    }
}

/** The transcript's lines around [item], the one it is on marked the way the transcript marks the playing group. */
@Composable
private fun NoteLines(item: NoteItem, atSec: Double, linesAround: suspend (Double) -> List<NoteLine>, strings: Strings) {
    val palette = blueprint
    var lines by remember(item.id) { mutableStateOf<List<NoteLine>?>(null) }
    LaunchedEffect(item.id, atSec) { lines = linesAround(atSec) }
    val shown = lines ?: return
    val marked = notedLine(shown, atSec, item.quote)
    Column(Modifier.fillMaxWidth().padding(bottom = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        shown.forEachIndexed { index, line ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(if (index == marked) palette.accent else Color.Transparent))
                Text(
                    noteLineText(line, strings[Str.SPEAKER_ME], palette.textMuted),
                    modifier = Modifier.padding(start = Space.s),
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                    color = palette.text,
                )
            }
        }
    }
}

/**
 * A note as its row says it: the text, the owner after a dash, the deadline after that, quieter, and last its time
 * [stamp] in [monospace] and [time] after a no-break space — the text and the owner on [tint] where an answer changed it
 * (docs/09 "Ask").
 */
internal fun noteText(
    item: NoteItem,
    stamp: String?,
    muted: Color,
    monospace: FontFamily? = null,
    tint: Color? = null,
    time: Color = muted,
): AnnotatedString = buildAnnotatedString {
    val words = item.text + item.rowOwner
    if (tint != null) withStyle(SpanStyle(background = tint)) { append(words) } else append(words)
    withStyle(SpanStyle(color = muted)) { append(item.rowDue) }
    stamp?.let {
        append('\u00A0')
        withStyle(SpanStyle(fontFamily = monospace, color = time)) { append(it) }
    }
}

/** [text] on [tint] whole: the overview an answer rewrote (docs/09 "Ask"). */
private fun tinted(text: String, tint: Color): AnnotatedString = buildAnnotatedString { withStyle(SpanStyle(background = tint)) { append(text) } }

/** One line under an opened note: who said it, quieter, when they have a name — an unnamed `me` is [me] — then what. */
internal fun noteLineText(line: NoteLine, me: String, muted: Color): AnnotatedString = buildAnnotatedString {
    (line.speakerName ?: me.takeIf { line.me })?.let { withStyle(SpanStyle(color = muted)) { append("$it  ") } }
    append(line.text)
}

/**
 * Which of [lines] the note is on: of the lines its quote is in (letters and digits, case folded), the one nearest its
 * time; failing that, the last one starting by its time — the line its `[HH:MM:SS]` was read from. Null for none.
 */
internal fun notedLine(lines: List<NoteLine>, atSec: Double, quote: String): Int? {
    val wanted = folded(quote)
    if (wanted.isNotEmpty()) {
        lines.indices.filter { folded(lines[it].text).contains(wanted) }.minByOrNull { abs(lines[it].atSec - atSec) }?.let { return it }
    }
    return lines.indexOfLast { floor(it.atSec) <= atSec }.takeIf { it >= 0 }
}

private fun folded(text: String): String = text.filter(Char::isLetterOrDigit).lowercase()

/** The keys that open a focused row's context menu on Windows: the menu key, and Shift+F10. */
internal fun opensContextMenu(key: Key, shift: Boolean): Boolean = key == Key.Menu || (shift && key == Key.F10)
