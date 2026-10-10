package app.recly.android.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import app.recly.android.R
import app.recly.android.core.CoreMessages
import app.recly.android.core.coreMessage
import app.recly.android.ui.component.BlueprintButton
import app.recly.android.ui.component.ButtonTone
import app.recly.android.ui.component.HairLine
import app.recly.android.ui.component.LoadingText
import app.recly.android.ui.theme.MinTouch
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import app.recly.android.ui.theme.mono
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import recly.core.chatgpt.ChatGptConnection
import recly.core.chatgpt.ChatGptModel
import recly.core.chatgpt.Summary
import recly.core.chatgpt.SummaryCitation
import recly.core.chatgpt.SummaryCitations
import recly.core.chatgpt.SummaryFormat
import recly.core.chatgpt.SummaryState
import recly.core.message.CoreMessage
import recly.core.message.CoreMessageRef
import recly.core.recording.SearchHit
import recly.core.storage.StorageKind

/** The detail's two views once a summary is in play (docs/09 "Summary view"). */
internal enum class DetailView { TRANSCRIPT, SUMMARY }

/**
 * Why the More menu's Summarize cannot run now, or null when it can — in the order the reasons are checked
 * (docs/09 "Detail header and More menu"). [busyReason] is what the menu already says while a transcription
 * of the recording is queued or running. A take still being written has no More menu at all.
 */
@StringRes
internal fun summarizeReason(
    hasTranscript: Boolean,
    transcribing: Boolean,
    @StringRes busyReason: Int,
    connection: ChatGptConnection,
    summary: SummaryState,
): Int? = askReason(hasTranscript, transcribing, busyReason, connection)
    ?: if (summary is SummaryState.Running) R.string.summary_running else null

/** Why More → Ask about this recording cannot open now: Summarize's reasons, but a summary being written is none (docs/08 "Ask"). */
@StringRes
internal fun askReason(hasTranscript: Boolean, transcribing: Boolean, @StringRes busyReason: Int, connection: ChatGptConnection): Int? = when {
    !hasTranscript -> R.string.detail_no_transcript
    transcribing -> busyReason
    connection !is ChatGptConnection.SignedIn -> CoreMessages.resourceOf(CoreMessage.CHATGPT_SIGN_IN_REQUIRED)
    else -> null
}

/** docs/08 "Summaries": each format under its name in Settings, in Summarize as and in the summary's footer. */
@StringRes
internal fun SummaryFormat.formatLabel(): Int = when (this) {
    SummaryFormat.AUTO -> R.string.summary_format_general
    SummaryFormat.ONE_ON_ONE -> R.string.summary_format_one_on_one
    SummaryFormat.LECTURE -> R.string.summary_format_lecture
    SummaryFormat.INTERVIEW -> R.string.summary_format_interview
    SummaryFormat.CUSTOM -> R.string.summary_format_custom
}

/** The format the footer names: none for General, which every summary is unless asked otherwise. */
internal fun footerFormat(summary: Summary): SummaryFormat? = summary.summaryFormat.takeIf { it != SummaryFormat.AUTO }

/** "ChatGPT · <model>", then the format, then "Edited" — each where there is one, on the one separator. */
internal fun footerLine(written: String, format: String?, edited: String?): String = listOfNotNull(written, format, edited).joinToString(" · ")

/** The format Summarize as marks `✓`: the one the recording's summary was written in, if it has one. */
internal fun currentFormat(summary: SummaryState): SummaryFormat? = savedSummary(summary)?.summaryFormat

/** docs/09 "Search": a hit found in the summary alone opens on the summary — there is nothing in the transcript to find. */
internal fun opensOnSummary(hit: SearchHit): Boolean = hit.summary != null && hit.snippets.isEmpty() && !hit.matchesInTitle

/** One stretch of a summary or an answer: plain text, or a [citation] — `[HH:MM:SS]` or `[MM:SS]` — that plays from its time. */
internal data class TextRun(val start: Int, val end: Int, val citation: SummaryCitation? = null)

/** [text] cut at every citation the core reads in it ([SummaryCitations]), so the runs cover it end to end in order. */
internal fun textRuns(text: String): List<TextRun> = buildList {
    var at = 0
    SummaryCitations.parse(text).forEach { citation ->
        if (citation.offset > at) add(TextRun(at, citation.offset))
        add(TextRun(citation.offset, citation.offset + citation.length, citation))
        at = citation.offset + citation.length
    }
    if (at < text.length) add(TextRun(at, text.length))
}

/** The citation's time as the text writes it, without its brackets — what `Play from {time}` says. */
internal fun citationTime(text: String, citation: SummaryCitation): String =
    text.substring(citation.offset + 1, citation.offset + citation.length - 1)

/** "Summarize again" once there is a summary to replace. */
internal fun hasSummary(summary: SummaryState): Boolean = when (summary) {
    SummaryState.None -> false
    is SummaryState.Ready -> true
    is SummaryState.Running -> summary.previous != null
    is SummaryState.Failed -> summary.previous != null
}

/** The summary the recording keeps: what Edit summary opens, and what Summarize again would replace. */
internal fun savedSummary(summary: SummaryState): Summary? = when (summary) {
    SummaryState.None -> null
    is SummaryState.Ready -> summary.summary
    is SummaryState.Running -> summary.previous
    is SummaryState.Failed -> summary.previous
}

/** Edit summary is offered once there is a summary; it waits while a new one is being written. */
@StringRes
internal fun editSummaryReason(summary: SummaryState): Int? = if (summary is SummaryState.Running) R.string.summary_running else null

/** "Summarize again" asks first when the summary it would replace is one the user edited. */
internal fun asksBeforeReplacing(summary: SummaryState): Boolean = savedSummary(summary)?.editedAt != null

/**
 * The summary editor's note: Drive and iCloud carry a saved summary to the other devices; a local folder, or a
 * recording not uploaded yet, keeps it here — the transcript editor's own rule (docs/09 "Editing and speakers").
 */
@StringRes
internal fun summaryEditNote(storage: StorageKind?): Int = when (storage) {
    StorageKind.DRIVE, StorageKind.ICLOUD -> R.string.summary_edit_note_shared
    StorageKind.FOLDER, null -> R.string.summary_edit_note_local
}

/** docs/09 "Summary view": the summary editor's working copy. */
internal data class SummaryDraft(val original: String, val text: String) {
    /** Whether Save writes anything: the core keeps the summary as it is for blank or unchanged text. */
    val changed: Boolean get() = text.trim().let { it.isNotEmpty() && it != original.trim() }

    companion object {
        fun of(summary: Summary) = SummaryDraft(summary.text, summary.text)
    }
}

/** Transcript | Summary, once there is a summary or the user has just asked for one — never while editing. */
internal fun showsSummaryChips(summary: SummaryState, asked: Boolean, editing: Boolean): Boolean =
    !editing && (asked || summary !is SummaryState.None)

/** The reasons whose own sentence is the whole notice: each says what to do. */
private val SPOKEN = setOf(
    CoreMessage.CHATGPT_SIGN_IN_REQUIRED,
    CoreMessage.CHATGPT_USAGE_LIMIT,
    CoreMessage.CHATGPT_PLAN_REQUIRED,
    CoreMessage.PROVIDER_REGION_RESTRICTED,
)

/** [reason]'s key when its sentence is shown on its own, or null when the failure line is generic. */
internal fun spokenReason(reason: String): CoreMessage? = CoreMessageRef.parse(reason)?.message?.takeIf { it in SPOKEN }

/** What goes under a generic failure line: the code's diagnostic, never translated — or the text as it came. */
internal fun reasonDetail(reason: String): String? = CoreMessageRef.parse(reason).let { if (it == null) reason else it.detail ?: it.arg }

/** The one button under a failed summary. */
internal enum class SummaryRecovery { MANAGE_USAGE, RETRY, NONE }

internal fun summaryRecovery(reason: String): SummaryRecovery = when (CoreMessageRef.parse(reason)?.message) {
    CoreMessage.CHATGPT_USAGE_LIMIT -> SummaryRecovery.MANAGE_USAGE
    // The sentence says where to sign in; there is nothing to press here.
    CoreMessage.CHATGPT_SIGN_IN_REQUIRED -> SummaryRecovery.NONE
    else -> SummaryRecovery.RETRY
}

/**
 * docs/09 "Summary view": the summary of the open recording, or where it is. The text is ChatGPT's plain text
 * with its own line breaks; [models] names the model it was written with. A citation in it plays from its time
 * through [onSeek] — the transcript's time buttons' own seek — where [canSeek] allows it.
 */
@Composable
internal fun SummaryPane(
    state: SummaryState,
    models: List<ChatGptModel>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    canSeek: (Double) -> Boolean = { false },
    onSeek: (Double) -> Unit = {},
) {
    val palette = blueprint
    when (state) {
        // None only for the moment between the tap and the run starting.
        SummaryState.None, is SummaryState.Running -> Column(modifier) {
            LoadingText(stringResource(R.string.summary_running), MaterialTheme.typography.bodySmall, palette.textMuted,
                Modifier.padding(horizontal = Space.m, vertical = Space.s).testTag("summary-running"))
            HairLine()
            (state as? SummaryState.Running)?.previous?.let { SummaryText(it, Modifier.weight(1f), canSeek, onSeek) }
        }

        is SummaryState.Ready -> Column(modifier) {
            SummaryText(state.summary, Modifier.weight(1f), canSeek, onSeek)
            HairLine()
            SummaryFooter(state.summary, models)
        }

        is SummaryState.Failed -> {
            val previous = state.previous
            if (previous == null) {
                ChatGptFailure(state.reason, R.string.summary_failed, onRetry, modifier.fillMaxSize(), retryTag = "summary-retry")
            } else {
                Column(modifier) {
                    ChatGptFailure(state.reason, R.string.summary_failed, onRetry, Modifier.fillMaxWidth(), retryTag = "summary-retry")
                    HairLine()
                    SummaryText(previous, Modifier.weight(1f), canSeek, onSeek)
                }
            }
        }
    }
}

/**
 * A summary or an answer that could not be written, as a centred notice: the reason's own sentence where it says
 * what to do, otherwise [generic] with the detail as it came — and the one button that helps (docs/09 "Summary view").
 */
@Composable
internal fun ChatGptFailure(reason: String, @StringRes generic: Int, onRetry: () -> Unit, modifier: Modifier, retryTag: String) {
    val context = LocalContext.current
    val spoken = spokenReason(reason)
    val text = if (spoken != null) coreMessage(spoken).text() else stringResource(generic)
    val detail = if (spoken != null) null else reasonDetail(reason)
    val button: (@Composable () -> Unit)? = when (summaryRecovery(reason)) {
        SummaryRecovery.MANAGE_USAGE -> {
            { BlueprintButton(stringResource(R.string.chatgpt_manage_usage), { context.openUrl(CHATGPT_USAGE_URL) }, tone = ButtonTone.PRIMARY) }
        }
        SummaryRecovery.RETRY -> {
            { BlueprintButton(stringResource(R.string.action_retry), onRetry, tone = ButtonTone.QUIET, modifier = Modifier.testTag(retryTag)) }
        }
        SummaryRecovery.NONE -> null
    }
    Notice(text, detail = detail, button = button, modifier = modifier)
}

/** docs/09 "Summary view": the whole summary as one plain text field, as the transcript editor's fields are. */
@Composable
internal fun SummaryEditor(text: String, onText: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(text, onText, modifier.padding(Space.m).testTag("summary-editor"))
}

/** The text as ChatGPT wrote it, selectable, in the transcript's body type. */
@Composable
private fun SummaryText(summary: Summary, modifier: Modifier, canSeek: (Double) -> Boolean, onSeek: (Double) -> Unit) {
    SelectionContainer(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        CitedText(summary.text, canSeek, onSeek, Modifier.padding(Space.m).testTag("summary-text"))
    }
}

/**
 * docs/09 "Summary view": a summary's or an answer's text with every citation in it a way to play from its time —
 * the citation in monospace and the accent, with no underline (a dotted one is a web page), and a tap target of at
 * least [MinTouch] each way laid over it as invisible padding. The text stays one selectable text: the targets are
 * siblings over it, so a long press anywhere else selects as before, and a citation the player cannot reach now
 * ([canSeek]) is drawn muted and takes no tap. Each target is its own `Play from {time}` button for a screen reader.
 */
@Composable
internal fun CitedText(text: String, canSeek: (Double) -> Boolean, onSeek: (Double) -> Unit, modifier: Modifier = Modifier) {
    val palette = blueprint
    val runs = remember(text) { textRuns(text) }
    val citation = mono.body.fontFamily
    val styled = buildAnnotatedString {
        runs.forEach { run ->
            val cited = run.citation
            if (cited == null) {
                append(text, run.start, run.end)
            } else {
                withStyle(SpanStyle(fontFamily = citation, color = if (canSeek(cited.atSec)) palette.accent else palette.textMuted)) {
                    append(text, run.start, run.end)
                }
            }
        }
    }
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    val reach = with(density) { MinTouch.toPx() }
    Box(modifier) {
        Text(styled, style = MaterialTheme.typography.bodyMedium, color = palette.text, onTextLayout = { layout = it })
        val lines = layout
        if (lines != null) {
            runs.forEach { run ->
                val cited = run.citation ?: return@forEach
                if (!canSeek(cited.atSec)) return@forEach
                val bounds = lines.getPathForRange(run.start, run.end).getBounds()
                val width = maxOf(bounds.width, reach)
                val height = maxOf(bounds.height, reach)
                val label = stringResource(R.string.summary_play_from, citationTime(text, cited))
                Box(
                    Modifier
                        .offset { IntOffset((bounds.center.x - width / 2).roundToInt(), (bounds.center.y - height / 2).roundToInt()) }
                        .size(with(density) { width.toDp() }, with(density) { height.toDp() })
                        .clickable(role = Role.Button) { onSeek(cited.atSec) }
                        .semantics { contentDescription = label },
                )
            }
        }
    }
}

/** "ChatGPT · <model> · <format> · Edited" — each part where there is one — and Copy all for the summary. */
@Composable
private fun SummaryFooter(summary: Summary, models: List<ChatGptModel>) {
    val label = models.firstOrNull { it.id == summary.model }?.label ?: summary.model
    CopyFooter(
        footerLine(
            stringResource(R.string.summary_model, label),
            footerFormat(summary)?.let { stringResource(it.formatLabel()) },
            if (summary.editedAt == null) null else stringResource(R.string.summary_edited),
        ),
        summary.text,
        Modifier.testTag("summary-copy"),
    )
}

/** Who wrote the text above it, and Copy all, which says `✓ Copied` for a moment as the transcript's does. */
@Composable
internal fun CopyFooter(line: String, text: String, copyModifier: Modifier = Modifier) {
    val palette = blueprint
    val clipboard = LocalClipboardManager.current
    var copied by remember(text) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(COPIED_MS); copied = false } }
    Row(
        Modifier.fillMaxWidth().background(palette.surface).padding(horizontal = Space.m, vertical = Space.s),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(line, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
        BlueprintButton(
            stringResource(if (copied) R.string.transcript_copied else R.string.transcript_copy),
            { clipboard.setText(AnnotatedString(text)); copied = true },
            tone = ButtonTone.QUIET,
            leading = if (copied) stringResource(R.string.action_done) else null,
            modifier = copyModifier,
        )
    }
}

private const val COPIED_MS = 3_000L
