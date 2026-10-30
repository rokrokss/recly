package app.recly.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.recly.android.R
import app.recly.android.ui.component.BlueprintButton
import app.recly.android.ui.component.BlueprintDialog
import app.recly.android.ui.component.BlueprintField
import app.recly.android.ui.component.ButtonTone
import app.recly.android.ui.component.focusRing
import app.recly.android.ui.theme.MinTouch
import app.recly.android.ui.theme.Radius
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import kotlinx.coroutines.delay
import recly.core.notes.Memos

/** docs/08 "Memo": a save 0.8 s after the last keystroke, so typing does not write each one. */
internal const val MEMO_SAVE_DELAY_MS = 800L

/** What the field takes: the core keeps [Memos.MAX] characters, so the field never holds more than it will. */
internal fun memoInput(text: String): String = Memos.cut(text)

/** The row and the list show the memo's first line that says anything; null when it says nothing. */
internal fun memoPreview(memo: String): String? = memo.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty)

/**
 * docs/08 "Memo": the memo's one field, saved as the user types — [MEMO_SAVE_DELAY_MS] after the last keystroke, and
 * when it leaves the screen — so nothing that closes it loses a word. [memo] is the core's value: it replaces the
 * field's only while the field holds nothing unsaved, since the field's own saves come back through it too. [focus]: the
 * field was opened to be written in, so it takes the cursor and the keyboard comes up.
 */
@Composable
internal fun MemoField(
    recordingId: String,
    memo: String,
    agreed: Boolean,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
    minLines: Int = 6,
    focus: Boolean = false,
) {
    var text by remember(recordingId) { mutableStateOf(memo) }
    var saved by remember(recordingId) { mutableStateOf(memo) }
    LaunchedEffect(recordingId, memo) {
        if (text == saved) {
            text = memo
            saved = memo
        }
    }
    val save by rememberUpdatedState(onSave)
    LaunchedEffect(recordingId, text) {
        if (text == saved) return@LaunchedEffect
        delay(MEMO_SAVE_DELAY_MS)
        saved = text
        save(text)
    }
    val latest by rememberUpdatedState(text)
    val latestSaved by rememberUpdatedState(saved)
    DisposableEffect(recordingId) { onDispose { if (latest != latestSaved) save(latest) } }
    val requester = remember { FocusRequester() }
    LaunchedEffect(recordingId, focus) { if (focus) requester.requestFocus() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s)) {
        BlueprintField(
            value = text,
            onValueChange = { text = memoInput(it) },
            placeholder = stringResource(R.string.memo_placeholder),
            fieldModifier = fieldModifier.focusRequester(requester).testTag("memo-field"),
            minLines = minLines,
            maxLines = Int.MAX_VALUE,
        )
        // Only where the notes will read it: signed in to ChatGPT, and organizing agreed to.
        if (agreed) {
            Text(stringResource(R.string.memo_footnote), style = MaterialTheme.typography.bodySmall, color = blueprint.textMuted,
                modifier = Modifier.testTag("memo-footnote"))
        }
    }
}

/**
 * docs/08 "Memo": the recording screen's `Memo` row while it records — there even when the memo is empty, since it is
 * where the memo is written, with its first line muted at its end.
 */
@Composable
internal fun MemoRow(memo: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = blueprint
    val label = stringResource(R.string.memo_title)
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MinTouch)
            .focusRing(palette, Radius.node)
            // Quiet, but the 3:1 a control's edge needs on the page, as the state nodes above it.
            .border(palette.line, palette.inputBorder, RoundedCornerShape(Radius.node))
            .background(palette.surface, RoundedCornerShape(Radius.node))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = Space.sm, vertical = Space.s)
            .testTag("memo-row"),
        horizontalArrangement = Arrangement.spacedBy(Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = palette.text)
        memoPreview(memo)?.let {
            Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = palette.textMuted, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

/** docs/08 "Memo": the row's editor — the field, and `Done`, which only closes it: the field saved as it went. */
@Composable
internal fun MemoDialog(recordingId: String, memo: String, agreed: Boolean, onSave: (String) -> Unit, onClose: () -> Unit) {
    BlueprintDialog(
        title = stringResource(R.string.memo_title),
        onDismissRequest = onClose,
        actions = {
            BlueprintButton(stringResource(R.string.job_state_done), onClose, tone = ButtonTone.PRIMARY, modifier = Modifier.testTag("memo-done"))
        },
    ) {
        MemoField(recordingId, memo, agreed, onSave, Modifier.fillMaxWidth(), minLines = 5, focus = true)
    }
}

/** docs/09 "AI view": the detail's `Memo` view — the field always open, filling the page; [focus] from More's `Memo`. */
@Composable
internal fun MemoPane(recordingId: String, memo: String, agreed: Boolean, onSave: (String) -> Unit, focus: Boolean, modifier: Modifier = Modifier) {
    MemoField(
        recordingId, memo, agreed, onSave,
        modifier.padding(horizontal = Space.m, vertical = Space.sm).testTag("memo-pane"),
        fieldModifier = Modifier.defaultMinSize(minHeight = 160.dp),
        minLines = 8,
        focus = focus,
    )
}
