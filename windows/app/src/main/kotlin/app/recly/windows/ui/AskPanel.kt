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
 */
@Composable
internal fun AskDialog(
    model: ShellModel,
    detail: RecordingDetail,
    canSeek: Boolean,
    onSeek: (Double) -> Unit,
    strings: Strings,
    theme: @Composable (@Composable () -> Unit) -> Unit,
) {
    val presets by produceState(emptyList<AskPreset>(), detail.recordingId) { value = model.askPresets(detail.recordingId) }
    // Gone with the window, or with another recording picked under it: closed as Close closes it.
    DisposableEffect(detail.recordingId) { onDispose { model.closeAsk() } }
    BlueprintDialog(
        title = strings[Str.ASK_TITLE],
        onDismissRequest = model::closeAsk,
        actions = { BlueprintButton(strings[Str.CLOSE], model::closeAsk, tone = ButtonTone.QUIET) },
        height = ASK_HEIGHT,
        theme = theme,
    ) {
        AskPanel(
            state = detail.ask,
            presets = presets,
            modelLabel = { summaryModelLabel(it, model.chatGpt?.connection ?: ChatGptConnection.SignedOut) },
            canSeek = canSeek,
            onSeek = onSeek,
            onAsk = model::ask,
            onManageUsage = { model.chatGpt?.openUsage() },
            strings = strings,
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
    modelLabel: (String) -> String,
    canSeek: Boolean,
    onSeek: (Double) -> Unit,
    onAsk: (AskPreset?, String?) -> Unit,
    onManageUsage: () -> Unit,
    strings: Strings,
) {
    val running = state is AskState.Running
    // What was asked in the user's words stays in the field, also when the panel is opened again over it.
    var question by remember { mutableStateOf(state.askedQuestion().orEmpty()) }
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
            BlueprintTextField(
                question,
                { question = it },
                strings[Str.ASK_QUESTION],
                singleLine = false,
                maxLines = QUESTION_LINES,
                enabled = !running,
                monospace = false,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                BlueprintButton(strings[Str.ASK], { onAsk(null, question) }, tone = ButtonTone.PRIMARY, enabled = question.isNotBlank() && !running)
            }
        }
        when (state) {
            AskState.None -> Unit
            is AskState.Running -> LoadingText(strings[Str.ASK_RUNNING], MaterialTheme.typography.bodyMedium, blueprint.textMuted)
            is AskState.Ready -> {
                CitedText(state.answer.text, canSeek, onSeek, strings)
                SummaryFooter(state.answer.text, strings[Str.SUMMARY_MODEL, modelLabel(state.answer.model)], state.answer.recordingId, strings)
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

/** Room for the chips, the question and an answer of a few paragraphs; a longer one scrolls with the rest. */
private val ASK_HEIGHT = 560.dp
