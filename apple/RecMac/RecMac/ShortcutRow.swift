import RecKit
import SwiftUI

/// Settings → Capture: the ⌥⌘R row — [SwitchRow]'s shape with the combination beside the switch, in
/// mono, and the system's refusal under the title in the warning tone.
struct ShortcutRow: View {
    @Binding var isOn: Bool
    /// The system would not register the combination: another app holds it.
    let refused: Bool
    @Environment(\.blueprint) private var blueprint

    var body: some View {
        VStack(spacing: 0) {
            Toggle(isOn: $isOn) {
                HStack(spacing: Space.s) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: loc("Keyboard shortcut"))
                            .font(blueprint.fonts.bodySmall)
                            .foregroundStyle(blueprint.palette.text)
                        if refused, isOn {
                            Text(verbatim: loc("Another app uses this shortcut."))
                                .font(blueprint.fonts.sans(TypeSize.small))
                                .foregroundStyle(blueprint.palette.warningInk)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    Text(verbatim: GlobalShortcut.label)
                        .font(blueprint.fonts.monoBodySmall)
                        .foregroundStyle(blueprint.palette.textMuted)
                }
            }
            .toggleStyle(BlueprintSwitchStyle())
            .padding(.horizontal, Space.m)
            .padding(.vertical, 6)
            .frame(minHeight: minTouch)
            HairLine()
        }
        .background(blueprint.palette.surface)
    }
}
