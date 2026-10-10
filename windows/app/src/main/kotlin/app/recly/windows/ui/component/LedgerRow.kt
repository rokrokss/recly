package app.recly.windows.ui.component

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.recly.windows.ui.theme.Motion
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import app.recly.windows.ui.theme.mono

/** docs/09 screen principle 2: the ledger's four columns — `Time · Title · Length · Status`, as on the Mac. */
private val TIME_COLUMN = 68.dp

/**
 * At least this wide, and as wide as `01:02:33` — the longest thing [app.recly.windows.ui.LedgerFormat.length]
 * mints — in the column's own type ([lengthColumnWidth]).
 */
private val LENGTH_COLUMN = 52.dp

/** The length column, measured against the widest length the ledger can write. */
@Composable
private fun lengthColumnWidth(): Dp =
    maxOf(LENGTH_COLUMN, textColumnWidth(listOf(app.recly.windows.ui.LedgerFormat.LONGEST_LENGTH), mono.small))

/**
 * Aligns an expanded row's action with the right edge of the column's centered DONE badge. [status]
 * is the ledger's measured status column ([statusColumnWidth]); [done] is that badge as it is drawn,
 * its word in the app's language.
 */
@Composable
fun LedgerAction(status: Dp, done: LedgerStatus, content: @Composable () -> Unit) {
    Layout(content = {
        Box { content() }
        StatusBadge(done, Modifier.clearAndSetSemantics {})
    }) { measurables, constraints ->
        val badge = measurables[1].measure(constraints.copy(
            minWidth = 0, maxWidth = status.roundToPx(), minHeight = 0,
        ))
        val inset = ((status.roundToPx() - badge.width) / 2).coerceAtLeast(0)
        val action = measurables[0].measure(constraints.copy(
            minWidth = 0, maxWidth = (constraints.maxWidth - inset).coerceAtLeast(0), minHeight = 0,
        ))
        layout(action.width + inset, action.height) {
            action.placeRelative(0, 0)
        }
    }
}

/** A 1dp rule (2dp in high contrast) — the only divider this design has. */
@Composable
fun HairLine(modifier: Modifier = Modifier) {
    val palette = blueprint
    Box(
        modifier
            .fillMaxWidth()
            .height(palette.line)
            .background(palette.grid),
    )
}

/** The same rule stood on its end, between the editor's sidebar and its canvas. */
@Composable
fun VerticalHairLine(modifier: Modifier = Modifier) {
    val palette = blueprint
    Box(modifier.width(palette.line).background(palette.grid))
}

/** The column headings above the ledger. */
@Composable
fun LedgerHeader(
    time: String,
    title: String,
    length: String,
    status: String,
    /** The measured status column ([statusColumnWidth]), shared with every row under it. */
    statusWidth: Dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.m, vertical = 6.dp)
                .clearAndSetSemantics {},
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Heading(time, Modifier.width(TIME_COLUMN))
            Heading(title, Modifier.weight(1f))
            Heading(length, Modifier.width(lengthColumnWidth()), TextAlign.End)
            Heading(status, Modifier.width(statusWidth), TextAlign.Center)
        }
        HairLine()
    }
}

@Composable
private fun Heading(text: String, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = blueprint.textMuted,
        textAlign = align,
        maxLines = 1,
    )
}

/**
 * One recording, as a row of the ledger: when (monospace), what, how long, and the state as a code
 * (docs/09 screen principle 2). The whole row is one accessibility node — four separate announcements of
 * `08-29`, a title, `42:10` and `DONE` are worse than one sentence — so the caller hands in the
 * sentence, with the state in words rather than as a code.
 */
@Composable
fun LedgerRow(
    date: String,
    time: String,
    title: String,
    subtitle: String,
    length: String,
    status: LedgerStatus,
    /** The measured status column ([statusColumnWidth]). */
    statusWidth: Dp,
    announce: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = blueprint
    Column(modifier.background(palette.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .semantics(mergeDescendants = true) {
                    contentDescription = announce
                    role = Role.Button
                }
                .padding(horizontal = Space.m, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Times are data, left to right in every language (2026-10-10).
            val stamp = mono.small.copy(textDirection = TextDirection.Ltr)
            Column(Modifier.width(TIME_COLUMN).clearAndSetSemantics {}) {
                Text(date, style = stamp, color = palette.textMuted, maxLines = 1)
                Text(time, style = stamp, color = palette.textMuted, maxLines = 1)
            }
            Column(
                modifier = Modifier.weight(1f).clearAndSetSemantics {},
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Right-aligned, so the minutes of every row stand in the same place — a column of
            // clock faces is read down, not across (the Mac's `howLong`).
            Text(
                length,
                modifier = Modifier.width(lengthColumnWidth()).clearAndSetSemantics {},
                style = stamp,
                color = palette.text,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
            Box(Modifier.width(statusWidth).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                // docs/09 "Motion": a state badge that swaps in place is the one transition this row
                // has, and it is the short one — [Motion.BADGE_FADE_MS] on the standard easing.
                Crossfade(
                    targetState = status,
                    animationSpec = tween(durationMillis = Motion.BADGE_FADE_MS, easing = Motion.Standard),
                ) { swapped ->
                    StatusBadge(swapped)
                }
            }
        }
        HairLine()
    }
}
