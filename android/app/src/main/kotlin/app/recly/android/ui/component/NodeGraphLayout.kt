package app.recly.android.ui.component

/** Where one node sits in the column. */
data class NodeBox(val top: Int, val height: Int) {
    val bottom: Int get() = top + height
}

/**
 * The run below a node: a straight line with a square `+` on it (docs/09 화면 원칙 3 — "단계 추가는
 * 커넥터 위 +"). The two legs are `top..plusTop` and `plusBottom..bottom`.
 *
 * The `+` is drawn [plusSize] wide but is *touched* over [touchSize], centred on the same point:
 * the glyph stays small enough for the mockup's rhythm while the target stays at least 44dp.
 */
data class ConnectorRun(
    val top: Int,
    val height: Int,
    val plusTop: Int,
    val plusSize: Int,
    val touchTop: Int,
    val touchSize: Int,
) {
    val bottom: Int get() = top + height
    val plusBottom: Int get() = plusTop + plusSize
}

data class GraphLayout(
    val nodes: List<NodeBox>,
    /**
     * One per node: `connectors[i]` inserts a step at position `i`, so the last one — the run that
     * closes the graph — appends. Without it a graph with a single node offers no `+` at all.
     */
    val connectors: List<ConnectorRun>,
    /** Top of the filled square that ends the column. */
    val terminalTop: Int,
    val height: Int,
)

/**
 * docs/09 화면 원칙 3: square nodes in one column, straight connectors, and an end terminal. Pure —
 * the composable measures its children and hands the heights here, so the arithmetic can be checked
 * without a window and the drawing has one source for both the nodes and the lines.
 *
 * Everything is in whatever unit the caller uses (pixels, from the measure pass).
 */
fun nodeGraphLayout(
    nodeHeights: List<Int>,
    leg: Int,
    plus: Int,
    terminal: Int,
    touch: Int,
): GraphLayout {
    if (nodeHeights.isEmpty()) return GraphLayout(emptyList(), emptyList(), 0, 0)

    val run = leg + plus + leg
    // The target never spills onto the nodes above and below: it would steal their taps.
    val touchSize = touch.coerceAtMost(run)
    val nodes = mutableListOf<NodeBox>()
    val connectors = mutableListOf<ConnectorRun>()
    var y = 0
    nodeHeights.forEach { height ->
        nodes += NodeBox(top = y, height = height)
        y += height
        connectors += ConnectorRun(
            top = y,
            height = run,
            plusTop = y + leg,
            plusSize = plus,
            touchTop = y + (run - touchSize) / 2,
            touchSize = touchSize,
        )
        y += run
    }
    return GraphLayout(nodes, connectors, terminalTop = y, height = y + terminal)
}
