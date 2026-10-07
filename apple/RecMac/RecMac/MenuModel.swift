import AppKit
import Foundation
import os
import ReclyCore
import RecKit
import ServiceManagement
import SwiftUI
import UniformTypeIdentifiers

/// One `CoreBridge` for the life of the process (docs/01), built on first launch of the menu, plus
/// the one recorder that owns the microphone. Everything the menu shows is published from here.
@MainActor
final class MenuModel: ObservableObject {
    /// One for the process. `NSApplicationDelegateAdaptor` builds the delegate independently of the
    /// scene, and the quit path and the menu have to be asking the same recorder — a second model
    /// would mean `⌘Q` finalizing a recording the menu does not know about, or none at all.
    static let shared = MenuModel()

    /// What the recorder says it is doing. The menu is drawn from this and nothing else: a second
    /// `isRecording` kept alongside it is a second answer to the same question, and the one on
    /// screen would be whichever of the two was written last.
    @Published private(set) var state: RecorderState = .idle
    let playbackGate = RecordingPlaybackGate()
    /// The line the menu shows when nothing is being recorded — opening the core, what went wrong,
    /// what a stop left behind.
    ///
    /// docs/07 rule 3: a *key*, resolved by [status] where the menu draws it, so a note already on
    /// screen follows a language change. [AppStrings.localized] hands a sentence it does not know
    /// back unchanged.
    @Published private(set) var note = "Opening" {
        // The message belongs to the note it was set with; a new note has none until it says so.
        didSet { message = nil }
    }
    /// docs/07 rule 3: what a delete or a disconnect had to say, which is RecKit's message
    /// rather than a key of this app's — kept as the message and resolved by [status], for the same
    /// reason [note] is kept as a key. Named as the phone's own slot is, because it is the same one.
    ///
    /// `@Published` because [status] is drawn from it: a finished operation writes only this, and
    /// a menu that was not told would go on showing the note it replaced.
    @Published private(set) var message: UiMessage?
    @Published private(set) var processing: ProcessingSettingsModel?
    /// docs/03 "Storage location": Google Drive, the app's iCloud folder or a local folder, for the settings'
    /// storage block.
    @Published private(set) var storage: StorageChoice?
    /// docs/05 "Fixed processing settings": the one speech-model download, shared by the settings row, the
    /// banner, a waiting recording's row and the popover's first-run card.
    @Published private(set) var modelDownload: ModelDownload?
    /// docs/09 screen principle 2: the waveform of a recording this Mac finalized, decoded in the background
    /// and kept, so the Details window opens it with the bars already there.
    private var waveforms: WaveformPrecompute?
    /// "Not now" on the first-run card, remembered on this Mac so the card does not come back.
    @Published var modelPromptDismissed: Bool = Defaults.modelPromptDismissed {
        didSet { Defaults.modelPromptDismissed = modelPromptDismissed }
    }
    private var capturedProcessingKey: String?
    private var capturedProcessingProvider: String?
    var processingSummary: String {
        if isRecording { return capturedProcessingProvider ?? RecKitStrings.localized(capturedProcessingKey ?? "On device") }
        return processing?.providerSummary ?? RecKitStrings.localized(processing?.summaryKey ?? "On device")
    }
    @Published private(set) var elapsed = ""
    /// False until the core is open: there is nothing to start a recording against before that.
    @Published private(set) var isReady = false
    /// The output device the tap is on, while a meeting is being recorded (docs/12 deliverable 5).
    @Published private(set) var capturedOutputDevice: String?
    @Published private(set) var capturedInputDevice: String?
    @Published private(set) var microphoneRecovering = false
    @Published private(set) var captureHealth: CaptureHealth = .healthy
    /// docs/12 "Menu bar": the newest recordings, refreshed after every executor pass — a page of
    /// [Recents.page] to begin with, and a page more each time the ledger is scrolled to its last
    /// row ([loadMoreRecents]).
    @Published private(set) var recents: [RecentItem] = []
    @Published private(set) var recentsLoading = true
    /// How many rows this device has, whichever device made them — the Details window's count,
    /// which the paged [recents] cannot give.
    @Published private(set) var recordingCount = 0
    /// How deep [recents] reads: grows by a page as the ledger is scrolled, never shrinks.
    private var recentsLimit = Recents.page
    private var loadingMoreRecents = false
    /// docs/10: the user-fixable failures across the queue, folded one line per reason — the
    /// popover's banner, the menu bar icon's error state, and the local notifications.
    @Published private(set) var alerts: [JobAlert] = []
    /// Non-nil while the docs/03 delete dialog is up — the question *and* the surface it was asked
    /// from, which is why it is one value and not a request beside a flag (see [DeleteAsk]).
    @Published private(set) var deleteRequest: DeleteAsk?
    /// Non-nil while the docs/03 disconnect warning is up, with the count it has to name.
    @Published var disconnectPrompt: DisconnectPrompt?
    /// Where that warning was asked from, and so where it is drawn. Written with the prompt, never
    /// before its count is read — see [DeleteAsk].
    @Published private(set) var disconnectSource: SettingsSurface = .popover
    /// docs/06: how far the last disconnect got, and so whether one is still owed. Stored, because
    /// a relaunch is the most likely place the retry happens from — the account is gone by then and
    /// this is the only thing that keeps the Disconnect row on screen.
    @Published private(set) var disconnectPhase = DisconnectDefaults.phase
    /// docs/03: true while Google is still listing Recly because the revoke failed. It outlives the
    /// disconnect, the phase and the account — only the user's own word clears it.
    @Published private(set) var revokeDebt = DisconnectDefaults.revokeDebt
    /// The signed-in Google account, or nil.
    @Published private(set) var account: String?
    /// docs/09 screen principle 5: where the settings' Connect is, which its button shows in place — "…"
    /// while the sign-in runs, ✓ when Drive is connected, and back to itself when it was not.
    @Published private(set) var signInState: ProcessingState = .idle
    /// docs/08 Result files: the recording the transcript window is showing, once one is picked.
    @Published private(set) var detail: RecordingDetailModel?
    /// docs/12 "Runner": `SMAppService`. Written from the system's own answer, never from the
    /// request — a registration the system refused must not leave a checked menu item behind.
    @Published private(set) var launchAtLogin = SMAppService.mainApp.status == .enabled
    /// docs/12 M8: the consent question, once before the first meeting recording, and switchable
    /// off from the menu.
    @Published var consentReminder: Bool = Defaults.consentReminder {
        didSet { Defaults.consentReminder = consentReminder }
    }
    /// docs/09 trend 2: where the one operation a popover row can start — an upload now, a retry —
    /// actually is. `ProcessingButton` owns the window around it; this owns the truth.
    @Published private(set) var action: ProcessingState = .idle
    /// docs/09 screen principle 1·4: this install, for the popover's header. Empty until the core is open,
    /// which is the only thing that knows it.
    @Published private(set) var deviceId = ""

    /// docs/12 "Agent connection": recly-events, run for the user while its switch is on. Nil
    /// executable — a build made without Go — and the switch says so.
    let agentEvents = AgentEventsController(executable: Bundle.main.url(forAuxiliaryExecutable: "recly-events"))

    private let logger = Logger(subsystem: "app.recly.mac", category: "shell")
    private var bridge: CoreBridge?
    private var recorder: SegmentedRecorder?
    private var session: RecorderSession?
    private var ticker: Timer?
    /// docs/06: the core's `TokenProvider`, and the sign-in that fills it.
    private let tokens = AppleTokenProvider()
    private var auth: GoogleAuth?
    private var runner: JobRunner?
    /// docs/07 rule 3: Notification Center draws its own text and keeps it, so a language change
    /// has to be carried to it rather than waiting for the next meeting.
    private var languageObserver: NSObjectProtocol?
    private let notifier = MeetingNotifier()
    /// docs/10 "macOS": the `UNUserNotificationCenter` notifications, down the same path the
    /// meeting detection uses. A process has one notification delegate and [notifier] is already
    /// it, so this one does not take it — the meeting notifier forwards what it does not recognise.
    ///
    /// Built with the model rather than lazily at the first reading of the queue: [MeetingNotifier]
    /// has to be able to forward a response to it before the core is open, because a notification
    /// tapped from a cold launch is delivered as soon as the launch finishes.
    private let alertNotifier = JobAlertNotifier(subsystem: "app.recly.mac")
    /// Where that tap waits while there is no screen to take it to (docs/10).
    private let alertRouter = AlertRouter<JobAlert>()
    /// And where the *meeting* offer's own tap waits. Same reason, other notification: the offer is
    /// the one thing on the Lock Screen that is most likely to be opened from a cold launch, and
    /// [act(on:)] needs a recorder that only [load] makes.
    private let meetingRouter = AlertRouter<MeetingNotifier.Action>()
    /// docs/03 "Disconnect" · docs/06: the whole of a disconnect, which is RecKit's and not this
    /// model's — the phone runs the same one. Lazy because every one of its closures reads `self`.
    private lazy var disconnectFlow = DisconnectFlow(
        device: .mac,
        logger: logger,
        core: { [weak self] in self?.bridge?.core },
        auth: { [weak self] in self?.auth },
        // A capture that is running has no job yet, so `core.disconnect`'s own busy guard — which
        // is over the queue — does not cover it.
        isRecording: { [weak self] in self?.isIdle == false },
        phase: { [weak self] in self?.disconnectPhase ?? .none },
        debt: { [weak self] in self?.revokeDebt ?? false },
        publishPhase: { [weak self] in self?.disconnectPhase = $0 },
        publishDebt: { [weak self] in self?.revokeDebt = $0 },
        publishMessage: { [weak self] in self?.message = $0 },
        accountChanged: { [weak self] in self?.account = $0 },
        accountRevoked: { [weak self] in self?.account = nil },
        refresh: { [weak self] in await self?.refreshRecents() }
    )
    /// docs/12 "Meeting detection". Built lazily because it closes over `self`.
    private lazy var detector = MeetingDetector { [weak self] prompt in
        // The detector documents this callback as the main queue, which is where the notification
        // and the recording both have to happen anyway.
        MainActor.assumeIsolated { self?.detected(prompt) }
    }
    private lazy var quit = TerminationGate { [weak self] in
        // No title prompt on the way out: the user asked to quit, and a modal that keeps the app
        // alive to ask for a name is the opposite of that. The recording is finalized and queued
        // exactly as a recovered one is, and it can be named from the list later.
        await self?.finish(askingForTitle: false)
    }

    private init() {
        // docs/10 "macOS": the job alerts share Notification Center with the meeting offers, and a
        // process has one delegate — [MeetingNotifier] took it in its own init, and this is the
        // other end of that. Wired here and not in [load] for the same reason the delegate itself
        // is taken here: a notification tapped from a cold launch is delivered as the app comes up,
        // and a forward installed once the database is open is one installed too late. The tap then
        // waits in [alertRouter] until there is an editor to take it to.
        notifier.forward = { [weak self] response in
            self?.alertNotifier.handle(response: response) ?? false
        }
        alertNotifier.onFix = { [weak self] alert in self?.alertRouter.deliver(alert) }
        // The same argument for the meeting offer itself: "Start recording" tapped on a notification
        // that woke the app was dropped, because [onAction] was only wired once the core was open.
        // It waits in [meetingRouter] until there is a recorder to serve it.
        notifier.onAction = { [weak self] action in self?.meetingRouter.deliver(action) }
        observeLanguage()
        Task { await load() }
    }

    /// docs/10: Notification Center's delegate, installed from `RecMacApp.init` — the earliest this
    /// shell runs any code of its own, and long before the core is open. `@StateObject` builds its
    /// initial value at the first body, which is after the system has already delivered the tap
    /// that opened the app; building this model here is what puts [notifier] and the forward behind
    /// it in place first. Idempotent.
    func installNotificationDelegate() {
        notifier.adoptDelegate()
    }

    /// docs/12 M2: open the core, finish whatever the last run left behind, then offer to record.
    private func load() async {
        do {
            let bridge = try await CoreBridge.make(tokenProvider: tokens)
            self.bridge = bridge
            // docs/12 "Echo": the microphone decides voice processing itself, at each start.
            let recorder = SegmentedRecorder(core: bridge.core) { [weak self] error in
                self?.captureFailed(error)
            }
            self.recorder = recorder
            recorder.preferMicrophone(nil)
            let recovery = RecordingRecovery(core: bridge.core)
            let session = RecorderSession(
                capture: recorder,
                recover: { await recovery.reconcile() },
                onState: { [weak self] state in
                    Task { @MainActor in self?.adopt(state) }
                },
                setPlaybackBlocked: { [playbackGate] blocked in
                    await playbackGate.setBlocked(blocked)
                }
            )
            self.session = session
            // Before anything else can touch the directories: a part the last run could not file is
            // still on disk, and a recording left open is one nothing would ever act on.
            let recovered = await session.recoverIfIdle()
            let download = ModelDownload(core: bridge.core)
            download.capturing = !isIdle
            // The recordings the download resumed are due now.
            download.onFinished = { [weak self] in self?.runner?.jobsDue() }
            await download.refresh()
            modelDownload = download
            let waveforms = WaveformPrecompute(core: bridge.core)
            waveforms.capturing = !isIdle
            self.waveforms = waveforms
            let processing = ProcessingSettingsModel(core: bridge.core, download: download)
            await processing.reload()
            processing.onSaved = { [weak self] in
                self?.objectWillChange.send()
                self?.runner?.jobsDue()
                Task { await download.refresh() }
            }
            self.processing = processing
            let storage = StorageChoice(core: bridge.core)
            storage.onChanged = { [weak self, weak processing, weak storage] in
                await processing?.storageChanged()
                // docs/12 "Agent connection": recly-events can watch Google Drive only.
                if let storage { self?.agentEvents.driveStorage = storage.selected == .drive }
            }
            // The recordings a picked folder let go are due now.
            storage.onFolderPicked = { [weak self] in self?.runner?.jobsDue() }
            self.storage = storage
            // docs/12 "Agent connection": recly-events runs on this Mac's own Drive connection — its
            // short-lived access token, never the refresh token (docs/recly.md §15 §9).
            agentEvents.driveConnected = { [weak self] in self?.hasGoogleCredential ?? false }
            agentEvents.driveToken = { [tokens] in try? await tokens.__accessToken() }
            agentEvents.driveStorage = (try? await bridge.core.processingSettings.storage()).map { $0 == .drive } ?? true
            observeJobs(core: bridge.core)
            observeRecordings(core: bridge.core)
            // There is a screen for a tap to land on now, so whatever came in while the core was
            // opening is served (docs/10).
            //
            // A tap on the model notification opens the settings that carry the download, as it
            // always has; the banner's own button is what starts it.
            alertRouter.connect { [weak self] alert in
                if alert.reason.fix == .modelDownload { self?.openEditor?() } else { self?.fix(alert) }
            }
            // Before the executor: a Drive credential restored from Keychain is what decides
            // whether the first pass can do anything at all (docs/06).
            let auth = GoogleAuth(tokens: tokens)
            self.auth = auth
            await auth.restore()
            account = auth.account
            // The Drive connection recly-events runs on is known now, not at the next poll.
            agentEvents.refreshNow()
            let runner = JobRunner(
                queue: CoreJobQueue(core: bridge.core),
                onPass: { [weak self] _ in self?.passFinished() }
            )
            self.runner = runner
            // docs/12 "Runner" (b)·(c), plus a pass now: a job left parked by the last run is due.
            runner.start()
            detector.start()
            deviceId = bridge.deps.device.deviceId
            isReady = true
            note = "Waiting"
            // docs/12 "Meeting detection": only once there is something to record with — and only once
            // [isReady], because `start` refuses before it (Sol P1-apple r3). The notification's
            // button starts a recording, and a button that cannot is worse than no notification —
            // so a tap that arrived before now was kept, and is served here.
            meetingRouter.connect { [weak self] action in self?.act(on: action) }
            applyShortcut()
            // The device id identifies this install and the data directory carries the user's home
            // directory — neither belongs in a log anyone can read off the machine. Counts are what
            // the line is actually for.
            logger.info(
                """
                shell.ready device=\(bridge.deps.device.deviceId, privacy: .private) \
                dataDir=\(bridge.dataDirectory.path, privacy: .private) \
                recovered=\(recovered, privacy: .public)
                """
            )
        } catch {
            note = "Core error"
            // A database-open failure puts the file's path in the message.
            logger.error("shell.failed error=\(String(describing: error), privacy: .private)")
        }
    }

    /// The ledger while a pass is still running. `onPass` only fires once `runDueJobs` has returned,
    /// and the core claims a job `RUNNING` and carries the whole upload out inside that one call —
    /// so a ledger fed by the pass alone goes straight from `PENDING` to `DONE` and the State node
    /// says `IDLE` for the length of an upload. A job row moving is what moves the badge and the
    /// node through `UPLOADING`, and the core writes that row as it happens.
    private func observeJobs(core: ReclyCore_) {
        Task { [weak self] in
            for await _ in core.jobs.observe() {
                guard let self else { return }
                await self.refreshRecents()
            }
        }
    }

    /// docs/03: a recording another Mac or phone made and uploaded arrives as a row with no job at
    /// all, so nothing about the queue moves when a pull adopts one — or drops one whose Drive
    /// folder is gone. The recordings table is what changes, and this is what reads it.
    private func observeRecordings(core: ReclyCore_) {
        Task { [weak self] in
            for await _ in core.recordings.observe() {
                guard let self else { return }
                await self.refreshRecents()
            }
        }
    }

    var isRecording: Bool {
        if case .recording = state { return true }
        return false
    }

    /// Nothing in flight — no recording to lose if the app goes away now.
    var isIdle: Bool { state == .idle }

    /// The stop is offered while the microphone is still opening, too — the session parks it and
    /// serves it the moment there is a recording, which is the whole point of parking it. While a
    /// stop is already running there is nothing left to offer.
    var canStop: Bool {
        switch state {
        case .starting, .recording: return true
        case .idle, .stopping: return false
        }
    }

    /// The menu's first line. The state has the say whenever a recording is in flight; [note] is
    /// what is left to show when there is none.
    var status: String {
        RecorderStatusLine.text(state: state, note: note, message: message)
    }

    /// The menu bar icon: the app mark's 22-point monochrome template (docs/09 "App icon"), so the
    /// status item is the same shape as the launcher icon. The idle one is a template image and
    /// AppKit paints it in the menu bar's own colour; the recording one is red (docs/12 "Status icon") and a template would lose that, so it is an ordinary image with a light and a dark
    /// variant in the catalog instead — the appearance is what the menu bar hands it.
    /// docs/10 "macOS": menu bar icon error state. A job the user has to do something about puts a mark
    /// on the icon — the same square badge the ledger draws, in the corner the recording tint does
    /// not use. Recording wins: a recording in progress is the more urgent thing to be told, and
    /// the queue's news is one click away in the popover either way.
    ///
    /// docs/09 "Every state is color + text": the mark is a *shape* added to the icon rather than a tint
    /// of it, and the accessibility description says which state it is in — an icon that only
    /// changed colour would say nothing at all to VoiceOver or in a monochrome menu bar. The marked
    /// icon stays a template for the same reason the plain one is: the menu bar paints it in its
    /// own colour, so it reads on a dark menu bar as well as a light one.
    var icon: NSImage {
        let name = isRecording ? "MenuBarIconRecording" : "MenuBarIcon"
        let base = NSImage(named: name) ?? NSImage(size: NSSize(width: 22, height: 22))
        let image = alerts.isEmpty || isRecording ? base : Self.marked(base)
        image.accessibilityDescription = alerts.isEmpty
            ? "Recly"
            : "Recly — \(alerts[0].reason.label)"
        return image
    }

    /// The badge: a filled square in the bottom-right of the 22pt icon, set off from the mark by a
    /// cleared margin so it reads as a second shape and not a corner of the first. Drawn rather
    /// than a second asset, because the composite has to stay a template: a template is alpha
    /// only, and the menu bar tints it — a fixed colour would be black on a dark menu bar.
    private static func marked(_ base: NSImage) -> NSImage {
        let size = base.size
        let marked = NSImage(size: size, flipped: false) { rect in
            base.draw(in: rect)
            let side = max(6, rect.width / 3)
            let badge = NSRect(x: rect.maxX - side, y: rect.minY, width: side, height: side)
            NSColor.clear.setFill()
            badge.insetBy(dx: -badgeGap, dy: -badgeGap).fill(using: .copy)
            NSColor.black.setFill()
            NSBezierPath(roundedRect: badge, xRadius: Radius.badge, yRadius: Radius.badge).fill()
            return true
        }
        marked.isTemplate = true
        return marked
    }

    /// The cleared margin around the badge, in points.
    private static let badgeGap: CGFloat = 1

    /// Every desktop start captures the microphone and system audio with automatic routing, with
    /// the fixed recording processing settings (docs/05).
    func start() {
        guard let session, isReady else { return }
        let mode = RecordingMode.meeting
        // docs/03: before the question, not after it — a disconnect's clean-up walks the recording
        // directory, and there is nothing to ask about a capture that is about to be refused.
        if let blocker = DisconnectGate.startBlocker() {
            message = blocker
            logger.info("shell.recording.start.refused reason=disconnecting")
            return
        }
        // docs/12 M8: before the recording — telling the participants after the fact is not
        // telling them, and this is the one prompt the user can answer "no" to.
        guard askAboutConsentIfNeeded(mode: mode) else { return }
        Task {
            do {
                // docs/03: the gate is held across the start itself. The check `start` made before
                // this task says nothing about the moment the capture actually opens — a disconnect
                // that took the gate inside that wait would be walking the recording directory
                // while this capture wrote into it. A start that finds it held is refused rather
                // than queued.
                let opened = try await DisconnectGate.ifOpen {
                    try await session.start(mode: mode)
                }
                guard let started = opened else {
                    // The gate was shut. By a disconnect — which is what to say — or by another
                    // start already opening, which the session would have refused anyway.
                    if let blocker = DisconnectGate.startBlocker() {
                        message = blocker
                        logger.info("shell.recording.start.refused reason=disconnecting")
                    }
                    return
                }
                // `nil` means the session was not idle — a second click, or a stop still finishing.
                // The state it published already says so; there is nothing to tell the user.
                guard let recordingId = started else { return }
                logger.info("shell.recording.start id=\(recordingId, privacy: .public)")
                // docs/12 "End detection" is about *this* recording, and only a meeting has one: a
                // microphone-only memo's own idle microphone is not a meeting that has ended.
                detector.recordingChanged(mode == .meeting)
            } catch let error as RecorderError where error.kind == .microphoneDenied {
                note = "The microphone permission is needed"
                presentMicrophoneDenied()
            } catch let error as RecorderError where error.kind == .systemAudioUnavailable {
                note = "System audio permission is needed"
                logger.error("shell.recording.start.tap error=\(error.description, privacy: .public)")
                presentSystemAudioUnavailable()
            } catch {
                note = "The recording failed"
                logger.error("shell.recording.start.failed error=\(String(describing: error), privacy: .public)")
            }
        }
    }

    /// docs/03: the title is asked for *after* the recording has ended, and the job is only queued
    /// once the answer is in — `updateTitle` refuses a recording whose job has already read the meta.
    func stop() {
        // Through the gate, so that a `⌘Q` pressed while this is running waits for it.
        quit.finish { [weak self] in await self?.finish(askingForTitle: true) }
    }

    /// docs/12: `⌘Q` is one keystroke away at all times, and quitting mid-recording the way any
    /// other app would leaves a row saying `recording` and a segment with no trailing MPEG-4 atoms
    /// in it — the crash case, entered deliberately. So the quit waits for the stop instead.
    func terminate() -> NSApplication.TerminateReply {
        switch quit.decide(state, then: { NSApplication.shared.reply(toApplicationShouldTerminate: true) }) {
        case .now: return .terminateNow
        case .later: return .terminateLater
        }
    }

    private func finish(askingForTitle: Bool) async {
        guard let session else { return }
        // A stop while the microphone is still opening is parked inside the session and served
        // the moment there is a recording to stop; one while a stop is already running is
        // `.notRecording`, and there is nothing to say about it.
        switch await session.stop(title: nil) {
        case .notRecording:
            break

        case .deferred(let recordingId, let pending):
            // Not finalized on purpose: there is nothing to name and nothing to queue until the
            // missing parts are filed, which the next recovery pass does. How many is the log's to
            // say, not the menu's.
            note = "Could not finish saving — it is recovered on the next run"
            logger.error("shell.recording.deferred id=\(recordingId, privacy: .public) pending=\(pending, privacy: .public)")

        case .finalized(let outcome):
            if askingForTitle {
                switch askForTitle() {
                case .save(let title, let participants):
                    if title != nil || participants != nil {
                        _ = try? await bridge?.core.recordings.updateTitle(
                            recordingId: outcome.recordingId,
                            title: title,
                            participants: participants.map { KotlinInt(int: Int32($0)) }
                        )
                    }
                case .discard, nil:
                    if detail?.recordingId == outcome.recordingId { detail = nil }
                    guard let core = bridge?.core else {
                        note = "Could not discard the recording"
                        return
                    }
                    let result = await RecordingDeletion.delete(
                        core: core, recordingId: outcome.recordingId, deleteDrive: false
                    )
                    switch result {
                    // The user asked for it to go, so its going is not news: back to idle.
                    case .deleted, .notFound: note = "Waiting"
                    case .busy, .unavailable: note = "Could not discard the recording"
                    }
                    await refreshRecents()
                    return
                }
            }
            // The core compiles the fixed plan from the settings the recording froze (docs/05).
            //
            // The failure is caught rather than swallowed as `try?`, which is what the phone
            // already did: a recording whose job could not be made is one nothing will ever upload,
            // and the menu said "Waiting" over it.
            do {
                _ = try await bridge?.core.enqueue(recordingId: outcome.recordingId)
                // docs/12 "Runner" (a): the job exists now, so a pass runs immediately rather than
                // waiting for the five-minute timer.
                runner?.jobsDue()
                waveforms?.enqueue(recordingId: outcome.recordingId)
                note = "Waiting"
                logger.info(
                    """
                    shell.recording.stop id=\(outcome.recordingId, privacy: .public) \
                    parts=\(outcome.parts, privacy: .public) \
                    durationSec=\(outcome.durationSec, privacy: .public)
                    """
                )
            } catch {
                note = "Could not create the job"
                logger.error(
                    "shell.recording.enqueue.failed error=\(String(describing: error), privacy: .public)"
                )
            }
        }
    }

    /// The one place the published state moves, so the clock the menu shows starts and stops with
    /// the recording rather than with whoever asked for it.
    private func adopt(_ next: RecorderState) {
        let wasRecording = isRecording
        state = next
        modelDownload?.capturing = next != .idle
        waveforms?.capturing = next != .idle
        if isRecording, !wasRecording {
            capturedProcessingKey = processing?.summaryKey
            capturedProcessingProvider = processing?.providerSummary
            startTicking()
        }
        if !isRecording, wasRecording {
            stopTicking()
            capturedOutputDevice = nil
        capturedInputDevice = nil
        captureHealth = .healthy
            // Whatever ended it — the menu, `⌘Q`, a fatal capture error — the detector is back to
            // looking for the next meeting rather than for the end of this one.
            detector.recordingChanged(false)
        }
    }

    /// A fatal capture error ends the recording the way the menu's own stop does: there is nobody
    /// to ask for a title, so what was captured is filed as it stands. A boundary that could not be
    /// registered is not fatal — the encoder is still running and the part is on disk.
    ///
    /// A start that is still in flight is not abandoned: the session parks the stop and serves it
    /// the moment the recording exists.
    private func captureFailed(_ error: RecorderError) {
        logger.error("shell.recording.error fatal=\(error.fatal, privacy: .public) \(error.description, privacy: .public)")
        if error.fatal { stop() }
    }

    // MARK: - Meeting detection (docs/12 "Meeting detection", ADR-011)

    /// The detector has decided something is worth saying. ADR-011: detect → confirm → record, so
    /// both prompts are a notification and nothing else — a recording never starts on its own.
    private func detected(_ prompt: MeetingDetectionRule.Prompt) {
        switch prompt {
        case .start:
            guard isIdle, isReady else { return }
            logger.info("detect.meeting")
            notifier.post(.start)

        case .stop:
            // Never a stop of its own (docs/12 "End detection": never an automatic stop).
            guard isRecording else { return }
            logger.info("detect.meeting.idle")
            notifier.post(.stop)
        }
    }

    /// docs/07 rule 3: a notification is drawn once and then stands in Notification Center in
    /// whatever language it was posted in — and its button's title was fixed at registration. So a
    /// language change re-registers the categories and posts the offers again.
    private func observeLanguage() {
        languageObserver = NotificationCenter.default.addObserver(
            forName: AppLanguage.didChange,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                Task { await self.notifier.relocalize() }
                // A job alert already standing in Notification Center was painted once and is still
                // in the old language; posting it again under the same identifier replaces it.
                Task { await self.alertNotifier.relocalize() }
            }
        }
    }

    /// The notification's button.
    private func act(on action: MeetingNotifier.Action) {
        switch action {
        case .start: start()
        case .stop: stop()
        }
    }

    // MARK: - Quick start and highlights (docs/12 "Menu bar app")

    /// ⌥⌘R from any app, on unless the user turned it off in Settings → Capture.
    @Published var shortcutEnabled: Bool = Defaults.shortcut {
        didSet {
            Defaults.shortcut = shortcutEnabled
            applyShortcut()
        }
    }
    /// The system refused ⌥⌘R: another app holds it.
    @Published private(set) var shortcutRefused = false
    private lazy var shortcut = GlobalShortcut { [weak self] in self?.toggleRecording() }

    private func applyShortcut() {
        shortcutRefused = !shortcut.set(enabled: shortcutEnabled)
        if shortcutRefused { logger.info("shell.shortcut.refused") }
    }

    /// An App Intent can launch the app; it waits for the core to open — at most ten seconds — rather
    /// than be dropped by a start that refuses before [isReady].
    func whenReady() async {
        var waited = 0
        while !isReady, waited < 100 {
            try? await Task.sleep(for: .milliseconds(100))
            waited += 1
        }
    }

    /// The shortcut's one action: a stop while something is recording or opening, a start otherwise.
    func toggleRecording() {
        if canStop { stop() } else if isIdle { start() }
    }

    /// The moment the user marked, in the running recording's own time — the popover's `Highlight`,
    /// and the `Add Highlight` App Intent. Nil when nothing is recording or the mark was not added (a
    /// second one within a second is ignored).
    @discardableResult
    func addHighlight() async -> String? {
        guard case .recording(let recordingId) = state, let recorder, let core = bridge?.core else { return nil }
        let atSec = recorder.recordedSec
        guard (try? await core.recordings.addHighlight(recordingId: recordingId, atSec: atSec).boolValue) == true else {
            return nil
        }
        logger.info("shell.highlight id=\(recordingId, privacy: .public)")
        let at = LedgerFormat.clock(Int(atSec))
        highlighted = at
        return at
    }

    /// The time of the last highlight, while the popover says so (two seconds, as on the watches).
    @Published private(set) var highlighted: String? {
        didSet {
            guard let highlighted else { return }
            Task { [weak self] in
                try? await Task.sleep(for: .seconds(2))
                if self?.highlighted == highlighted { self?.highlighted = nil }
            }
        }
    }

    // MARK: - Sign-in (docs/06)

    /// A Google credential may have no profile email.
    var hasGoogleCredential: Bool {
        auth?.restoration == .restored(hasCredential: true)
    }

    /// False when the build has no usable Google client configuration.
    var canSignIn: Bool { GoogleAuth.isConfigured }

    /// docs/03: why the popover's sign-in is refused, or nil when it is not. A second account signed
    /// in over a disconnect that still owes its local clean-up is one the retry could revoke by
    /// mistake, so the sign-in is what waits.
    var signInBlocker: UiMessage? { DisconnectGuard.signInBlocker(pending: disconnectPhase.owed) }

    func signIn() {
        if let blocker = signInBlocker {
            message = blocker
            return
        }
        guard let auth else { return }
        signInState = .processing
        Task {
            // Reuse the app's window as the authentication anchor; a browser handoff needs no
            // additional instruction window. A cold notification may have no visible window yet.
            let existing = NSApp.keyWindow ?? NSApp.mainWindow ?? NSApp.windows.first(where: \.isVisible)
            let anchor = existing ?? Self.makeAuthAnchor()
            defer { if existing == nil { anchor.close() } }
            do {
                account = try await auth.signIn(presenting: anchor)
                // What the last disconnect said is over once Drive is connected again.
                message = nil
                signInState = .done
                logger.info("auth.signIn.ok")
                // docs/06: a job parked in NEEDS_AUTH resumes when the user signs in.
                await unpark()
            } catch GoogleAuth.Failure.canceled {
                // The user closed the consent sheet. Nothing failed, so nothing is said.
                signInState = .failed
                logger.info("auth.signIn.canceled")
            } catch {
                signInState = .failed
                note = "Sign-in failed"
                logger.error("auth.signIn.failed error=\(String(describing: error), privacy: .public)")
                presentAuthFailure(error)
            }
        }
    }

    /// docs/06: the account slot belongs to a disconnect until it has finished. A plain sign-out
    /// after `REVOKE_PENDING` would delete the sign-in the retry reads to tell "revoke again" from
    /// "already revoked", and the grant would stand with no debt recorded.
    func signOut() {
        if let blocker = signInBlocker {
            message = blocker
            return
        }
        Task {
            await auth?.signOut()
            account = nil
            logger.info("auth.signOut")
        }
    }

    /// Every job that was only waiting for a sign-in goes back to `PENDING`, then one pass.
    private func unpark() async {
        guard let core = bridge?.core else { return }
        _ = try? await core.pullRemoteRecordings(force: true)
        await ParkedJobs.unpark(core: core)
        runner?.jobsDue()
    }

    // MARK: - Recent recordings (docs/12 "Menu bar")

    /// A pass has finished: the ledger is redrawn from the queue it left behind, and the banner,
    /// the menu bar icon and the notifications are folded out of the same reading ([publishAlerts]).
    /// docs/10 replaced the sign-in-only line this used to raise — `NEEDS_AUTH` is one of its seven.
    private func passFinished() {
        Task { await refreshRecents() }
    }

    private func refreshRecents() async {
        guard let core = bridge?.core else { return }
        defer { recentsLoading = false }
        do {
            recents = try await Recents.load(core: core, limit: recentsLimit)
            recordingCount = try await core.recordings.ids().count
        } catch {
            logger.error("shell.recents.failed error=\(String(describing: error), privacy: .private)")
        }
        await publishAlerts()
    }

    /// docs/12 "Menu bar": the ledger's end was scrolled into view. A reading that filled its window
    /// may have older rows behind it, so the window grows by a page and is read again; one that
    /// came back short already had everything, and nothing is asked.
    func loadMoreRecents() async {
        guard !loadingMoreRecents, recents.count >= recentsLimit else { return }
        loadingMoreRecents = true
        defer { loadingMoreRecents = false }
        recentsLimit += Recents.page
        await refreshRecents()
    }

    /// docs/03: what the other devices have uploaded since this one last looked. The ledger itself
    /// is drawn from what is already here — this runs beside it and never in front of it, and the
    /// rows a pull adopts (or drops) come back through [observeRecordings] whenever Drive answers.
    ///
    /// `force`, because the popover being opened is the user asking: the throttle is for the pass.
    func pullRemoteRecordings() async {
        guard let core = bridge?.core else { return }
        _ = try? await core.pullRemoteRecordings(force: true)
    }

    // MARK: - The failures a person has to fix (docs/10)

    /// docs/10 rule 3: what is standing is whatever this reading of the queue says, empty included
    /// — a reason that has been cleared is withdrawn from Notification Center by the same call that
    /// takes it off the popover's banner and the menu bar icon. Every reading goes through here.
    ///
    /// The *queue*, and not [recents]: the ledger is a window onto the newest recordings, so a job
    /// blocked before those had its banner line, its menu bar badge and its notification taken away
    /// by nothing more than a page of newer recordings — while it was still blocked.
    private func publishAlerts() async {
        guard let core = bridge?.core else { return }
        do {
            alerts = JobAlerts.fold(try await JobAlerts.sources(core: core))
        } catch {
            // The queue could not be read. Whatever is standing stays standing: withdrawing on a
            // failed read would take a notification down for a job that is still parked.
            logger.error("shell.alerts.failed error=\(String(describing: error), privacy: .private)")
            return
        }
        await alertNotifier.publish(alerts)
    }

    /// docs/10: "A tap goes to the screen that can fix it. It does not end at 'Open app'." On a
    /// `LSUIElement` Mac that means the settings window — [openEditor] is set by the scene, which
    /// is the only thing that can open one.
    func fix(_ alert: JobAlert) {
        switch alert.reason.fix {
        case .privacy:
            NSWorkspace.shared.open(PrivacyLinks.recly(locale: .current))
        case .signIn:
            signIn()

        case .driveStorage:
            openDriveStorage()

        // docs/03 "Storage location": the iCloud uploads parked for space, asked again once there is some.
        case .retryUploads:
            for item in recents where item.alert == alert.reason { retry(item) }

        // docs/08 "Errors": the key is the thing to look at, and it is entered in the recording
        // processing settings.
        case .secrets, .editor:
            openEditor?()

        // docs/05 "Fixed processing settings": downloaded where the banner stands, not in the settings.
        case .modelDownload:
            modelDownload?.start()
        }
    }

    /// docs/10 "Drive out of space": the one fix that leaves the app, because the space is Google's to
    /// give back. Offered on the ledger row as well as on the banner.
    func openDriveStorage() {
        NSWorkspace.shared.open(driveStorageURL)
    }

    /// How a window gets opened from a model: `openWindow` is an environment value and only a view
    /// has one, so the scene hands its own down (`MenuPopover`).
    var openEditor: (() -> Void)?

    // MARK: - Deleting a recording (docs/03 "Deleting in the app")

    /// The dialog is asked every time, because the Drive half of it is a separate question whose
    /// answer is never remembered. The part count is read here rather than carried on every row.
    ///
    /// [source] is where it was asked from, and it is written into the question rather than kept
    /// beside it — see [DeleteAsk].
    ///
    /// Only the newest ask becomes the question. The count is a trip to the core, so two presses in
    /// a row are two reads in flight, and the slower one finishing last would otherwise replace the
    /// dialog the user is looking at with the row they left behind.
    func confirmDelete(_ item: RecentItem, from source: DeleteAsk.Source) {
        guard let core = bridge?.core else { return }
        deleteAsked += 1
        let asked = deleteAsked
        Task {
            let unuploaded = await Retention.unuploadedParts(core: core, recordingId: item.id)
            // Another device's recording is Drive's; this Mac's own may not have a folder yet.
            let storage = item.remote ? item.storage : await Retention.storage(core: core, recordingId: item.id)
            guard asked == self.deleteAsked else { return }
            deleteRequest = DeleteAsk(
                request: DeleteRequest(
                    recordingId: item.id,
                    title: item.titleLabel,
                    unuploaded: unuploaded,
                    remote: item.remote,
                    hasDriveFolder: item.hasCloudFolder,
                    icloud: storage == .icloud,
                    folder: storage == .folder
                ),
                source: source
            )
        }
    }

    /// The answer that deletes nothing, from either surface — and the answer to a question that was
    /// never asked, which is what a dismissal is while a count is still being read: the counter goes
    /// up, so the read that comes back has nothing left to ask about.
    func cancelDelete() {
        deleteAsked += 1
        deleteRequest = nil
    }

    /// How many deletes have been asked about, ever. Not a count anybody reads — it is the generation
    /// [confirmDelete] carries across its await to tell its own answer from a newer one's.
    private var deleteAsked = 0

    /// docs/03: local always, Drive only when the user asked for it — and a Drive that refused does
    /// not undo the local deletion, so what is left to say is that the folder is still there.
    func delete(_ request: DeleteRequest, deleteDrive: Bool) {
        cancelDelete()
        // The transcript window may be showing the recording that is about to stop existing.
        if detail?.recordingId == request.recordingId { detail = nil }
        perform {
            guard let core = self.bridge?.core else { return false }
            let outcome = await RecordingDeletion.delete(
                core: core,
                recordingId: request.recordingId,
                deleteDrive: deleteDrive
            )
            guard outcome != .unavailable else { return false }
            defer { Task { await self.refreshRecents() } }
            switch outcome {
            case .busy:
                self.note = "Still uploading — try again once it has finished"
                return false

            case .deleted(let driveError):
                if let driveError {
                    self.message = request.folder
                        ? .key("Deleted here, but not from the local folder: %@", args: [.verbatim(driveError)])
                        : .key(
                            request.icloud ? "Deleted here, but iCloud refused: %@" : "Deleted here, but Drive refused: %@",
                            args: [.verbatim(driveError)]
                        )
                }
                return driveError == nil

            case .notFound, .unavailable:
                return false
            }
        }
    }

    // MARK: - Disconnecting (docs/03 "Sign out vs Disconnect" · docs/06)

    /// Opens the docs/03 warning. The count is read first because the dialog has to state it: a
    /// user about to lose the queue deserves to know what is still only on this Mac.
    func askToDisconnect(from surface: SettingsSurface) {
        guard !disconnecting else { return }
        guard let core = bridge?.core else { return }
        Task {
            let unuploaded = await Retention.unuploadedRecordings(core: core)
            disconnectSource = surface
            disconnectPrompt = DisconnectPrompt(
                unuploaded: unuploaded,
                // A capture that is running has no job yet, so `core.disconnect`'s own busy guard —
                // which is over the queue — does not cover it, and "also delete the recordings"
                // would delete the one being written. Read here rather than when the button is
                // clicked so the dialog can say so instead of refusing silently.
                recording: !isIdle
            )
        }
    }

    func cancelDisconnect() {
        disconnectPrompt = nil
    }

    /// docs/03 "Disconnect" · docs/06, all of it in [DisconnectFlow]: the Mac and the phone were running
    /// the same two hundred lines side by side, and what they differ by is what [disconnectFlow]
    /// is built with.
    func disconnect(alsoDeleteRecordings: Bool) {
        // The second half of a double-press must not catch the re-presented prompt below and
        // confirm a warning nobody has read: from the first activation until its re-read decides,
        // every further activation is a no-op.
        guard let shown = disconnectPrompt, !disconnecting else { return }
        disconnecting = true
        disconnectPrompt = nil
        perform {
            defer { self.disconnecting = false }
            // What the dialog promised is read again before it is acted on; a warning it never
            // showed re-asks instead of destroying quietly (RecKit, and the phone asks the same).
            if let fresh = await DisconnectPrompt.rewarning(
                core: self.bridge?.core,
                recording: !self.isIdle,
                shown: shown,
                alsoDeleteRecordings: alsoDeleteRecordings
            ) {
                self.disconnectPrompt = fresh
                return false
            }
            self.disconnectPrompt = nil
            return await self.disconnectFlow.run(alsoDeleteRecordings: alsoDeleteRecordings)
        }
    }

    /// Published before work starts and held through revocation and local cleanup.
    @Published private(set) var disconnecting = false

    /// docs/03: the user's own word, and the only thing that clears the debt. Recly cannot ask
    /// Google whether the grant is still listed — it has no account left to ask with — so the row
    /// stays until they say they took it down themselves.
    func revokeDebtSettled() {
        disconnectFlow.debtSettled()
    }

    /// docs/03: the Google account page the disconnect dialog points at, for a user who would
    /// rather do it themselves — and the only thing left to offer when the revoke was refused.
    func openAccountPermissions() {
        guard let url = URL(string: "https://myaccount.google.com/permissions") else { return }
        NSWorkspace.shared.open(url)
    }

    /// docs/10: `FAILED`·`SKIPPED_SHORT`·`NEEDS_AUTH` go back to `PENDING` with a fresh budget.
    func retry(_ item: RecentItem) {
        guard let jobId = item.jobId else { return }
        perform {
            guard let core = self.bridge?.core else { return false }
            guard (try? await core.jobs.retry(jobId: jobId).boolValue) == true else {
                self.note = "This cannot be retried right now"
                return false
            }
            self.runner?.jobsDue()
            return true
        }
    }

    func openInDrive(_ item: RecentItem) {
        guard let link = item.link else { return }
        NSWorkspace.shared.open(link)
    }

    /// docs/03 "Storage location": an iCloud recording's folder, in the iCloud Drive folder Finder shows — or a
    /// local folder recording's, under the folder picked in settings.
    func showInFinder(_ item: RecentItem) {
        guard let folder = item.cloudFolder else { return }
        NSWorkspace.shared.activateFileViewerSelecting([folder])
    }

    /// docs/08 "Result files": the local copies if the steps ran on this Mac, and Drive's if they ran
    /// elsewhere — `core.results` decides which, and keeps what it downloads.
    func showDetail(_ item: RecentItem) {
        guard let core = bridge?.core else { return }
        detail = RecordingDetailModel(core: core, recordingId: item.id, title: item.titleLabel, playbackGate: playbackGate)
    }

    // MARK: - Importing (docs/03 "Naming rules", ux §7)

    /// The transcoder an import runs through — RecKit's, shared with the iPhone. Nil until RecKit has
    /// one, and the Details window offers no import without it.
    static let importer: (any AudioImporter)? = nil

    /// The last import came to nothing: the core's reason code — empty when it gave none — for the
    /// Details window's notice, which says it in words where it is drawn (docs/07 rule 3). Cleared
    /// by the next import.
    @Published private(set) var importFailure: String?

    /// Audio and video files the user picked or dropped, imported one after another, each its own
    /// row (`IMPORTING`, then as any recording). Anything else that was dropped is left alone.
    func importAudio(_ urls: [URL]) {
        guard let core = bridge?.core, let importer = Self.importer else { return }
        let files = urls.filter { UTType(filenameExtension: $0.pathExtension)?.conforms(to: .audiovisualContent) == true }
        guard !files.isEmpty else { return }
        importFailure = nil
        Task {
            for file in files {
                // The file's own date when it has one; the core uses now otherwise.
                let created = (try? file.resourceValues(forKeys: [.creationDateKey]))?.creationDate
                let startedAt = created.map {
                    KotlinInstant.companion.fromEpochMilliseconds(epochMilliseconds: Int64($0.timeIntervalSince1970 * 1000))
                }
                do {
                    let result = try await core.importAudio(
                        sourcePath: file.path,
                        displayName: file.deletingPathExtension().lastPathComponent,
                        startedAt: startedAt,
                        importer: importer
                    )
                    if let imported = result as? ImportResultImported {
                        logger.info("shell.import.ok id=\(imported.recordingId, privacy: .public)")
                        runner?.jobsDue()
                        waveforms?.enqueue(recordingId: imported.recordingId)
                    } else if let failed = result as? ImportResultFailed {
                        logger.info("shell.import.failed reason=\(failed.reason, privacy: .public)")
                        importFailure = failed.reason
                    }
                } catch {
                    logger.error("shell.import.failed error=\(String(describing: error), privacy: .private)")
                    importFailure = ""
                }
            }
        }
    }

    /// docs/08 "Exports": one file of the recording for the share picker or a save panel, named by the
    /// core. Nil when there is nothing in that format.
    func export(_ recordingId: String, _ format: ExportFormat) async -> String? {
        guard let core = bridge?.core else { return nil }
        do {
            return try await core.exportFile(recordingId: recordingId, format: format)
        } catch {
            logger.error("shell.export.failed error=\(String(describing: error), privacy: .private)")
            return nil
        }
    }

    /// A popover button's window (docs/09 trend 2): the action reports its own outcome, so a retry
    /// that could not be made due shows no ✓. [action] is moved *before* the `Task`, not inside it:
    /// the button reads it the moment it is clicked, and a hop to the next main-actor turn would
    /// let the previous operation's `.done` be the state a fresh click sees.
    private func perform(_ work: @escaping () async -> Bool) {
        action = .processing
        Task {
            let succeeded = await work()
            action = succeeded ? .done : .failed
        }
    }

    // MARK: - Local MCP server (docs/12 "Agent connection")

    /// `recly-events mcp --print-config --folder <root>` for the folder recordings go to — the iCloud
    /// folder or the picked local folder — onto the clipboard, for an agent on this Mac to start the
    /// server with. False when there is no folder or the program refused it.
    func copyMCPConfiguration() async -> Bool {
        guard let executable = Bundle.main.url(forAuxiliaryExecutable: "recly-events"),
              let root = await recordingsRoot()
        else { return false }
        let printed: Data? = await Task.detached {
            let process = Process()
            process.executableURL = executable
            process.arguments = ["mcp", "--print-config", "--folder", root]
            let out = Pipe()
            process.standardOutput = out
            process.standardError = FileHandle.nullDevice
            guard (try? process.run()) != nil else { return nil }
            let data = out.fileHandleForReading.readDataToEndOfFile()
            process.waitUntilExit()
            return process.terminationStatus == 0 ? data : nil
        }.value
        guard let printed, let text = String(data: printed, encoding: .utf8) else {
            logger.error("agent.mcp.config.failed")
            return false
        }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
        logger.info("agent.mcp.config.copied")
        return true
    }

    /// The top of the folder new recordings go to, when it is on this Mac's disk.
    private func recordingsRoot() async -> String? {
        guard let core = bridge?.core, let kind = try? await core.processingSettings.storage() else { return nil }
        if kind == .folder { return LocalFolderPath.current }
        if kind == .icloud { return await (core.deps.ubiquity as? ICloudContainer)?.folderURL("")?.path }
        return nil
    }

    // MARK: - Launch at login (docs/12 "Runner")

    func setLaunchAtLogin(_ enabled: Bool) {
        do {
            if enabled {
                try SMAppService.mainApp.register()
            } else {
                try SMAppService.mainApp.unregister()
            }
        } catch {
            // An ad-hoc-signed build is refused by `SMAppService`; say so rather than leaving a
            // checked item that does nothing.
            note = "Could not set launch at login"
            logger.error("shell.launchAtLogin.failed error=\(String(describing: error), privacy: .public)")
        }
        launchAtLogin = SMAppService.mainApp.status == .enabled
    }

    // MARK: - Elapsed time

    private func startTicking() {
        tick()
        let ticker = Timer(timeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
        // `.common`: a menu that is open puts the run loop in tracking mode, and the elapsed time
        // has to keep moving while the user is looking at it.
        RunLoop.main.add(ticker, forMode: .common)
        self.ticker = ticker
    }

    private func stopTicking() {
        ticker?.invalidate()
        ticker = nil
        elapsed = ""
    }

    private func tick() {
        // Read here rather than once at the start: the tap re-creates itself onto whatever the
        // default output device has become (docs/12 "Tap re-creation"), and a menu still naming the
        // headphones that were unplugged ten minutes ago is worse than naming nothing. `nil` in
        // microphone mode, where no tap was ever opened.
        capturedOutputDevice = recorder?.capturedOutputDevice
        capturedInputDevice = recorder?.capturedInputDevice
        microphoneRecovering = recorder?.microphoneRecovering ?? false
        captureHealth = recorder?.systemCaptureHealth ?? .healthy
        let total = Int((recorder?.recordedSec ?? 0).rounded(.down))
        elapsed = LedgerFormat.clock(total)
    }

    /// docs/09 screen principle 6: the levels behind the live strip, asked for ten times a second by the
    /// view that draws it — not published, because a `@Published` array at that rate would redraw
    /// the whole popover for a picture. It takes the recorder's own lock and nothing of the menu's,
    /// and it answers with an empty array when there is no recording.
    func livePeaks() -> [Float] {
        recorder?.livePeaks() ?? []
    }

    // MARK: - Prompts

    /// What the stop asked for and got: the name, and how many people were in the room.
    enum NamingAnswer {
        case save(title: String?, participants: Int?)
        case discard
    }

    /// docs/03: the title, asked after the recording has ended. A popover cannot host it — it is
    /// closed by the time the stop finishes — so it is a [BlueprintPanel], which is the same
    /// question the phone's `NamingSheet` asks in the same shape rather than an `NSAlert` with a
    /// text field bolted to its side.
    private func askForTitle() -> NamingAnswer? {
        BlueprintPanel.run { finish in
            NamingSheet(
                onSave: { finish(.save(title: $0, participants: $1)) },
                onCancel: { finish(.discard) }
            )
        }
    }

    /// docs/12 M8 · ADR-011: a local capture shows the other participants nothing at all, so the
    /// responsibility for telling them is the user's and the app's job is to remind them — once,
    /// before the recording, and never again once they have said not to. There is no covert mode.
    ///
    /// `false` cancels the recording: this is a question, and "Cancel" has to mean something.
    private func askAboutConsentIfNeeded(mode: RecordingMode) -> Bool {
        guard mode == .meeting, consentReminder else { return true }
        let alert = NSAlert()
        alert.messageText = AppStrings.localized("Did you tell the participants about the recording?")
        // docs/research/02 §Consent · law. Not legal advice and not a jurisdiction the app tries
        // to guess: the three lines are what the user needs to know that the question is not
        // rhetorical.
        alert.informativeText = AppStrings.localized("consent.body")
        alert.addButton(withTitle: AppStrings.localized("I told them · Start recording"))
        alert.addButton(withTitle: AppStrings.localized("Cancel"))
        alert.showsSuppressionButton = true
        alert.suppressionButton?.title = AppStrings.localized("Do not ask again")
        alert.accessoryView = Self.consentGuidanceLink()
        NSApp.activate(ignoringOtherApps: true)
        let response = alert.runModal()
        // Switched off from where the user is when they decide they have had enough of it.
        // The menu's own toggle turns it back on.
        if alert.suppressionButton?.state == .on { consentReminder = false }
        return response == .alertFirstButtonReturn
    }

    /// A link rather than a third button, because a button would dismiss the alert the user is
    /// still answering. Wikipedia's summary of recording-consent law until Recly has a page of its
    /// own to point at; it is the only one of these that is maintained and covers all three.
    private static func consentGuidanceLink() -> NSView {
        let url = URL(string: "https://en.wikipedia.org/wiki/Telephone_call_recording_laws")!
        let field = NSTextField(labelWithAttributedString: NSAttributedString(
            string: AppStrings.localized("Recording-consent rules by jurisdiction"),
            attributes: [
                .link: url,
                .foregroundColor: NSColor.linkColor,
                .underlineStyle: NSUnderlineStyle.single.rawValue,
            ]
        ))
        // A label is not selectable, and a link in a text field is only clickable when it is.
        field.isSelectable = true
        field.allowsEditingTextAttributes = true
        field.frame = NSRect(x: 0, y: 0, width: 320, height: 20)
        return field
    }

    /// docs/12 deliverable 1: there is no API to ask whether the tap is allowed, so a refusal is
    /// only ever discovered by trying. The two things worth offering are the pane that can undo it
    /// and the recording the user can still have right now.
    private func presentSystemAudioUnavailable() {
        let alert = NSAlert()
        alert.messageText = AppStrings.localized("System audio cannot be captured")
        alert.informativeText = AppStrings.localized(
            "Turn Recly on in System Settings > Privacy & Security > Screen & System Audio Recording."
        )
        // docs/09 screen principle 5: two answers, not three. The third — "record the microphone only" —
        // was a *second* thing to decide inside a panel about a permission, and the popover's mode
        // chips are where that choice already lives; a cancel that starts a different recording
        // than the one asked for is not a cancel.
        alert.addButton(withTitle: AppStrings.localized("Open System Settings"))
        alert.addButton(withTitle: AppStrings.localized("Close"))
        NSApp.activate(ignoringOtherApps: true)
        guard alert.runModal() == .alertFirstButtonReturn,
              let pane = URL(
                  string: "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture"
              )
        else { return }
        NSWorkspace.shared.open(pane)
    }

    /// A retained, unshown anchor for sign-in started before any app window is visible.
    private static func makeAuthAnchor() -> NSWindow {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 360, height: 100),
            styleMask: [.borderless],
            backing: .buffered,
            defer: false
        )
        // A window made in code releases itself on `close()` by default, and this one is also held
        // by the caller for the length of the sign-in — the second release was a crash after
        // every failed sign-in, in the window's own closing animation.
        window.isReleasedWhenClosed = false
        window.center()
        return window
    }

    /// The one failure worth a panel of its own is the placeholder client id: nothing the user does
    /// in the app can fix it, and the README is where the fix is written down.
    private func presentAuthFailure(_ error: Error) {
        let alert = NSAlert()
        alert.messageText = AppStrings.localized("Google sign-in failed")
        // docs/07 rule 4: `localizedDescription` is the *system's* language and belongs in the log
        // line the caller already wrote, not on a panel the app has a sentence of its own for.
        alert.informativeText = (error as? GoogleAuth.Failure)?.message
            ?? AppStrings.localized("The sign-in failed")
        alert.addButton(withTitle: AppStrings.localized("OK"))
        NSApp.activate(ignoringOtherApps: true)
        alert.runModal()
    }

    /// docs/12 "Permissions": a refusal is not something the app can retry its way out of, so the answer
    /// is the deep link to the pane that can undo it.
    private func presentMicrophoneDenied() {
        let alert = NSAlert()
        alert.messageText = AppStrings.localized("The microphone permission is required")
        alert.informativeText = AppStrings.localized(
            "Turn Recly on in System Settings > Privacy & Security > Microphone."
        )
        alert.addButton(withTitle: AppStrings.localized("Open System Settings"))
        alert.addButton(withTitle: AppStrings.localized("Close"))
        NSApp.activate(ignoringOtherApps: true)
        guard alert.runModal() == .alertFirstButtonReturn,
              let pane = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone")
        else { return }
        NSWorkspace.shared.open(pane)
    }
}

/// docs/03 "Deleting in the app": the delete question that is up, and the surface it was asked from.
///
/// One value rather than a request with a flag beside it, because the two are one fact and are only
/// true together. The count the dialog states is a read off the core, so the request is not ready
/// until an await has come back; a flag set before that await would re-point the dialog that is
/// *already* up the moment a second row was pressed, and a dismissal in flight would leave the
/// popover and the window each thinking the question was theirs.
///
/// Mac-local, and not a field on RecKit's [DeleteRequest]: the phone has one screen to ask on.
struct DeleteAsk: Identifiable, Equatable {
    /// Where the question was asked, and so where it is drawn — the popover over its own ledger,
    /// the Transcripts window as the platform's sheet.
    enum Source {
        case popover
        case recordingsWindow
    }

    let request: DeleteRequest
    let source: Source

    var id: String { request.id }
}

/// The two surfaces that draw [SettingsPane] — the popover's settings and the Settings window — and
/// so the two a disconnect can be asked from. Both can be open at once; the warning is drawn only on
/// the one that asked, as [DeleteAsk] is.
enum SettingsSurface {
    case popover
    case settingsWindow
}

/// The shell's settings, in one place. `UserDefaults` and not the core: none of them is worth
/// syncing between machines — whether this user wants the consent question or the first-run card
/// are facts about one Mac.
///
/// The two a disconnect leaves behind are not here: they are written *before* the credentials they
/// are about are deleted and read back by the same rules on the phone, so they live in RecKit
/// ([DisconnectDefaults]).
private enum Defaults {
    private static let consentReminderKey = "consentReminder"
    private static let modelPromptDismissedKey = "modelPromptDismissed"
    private static let shortcutKey = "globalShortcut"

    /// docs/12 M8: on until the user turns it off — the one default here that is not `false`, so it
    /// is the absence of the key and not its value that has to be read.
    static var consentReminder: Bool {
        get { UserDefaults.standard.object(forKey: consentReminderKey) as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: consentReminderKey) }
    }

    /// docs/12 "Menu bar app": ⌥⌘R, on until the user turns it off.
    static var shortcut: Bool {
        get { UserDefaults.standard.object(forKey: shortcutKey) as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: shortcutKey) }
    }

    /// docs/05 "Fixed processing settings": "Not now" on the first-run model card.
    static var modelPromptDismissed: Bool {
        get { UserDefaults.standard.bool(forKey: modelPromptDismissedKey) }
        set { UserDefaults.standard.set(newValue, forKey: modelPromptDismissedKey) }
    }
}
