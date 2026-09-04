package app.recly.windows.ui.component

/** Where one node sits along the row. */
data class NodeBox(val left: Int, val width: Int) {
    val right: Int get() = left + width
}

/**
 * The run after a node: a straight line with a square `+` on it (docs/09 화면 원칙 3 — "단계 추가는
 * 커넥터 위 +"). The two legs are `left..plusLeft` and `plusRight..right`.
 *
 * The `+` is drawn [plusSize] wide but is *clicked* over [touchSize], centred on the same point: the
 * glyph stays small enough for the mockup's rhythm while the target stays at least 44dp.
 */
data class ConnectorRun(
    val left: Int,
    val width: Int,
    val plusLeft: Int,
    val plusSize: Int,
    val touchLeft: Int,
    val touchSize: Int,
) {
    val right: Int get() = left + width
    val plusRight: Int get() = plusLeft + plusSize
}

data class GraphLayout(
    val nodes: List<NodeBox>,
    /**
     * One per node: `connectors[i]` inserts a step at position `i`, so the last one — the run that
     * closes the graph — appends. Without it a graph with a single node offers no `+` at all.
     */
    val connectors: List<ConnectorRun>,
    /** Left edge of the filled square that ends the row. */
    val terminalLeft: Int,
    val width: Int,
)

/**
 * docs/09 화면 원칙 3: square nodes in one row, straight connectors, and an end terminal. **The
 * desktop's graph runs left to right** — the phone stacks its nodes because a phone is tall and a
 * window is wide — but the arithmetic is the phone's, one axis over.
 *
 * Pure: the composable measures its children and hands the widths here, so this can be checked
 * without a window and the drawing has one source for both the nodes and the lines. Everything is
 * in whatever unit the caller uses (pixels, from the measure pass).
 */
fun nodeGraphLayout(
    nodeWidths: List<Int>,
    leg: Int,
    plus: Int,
    terminal: Int,
    touch: Int,
): GraphLayout {
    if (nodeWidths.isEmpty()) return GraphLayout(emptyList(), emptyList(), 0, 0)

    val run = leg + plus + leg
    // The target never spills onto the nodes on either side: it would steal their clicks.
    val touchSize = touch.coerceAtMost(run)
    val nodes = mutableListOf<NodeBox>()
    val connectors = mutableListOf<ConnectorRun>()
    var x = 0
    nodeWidths.forEach { width ->
        nodes += NodeBox(left = x, width = width)
        x += width
        connectors += ConnectorRun(
            left = x,
            width = run,
            plusLeft = x + leg,
            plusSize = plus,
            touchLeft = x + (run - touchSize) / 2,
            touchSize = touchSize,
        )
        x += run
    }
    return GraphLayout(nodes, connectors, terminalLeft = x, width = x + terminal)
}
