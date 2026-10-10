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
    @State private var pickingModel = false
    @State private var pickingFormat = false
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
            SectionBlock {
                PreferenceField(
                    title: loc("My format"),
                    text: $model.customFormat,
                    placeholder: loc("Sections and instructions, e.g. Summary, Risks, Next steps with owners."),
                    note: loc("Used when the summary format is My format."),
                    limit: Int(SummaryPreferences.companion.CUSTOM_MAX),
                    multiline: true,
                    identifier: "chatgpt-my-format",
                    done: model.savePreferences
                )
            }
            SectionBlock {
                PreferenceField(
                    title: loc("About you"),
                    text: $model.aboutMe,
                    placeholder: loc("e.g. Product manager; I care about deadlines and decisions."),
                    note: loc("Summaries use this to judge what matters to you."),
                    limit: Int(SummaryPreferences.companion.ABOUT_MAX),
                    multiline: false,
                    identifier: "chatgpt-about-you",
                    done: model.savePreferences
                )
            }
            SectionFootnote(loc("A summary sends the transcript text, the moments you highlighted and what you wrote here to OpenAI — never the audio — and counts toward your ChatGPT plan’s usage."))
        }
    }

    /// The format Summarize uses: the platform's dropdown on the Mac, and on the phone the button and dialog
    /// the Model row has.
    @ViewBuilder
    private var formatPicker: some View {
        let shown = model.shownPreferences
        let options = shown.formats.map(FormatOption.init)
        let current = FormatOption(format: shown.effectiveFormat)
        #if os(macOS)
        BlueprintDropdown(loc("Summary format"), options: options, selection: Binding(get: { current }, set: { model.selectFormat($0.format) })) {
            ChatGptText.formatLabel($0.format)
        }
        .accessibilityIdentifier("chatgpt-summary-format")
        #else
        BlueprintButton(ChatGptText.formatLabel(current.format), tone: .quiet) { pickingFormat = true }
            .accessibilityIdentifier("chatgpt-summary-format")
            .blueprintDialog(isPresented: $pickingFormat) {
                BlueprintDialog(title: loc("Summary format")) {
                    BlueprintButton(loc("Close"), tone: .quiet, minWidth: minTouch) { pickingFormat = false }
                } content: {
                    ForEach(options) { option in
                        BlueprintRadioRow(ChatGptText.formatLabel(option.format), selected: option == current) {
                            model.selectFormat(option.format)
                            pickingFormat = false
                        }
                    }
                }
            }
        #endif
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

    @ViewBuilder
    private func picker(_ signedIn: ChatGptConnection.SignedIn) -> some View {
        let options = signedIn.models.map { ModelOption(id: $0.id, label: $0.label) }
        let current = options.first { $0.id == signedIn.model } ?? options[0]
        #if os(macOS)
        BlueprintDropdown(loc("Model"), options: options, selection: Binding(get: { current }, set: { model.selectModel($0.id) })) { $0.label }
        #else
        BlueprintButton(current.label, tone: .quiet) { pickingModel = true }
            .blueprintDialog(isPresented: $pickingModel) {
                BlueprintDialog(title: loc("Model")) {
                    BlueprintButton(loc("Close"), tone: .quiet, minWidth: minTouch) { pickingModel = false }
                } content: {
                    ForEach(options) { option in
                        BlueprintRadioRow(option.label, selected: option == current) {
                            model.selectModel(option.id)
                            pickingModel = false
                        }
                    }
                }
            }
        #endif
    }

    private struct ModelOption: Hashable, Identifiable {
        let id: String
        let label: String
    }

    private func loc(_ key: String) -> String { RecKitStrings.localized(key) }
}

/// One of the section's two fields (docs/09 "Summary view"): its name, the box, and what it is for under it —
/// the vocabulary row's shape. Saved when editing ends — the focus leaves, Return on the one-line field, the
/// screen goes — with no Cancel · Save; empty is allowed. My format shows 4 lines, grows to 8 and then
/// scrolls; on the Mac, Return saves it there as in any field, and ⌥Return starts a new line.
private struct PreferenceField: View {
    let title: String
    @Binding var text: String
    let placeholder: String
    let note: String
    /// The core keeps no more than this (`SummaryPreferences`), so the field takes no more.
    let limit: Int
    let multiline: Bool
    let identifier: String
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
                .padding(.horizontal, 10)
                .padding(.vertical, 9)
                .background(blueprint.palette.surface, in: RoundedRectangle(cornerRadius: Radius.node))
                .overlay {
                    RoundedRectangle(cornerRadius: Radius.node)
                        .strokeBorder(focused ? blueprint.palette.accent : blueprint.palette.inputBorder, lineWidth: focused ? blueprint.line + 1 : blueprint.line)
                }
                .focused($focused)
                .onChange(of: text) { _, typed in
                    if typed.count > limit { text = String(typed.prefix(limit)) }
                }
                .onChange(of: focused) { _, now in if !now { done() } }
                .onSubmit(done)
                .onDisappear(perform: done)
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
        let prompt = Text(verbatim: placeholder).foregroundColor(blueprint.palette.textMuted)
        if multiline {
            TextField("", text: $text, prompt: prompt, axis: .vertical)
                .lineLimit(4...8)
        } else {
            TextField("", text: $text, prompt: prompt)
                #if os(iOS)
                .submitLabel(.done)
                #endif
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
