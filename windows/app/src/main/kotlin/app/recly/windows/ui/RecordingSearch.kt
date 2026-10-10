package app.recly.windows.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import app.recly.windows.ui.component.HairLine
import app.recly.windows.ui.component.fieldBox
import app.recly.windows.ui.theme.MinTouch
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import app.recly.windows.ui.theme.mono
import recly.core.recording.SearchHit
import recly.core.recording.SearchRange
import recly.core.recording.SummaryMatch

/**
 * docs/10 "Search": a field on the input border — the accent while it has the focus, as every field (2026-10-10) —
 * with a magnifier at its start and, where [clearLabel] is given, a clear mark at its end while it has text. [label]
 * is what a reader hears it called; the placeholder says the same, cut with a mark when it does not fit.
 */
@Composable
internal fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    label: String,
    /** Null for a field whose bar has its own close (the find bar, 2026-10-10). */
    clearLabel: String?,
    modifier: Modifier = Modifier,
    focus: FocusRequester? = null,
) {
    val palette = blueprint
    Row(
        modifier
            .defaultMinSize(minHeight = MinTouch)
            .fieldBox()
            .padding(start = Space.s),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(14.dp).clearAndSetSemantics { }) {
            val stroke = 1.5.dp.toPx()
            val r = size.minDimension * 0.34f
            drawCircle(palette.textMuted, r, Offset(r + stroke, r + stroke), style = Stroke(stroke))
            drawLine(palette.textMuted, Offset(r * 1.7f + stroke, r * 1.7f + stroke), Offset(size.width, size.height), stroke)
        }
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.weight(1f).then(focus?.let { Modifier.focusRequester(it) } ?: Modifier).semantics { contentDescription = label },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = palette.text, textDirection = TextDirection.Content),
            cursorBrush = SolidColor(palette.accent),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = palette.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    inner()
                }
            },
        )
        if (query.isNotEmpty() && clearLabel != null) {
            Box(
                Modifier.size(MinTouch).clickable(role = Role.Button) { onQuery("") }.semantics { contentDescription = clearLabel },
                contentAlignment = Alignment.Center,
            ) {
                Text("×", style = MaterialTheme.typography.labelLarge, color = palette.textMuted, modifier = Modifier.clearAndSetSemantics { })
            }
        } else {
            Box(Modifier.size(Space.s))
        }
    }
}

/**
 * docs/10 "Search": one recording a search found — its time, its title and up to two transcript lines with
 * the matches tinted, the summary line that matched under them after [summaryLabel], and the time of the first
 * transcript hit at the end.
 */
@Composable
internal fun SearchResultRow(hit: SearchHit, untitled: String, summaryLabel: String, totalSec: Double?, onOpen: () -> Unit) {
    val palette = blueprint
    Column(Modifier.fillMaxWidth().background(palette.surface)) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen).padding(horizontal = Space.m, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column {
                Text(LedgerFormat.date(hit.startedAt), style = mono.small.copy(textDirection = TextDirection.Ltr), color = palette.textMuted, maxLines = 1)
                Text(LedgerFormat.time(hit.startedAt), style = mono.small.copy(textDirection = TextDirection.Ltr), color = palette.textMuted, maxLines = 1)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    tinted(hit.title?.takeIf { it.isNotBlank() } ?: untitled, hit.titleRanges),
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The transcript's and the summary's words, in the direction they take (2026-10-10).
                val words = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content)
                hit.snippets.take(SNIPPET_LINES).forEach { snippet ->
                    Text(tinted(snippet.text, snippet.ranges), style = words, color = palette.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                hit.summary?.let { summarySnippet(summaryLabel, it) }?.let { line ->
                    Text(tinted(line.text, line.ranges), style = words, color = palette.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            hit.snippets.firstOrNull()?.let {
                Text(LedgerFormat.clock(it.atSec, totalSec), style = mono.small.copy(textDirection = TextDirection.Ltr), color = palette.textMuted, maxLines = 1)
            }
        }
        HairLine()
    }
}

/**
 * docs/10 "Search": the find bar at the top of the transcript — the query, `‹ 2 / 7 ›` and close, which is the bar's
 * one close: its field has no clear mark of its own (2026-10-10). The arrows move between matches without moving the
 * playhead.
 */
@Composable
internal fun FindBar(
    query: String,
    onQuery: (String) -> Unit,
    count: Int,
    current: Int?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    focus: FocusRequester,
    strings: Strings,
) {
    val palette = blueprint
    Row(
        Modifier.fillMaxWidth().background(palette.surface).padding(horizontal = Space.m, vertical = Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchField(query, onQuery, strings[Str.TRANSCRIPT_SEARCH], null, Modifier.weight(1f), focus)
        FindArrow("‹", strings[Str.FIND_PREVIOUS], count > 0, onPrevious)
        val position = if (count == 0) "0 / 0" else "${(current ?: 0) + 1} / $count"
        Text(
            position,
            style = mono.small.copy(textDirection = TextDirection.Ltr),
            color = palette.textMuted,
            modifier = Modifier.semantics { contentDescription = strings[Str.FIND_POSITION, if (count == 0) 0 else (current ?: 0) + 1, count] },
        )
        FindArrow("›", strings[Str.FIND_NEXT], count > 0, onNext)
        FindArrow("×", strings[Str.FIND_CLOSE], true, onClose)
    }
}

@Composable
private fun FindArrow(mark: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(MinTouch).clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        // Off, the mark is muted rather than the grid's near-nothing (2026-10-10).
        Text(mark, style = MaterialTheme.typography.titleMedium, color = if (enabled) blueprint.text else blueprint.textMuted, modifier = Modifier.clearAndSetSemantics { })
    }
}

/** A hit found in the summary alone — not in the title, not in the transcript — opens on the summary (docs/10 "Search"). */
internal fun opensOnSummary(hit: SearchHit): Boolean = hit.summary != null && hit.snippets.isEmpty() && !hit.matchesInTitle

/** The summary's line as a snippet: `Summary · ` in front, and the matches moved along by it. */
internal fun summarySnippet(label: String, match: SummaryMatch): SummaryMatch {
    val prefix = label + SUMMARY_SEPARATOR
    return SummaryMatch(prefix + match.text, match.ranges.map { it.copy(offset = it.offset + prefix.length) })
}

/** The core's match ranges as the accent at 16 % behind the text, which stays the body colour. */
@Composable
private fun tinted(text: String, ranges: List<SearchRange>): AnnotatedString {
    val tint = blueprint.accent.copy(alpha = MATCH_TINT)
    return buildAnnotatedString {
        append(text)
        ranges.forEach { addStyle(SpanStyle(background = tint), it.offset.coerceIn(0, text.length), (it.offset + it.length).coerceIn(0, text.length)) }
    }
}

private const val SNIPPET_LINES = 2

private const val SUMMARY_SEPARATOR = " · "
