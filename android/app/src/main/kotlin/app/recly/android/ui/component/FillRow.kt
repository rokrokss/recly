package app.recly.android.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import app.recly.android.ui.theme.Space

/**
 * A row of choice chips that fills its line — the theme and the transcription choices of the
 * settings, whose chips otherwise sat at the start of an empty line while every other control on
 * the screen ends at its right edge.
 *
 * The chips are the same width when they all fit that way; when one label is too long for an even
 * split (`System default`), each keeps its own width plus an equal share of what is left, so no chip
 * is ever narrower than it would be on its own. A line that does not hold them all wraps, and
 * every line fills.
 */
@Composable
fun FillRow(modifier: Modifier = Modifier, spacing: Dp = Space.s, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val natural = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else natural.sum() + gap * (natural.size - 1).coerceAtLeast(0)
        val lines = fillLines(natural, width, gap)
        var index = 0
        val placed = lines.map { line ->
            // One height for the line, so a chip whose label wrapped does not stand taller than its neighbours.
            val height = line.indices.maxOf { measurables[index + it].minIntrinsicHeight(line[it]) }
            line.map { target -> measurables[index++].measure(Constraints.fixed(target, height)) }
        }
        val total = placed.sumOf { line -> line.maxOf { it.height } } + gap * (placed.size - 1).coerceAtLeast(0)
        layout(width, total) {
            var y = 0
            placed.forEach { line ->
                var x = 0
                line.forEach { chip ->
                    chip.placeRelative(x, y)
                    x += chip.width + gap
                }
                y += line.maxOf { it.height } + gap
            }
        }
    }
}

/**
 * The widths [FillRow] gives its chips, line by line: a greedy wrap on their [natural] widths, then
 * each line filled to [width]. Pixels the division leaves over go to the last chip, so every line
 * ends on the edge.
 */
internal fun fillLines(natural: List<Int>, width: Int, gap: Int): List<List<Int>> {
    val lines = mutableListOf<MutableList<Int>>()
    var used = 0
    natural.map { it.coerceAtMost(width) }.forEach { chip ->
        val line = lines.lastOrNull()
        if (line == null || used + gap + chip > width) {
            lines += mutableListOf(chip)
            used = chip
        } else {
            line += chip
            used += gap + chip
        }
    }
    return lines.map { line ->
        val room = width - gap * (line.size - 1)
        val widths = if (line.max() * line.size <= room) {
            List(line.size) { room / line.size }
        } else {
            val share = (room - line.sum()) / line.size
            line.map { it + share }
        }
        widths.dropLast(1) + (room - widths.dropLast(1).sum())
    }
}
