package app.recly.windows.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.style.TextDirection
import app.recly.windows.ui.component.pressCursor
import kotlin.math.roundToInt
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import app.recly.windows.ui.component.BlueprintButton
import app.recly.windows.ui.component.ButtonTone
import app.recly.windows.ui.component.LoadingText
import app.recly.windows.ui.component.focusRing
import app.recly.windows.ui.theme.LocalReduceMotion
import app.recly.windows.ui.theme.MinTouch
import app.recly.windows.ui.theme.Radius
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptDocument

/** Where an inline speaker save on a group is: running. */
internal enum class InlineSave { SAVING }

/** A group's speaker menu that is open, hung from where it was clicked. */
private data class GroupMenu(val block: Int, val at: Offset, val speaker: String)

/**
 * docs/09 "Screen principles": the transcript beside the recording, a group of one speaker's lines at a time.
 * While playing, the group under the playhead wears an accent bar on its start edge and an accent time,
 * and the list keeps it in its upper third — until the user scrolls, when following pauses and
 * `Back to playback` brings it back. A group's speaker is a badge that opens the speaker menu. Find matches are
 * tinted, the current one more.
 */
@Composable
internal fun TranscriptReader(
    transcript: Transcript,
    document: TranscriptDocument,
    canSeek: Boolean,
    onSeek: (Double) -> Unit,
    positionSec: Double,
    playing: Boolean,
    matches: List<FindMatch>,
    currentMatch: Int?,
    /** A group (its index and its segments), and who should say it — null is a new speaker. */
    onChangeSpeaker: (Int, IntRange, String?) -> Unit,
    onRenameSpeaker: (Int, String) -> Unit,
    saving: Map<Int, InlineSave>,
    strings: Strings,
    modifier: Modifier = Modifier,
    seekableDurationSec: Double = Double.POSITIVE_INFINITY,
    /** The recording's length, which picks the format of every time drawn here ([LedgerFormat.clock]). */
    spanSec: Double? = transcript.durationSec,
    /**
     * Why the speakers cannot be changed now — a job of the recording is still unsettled, and the core refuses the
     * edit (D13) — or null. The badges are off and say it to a reader.
     */
    speakersBlocked: String? = null,
) {
    val blocks = document.blocks
    val ranges = remember(transcript) { blockSegments(transcript, blocks) }
    val list = rememberLazyListState()
    LaunchedEffect(transcript.recordingId) { list.scrollToItem(0) }
    val active = if (playing) blocks.indexOfLast { it.start <= positionSec }.takeIf { it >= 0 } else null
    var following by remember { mutableStateOf(true) }
    var moving by remember { mutableStateOf(false) }
    LaunchedEffect(playing) { if (!playing) following = true }
    // A scroll the user made, not one this reader made, pauses the following.
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.filter { it }.collect { if (!moving && playing) following = false }
    }
    // docs/09 "Motion": with reduce motion the list jumps where it would have glided.
    val still by rememberUpdatedState(LocalReduceMotion.current)
    /** The group at [index] in the upper third of the list, 200 ms there, as a move of the reader's own. */
    suspend fun bringUp(index: Int) {
        moving = true
        try {
            val height = snapshotFlow { list.layoutInfo.viewportSize.height }.first { it > 0 }
            if (still) list.scrollToItem(index, -height / 3) else list.animateScrollToItem(index, -height / 3)
        } finally {
            moving = false
        }
    }
    LaunchedEffect(active, following) {
        val index = active ?: return@LaunchedEffect
        if (following) bringUp(index)
    }
    LaunchedEffect(currentMatch) {
        currentMatch?.let { matches.getOrNull(it) }?.let { bringUp(it.block) }
    }
    // The menus are drawn over the list, outside its selection: a popup inside a SelectionContainer does not
    // get the click that picks one of its rows.
    var menu by remember { mutableStateOf<GroupMenu?>(null) }
    // One speaker change at a time: the badges wait while one saves.
    val busy = InlineSave.SAVING in saving.values
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.onGloballyPositioned { origin = it.positionInRoot() }) {
        SelectionContainer(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().testTag("transcript-passages"),
                state = list,
                contentPadding = PaddingValues(vertical = Space.s),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                itemsIndexed(blocks, key = { _, block -> block.index }) { index, block ->
                    Group(
                        transcript = transcript,
                        index = index,
                        start = block.start,
                        speaker = block.speaker,
                        text = tinted(block.text, matches, index, currentMatch),
                        isActive = index == active,
                        canSeek = canSeek && block.start < seekableDurationSec,
                        onSeek = onSeek,
                        onSpeakerMenu = { at -> menu = GroupMenu(index, at - origin, speaker = block.speaker) },
                        saving = saving[index],
                        speakerEnabled = !busy && speakersBlocked == null,
                        speakerReason = speakersBlocked,
                        strings = strings,
                        spanSec = spanSec,
                    )
                }
            }
        }
        if (playing && !following) {
            BackToPlayback(strings[Str.TRANSCRIPT_BACK_TO_PLAYBACK], Modifier.align(Alignment.TopCenter).padding(top = Space.s)) { following = true }
        }
        menu?.let { open ->
            val close = { menu = null }
            // Where it was clicked is a point of the window, not of the reading order: placed from the left whatever
            // the language, or in Arabic, Persian and Urdu it hung from the mirror image of the badge.
            Box(Modifier.align(AbsoluteAlignment.TopLeft).absoluteOffset { IntOffset(open.at.x.roundToInt(), open.at.y.roundToInt()) }) {
                SpeakerMenu(transcript.speakers, open.speaker, close, { onRenameSpeaker(open.block, open.speaker) }, { onChangeSpeaker(open.block, ranges[open.block], it) }, strings)
            }
        }
    }
}

@Composable
private fun Group(
    transcript: Transcript,
    index: Int,
    start: Double,
    speaker: String,
    text: AnnotatedString,
    isActive: Boolean,
    canSeek: Boolean,
    onSeek: (Double) -> Unit,
    /** Where the badge is on screen — its start edge, at its foot — for the menu hung there. */
    onSpeakerMenu: (Offset) -> Unit,
    saving: InlineSave?,
    speakerEnabled: Boolean,
    speakerReason: String?,
    strings: Strings,
    spanSec: Double?,
) {
    val palette = blueprint
    val stamp = LedgerFormat.clock(start, spanSec)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(if (isActive) palette.accent else Color.Transparent))
        Column(Modifier.padding(start = Space.m - 2.dp, end = Space.m)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
                // What a screen reader hears is unchanged: hours always said.
                val seekLabel = strings[Str.TRANSCRIPT_SEEK, LedgerFormat.elapsed((start * 1000).toLong())]
                // At the status badge's size (2026-10-28); a press moves the playhead and leaves playing or paused as it was.
                BlueprintButton(
                    stamp, { onSeek(start) }, enabled = canSeek,
                    modifier = Modifier.testTag("transcript-time-$index").semantics { contentDescription = seekLabel },
                    tone = if (isActive) ButtonTone.ACCENT else ButtonTone.QUIET, monospace = true, compact = true,
                )
                if (speaker.isNotEmpty()) {
                    var place by remember { mutableStateOf(Offset.Zero) }
                    // Its menu opens under it (2026-10-10).
                    Box(Modifier.onGloballyPositioned { place = it.positionInRoot() + startFoot(it.size.width, it.size.height, rtl) }) {
                        SpeakerBadge(speaker, speakerName(transcript.speakers.firstOrNull { it.id == speaker }, strings), speakerEnabled, speakerReason) { onSpeakerMenu(place) }
                    }
                }
                when (saving) {
                    InlineSave.SAVING -> LoadingText(strings[Str.EDIT_SAVING], MaterialTheme.typography.bodySmall, palette.textMuted)
                    null -> Unit
                }
            }
            // What was said, in the direction its own words take and aligned by it, across the width (2026-10-10).
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                color = palette.text,
                modifier = Modifier.fillMaxWidth().testTag("transcript-text-$index"),
            )
        }
    }
}

/** The start edge's foot of a box of [width] × [height], from its top left: the right edge in a right-to-left language. */
private fun startFoot(width: Int, height: Int, rtl: Boolean): Offset = Offset(if (rtl) width.toFloat() else 0f, height.toFloat())

/**
 * docs/09 "Screen principles": the pill that takes the list back to where playback is — 32 tall to look at, and
 * a 44 target around it (2026-10-08).
 */
@Composable
private fun BackToPlayback(label: String, modifier: Modifier, onClick: () -> Unit) {
    val palette = blueprint
    val shape = RoundedCornerShape(Radius.card)
    Box(
        modifier
            .defaultMinSize(minHeight = MinTouch)
            .focusRing(shape)
            .pressCursor()
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .height(32.dp)
                .background(palette.surface, shape)
                .border(palette.line, palette.accent, shape)
                .padding(horizontal = Space.m),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = palette.accent)
        }
    }
}

/** A block's text with every find match tinted, the current one stronger. */
@Composable
private fun tinted(text: String, matches: List<FindMatch>, block: Int, current: Int?): AnnotatedString {
    val accent = blueprint.accent
    val mine = matches.withIndex().filter { it.value.block == block }
    if (mine.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        mine.forEach { (index, match) ->
            val alpha = if (index == current) CURRENT_TINT else MATCH_TINT
            addStyle(SpanStyle(background = accent.copy(alpha = alpha)), match.start, (match.start + match.length).coerceAtMost(text.length))
        }
    }
}

/** docs/09: a match is the accent at 16 %; the one the find bar is on, a step stronger. */
internal const val MATCH_TINT = 0.16f
private const val CURRENT_TINT = 0.4f
