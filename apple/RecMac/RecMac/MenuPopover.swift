import AppKit
import ReclyCore
import RecKit
import SwiftUI

/// docs/09 화면 원칙 6: the menu-bar popover — three state nodes, the last five recordings as a
/// ledger, and the actions. docs/09 트렌드 7 puts glass on the *chrome* and nowhere else: the header
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
        MenuPanelContent(maximumSize: maximumSize, preferredContentHeight: showingSettings ? 420 : 280) {
            header
            HairLine()
        } content: {
            if showingSettings {
                SettingsPane(model: model, language: language, theme: theme)
            } else {
                ledger
            }
        } footer: {
            HairLine()
            footer
        }
        // Each section opens at its top; a settings scroll offset must not carry into the ledger.
        .id(showingSettings)
        .background(blueprint.palette.surface)
        // A `LSUIElement` app has no window to hang a sheet off and the popover is the only surface
        // there is, so the dialogs are drawn *in* it — over the ledger, which is what they are
        // about — rather than as a panel the popover would be dismissed behind.
        .overlay {
            if let prompt = model.disconnectPrompt {
                BlueprintDialogScrim {
                    DisconnectDialog(
                        prompt: prompt,
                        confirm: { model.disconnect(alsoDeleteRecordings: $0) },
                        cancel: { model.cancelDisconnect() }
                    )
                }
            }
        }
        // docs/03 "앱에서 지우기": the same, for a delete started from a ledger row here. The
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
        // docs/05 "워크플로우 가져오기": the replace confirmation, drawn here for the same reason —
        // the settings pane is inside this popover and has no window of its own to present from.
        .overlay {
            if let transfer = model.workflowTransfer, let picked = transfer.confirm {
                BlueprintDialogScrim {
                    ImportDialog(
                        picked: picked,
                        confirm: { Task { await transfer.confirmImport() } },
                        cancel: { transfer.cancelImport() }
                    )
                }
            }
        }
        // docs/10: the fix for a quota or a webhook is in the editor, and only a view has an
        // `openWindow` to open one with.
        .onAppear {
            model.openEditor = { openWindow(id: WorkflowWindow.id) }
            model.refreshMicrophones()
        }
    }

    // MARK: - Chrome

    private var header: some View {
        VStack(spacing: 0) {
            ScreenHeader(
                title: "Recly",
                meta: "\(Source.desktop.name.lowercased()) · \(model.deviceId.prefix(8))"
            )
            StateNodeRow(specs).padding(.horizontal, Space.m)
            if model.isRecording, model.microphoneRecovering {
                Text(verbatim: loc("Reconnecting microphone…"))
                    .font(blueprint.fonts.monoSmall)
                    .foregroundStyle(blueprint.palette.danger)
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
            if model.isRecording, model.mode == .meeting, model.captureHealth != .healthy {
                Text(verbatim: loc(model.captureHealth == .failed
                    ? "System audio unavailable. Microphone recording continues."
                    : "Reconnecting system audio…"))
                    .font(blueprint.fonts.monoSmall)
                    .foregroundStyle(blueprint.palette.danger)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, Space.m)
                    .padding(.top, Space.s)
            }
            // docs/12 M4-L3 "메뉴바": which output device the system audio is being taken from,
            // while it is being taken. Nothing to say in microphone mode, so nothing is said.
            if model.isRecording, let device = model.capturedOutputDevice {
                Text(AppStrings.localized("System audio: %@", device))
                    .font(blueprint.fonts.monoSmall)
                    .foregroundStyle(blueprint.palette.textMuted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, Space.m)
                    .padding(.top, Space.s)
            }
            // docs/09 화면 원칙 1: while it is running, the timer *is* the dashboard — the same
            // full-width mono clock Windows and the iPhone draw, not a readout tucked in beside
            // the buttons. docs/09 화면 원칙 6 puts the track being written directly under it: the
            // answer to "is it hearing me" that a clock alone cannot give.
            if model.isRecording {
                VStack(spacing: Space.s) {
                    MonoTimer(model.elapsed, color: blueprint.palette.danger)
                        .frame(maxWidth: .infinity)
                        .accessibilityIdentifier("elapsed")
                    LiveWaveformView(peaks: model.livePeaks)
                        .frame(maxWidth: .infinity)
                        .accessibilityIdentifier("live-waveform")
                }
                .padding(.horizontal, Space.m)
                .padding(.top, Space.s)
            }
            HStack(spacing: Space.s) {
                if model.canStop {
                    BlueprintButton(loc("Stop recording"), tone: .danger) { model.stop() }
                        .keyboardShortcut(".")
                } else {
                    BlueprintButton(loc("Start recording"), tone: .primary) { model.start() }
                        .disabled(!model.isReady)
                }
                BlueprintButton(loc("Workflows")) {
                    NSApp.activate(ignoringOtherApps: true)
                    openWindow(id: WorkflowWindow.id)
                }
                Spacer(minLength: 0)
                // Idle, the empty half of the row is the workflow picker's; while a recording runs
                // the pick cannot change anyway, and the space is the timer's above.
                if !model.isRecording {
                    workflowPicker
                }
            }
            .padding(.horizontal, Space.m)
            .padding(.vertical, 12)
        }
        .background(.ultraThinMaterial)
    }

    /// The one choice on this surface: which workflow this Mac records with (ADR-016 — the pick is
    /// this Mac's own pointer, so it outlives the popover and the launch).
    ///
    /// Chips rather than a `Picker(.menu)`: docs/09 "형태" has no pop-up button in it, and what a
    /// pop-up hid was the one thing this row is for — which workflow the next recording runs. They
    /// wrap when there are more of them than the popover is wide, and the whole row scrolls
    /// sideways rather than pushing the actions off it.
    private var workflowPicker: some View {
        ScrollView(.horizontal) {
            FlowLayout {
                ForEach(model.workflows, id: \.id) { workflow in
                    BlueprintChip(workflow.name, selected: model.workflowId == workflow.id) {
                        Task { await model.selectWorkflow(workflow.id) }
                    }
                }
            }
        }
        .scrollIndicators(.never)
        .frame(maxWidth: 220)
        .disabled(model.workflows.isEmpty)
        .accessibilityIdentifier("workflow")
    }

    private var specs: [NodeSpec] {
        [
            NodeSpec(label: loc("Device"), value: Source.desktop.name.lowercased()),
            NodeSpec(label: loc("Workflow"), value: workflowName),
            stateNode,
        ]
    }

    /// The recorder's own state comes first; `REC` is never displaced. While it is idle and a job in
    /// the ledger is running, the node says `UPLOADING` with a turning loader instead of `IDLE` —
    /// otherwise nothing above the list says the app is doing anything at all.
    private var stateNode: NodeSpec {
        if model.state == .idle, Recents.uploading(model.recents) {
            return NodeSpec(
                label: loc("State"),
                value: "UPLOADING",
                valueColor: blueprint.palette.accent,
                active: true,
                busy: true
            )
        }
        return NodeSpec(
            label: loc("State"),
            value: stateCode,
            valueColor: model.isRecording ? blueprint.palette.danger : blueprint.palette.textMuted,
            active: model.isRecording
        )
    }

    /// The node names the workflow this Mac is set to record with — the one chip that is selected.
    ///
    /// ADR-016 · docs/09 화면 원칙 1: no selection is one line in all four shells, whether the
    /// document is empty or the pointer named a workflow that is gone. Both say the same thing about
    /// pressing start — nothing would run — and name the same fix.
    private var workflowName: String {
        if let selected = model.workflows.first(where: { $0.id == model.workflowId }) {
            return selected.name
        }
        return loc("Choose a workflow")
    }

    /// docs/09: state is a code, in monospace, and never colour alone.
    private var stateCode: String {
        switch model.state {
        case .idle: return "IDLE"
        case .starting: return "STARTING"
        case .recording: return "REC"
        case .stopping: return "STOPPING"
        }
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

    // MARK: - The ledger (docs/09 화면 원칙 2 · docs/12 "메뉴바")

    private var ledger: some View {
        // Lazy, so the page marker under the rows appears only when it is scrolled to.
        LazyVStack(spacing: 0) {
            // docs/10 "macOS": 팝오버 상단 배너 — the same lines the notifications carry, one row per
            // reason however many jobs are behind it, and the row is the way to the screen that
            // fixes it. It replaces the sign-in-only banner: `NEEDS_AUTH` is one of the seven.
            AlertBanner(alerts: model.alerts) { model.fix($0) }
            LedgerHeader(
                time: loc("Time"),
                title: loc("Title"),
                length: loc("Length"),
                status: loc("Status")
            )
            ForEach(model.recents) { item in
                row(item)
            }
            // docs/12 "메뉴바": the ledger's next page, asked for when its end comes into view. Keyed
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
            subtitle: item.savedLocally ? RecKitStrings.localized("Saved on this device") : "",
            length: length,
            status: item.badge,
            announce: LedgerFormat.announce(
                title: item.titleLabel,
                at: LedgerFormat.startedAt(item.startedAt),
                length: length,
                state: item.stateLabel
            ),
            // docs/09 "접근성": the row opens what is behind it, which a screen reader would
            // otherwise only find out by tapping — the same value and the same named action the
            // phone's row carries.
            expanded: expanded == item.id,
            // docs/09 "모션": 200 ms ease-in-out, and nothing at all with reduce motion on — the
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
                // docs/08 "폴링 · 상태": a transcription in flight has no "when", only how long it
                // has been waiting — the badge's RETRY would otherwise read as "stuck".
                if item.waitingMinutes != nil {
                    Text(verbatim: item.stateLabel)
                        .font(blueprint.fonts.sans(TypeSize.small))
                        .foregroundStyle(blueprint.palette.textMuted)
                }
                // docs/07 §5: what the core last said about this job, with its diagnostic under it
                // — the sentence translated, the diagnostic never. For a docs/08 "오류" the
                // sentence is what to do next and the diagnostic is the provider's own words.
                if item.alert != .needsAuth, let reason = item.reason {
                    Text(verbatim: reason.sentence)
                        .font(blueprint.fonts.sans(TypeSize.small))
                        .foregroundStyle(blueprint.palette.danger)
                    if let detail = reason.detail {
                        Text(verbatim: detail)
                            .font(blueprint.fonts.monoSmall)
                            .foregroundStyle(blueprint.palette.textMuted)
                    }
                }
                // More buttons than a 460pt popover holds in one line, and a label cut to a
                // syllable says nothing — so they wrap onto a second line, the way the workflow
                // chips above them do, rather than becoming a column.
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
                if item.link != nil {
                    BlueprintButton(loc("Open in Drive")) { model.openInDrive(item) }
                }
                // docs/10: a retry is for a job that has stopped. One that is waiting out a backoff
                // comes back on its own `next_run_at`, and there is nothing to ask for.
                if item.canRetry {
                    ProcessingButton(loc("Retry"), state: model.action) { model.retry(item) }
                }
                // docs/08 AUTH_REJECTED: the key is defined in the workflow, so that is where "check
                // the key" lands — which on a Mac means opening the editor window as well.
                if item.needsKey {
                    BlueprintButton(RecordingDetailStrings.checkKey) {
                        model.editWorkflow(of: item)
                        NSApp.activate(ignoringOtherApps: true)
                        openWindow(id: WorkflowWindow.id)
                    }
                    .accessibilityIdentifier("check-key")
                }
                // docs/08 "결과 파일": the transcript, in the window that fits it.
                BlueprintButton(RecordingDetailStrings.open) {
                    model.showDetail(item)
                    NSApp.activate(ignoringOtherApps: true)
                    openWindow(id: RecordingsWindow.id)
                }
                .accessibilityIdentifier("open-detail")
            }
            Spacer(minLength: 0)
            // docs/03: a recording being written to, arriving from the watch, or uploaded right now
            // — here or on the device that made it — is not one to delete ([RecentItem.canDelete]).
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

/// docs/09 화면 원칙 4: the settings the menu used to carry, as a section table — account, capture,
/// language, and the honest system block at the bottom.
private struct SettingsPane: View {
    @ObservedObject var model: MenuModel
    @ObservedObject var language: AppLanguage
    @ObservedObject var theme: AppTheme
    @Environment(\.blueprint) private var blueprint
    @Environment(\.locale) private var locale

    var body: some View {
        VStack(spacing: 0) {
            DriveConnectionSection(
                account: model.account, connected: model.hasGoogleCredential,
                configured: model.canSignIn, pending: model.disconnectPhase.owed, disconnecting: model.disconnecting,
                revokeDebt: model.revokeDebt, blocker: model.signInBlocker?.text,
                signIn: model.signIn, disconnect: model.askToDisconnect,
                permissions: model.openAccountPermissions, debtSettled: model.revokeDebtSettled
            )

            // docs/12 M4-L3 "메뉴바": the mode is picked before a recording and cannot change
            // during one — the track set is written into the meta at `start`.
            section(loc("Capture"))
            SectionRow(title: loc("Recording mode")) {
                HStack(spacing: Space.s) {
                    BlueprintChip(loc("Microphone only"), selected: model.mode == .microphone) {
                        model.mode = .microphone
                    }
                    BlueprintChip(
                        loc("Meeting (mic + system)"),
                        selected: model.mode == .meeting
                    ) {
                        model.mode = .meeting
                    }
                }
                // The chips carry the choice, so they keep their whole label; when the popover is
                // short of room it is the title on the left that wraps, not the chips that truncate.
                .fixedSize(horizontal: true, vertical: false)
                .disabled(model.canStop)
            }
            SectionRow(title: loc("Microphone")) {
                ScrollView(.horizontal) {
                    HStack(spacing: Space.s) {
                        BlueprintChip(loc("Automatic"), selected: model.microphoneUID.isEmpty) {
                            model.microphoneUID = ""
                        }
                        ForEach(model.microphones) { device in
                            BlueprintChip(device.name, selected: model.microphoneUID == device.id) {
                                model.microphoneUID = device.id
                            }
                        }
                    }
                }
                .scrollIndicators(.never)
                .frame(maxWidth: 220)
                .disabled(model.canStop)
                .accessibilityIdentifier("microphone-selection")
            }
            // docs/12 "실행기": `SMAppService`, written from the system's own answer.
            SwitchRow(
                title: loc("Launch at login"),
                isOn: Binding(get: { model.launchAtLogin }, set: model.setLaunchAtLogin)
            )
            // docs/12 M8: the reminder is on by default and this is where it goes off — and back
            // on, which the alert's own "Do not ask again" cannot do.
            SwitchRow(title: loc("Consent check before recording"), isOn: $model.consentReminder)

            // docs/07 rule 2·3: the same block the phone's settings tab draws, so it is drawn
            // once (RecKit).
            LanguageSection(language: language)

            // docs/09 "접근성": the one override of the system's light/dark, and the same block the
            // phone's settings tab draws (RecKit).
            ThemeSection(theme: theme)

            // docs/05 "워크플로우 내보내기 · 가져오기": definitions are this Mac's own, so a file is
            // how they reach another device. The same block the phone's settings tab draws (RecKit).
            if let transfer = model.workflowTransfer {
                WorkflowTransferSection(model: transfer)
            }

            // docs/09 트렌드 6: no mascot and no "handmade" line — what this build actually is.
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
