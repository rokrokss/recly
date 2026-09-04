import RecKit
import SwiftUI

/// docs/03: the name, and how many people were in the room — the hint `transcribe` trusts over the
/// workflow's own `speakers` (docs/08). The phone asks the same two things in the same order
/// (`RecPhone/RecordingView.NamingSheet`); this is the Mac's copy of it, drawn in a
/// [BlueprintPanel] because a menu-bar app has no window to present a sheet from.
///
/// "Unknown" is where the count starts and it writes nothing: a recording keeps whatever count it
/// already had rather than losing it to an unanswered question.
struct NamingSheet: View {
    /// `(title, participants)` — nil title is "no name", nil participants is "unknown".
    let onSave: (String?, Int?) -> Void
    let onSkip: () -> Void

    @State private var title = ""
    @State private var participants: Int?
    @Environment(\.blueprint) private var blueprint

    /// docs/03: 2 · 3 · 4 · 5 · 6+ · unknown. docs/08 caps the hint at 10 speakers, so "6+" asks
    /// for six and lets the provider find more.
    private let choices: [Int?] = [nil, 2, 3, 4, 5, 6]

    var body: some View {
        BlueprintDialog(title: loc("Recording title")) {
            BlueprintButton(loc("Skip"), tone: .quiet) { onSkip() }
            BlueprintButton(loc("Save"), tone: .primary) { onSave(trimmed, participants) }
        } content: {
            BlueprintField(loc("Title"), text: $title)
            BlueprintDialogText(loc("Leave it empty to keep the timestamp name"), tone: .muted)
            Text(verbatim: loc("People in the room"))
                .font(blueprint.fonts.label)
                .tracking(0.6)
                .foregroundStyle(blueprint.palette.textMuted)
            FlowLayout {
                ForEach(choices, id: \.self) { choice in
                    BlueprintChip(label(choice), selected: participants == choice) {
                        participants = choice
                    }
                }
            }
        }
    }

    private var trimmed: String? {
        let typed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        return typed.isEmpty ? nil : typed
    }

    /// docs/07 rule 4: a count is a number, not a sentence — only "unknown" and "6+" are words.
    private func label(_ choice: Int?) -> String {
        switch choice {
        case .none: return loc("Unknown")
        case .some(6): return loc("6+")
        case .some(let count): return String(count)
        }
    }
}
