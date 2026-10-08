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
            TextField("", text: $text, prompt: Text(verbatim: RecKitStrings.localized("Search titles and transcripts")))
                .textFieldStyle(.plain)
                .font(blueprint.fonts.bodySmall)
                .foregroundStyle(blueprint.palette.text)
                .focused(focus)
                #if os(iOS)
                .submitLabel(.search)
                #endif
                .accessibilityIdentifier("list-search")
            if !text.isEmpty {
                Button { text = "" } label: {
                    Text(verbatim: "×")
                        .font(blueprint.fonts.sans(TypeSize.body))
                        .foregroundStyle(blueprint.palette.textMuted)
                        .frame(width: Self.clearSide, height: Self.clearSide)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(verbatim: RecKitStrings.localized("Close search")))
                .accessibilityIdentifier("list-search-clear")
            }
        }
        .padding(.leading, Space.s)
        .padding(.trailing, Self.clearSide == minTouch ? 0 : Space.s)
        .frame(minHeight: Self.height)
        .background(blueprint.palette.surface, in: RoundedRectangle(cornerRadius: Radius.node))
        .overlay {
            RoundedRectangle(cornerRadius: Radius.node)
                .strokeBorder(blueprint.palette.inputBorder, lineWidth: blueprint.line)
        }
        .padding(.horizontal, Space.m)
        .padding(.bottom, Space.s)
    }

    /// A finger on the phone needs the whole 44pt target; the Mac's pointer is served by a smaller field.
    #if os(iOS)
    private static let height: CGFloat = minTouch
    private static let clearSide: CGFloat = minTouch
    #else
    private static let height: CGFloat = 32
    private static let clearSide: CGFloat = 24
    #endif
}
#endif
