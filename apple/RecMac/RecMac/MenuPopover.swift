import AppKit
import ReclyCore
import RecKit
import SwiftUI

/// docs/09 screen principle 6: the menu-bar popover — three state nodes, the last five recordings as a
/// ledger, and the actions. docs/09 trend 7 puts glass on the *chrome* and nowhere else: the header
/// and the footer are `.ultraThinMaterial`, the ledger between them is the opaque surface, because
/// a list of data read through a blur is the thing that trend is against.
struct MenuPopover: View {
    @ObservedObject var model: MenuModel
    @ObservedObject var language: AppLanguage
    @ObservedObject var theme: AppTheme
    let maximumSize: CGSize
    @Environment(\.blueprint) private var blueprint
    @Environment(\.openWindow) private var openWindow
    /// docs/07 rule 3: this view draws strings that were resolved outside SwiftUI — a model's
    /// status line, a RecKit label — and `Text(verbatim:)` carries no dependency on the language.
    /// Reading the locale is what declares one, so a change redraws this body with the new words.
    @Environment(\.locale) private var locale

    @State private var showingSettings = false
    @State private var statusTrailingInsets: [String: CGFloat] = [:]
    @State private var expanded: String?

    var body: some View {
        // One height for the ledger and the settings: a popover that changed size on "Hide settings"
        // had its window follow a turn later (the panel resizes outside the layout pass that asked
        // for it — see `MenuBarPanel.scheduleFit`), and that frame flashed a different layout. Each
        // section still opens at its top: only the scrolled middle is rebuilt, never the header
        // and footer around it.
        MenuPanelContent(maximumSize: maximumSize, preferredContentHeight: 420, contentID: showingSettings) {
            header
            HairLine()
        } content: {
            if showingSettings {
                SettingsPane(model: model, language: language, theme: theme, surface: .popover)
            } else {
                ledger
            }
        } footer: {
            HairLine()
            footer
        }
        .background(blueprint.palette.surface)
        // A `LSUIElement` app has no window to hang a sheet off and the popover is the only surface
        // there is, so the dialogs are drawn *in* it — over the ledger, which is what they are
        // about — rather than as a panel the popover would be dismissed behind.
        .overlay {
            if let prompt = model.disconnectPrompt, model.disconnectSource == .popover {
                BlueprintDialogScrim {
                    DisconnectDialog(
                        prompt: prompt,
                        confirm: { model.disconnect(alsoDeleteRecordings: $0) },
                        cancel: { model.cancelDisconnect() }
                    )
                }
            }
        }
        // docs/03 "Deleting in the app": the same, for a delete started from a ledger row here. The
        // Transcripts window keeps its own sheet for the ones its rows ask — the ask carries the
        // surface it came from, so one question is never drawn on both.
        .overlay {
            if let ask = model.deleteRequest, ask.source == .popover {
                BlueprintDialogScrim {
                    DeleteDialog(request: ask.request, device: .mac) {
                        model.delete($0, deleteDrive: $1)
                    } cancel: {
                        model.cancelDelete()
                    }
                }
            }
        }
        // docs/09 "Summary view": the welcome after a first sign-in started here, over the popover like the
        // dialogs above.
        .chatGptWelcome(model.chatGpt, inline: true, shown: model.chatGptSurface == .popover)
        // docs/10: the fix for a quota or a key is in the settings window, and only a view has an
        // `openWindow` to open one with.
        .onAppear {
            model.openEditor = { openWindow(id: "processing-settings") }
        }
    }

    // MARK: - Chrome

    private var header: some View {
        VStack(spacing: 0) {
            // 2026-10-08 §2: the app's name and nothing under it — the device id is in Settings → About.
            ScreenHeader(title: "Recly")
            StateNodeRow(specs).padding(.horizontal, Space.m)
            // 2026-10-08 §12: a capture finding its way back is a wait, not a failure — the warning
            // tone; red stays for deletion and for the recording state.
            if model.isRecording, model.microphoneRecovering {
                Text(verbatim: loc("Reconnecting microphone…"))
                    .font(blueprint.fonts.monoSmall)
                    .foregroundStyle(BadgeTone.warning.ink(blueprint.palette))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, Space.m)
                    .padding(.top, Space.s)
            } else if model.isRecording, let device = model.capturedInputDevice {
                Text(AppStrings.localized("Microphone: %@", device))
                    .font(blueprint.fonts.monoSmall)
                    .foregroundStyle(blueprint.palette.textMuted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, Space.m)
                    .padding(.top, Space.s)
            }
            if model.isRecording, model.captureHealth != .healthy {
                Text(verbatim: loc(model.captureHealth == .failed
                    ? "System audio unavailable. Microphone recording continues."
                    : "Reconnecting system audio…"))
                    .font(blueprint.fonts.monoSmall)
                    .foregroundStyle(BadgeTone.warning.ink(blueprint.palette))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, Space.m)
                    .padding(.top, Space.s)
            }
            // docs/12 M4-L3 "Menu bar": which output device the system audio is being taken from,
            // while it is being taken. Nothing to say in microphone mode, so nothing is said.
            if model.isRecording, let device = model.capturedOutputDevice {
                Text(AppStrings.localized("System audio: %@", device))
                    .font(blueprint.fonts.monoSmall)
                    .foregroundStyle(blueprint.palette.textMuted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, Space.m)
                    .padding(.top, Space.s)
            }
            // docs/09 screen principle 1: while it is running, the timer *is* the dashboard — the same
            // full-width mono clock Windows and the iPhone draw, not a readout tucked in beside
            // the buttons. docs/09 screen principle 6 puts the track being written directly under it: the
            // answer to "is it hearing me" that a clock alone cannot give.
            // 2026-10-08 §4: and while the recording is being saved, at its final length — the
            // waveform goes with the capture.
            if model.isRecording || (model.state == .stopping && !model.elapsed.isEmpty) {
                VStack(spacing: Space.s) {
                    MonoTimer(model.elapsed, color: model.isRecording ? blueprint.palette.danger : nil)
                        .frame(maxWidth: .infinity)
                        .accessibilityIdentifier("elapsed")
                    if model.isRecording {
                        LiveWaveformView(peaks: model.livePeaks)
                            .frame(maxWidth: .infinity)
                            .accessibilityIdentifier("live-waveform")
                    }
                }
                .padding(.horizontal, Space.m)
                .padding(.top, Space.s)
            }
            HStack(spacing: Space.s) {
                if model.canStop {
                    BlueprintButton(loc("Stop recording"), tone: .danger) { model.stop() }
                        .keyboardShortcut(".")
                    // docs/12 "Menu bar app": a moment of the running recording, marked for the minutes.
                    if model.isRecording {
                        BlueprintButton(RecKitStrings.localized("Highlight")) { Task { await model.addHighlight() } }
                            .accessibilityIdentifier("highlight")
                    }
                } else {
                    BlueprintButton(loc("Start recording"), tone: .primary) { model.start() }
                        .disabled(!model.isReady)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, Space.m)
            .padding(.top, 12)
            .padding(.bottom, model.highlighted == nil ? 12 : Space.s)
            // The news of that moment, for two seconds — then the row is as it was.
            if let at = model.highlighted {
                Text(RecKitStrings.localized("Highlight · %@", at))
                    .font(blueprint.fonts.monoSmall)
                    .foregroundStyle(blueprint.palette.textMuted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, Space.m)
                    .padding(.bottom, 12)
            }
        }
        .background(.ultraThinMaterial)
    }

    private var specs: [NodeSpec] {
        [
            NodeSpec(label: loc("Device"), value: StateNodeWords.device(.desktop)),
            NodeSpec(label: RecKitStrings.localized("Transcription"), value: model.processingSummary),
            stateNode,
        ]
    }

    /// The recorder's own state comes first; `Recording` is never displaced. While it is idle and a job
    /// in the ledger is running, the node says `Uploading` with a turning loader instead of `Ready` —
    /// otherwise nothing above the list says the app is doing anything at all.
    private var stateNode: NodeSpec {
        if model.state == .idle, Recents.uploading(model.recents) {
            return NodeSpec(
                label: loc("State"),
                value: StateNodeWords.uploading,
                valueColor: blueprint.palette.accent,
                active: true,
                busy: true
            )
        }
        // 2026-10-08 §1b: a word, in monospace, and never colour alone.
        return NodeSpec(
            label: loc("State"),
            value: StateNodeWords.recorder(model.state),
            valueColor: model.isRecording ? blueprint.palette.danger : blueprint.palette.textMuted,
            active: model.isRecording
        )
    }

    private var footer: some View {
        HStack(spacing: Space.s) {
            BlueprintButton(
                showingSettings ? loc("Hide settings") : loc("Settings"),
                tone: .quiet
            ) {
                showingSettings.toggle()
            }
            .accessibilityIdentifier("menu-settings-toggle")
            Spacer(minLength: 0)
            // Not disabled while a recording is in flight — `⌘Q` reaches the app whether this
            // button is enabled or not, and the label is what says what the quit is about to do:
            // it waits for the stop to finalize and queue the recording (see `AppDelegate`).
            BlueprintButton(
                loc(model.isIdle ? "Quit" : "Save the recording and quit"),
                tone: .quiet
            ) {
                NSApplication.shared.terminate(nil)
            }
            .keyboardShortcut("q")
            .accessibilityIdentifier("menu-quit")
        }
        .padding(.horizontal, Space.m)
        .padding(.top, 10)
        // The popover rounds its own bottom corners over the content, so the last row of buttons
        // is given the room that costs.
        .padding(.bottom, 18)
        .background(.ultraThinMaterial)
    }

    // MARK: - The ledger (docs/09 screen principle 2 · docs/12 "Menu bar")

    private var ledger: some View {
        // Lazy, so the page marker under the rows appears only when it is scrolled to.
        LazyVStack(spacing: 0) {
            // docs/05 "Fixed processing settings": the first-run card, above the ledger, for a Mac set to
            // transcribe on device that has no speech model yet.
            if let download = model.modelDownload {
                ModelPromptCard(
                    download: download,
                    dismissed: model.modelPromptDismissed,
                    waiting: model.alerts.contains { $0.reason == .localModel }
                ) {
                    model.modelPromptDismissed = true
                }
            }
            // docs/10 "macOS": a banner at the top of the popover — the same lines the
            // notifications carry, one row per reason however many jobs are behind it, and the row
            // is the way to the screen that fixes it. It replaces the sign-in-only banner:
            // `NEEDS_AUTH` is one of the seven.
            AlertBanner(alerts: model.alerts, download: model.modelDownload) { model.fix($0) }
            LedgerHeader(
                time: loc("Time"),
                title: loc("Title"),
                length: loc("Length"),
                status: loc("Status")
            )
            ForEach(model.recents) { item in
                row(item)
            }
            // docs/12 "Menu bar": the ledger's next page, asked for when its end comes into view. Keyed
            // on the count so that a page that did not push it out of view asks again.
            if !model.recents.isEmpty {
                Color.clear
                    .frame(height: 1)
                    .id(model.recents.count)
                    .onAppear { Task { await model.loadMoreRecents() } }
            }
            if model.recents.isEmpty {
                Text("No recordings yet")
                    .font(blueprint.fonts.bodySmall)
                    .foregroundStyle(blueprint.palette.textMuted)
                    .padding(Space.l)
            }
            Text(verbatim: model.status)
                .font(blueprint.fonts.sans(TypeSize.small))
                .foregroundStyle(blueprint.palette.textMuted)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, Space.m)
                .padding(.vertical, Space.s)
        }
    }

    @ViewBuilder
    private func row(_ item: RecentItem) -> some View {
        let length = LedgerFormat.length(item.durationSec)
        LedgerRow(
            date: LedgerFormat.date(item.startedAt),
            time: LedgerFormat.time(item.startedAt),
            title: item.titleLabel,
            subtitle: "",
            length: length,
            status: item.badge,
            announce: LedgerFormat.announce(
                title: item.titleLabel,
                at: LedgerFormat.startedAt(item.startedAt),
                length: length,
                state: item.stateLabel
            ),
            // docs/09 "Accessibility": the row opens what is behind it, which a screen reader would
            // otherwise only find out by tapping — the same value and the same named action the
            // phone's row carries.
            expanded: expanded == item.id,
            // docs/09 "Motion": 200 ms ease-in-out, and nothing at all with reduce motion on — the
            // row simply is open.
            action: {
                withAnimation(Motion.standardAnimation(reduceMotion: blueprint.reduceMotion)) {
                    expanded = expanded == item.id ? nil : item.id
                }
            }
        )
        .onPreferenceChange(LedgerStatusTrailingInset.self) { statusTrailingInsets[item.id] = $0 }
        if expanded == item.id {
            VStack(alignment: .leading, spacing: Space.s) {
                // docs/08 "Polling · status": a transcription in flight has no "when", only how long it
                // has been waiting — the badge's RETRY would otherwise read as "stuck".
                if item.waitingMinutes != nil {
                    Text(verbatim: item.stateLabel)
                        .font(blueprint.fonts.sans(TypeSize.small))
                        .foregroundStyle(blueprint.palette.textMuted)
                }
                // docs/07 §5: what the core last said about this job, with its diagnostic under it
                // — the sentence translated, the diagnostic never. For a docs/08 "Errors" the
                // sentence is what to do next and the diagnostic is the provider's own words.
                // Red for a failure, the badge's warning tone for a job that is only waiting.
                if item.alert != .needsAuth {
                    RowReason(item: item, download: model.modelDownload)
                }
                // More buttons than a 460pt popover holds in one line, and a label cut to a
                // syllable says nothing — so they wrap onto a second line rather than becoming a
                // column.
                actions(item)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.leading, 78)
            .padding(.trailing, Space.m)
            .padding(.vertical, 10)
            .background(blueprint.palette.background)
        }
    }

    /// The things that can still be done about this recording, across the row and onto a second
    /// line when they do not fit.
    private func actions(_ item: RecentItem) -> some View {
        HStack(alignment: .top, spacing: Space.s) {
            FlowLayout {
                // docs/05 "Fixed processing settings": the model this recording waits for, in its own
                // language, first — and nothing while the download runs (the banner has it).
                if item.waitingForModel, let download = model.modelDownload {
                    ModelDownloadButton(download: download, language: item.localLanguage)
                        .accessibilityIdentifier("download-model")
                }
                if item.link != nil {
                    BlueprintButton(loc("Open in Drive")) { model.openInDrive(item) }
                }
                // docs/03 "Storage location": an iCloud or local folder recording has no web page; its folder
                // is in Finder.
                if item.cloudFolder != nil {
                    BlueprintButton(loc("Show in Finder")) { model.showInFinder(item) }
                        .accessibilityIdentifier("show-in-finder")
                }
                // docs/10: a retry is for a job that has stopped. One that is waiting out a backoff
                // comes back on its own `next_run_at`, and there is nothing to ask for.
                if item.canRetry {
                    ProcessingButton(loc("Retry"), state: model.action) { model.retry(item) }
                }
                // docs/08 AUTH_REJECTED: the key is entered in the recording processing settings, so
                // that is where "check the key" lands — which on a Mac means opening that window.
                if item.needsKey {
                    BlueprintButton(RecordingDetailStrings.checkKey) {
                        NSApp.activate(ignoringOtherApps: true)
                        openWindow(id: "processing-settings")
                    }
                    .accessibilityIdentifier("check-key")
                }
                // docs/08 "Result files": the transcript, in the window that fits it.
                BlueprintButton(RecordingDetailStrings.open) {
                    model.showDetail(item)
                    NSApp.activate(ignoringOtherApps: true)
                    openWindow(id: RecordingsWindow.id)
                }
                .accessibilityIdentifier("open-detail")
            }
            Spacer(minLength: 0)
            // docs/03: a recording being written to, imported, or arriving from the watch is not one
            // to delete ([RecentItem.canDelete]). An upload is: the core stops it first.
            //
            // The question is asked here, over the ledger it is about, the way the disconnect and
            // the import are: sending the user to another window to answer "delete this?" is the
            // app changing the subject in the middle of its own question.
            if item.canDelete {
                BlueprintButton(loc("Delete"), tone: .danger) {
                    model.confirmDelete(item, from: .popover)
                }
                .accessibilityIdentifier("delete")
                .fixedSize(horizontal: true, vertical: false)
                .padding(.trailing, statusTrailingInsets[item.id] ?? 0)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// docs/09 screen principle 4: the settings the menu used to carry, as a section table — account, language,
/// theme, capture, recording processing, the agent connection, and the honest system block at the bottom.
struct SettingsPane: View {
    @ObservedObject var model: MenuModel
    @ObservedObject var language: AppLanguage
    @ObservedObject var theme: AppTheme
    let surface: SettingsSurface
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale

    private func drive(_ showsHeader: Bool) -> DriveConnectionSection {
        DriveConnectionSection(
            account: model.account, connected: model.hasGoogleCredential,
            configured: model.canSignIn, pending: model.disconnectPhase.owed, disconnecting: model.disconnecting,
            revokeDebt: model.revokeDebt, blocker: model.signInBlocker?.text,
            signInState: model.signInState,
            signIn: model.signIn, disconnect: { model.askToDisconnect(from: surface) },
            permissions: model.openAccountPermissions, debtSettled: model.revokeDebtSettled,
            showsHeader: showsHeader
        )
    }

    var body: some View {
        VStack(spacing: 0) {
            // docs/03 "Storage location": the storage choice on top of the Drive rows, where this build can
            // offer iCloud or a local folder; the Drive block as it always was where it cannot.
            if let storage = model.storage {
                StorageSection(
                    choice: storage,
                    drive: drive
                )
            } else {
                drive(true)
            }

            // docs/07 rule 2·3: the same block the phone's settings tab draws, so it is drawn
            // once (RecKit).
            LanguageSection(language: language)

            // docs/09 "Accessibility": the one override of the system's light/dark, and the same block the
            // phone's settings tab draws (RecKit).
            ThemeSection(theme: theme)

            section(loc("Capture"))
            // docs/12 "Runner": `SMAppService`, written from the system's own answer.
            SwitchRow(
                title: loc("Launch at login"),
                isOn: Binding(get: { model.launchAtLogin }, set: model.setLaunchAtLogin)
            )
            // docs/12 M8: the reminder is on by default and this is where it goes off — and back
            // on, which the alert's own "Do not ask again" cannot do.
            SwitchRow(title: loc("Consent check before recording"), isOn: $model.consentReminder)

            // docs/05: the recording processing settings. The same block the phone's settings tab
            // draws (RecKit).
            if let processing = model.processing {
                ProcessingSettingsView(model: processing, settingsFile: false)
            }

            // docs/15 §10: the user's ChatGPT plan, for summaries — the same block the phone's settings tab
            // draws (RecKit).
            if let chatGpt = model.chatGpt {
                ChatGptSection(model: chatGpt) { model.chatGptSurface = surface }
            }

            AgentConnectionSection(
                agent: model.agentEvents, storage: model.storage, copyConfiguration: { await model.copyMCPConfiguration() }
            )

            // docs/09 screen principle 4: the settings file is a utility, so it comes after the
            // features, Agent connection included (2026-10-06).
            if let processing = model.processing {
                ProcessingSettingsFileSection(model: processing)
            }

            // docs/09 trend 6: no mascot and no "handmade" line — what this build actually is.
            section(loc("About"))
            VStack(alignment: .leading, spacing: 4) {
                mono("recly \(CoreBridge.appVersion) (build \(CoreBridge.appBuild)) · macos \(CoreBridge.systemVersion)")
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
        // docs/09 "Summary view": the welcome after a first sign-in started in the Settings window is that
        // window's sheet; one started in the popover is drawn over the popover (above).
        .chatGptWelcome(surface == .settingsWindow ? model.chatGpt : nil, shown: model.chatGptSurface == .settingsWindow)
        .task { await model.chatGpt?.refresh() }
        // docs/03 "Sign out vs Disconnect": the popover draws the warning over itself (above); the
        // Settings window has a window to present from, so a disconnect asked there is its sheet.
        .blueprintDialog(
            item: Binding(
                get: { surface == .settingsWindow && model.disconnectSource == surface ? model.disconnectPrompt : nil },
                // A sheet only ever writes nil back, and a dismissal is a cancel like any other.
                set: { if $0 == nil { model.cancelDisconnect() } }
            )
        ) { prompt in
            DisconnectDialog(
                prompt: prompt,
                confirm: { model.disconnect(alsoDeleteRecordings: $0) },
                cancel: { model.cancelDisconnect() }
            )
        }
    }

    private func section(_ title: String) -> some View {
        SectionHeader(title).padding(.horizontal, Space.m)
    }

    private func mono(_ text: String) -> some View {
        Text(verbatim: text)
            .font(blueprint.fonts.monoSmall)
            .foregroundStyle(blueprint.palette.textMuted)
            .textSelection(.enabled)
    }
}
