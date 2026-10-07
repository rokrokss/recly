package app.recly.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import app.recly.android.R
import app.recly.android.ui.component.BlueprintButton
import app.recly.android.ui.component.ButtonTone
import app.recly.android.ui.theme.MinTouch
import app.recly.android.ui.theme.LocalReduceMotion
import app.recly.android.ui.theme.Radius
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import app.recly.android.ui.theme.mono
import recly.core.transcribe.Transcript

/**
 * One utterance group of the reader: the segments [segments] say, run together the way the core's
 * `TranscriptDocument` runs them (a new group at a new speaker, past 60 s or past 1,200 characters), with
 * the segment range kept so a speaker change can name every segment of "this line".
 */
internal data class ReaderGroup(val index: Int, val start: Double, val end: Double, val speaker: String, val text: String, val segments: IntRange)

internal fun readerGroups(transcript: Transcript): List<ReaderGroup> = buildList {
    var first = -1
    var last = -1
    val words = StringBuilder()
    fun flush() {
        if (words.isEmpty()) return
        val head = transcript.segments[first]
        add(ReaderGroup(size, head.start.coerceAtLeast(0.0), transcript.segments[last].end, head.speaker, words.toString(), first..last))
        words.clear()
    }
    transcript.segments.forEachIndexed { index, segment ->
        val text = segment.text.trim()
        if (text.isEmpty()) return@forEachIndexed
        if (words.isNotEmpty()) {
            val head = transcript.segments[first]
            if (head.speaker != segment.speaker || segment.end - head.start.coerceAtLeast(0.0) > 60 || words.length + text.length > 1200) flush()
        }
        if (words.isEmpty()) first = index else words.append(' ')
        last = index
        words.append(text)
    }
    flush()
}

/** The group the playhead is in: the last one that starts at or before it. */
internal fun activeGroup(groups: List<ReaderGroup>, positionSec: Double): Int = groups.indexOfLast { it.start <= positionSec }

/** Where [query] occurs in the groups' text, ignoring case — the find bar's matches, in reading order. */
internal data class FindMatch(val group: Int, val offset: Int, val length: Int)

internal fun findMatches(groups: List<ReaderGroup>, query: String): List<FindMatch> {
    val term = query.trim()
    if (term.isEmpty()) return emptyList()
    return groups.flatMap { group ->
        generateSequence(group.text.indexOf(term, ignoreCase = true).takeIf { it >= 0 }) { from ->
            group.text.indexOf(term, from + term.length, ignoreCase = true).takeIf { it >= 0 }
        }.map { FindMatch(group.index, it, term.length) }.toList()
    }
}

/** The speaker a group header shows: the name the user gave, or the id. */
internal fun speakerLabel(transcript: Transcript, id: String): String =
    transcript.speakers.firstOrNull { it.id == id }?.name ?: id

/**
 * docs/09 "Transcript reader": the transcript in utterance groups, each with its time (a seek), its speaker
 * badge and — where a highlight falls in it — a small accent square. While the recording plays, the group under the
 * playhead carries an accent bar and an accent time, and the list keeps it in the upper third; a scroll
 * by the user stops that until [onFollow] is pressed again. With [find] the matches are tinted and the
 * current one is brought into view.
 */
@Composable
internal fun TranscriptReader(
    transcript: Transcript,
    canSeek: Boolean,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
    seekableDurationSec: Double = Double.POSITIVE_INFINITY,
    positionSec: Double = 0.0,
    playing: Boolean = false,
    highlights: List<Double> = emptyList(),
    onHighlight: (Double) -> Unit = {},
    onSpeaker: (ReaderGroup) -> Unit = {},
    /** The group whose speaker menu is open, and the menu — drawn beside its badge so it opens there. */
    speakerMenuFor: Int? = null,
    speakerMenu: @Composable () -> Unit = {},
    /** The highlight whose Go to / Remove menu is open, and that menu. */
    highlightMenuFor: Double? = null,
    highlightMenu: @Composable () -> Unit = {},
    /** The speaker change being saved from the menu, and what to say beside its badge meanwhile. */
    savingGroup: Int? = null,
    savingLabel: String? = null,
    find: List<FindMatch> = emptyList(),
    findCurrent: Int = -1,
    /** Following the playhead is paused by the user's own scroll, and resumed by "Back to playback". */
    following: Boolean = true,
    onFollowChange: (Boolean) -> Unit = {},
    startAt: Int? = null,
) {
    val groups = remember(transcript) { readerGroups(transcript) }
    val list = rememberLazyListState()
    val palette = blueprint
    val reduce = LocalReduceMotion.current
    val active = if (playing) activeGroup(groups, positionSec) else -1
    LaunchedEffect(transcript.recordingId) { list.scrollToItem(startAt?.coerceIn(0, (groups.size - 1).coerceAtLeast(0)) ?: 0) }
    // A drag is the user's own: programmatic scrolls do not start one.
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { if (it is DragInteraction.Start) onFollowChange(false) }
    }
    LaunchedEffect(active, following) {
        if (active < 0 || !following) return@LaunchedEffect
        val offset = -list.layoutInfo.viewportSize.height / 3
        if (reduce) list.scrollToItem(active, offset) else list.animateScrollToItem(active, offset)
    }
    LaunchedEffect(findCurrent) {
        val match = find.getOrNull(findCurrent) ?: return@LaunchedEffect
        val offset = -list.layoutInfo.viewportSize.height / 3
        if (reduce) list.scrollToItem(match.group, offset) else list.animateScrollToItem(match.group, offset)
    }
    val tint = palette.accent.copy(alpha = 0.16f)
    SelectionContainer(modifier) {
        LazyColumn(Modifier.fillMaxSize().testTag("transcript-passages"), state = list,
            contentPadding = PaddingValues(vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            items(groups, key = { it.index }) { group ->
                val now = group.index == active
                Column(
                    Modifier
                        .padding(horizontal = Space.m)
                        // docs/09 "Transcript reader": the group under the playhead, by a bar on its start edge.
                        .drawBehind {
                            if (now) drawRect(palette.accent, topLeft = Offset(-Space.s.toPx(), 0f), size = Size(2.dp.toPx(), size.height))
                        },
                ) {
                    val stamp = hms(group.start.toLong())
                    val seekLabel = stringResource(R.string.transcript_seek, stamp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), itemVerticalAlignment = Alignment.CenterVertically) {
                        BlueprintButton(stamp, { onSeek(group.start) }, enabled = canSeek && group.start < seekableDurationSec,
                            modifier = Modifier.testTag("transcript-time-${group.index}").semantics { contentDescription = seekLabel },
                            tone = if (now) ButtonTone.ACCENT else ButtonTone.QUIET, monospace = true)
                        if (group.speaker.isNotEmpty()) Box {
                            SpeakerBadge(speakerLabel(transcript, group.speaker), named = transcript.speakers.any { it.id == group.speaker && it.name != null },
                                onClick = { onSpeaker(group) }, modifier = Modifier.testTag("transcript-speaker-${group.index}"))
                            if (speakerMenuFor == group.index) speakerMenu()
                        }
                        highlights.filter { it >= group.start && it < group.end.coerceAtLeast(group.start + 0.001) }.forEach { at ->
                            Box {
                                HighlightMarker(at, onClick = { onHighlight(at) })
                                if (highlightMenuFor == at) highlightMenu()
                            }
                        }
                        if (savingGroup == group.index && savingLabel != null) {
                            Text(savingLabel, style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
                        }
                    }
                    val matches = find.filter { it.group == group.index }
                    Text(if (matches.isEmpty()) AnnotatedString(group.text) else buildAnnotatedString {
                        append(group.text)
                        matches.forEach { addStyle(SpanStyle(background = tint), it.offset, it.offset + it.length) }
                    }, style = MaterialTheme.typography.bodyMedium, color = palette.text,
                        modifier = Modifier.testTag("transcript-text-${group.index}"))
                }
            }
        }
    }
}

/**
 * docs/09 "Transcript reader": who speaks, as a quiet badge — the name in the body face, an id like `S1` in
 * monospace. A tap opens the speaker menu.
 */
@Composable
internal fun SpeakerBadge(label: String, named: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = blueprint
    Box(
        modifier
            .defaultMinSize(minHeight = 48.dp)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            modifier = Modifier
                .border(palette.line, palette.textMuted, RoundedCornerShape(Radius.badge))
                .padding(horizontal = Space.s, vertical = 2.dp),
            style = if (named) MaterialTheme.typography.labelLarge else mono.small,
            color = palette.textMuted,
            maxLines = 1,
        )
    }
}

/** A highlight inside a group: a 6dp filled accent square after its time; a tap opens Go to / Remove. */
@Composable
internal fun HighlightMarker(atSec: Double, onClick: () -> Unit) {
    val label = stringResource(R.string.highlight_tick, hms(atSec.toLong()))
    Box(
        Modifier
            .size(MinTouch)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(6.dp).background(blueprint.accent))
    }
}

/** docs/09 "Transcript reader": following paused by a scroll — the way back to the playhead. */
@Composable
internal fun BackToPlayback(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = blueprint
    val shape = RoundedCornerShape(Radius.card)
    Box(
        modifier
            .height(32.dp)
            .background(palette.surface, shape)
            .border(palette.line, palette.accent, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.m)
            .testTag("back-to-playback"),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.reader_back_to_playback), style = MaterialTheme.typography.labelLarge, color = palette.accent)
    }
}
