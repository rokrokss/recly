import CoreGraphics

/// Where one node sits along the graph's axis.
public struct NodeBox: Equatable, Sendable {
    public let start: CGFloat
    public let extent: CGFloat

    public var end: CGFloat { start + extent }
}

/// The run after a node: a straight line with a square `+` on it (docs/09 화면 원칙 3 — "단계 추가는
/// 커넥터 위 +"). The two legs are `start..<plusStart` and `plusEnd..<end`.
///
/// The `+` is drawn [plusSize] wide but is *touched* over [touchSize], centred on the same point:
/// the glyph stays small enough for the mockup's rhythm while the target stays at least 44pt.
public struct ConnectorRun: Equatable, Sendable {
    public let start: CGFloat
    public let extent: CGFloat
    public let plusStart: CGFloat
    public let plusSize: CGFloat
    public let touchStart: CGFloat
    public let touchSize: CGFloat

    public var end: CGFloat { start + extent }
    public var plusEnd: CGFloat { plusStart + plusSize }
}

public struct GraphLayout: Equatable, Sendable {
    public let nodes: [NodeBox]
    /// One per node: `connectors[i]` inserts a step at position `i`, so the last one — the run that
    /// closes the graph — appends. Without it a graph with a single node offers no `+` at all.
    public let connectors: [ConnectorRun]
    /// Where the filled square that ends the graph begins.
    public let terminalStart: CGFloat
    /// The whole run, along the axis.
    public let extent: CGFloat
}

/// docs/09 화면 원칙 3: square nodes in one line, straight connectors, and an end terminal. Pure —
/// the SwiftUI `Layout` measures its subviews and hands the extents here, so the arithmetic can be
/// checked without a window and the lines and the nodes are positioned by one source.
///
/// Everything is in points, along whichever axis the caller is drawing: a vertical column on the
/// phone, a horizontal row in the Mac's window.
public func nodeGraphLayout(
    nodeExtents: [CGFloat],
    leg: CGFloat,
    plus: CGFloat,
    terminal: CGFloat,
    touch: CGFloat
) -> GraphLayout {
    guard !nodeExtents.isEmpty else {
        return GraphLayout(nodes: [], connectors: [], terminalStart: 0, extent: 0)
    }

    let run = leg + plus + leg
    // The target never spills onto the nodes on either side: it would steal their taps.
    let touchSize = min(touch, run)
    var nodes: [NodeBox] = []
    var connectors: [ConnectorRun] = []
    var at: CGFloat = 0
    for extent in nodeExtents {
        nodes.append(NodeBox(start: at, extent: extent))
        at += extent
        connectors.append(
            ConnectorRun(
                start: at,
                extent: run,
                plusStart: at + leg,
                plusSize: plus,
                touchStart: at + (run - touchSize) / 2,
                touchSize: touchSize
            )
        )
        at += run
    }
    return GraphLayout(
        nodes: nodes,
        connectors: connectors,
        terminalStart: at,
        extent: at + terminal
    )
}
