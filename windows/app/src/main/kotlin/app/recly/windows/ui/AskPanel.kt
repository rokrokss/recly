package app.recly.windows.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import app.recly.windows.ui.component.BlueprintButton
import app.recly.windows.ui.component.BlueprintChip
import app.recly.windows.ui.component.BlueprintDialog
import app.recly.windows.ui.component.BlueprintTextField
import app.recly.windows.ui.component.ButtonTone
import app.recly.windows.ui.component.LoadingText
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import recly.core.chatgpt.AskPreset
import recly.core.chatgpt.AskState
import recly.core.chatgpt.ChatGptConnection

/**
 * docs/08 "Ask": one question about one recording — a preset or the user's own — and its answer, in a dialog over
 * the detail. Nothing is kept: closing it forgets the answer ([ShellModel.closeAsk]), though a question still
 * running finishes, and reopening the panel shows it running.
 *
 * Its one way out is the close mark in its title row (2026-10-10). It is as tall as what it holds, up to
 * [ASK_HEIGHT], where the body scrolls under the title; and it leaves the detail usable under it — the player there
 * pauses what one of the answer's times started.
 */
@Composable
internal fun AskDialog(
    model: ShellModel,
    detail: RecordingDetail,
    canPlay: Boolean,
    onPlay: (Double) -> Unit,
    strings: Strings,
    theme: @Composable (@Composable () -> Unit) -> Unit,
) {
    val presets by produceState(emptyList<AskPreset>(), detail.recordingId) { value = model.askPresets(detail.recordingId) }
    // Gone with the window, or with another recording picked under it: closed as the close mark closes it.
    DisposableEffect(detail.recordingId) { onDispose { model.closeAsk() } }
    BlueprintDialog(
        title = strings[Str.ASK_TITLE],
        onDismissRequest = model::closeAsk,
        actions = null,
        height = ASK_OPENING_HEIGHT,
        theme = theme,
        fitContent = true,
        maxHeight = ASK_HEIGHT,
        closeLabel = strings[Str.CLOSE],
        modeless = true,
    ) {
        AskPanel(
            state = detail.ask,
            presets = presets,
            modelLabel = { id, name -> summaryModelLabel(id, name, model.chatGpt?.connection ?: ChatGptConnection.SignedOut) },
            canPlay = canPlay,
            onPlay = onPlay,
            onAsk = model::ask,
            onManageUsage = { model.chatGpt?.openUsage() },
            strings = strings,
            seekableDurationSec = detail.audio.totalSec,
        )
    }
}

/**
 * The panel's body: the presets as chips that ask at once, the user's own question with Ask, and the answer — the
 * square loader while it runs, the text with its times and the summary's footer when it is ready, the summary's
 * failure notice when it is not. Everything that asks waits while a question runs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AskPanel(
    state: AskState,
    presets: List<AskPreset>,
    /** The footer's model, from its id and the name kept with the answer ([summaryModelLabel]). */
    modelLabel: (String, String?) -> String,
    canPlay: Boolean,
    onPlay: (Double) -> Unit,
    onAsk: (AskPreset?, String?) -> Unit,
    onManageUsage: () -> Unit,
    strings: Strings,
    seekableDurationSec: Double = Double.POSITIVE_INFINITY,
) {
    val running = state is AskState.Running
    // What was asked in the user's words stays in the field, also when the panel is opened again over it.
    var field by remember { mutableStateOf(TextFieldValue(state.askedQuestion().orEmpty())) }
    val question = field.text
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        if (presets.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                presets.forEach { preset ->
                    BlueprintChip(
                        askPresetLabel(preset, strings),
                        // The answer on show is this preset's.
                        selected = state.askedPreset() == preset,
                        onClick = { onAsk(preset, null) },
                        enabled = !running,
                        role = Role.Button,
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            val asks = question.isNotBlank() && !running
            BlueprintTextField(
                field,
                { field = it },
                strings[Str.ASK_QUESTION],
                // Enter asks, as Ask does; Shift+Enter is a new line where the cursor is (2026-10-10).
                modifier = Modifier.onPreviewKeyEvent { event ->
                    when {
                        event.key != Key.Enter && event.key != Key.NumPadEnter -> false
                        event.isShiftPressed -> {
                            if (event.type == KeyEventType.KeyDown) field = insertLineBreak(field)
                            true
                        }
                        else -> {
                            if (event.type == KeyEventType.KeyDown && asks) onAsk(null, question)
                            true
                        }
                    }
                },
                singleLine = false,
                maxLines = QUESTION_LINES,
                enabled = !running,
                monospace = false,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                BlueprintButton(strings[Str.ASK], { onAsk(null, question) }, tone = ButtonTone.PRIMARY, enabled = asks)
            }
        }
        when (state) {
            AskState.None -> Unit
            is AskState.Running -> LoadingText(strings[Str.ASK_RUNNING], MaterialTheme.typography.bodyMedium, blueprint.textMuted)
            is AskState.Ready -> {
                CitedText(state.answer.text, canPlay, onPlay, strings, seekableDurationSec)
                SummaryFooter(state.answer.text, strings[Str.SUMMARY_MODEL, modelLabel(state.answer.model, state.answer.modelName)], state.answer.recordingId, strings)
            }
            // Retry asks the same preset or the same words again.
            is AskState.Failed -> SummaryFailureNotice(
                summaryFailure(state.reason, Str.ASK_FAILED),
                onManageUsage,
                { onAsk(state.preset, state.question) },
                strings,
            )
        }
    }
}

/** [field] with its selection replaced by a line break and the cursor after it: Shift+Enter in the question. */
internal fun insertLineBreak(field: TextFieldValue): TextFieldValue {
    val start = minOf(field.selection.start, field.selection.end)
    val end = maxOf(field.selection.start, field.selection.end)
    return TextFieldValue(field.text.replaceRange(start, end, "\n"), TextRange(start + 1))
}

/** The preset the state is about, or null for the user's own question and for nothing asked. */
internal fun AskState.askedPreset(): AskPreset? = when (this) {
    AskState.None -> null
    is AskState.Running -> preset
    is AskState.Ready -> answer.preset
    is AskState.Failed -> preset
}

internal fun AskState.askedQuestion(): String? = when (this) {
    AskState.None -> null
    is AskState.Running -> question
    is AskState.Ready -> answer.question
    is AskState.Failed -> question
}

/** Up to four lines of question before the field scrolls. */
private const val QUESTION_LINES = 4

/** The tallest the panel grows: the chips, the question and an answer of a few paragraphs; a longer one scrolls. */
private val ASK_HEIGHT = 560.dp

/** Where the window opens before it takes the panel's own height. */
private val ASK_OPENING_HEIGHT = 320.dp
