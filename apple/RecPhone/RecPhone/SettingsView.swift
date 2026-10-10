import RecKit
import SwiftUI

/// docs/09 screen principle 4: settings are a section table — account / language / theme / capture /
/// uploads / recording processing / privacy, and at the bottom the honest system block in monospace
/// (docs/09 trend 6: version, build, device id, open-source notices). The phone has no
/// launch-at-login to offer (docs/12's `SMAppService` is a Mac's) and no automatic recording
/// (ADR-011).
struct SettingsView: View {
    @ObservedObject var model: RecordingModel
    @ObservedObject var language: AppLanguage
    @ObservedObject var theme: AppTheme
    @Environment(\.blueprint) private var blueprint
    /// docs/07 rule 3: the rows below draw strings resolved outside SwiftUI, and reading the locale
    /// is what declares the dependency that redraws them when the language changes.
    @Environment(\.locale) private var locale
    @Environment(\.openURL) private var openURL

    var body: some View {
        // 2026-10-08 §12: the title is the screen's own header, above the scroll rather than a bar
        // over it — the other two tabs' header, and nothing scrolls under it.
        VStack(spacing: 0) {
            ScreenHeader(title: AppStrings.localized("Settings"))
            HairLine()
            ScrollView {
                VStack(spacing: 0) {
                    account
                    // docs/07 rule 2·3: the same block the Mac's settings pane draws, so it is
                    // drawn once (RecKit).
                    LanguageSection(language: language)
                    // docs/09 "Accessibility": the one override of the system's light/dark, and the same
                    // block the Mac's settings pane draws (RecKit).
                    ThemeSection(theme: theme)
                    microphone
                    uploads
                    processingSettings
                    chatGpt
                    privacy
                    about
                }
                .padding(.bottom, Space.l)
            }
        }
        .frame(maxWidth: .infinity)
        .dotGridBackground()
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
        // docs/09 "Summary view": the first sign-in on this phone, confirmed once.
        .chatGptWelcome(model.chatGpt)
        // docs/15 §10: the sign-in and the plan's models as they are now, whenever Settings opens.
        .task { await model.chatGpt?.refresh() }
        // docs/03 "Sign out vs Disconnect": the four things that are true of a disconnect and are not
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

    private func drive(_ showsHeader: Bool) -> DriveConnectionSection {
        DriveConnectionSection(
            account: model.account, connected: model.hasGoogleCredential,
            configured: model.canSignIn, pending: model.disconnectPhase.owed, disconnecting: model.disconnecting,
            revokeDebt: model.revokeDebt, blocker: model.signInBlocker?.text,
            signInState: model.signInState,
            signIn: model.signIn, disconnect: model.askToDisconnect,
            permissions: model.openAccountPermissions, debtSettled: model.revokeDebtSettled,
            showsHeader: showsHeader
        )
    }

    @ViewBuilder
    private var account: some View {
        // docs/03 "Storage location": the storage choice on top of the Drive rows, where this build can offer
        // iCloud; the Drive block as it always was where it cannot.
        if let storage = model.storage {
            StorageSection(
                choice: storage,
                drive: drive
            )
        } else {
            drive(true)
        }
        if let note = model.authNote {
            hint(note, tone: .danger)
        }
        // docs/07 rule 3: what the last disconnect had to say, made into words here rather than
        // stored as them — in the warning tone when it is what stands in the way (2026-10-10, 2.10).
        if let message = model.message {
            hint(message.text, tone: DisconnectGuard.isBlocker(message) ? .warning : .neutral)
        }

    }

    private var microphone: some View {
        Group {
            section(loc("Capture"))
            SectionRow(title: loc("Microphone")) {
                // Always here, so nothing to call attention to: the Record screen's own accent button is
                // the one a refusal brings (2026-09-29).
                BlueprintButton(loc("Open System Settings"), tone: .quiet) { model.openSettings() }
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
            ProcessingSettingsView(model: processing)
        }
    }

    // MARK: - ChatGPT (docs/15 §10)

    /// The same block the Mac's settings pane draws (RecKit).
    @ViewBuilder
    private var chatGpt: some View {
        if let chatGpt = model.chatGpt {
            ChatGptSection(model: chatGpt)
        }
    }

    private var privacy: some View {
        Group {
            section(RecKitStrings.localized("Privacy"))
            // docs/09 (2026-10-09): a web page, so the row is the link itself.
            SectionBlock {
                TextLink(RecKitStrings.localized("Privacy Policy")) {
                    openURL(PrivacyLinks.recly(locale: locale))
                }
                .accessibilityIdentifier("privacy-policy")
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

    // MARK: - About (docs/09 trend 6)

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
                mono("AppAuth · GTMAppAuth · FluidAudio · Kotlin · Ktor · NemoTextProcessing · SQLDelight — Apache-2.0")
                mono("fastcluster — BSD-2-Clause")
                // docs/09 "On-device speaker separation": the diarization models the app ships, and the
                // attribution CC-BY-4.0 asks for.
                mono("pyannote community-1 (pyannote · WeSpeaker · BUT Speech@FIT · Fluid Inference, converted to Core ML) — CC-BY-4.0")
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
            // 2026-10-10 (2.13): diagnostics read left to right in every language.
            .environment(\.layoutDirection, .leftToRight)
    }
}
