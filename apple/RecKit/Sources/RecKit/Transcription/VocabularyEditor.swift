#if os(iOS) || os(macOS)
import ReclyCore
import SwiftUI

/// docs/05 "Fixed processing settings" · docs/09 "Vocabulary": the names and terms the transcription should
/// spell correctly, as chips in the processing settings draft — a field that adds one per line, a ×
/// on each to take it out. Saved with the rest of the draft (Cancel · Save).
struct VocabularyEditor: View {
    @Binding var terms: [String]
    /// The line under the row: who reads the list, or that nobody does.
    let description: String
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale
    @State private var entry = ""
    @State private var overLimit = false

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s) {
            Text(verbatim: loc("Vocabulary"))
                .font(blueprint.fonts.bodySmall)
                .foregroundStyle(blueprint.palette.text)
            if !terms.isEmpty {
                FlowLayout {
                    ForEach(terms, id: \.self) { term in chip(term) }
                }
            }
            HStack(spacing: Space.s) {
                TextField("", text: $entry, prompt: FieldPlaceholder.prompt(loc("Add a name or term"), blueprint.palette))
                    .fieldPlaceholder(loc("Add a name or term"), empty: entry.isEmpty)
                    .textFieldStyle(.plain)
                    .font(blueprint.fonts.bodySmall)
                    .foregroundStyle(blueprint.palette.text)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 9)
                    .overlay {
                        RoundedRectangle(cornerRadius: Radius.node).strokeBorder(blueprint.palette.inputBorder, lineWidth: blueprint.line)
                    }
                    .onSubmit(add)
                    .onChange(of: entry) { _, typed in
                        // A paste of several lines is several terms.
                        if typed.contains(where: \.isNewline) { add() }
                    }
                    .accessibilityLabel(Text(verbatim: loc("Add a name or term")))
                    .accessibilityIdentifier("vocabulary-field")
                BlueprintButton(loc("Add"), tone: .quiet) { add() }
                    .disabled(entry.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            if overLimit {
                Text(verbatim: loc("Up to 50 terms, 40 characters each."))
                    .font(blueprint.fonts.sans(TypeSize.small))
                    .foregroundStyle(BadgeTone.warning.ink(blueprint.palette))
            }
            Text(verbatim: description)
                .font(blueprint.fonts.sans(TypeSize.small))
                .foregroundStyle(blueprint.palette.textMuted)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.vertical, Space.s)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func chip(_ term: String) -> some View {
        HStack(spacing: Space.xs) {
            Text(verbatim: term)
                .font(blueprint.fonts.sans(TypeSize.small, weight: .medium))
                .foregroundStyle(blueprint.palette.text)
                .lineLimit(1)
            Button {
                terms.removeAll { $0 == term }
                overLimit = false
            } label: {
                Text(verbatim: "×")
                    .font(blueprint.fonts.sans(TypeSize.bodySmall))
                    .foregroundStyle(blueprint.palette.textMuted)
                    // 2026-10-08 §12: a 44pt target in both directions, as every chip's is.
                    .frame(minWidth: minTouch, minHeight: minTouch)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(verbatim: RecKitStrings.localized("Remove %@", term)))
        }
        .padding(.leading, 10)
        .overlay {
            RoundedRectangle(cornerRadius: Radius.node).strokeBorder(blueprint.palette.grid, lineWidth: blueprint.line)
        }
    }

    /// Every line of the field as a term, in order; a repeat is the same term, and a term past the limits
    /// is not taken — the line under the field says what they are.
    private func add() {
        var next = terms
        var refused = false
        for line in entry.split(whereSeparator: \.isNewline) {
            let term = line.trimmingCharacters(in: .whitespaces)
            guard !term.isEmpty, !next.contains(where: { $0.caseInsensitiveCompare(term) == .orderedSame }) else { continue }
            guard term.count <= Int(ProcessingSettingsKt.VOCABULARY_ENTRY_MAX), next.count < Int(ProcessingSettingsKt.VOCABULARY_MAX) else {
                refused = true
                continue
            }
            next.append(term)
        }
        entry = ""
        overLimit = refused
        if next != terms { terms = next }
    }

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }
}
#endif
