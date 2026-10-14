import SwiftUI

#if os(iOS) || os(macOS)
/// docs/09 "Detail header and More menu" · "Disabled, focus and structure": the icon button — an SF Symbol in the
/// quiet ink on a square [minTouch] target, its name for VoiceOver, the `disabled` ink while it is off, and the
/// focus ring. Every header's icon is this one control (import, share, close).
public struct BlueprintIconButton: View {
    private let systemName: String
    private let label: String
    private let action: () -> Void

    /// - Parameter label: what VoiceOver says — the icon has no words of its own.
    public init(_ systemName: String, label: String, action: @escaping () -> Void) {
        self.systemName = systemName
        self.label = label
        self.action = action
    }

    public var body: some View {
        Button(action: action) { BlueprintIconLabel(systemName) }
            .buttonStyle(BlueprintPressStyle())
            .focusEffectDisabled()
            .accessibilityLabel(Text(verbatim: label))
    }
}

/// The icon button's face, for a control that is not a `Button` of its own — a `Menu`'s label.
public struct BlueprintIconLabel: View {
    private let systemName: String
    @Environment(\.blueprint) private var blueprint
    @Environment(\.isEnabled) private var isEnabled

    public init(_ systemName: String) {
        self.systemName = systemName
    }

    public var body: some View {
        Image(systemName: systemName)
            .font(blueprint.fonts.sans(TypeSize.body))
            .foregroundStyle(isEnabled ? blueprint.palette.textMuted : blueprint.palette.disabled)
            .frame(width: minTouch, height: minTouch)
            .contentShape(Rectangle())
            .blueprintFocusRing()
    }
}
#endif
