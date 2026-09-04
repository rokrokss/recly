package app.recly.windows.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.recly.windows.ui.theme.MinTouch
import app.recly.windows.ui.theme.Radius
import app.recly.windows.ui.theme.Space
import app.recly.windows.ui.theme.blueprint
import app.recly.windows.ui.theme.mono

/** docs/09 화면 원칙 3: the node row's horizontal rhythm — leg, `+`, leg, then the end terminal. */
private val LEG = 16.dp
private val PLUS = 18.dp
private val TERMINAL = 10.dp

private const val ID_TERMINAL = "terminal"
private const val PLUS_ID = "plus"
private const val LEG_ID = "leg"

/**
 * The workflow editor's canvas: square nodes in one row, joined by straight connectors, with a `+`
 * on every connector — the closing one included, so a step can be appended after the last — and a
 * filled square at the end. [onInsert] is given the position the new node would take.
 *
 * The placement is [nodeGraphLayout]'s — this measures the nodes and hands the widths over, so the
 * lines and the nodes are positioned by the same arithmetic the unit test checks. The row is as
 * tall as its tallest node and the connectors run through the middle of it.
 */
@Composable
fun NodeGraph(
    count: Int,
    /**
     * The position a new node would take, and where on this canvas the `+` that was clicked is —
     * a menu is an answer to a click, so it opens under the thing that was clicked.
     */
    onInsert: (Int, IntOffset) -> Unit,
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
    val lineHeight = with(density) { palette.line.roundToPx() }.coerceAtLeast(1)

    Layout(
        modifier = modifier,
        content = {
            repeat(count) { index -> Box(Modifier.layoutId(index)) { node(index) } }
            repeat(count) { index ->
                Box(Modifier.layoutId(PLUS_ID)) { PlusButton(insertLabel, index, onInsert) }
            }
            Box(Modifier.layoutId(ID_TERMINAL).background(palette.text, RoundedCornerShape(Radius.badge)))
            // The straight lines: two legs per connector, left and right of its `+`.
            repeat(2 * count) {
                Box(Modifier.layoutId(LEG_ID).background(palette.text))
            }
        },
    ) { measurables, constraints ->
        val loose = Constraints()
        val nodes = (0 until count).map { index ->
            measurables.first { it.layoutId == index }.measure(loose)
        }
        val graph = nodeGraphLayout(nodes.map { it.width }, leg, plus, terminal, touch)
        val height = nodes.maxOfOrNull { it.height } ?: 0

        val pluses = graph.connectors.map { it.touchSize }
            .zip(measurables.filter { it.layoutId == PLUS_ID })
            .map { (size, measurable) -> measurable.measure(Constraints.fixed(size, size)) }
        val terminalSquare = measurables.first { it.layoutId == ID_TERMINAL }
            .measure(Constraints.fixed(terminal, terminal))
        val legs = measurables.filter { it.layoutId == LEG_ID }
            .map { it.measure(Constraints.fixed(leg, lineHeight)) }

        layout(maxOf(graph.width, constraints.minWidth), maxOf(height, constraints.minHeight)) {
            val middle = height / 2
            var legIndex = 0
            nodes.forEachIndexed { index, placeable ->
                placeable.place(graph.nodes[index].left, (height - placeable.height) / 2)
            }
            graph.connectors.forEachIndexed { index, run ->
                legs[legIndex++].place(run.left, middle - lineHeight / 2)
                pluses[index].place(run.touchLeft, middle - run.touchSize / 2)
                legs[legIndex++].place(run.plusRight, middle - lineHeight / 2)
            }
            terminalSquare.place(graph.terminalLeft, middle - terminal / 2)
        }
    }
}

/** The glyph is [PLUS] wide, as the mockup draws it; what takes the click is [MinTouch] around it. */
@Composable
private fun PlusButton(label: String, index: Int, onInsert: (Int, IntOffset) -> Unit) {
    val palette = blueprint
    // Where this `+` ended up on the canvas, so the menu it opens can be put under it. The layout
    // decides that (`nodeGraphLayout`), and this is the only place the answer is available.
    var at by remember { mutableStateOf(IntOffset.Zero) }
    Box(
        modifier = Modifier
            .size(MinTouch)
            .onGloballyPositioned { coordinates ->
                // The glyph is the only child of the box the layout places, so that box's own
                // position is where this `+` is on the canvas — and the menu opens just under it.
                val box = coordinates.parentLayoutCoordinates ?: coordinates
                val position = box.positionInParent()
                at = IntOffset(position.x.toInt(), position.y.toInt() + box.size.height)
            }
            .clickable(onClickLabel = label, role = Role.Button) { onInsert(index, at) },
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
 * One node of the graph: a kicker, a title and a monospace detail line, in a square box whose border
 * is the accent when it is the node the inspector is showing.
 */
@Composable
fun GraphNode(
    kicker: String,
    title: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** An identifier that belongs to the node — a step id — set as data beside the kicker. */
    code: String? = null,
) {
    val palette = blueprint
    Column(
        modifier = modifier
            .width(196.dp)
            .border(
                // docs/09 "고대비 모드" makes every line 2dp, so a flat 2dp here stopped saying
                // which node the inspector was showing.
                width = if (selected) palette.selectedLine else palette.line,
                color = if (selected) palette.accent else palette.text,
                shape = RoundedCornerShape(Radius.node),
            )
            .background(palette.surface, RoundedCornerShape(Radius.node))
            .clickable(role = Role.Button, onClick = onClick)
            // docs/09 "접근성": the accent border is the only thing that says the inspector is
            // showing this node, and a border reads as nothing at all. Same node as the click.
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        // docs/09 "모든 상태는 색 + 텍스트": the accent border is a colour and a weight, and neither
        // is anything to a monochrome reader — the mark is, and it rides in the kicker's own line.
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (selected) "$SELECTION_MARK $kicker" else kicker,
                style = MaterialTheme.typography.labelSmall,
                color = palette.textMuted,
                maxLines = 1,
            )
            code?.let {
                Text(
                    it,
                    modifier = Modifier.weight(1f, fill = false),
                    style = mono.small,
                    color = palette.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
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
