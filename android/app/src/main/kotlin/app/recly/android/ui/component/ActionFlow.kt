package app.recly.android.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import app.recly.android.ui.theme.Space

/**
 * docs/09 화면 원칙 2: an expanded ledger row's buttons, across the row's whole width and onto more
 * lines when they do not fit, with [trailing] — the row's Delete — at the end of the last line. It
 * used to stand in a column of its own under the status badge, and whatever it took from the row
 * the other buttons lost: at 360dp a Retry beside Open in Drive already had to go to a line of its
 * own (2026-09-29).
 */
@Composable
fun ActionFlow(
    modifier: Modifier = Modifier,
    spacing: Dp = Space.s,
    trailing: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit,
) {
    Layout(content = { actions(); trailing?.invoke() }, modifier = modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val width = constraints.maxWidth
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val spots = actionFlow(placeables.map { it.width }, width, gap, trailingLast = trailing != null)
        val lines = (spots.maxOfOrNull { it.line } ?: -1) + 1
        val heights = IntArray(lines)
        spots.forEachIndexed { index, spot -> heights[spot.line] = maxOf(heights[spot.line], placeables[index].height) }
        val tops = IntArray(lines)
        for (line in 1 until lines) tops[line] = tops[line - 1] + heights[line - 1] + gap
        val height = if (lines == 0) 0 else tops[lines - 1] + heights[lines - 1]
        layout(width, height) {
            spots.forEachIndexed { index, spot -> placeables[index].placeRelative(spot.x, tops[spot.line]) }
        }
    }
}

/** Where one button of an [ActionFlow] goes: its start edge and which line it is on. */
internal data class Spot(val x: Int, val line: Int)

/**
 * The greedy wrap of [widths] into lines of [width]; with [trailingLast] the last one is instead put
 * at the end of the last line when it fits there, and at the end of a line of its own when it
 * does not.
 */
internal fun actionFlow(widths: List<Int>, width: Int, gap: Int, trailingLast: Boolean): List<Spot> {
    val spots = mutableListOf<Spot>()
    var line = 0
    var used = 0
    val flowing = if (trailingLast) widths.dropLast(1) else widths
    flowing.forEach { w ->
        if (used > 0 && used + gap + w > width) {
            line++
            used = 0
        }
        spots += Spot(if (used == 0) 0 else used + gap, line)
        used = if (used == 0) w else used + gap + w
    }
    if (trailingLast && widths.isNotEmpty()) {
        val w = widths.last()
        if (used > 0 && used + gap + w > width) line++
        spots += Spot((width - w).coerceAtLeast(0), line)
    }
    return spots
}
