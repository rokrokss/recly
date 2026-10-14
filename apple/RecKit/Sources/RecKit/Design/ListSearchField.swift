import SwiftUI

#if os(iOS) || os(macOS)
/// ux §6 · 2026-10-08 §12: the list's search field — on the Mac's Details window and on the phone's list,
/// under the count line and above the ledger. The Blueprint field (input border, radius 4) with the
/// magnifier at its start and `×` to clear it at its end.
public struct ListSearchField: View {
    @Binding private var text: String
    private let focus: FocusState<Bool>.Binding
    @Environment(\.blueprint) private var blueprint

    public init(text: Binding<String>, focus: FocusState<Bool>.Binding) {
        _text = text
        self.focus = focus
    }

    public var body: some View {
        HStack(spacing: Space.xs) {
            Image(systemName: "magnifyingglass")
                .font(blueprint.fonts.sans(TypeSize.small))
                .foregroundStyle(blueprint.palette.textMuted)
                .accessibilityHidden(true)
            TextField("", text: $text, prompt: FieldPlaceholder.prompt(placeholder, blueprint.palette))
                .fieldPlaceholder(placeholder, empty: text.isEmpty)
                .textFieldStyle(.plain)
                .font(blueprint.fonts.bodySmall)
                .foregroundStyle(blueprint.palette.text)
                .focused(focus)
                #if os(iOS)
                .submitLabel(.search)
                #endif
                .accessibilityLabel(Text(verbatim: placeholder))
                .accessibilityIdentifier("list-search")
            if !text.isEmpty {
                Button { text = "" } label: {
                    Text(verbatim: "×")
                        .font(blueprint.fonts.sans(TypeSize.body))
                        .foregroundStyle(blueprint.palette.textMuted)
                        .frame(width: minTouch, height: minTouch)
                        .contentShape(Rectangle())
                        .blueprintFocusRing()
                }
                .buttonStyle(.plain)
                .focusEffectDisabled()
                .accessibilityLabel(Text(verbatim: RecKitStrings.localized("Close search")))
                .accessibilityIdentifier("list-search-clear")
            }
        }
        // 2026-10-10 (A-A23): the × brings its own 44pt target to the end; with nothing typed the field keeps its
        // inset there, so the placeholder does not run into the edge. No inset above and below: the field is the
        // touch minimum tall on the Mac too (2026-10-15), which leaves the words more room than 8 each way.
        .frame(minHeight: minTouch)
        .fieldBox(
            focused: focus.wrappedValue,
            inset: EdgeInsets(top: 0, leading: Space.sm, bottom: 0, trailing: text.isEmpty ? Space.sm : 0)
        )
        .padding(.horizontal, Space.m)
        .padding(.bottom, Space.s)
    }

    private var placeholder: String { RecKitStrings.localized("Search titles and transcripts") }
}
#endif
