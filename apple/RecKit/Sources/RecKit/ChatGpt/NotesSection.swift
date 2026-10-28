#if os(iOS) || os(macOS)
import ReclyCore
import SwiftUI

/// docs/09 "AI view" · docs/08 "Organizing" — "Settings": Settings → Notes, right after the ChatGPT section, signed in or
/// not — what every recording's notes are asked for, apart from the provider that writes them: `Notes instructions`, up
/// to three of `My sections`, and `About you`. Each field saves when editing ends; `Remove` saves at once. Nothing at
/// all where ChatGPT is not offered.
public struct NotesSection: View {
    @ObservedObject private var model: ChatGptSettingsModel
    /// The section `Add section` just made, whose name takes the focus as it appears.
    @State private var added: UUID?
    @FocusState private var instructionsFocused: Bool
    @FocusState private var aboutFocused: Bool
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale

    public init(model: ChatGptSettingsModel) {
        self.model = model
    }

    public var body: some View {
        if model.available {
            SectionHeader(loc("Notes")).padding(.horizontal, Space.m)
            SectionBlock {
                PreferenceField(
                    title: loc("Notes instructions"),
                    text: $model.instructions,
                    placeholder: loc("e.g. Group to-dos by owner. Keep technical terms in English."),
                    note: loc("Every recording’s notes follow this."),
                    limit: Int(NotesPreferences.companion.INSTRUCTIONS_MAX),
                    lines: 2...8,
                    multiline: true,
                    identifier: "notes-instructions",
                    focused: $instructionsFocused,
                    done: { model.saveNotes() }
                )
            }
            SectionBlock {
                Text(verbatim: loc("My sections"))
                    .font(blueprint.fonts.bodySmall)
                    .foregroundStyle(blueprint.palette.text)
                ForEach($model.sections) { $section in
                    NoteSectionEditor(
                        section: $section,
                        focusRequested: Binding(get: { added == section.key }, set: { if !$0 { added = nil } }),
                        save: { model.saveNotes() },
                        leave: { model.saveNotes(leaving: section.key) },
                        remove: { model.removeSection(section.key) }
                    )
                    .padding(.bottom, Space.s)
                }
                if model.sections.count < Int(NotesPreferences.companion.SECTIONS_MAX) {
                    BlueprintButton(loc("Add section"), tone: .quiet) { added = model.addSection() }
                        .accessibilityIdentifier("notes-add-section")
                }
                Text(verbatim: loc("Each recording’s notes also list these, with when each was said."))
                    .font(blueprint.fonts.sans(TypeSize.small))
                    .foregroundStyle(blueprint.palette.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            // The screen goes: a section left without a name goes with it.
            .onDisappear { model.saveNotes(droppingNameless: true) }
            SectionBlock {
                PreferenceField(
                    title: loc("About you"),
                    text: $model.aboutMe,
                    placeholder: loc("e.g. Product manager; I care about deadlines and decisions."),
                    note: loc("Notes and answers use this to judge what matters to you."),
                    limit: Int(SummaryPreferences.companion.ABOUT_MAX),
                    lines: 1...3,
                    multiline: false,
                    identifier: "chatgpt-about-you",
                    focused: $aboutFocused,
                    done: model.savePreferences
                )
            }
        }
    }

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }
}

/// One of My sections: its one-line `Section name`, `What goes in it` under it, and a quiet `Remove`. Editing that leaves
/// the section — neither field has the focus any more — takes it from the list when it has no name.
private struct NoteSectionEditor: View {
    @Binding var section: NoteSectionDraft
    @Binding var focusRequested: Bool
    let save: () -> Void
    let leave: () -> Void
    let remove: () -> Void
    @FocusState private var nameFocused: Bool
    @FocusState private var descriptionFocused: Bool
    @Environment(\.locale) private var locale

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s) {
            PreferenceField(
                title: loc("Section name"),
                text: $section.name,
                placeholder: loc("e.g. Risks"),
                note: nil,
                limit: Int(NotesPreferences.companion.SECTION_NAME_MAX),
                lines: 1...1,
                multiline: false,
                identifier: "notes-section-name",
                focused: $nameFocused,
                focusRequested: $focusRequested,
                done: save
            ) {
                BlueprintButton(loc("Remove"), tone: .quiet, action: remove)
                    .accessibilityLabel(Text(verbatim: RecKitStrings.localized("Remove %@", section.name)))
                    .accessibilityIdentifier("notes-section-remove")
            }
            PreferenceField(
                title: loc("What goes in it"),
                text: $section.description,
                placeholder: loc("e.g. Anything that could delay the launch, and who raised it."),
                note: nil,
                limit: Int(NotesPreferences.companion.DESCRIPTION_MAX),
                lines: 1...3,
                multiline: true,
                identifier: "notes-section-description",
                focused: $descriptionFocused,
                done: save
            )
        }
        .onChange(of: nameFocused || descriptionFocused) { _, inSection in if !inSection { leave() } }
    }

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }
}

/// One field of the notes settings (docs/09 "AI view"): its name — with [accessory] at the end of that line — the box,
/// and what it is for under it, the vocabulary row's shape. Saved when editing ends — the focus leaves, the screen goes —
/// with no Cancel · Save; empty is allowed. Each grows over [lines] and then scrolls. A [multiline] field is written in
/// lines: Return is a new line in it, on the Mac too (2026-10-10, A-A6). The others are one paragraph that wraps: Return
/// ends the editing.
struct PreferenceField<Accessory: View>: View {
    let title: String
    @Binding var text: String
    let placeholder: String
    let note: String?
    /// The core keeps no more than this, so the field takes no more.
    let limit: Int
    let lines: ClosedRange<Int>
    let multiline: Bool
    let identifier: String
    /// The caller's, so it can tell when the editing of more than one field ends.
    var focused: FocusState<Bool>.Binding
    /// Set by the caller when the field is to take the focus as it appears — once, then cleared.
    @Binding var focusRequested: Bool
    let done: () -> Void
    @ViewBuilder let accessory: () -> Accessory
    @Environment(\.blueprint) private var blueprint

    init(
        title: String, text: Binding<String>, placeholder: String, note: String?, limit: Int, lines: ClosedRange<Int>,
        multiline: Bool, identifier: String, focused: FocusState<Bool>.Binding, focusRequested: Binding<Bool> = .constant(false),
        done: @escaping () -> Void, @ViewBuilder accessory: @escaping () -> Accessory = { EmptyView() }
    ) {
        self.title = title
        _text = text
        self.placeholder = placeholder
        self.note = note
        self.limit = limit
        self.lines = lines
        self.multiline = multiline
        self.identifier = identifier
        self.focused = focused
        _focusRequested = focusRequested
        self.done = done
        self.accessory = accessory
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s) {
            HStack(spacing: Space.s) {
                Text(verbatim: title)
                    .font(blueprint.fonts.bodySmall)
                    .foregroundStyle(blueprint.palette.text)
                    .frame(maxWidth: .infinity, alignment: .leading)
                accessory()
            }
            field
                .textFieldStyle(.plain)
                .font(blueprint.fonts.bodySmall)
                .foregroundStyle(blueprint.palette.text)
                .fieldBox(focused: focused.wrappedValue, inset: EdgeInsets())
                .onChange(of: text) { _, typed in
                    if typed.count > limit { text = String(typed.prefix(limit)) }
                    // A paragraph: the phone's Return, which a wrapping field takes as a new line, ends the
                    // editing instead (the Mac's submits).
                    if !multiline, typed.contains(where: \.isNewline) {
                        text = typed.filter { !$0.isNewline }
                        focused.wrappedValue = false
                    }
                }
                .onChange(of: focused.wrappedValue) { _, now in if !now { done() } }
                .onSubmit(done)
                .onDisappear(perform: done)
                .onAppear { if focusRequested { takeFocus() } }
                .onChange(of: focusRequested) { _, requested in if requested { takeFocus() } }
                .accessibilityLabel(Text(verbatim: title))
                .accessibilityIdentifier(identifier)
            if let note {
                Text(verbatim: note)
                    .font(blueprint.fonts.sans(TypeSize.small))
                    .foregroundStyle(blueprint.palette.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private var field: some View {
        #if os(macOS)
        if multiline {
            // A field's 12 × 8, less the inset the text view keeps of its own.
            GrowingTextEditor(text: $text, placeholder: placeholder, lines: lines, focused: focused)
                .padding(FieldInset.textView)
        } else {
            TextField("", text: $text, axis: .vertical)
                .lineLimit(lines)
                .fieldPlaceholder(placeholder, empty: text.isEmpty)
                .focused(focused)
                .padding(FieldInset.field)
        }
        #else
        TextField("", text: $text, prompt: FieldPlaceholder.prompt(placeholder, blueprint.palette), axis: .vertical)
            .lineLimit(lines)
            .submitLabel(multiline ? .return : .done)
            .focused(focused)
            .padding(FieldInset.field)
        #endif
    }

    /// A moment after the field appears, once whatever made it — `Add section` — has finished.
    private func takeFocus() {
        focusRequested = false
        Task {
            try? await Task.sleep(for: .milliseconds(150))
            focused.wrappedValue = true
        }
    }
}
#endif
