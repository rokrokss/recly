import SwiftUI

/// docs/09 "Highlights": the news of a highlight just made — `Highlight · 12:34`, the time in mono and in
/// the live timer's shape (2026-10-08 §3) — on the line under the record control, wherever a recording
/// is made.
public struct HighlightNews: View {
    private let atSec: Double
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale

    public init(atSec: Double) {
        self.atSec = atSec
    }

    public var body: some View {
        let parts = RecKitStrings.localized("Highlight · %@").components(separatedBy: "%@")
        return Text(verbatim: parts.first ?? "")
            + Text(verbatim: LedgerFormat.elapsed(Int(atSec))).font(blueprint.fonts.monoBodySmall)
            + Text(verbatim: parts.dropFirst().joined(separator: "%@"))
    }
}
