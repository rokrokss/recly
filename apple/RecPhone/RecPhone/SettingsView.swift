import RecKit
import SwiftUI

/// docs/09 화면 원칙 4: settings are a section table — account / capture / uploads / language / theme,
/// and at the bottom the honest system block in monospace (docs/09 트렌드 6: version, build, device
/// id, open-source notices). The phone has no launch-at-login to offer (docs/12's `SMAppService` is
/// a Mac's) and no automatic recording (ADR-011).
struct SettingsView: View {
    @ObservedObject var model: RecordingModel
    @ObservedObject var language: AppLanguage
    @ObservedObject var theme: AppTheme
    @Environment(\.blueprint) private var blueprint
    /// docs/07 rule 3: the rows below draw strings resolved outside SwiftUI, and reading the locale
    /// is what declares the dependency that redraws them when the language changes.
    @Environment(\.locale) private var locale

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 0) {
                    account
                    microphone
                    uploads
                    // docs/07 rule 2·3: the same block the Mac's settings pane draws, so it is
                    // drawn once (RecKit).
                    LanguageSection(language: language)
                    // docs/09 "접근성": the one override of the system's light/dark, and the same
                    // block the Mac's settings pane draws (RecKit).
                    ThemeSection(theme: theme)
                    processingSettings
                    privacy
                    about
                }
                .padding(.bottom, Space.l)
            }
            .frame(maxWidth: .infinity)
            .dotGridBackground()
            .navigationTitle(AppStrings.localized("Settings"))
            .navigationBarTitleDisplayMode(.inline)
        }
        .sheet(isPresented: $model.privacyPresented) {
            if let privacy = model.transferPrivacy {
                NavigationStack {
                    TransferPrivacyView(model: privacy)
                        .toolbar {
                            ToolbarItem(placement: .confirmationAction) {
                                Button(RecKitStrings.localized("Close")) { model.privacyPresented = false }
                            }
                        }
                }
            }
        }
        // docs/03 "로그아웃 vs 연결 해제": the four things that are true of a disconnect and are not
        // true of a sign-out, before it happens rather than after.
        .blueprintDialog(item: $model.disconnectPrompt) { prompt in
            DisconnectDialog(
                prompt: prompt,
                confirm: { model.disconnect(alsoDeleteRecordings: $0) },
                cancel: { model.cancelDisconnect() }
            )
        }
    }

    // MARK: - Account (docs/06)

    @ViewBuilder
    private var account: some View {
        DriveConnectionSection(
            account: model.account, connected: model.hasGoogleCredential,
            configured: model.canSignIn, pending: model.disconnectPhase.owed, disconnecting: model.disconnecting,
            revokeDebt: model.revokeDebt, blocker: model.signInBlocker?.text,
            signIn: model.signIn, disconnect: model.askToDisconnect,
            permissions: model.openAccountPermissions, debtSettled: model.revokeDebtSettled
        )
        if let note = model.authNote {
            hint(note, tone: .danger)
        }
        // docs/07 rule 3: what the last disconnect had to say, made into words here rather than
        // stored as them.
        if let message = model.message {
            hint(message.text, tone: .neutral)
        }

    }

    private var microphone: some View {
        Group {
            section(loc("Capture"))
            SectionRow(title: loc("Microphone")) {
                BlueprintButton(loc("Open System Settings")) { model.openSettings() }
            }
            // docs/12 M8: the Mac asks before every meeting recording. A phone has no meeting
            // detection, so it asks once before the first one — and the subtitle says so, because a
            // reminder that behaves differently on two devices has to explain itself. This is also
            // the only way back on once the dialog's own "Do not ask again" has been used.
            SwitchRow(
                title: loc("Consent reminder"),
                isOn: $model.consentReminder
            )
            .accessibilityIdentifier("consent-reminder")
        }
    }

    // MARK: - Uploads (docs/11 A5)

    /// One switch, because there is one question: may a recording leave over mobile data. Android
    /// asks WorkManager for `UNMETERED`; here it is the two flags on the upload *request*
    /// ([UploadNetwork]), which the next chunk reads — the one already on the wire keeps the
    /// network it left on, exactly as a WorkManager constraint is re-read only on the next enqueue.
    private var uploads: some View {
        Group {
            section(loc("Uploads"))
            SwitchRow(
                title: loc("Upload on Wi-Fi only"),
                isOn: $model.wifiOnly
            )
        }
    }

    // MARK: - Recording processing (docs/05)

    /// The same block the Mac's settings pane draws (RecKit). Nil until the core is open.
    @ViewBuilder
    private var processingSettings: some View {
        if let processing = model.processing {
            ProcessingSettingsView(model: processing, preparationAllowed: model.state == .idle)
        }
    }

    private var privacy: some View {
        Group {
            section(RecKitStrings.localized("Privacy"))
            SectionRow(title: RecKitStrings.localized("Privacy Policy")) {
                Link(RecKitStrings.localized("Open"), destination: PrivacyLinks.recly(locale: locale))
            }
            if model.transferPrivacy != nil {
                SectionRow(title: RecKitStrings.localized("Allowed destinations")) {
                    BlueprintButton(RecKitStrings.localized("Review transfers")) {
                        model.privacyPresented = true
                    }
                    .accessibilityIdentifier("transfer-privacy")
                }
            }
        }
    }

    // MARK: - About (docs/09 트렌드 6)

    /// No mascot and no "handmade" line: what the bottom of a settings screen owes the user is what
    /// this build actually is, in monospace, so it can be read out over a support thread.
    private var about: some View {
        Group {
            section(loc("About"))
            VStack(alignment: .leading, spacing: 4) {
                mono("recly \(CoreBridge.appVersion) (build \(CoreBridge.appBuild)) · ios \(CoreBridge.systemVersion)")
                mono("device \(model.deviceId)")
                Text(verbatim: loc("Open-source notices"))
                    .font(blueprint.fonts.sans(TypeSize.small))
                    .foregroundStyle(blueprint.palette.textMuted)
                mono("AppAuth · GTMAppAuth · Kotlin · Ktor · SQLDelight — Apache-2.0")
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, Space.m)
            .padding(.vertical, 12)
        }
    }

    // MARK: - Pieces

    private func section(_ title: String) -> some View {
        SectionHeader(title).padding(.horizontal, Space.m)
    }

    private func hint(_ text: String, tone: BadgeTone) -> some View {
        Text(verbatim: text)
            .font(blueprint.fonts.sans(TypeSize.small))
            .foregroundStyle(tone.ink(blueprint.palette))
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, Space.m)
            .padding(.vertical, Space.s)
    }

    private func mono(_ text: String) -> some View {
        Text(verbatim: text)
            .font(blueprint.fonts.monoSmall)
            .foregroundStyle(blueprint.palette.textMuted)
            .textSelection(.enabled)
    }
}
