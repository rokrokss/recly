import SwiftUI

/// docs/09 "Component measurements" — Focus ring (2026-10-15): the accent at `selectedLine`, inside the control and
/// square like it, on every focusable control, links included — what full keyboard access lands on. The system's own
/// ring is a rounded halo outside the control in the user's accent colour, so a control that draws this one turns that
/// one off (`focusEffectDisabled`).
///
/// Applied to a control's *label*: `isFocused` is the focus of the nearest focusable ancestor, which for a label is the
/// button around it.
private struct FocusRing: ViewModifier {
    @Environment(\.blueprint) private var blueprint
    @Environment(\.isFocused) private var focused
    let radius: CGFloat
    let onFill: Bool

    func body(content: Content) -> some View {
        content.overlay {
            if focused {
                // On an accent fill the accent is no ring at all, so a filled control's ring is the ink on that fill.
                RoundedRectangle(cornerRadius: radius)
                    .strokeBorder(onFill ? blueprint.palette.onAccent : blueprint.palette.accent, lineWidth: blueprint.palette.selectedLine)
                    .allowsHitTesting(false)
            }
        }
    }
}

extension View {
    /// The focus ring on a control's label, square at [radius] — the control's own corner. [onFill] for a label drawn
    /// on an accent fill (a primary button).
    func blueprintFocusRing(radius: CGFloat = Radius.node, onFill: Bool = false) -> some View {
        modifier(FocusRing(radius: radius, onFill: onFill))
    }
}
