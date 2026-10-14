import SwiftUI

/// docs/09 "Component measurements" — Text field (2026-10-15): what sits between a field's box and its words.
enum FieldInset {
    /// 12 × 8.
    static let field = EdgeInsets(top: Space.s, leading: Space.sm, bottom: Space.s, trailing: Space.sm)

    /// The same 12 × 8 around a text view, less what the text view keeps of its own: its line fragment padding at
    /// each side and, on iOS, its text container's inset above and below — so its words stand where a field's do.
    #if os(macOS)
    static let textView = EdgeInsets(
        top: Space.s, leading: Space.sm - textViewSide, bottom: Space.s, trailing: Space.sm - textViewSide
    )
    #else
    static let textView = EdgeInsets(
        top: 0, leading: Space.sm - textViewSide, bottom: 0, trailing: Space.sm - textViewSide
    )
    #endif

    /// A text view's line fragment padding, on each side.
    static let textViewSide: CGFloat = 5
}

extension View {
    /// docs/09 "Component measurements" — Text field: the inset, the surface, an `inputBorder` edge on the line and,
    /// while [focused], the accent at `selectedLine` — the one field box every field and text view is drawn in.
    func fieldBox(focused: Bool, inset: EdgeInsets = FieldInset.field) -> some View {
        modifier(FieldBox(focused: focused, inset: inset))
    }
}

private struct FieldBox: ViewModifier {
    @Environment(\.blueprint) private var blueprint
    let focused: Bool
    let inset: EdgeInsets

    func body(content: Content) -> some View {
        content
            .padding(inset)
            .background(blueprint.palette.surface, in: RoundedRectangle(cornerRadius: Radius.node))
            .overlay {
                RoundedRectangle(cornerRadius: Radius.node)
                    .strokeBorder(
                        focused ? blueprint.palette.accent : blueprint.palette.inputBorder,
                        lineWidth: focused ? blueprint.palette.selectedLine : blueprint.line
                    )
            }
    }
}
