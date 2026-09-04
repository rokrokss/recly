package app.recly.android.ui.component

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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import app.recly.android.ui.theme.mono

/** docs/09 화면 원칙 2: the ledger's date column, in the width the mockup sets. */
private val TIME_COLUMN = 62.dp

/** The gap between two columns of the ledger. */
private val COLUMN_GAP = Space.s

/**
 * The two columns of the ledger that hold data rather than prose, and so are measured rather than
 * fixed. Both used to be a constant and both lost letters at a font scale of 1.3: `NEEDS_AUTH` in a
 * 76dp column, and `00:09` in a 44dp one, which came out as `00:0`.
 */
data class LedgerColumns(val length: Dp, val status: Dp)

/**
 * The narrowest a title column may be and still be a title: below this it holds a word and an
 * ellipsis, which is not what the ledger is for.
 */
val LedgerTitleMin: Dp = 96.dp

/** What shape the ledger takes at the width it was given. */
enum class LedgerLayout {
    /** when · what · how long · state, on one line. */
    COLUMNS,

    /** when · what, then how long and the state under them. */
    STACKED,
}

/**
 * Four columns, or two lines? The two data columns are measured (see [ledgerColumns]), so on a
 * narrow screen at a large font size they and the fixed time column can take everything: at 320dp
 * and a font scale of 1.3 the title was left some 34dp, and at larger scales nothing at all — at
 * which point the measured columns are squeezed too and the clipping this design measured its way
 * out of comes back. Whenever what is left for the title falls under [titleMin] the row gives up
 * being a table and becomes two lines instead, where the title has the whole width.
 */
fun ledgerLayout(
    available: Dp,
    columns: LedgerColumns,
    titleMin: Dp = LedgerTitleMin,
): LedgerLayout {
    // Everything the title does not get: the row's own padding, the time column, the two measured
    // columns, and the three gaps between the four of them.
    val taken = Space.m * 2 + TIME_COLUMN + columns.length + columns.status + COLUMN_GAP * 3
    return if (available - taken < titleMin) LedgerLayout.STACKED else LedgerLayout.COLUMNS
}

/**
 * [LedgerColumns] for the [lengths] and status [codes] this ledger can show, measured in the style
 * they are drawn in — so the widths carry the fluid scale and the user's own font size, and neither
 * column changes from row to row.
 */
@Composable
fun ledgerColumns(lengths: List<String>, codes: List<String>): LedgerColumns = LedgerColumns(
    length = textColumnWidth(lengths, mono.small),
    status = statusColumnWidth(codes),
)

/**
 * Where the title column starts, so a detail drawn under a row — what an expanded row says about
 * itself — lines up with the title rather than with an indent somebody guessed.
 */
val LedgerTitleInset: Dp = Space.m + TIME_COLUMN + COLUMN_GAP

/** A 1dp rule — the only divider this design has. */
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

/**
 * The column headings above the ledger. A stacked ledger ([LedgerLayout.STACKED]) heads two lines
 * rather than four columns, in the same widths its rows use, so each heading still sits over what
 * it names.
 */
@Composable
fun LedgerHeader(
    time: String,
    title: String,
    length: String,
    status: String,
    columns: LedgerColumns,
    modifier: Modifier = Modifier,
    layout: LedgerLayout = LedgerLayout.COLUMNS,
) {
    Column(modifier) {
        val head = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.m, vertical = Space.s)
            .clearAndSetSemantics {}
        when (layout) {
            LedgerLayout.COLUMNS -> Row(head, Arrangement.spacedBy(COLUMN_GAP)) {
                Heading(time, Modifier.width(TIME_COLUMN))
                Heading(title, Modifier.weight(1f))
                Heading(length, Modifier.width(columns.length), TextAlign.End)
                Heading(status, Modifier.width(columns.status), TextAlign.Center)
            }

            LedgerLayout.STACKED -> Column(head, Arrangement.spacedBy(Space.xs)) {
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(COLUMN_GAP)) {
                    Heading(time, Modifier.width(TIME_COLUMN))
                    Heading(title, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(COLUMN_GAP, Alignment.End)) {
                    Heading(length, Modifier.width(columns.length), TextAlign.End)
                    Heading(status, Modifier.width(columns.status), TextAlign.Center)
                }
            }
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
 * (docs/09 화면 원칙 2). The whole row is one accessibility node — four separate announcements of
 * `08-29`, a title, `42:10` and `DONE` are worse than one sentence — so the caller hands in the
 * sentence, with the state in words rather than as a code.
 *
 * The row also opens a block under itself, and that is a fact a screen reader can otherwise only
 * find out by tapping: [expanded] becomes the row's `expand`/`collapse` action, labelled by
 * [toggleLabel] (docs/09 "접근성").
 */
@Composable
fun LedgerRow(
    date: String,
    time: String,
    title: String,
    subtitle: String,
    length: String,
    status: LedgerStatus,
    columns: LedgerColumns,
    announce: String,
    expanded: Boolean,
    toggleLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    layout: LedgerLayout = LedgerLayout.COLUMNS,
) {
    val palette = blueprint
    Column(modifier.background(palette.surface)) {
        // One node and one tap, whichever shape the row takes: the sentence and the expand action
        // are on the body, and the two layouts below only rearrange what it wraps.
        val body = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = announce
                role = Role.Button
                if (expanded) {
                    collapse(label = toggleLabel) { onClick(); true }
                } else {
                    expand(label = toggleLabel) { onClick(); true }
                }
            }
            .padding(horizontal = Space.m, vertical = 12.dp)
        when (layout) {
            LedgerLayout.COLUMNS -> Row(
                modifier = body,
                horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TimeCell(date, time)
                TitleCell(title, subtitle, Modifier.weight(1f))
                LengthCell(length, Modifier.width(columns.length))
                StatusCell(status, Modifier.width(columns.status))
            }

            // The title has the width to itself; how long and what state go under it, right-aligned
            // in the widths the header uses so the two lines still read as a table.
            LedgerLayout.STACKED -> Column(body, Arrangement.spacedBy(Space.xs)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TimeCell(date, time)
                    TitleCell(title, subtitle, Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LengthCell(length, Modifier.width(columns.length))
                    StatusCell(status, Modifier.width(columns.status))
                }
            }
        }
        HairLine()
    }
}

@Composable
private fun TimeCell(date: String, time: String) {
    val palette = blueprint
    Column(Modifier.width(TIME_COLUMN).clearAndSetSemantics {}) {
        Text(date, style = mono.small, color = palette.textMuted, maxLines = 1)
        Text(time, style = mono.small, color = palette.textMuted, maxLines = 1)
    }
}

@Composable
private fun TitleCell(title: String, subtitle: String, modifier: Modifier = Modifier) {
    val palette = blueprint
    Column(
        modifier = modifier.clearAndSetSemantics {},
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            subtitle,
            style = mono.small,
            color = palette.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LengthCell(length: String, modifier: Modifier = Modifier) {
    Text(
        length,
        modifier = modifier.clearAndSetSemantics {},
        style = mono.small,
        color = blueprint.text,
        textAlign = TextAlign.End,
        maxLines = 1,
    )
}

@Composable
private fun StatusCell(status: LedgerStatus, modifier: Modifier = Modifier) {
    Box(modifier.clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        StatusBadge(status)
    }
}
