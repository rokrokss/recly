import SwiftUI

/// One node of the recording dashboard: a label and the value under it (docs/09 화면 원칙 1).
public struct NodeSpec: Identifiable, Equatable, Sendable {
    public let id: String
    public let label: String
    public let value: String
    /// Nil for the body colour; a state that means something (`REC`) says so in its own.
    public let valueColor: Color?
    /// A node that is not doing anything takes the quiet border.
    public let active: Bool
    /// Work is running behind the value, and the node turns a loader beside it to say so.
    public let busy: Bool

    public init(
        label: String,
        value: String,
        valueColor: Color? = nil,
        active: Bool = true,
        busy: Bool = false
    ) {
        id = label
        self.label = label
        self.value = value
        self.valueColor = valueColor
        self.active = active
        self.busy = busy
    }
}

/// A square node with a label and a monospace value.
public struct StateNode: View {
    @Environment(\.blueprint) private var blueprint
    private let spec: NodeSpec

    public init(_ spec: NodeSpec) {
        self.spec = spec
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: Space.xs) {
            Text(verbatim: spec.label)
                .font(blueprint.fonts.label)
                .tracking(0.6)
                .foregroundStyle(blueprint.palette.textMuted)
            HStack(spacing: Space.xs) {
                if spec.busy {
                    BlueprintLoader(color: spec.valueColor ?? blueprint.palette.text)
                }
                Text(verbatim: spec.value)
                    .font(blueprint.fonts.monoBodySmall)
                    .foregroundStyle(spec.valueColor ?? blueprint.palette.text)
            }
        }
        // A node is a word and a value; at the largest type sizes it takes a second line rather
        // than truncating to "Dev…".
        .lineLimit(2)
        .minimumScaleFactor(0.7)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(blueprint.palette.surface, in: RoundedRectangle(cornerRadius: Radius.node))
        .overlay {
            RoundedRectangle(cornerRadius: Radius.node)
                .strokeBorder(
                    spec.active ? blueprint.palette.text : blueprint.palette.grid,
                    lineWidth: blueprint.line
                )
        }
        .accessibilityElement(children: .combine)
    }
}

/// The dashboard nodes, joined edge to edge by straight 20pt connectors.
///
/// docs/09 "접근성" · 유동 타이포: three nodes across a phone is a layout for ordinary type sizes. At
/// an accessibility size the same graph runs downwards instead — same nodes, same straight
/// connectors, one turn of ninety degrees.
public struct StateNodeRow: View {
    @Environment(\.blueprint) private var blueprint
    @Environment(\.dynamicTypeSize) private var typeSize
    private let nodes: [NodeSpec]

    public init(_ nodes: [NodeSpec]) {
        self.nodes = nodes
    }

    public var body: some View {
        let layout = typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(spacing: 0))
            : AnyLayout(HStackLayout(spacing: 0))
        layout {
            ForEach(Array(nodes.enumerated()), id: \.element.id) { index, spec in
                StateNode(spec)
                if index != nodes.count - 1 {
                    Rectangle()
                        .fill(blueprint.palette.text)
                        .frame(
                            width: typeSize.isAccessibilitySize ? blueprint.line : 20,
                            height: typeSize.isAccessibilitySize ? 20 : blueprint.line
                        )
                        .accessibilityHidden(true)
                }
            }
        }
    }
}

/// docs/09 "타이포": the timer is the one piece of data big enough to be the screen.
public struct MonoTimer: View {
    @Environment(\.blueprint) private var blueprint
    private let text: String
    private let color: Color?

    public init(_ text: String, color: Color? = nil) {
        self.text = text
        self.color = color
    }

    public var body: some View {
        Text(verbatim: text)
            .font(blueprint.fonts.monoTimer)
            .foregroundStyle(color ?? blueprint.palette.text)
            .lineLimit(1)
            .minimumScaleFactor(0.5)
    }
}

/// A 1pt rule (2pt in high contrast) — the only divider this design has.
public struct HairLine: View {
    @Environment(\.blueprint) private var blueprint

    public init() {}

    public var body: some View {
        Rectangle()
            .fill(blueprint.palette.grid)
            .frame(height: blueprint.line)
            .accessibilityHidden(true)
    }
}
