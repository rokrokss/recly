#if os(iOS) || os(macOS)
import ReclyCore
import SwiftUI

/// docs/09 "AI view" · docs/15 §10: the ChatGPT section, after Recording processing — the plan the user
/// already pays for, on the same rows and buttons the Drive account has: its sign-in, its usage and its model, and
/// nothing about what the notes are asked for, which is Settings → Notes' ([NotesSection]) — so another AI provider
/// can take this section's place (2026-10-28). Nothing at all where ChatGPT is not offered.
public struct ChatGptSection: View {
    @ObservedObject private var model: ChatGptSettingsModel
    /// Told before a sign-in starts, so a shell drawn on two surfaces knows which one to welcome on.
    private let onSignIn: (() -> Void)?
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
            SectionFootnote(loc("Summaries and questions send the transcript text, your notes and your summary settings to OpenAI — never the audio — and count toward your ChatGPT plan’s usage."))
        }
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
            VStack(alignment: .leading, spacing: Space.xxs) {
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

/// The one-time confirmation after the first sign-in on a device (docs/09 "AI view"). The shells present it
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
    /// The welcome over this view, once [model] says so — after the consent to organize when the sign-in asks for it
    /// (docs/08 "Organizing"), which goes first. [inline] draws it in the view, for a surface with no
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
            if shown, model.organizeConsent {
                BlueprintDialogScrim { consent }
            } else if shown, model.welcome {
                BlueprintDialogScrim { ChatGptWelcomeDialog { model.welcome = false } }
            }
        } else {
            Color.clear.blueprintDialog(isPresented: Binding(
                get: { shown && (model.organizeConsent || model.welcome) },
                set: { if !$0 { model.organizeConsent = false; model.welcome = false } }
            )) {
                // One sheet: the consent, then the welcome in its place.
                if model.organizeConsent {
                    consent
                } else {
                    ChatGptWelcomeDialog { model.welcome = false }
                }
            }
        }
    }

    private var consent: some View {
        OrganizeConsentDialog(permission: model.organizePermission) { answer in
            Task { await model.answerOrganizeConsent(answer) }
        }
    }
}
#endif
