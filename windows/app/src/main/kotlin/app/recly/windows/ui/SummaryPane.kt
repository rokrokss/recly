package app.recly.windows.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextDirection
import app.recly.windows.ui.component.fieldBox
import kotlin.math.hypot
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
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
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import app.recly.windows.ui.theme.mono
import kotlin.math.roundToInt
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
 * Its times play the recording from there ([canPlay], [onPlay]): seek, and start playing (2026-10-10).
 */
@Composable
internal fun SummaryPane(
    model: ShellModel,
    detail: RecordingDetail,
    canPlay: Boolean,
    onPlay: (Double) -> Unit,
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
                summary.previous?.let { CitedText(it.text, canPlay, onPlay, strings, detail.audio.totalSec) }
            }
            is SummaryState.Ready -> {
                CitedText(summary.summary.text, canPlay, onPlay, strings, detail.audio.totalSec)
                SummaryFooter(
                    text = summary.summary.text,
                    source = summaryFooter(
                        strings,
                        summaryModelLabel(summary.summary.model, summary.summary.modelName, model.chatGpt?.connection ?: ChatGptConnection.SignedOut),
                        summary.summary.summaryFormat,
                        edited = summary.summary.editedAt != null,
                    ),
                    recordingId = detail.recordingId,
                    strings = strings,
                )
            }
            is SummaryState.Failed -> {
                SummaryFailureNotice(summaryFailure(summary.reason), { model.chatGpt?.openUsage() }, model::retrySummary, strings)
                summary.previous?.let { CitedText(it.text, canPlay, onPlay, strings, detail.audio.totalSec) }
            }
        }
    }
}

/**
 * docs/09 "Summary view": a summary or an answer — plain text with its own line breaks, selectable like the
 * transcript, in the direction its own words take — whose `[00:12:34]` times each play the recording from there.
 *
 * The text is one selectable [Text], each time a span of it in monospace and the accent, with no underline — a
 * dotted one is a web page. Selection and a click on part of a text want the same press, so a time's target is not
 * the span but an invisible box laid over it, at least [MinTouch] each way and centred on its words — one for each
 * line a time is on: the press on a time is the time's, a drag anywhere else selects, and a copy carries the time as
 * written. The boxes take no room, so the lines keep their height; one reaches a little over the lines next to it,
 * and where two reach over each other the press plays the time whose words are nearest ([citationHit], Apple's
 * rule, 2026-10-10). A time the player cannot reach now — nothing to play, or past the recording's end
 * ([seekableDurationSec]), as a transcript time button past it is off — is muted and only text. Each box is also a
 * screen reader's `Play from …` button.
 */
@Composable
internal fun CitedText(
    text: String,
    canPlay: Boolean,
    onPlay: (Double) -> Unit,
    strings: Strings,
    seekableDurationSec: Double = Double.POSITIVE_INFINITY,
) {
    val palette = blueprint
    val runs = remember(text) { citationRuns(text) }
    // Where each run starts in the text, which is where the layout finds its words.
    val starts = remember(runs) { runs.runningFold(0) { at, run -> at + run.text.length } }
    var pressed by remember(text) { mutableStateOf<Int?>(null) }
    val playable = runs.map { run -> canPlay && run.atSec != null && run.atSec < seekableDurationSec }
    val annotated = buildAnnotatedString {
        runs.forEachIndexed { index, run ->
            if (run.atSec == null) {
                append(run.text)
            } else {
                val color = if (playable[index]) palette.accent else palette.textMuted
                withStyle(SpanStyle(fontFamily = mono.body.fontFamily, color = if (pressed == index) color.copy(alpha = PRESSED_ALPHA) else color)) {
                    append(run.text)
                }
            }
        }
    }
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    val reach = with(LocalDensity.current) { MinTouch.toPx() }
    Box {
        SelectionContainer {
            Text(
                annotated,
                // Across the width, so the words' own direction also decides which edge they start from.
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                color = palette.text,
                onTextLayout = { layout = it },
            )
        }
        val lines = layout ?: return@Box
        val targets = remember(lines, playable) {
            runs.indices.filter { playable[it] }.flatMap { index -> citationTargets(lines, index, starts[index], starts[index + 1]) }
        }
        targets.forEach { target ->
            val area = target.area(reach)
            val label = strings[Str.SUMMARY_PLAY_FROM, runs[target.run].time!!]
            val play = { hit: CitationTarget? -> hit?.let { runs[it.run].atSec }?.let(onPlay) }
            Box(
                Modifier
                    // Over the words and taking no room: the lines keep their height.
                    .layout { measurable, _ ->
                        val box = measurable.measure(Constraints.fixed(area.width.roundToInt(), area.height.roundToInt()))
                        layout(0, 0) { box.place(area.left.roundToInt(), area.top.roundToInt()) }
                    }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .pointerInput(targets) {
                        detectTapGestures(
                            onPress = { at ->
                                pressed = citationHit(at + area.topLeft, targets, reach)?.run
                                tryAwaitRelease()
                                pressed = null
                            },
                            onTap = { at -> play(citationHit(at + area.topLeft, targets, reach)) },
                        )
                    }
                    .semantics {
                        contentDescription = label
                        role = Role.Button
                        onClick { play(target); true }
                    },
            )
        }
    }
}

/**
 * docs/09 "Summary view": one time's words on one line of the text — [run] is its place among the text's runs — and
 * around them the box a press plays it from.
 */
internal data class CitationTarget(val run: Int, val glyphs: Rect) {
    /** At least [reach] each way, centred on the words: it reaches over the lines next to it, where [citationHit] decides. */
    fun area(reach: Float): Rect {
        val width = maxOf(glyphs.width, reach)
        val height = maxOf(glyphs.height, reach)
        return Rect(glyphs.center.x - width / 2, glyphs.center.y - height / 2, glyphs.center.x + width / 2, glyphs.center.y + height / 2)
    }

    fun distance(point: Offset): Float =
        hypot(maxOf(glyphs.left - point.x, 0f, point.x - glyphs.right), maxOf(glyphs.top - point.y, 0f, point.y - glyphs.bottom))
}

/**
 * What a press at [point] plays: of the targets whose area holds it, the one whose words are nearest — so a press on
 * a time's own words plays that time even where the next line's target reaches over it (Apple's `CitationTarget.hit`).
 */
internal fun citationHit(point: Offset, targets: List<CitationTarget>, reach: Float): CitationTarget? =
    targets.filter { it.area(reach).contains(point) }.minByOrNull { it.distance(point) }

/** The box around the characters [start] to [end] on each line they are on — one, unless a time wraps. */
private fun citationTargets(layout: TextLayoutResult, run: Int, start: Int, end: Int): List<CitationTarget> =
    (start until end).groupBy(layout::getLineForOffset).values.map { offsets ->
        CitationTarget(
            run,
            offsets.map(layout::getBoundingBox).reduce { a, b ->
                Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
            },
        )
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
 * spent plan, nothing where the sentence already says where to go or where trying again cannot help, Retry for the
 * rest. The summary's, and Ask's.
 */
@Composable
internal fun SummaryFailureNotice(failure: SummaryFailure, onManageUsage: () -> Unit, onRetry: () -> Unit, strings: Strings) {
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
                // The accent while it has the focus, as every other field (2026-10-10).
                .fieldBox()
                .padding(Space.s),
            enabled = enabled,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = palette.text, textDirection = TextDirection.Content),
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

/** Pressed, a time fades as a text link does. */
private const val PRESSED_ALPHA = 0.6f
