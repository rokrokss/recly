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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
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
import app.recly.windows.ui.component.SELECTION_MARK
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptDocument

/** Where an inline speaker save on a group is: running, then done for a moment. */
internal enum class InlineSave { SAVING, SAVED }

/** A group's menu that is open: its speaker's, or its highlight's, hung from where it was clicked. */
private data class GroupMenu(val block: Int, val at: Offset, val speaker: String? = null, val highlight: Double? = null)

/**
 * docs/09 "Screen principles": the transcript beside the recording, a group of one speaker's lines at a time.
 * While playing, the group under the playhead wears an accent bar on its start edge and an accent time,
 * and the list keeps it in its upper third — until the user scrolls, when following pauses and
 * `Back to playback` brings it back. A group's speaker is a badge that opens the speaker menu; a group
 * with a highlight in it has a small accent square after its time. Find matches are tinted, the current one more.
 */
@Composable
internal fun TranscriptReader(
    transcript: Transcript,
    document: TranscriptDocument,
    canSeek: Boolean,
    onSeek: (Double) -> Unit,
    positionSec: Double,
    playing: Boolean,
    highlights: List<Double>,
    onRemoveHighlight: (Double) -> Unit,
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
    /** The group at [index] in the upper third of the list, 200 ms there, as a move of the reader's own. */
    suspend fun bringUp(index: Int) {
        moving = true
        try {
            val height = snapshotFlow { list.layoutInfo.viewportSize.height }.first { it > 0 }
            list.animateScrollToItem(index, -height / 3)
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
                    val end = blocks.getOrNull(index + 1)?.start ?: Double.POSITIVE_INFINITY
                    Group(
                        transcript = transcript,
                        index = index,
                        start = block.start,
                        speaker = block.speaker,
                        text = tinted(block.text, matches, index, currentMatch),
                        highlight = highlights.firstOrNull { it >= block.start && it < end },
                        isActive = index == active,
                        canSeek = canSeek && block.start < seekableDurationSec,
                        onSeek = onSeek,
                        onSpeakerMenu = { at -> menu = GroupMenu(index, at - origin, speaker = block.speaker) },
                        onHighlightMenu = { at, highlight -> menu = GroupMenu(index, at - origin, highlight = highlight) },
                        saving = saving[index],
                        speakerEnabled = !busy,
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
            Box(Modifier.offset { IntOffset(open.at.x.roundToInt(), open.at.y.roundToInt()) }) {
                open.speaker?.let { speaker ->
                    SpeakerMenu(transcript.speakers, speaker, close, { onRenameSpeaker(open.block, speaker) }, { onChangeSpeaker(open.block, ranges[open.block], it) }, strings)
                }
                open.highlight?.let { at -> HighlightMenu(at, spanSec, close, { onSeek(at) }, { onRemoveHighlight(at) }, strings) }
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
    highlight: Double?,
    isActive: Boolean,
    canSeek: Boolean,
    onSeek: (Double) -> Unit,
    /** Where the badge or the flag is on screen, for the menu the reader hangs there. */
    onSpeakerMenu: (Offset) -> Unit,
    onHighlightMenu: (Offset, Double) -> Unit,
    saving: InlineSave?,
    speakerEnabled: Boolean,
    strings: Strings,
    spanSec: Double?,
) {
    val palette = blueprint
    val stamp = LedgerFormat.clock(start, spanSec)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(if (isActive) palette.accent else Color.Transparent))
        Column(Modifier.padding(start = Space.m - 2.dp, end = Space.m)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
                // What a screen reader hears is unchanged: hours always said.
                val seekLabel = strings[Str.TRANSCRIPT_SEEK, LedgerFormat.elapsed((start * 1000).toLong())]
                BlueprintButton(
                    stamp, { onSeek(start) }, enabled = canSeek,
                    modifier = Modifier.testTag("transcript-time-$index").semantics { contentDescription = seekLabel },
                    tone = if (isActive) ButtonTone.ACCENT else ButtonTone.QUIET, monospace = true,
                )
                highlight?.let { at -> HighlightFlag(at, strings) { place -> onHighlightMenu(place, at) } }
                if (speaker.isNotEmpty()) {
                    var place by remember { mutableStateOf(Offset.Zero) }
                    Box(Modifier.onGloballyPositioned { place = it.positionInRoot() }) {
                        SpeakerBadge(speaker, transcript.speakers.firstOrNull { it.id == speaker }?.name, speakerEnabled) { onSpeakerMenu(place) }
                    }
                }
                when (saving) {
                    InlineSave.SAVING -> LoadingText(strings[Str.EDIT_SAVING], MaterialTheme.typography.bodySmall, palette.textMuted)
                    InlineSave.SAVED -> Text(SELECTION_MARK, style = MaterialTheme.typography.bodySmall, color = palette.success)
                    null -> Unit
                }
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = palette.text, modifier = Modifier.testTag("transcript-text-$index"))
        }
    }
}

/**
 * The square right after a group's time, centred with it: it opens the highlight's menu, and a reader hears
 * it as "Highlight 00:12:34". Its target is only a gap wider than the square, so the square stays by the time.
 */
@Composable
private fun HighlightFlag(atSec: Double, strings: Strings, onMenu: (Offset) -> Unit) {
    var place by remember { mutableStateOf(Offset.Zero) }
    val label = strings[Str.HIGHLIGHT_TICK, LedgerFormat.elapsed((atSec * 1000).toLong())]
    Box(
        Modifier
            .size(width = HIGHLIGHT_MARK + Space.xs * 2, height = 28.dp)
            .onGloballyPositioned { place = it.positionInRoot() }
            .clickable(role = Role.Button) { onMenu(place) }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { HighlightMark() }
}

/** docs/09 "Screen principles": the pill that takes the list back to where playback is. */
@Composable
private fun BackToPlayback(label: String, modifier: Modifier, onClick: () -> Unit) {
    val palette = blueprint
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .height(32.dp)
            .background(palette.surface, shape)
            .border(palette.line, palette.accent, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.m),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = palette.accent)
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
