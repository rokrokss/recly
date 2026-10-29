package app.recly.android.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import app.recly.android.R
import app.recly.android.core.CoreMessages
import app.recly.android.core.coreMessage
import app.recly.android.ui.component.BlueprintButton
import app.recly.android.ui.component.ButtonTone
import app.recly.android.ui.component.Glyph
import app.recly.android.ui.component.GlyphButton
import app.recly.android.ui.theme.MinTouch
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import app.recly.android.ui.theme.mono
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import recly.core.chatgpt.ChatGptConnection
import recly.core.chatgpt.ChatGptModel
import recly.core.chatgpt.FillNeeds
import recly.core.chatgpt.FillResult
import recly.core.chatgpt.FillRun
import recly.core.chatgpt.SummaryCitation
import recly.core.chatgpt.SummaryCitations
import recly.core.message.CoreMessage
import recly.core.message.CoreMessageRef
import recly.core.recording.SearchHit

/** The detail's two views once the recording has notes, or Organize was asked (docs/09 "AI view"). */
internal enum class DetailView { TRANSCRIPT, NOTES }

/**
 * Why the More menu's Organize cannot run now, or null when it can — in the order the reasons are checked
 * (docs/09 "AI view"). [busyReason] is what the menu already says while a transcription of the recording is queued
 * or running; [organizing] is a pass of its notes being worked on here. A take still being written says `Still
 * recording` before any of these.
 */
@StringRes
internal fun organizeReason(
    hasTranscript: Boolean,
    transcribing: Boolean,
    @StringRes busyReason: Int,
    connection: ChatGptConnection,
    organizing: Boolean,
): Int? = askReason(hasTranscript, transcribing, busyReason, connection)
    ?: if (organizing) R.string.notes_organizing else null

/** Why More → Edit notes cannot open now: the notes are being organized here, and would change under the editor. */
@StringRes
internal fun editNotesReason(organizing: Boolean): Int? = if (organizing) R.string.notes_organizing else null

/** Why More → Ask about this recording cannot open now: Organize's reasons, but notes being organized are none (docs/08 "Ask"). */
@StringRes
internal fun askReason(hasTranscript: Boolean, transcribing: Boolean, @StringRes busyReason: Int, connection: ChatGptConnection): Int? = when {
    !hasTranscript -> R.string.detail_no_transcript
    transcribing -> busyReason
    connection !is ChatGptConnection.SignedIn -> CoreMessages.resourceOf(CoreMessage.CHATGPT_SIGN_IN_REQUIRED)
    else -> null
}

/**
 * docs/08 "Titles and speaker names": the two things More can fill in, each its own item — the title and the speakers'
 * names, never one action (2026-10-15) — with what each says while it cannot run, while it runs and when it failed.
 */
enum class FillHalf(
    @param:StringRes val item: Int,
    /** `FillNeeds` says nothing of this half is missing. */
    @param:StringRes val nothing: Int,
    /** The line under the detail's header while a fill of this half runs. */
    @param:StringRes val running: Int,
    @param:StringRes val failed: Int,
) {
    TITLE(R.string.fill_title_menu, R.string.fill_has_title, R.string.fill_title_running, R.string.fill_title_failed),
    NAMES(R.string.fill_names_menu, R.string.fill_no_unnamed, R.string.fill_names_running, R.string.fill_names_failed);

    /** Whether [run] — the recording's fill that is asking ChatGPT now, if any — asks for this half. */
    fun runs(run: FillRun?): Boolean = when (this) {
        TITLE -> run?.title == true
        NAMES -> run?.names == true
    }

    /** Whether the recording still lacks this half: what the item is offered by. */
    fun needed(needs: FillNeeds): Boolean = when (this) {
        TITLE -> needs.title
        NAMES -> needs.names
    }
}

/**
 * Why More → Fill in title or Fill in speaker names cannot run now (docs/09 "AI view"), in this order: Ask's
 * reasons, then a fill of [half] running ([running] — `observeFills()`, the publication's too), then nothing of it
 * missing — [needed] is [FillHalf.needed] for the recording as it is now, which a fill that is running may be about
 * to change. A take still being written says `Still recording` before any of these.
 */
@StringRes
internal fun fillReason(
    half: FillHalf,
    hasTranscript: Boolean,
    transcribing: Boolean,
    @StringRes busyReason: Int,
    connection: ChatGptConnection,
    running: Boolean,
    needed: Boolean,
): Int? = askReason(hasTranscript, transcribing, busyReason, connection) ?: when {
    running -> R.string.fill_running
    !needed -> half.nothing
    else -> null
}

/** What the detail says under its header once Fill in title or Fill in speaker names has answered (docs/08 "Titles and speaker names"). */
internal sealed interface FillNotice {
    /** Nothing was sent: the More menu's own reason, as a refused Transcribe again says it. */
    data class Refused(@param:StringRes val reason: Int) : FillNotice

    /** [half]'s failure — `Could not fill in the title` — with the core's sentence for [reason] when it gave one. */
    data class Failed(val half: FillHalf, val reason: String?) : FillNotice
}

/**
 * [result] of asking for [half] as a line under the header, or null for nothing to say — what arrived is seen where it
 * goes. Nothing of it missing says the item's own reason; ChatGPT answering without the half asked is that half's
 * failure alone. A null [result] is a call that threw. [busyReason] is what the More menu says about the job in the way.
 */
internal fun fillNotice(result: FillResult?, half: FillHalf, @StringRes busyReason: Int): FillNotice? = when (result) {
    is FillResult.Filled -> if (if (half == FillHalf.TITLE) result.title else result.names > 0) null else FillNotice.Failed(half, null)
    FillResult.NothingToFill -> FillNotice.Refused(half.nothing)
    FillResult.Busy -> FillNotice.Refused(busyReason)
    FillResult.NoTranscript -> FillNotice.Refused(R.string.detail_no_transcript)
    is FillResult.Failed -> FillNotice.Failed(half, result.reason)
    null -> FillNotice.Failed(half, null)
}

/**
 * docs/08 "Titles and speaker names": the title sheet's placeholder — `Filled in automatically` while a title left empty
 * will come from ChatGPT once the recording is transcribed ([transcribes], ChatGPT signed in, organizing agreed to — not
 * [consentNeeded]), `Untitled` otherwise.
 */
@StringRes
internal fun titlePlaceholder(transcribes: Boolean, connection: ChatGptConnection, consentNeeded: Boolean): Int =
    if (transcribes && connection is ChatGptConnection.SignedIn && !consentNeeded) R.string.title_auto_fill else R.string.jobs_untitled

/** docs/09 "AI view": a hit found in the notes alone opens on the notes — there is nothing in the transcript to find. */
internal fun opensOnNotes(hit: SearchHit): Boolean = hit.notes != null && hit.snippets.isEmpty() && !hit.matchesInTitle

/** One stretch of an answer: plain text, or a [citation] — `[HH:MM:SS]` or `[MM:SS]` — that plays from its time. */
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

/** The one button under failed notes or a failed answer. */
internal enum class ChatGptRecovery { MANAGE_USAGE, RETRY, NONE }

internal fun chatGptRecovery(reason: String): ChatGptRecovery = when (CoreMessageRef.parse(reason)?.message) {
    CoreMessage.CHATGPT_USAGE_LIMIT -> ChatGptRecovery.MANAGE_USAGE
    // The sentence says where to sign in; there is nothing to press here. Nor for a plan without access: asking again
    // gets the same answer.
    CoreMessage.CHATGPT_SIGN_IN_REQUIRED, CoreMessage.CHATGPT_PLAN_REQUIRED -> ChatGptRecovery.NONE
    else -> ChatGptRecovery.RETRY
}

/** The model an answer names: the plan's name for it when written, else the current list's, else its id. */
internal fun modelLabel(modelName: String?, model: String, models: List<ChatGptModel>): String =
    modelName ?: models.firstOrNull { it.id == model }?.label ?: model

/**
 * Notes or an answer that could not be written, as a centred notice: the reason's own sentence where it says
 * what to do, otherwise [generic] with the detail as it came — and the one button that helps (docs/09 "AI view").
 */
@Composable
internal fun ChatGptFailure(reason: String, @StringRes generic: Int, onRetry: () -> Unit, modifier: Modifier, retryTag: String) {
    val context = LocalContext.current
    val spoken = spokenReason(reason)
    val text = if (spoken != null) coreMessage(spoken).text() else stringResource(generic)
    val detail = if (spoken != null) null else reasonDetail(reason)
    val button: (@Composable () -> Unit)? = when (chatGptRecovery(reason)) {
        ChatGptRecovery.MANAGE_USAGE -> {
            { BlueprintButton(stringResource(R.string.chatgpt_manage_usage), { context.openUrl(CHATGPT_USAGE_URL) }, tone = ButtonTone.PRIMARY) }
        }
        ChatGptRecovery.RETRY -> {
            { BlueprintButton(stringResource(R.string.action_retry), onRetry, tone = ButtonTone.QUIET, modifier = Modifier.testTag(retryTag)) }
        }
        ChatGptRecovery.NONE -> null
    }
    Notice(text, detail = detail, button = button, modifier = modifier)
}

/**
 * docs/09 "AI view": an answer's text with every citation in it a way to play from its time —
 * the citation in monospace and the accent, with no underline (a dotted one is a web page). Each citation is a target
 * on every line it sits on, its glyphs grown to at least [MinTouch] each way; where two targets overlap — a citation
 * on each of two lines — a tap plays the one whose glyphs are nearest it ([citationAt], the iPhone and Mac's rule).
 * The taps are read by one layer over the text that takes only a tap inside a target, so the text stays one
 * selectable text and a long press anywhere selects as before. A citation the player cannot reach now ([canSeek]) is
 * drawn muted and is no target. For a screen reader each citation is its own `Play from {time}` button.
 *
 * The words take their direction from themselves: an English answer in an Arabic app still reads left to right.
 * [padding] is inside the tap layer, so a target reaches into it.
 */
@Composable
internal fun CitedText(text: String, canSeek: (Double) -> Boolean, onSeek: (Double) -> Unit, modifier: Modifier = Modifier, padding: Dp = Space.m) {
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
    val inset = with(density) { padding.toPx() }
    // In the tap layer's own coordinates: the text sits [padding] in from its top-left corner whatever the direction.
    val targets = layout?.let { lines -> citationTargets(lines, runs) }.orEmpty()
        .filter { target -> runs[target.run].citation?.let { canSeek(it.atSec) } == true }
        .map { it.copy(glyphs = it.glyphs.translate(inset, inset)) }
    val play by rememberUpdatedState { target: CitationTarget -> runs[target.run].citation?.let { onSeek(it.atSec) } }
    Box(
        modifier.pointerInput(targets, reach) {
            awaitEachGesture {
                // Before the text's own gestures see it, and taking nothing unless it ends as a tap in a target.
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val target = citationAt(down.position, targets, reach) ?: return@awaitEachGesture
                val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    var change = down
                    while (change.pressed) {
                        change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull null
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull null
                    }
                    change
                }
                if (up != null) {
                    up.consume()
                    play(target)
                }
            }
        },
    ) {
        Text(styled, Modifier.fillMaxWidth().padding(padding), style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
            color = palette.text, onTextLayout = { layout = it })
        // A citation the layout cut over two lines is one button, on its first line. Placed by the layout's own
        // left-to-right coordinates, so a right-to-left page does not mirror it away from its citation.
        targets.distinctBy { it.run }.forEach { target ->
            val cited = runs[target.run].citation ?: return@forEach
            val area = target.area(reach)
            val label = stringResource(R.string.citation_play_from, citationTime(text, cited))
            Box(
                Modifier
                    .align(AbsoluteAlignment.TopLeft)
                    .absoluteOffset { IntOffset(area.left.roundToInt(), area.top.roundToInt()) }
                    .size(with(density) { area.width.toDp() }, with(density) { area.height.toDp() })
                    .semantics {
                        contentDescription = label
                        role = Role.Button
                        onClick(label) { onSeek(cited.atSec); true }
                    },
            )
        }
    }
}

/** docs/09 "AI view": a citation's target — its glyphs on one line of the text, by its place among the text's runs. */
internal data class CitationTarget(val run: Int, val glyphs: Rect) {
    /** At least [reach] each way, centred on the glyphs, so it reaches over the lines above and below: [citationAt] decides. */
    fun area(reach: Float): Rect {
        val width = maxOf(glyphs.width, reach)
        val height = maxOf(glyphs.height, reach)
        return Rect(glyphs.center.x - width / 2, glyphs.center.y - height / 2, glyphs.center.x + width / 2, glyphs.center.y + height / 2)
    }

    /** How far [point] is from the glyphs themselves — nothing when it is on them. */
    fun distance(point: Offset): Float = hypot(
        maxOf(glyphs.left - point.x, 0f, point.x - glyphs.right),
        maxOf(glyphs.top - point.y, 0f, point.y - glyphs.bottom),
    )
}

/**
 * What a tap at [point] plays: of the targets whose area holds it, the one whose glyphs are nearest — so a tap on a
 * citation's own glyphs plays that citation even where the next line's target reaches over it.
 */
internal fun citationAt(point: Offset, targets: List<CitationTarget>, reach: Float): CitationTarget? =
    targets.filter { it.area(reach).contains(point) }.minByOrNull { it.distance(point) }

/** Every citation's glyphs, one box for each line it is laid out on. */
internal fun citationTargets(layout: TextLayoutResult, runs: List<TextRun>): List<CitationTarget> = buildList {
    runs.forEachIndexed { index, run ->
        if (run.citation == null || run.end <= run.start) return@forEachIndexed
        for (line in layout.getLineForOffset(run.start)..layout.getLineForOffset(run.end - 1)) {
            val from = maxOf(run.start, layout.getLineStart(line))
            val to = minOf(run.end, layout.getLineEnd(line))
            if (from < to) add(CitationTarget(index, layout.getPathForRange(from, to).getBounds()))
        }
    }
}

/**
 * Who wrote the text above it, and Copy all as the copy icon (2026-10-28), which is the check mark — `Copied` to a screen
 * reader — for the moment the transcript's says `✓ Copied`.
 */
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
        GlyphButton(
            if (copied) Glyph.CHECK else Glyph.COPY,
            stringResource(if (copied) R.string.transcript_copied else R.string.transcript_copy),
            { if (!copied) { clipboard.setText(AnnotatedString(text)); copied = true } },
            copyModifier,
        )
    }
}

private const val COPIED_MS = 3_000L
