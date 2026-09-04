import SwiftUI

/// docs/15 §3, lane P1 deliverable 6: what leaves the device when the `transcribe` step runs — the
/// audio — and whose policy decides what becomes of it afterwards. It sits under the provider chips
/// in the `transcribe` form of every shell, and says the same thing in all of them — the Android
/// editor's `provider_disclosure_transcribe` is the same text, held to it by
/// `ProviderDisclosureTests`.
///
/// docs/15 §3 "작성 규칙": no "kept for N days" — Recly does not know the number and would be making
/// a promise on somebody else's behalf — and no link until the providers' own policy URLs are
/// confirmed, because an invented one points at a page nobody wrote.
public struct ProviderDisclosure: View {
    @Environment(\.blueprint) private var blueprint
    /// docs/07 rule 3: reading the locale is what redraws this in a language picked while the
    /// editor is open.
    @Environment(\.locale) private var locale

    public init() {}

    public var body: some View {
        Text(verbatim: RecKitStrings.localized("provider.disclosure.transcribe"))
            .font(blueprint.fonts.sans(TypeSize.small))
            .foregroundStyle(blueprint.palette.textMuted)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityIdentifier("provider-disclosure")
    }
}
