package app.recly.windows.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import app.recly.windows.ui.component.BlueprintButton
import app.recly.windows.ui.component.BlueprintChip
import app.recly.windows.ui.component.BlueprintTextField
import app.recly.windows.ui.component.ButtonTone
import app.recly.windows.ui.component.CloseMark
import app.recly.windows.ui.component.HairLine
import app.recly.windows.ui.component.LoadingText
import app.recly.windows.ui.theme.MinTouch
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import recly.core.chatgpt.AskChanges
import recly.core.chatgpt.AskPreset
import recly.core.chatgpt.AskRequest
import recly.core.chatgpt.AskState
import recly.core.chatgpt.ChatGptConnection

/**
 * docs/09 "Ask": where the composer is — only where ChatGPT is offered ([connection] neither unknown nor `Unavailable`)
 * and the recording has a transcript to ask about, and not while the page loads, either editor is open or the memo is
 * shown. While Ask cannot run the field says why ([askBlocked]).
 */
internal fun showsAsk(connection: ChatGptConnection?, loading: Boolean, editing: Boolean, memoShown: Boolean, hasTranscript: Boolean): Boolean =
    connection != null && connection != ChatGptConnection.Unavailable && !loading && !editing && !memoShown && hasTranscript

/** docs/09 "Ask": what the answer on screen changed in the transcript and the summary, until it is undone or closed. */
internal val RecordingDetail.askChanges: AskChanges?
    get() = (ask as? AskState.Ready)?.answer?.changes

/**
 * docs/09 "Ask": the view an answer's changes show — the summary when only it changed and the recording has one
 * ([hasNotes]), the transcript when only it changed; null leaves the view as it is.
 */
internal fun askChangesView(changes: AskChanges, hasNotes: Boolean): DetailView? = when {
    changes.transcriptCount == 0 && changes.summaryCount > 0 -> DetailView.NOTES.takeIf { hasNotes }
    changes.summaryCount == 0 && changes.transcriptCount > 0 -> DetailView.TRANSCRIPT
    else -> null
}

/** `Changed: Transcript 2 · Summary 1` — only the parts that changed. */
internal fun askChangesLine(changes: AskChanges, strings: Strings): String = strings[
    Str.ASK_CHANGED,
    listOfNotNull(
        strings[Str.ASK_CHANGED_TRANSCRIPT, changes.transcriptCount].takeIf { changes.transcriptCount > 0 },
        strings[Str.ASK_CHANGED_SUMMARY, changes.summaryCount].takeIf { changes.summaryCount > 0 },
    ).joinToString(" · "),
]

/** What the detail says under its header after Undo: nothing once it was all undone, else `Could not undo` for a moment. */
internal fun askUndoNotice(undone: Boolean): Str? = Str.ASK_UNDO_FAILED.takeUnless { undone }

/** Whether Send — or Enter — sends [typed] now: not while a request runs, nor while Ask cannot run ([reason]), nor empty. */
internal fun asksNow(typed: String, running: Boolean, reason: String?): Boolean = !running && reason == null && typed.isNotBlank()

/** [field] no longer than the core takes ([AskRequest.MAX]), the cursor kept inside it. */
internal fun capped(field: TextFieldValue): TextFieldValue {
    if (field.text.length <= AskRequest.MAX) return field
    val text = field.text.take(AskRequest.MAX)
    return TextFieldValue(text, TextRange(field.selection.start.coerceAtMost(text.length), field.selection.end.coerceAtMost(text.length)))
}

/** [field] with its selection replaced by a line break and the cursor after it: Shift+Enter in the request. */
internal fun insertLineBreak(field: TextFieldValue): TextFieldValue {
    val start = minOf(field.selection.start, field.selection.end)
    val end = maxOf(field.selection.start, field.selection.end)
    return TextFieldValue(field.text.replaceRange(start, end, "\n"), TextRange(start + 1))
}

/**
 * docs/09 "Ask": the foot of the detail, under the transcript or the summary — what the last request came to
 * ([AskResult]), then the composer ([AskComposer]). The presets are read again whenever the transcript changes. Nothing
 * here is saved: the core keeps the answer in this process (docs/08 "Ask"), and leaving the recording closes it
 * ([RecordingsWindow]).
 */
@Composable
internal fun AskBar(
    model: ShellModel,
    detail: RecordingDetail,
    connection: ChatGptConnection,
    /** What the composer holds — the detail's, so another recording starts with it empty. */
    field: TextFieldValue,
    onField: (TextFieldValue) -> Unit,
    canPlay: Boolean,
    onPlay: (Double) -> Unit,
    /** How tall an answer is before it scrolls in its place ([answerHeight]). */
    answerHeight: Dp,
    strings: Strings,
) {
    val presets by produceState(emptyList<AskPreset>(), detail.recordingId, detail.transcript?.createdAt) {
        value = model.askPresets(detail.recordingId)
    }
    val reason = askBlocked(detail.writing, detail.hasTranscript, detail.busyReason.takeIf { detail.transcribing }, connection)
    val ask: (AskPreset?, String?) -> Unit = { preset, question -> model.ask(detail.recordingId, preset, question) }
    Column(Modifier.fillMaxWidth()) {
        HairLine()
        Column(
            Modifier.fillMaxWidth().background(blueprint.surface).padding(horizontal = Space.m, vertical = Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            if (detail.ask != AskState.None) {
                AskResult(
                    state = detail.ask,
                    modelLabel = { id, name -> chatGptModelLabel(id, name, connection) },
                    undoing = detail.askUndoing,
                    onUndo = { model.undoAsk(detail.recordingId) },
                    onAsk = ask,
                    onClose = { model.closeAsk(detail.recordingId) },
                    onManageUsage = { model.chatGpt?.openUsage() },
                    canPlay = canPlay,
                    onPlay = onPlay,
                    strings = strings,
                    seekableDurationSec = detail.audio.totalSec,
                    answerHeight = answerHeight,
                )
            }
            // The chips only while nothing is over the field, so the bar stays low under an answer.
            AskComposer(field, onField, presets.takeIf { detail.ask == AskState.None }.orEmpty(), reason?.let { strings[it] },
                detail.ask is AskState.Running, ask, strings)
        }
    }
}

/**
 * docs/09 "Ask": one field for a question about the recording or a change to its transcript and summary, from one line
 * up to six, with Send. The presets are chips over it while it has the cursor, is empty and Ask can run (the bar gives
 * none while an answer is over it); a chip asks at once. Enter sends and Shift+Enter starts a new line where the cursor is (2026-10-10); sending empties the field.
 * While Ask cannot run the field says why in its place ([reason]) and takes nothing; while a request runs it waits.
 */
@Composable
internal fun AskComposer(
    field: TextFieldValue,
    onField: (TextFieldValue) -> Unit,
    presets: List<AskPreset>,
    /** Why nothing can be asked now, or null. */
    reason: String?,
    running: Boolean,
    onAsk: (AskPreset?, String?) -> Unit,
    strings: Strings,
) {
    // The field's, or a chip's under it: a press on a chip keeps the chips there until it has asked.
    var focused by remember { mutableStateOf(false) }
    val typed = field.text.trim()
    val sends = asksNow(typed, running, reason)
    val send = {
        if (sends) {
            onAsk(null, typed)
            onField(TextFieldValue(""))
        }
    }
    Column(Modifier.fillMaxWidth().onFocusChanged { focused = it.hasFocus }, verticalArrangement = Arrangement.spacedBy(Space.s)) {
        if (focused && typed.isEmpty() && !running && reason == null && presets.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                presets.forEach { preset ->
                    BlueprintChip(askPresetLabel(preset, strings), selected = false, onClick = { onAsk(preset, null) }, role = Role.Button)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.Bottom) {
            val placeholder = reason ?: strings[Str.ASK_PLACEHOLDER]
            BlueprintTextField(
                field,
                { onField(capped(it)) },
                label = placeholder,
                modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                    when {
                        event.key != Key.Enter && event.key != Key.NumPadEnter -> false
                        event.isShiftPressed -> {
                            if (event.type == KeyEventType.KeyDown) onField(capped(insertLineBreak(field)))
                            true
                        }
                        else -> {
                            if (event.type == KeyEventType.KeyDown) send()
                            true
                        }
                    }
                },
                singleLine = false,
                maxLines = FIELD_LINES,
                enabled = !running && reason == null,
                placeholder = placeholder,
                labelShown = false,
            )
            BlueprintButton(strings[Str.ASK_SEND], send, tone = ButtonTone.PRIMARY, enabled = sends)
        }
    }
}

/**
 * docs/09 "Ask": what the last request came to, over the composer — `Working…` while it runs; the answer with its
 * citations playable as the summary's are, its model and Copy all; what it changed, with Undo; that it changed nothing;
 * or why it failed, with the notes' notice — Retry asks the same preset or the same words again. Its close mark puts it
 * away, except while it runs.
 */
@Composable
internal fun AskResult(
    state: AskState,
    /** The footer's model, from its id and the name kept with the answer ([chatGptModelLabel]). */
    modelLabel: (String, String?) -> String,
    /** The changes line's Undo is bringing the transcript and the summary back. */
    undoing: Boolean,
    onUndo: () -> Unit,
    onAsk: (AskPreset?, String?) -> Unit,
    onClose: () -> Unit,
    onManageUsage: () -> Unit,
    canPlay: Boolean,
    onPlay: (Double) -> Unit,
    strings: Strings,
    seekableDurationSec: Double = Double.POSITIVE_INFINITY,
    /** Taller than this, the answer scrolls in its place ([answerHeight]). */
    answerHeight: Dp = ANSWER_HEIGHT,
) {
    val palette = blueprint
    val small = MaterialTheme.typography.bodySmall
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            when (state) {
                AskState.None -> Unit
                is AskState.Running -> LoadingText(strings[Str.ASK_WORKING], small, palette.textMuted, Modifier.defaultMinSize(minHeight = MinTouch))
                is AskState.Ready -> {
                    val answer = state.answer
                    if (answer.text.isNotEmpty()) {
                        Box(Modifier.fillMaxWidth().heightIn(max = answerHeight).verticalScroll(rememberScrollState())) {
                            CitedText(answer.text, canPlay, onPlay, strings, seekableDurationSec)
                        }
                        ChatGptFooter(strings[Str.SUMMARY_MODEL, modelLabel(answer.model, answer.modelName)], answer.recordingId, strings) {
                            answer.text
                        }
                    }
                    val changes = answer.changes
                    when {
                        changes != null -> AskChangedLine(changes, undoing, onUndo, strings)
                        answer.text.isEmpty() -> Box(Modifier.defaultMinSize(minHeight = MinTouch), contentAlignment = Alignment.CenterStart) {
                            Text(strings[Str.ASK_NOTHING], style = small, color = palette.textMuted)
                        }
                    }
                }
                is AskState.Failed -> ChatGptFailureNotice(
                    chatGptFailure(state.reason, Str.ASK_FAILED),
                    onManageUsage,
                    { onAsk(state.preset, state.question) },
                    strings,
                )
            }
        }
        if (state !is AskState.Running) CloseMark(strings[Str.CLOSE], onClose)
    }
}

/** `Changed: Transcript 2 · Summary 1`, and the way back — a quiet Undo, `Undoing…` while it goes. */
@Composable
private fun AskChangedLine(changes: AskChanges, undoing: Boolean, onUndo: () -> Unit, strings: Strings) {
    val small = MaterialTheme.typography.bodySmall
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
        Text(askChangesLine(changes, strings), modifier = Modifier.weight(1f), style = small, color = blueprint.textMuted)
        if (undoing) {
            LoadingText(strings[Str.ASK_UNDOING], small, blueprint.textMuted, Modifier.defaultMinSize(minHeight = MinTouch))
        } else {
            BlueprintButton(strings[Str.ASK_UNDO], onUndo, tone = ButtonTone.QUIET)
        }
    }
}

/** From one line of request up to six before the field scrolls. */
private const val FIELD_LINES = 6

/**
 * An answer taller than a third of the detail ([page]) scrolls in its place, so the page above keeps most of its height —
 * and never taller than 320 (the phone's is 240), however tall the window.
 */
internal fun answerHeight(page: Dp): Dp = if (page > 0.dp) minOf(ANSWER_HEIGHT, page / 3) else ANSWER_HEIGHT

private val ANSWER_HEIGHT = 320.dp
