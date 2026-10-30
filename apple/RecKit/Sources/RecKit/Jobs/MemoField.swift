import ReclyCore
import SwiftUI

#if os(iOS) || os(macOS)
/// docs/08 "Memo" · docs/09 "AI view": the memo's field — the one the recording screens, the Mac's popover and the detail
/// write a recording's memo in. It saves as the user types — [MemoText.saveDelay] after the last keystroke, and again as
/// it goes — so there is no Save, and a popover closed by Escape or a click elsewhere loses nothing. It keeps its own
/// text: a newer memo from the core — another device's, or this field's own save coming back — replaces it only while
/// nothing typed here is unsaved.
public struct MemoField: View {
    /// The memo as the core has it now.
    private let memo: String
    /// Signed in to ChatGPT and agreed to organizing: the line under the field says the notes read it.
    private let agreed: Bool
    private let lines: ClosedRange<Int>
    private let save: (String) -> Void
    private var focused: FocusState<Bool>.Binding
    @State private var text = ""
    /// What was last saved, or taken in from the core; nil until the field has appeared.
    @State private var saved: String?
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale

    public init(memo: String, agreed: Bool, lines: ClosedRange<Int>, focused: FocusState<Bool>.Binding, save: @escaping (String) -> Void) {
        self.memo = memo
        self.agreed = agreed
        self.lines = lines
        self.focused = focused
        self.save = save
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: Space.s) {
            field
                .textFieldStyle(.plain)
                .font(blueprint.fonts.bodySmall)
                .foregroundStyle(blueprint.palette.text)
                .fieldBox(focused: focused.wrappedValue, inset: EdgeInsets())
                .accessibilityLabel(Text(verbatim: RecKitStrings.localized("Notes")))
                .accessibilityIdentifier("memo-field")
            if agreed {
                Text(verbatim: RecKitStrings.localized("Read along when the summary is made."))
                    .font(blueprint.fonts.sans(TypeSize.small))
                    .foregroundStyle(blueprint.palette.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("memo-footnote")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .onAppear {
            guard saved == nil else { return }
            text = memo
            saved = memo
        }
        .onChange(of: memo) { _, next in
            guard text == saved else { return }
            text = next
            saved = next
        }
        .onChange(of: text) { _, typed in
            let capped = MemoText.capped(typed)
            if capped != typed { text = capped }
        }
        .task(id: text) {
            try? await Task.sleep(for: MemoText.saveDelay)
            guard !Task.isCancelled else { return }
            flush()
        }
        .onDisappear(perform: flush)
    }

    @ViewBuilder
    private var field: some View {
        let placeholder = RecKitStrings.localized("Agenda, who's there, what to keep track of")
        #if os(macOS)
        // A field's 12 × 8, less the inset the text view keeps of its own; Return is a new line, as in every memo.
        GrowingTextEditor(text: $text, placeholder: placeholder, lines: lines, focused: focused)
            .padding(FieldInset.textView)
        #else
        TextField("", text: $text, prompt: FieldPlaceholder.prompt(placeholder, blueprint.palette), axis: .vertical)
            .lineLimit(lines)
            .submitLabel(.return)
            .focused(focused)
            .padding(FieldInset.field)
        #endif
    }

    /// The text typed since the last save, saved now.
    private func flush() {
        guard let saved, text != saved else { return }
        self.saved = text
        save(text)
    }
}

/// docs/08 "Memo": the recording screens' `Memo` row — always there while recording, empty or not — with the memo's
/// first line in the muted ink beside its name. A tap opens the memo's field.
public struct MemoRow: View {
    private let memo: String
    private let action: () -> Void
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale

    public init(memo: String, action: @escaping () -> Void) {
        self.memo = memo
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            HStack(spacing: Space.sm) {
                Text(verbatim: RecKitStrings.localized("Notes"))
                    .font(blueprint.fonts.bodySmall)
                    .foregroundStyle(blueprint.palette.text)
                    .fixedSize()
                if let line = MemoText.firstLine(memo) {
                    Text(verbatim: line)
                        .font(blueprint.fonts.bodySmall)
                        .foregroundStyle(blueprint.palette.textMuted)
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
                Spacer(minLength: 0)
            }
            .padding(FieldInset.field)
            .frame(minHeight: minTouch)
            .frame(maxWidth: .infinity, alignment: .leading)
            .fieldBox(focused: false, inset: EdgeInsets())
            .contentShape(Rectangle())
        }
        .buttonStyle(BlueprintPressStyle())
        .focusEffectDisabled()
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("memo-row")
    }
}

#endif

/// docs/08 "Memo": what every shell's memo shares — the core's limit, the wait before a save, and how the memo reads in
/// one line.
public enum MemoText {
    /// How long the field waits after the last keystroke before it saves.
    public static let saveDelay: Duration = .milliseconds(800)

    /// [text] cut to the core's [Memos.MAX] — counted as the core counts, in UTF-16 code units — never in the middle of
    /// a character.
    public static func capped(_ text: String) -> String {
        let limit = Int(Memos.companion.MAX)
        guard text.utf16.count > limit else { return text }
        var kept = ""
        var count = 0
        for character in text {
            let size = character.utf16.count
            if count + size > limit { break }
            kept.append(character)
            count += size
        }
        return kept
    }

    /// The memo's first line that is not blank, trimmed; nil when the memo is blank — a blank memo shows nothing.
    public static func firstLine(_ memo: String) -> String? {
        memo.split(whereSeparator: \.isNewline)
            .lazy
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .first { !$0.isEmpty }
    }

    /// Whether there is a memo to show: anything but blank.
    public static func isBlank(_ memo: String) -> Bool { firstLine(memo) == nil }
}
