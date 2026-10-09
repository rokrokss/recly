package app.recly.windows.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import app.recly.windows.i18n.text
import app.recly.windows.ui.component.BlueprintButton
import app.recly.windows.ui.component.BlueprintChip
import app.recly.windows.ui.component.ButtonTone
import app.recly.windows.ui.component.HairLine
import app.recly.windows.ui.component.LoadingText
import app.recly.windows.ui.component.SELECTION_MARK
import app.recly.windows.ui.theme.Radius
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import app.recly.windows.ui.theme.mono
import kotlinx.coroutines.delay
import recly.core.chatgpt.ChatGptConnection
import recly.core.chatgpt.SummaryState

/** docs/08 "Summaries": Transcript | Summary, under the detail's header. One is always chosen. */
@Composable
internal fun SummaryChips(showingSummary: Boolean, onShowSummary: (Boolean) -> Unit, strings: Strings) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.m).padding(bottom = Space.s),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        BlueprintChip(strings[Str.EXPORT_TRANSCRIPT], selected = !showingSummary, onClick = { onShowSummary(false) })
        BlueprintChip(strings[Str.SUMMARY_TAB], selected = showingSummary, onClick = { onShowSummary(true) })
    }
    HairLine()
}

/**
 * docs/08 "Summaries": the recording's summary — plain text from ChatGPT, shown as it is — or where a new one
 * has got to. The previous summary stays readable under a run and under a failure, until a new one replaces it.
 */
@Composable
internal fun SummaryPane(model: ShellModel, detail: RecordingDetail, strings: Strings, modifier: Modifier = Modifier) {
    val palette = blueprint
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        when (val summary = detail.summary) {
            // Chosen a moment ago, and the core has not said it is running yet.
            SummaryState.None -> LoadingText(strings[Str.SUMMARY_RUNNING], MaterialTheme.typography.bodyMedium, palette.textMuted)
            is SummaryState.Running -> {
                LoadingText(strings[Str.SUMMARY_RUNNING], MaterialTheme.typography.bodyMedium, palette.textMuted)
                summary.previous?.let { SummaryText(it.text) }
            }
            is SummaryState.Ready -> {
                SummaryText(summary.summary.text)
                SummaryFooter(
                    text = summary.summary.text,
                    model = summaryModelLabel(summary.summary.model, model.chatGpt?.connection ?: ChatGptConnection.SignedOut),
                    edited = summary.summary.editedAt != null,
                    recordingId = detail.recordingId,
                    strings = strings,
                )
            }
            is SummaryState.Failed -> {
                SummaryFailureNotice(summaryFailure(summary.reason), model, strings)
                summary.previous?.let { SummaryText(it.text) }
            }
        }
    }
}

/** Plain text with its own line breaks, selectable like the transcript. */
@Composable
private fun SummaryText(text: String) {
    SelectionContainer {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = blueprint.text)
    }
}

/**
 * Which model wrote it — and that the user has changed it since — and Copy all, which says Copied, with the mark,
 * the way the transcript's does.
 */
@Composable
private fun SummaryFooter(text: String, model: String, edited: Boolean, recordingId: String, strings: Strings) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(recordingId) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(COPIED_MS); copied = false } }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val source = strings[Str.SUMMARY_MODEL, model]
        Text(if (edited) "$source$SEPARATOR${strings[Str.SUMMARY_EDITED]}" else source,
            style = MaterialTheme.typography.bodySmall, color = blueprint.textMuted, modifier = Modifier.weight(1f))
        BlueprintButton(
            if (copied) "$SELECTION_MARK ${strings[Str.TRANSCRIPT_COPIED]}" else strings[Str.TRANSCRIPT_COPY],
            {
                clipboard.setText(AnnotatedString(text))
                copied = true
            },
            tone = ButtonTone.QUIET,
        )
    }
}

/**
 * A centred notice and at most one centred button under it (docs/09 screen principle 8): Manage usage for a
 * spent plan, nothing where the sentence already says where to go, Retry for the rest.
 */
@Composable
private fun SummaryFailureNotice(failure: SummaryFailure, model: ShellModel, strings: Strings) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.s),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(failure.headline.text(strings), color = blueprint.danger, textAlign = TextAlign.Center)
        failure.detail?.let { Text(it, style = mono.small, color = blueprint.textMuted, textAlign = TextAlign.Center) }
        when (failure.recovery) {
            SummaryRecovery.MANAGE_USAGE -> BlueprintButton(
                strings[Str.CHATGPT_MANAGE_USAGE],
                { model.chatGpt?.openUsage() },
                tone = ButtonTone.PRIMARY,
            )
            SummaryRecovery.RETRY -> BlueprintButton(strings[Str.RECENT_RETRY], model::askToSummarize, tone = ButtonTone.QUIET)
            SummaryRecovery.NONE -> Unit
        }
    }
}

/**
 * docs/08 "Summaries": [recordingId]'s summary as the editor holds it until Save. [original] is the text it opened
 * on, which is what leaving it compares against.
 */
internal class SummaryDraft(val recordingId: String, val original: String) {
    var text: String by mutableStateOf(original)

    val changed: Boolean get() = summaryEdited(original, text)
}

/**
 * docs/08 "Summaries": the whole summary in one plain field, in the place the summary was, and under it what
 * saving does — the summary in storage changes and the other devices show it, or it stays on this PC.
 */
@Composable
internal fun SummaryEditor(draft: SummaryDraft, note: Str, enabled: Boolean, strings: Strings, modifier: Modifier = Modifier) {
    val palette = blueprint
    Column(modifier) {
        BasicTextField(
            value = draft.text,
            onValueChange = { draft.text = it },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(Space.m)
                .semantics { contentDescription = strings[Str.SUMMARY_TAB] }
                .border(palette.line, palette.inputBorder, RoundedCornerShape(Radius.node))
                .background(palette.surface, RoundedCornerShape(Radius.node))
                .padding(Space.s),
            enabled = enabled,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = palette.text),
            cursorBrush = SolidColor(palette.accent),
        )
        HairLine()
        Text(
            strings[note],
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.s),
            style = MaterialTheme.typography.bodySmall,
            color = palette.textMuted,
        )
    }
}

/** The footer's own join, the one [Str.SUMMARY_MODEL] puts between ChatGPT and the model. */
private const val SEPARATOR = " · "

private const val COPIED_MS = 3_000L
