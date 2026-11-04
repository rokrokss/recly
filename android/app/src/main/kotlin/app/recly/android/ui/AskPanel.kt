package app.recly.android.ui

import androidx.annotation.StringRes
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.recly.android.R
import app.recly.android.settings.AppLanguage
import app.recly.android.ui.component.BlueprintButton
import app.recly.android.ui.component.BlueprintField
import app.recly.android.ui.component.ButtonTone
import app.recly.android.ui.component.Glyph
import app.recly.android.ui.component.GlyphButton
import app.recly.android.ui.component.HairLine
import app.recly.android.ui.component.LoadingText
import app.recly.android.ui.theme.MinTouch
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import recly.core.chatgpt.AskChanges
import recly.core.chatgpt.AskPreset
import recly.core.chatgpt.AskRequest
import recly.core.chatgpt.AskState
import recly.core.chatgpt.ChatGptConnection
import recly.core.chatgpt.ChatGptModel
import recly.core.transcribe.Transcript

/** docs/08 "Ask": each preset under its own words; Translate names the app's language, as the App language list does. */
@StringRes
internal fun AskPreset.presetLabel(): Int = when (this) {
    AskPreset.ACTION_ITEMS -> R.string.ask_action_items
    AskPreset.OPEN_QUESTIONS -> R.string.ask_open_questions
    AskPreset.TRANSLATE -> R.string.ask_translate
    AskPreset.MY_SPEAKING -> R.string.ask_my_speaking
}

/**
 * docs/09 "Ask": the foot of the page is there where ChatGPT is offered and the recording has a transcript that says
 * something — not while the page loads, while the transcript or the summary is being edited ([editing]), nor on the memo.
 */
internal fun showsAskBar(chatGpt: ChatGptConnection, loading: Boolean, editing: Boolean, view: DetailView, transcript: Transcript?): Boolean =
    chatGpt !is ChatGptConnection.Unavailable && !loading && !editing && view != DetailView.MEMO &&
        transcript?.segments?.any { it.text.isNotBlank() } == true

/**
 * Why nothing can be asked now — the field's placeholder, while it is off — or null when it can: `Still recording` while
 * the recorder writes into this take, then the reasons More's Ask had ([askReason]).
 */
@StringRes
internal fun composerReason(
    writing: Boolean,
    hasTranscript: Boolean,
    transcribing: Boolean,
    @StringRes busyReason: Int,
    connection: ChatGptConnection,
): Int? = if (writing) R.string.detail_still_recording else askReason(hasTranscript, transcribing, busyReason, connection)

/** What the field takes: no more than [AskRequest.MAX] characters, never half of a character. */
internal fun askInput(text: String): String {
    if (text.length <= AskRequest.MAX) return text
    val cut = text.take(AskRequest.MAX)
    return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
}

/** Send is pressable with words in the field, nothing running and nothing in the way ([reason]). */
internal fun canSend(running: Boolean, reason: Int?, text: String): Boolean = !running && reason == null && text.isNotBlank()

/** The preset chips over the field: while it has the cursor and is empty, and only while a chip could ask. */
internal fun showsPresets(focused: Boolean, text: String, running: Boolean, reason: Int?, presets: List<AskPreset>): Boolean =
    focused && text.isBlank() && !running && reason == null && presets.isNotEmpty()

/**
 * The keyboard's Return sends, as its key says: [next] is [previous] with one new line typed into it. A paste of several
 * lines keeps them — another transcript is pasted whole.
 */
internal fun typedNewline(previous: String, next: String): Boolean =
    next.length == previous.length + 1 && next.count { it == '\n' } == previous.count { it == '\n' } + 1

/** `Changed: Transcript 2 · Summary 1` — each document that changed, with how many places in it. */
internal fun changedParts(changes: AskChanges): List<Pair<Int, Int>> = listOfNotNull(
    (R.string.ask_changed_transcript to changes.transcriptCount).takeIf { changes.transcriptCount > 0 },
    (R.string.ask_changed_summary to changes.summaryCount).takeIf { changes.summaryCount > 0 },
)

/**
 * docs/09 "Ask": changes to one of the two turn the page to it — the summary only where the recording has one to show;
 * null to stay where it is, as when both changed.
 */
internal fun askChangesView(changes: AskChanges, hasNotes: Boolean): DetailView? = when {
    changes.transcriptCount == 0 && changes.summaryCount > 0 -> DetailView.NOTES.takeIf { hasNotes }
    changes.summaryCount == 0 && changes.transcriptCount > 0 -> DetailView.TRANSCRIPT
    else -> null
}

/** What the line under the header says after the changes line's Undo: nothing when it undid them all. */
@StringRes
internal fun undoRefusal(undone: Boolean): Int? = if (undone) null else R.string.ask_undo_failed

/**
 * docs/09 "Ask": the foot of the detail, over the player — a hairline, then on the surface what the last request came to
 * ([AskResult]) and the composer ([AskComposer]).
 */
@Composable
internal fun AskBar(
    state: AskState,
    presets: List<AskPreset>,
    @StringRes reason: Int?,
    models: List<ChatGptModel>,
    question: String,
    onQuestion: (String) -> Unit,
    focused: Boolean,
    onFocus: (Boolean) -> Unit,
    undoing: Boolean,
    canSeek: (Double) -> Boolean,
    onSeek: (Double) -> Unit,
    onAsk: (AskPreset?, String?) -> Unit,
    onUndo: () -> Unit,
    onClose: () -> Unit,
    answerHeight: Dp,
) {
    HairLine()
    Column(
        Modifier.fillMaxWidth().background(blueprint.surface).padding(horizontal = Space.m, vertical = Space.s).testTag("ask-bar"),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        if (state !is AskState.None) {
            AskResult(state, models, undoing, canSeek, onSeek, onUndo,
                onRetry = { (state as? AskState.Failed)?.let { onAsk(it.preset, it.question) } }, onClose = onClose,
                answerHeight = answerHeight)
        }
        AskComposer(question, onQuestion, presets, reason, running = state is AskState.Running, focused, onFocus, onAsk)
    }
}

/**
 * docs/09 "Ask": one field for a question about the recording or a change to its transcript and summary, a line that grows
 * to six and then scrolls, and `Send` at its end. The keyboard's Send sends, and so does a new line typed into the field;
 * a paste keeps its lines. While it has the cursor and is empty the presets are chips over it, a tap asking (the page gives
 * none while an answer is over it). While nothing
 * can be asked ([reason]) the field is off and its placeholder says why; while a request runs it takes the next one and
 * only Send waits. Sending empties it.
 */
@Composable
internal fun AskComposer(
    question: String,
    onQuestion: (String) -> Unit,
    presets: List<AskPreset>,
    @StringRes reason: Int?,
    running: Boolean,
    focused: Boolean,
    onFocus: (Boolean) -> Unit,
    onAsk: (AskPreset?, String?) -> Unit,
) {
    val send = {
        if (canSend(running, reason, question)) {
            val asked = question.trim()
            onQuestion("")
            onAsk(null, asked)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        if (showsPresets(focused, question, running, reason, presets)) {
            // The app's own language, under its own name — the App language list's word for it.
            val language = stringResource(AppLanguage.effective(LocalConfiguration.current.locales[0]).labelRes())
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                presets.forEach { preset ->
                    // An action, not a choice: a tap asks. At the status badge's size, as a time button is (2026-11-04).
                    BlueprintButton(
                        if (preset == AskPreset.TRANSLATE) stringResource(preset.presetLabel(), language) else stringResource(preset.presetLabel()),
                        onClick = { onAsk(preset, null) },
                        modifier = Modifier.testTag("ask-${preset.name.lowercase()}"),
                        tone = ButtonTone.QUIET,
                        compact = true,
                        tight = true,
                    )
                }
            }
        }
        val placeholder = reason?.let { stringResource(it) } ?: stringResource(R.string.ask_placeholder)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.Bottom) {
            BlueprintField(
                question,
                { next -> if (typedNewline(question, next)) send() else onQuestion(askInput(next)) },
                modifier = Modifier.weight(1f),
                placeholder = placeholder,
                // 2026-10-10 (2.13): the user's words in their own direction.
                textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                fieldModifier = Modifier.onFocusChanged { onFocus(it.isFocused) }.semantics { contentDescription = placeholder }.testTag("ask-field"),
                maxLines = FIELD_LINES,
                // While a request runs the next one can be typed; only Send waits (2026-11-04).
                enabled = reason == null,
            )
            BlueprintButton(stringResource(R.string.ask_send), send, tone = ButtonTone.PRIMARY,
                enabled = canSend(running, reason, question), modifier = Modifier.testTag("ask-send"))
        }
    }
}

/**
 * docs/09 "Ask": what the last request came to, over the composer — `Working…` while it runs; the answer, its citations
 * playing from their time ([canSeek], [onSeek] — the transcript's own seek), scrolling in its place past [answerHeight],
 * with `ChatGPT · <model>` and Copy all; what it changed, with `Undo` (`Undoing…` while [undoing]); that it changed
 * nothing; or why it failed, with Retry asking the same again. The ✕ at its end closes it, except while it runs. Nothing
 * here is saved: the core keeps it in this process (docs/08 "Ask").
 */
@Composable
internal fun AskResult(
    state: AskState,
    models: List<ChatGptModel>,
    undoing: Boolean,
    canSeek: (Double) -> Boolean,
    onSeek: (Double) -> Unit,
    onUndo: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    answerHeight: Dp,
) {
    val palette = blueprint
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            when (state) {
                AskState.None -> Unit
                is AskState.Running -> Box(Modifier.defaultMinSize(minHeight = MinTouch), contentAlignment = Alignment.CenterStart) {
                    LoadingText(stringResource(R.string.ask_working), MaterialTheme.typography.bodySmall, palette.textMuted,
                        Modifier.testTag("ask-running"))
                }
                is AskState.Ready -> {
                    val answer = state.answer
                    if (answer.text.isNotEmpty()) {
                        Box(Modifier.fillMaxWidth().heightIn(max = answerHeight).verticalScroll(rememberScrollState())) {
                            SelectionContainer(Modifier.fillMaxWidth()) {
                                CitedText(answer.text, canSeek, onSeek, Modifier.testTag("ask-answer"), padding = 0.dp)
                            }
                        }
                        CopyFooter(stringResource(R.string.chatgpt_footer, modelLabel(answer.modelName, answer.model, models)), answer.text,
                            Modifier.testTag("ask-copy"), padding = 0.dp)
                    }
                    val changes = answer.changes
                    if (changes != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s),
                            verticalAlignment = Alignment.CenterVertically) {
                            val parts = changedParts(changes).map { (part, count) -> stringResource(part, count) }
                            Text(stringResource(R.string.ask_changed, parts.joinToString(" · ")), Modifier.weight(1f).testTag("ask-changed"),
                                style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
                            if (undoing) {
                                // In the button's own height, so the line does not move.
                                Box(Modifier.defaultMinSize(minHeight = MinTouch), contentAlignment = Alignment.Center) {
                                    LoadingText(stringResource(R.string.ask_undoing), MaterialTheme.typography.bodySmall, palette.textMuted)
                                }
                            } else {
                                BlueprintButton(stringResource(R.string.ask_undo), onUndo, tone = ButtonTone.QUIET, modifier = Modifier.testTag("ask-undo"))
                            }
                        }
                    } else if (answer.text.isEmpty()) {
                        Box(Modifier.defaultMinSize(minHeight = MinTouch), contentAlignment = Alignment.CenterStart) {
                            Text(stringResource(R.string.ask_nothing_changed), Modifier.testTag("ask-nothing"),
                                style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
                        }
                    }
                }
                is AskState.Failed -> ChatGptFailure(state.reason, R.string.ask_failed, onRetry, Modifier.fillMaxWidth(), retryTag = "ask-retry")
            }
        }
        if (state !is AskState.Running) {
            GlyphButton(Glyph.CLOSE, stringResource(R.string.action_close), onClose, Modifier.testTag("ask-close"))
        }
    }
}

/** The field grows to six lines, then scrolls. */
private const val FIELD_LINES = 6

/**
 * An answer taller than a third of the [page] scrolls in its place, so the page above keeps most of its height — and never
 * taller than 240 (the desktops' is 320), however tall the page.
 */
internal fun answerHeight(page: Dp): Dp = if (page > 0.dp) minOf(ANSWER_HEIGHT, page / 3) else ANSWER_HEIGHT

private val ANSWER_HEIGHT = 240.dp
