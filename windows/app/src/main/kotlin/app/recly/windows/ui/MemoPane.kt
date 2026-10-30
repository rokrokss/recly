package app.recly.windows.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import app.recly.windows.ui.component.BlueprintButton
import app.recly.windows.ui.component.BlueprintTextField
import app.recly.windows.ui.component.focusRing
import app.recly.windows.ui.component.pressCursor
import app.recly.windows.ui.theme.MinTouch
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import kotlinx.coroutines.delay
import recly.core.notes.Memos
import recly.core.notes.RecordingNotes

/** Which of the detail's chips is on show (docs/09 "AI view"): the transcript, the notes, or the memo. */
internal enum class DetailView { TRANSCRIPT, NOTES, MEMO }

internal val RecordingDetail.view: DetailView
    get() = when {
        showingMemo -> DetailView.MEMO
        showingNotes -> DetailView.NOTES
        else -> DetailView.TRANSCRIPT
    }

/** docs/08 "Memo": a blank memo shows nothing anywhere. */
internal fun hasMemo(memo: String?): Boolean = !memo.isNullOrBlank()

/** The Memo chip: while there is a memo, or while it is the chosen one — emptying it there does not take the view away. */
internal fun showsMemoChip(memo: String?, memoChosen: Boolean): Boolean = memoChosen || hasMemo(memo)

/** The chips' row: the notes' rule ([showsNotesChips]) or the memo's — never over the transcript's editor. */
internal fun showsDetailChips(notes: RecordingNotes?, notesChosen: Boolean, memo: String?, memoChosen: Boolean, editing: Boolean): Boolean =
    !editing && (showsNotesChips(notes, notesChosen, editing = false) || showsMemoChip(memo, memoChosen))

/** The tray row's line: the memo's first line that says something, trimmed; nothing for a blank memo. */
internal fun memoPreview(memo: String?): String? = memo?.lineSequence()?.map(String::trim)?.firstOrNull(String::isNotEmpty)

/**
 * docs/08 "Memo": the memo as one field that saves as the user types — [SAVE_AFTER_MS] after the last key, and when the field
 * leaves the screen (Done, another chip, another recording, the tray popup closing on focus loss). There is no Save and
 * nothing says it saved. The core's value replaces what is shown only while nothing typed is waiting to be saved: the
 * field's own saves come back through it, and another device's change arrives the same way.
 */
@Composable
internal fun MemoField(
    recordingId: String,
    /** The memo as the core has it; null until it has been read. */
    stored: String?,
    onSave: (String) -> Unit,
    /** Signed in to ChatGPT and agreed to organizing: the line under the field says the notes read it. */
    agreed: Boolean,
    strings: Strings,
    modifier: Modifier = Modifier,
    minLines: Int = MEMO_LINES,
    maxLines: Int = Int.MAX_VALUE,
    focusRequester: FocusRequester? = null,
) = key(recordingId) {
    // Keyed by the recording, so a field moved to another one leaves with its own typing and saves it to its own.
    var text by remember { mutableStateOf(stored.orEmpty()) }
    var typed by remember { mutableStateOf(false) }
    LaunchedEffect(stored) { if (stored != null && !typed) text = stored }
    LaunchedEffect(text, typed) {
        if (!typed) return@LaunchedEffect
        delay(SAVE_AFTER_MS)
        onSave(text)
        typed = false
    }
    val latest by rememberUpdatedState(text)
    val waiting by rememberUpdatedState(typed)
    val save by rememberUpdatedState(onSave)
    DisposableEffect(Unit) { onDispose { if (waiting) save(latest) } }
    BlueprintTextField(
        value = text,
        onValueChange = { value ->
            val kept = Memos.cut(value)
            if (kept != text) {
                text = kept
                typed = true
            }
        },
        label = strings[Str.MEMO_TITLE],
        modifier = modifier,
        hint = strings[Str.MEMO_NOTE].takeIf { agreed },
        singleLine = false,
        minLines = minLines,
        maxLines = maxLines,
        placeholder = strings[Str.MEMO_PLACEHOLDER],
        focusRequester = focusRequester,
        labelShown = false,
    )
}

/** docs/09 "AI view": the Memo chip's body — the field, always editable, and the line under it; [focus] from More's `Memo`. */
@Composable
internal fun MemoPane(model: ShellModel, detail: RecordingDetail, focus: Boolean, strings: Strings, modifier: Modifier = Modifier) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(detail.recordingId, focus) { if (focus) runCatching { requester.requestFocus() } }
    Column(modifier.verticalScroll(rememberScrollState()).padding(Space.m)) {
        MemoField(
            recordingId = detail.recordingId,
            stored = detail.memo,
            onSave = { model.saveMemo(detail.recordingId, it) },
            agreed = model.notesAgreed,
            strings = strings,
            modifier = Modifier.fillMaxWidth(),
            focusRequester = requester,
        )
    }
}

/**
 * docs/08 "Memo" · docs/09 screen principle 6: the tray's `Memo` row while recording — there even when the memo is empty,
 * since it is where one is written. Its first line is beside the label; a click opens the field in place under it, and
 * Done folds it again.
 */
@Composable
internal fun TrayMemo(model: ShellModel, recordingId: String, strings: Strings) {
    val palette = blueprint
    val stored by remember(recordingId) { model.observeMemo(recordingId) }.collectAsState(initial = null)
    var open by remember(recordingId) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(start = Space.m, end = Space.m, bottom = Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .defaultMinSize(minHeight = MinTouch)
                .focusRing()
                .pressCursor()
                .clickable(role = Role.Button) { open = !open },
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(strings[Str.MEMO_TITLE], style = MaterialTheme.typography.bodyMedium, color = palette.text)
            if (!open) {
                memoPreview(stored)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                        color = palette.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (open) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            MemoField(
                recordingId = recordingId,
                stored = stored,
                onSave = { model.saveMemo(recordingId, it) },
                agreed = model.notesAgreed,
                strings = strings,
                modifier = Modifier.fillMaxWidth(),
                minLines = TRAY_MEMO_LINES,
                maxLines = TRAY_MEMO_LINES,
                focusRequester = focus,
            )
            BlueprintButton(
                strings[Str.EDIT_DONE],
                { open = false },
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

/** How long the field waits after the last key before it saves. */
internal const val SAVE_AFTER_MS = 800L

private const val MEMO_LINES = 8
private const val TRAY_MEMO_LINES = 4
