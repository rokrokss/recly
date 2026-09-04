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
                    workflows
                    about
                }
                .padding(.bottom, Space.l)
            }
            .frame(maxWidth: .infinity)
            .dotGridBackground()
            .navigationTitle(AppStrings.localized("Settings"))
            .navigationBarTitleDisplayMode(.inline)
        }
        // docs/03 "로그아웃 vs 연결 해제": the four things that are true of a disconnect and are not
        // true of a sign-out, before it happens rather than after.
        .blueprintDialog(item: $model.disconnectPrompt) { prompt in
            DisconnectDialog(
                prompt: prompt,
                device: .phone,
                confirm: { model.disconnect(alsoDeleteRecordings: $0) },
                cancel: { model.cancelDisconnect() },
                permissions: { model.openAccountPermissions() }
            )
        }
    }

    // MARK: - Account (docs/06)

    @ViewBuilder
    private var account: some View {
        section(loc("Google account"))
        if let account = model.account {
            SectionRow(title: account, subtitle: model.signInBlocker?.text) {
                // docs/06: this is `signOut()` and nothing else — it clears this phone's
                // credentials and leaves the grant, and so leaves every other device signed in.
                //
                // Held while a disconnect is owed: the retry reads the sign-in to tell a revoke
                // that happened from one that never did, and a sign-out would take it away.
                BlueprintButton(loc("Sign out"), tone: .quiet) { model.signOut() }
                    .disabled(model.disconnectPhase.owed)
                    .accessibilityIdentifier("signOut")
            }
            .accessibilityIdentifier("account")
        } else if model.canSignIn {
            // docs/03: while a disconnect still owes its local clean-up the row says so and the
            // button is off — signing in again would give the retry a *different* account's grant
            // to take away.
            SectionRow(title: loc("Signed out"), subtitle: model.signInBlocker?.text) {
                BlueprintButton(loc("Sign in with Google")) { model.signIn() }
                    .disabled(model.signInBlocker != nil)
                    .accessibilityIdentifier("signIn")
            }
        } else {
            // docs/06: `GIDSignIn` answers a placeholder client id with an Obj-C exception, so the
            // button is not offered at all — and a job that needed Drive is parked in `NEEDS_AUTH`
            // rather than failed.
            SectionRow(title: loc("The Google client ID is not set yet (see the README)."))
                .accessibilityIdentifier("notConfigured")
        }
        // docs/03 · docs/06: a second row and not a second meaning for the first one. Signing out
        // is this phone; disconnecting takes the grant away from every device.
        //
        // Offered without an account as well when a disconnect got the grant away and then failed
        // to clean this phone up: the keys and the queue are still here, and this row is the only
        // way to finish it (docs/03 "연결 해제").
        if model.account != nil || model.disconnectPhase.owed {
            SectionRow(
                title: loc("Disconnect"),
                subtitle: loc("Take this app’s access to your Google account away.")
            ) {
                BlueprintButton(loc("Disconnect"), tone: .danger) { model.askToDisconnect() }
                    .accessibilityIdentifier("disconnect")
            }
        }
        // docs/03: a revoke that failed leaves the grant standing, and it is Google's page — not
        // this app — that takes it down. So the row outlives the disconnect, the phase and even the
        // signed-in state, which is why it sits outside every block above; only the user closes it.
        if model.revokeDebt {
            SectionRow(
                title: DisconnectGuard.stillListed.text,
                subtitle: "myaccount.google.com/permissions"
            ) {
                HStack(spacing: Space.s) {
                    BlueprintButton(loc("Open Google account permissions")) {
                        model.openAccountPermissions()
                    }
                    BlueprintButton(DisconnectGuard.debtSettled.text, tone: .quiet) {
                        model.revokeDebtSettled()
                    }
                    .accessibilityIdentifier("revoke-debt-settled")
                }
            }
            .accessibilityIdentifier("revoke-debt")
        }
        if let note = model.authNote {
            hint(note, tone: .danger)
        }
        // docs/07 rule 3: what the last disconnect had to say, made into words here rather than
        // stored as them.
        if let message = model.message {
            hint(message.text, tone: .neutral)
        }
        hint(
            loc("Drive access: only the files this app creates (drive.file)."),
            tone: .neutral
        )
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
                subtitle: loc(
                    "A phone cannot tell a meeting from anything else, so it asks once, before the first recording."
                ),
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
                subtitle: loc("Never upload over mobile data."),
                isOn: $model.wifiOnly
            )
        }
    }

    // MARK: - Workflows (docs/05 "워크플로우 내보내기 · 가져오기")

    /// Definitions are this phone's own, so a file is how they reach another device. The same block
    /// the Mac's settings pane draws (RecKit). Nil until the core is open.
    @ViewBuilder
    private var workflows: some View {
        if let model = model.workflowTransfer {
            WorkflowTransferSection(model: model)
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
                mono("GoogleSignIn · AppAuth · GTMAppAuth · Kotlin · Ktor · SQLDelight — Apache-2.0")
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
