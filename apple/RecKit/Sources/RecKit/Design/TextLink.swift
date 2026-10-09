import SwiftUI

/// docs/09 (2026-10-09): a control whose only effect is to open a web page outside the app — the privacy
/// policy, a set-up guide, ChatGPT's usage page — is a link, not a button: the accent, a dotted underline,
/// and no box. Buttons stay for what happens inside the app; a dialog keeps [BlueprintDialogLink].
///
/// The target is [minTouch] tall all the same, made of room around the words that the layout does not
/// count, so a link on a row's second line does not make the row taller.
public struct TextLink: View {
    @Environment(\.blueprint) private var blueprint
    private let label: String
    private let small: Bool
    private let action: () -> Void

    /// - Parameter small: the secondary 12 size, for a link that sits in a row's subtitle.
    public init(_ label: String, small: Bool = false, action: @escaping () -> Void) {
        self.label = label
        self.small = small
        self.action = action
    }

    public var body: some View {
        let size = small ? TypeSize.small : TypeSize.bodySmall
        // What the target needs beyond the line's own height, above and below.
        let reach = max(0, (minTouch - size * blueprint.fonts.scale * Self.lineHeight) / 2)
        Button(action: action) {
            Text(verbatim: label)
                .font(blueprint.fonts.sans(size))
                .foregroundStyle(blueprint.palette.accent)
                // Dotted rather than solid, so it reads as a way out of the app and not as emphasis; the
                // line is what a colour-blind reader finds the link by (docs/09 "Every state is color + text").
                .modifier(DottedUnderline(color: blueprint.palette.accent.opacity(0.7), dot: blueprint.line))
                .fixedSize(horizontal: false, vertical: true)
                .padding(.vertical, reach)
                .frame(minWidth: minTouch, alignment: .leading)
                .contentShape(Rectangle())
                .padding(.vertical, -reach)
        }
        .buttonStyle(Pressed())
        .accessibilityRemoveTraits(.isButton)
        .accessibilityAddTraits(.isLink)
    }

    /// The system sans's line height as a share of its size.
    private static let lineHeight: CGFloat = 1.2

    private struct Pressed: ButtonStyle {
        func makeBody(configuration: Configuration) -> some View {
            configuration.label.opacity(configuration.isPressed ? 0.6 : 1)
        }
    }
}

/// Square dots of [dot] a side with room for two between them, [drop] under the baseline of every line
/// the label wraps to. Before iOS 18 / macOS 15 there is no line layout to draw under, and the system's
/// own dotted underline stands in.
private struct DottedUnderline: ViewModifier {
    let color: Color
    let dot: CGFloat

    func body(content: Content) -> some View {
        if #available(iOS 18, macOS 15, watchOS 11, *) {
            content.textRenderer(Dots(color: color, dot: dot))
        } else {
            content.underline(true, pattern: .dot, color: color)
        }
    }

    @available(iOS 18, macOS 15, watchOS 11, *)
    private struct Dots: TextRenderer {
        let color: Color
        let dot: CGFloat
        private let drop: CGFloat = 2

        func draw(layout: Text.Layout, in context: inout GraphicsContext) {
            for line in layout {
                context.draw(line)
                let bounds = line.typographicBounds
                var x = bounds.rect.minX
                while x + dot <= bounds.rect.maxX {
                    context.fill(Path(CGRect(x: x, y: bounds.origin.y + drop, width: dot, height: dot)), with: .color(color))
                    x += dot * 3
                }
            }
        }
    }
}
