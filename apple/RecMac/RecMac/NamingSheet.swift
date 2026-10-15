import RecKit
import SwiftUI

/// docs/03: the name, and how many people were in the room — the hint `transcribe` trusts over the
/// workflow's own `speakers` (docs/08). The phone asks the same two things in the same order
/// (`RecPhone/RecordingView.NamingSheet`); this is the Mac's copy of it, drawn in a
/// [BlueprintPanel] because a menu-bar app has no window to present a sheet from.
///
/// "Unknown" is where the count starts and it writes nothing: a recording keeps whatever count it
/// already had rather than losing it to an unanswered question.
///
/// Under them, the ChatGPT box of docs/08 "Titles and speaker names". The Mac asks no permission for it, so the box
/// is the panel's own state, started as it was left and saved as it changes.
struct NamingSheet: View {
    /// 2026-10-10 (3.5): the count is a hint to the transcription, so it is not asked while transcription is off.
    let asksPeople: Bool
    /// The ChatGPT box is offered: transcription is on and ChatGPT is signed in.
    let asksFill: Bool
    /// The box changed — kept for the next panel (`SummaryPreferences.fillAfterStop`).
    let onFillChanged: (Bool) -> Void
    /// `(title, participants, fill)` — nil title is "no name", nil participants is "unknown", fill is the box
    /// shown and ticked.
    let onSave: (String?, Int?, Bool) -> Void
    let onCancel: () -> Void

    @State private var title = ""
    @State private var participants: Int?
    @State private var fill: Bool
    @Environment(\.blueprint) private var blueprint

    init(
        asksPeople: Bool, asksFill: Bool, fill: Bool, onFillChanged: @escaping (Bool) -> Void,
        onSave: @escaping (String?, Int?, Bool) -> Void, onCancel: @escaping () -> Void
    ) {
        self.asksPeople = asksPeople
        self.asksFill = asksFill
        self.onFillChanged = onFillChanged
        self.onSave = onSave
        self.onCancel = onCancel
        _fill = State(initialValue: fill)
    }

    /// docs/03: 2 · 3 · 4 · 5 · 6+ · unknown. docs/08 caps the hint at 10 speakers, so "6+" asks
    /// for six and lets the provider find more.
    private let choices: [Int?] = [nil, 2, 3, 4, 5, 6]

    var body: some View {
        BlueprintDialog(title: loc("Recording title")) {
            // docs/03 "Titles" · 2026-10-08 §5: the second answer throws the recording away, so it says
            // so, in the red of every other irreversible delete.
            BlueprintButton(loc("Discard recording"), tone: .danger, minWidth: minTouch) { onCancel() }
            BlueprintButton(loc("Save"), tone: .primary, minWidth: 120) { onSave(trimmed, participants, asksFill && fill) }
        } content: {
            BlueprintField(loc("Title"), text: $title, placeholder: RecKitStrings.localized("Untitled"))
            if asksPeople {
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
            if asksFill {
                FillAfterStopRow(isOn: Binding(get: { fill }, set: { fill = $0; onFillChanged($0) }))
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
