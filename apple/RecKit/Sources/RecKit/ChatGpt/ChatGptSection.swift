#if os(iOS) || os(macOS)
import ReclyCore
import SwiftUI

/// docs/09 "Summary view" · docs/15 §10: the ChatGPT section, after Recording processing — the plan the user
/// already pays for, used for summaries, on the same rows and buttons the Drive account has. Nothing at
/// all where ChatGPT is not offered.
public struct ChatGptSection: View {
    @ObservedObject private var model: ChatGptSettingsModel
    /// Told before a sign-in starts, so a shell drawn on two surfaces knows which one to welcome on.
    private let onSignIn: (() -> Void)?
    /// My format was just chosen with nothing in it: its field takes the focus as it appears (2026-10-10, 3.1).
    @State private var focusMyFormat = false
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale
    @Environment(\.openURL) private var openURL

    public init(model: ChatGptSettingsModel, onSignIn: (() -> Void)? = nil) {
        self.model = model
        self.onSignIn = onSignIn
    }

    public var body: some View {
        if model.available {
            SectionHeader(loc("ChatGPT")).padding(.horizontal, Space.m)
            rows
            if model.waitingForBrowser {
                SectionBlock {
                    HStack(spacing: Space.s) {
                        Text(verbatim: loc("Finish signing in in your browser"))
                            .font(blueprint.fonts.sans(TypeSize.small))
                            .foregroundStyle(blueprint.palette.textMuted)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        BlueprintButton(loc("Cancel"), tone: .quiet, minWidth: minTouch) { model.cancelSignIn() }
                            .accessibilityIdentifier("chatgpt-cancel")
                    }
                }
            }
            failure
            // docs/08 "Summaries": what every summary is asked for — under Model, signed in or out.
            SectionRow(title: loc("Summary format")) { formatPicker }
            // 2026-10-10 (3.1): My format's words only while My format is the format — until it has some, the note
            // says what summaries use meanwhile.
            if model.preferences.format == .custom {
                SectionBlock {
                    PreferenceField(
                        title: loc("My format"),
                        text: $model.customFormat,
                        placeholder: loc("Sections and instructions, e.g. Summary, Risks, Next steps with owners."),
                        note: loc(model.customFormat.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                            ? "Write your format here; until then summaries use General."
                            : "Used when the summary format is My format."),
                        limit: Int(SummaryPreferences.companion.CUSTOM_MAX),
                        lines: 2...8,
                        multiline: true,
                        identifier: "chatgpt-my-format",
                        focusRequested: $focusMyFormat,
                        done: model.savePreferences
                    )
                }
            }
            SectionBlock {
                PreferenceField(
                    title: loc("About you"),
                    text: $model.aboutMe,
                    placeholder: loc("e.g. Product manager; I care about deadlines and decisions."),
                    note: loc("Summaries and answers use this to judge what matters to you."),
                    limit: Int(SummaryPreferences.companion.ABOUT_MAX),
                    lines: 1...3,
                    multiline: false,
                    identifier: "chatgpt-about-you",
                    focusRequested: .constant(false),
                    done: model.savePreferences
                )
            }
            SectionFootnote(loc("Summaries and questions send the transcript text, the moments you highlighted and what you wrote here to OpenAI — never the audio — and count toward your ChatGPT plan’s usage."))
        }
    }

    /// The format Summarize uses, as a dropdown on both platforms (2026-10-10, A-A8). Every format is offered —
    /// My format too, whose words are written once it is chosen (3.1) — and the one shown is the one saved.
    private var formatPicker: some View {
        let options = SummaryFormat.allCases.map(FormatOption.init)
        let current = FormatOption(format: model.preferences.format)
        return BlueprintDropdown(loc("Summary format"), options: options, selection: Binding(get: { current }, set: { option in
            model.selectFormat(option.format)
            if option.format == .custom, model.customFormat.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                focusMyFormat = true
            }
        })) {
            ChatGptText.formatLabel($0.format)
        }
        .accessibilityIdentifier("chatgpt-summary-format")
    }

    private struct FormatOption: Hashable, Identifiable {
        let format: SummaryFormat
        var id: SummaryFormat { format }
    }

    @ViewBuilder
    private var rows: some View {
        switch onEnum(of: model.connection) {
        case .signedIn(let signedIn):
            // docs/09 (2026-10-09): Manage usage opens a web page, so it is a link on the account's own row.
            SectionRow(
                title: signedIn.account,
                subtitle: loc("Using your ChatGPT plan"),
                link: TextLink(loc("Manage usage"), small: true) { openURL(ChatGptSettingsModel.usage) }
            ) {
                BlueprintButton(loc("Sign out"), tone: .quiet) { model.signOut() }
                    .accessibilityIdentifier("chatgpt-sign-out")
            }
            if !signedIn.models.isEmpty {
                SectionRow(title: loc("Model")) { picker(signedIn) }
            }
        case .expired(let expired):
            SectionRow(
                title: expired.account,
                subtitle: loc("OpenAI ended this sign-in. Continue with ChatGPT to sign in again."),
                subtitleColor: BadgeTone.warning.ink(blueprint.palette)
            ) { continueButton }
        case .signedOut, .unavailable:
            SectionRow(
                title: loc("Use your ChatGPT plan"),
                subtitle: loc("Summarize recordings with the plan you already have. Recly charges nothing for it.")
            ) { continueButton }
        }
    }

    /// docs/09 screen principle 5: the sign-in's progress is the button's own, in its place.
    private var continueButton: some View {
        ProcessingButton(loc("Continue with ChatGPT"), state: model.signInState) {
            onSignIn?()
            model.signIn()
        }
        .accessibilityIdentifier("chatgpt-continue")
    }

    @ViewBuilder
    private var failure: some View {
        switch model.failure {
        case .signIn(let reason):
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: loc("Could not sign in to ChatGPT"))
                    .font(blueprint.fonts.sans(TypeSize.small, weight: .medium))
                    .foregroundStyle(blueprint.palette.danger)
                if let detail = ChatGptText.detail(reason) {
                    Text(verbatim: detail)
                        .font(blueprint.fonts.sans(TypeSize.small))
                        .foregroundStyle(blueprint.palette.textMuted)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, Space.m)
            .padding(.vertical, Space.s)
        case .signOutUnconfirmed:
            // Not red: this device is signed out, and ChatGPT settings can finish the rest.
            SectionFootnote(loc("Signed out on this device, but OpenAI did not confirm it. You can remove Recly in ChatGPT settings."))
        case nil:
            EmptyView()
        }
    }

    /// The plan's models, as a dropdown on both platforms (2026-10-10, A-A8).
    private func picker(_ signedIn: ChatGptConnection.SignedIn) -> some View {
        let options = signedIn.models.map { ModelOption(id: $0.id, label: $0.label) }
        let current = options.first { $0.id == signedIn.model } ?? options[0]
        return BlueprintDropdown(loc("Model"), options: options, selection: Binding(get: { current }, set: { model.selectModel($0.id) })) { $0.label }
    }

    private struct ModelOption: Hashable, Identifiable {
        let id: String
        let label: String
    }

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }
}

/// One of the section's two fields (docs/09 "Summary view"): its name, the box, and what it is for under it —
/// the vocabulary row's shape. Saved when editing ends — the focus leaves, the screen goes — with no Cancel · Save;
/// empty is allowed. Each grows over [lines] and then scrolls. My format is written in lines: Return is a new line
/// in it, on the Mac too (2026-10-10, A-A6). About you is one paragraph that wraps: Return saves it.
private struct PreferenceField: View {
    let title: String
    @Binding var text: String
    let placeholder: String
    let note: String
    /// The core keeps no more than this (`SummaryPreferences`), so the field takes no more.
    let limit: Int
    let lines: ClosedRange<Int>
    let multiline: Bool
    let identifier: String
    /// Set by the section when the field is to take the focus as it appears — once, then cleared.
    @Binding var focusRequested: Bool
    let done: () -> Void
    @Environment(\.blueprint) private var blueprint
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s) {
            Text(verbatim: title)
                .font(blueprint.fonts.bodySmall)
                .foregroundStyle(blueprint.palette.text)
            field
                .textFieldStyle(.plain)
                .font(blueprint.fonts.bodySmall)
                .foregroundStyle(blueprint.palette.text)
                .background(blueprint.palette.surface, in: RoundedRectangle(cornerRadius: Radius.node))
                .overlay {
                    RoundedRectangle(cornerRadius: Radius.node)
                        .strokeBorder(focused ? blueprint.palette.accent : blueprint.palette.inputBorder, lineWidth: focused ? blueprint.line + 1 : blueprint.line)
                }
                .onChange(of: text) { _, typed in
                    if typed.count > limit { text = String(typed.prefix(limit)) }
                    // A paragraph: the phone's Return, which a wrapping field takes as a new line, ends the
                    // editing instead (the Mac's submits).
                    if !multiline, typed.contains(where: \.isNewline) {
                        text = typed.filter { !$0.isNewline }
                        focused = false
                    }
                }
                .onChange(of: focused) { _, now in if !now { done() } }
                .onSubmit(done)
                .onDisappear(perform: done)
                .onAppear { if focusRequested { takeFocus() } }
                .onChange(of: focusRequested) { _, requested in if requested { takeFocus() } }
                .accessibilityLabel(Text(verbatim: title))
                .accessibilityIdentifier(identifier)
            Text(verbatim: note)
                .font(blueprint.fonts.sans(TypeSize.small))
                .foregroundStyle(blueprint.palette.textMuted)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private var field: some View {
        #if os(macOS)
        if multiline {
            // A field's 10 × 9, less the inset the text view keeps of its own.
            GrowingTextEditor(text: $text, placeholder: placeholder, lines: lines, focused: $focused)
                .padding(.horizontal, 10 - GrowingTextEditor.inset)
                .padding(.vertical, 9)
        } else {
            TextField("", text: $text, axis: .vertical)
                .lineLimit(lines)
                .fieldPlaceholder(placeholder, empty: text.isEmpty)
                .focused($focused)
                .padding(.horizontal, 10)
                .padding(.vertical, 9)
        }
        #else
        TextField("", text: $text, prompt: FieldPlaceholder.prompt(placeholder, blueprint.palette), axis: .vertical)
            .lineLimit(lines)
            .submitLabel(multiline ? .return : .done)
            .focused($focused)
            .padding(.horizontal, 10)
            .padding(.vertical, 9)
        #endif
    }

    /// A moment after the field appears, once whatever opened it — the format's menu — has closed.
    private func takeFocus() {
        focusRequested = false
        Task {
            try? await Task.sleep(for: .milliseconds(150))
            focused = true
        }
    }
}

/// The one-time confirmation after the first sign-in on a device (docs/09 "Summary view"). The shells present it
/// where their other questions go.
public struct ChatGptWelcomeDialog: View {
    private let done: () -> Void
    @Environment(\.openURL) private var openURL
    @Environment(\.locale) private var locale

    public init(done: @escaping () -> Void) { self.done = done }

    public var body: some View {
        BlueprintDialog(title: RecKitStrings.localized("You’re using your ChatGPT plan")) {
            BlueprintButton(RecKitStrings.localized("Manage usage"), tone: .quiet) {
                openURL(ChatGptSettingsModel.usage)
                done()
            }
            BlueprintButton(RecKitStrings.localized("Got it"), tone: .primary, action: done)
                .accessibilityIdentifier("chatgpt-welcome-done")
        } content: {
            BlueprintDialogText(RecKitStrings.localized("Summaries in Recly use your ChatGPT plan. You can manage usage in ChatGPT settings."))
        }
    }
}

extension View {
    /// The welcome over this view, once [model] says so. [inline] draws it in the view, for a surface with no
    /// window to present a sheet from (the Mac's popover, see `blueprintDialogOverlay`); [shown] keeps a
    /// question drawn on one surface off the other.
    public func chatGptWelcome(_ model: ChatGptSettingsModel?, inline: Bool = false, shown: Bool = true) -> some View {
        // Hosted by a view of its own, which observes the model the screen around it does not.
        Group {
            if inline {
                overlay { if let model { ChatGptWelcomeHost(model: model, inline: true, shown: shown) } }
            } else {
                background { if let model { ChatGptWelcomeHost(model: model, inline: false, shown: shown) } }
            }
        }
    }
}

private struct ChatGptWelcomeHost: View {
    @ObservedObject var model: ChatGptSettingsModel
    let inline: Bool
    let shown: Bool

    var body: some View {
        if inline {
            if shown, model.welcome {
                BlueprintDialogScrim { ChatGptWelcomeDialog { model.welcome = false } }
            }
        } else {
            Color.clear.blueprintDialog(isPresented: Binding(get: { shown && model.welcome }, set: { if !$0 { model.welcome = false } })) {
                ChatGptWelcomeDialog { model.welcome = false }
            }
        }
    }
}
#endif
