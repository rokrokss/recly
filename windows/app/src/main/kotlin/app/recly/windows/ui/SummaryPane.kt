package app.recly.windows.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import app.recly.windows.i18n.text
import app.recly.windows.ui.component.BlueprintButton
import app.recly.windows.ui.component.BlueprintChip
import app.recly.windows.ui.component.ButtonTone
import app.recly.windows.ui.component.HairLine
import app.recly.windows.ui.component.LoadingText
import app.recly.windows.ui.component.SELECTION_MARK
import app.recly.windows.ui.theme.MinTouch
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
 * Its times play the recording from there, as the transcript's do ([canSeek], [onSeek]).
 */
@Composable
internal fun SummaryPane(
    model: ShellModel,
    detail: RecordingDetail,
    canSeek: Boolean,
    onSeek: (Double) -> Unit,
    strings: Strings,
    modifier: Modifier = Modifier,
) {
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
                summary.previous?.let { CitedText(it.text, canSeek, onSeek, strings) }
            }
            is SummaryState.Ready -> {
                CitedText(summary.summary.text, canSeek, onSeek, strings)
                SummaryFooter(
                    text = summary.summary.text,
                    source = summaryFooter(
                        strings,
                        summaryModelLabel(summary.summary.model, model.chatGpt?.connection ?: ChatGptConnection.SignedOut),
                        summary.summary.summaryFormat,
                        edited = summary.summary.editedAt != null,
                    ),
                    recordingId = detail.recordingId,
                    strings = strings,
                )
            }
            is SummaryState.Failed -> {
                SummaryFailureNotice(summaryFailure(summary.reason), { model.chatGpt?.openUsage() }, model::retrySummary, strings)
                summary.previous?.let { CitedText(it.text, canSeek, onSeek, strings) }
            }
        }
    }
}

/**
 * docs/09 "Summary view": a summary or an answer — plain text with its own line breaks, selectable like the
 * transcript — whose `[00:12:34]` times each play the recording from there, the way a transcript time does.
 *
 * Selection and a tap on part of a text want the same press, so each time is not a span of the text but a small
 * control of its own set into it ([InlineTextContent]): the press on a time is the time's, a drag anywhere else
 * selects, and a copy carries the time as written. Its 44 target is invisible padding laid over the lines next to
 * it, so the lines keep their height.
 */
@Composable
internal fun CitedText(text: String, canSeek: Boolean, onSeek: (Double) -> Unit, strings: Strings) {
    val palette = blueprint
    val style = MaterialTheme.typography.bodyMedium
    val citation = style.copy(fontFamily = mono.body.fontFamily)
    val runs = remember(text) { citationRuns(text) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val annotated = remember(runs) {
        buildAnnotatedString {
            runs.forEachIndexed { index, run -> if (run.atSec == null) append(run.text) else appendInlineContent("$CITATION$index", run.text) }
        }
    }
    // Each time's room in the line, as wide and as tall as its own words: measured once per text, not per frame of
    // the player under it.
    val placeholders = remember(runs, citation, density) {
        runs.map { run ->
            run.atSec?.let {
                val size = measurer.measure(run.text, citation, softWrap = false).size
                with(density) { Placeholder(size.width.toSp(), size.height.toSp(), PlaceholderVerticalAlign.TextCenter) }
            }
        }
    }
    val inline = runs.withIndex().filter { it.value.atSec != null }.associate { (index, run) ->
        "$CITATION$index" to InlineTextContent(placeholders[index]!!) {
            Citation(run.text, strings[Str.SUMMARY_PLAY_FROM, run.time!!], citation, canSeek) { onSeek(run.atSec!!) }
        }
    }
    SelectionContainer {
        Text(annotated, style = style, color = palette.text, inlineContent = inline)
    }
}

/** One time in a summary: monospace in the accent, no underline — a dotted one is a web page (docs/09). */
@Composable
private fun Citation(text: String, label: String, style: TextStyle, enabled: Boolean, onClick: () -> Unit) {
    val palette = blueprint
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            // The text's own box in the line; the target under it is MinTouch tall and centred on it.
            .layout { measurable, constraints ->
                val target = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                layout(constraints.maxWidth, constraints.maxHeight) { target.place(0, (constraints.maxHeight - target.height) / 2) }
            }
            .defaultMinSize(minHeight = MinTouch)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            modifier = Modifier.alpha(if (pressed) PRESSED_ALPHA else 1f).clearAndSetSemantics { },
            style = style,
            color = if (enabled) palette.accent else palette.textMuted,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * Which model wrote it — in which format, and that the user has changed it since ([summaryFooter]) — and Copy all,
 * which says Copied, with the mark, the way the transcript's does.
 */
@Composable
internal fun SummaryFooter(text: String, source: String, recordingId: String, strings: Strings) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(recordingId) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(COPIED_MS); copied = false } }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(source, style = MaterialTheme.typography.bodySmall, color = blueprint.textMuted, modifier = Modifier.weight(1f))
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
 * spent plan, nothing where the sentence already says where to go, Retry for the rest. The summary's, and Ask's.
 */
@Composable
internal fun SummaryFailureNotice(failure: SummaryFailure, onManageUsage: () -> Unit, onRetry: () -> Unit, strings: Strings) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.s),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(failure.headline.text(strings), color = blueprint.danger, textAlign = TextAlign.Center)
        failure.detail?.let { Text(it, style = mono.small, color = blueprint.textMuted, textAlign = TextAlign.Center) }
        when (failure.recovery) {
            SummaryRecovery.MANAGE_USAGE -> BlueprintButton(strings[Str.CHATGPT_MANAGE_USAGE], onManageUsage, tone = ButtonTone.PRIMARY)
            // Retry repeats the run the user already asked for; only More → Summarize again asks first (docs/09).
            SummaryRecovery.RETRY -> BlueprintButton(strings[Str.RECENT_RETRY], onRetry, tone = ButtonTone.QUIET)
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

private const val COPIED_MS = 3_000L

/** The id of a time set into the text ([CitedText]), with its run's index after it. */
private const val CITATION = "citation-"

/** Pressed, a time fades as a text link does. */
private const val PRESSED_ALPHA = 0.6f
