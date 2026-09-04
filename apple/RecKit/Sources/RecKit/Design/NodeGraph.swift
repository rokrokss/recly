import SwiftUI

/// docs/09 화면 원칙 3: the node column's rhythm — leg, `+`, leg, then the end terminal.
private enum Rhythm {
    static let leg: CGFloat = 14
    static let plus: CGFloat = 18
    static let terminal: CGFloat = 10
}

/// The workflow editor's canvas: square nodes in one line, joined by straight connectors, with a
/// `+` on every connector — the closing one included, so a step can be appended after the last —
/// and a filled square at the end. [insert] is given the position the new node would take.
///
/// Vertical on the phone and horizontal in the Mac's window (docs/09 화면 원칙 3), which is the whole
/// of the difference between them: the placement is [nodeGraphLayout]'s either way.
public struct NodeGraph<Node: View>: View {
    @Environment(\.blueprint) private var blueprint
    private let axis: Axis
    private let count: Int
    private let insertLabel: String
    private let insert: (Int) -> Void
    private let node: (Int) -> Node

    public init(
        axis: Axis,
        count: Int,
        insertLabel: String,
        insert: @escaping (Int) -> Void,
        @ViewBuilder node: @escaping (Int) -> Node
    ) {
        self.axis = axis
        self.count = count
        self.insertLabel = insertLabel
        self.insert = insert
        self.node = node
    }

    public var body: some View {
        NodeGraphArrangement(
            axis: axis,
            count: count,
            lineWidth: blueprint.line
        ) {
            // The order the arrangement reads: nodes, then one `+` per connector, then two legs per
            // connector, then the terminal square.
            ForEach(0..<count, id: \.self) { index in node(index) }
            ForEach(0..<count, id: \.self) { index in
                PlusButton(label: insertLabel) { insert(index) }
                    // The legs and the terminal come after the buttons in this container, so they
                    // are drawn over them; the `+`'s 44pt target is wider than its run's 18pt
                    // glyph and lies under both legs. Above, and the taps are the button's.
                    .zIndex(1)
            }
            // Decoration, and nothing a tap can be meant for: the target it overlaps belongs to
            // the `+` it is drawn around (see `nodeGraphLayoutTests` — the target spills onto both
            // legs by design, because the glyph is smaller than a touch).
            ForEach(0..<(2 * count), id: \.self) { _ in
                Rectangle()
                    .fill(blueprint.palette.text)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
            Rectangle()
                .fill(blueprint.palette.text)
                .clipShape(RoundedRectangle(cornerRadius: Radius.badge))
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
    }
}

/// The glyph is 18pt, as the mockup draws it; what takes the tap is [minTouch] around it.
private struct PlusButton: View {
    @Environment(\.blueprint) private var blueprint
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(verbatim: "+")
                .font(blueprint.fonts.monoSmall)
                .foregroundStyle(blueprint.palette.text)
                .frame(width: Rhythm.plus, height: Rhythm.plus)
                .background(blueprint.palette.surface, in: RoundedRectangle(cornerRadius: Radius.badge))
                .overlay {
                    RoundedRectangle(cornerRadius: Radius.badge)
                        .strokeBorder(blueprint.palette.text, lineWidth: blueprint.line)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(verbatim: label))
    }
}

/// The placement, from [nodeGraphLayout]. A `Layout` rather than a stack of spacers because the
/// pure function is then what actually positions the drawing — the same arithmetic the unit test
/// checks, rather than a second copy of it.
private struct NodeGraphArrangement: Layout {
    let axis: Axis
    let count: Int
    let lineWidth: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let sizes = nodeSizes(subviews)
        let graph = layout(of: sizes)
        let cross = sizes.map(cross).max() ?? 0
        return axis == .vertical
            ? CGSize(width: max(cross, proposal.width ?? cross), height: graph.extent)
            : CGSize(width: graph.extent, height: max(cross, proposal.height ?? cross))
    }

    func placeSubviews(
        in bounds: CGRect,
        proposal: ProposedViewSize,
        subviews: Subviews,
        cache: inout ()
    ) {
        let sizes = nodeSizes(subviews)
        let graph = layout(of: sizes)
        let middle = axis == .vertical ? bounds.midX : bounds.midY

        for (index, box) in graph.nodes.enumerated() {
            place(subviews[index], at: box.start, middle: middle, size: sizes[index], bounds: bounds)
        }

        var legIndex = 2 * count
        for (index, run) in graph.connectors.enumerated() {
            let touch = CGSize(width: run.touchSize, height: run.touchSize)
            place(subviews[count + index], at: run.touchStart, middle: middle, size: touch, bounds: bounds)
            for start in [run.start, run.plusEnd] {
                place(
                    subviews[legIndex],
                    at: start,
                    middle: middle,
                    size: along(main: Rhythm.leg, cross: lineWidth),
                    bounds: bounds
                )
                legIndex += 1
            }
        }
        // The content is `count` nodes, then `count` pluses, then `2 * count` legs, then the
        // terminal — so the terminal is the last of `4 * count + 1` subviews.
        place(
            subviews[4 * count],
            at: graph.terminalStart,
            middle: middle,
            size: CGSize(width: Rhythm.terminal, height: Rhythm.terminal),
            bounds: bounds
        )
    }

    private func layout(of sizes: [CGSize]) -> GraphLayout {
        nodeGraphLayout(
            nodeExtents: sizes.map(main),
            leg: Rhythm.leg,
            plus: Rhythm.plus,
            terminal: Rhythm.terminal,
            touch: minTouch
        )
    }

    private func nodeSizes(_ subviews: Subviews) -> [CGSize] {
        (0..<count).map { subviews[$0].sizeThatFits(.unspecified) }
    }

    private func main(_ size: CGSize) -> CGFloat { axis == .vertical ? size.height : size.width }

    private func cross(_ size: CGSize) -> CGFloat { axis == .vertical ? size.width : size.height }

    private func along(main: CGFloat, cross: CGFloat) -> CGSize {
        axis == .vertical
            ? CGSize(width: cross, height: main)
            : CGSize(width: main, height: cross)
    }

    private func place(
        _ subview: LayoutSubview,
        at start: CGFloat,
        middle: CGFloat,
        size: CGSize,
        bounds: CGRect
    ) {
        let centre = start + main(size) / 2
        let point = axis == .vertical
            ? CGPoint(x: middle, y: bounds.minY + centre)
            : CGPoint(x: bounds.minX + centre, y: middle)
        subview.place(at: point, anchor: .center, proposal: ProposedViewSize(size))
    }
}

/// One node of the graph: a kicker, a title and a monospace detail line, in a square box whose
/// border is the accent when it is the node the inspector is showing.
public struct GraphNode: View {
    @Environment(\.blueprint) private var blueprint
    private let kicker: String
    private let title: String
    private let detail: String
    private let selected: Bool
    private let action: () -> Void

    public init(
        kicker: String,
        title: String,
        detail: String,
        selected: Bool,
        action: @escaping () -> Void
    ) {
        self.kicker = kicker
        self.title = title
        self.detail = detail
        self.selected = selected
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: Space.xs) {
                HStack(spacing: Space.xs) {
                    // docs/09 "모든 상태는 색 + 텍스트": the accent border says which node the
                    // inspector is showing, and this says it again in letters — the only one of the
                    // two a monochrome or colour-blind reader gets. Decoration to VoiceOver, which
                    // hears the same fact as a trait.
                    if selected {
                        Text(verbatim: BlueprintChip.selectionMark)
                            .font(blueprint.fonts.label)
                            .foregroundStyle(blueprint.palette.accent)
                            .accessibilityHidden(true)
                    }
                    Text(verbatim: kicker)
                        .font(blueprint.fonts.label)
                        .tracking(0.6)
                        .foregroundStyle(blueprint.palette.textMuted)
                        .lineLimit(1)
                }
                Text(verbatim: title)
                    .font(blueprint.fonts.rowTitle)
                    .foregroundStyle(blueprint.palette.text)
                    .lineLimit(1)
                if !detail.isEmpty {
                    Text(verbatim: detail)
                        .font(blueprint.fonts.monoSmall)
                        .foregroundStyle(blueprint.palette.textMuted)
                        // docs/07 rule 4: the detail is data — a URL, a provider name, a folder
                        // template — and a code with its end replaced by "…" is not the code. Two
                        // lines at the design's own size, more as the user's size grows.
                        .lineLimit(detailLines)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .frame(width: Self.width(scale: blueprint.fonts.scale), alignment: .leading)
            .background(blueprint.palette.surface, in: RoundedRectangle(cornerRadius: Radius.node))
            .overlay {
                RoundedRectangle(cornerRadius: Radius.node)
                    .strokeBorder(
                        selected ? blueprint.palette.accent : blueprint.palette.text,
                        lineWidth: selected ? blueprint.palette.selectedLine : blueprint.line
                    )
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }

    /// How many lines the detail may take. It grows with the type size for the same reason [width]
    /// does: the node holds the same number of *characters* at every size, so the thing that has to
    /// give is the number of lines, not the letters.
    private var detailLines: Int {
        Int((2 * blueprint.fonts.scale).rounded(.up))
    }

    /// docs/09 화면 원칙 3 draws the node 232pt wide, which is a width measured for the design's own
    /// 12/14pt type. Dynamic Type makes those letters the user's, so the box follows them — capped,
    /// because a node wider than the phone is not a node.
    static func width(scale: CGFloat) -> CGFloat {
        min(232 * max(scale, 1), 320)
    }
}
