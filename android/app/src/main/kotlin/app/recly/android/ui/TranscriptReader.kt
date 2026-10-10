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
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.recly.android.R
import app.recly.android.ui.component.BlueprintButton
import app.recly.android.ui.component.ButtonTone
import app.recly.android.ui.theme.LocalReduceMotion
import app.recly.android.ui.theme.MinTouch
import app.recly.android.ui.theme.Radius
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import app.recly.android.ui.theme.mono
import recly.core.recording.RecordingSearch
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

/** Where [query] occurs in the groups' text, folded the way the search folds it — the find bar's matches, in reading order. */
internal data class FindMatch(val group: Int, val offset: Int, val length: Int)

internal fun findMatches(groups: List<ReaderGroup>, query: String): List<FindMatch> =
    groups.flatMap { group -> RecordingSearch.findRanges(group.text, query).map { FindMatch(group.index, it.offset, it.length) } }


/**
 * The speaker a group header shows: the name the user gave, [me] — the app's word for "Me" — for the person who made
 * the recording while they have no name (docs/08 "Me and others"), or the id.
 */
internal fun speakerLabel(transcript: Transcript, id: String, me: String): String {
    val speaker = transcript.speakers.firstOrNull { it.id == id }
    return speaker?.name ?: if (speaker?.me == true) me else id
}

/** A label that is a word — a name, or "Me" — is set in the body face; an id like `S1` is data, in monospace. */
internal fun speakerIsWord(transcript: Transcript, id: String): Boolean =
    transcript.speakers.firstOrNull { it.id == id }?.let { it.name != null || it.me == true } == true

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
    /** The recording's length, which picks the format of every time button ([clock]). */
    scaleSec: Long? = null,
    seekableDurationSec: Double = Double.POSITIVE_INFINITY,
    positionSec: Double = 0.0,
    playing: Boolean = false,
    highlights: List<Double> = emptyList(),
    onHighlight: (Double) -> Unit = {},
    onSpeaker: (ReaderGroup) -> Unit = {},
    /** False while a speaker change saves: the badges wait for it rather than start a second one. */
    speakersEnabled: Boolean = true,
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
    // docs/09: a match is the accent at 16 %; the one the find bar is on, a step stronger (as the PC draws it).
    val tint = palette.accent.copy(alpha = MATCH_TINT)
    val currentTint = palette.accent.copy(alpha = CURRENT_MATCH_TINT)
    val currentMatch = find.getOrNull(findCurrent)
    val me = stringResource(R.string.speaker_me)
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
                    val stamp = clock(group.start.toLong(), scaleSec)
                    // Spoken text keeps its one format (UX decisions of 2026-10-08).
                    val seekLabel = stringResource(R.string.transcript_seek, hms(group.start.toLong()))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), itemVerticalAlignment = Alignment.CenterVertically) {
                        BlueprintButton(stamp, { onSeek(group.start) }, enabled = canSeek && group.start < seekableDurationSec,
                            modifier = Modifier.testTag("transcript-time-${group.index}").semantics { contentDescription = seekLabel },
                            tone = if (now) ButtonTone.ACCENT else ButtonTone.QUIET, monospace = true)
                        // docs/09 "Highlights": right after the time, before the speaker. A group holds the marks from its
                        // start to the next group's — the first one from 0 — so a mark in a pause, or before the first
                        // words, is not lost.
                        val from = if (group.index == 0) 0.0 else group.start
                        val end = groups.getOrNull(group.index + 1)?.start ?: Double.POSITIVE_INFINITY
                        highlights.filter { it >= from && it < end }.forEach { at ->
                            Box {
                                HighlightMarker(at, onClick = { onHighlight(at) })
                                if (highlightMenuFor == at) highlightMenu()
                            }
                        }
                        if (group.speaker.isNotEmpty()) Box {
                            SpeakerBadge(speakerLabel(transcript, group.speaker, me), named = speakerIsWord(transcript, group.speaker),
                                onClick = { onSpeaker(group) }, enabled = speakersEnabled, modifier = Modifier.testTag("transcript-speaker-${group.index}"))
                            if (speakerMenuFor == group.index) speakerMenu()
                        }
                        if (savingGroup == group.index && savingLabel != null) {
                            Text(savingLabel, style = MaterialTheme.typography.bodySmall, color = palette.textMuted)
                        }
                    }
                    val matches = find.filter { it.group == group.index }
                    // What was said takes its direction from itself: English in an Arabic app still reads left to right.
                    Text(if (matches.isEmpty()) AnnotatedString(group.text) else buildAnnotatedString {
                        append(group.text)
                        matches.forEach { addStyle(SpanStyle(background = if (it == currentMatch) currentTint else tint), it.offset, it.offset + it.length) }
                    }, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content), color = palette.text,
                        modifier = Modifier.testTag("transcript-text-${group.index}"))
                }
            }
        }
    }
}

/** A find match's tint, and the current one's. */
private const val MATCH_TINT = 0.16f
private const val CURRENT_MATCH_TINT = 0.4f

/**
 * docs/09 "Transcript reader": who speaks, as a quiet badge — the name in the body face, an id like `S1` in
 * monospace. A tap opens the speaker menu. The border is the whole [MinTouch] target, so the badge stands as tall
 * as the time button beside it, on the same centre line.
 */
@Composable
internal fun SpeakerBadge(label: String, named: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val palette = blueprint
    Box(
        modifier
            .defaultMinSize(minWidth = MinTouch, minHeight = MinTouch)
            .border(palette.line, if (enabled) palette.textMuted else palette.grid, RoundedCornerShape(Radius.badge))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.s),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = if (named) MaterialTheme.typography.labelLarge else mono.small,
            color = palette.textMuted,
            maxLines = 1,
        )
    }
}

/**
 * A highlight inside a group: a 6dp filled accent square after its time; a tap opens Go to / Remove. The
 * square stands in the middle of its own 48dp target ([MinTouch], docs/09 "Accessibility"), laid out
 * as such: a target that only reached past a 6dp slot would take the taps meant for the time beside it.
 */
@Composable
internal fun HighlightMarker(atSec: Double, onClick: () -> Unit) {
    val label = stringResource(R.string.highlight_tick, hms(atSec.toLong()))
    Box(
        Modifier
            .size(MinTouch)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag("transcript-highlight"),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(6.dp).background(blueprint.accent))
    }
}

/**
 * docs/09 "Transcript reader": following paused by a scroll — the way back to the playhead. A 32dp pill to
 * look at, inside a 48dp target ([MinTouch]) that takes the tap (docs/09 "Accessibility").
 */
@Composable
internal fun BackToPlayback(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = blueprint
    val shape = RoundedCornerShape(Radius.card)
    Box(
        modifier
            .heightIn(min = MinTouch)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("back-to-playback"),
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
            Text(stringResource(R.string.reader_back_to_playback), style = MaterialTheme.typography.labelLarge, color = palette.accent)
        }
    }
}
