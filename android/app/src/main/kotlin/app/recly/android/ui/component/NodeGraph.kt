package app.recly.android.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.recly.android.ui.theme.MinTouch
import app.recly.android.ui.theme.Radius
import app.recly.android.ui.theme.Space
import app.recly.android.ui.theme.blueprint
import app.recly.android.ui.theme.mono

/** docs/09 화면 원칙 3: the node column's vertical rhythm — leg, `+`, leg, then the end terminal. */
private val LEG = 14.dp
private val PLUS = 18.dp
private val TERMINAL = 10.dp

private const val TERMINAL_ID = "terminal"
private const val PLUS_ID = "plus"
private const val LEG_ID = "leg"

/**
 * The workflow editor's canvas: square nodes in one column, joined by straight connectors, with a
 * `+` on every connector — the closing one included, so a node can be appended after the last — and
 * a filled square at the end. [onInsert] is given the position the new node would take.
 *
 * The placement is [nodeGraphLayout]'s — this measures the nodes (their height is whatever their
 * text and the user's font scale make it) and hands the heights over, so the lines and the nodes
 * are positioned by the same arithmetic the unit test checks.
 */
@Composable
fun NodeGraph(
    count: Int,
    onInsert: (Int) -> Unit,
    insertLabel: String,
    modifier: Modifier = Modifier,
    node: @Composable (Int) -> Unit,
) {
    val palette = blueprint
    val density = LocalDensity.current
    val leg = with(density) { LEG.roundToPx() }
    val plus = with(density) { PLUS.roundToPx() }
    val terminal = with(density) { TERMINAL.roundToPx() }
    val touch = with(density) { MinTouch.roundToPx() }
    val lineWidth = with(density) { palette.line.roundToPx() }.coerceAtLeast(1)

    Layout(
        modifier = modifier,
        content = {
            repeat(count) { index -> Box(Modifier.layoutId(index)) { node(index) } }
            repeat(count) { index ->
                Box(Modifier.layoutId(PLUS_ID)) { PlusButton(insertLabel) { onInsert(index) } }
            }
            Box(Modifier.layoutId(TERMINAL_ID).background(palette.text, RoundedCornerShape(Radius.badge)))
            // The straight lines: two legs per connector, above and below its `+`.
            repeat(2 * count) {
                Box(Modifier.layoutId(LEG_ID).background(palette.text))
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val nodes = (0 until count).map { index ->
            measurables.first { it.layoutId == index }.measure(loose)
        }
        val graph = nodeGraphLayout(nodes.map { it.height }, leg, plus, terminal, touch)
        val width = constraints.maxWidth

        val pluses = graph.connectors.map { it.touchSize }
            .zip(measurables.filter { it.layoutId == PLUS_ID })
            .map { (size, measurable) -> measurable.measure(Constraints.fixed(size, size)) }
        val terminalSquare = measurables.first { it.layoutId == TERMINAL_ID }
            .measure(Constraints.fixed(terminal, terminal))
        val legs = measurables.filter { it.layoutId == LEG_ID }
            .map { it.measure(Constraints.fixed(lineWidth, leg)) }

        layout(width, graph.height) {
            val legX = (width - lineWidth) / 2
            var legIndex = 0
            nodes.forEachIndexed { index, placeable ->
                placeable.place((width - placeable.width) / 2, graph.nodes[index].top)
            }
            graph.connectors.forEachIndexed { index, run ->
                legs[legIndex++].place(legX, run.top)
                pluses[index].place((width - run.touchSize) / 2, run.touchTop)
                legs[legIndex++].place(legX, run.plusBottom)
            }
            terminalSquare.place((width - terminal) / 2, graph.terminalTop)
        }
    }
}

/** The glyph is [PLUS] wide, as the mockup draws it; what takes the tap is [MinTouch] around it. */
@Composable
private fun PlusButton(label: String, onClick: () -> Unit) {
    val palette = blueprint
    Box(
        modifier = Modifier
            .size(MinTouch)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(PLUS)
                .border(palette.line, palette.text, RoundedCornerShape(Radius.badge))
                .background(palette.surface, RoundedCornerShape(Radius.badge)),
            contentAlignment = Alignment.Center,
        ) {
            Text("+", style = mono.small, color = palette.text)
        }
    }
}

/**
 * One node of the graph: a kicker, a title and a monospace detail line, in a square box whose
 * border is the accent when it is the node the inspector below is showing.
 */
@Composable
fun GraphNode(
    kicker: String,
    title: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = blueprint
    Column(
        modifier = modifier
            .width(232.dp)
            .border(
                // A selection is always one step heavier than the hairline around it.
                width = if (selected) palette.selectedLine else palette.line,
                color = if (selected) palette.accent else palette.text,
                shape = RoundedCornerShape(Radius.node),
            )
            .background(palette.surface, RoundedCornerShape(Radius.node))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(kicker, style = MaterialTheme.typography.labelSmall, color = palette.textMuted, maxLines = 1)
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (detail.isNotEmpty()) {
            Text(
                detail,
                modifier = Modifier.fillMaxWidth(),
                style = mono.small,
                color = palette.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
