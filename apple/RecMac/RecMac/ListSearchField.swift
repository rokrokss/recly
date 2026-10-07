import RecKit
import SwiftUI

/// ux §6: the Details window's search field, above the list — the Blueprint field (input border,
/// radius 4) with the magnifier at its start and `×` to clear it at its end.
struct ListSearchField: View {
    @Binding var text: String
    let focus: FocusState<Bool>.Binding
    @Environment(\.blueprint) private var blueprint

    var body: some View {
        HStack(spacing: Space.xs) {
            Image(systemName: "magnifyingglass")
                .font(blueprint.fonts.sans(TypeSize.small))
                .foregroundStyle(blueprint.palette.textMuted)
                .accessibilityHidden(true)
            TextField("", text: $text, prompt: Text(verbatim: RecKitStrings.localized("Search titles and transcripts")))
                .textFieldStyle(.plain)
                .font(blueprint.fonts.bodySmall)
                .foregroundStyle(blueprint.palette.text)
                .focused(focus)
                .accessibilityIdentifier("list-search")
            if !text.isEmpty {
                Button { text = "" } label: {
                    Text(verbatim: "×")
                        .font(blueprint.fonts.sans(TypeSize.body))
                        .foregroundStyle(blueprint.palette.textMuted)
                        .frame(width: 24, height: 24)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(verbatim: RecKitStrings.localized("Close search")))
            }
        }
        .padding(.horizontal, Space.s)
        .frame(minHeight: 32)
        .background(blueprint.palette.surface, in: RoundedRectangle(cornerRadius: Radius.node))
        .overlay {
            RoundedRectangle(cornerRadius: Radius.node)
                .strokeBorder(blueprint.palette.inputBorder, lineWidth: blueprint.line)
        }
        .padding(.horizontal, Space.m)
        .padding(.bottom, Space.s)
    }
}
