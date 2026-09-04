import AppKit
import Foundation
import os
import ReclyCore
import RecKit
import ServiceManagement
import SwiftUI

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
    /// The line the menu shows when nothing is being recorded — opening the core, what went wrong,
    /// what a stop left behind.
    ///
    /// docs/07 rule 3: a *key*, resolved by [status] where the menu draws it, so a note already on
    /// screen follows a language change. [AppStrings.localized] hands a sentence it does not know
    /// back unchanged.
    @Published private(set) var note = "Opening" {
        // Both belong to the note they were set with; a new note has neither until it says so.
        didSet {
            noteCount = nil
            message = nil
        }
    }
    /// The argument of the one note that takes one (`Deferred %@`), set right after the key so that
    /// [status] can format it in the language the menu is being drawn in.
    private var noteCount: Int?
    /// docs/07 rule 3: what a delete or a disconnect had to say, which is RecKit's message
    /// rather than a key of this app's — kept as the message and resolved by [status], for the same
    /// reason [note] is kept as a key. Named as the phone's own slot is, because it is the same one.
    ///
    /// `@Published` because [status] is drawn from it: a finished operation writes only this, and
    /// a menu that was not told would go on showing the note it replaced.
    @Published private(set) var message: UiMessage?
    /// ADR-016: every workflow the document has — a definition says nothing about which device may
    /// run it.
    @Published private(set) var workflows: [WorkflowSummary] = []
    /// Which of them this Mac records with: a mirror of the local pointer (ADR-016), resolved
    /// against the document by the core's own rule rather than by a second copy of it. Nil when this
    /// Mac has picked none, or points at one the document no longer resolves; both are "pick one".
    ///
    /// Read-only from outside, because the pointer is the truth and [selectWorkflow] is the one way
    /// it moves — a value written here would be a second answer that the next observation undoes.
    @Published private(set) var workflowId: String?
    @Published private(set) var elapsed = ""
    /// False until the core is open: there is nothing to start a recording against before that.
    @Published private(set) var isReady = false
    /// docs/12 M4-L3 "메뉴바": microphone only / meeting (microphone + system). Remembered between
    /// launches, and
    /// `microphone` on a machine that has never chosen — recording everyone else in the room by
    /// default is not a default to make on the user's behalf.
    @Published var mode: RecordingMode = Defaults.mode {
        didSet { Defaults.mode = mode }
    }
    /// The output device the tap is on, while a meeting is being recorded (docs/12 deliverable 5).
    @Published private(set) var capturedOutputDevice: String?
    /// docs/12 "메뉴바": the last five recordings, refreshed after every executor pass.
    @Published private(set) var recents: [RecentItem] = []
    /// docs/10: the user-fixable failures across the queue, folded one line per reason — the
    /// popover's banner, the menu bar icon's error state, and the local notifications.
    @Published private(set) var alerts: [JobAlert] = []
    /// Non-nil while the docs/03 delete dialog is up — the question *and* the surface it was asked
    /// from, which is why it is one value and not a request beside a flag (see [DeleteAsk]).
    @Published private(set) var deleteRequest: DeleteAsk?
    /// Non-nil while the docs/03 disconnect warning is up, with the count it has to name.
    @Published var disconnectPrompt: DisconnectPrompt?
    /// docs/06: how far the last disconnect got, and so whether one is still owed. Stored, because
    /// a relaunch is the most likely place the retry happens from — the account is gone by then and
    /// this is the only thing that keeps the Disconnect row on screen.
    @Published private(set) var disconnectPhase = DisconnectDefaults.phase
    /// docs/03: true while Google is still listing Recly because the revoke failed. It outlives the
    /// disconnect, the phase and the account — only the user's own word clears it.
    @Published private(set) var revokeDebt = DisconnectDefaults.revokeDebt
    /// The signed-in Google account, or nil.
    @Published private(set) var account: String?
    /// Built once the core is open; the workflow window is empty until then.
    @Published private(set) var workflowEditor: WorkflowsModel?
    /// docs/05 "워크플로우 내보내기 · 가져오기": the settings pane's file section, built with the core
    /// like the editor. Nil until then — there is no document to export before the core is open.
    @Published private(set) var workflowTransfer: WorkflowTransferModel?
    /// docs/08 결과 파일: the recording the transcript window is showing, once one is picked.
    @Published private(set) var detail: RecordingDetailModel?
    /// docs/12 "실행기": `SMAppService`. Written from the system's own answer, never from the
    /// request — a registration the system refused must not leave a checked menu item behind.
    @Published private(set) var launchAtLogin = SMAppService.mainApp.status == .enabled
    /// docs/12 M8: the consent question, once before the first meeting recording, and switchable
    /// off from the menu.
    @Published var consentReminder: Bool = Defaults.consentReminder {
        didSet { Defaults.consentReminder = consentReminder }
    }
    /// docs/09 트렌드 2: where the one operation a popover row can start — an upload now, a retry —
    /// actually is. `ProcessingButton` owns the window around it; this owns the truth.
    @Published private(set) var action: ProcessingState = .idle
    /// docs/09 화면 원칙 1·4: this install, for the popover's header. Empty until the core is open,
    /// which is the only thing that knows it.
    @Published private(set) var deviceId = ""

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
    /// Where that tap waits while there is no editor to take it to (docs/10).
    private let alertRouter = AlertRouter<JobAlert>()
    /// And where the *meeting* offer's own tap waits. Same reason, other notification: the offer is
    /// the one thing on the Lock Screen that is most likely to be opened from a cold launch, and
    /// [act(on:)] needs a recorder that only [load] makes.
    private let meetingRouter = AlertRouter<MeetingNotifier.Action>()
    /// docs/03 "연결 해제" · docs/06: the whole of a disconnect, which is RecKit's and not this
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
    /// docs/12 "미팅 감지". Built lazily because it closes over `self`.
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
            let bridge = try await CoreBridge.make(appVersion: CoreBridge.appVersion, tokenProvider: tokens)
            self.bridge = bridge
            let recorder = SegmentedRecorder(
                core: bridge.core,
                // docs/12 "에코": Apple's voice processing is telephony-tuned and narrows the band,
                // so it is a flag and nothing else — off unless someone has deliberately set it.
                voiceProcessing: Defaults.voiceProcessing
            ) { [weak self] error in
                self?.captureFailed(error)
            }
            self.recorder = recorder
            let recovery = RecordingRecovery(core: bridge.core)
            let session = RecorderSession(
                capture: recorder,
                recover: { await recovery.reconcile() },
                onState: { [weak self] state in
                    Task { @MainActor in self?.adopt(state) }
                }
            )
            self.session = session
            // Before anything else can touch the directories: a part the last run could not file is
            // still on disk, and a recording left open is one nothing would ever act on.
            let recovered = await session.recoverIfIdle()
            await refreshWorkflows()
            workflowTransfer = WorkflowTransferModel(core: bridge.core)
            let editor = WorkflowsModel(core: bridge.core)
            // Every edit and every reopen ends in the editor re-reading the document, which is
            // exactly when the popover's answers about it go stale.
            editor.onDocumentChanged = { [weak self] in
                Task { @MainActor in await self?.refreshWorkflows() }
            }
            workflowEditor = editor
            observeDeviceDefault(core: bridge.core)
            observeJobs(core: bridge.core)
            observeRecordings(core: bridge.core)
            // There is a screen for a tap to land on now, so whatever came in while the core was
            // opening is served (docs/10).
            alertRouter.connect { [weak self] alert in self?.fix(alert) }
            // Before the executor: a sign-in restored from the SDK's Keychain is what decides
            // whether the first pass can do anything at all (docs/06).
            let auth = GoogleAuth(tokens: tokens)
            self.auth = auth
            await auth.restore()
            account = auth.account
            let runner = JobRunner(
                queue: CoreJobQueue(core: bridge.core),
                onPass: { [weak self] _ in self?.passFinished() }
            )
            self.runner = runner
            // docs/12 "실행기" (b)·(c), plus a pass now: a job left parked by the last run is due.
            runner.start()
            detector.start()
            deviceId = bridge.deps.device.deviceId
            isReady = true
            note = "Waiting"
            // docs/12 "미팅 감지": only once there is something to record with — and only once
            // [isReady], because `start` refuses before it (Sol P1-apple r3). The notification's
            // button starts a recording, and a button that cannot is worse than no notification —
            // so a tap that arrived before now was kept, and is served here.
            meetingRouter.connect { [weak self] action in self?.act(on: action) }
            // The device id identifies this install, the data directory carries the user's home
            // directory, and the workflow names are the user's own text — none of the three belongs
            // in a log anyone can read off the machine. Counts are what the line is actually for.
            logger.info(
                """
                shell.ready device=\(bridge.deps.device.deviceId, privacy: .private) \
                dataDir=\(bridge.dataDirectory.path, privacy: .private) \
                workflows=\(self.workflows.count, privacy: .public) \
                recovered=\(recovered, privacy: .public)
                """
            )
        } catch {
            note = "Core error"
            // A database-open failure puts the file's path in the message.
            logger.error("shell.failed error=\(String(describing: error), privacy: .private)")
        }
    }

    /// [workflows] and [workflowId] as the core has them *now*. Both are answers about the
    /// stored document, and the document moves under this model — an editor save, an import — so
    /// they are re-read rather than kept from the one launch that first read them. Otherwise the popover names one workflow and the `nil` it starts with
    /// runs another.
    private func refreshWorkflows() async {
        guard let core = bridge?.core else { return }
        do {
            // docs/05 "첫 기기": the first read seeds the starter on a Mac that has never had a
            // document, and — ADR-016 — points this Mac's own default at 메모, the one starter there
            // is. A Mac adopting an existing document seeds nothing and keeps a null pointer, which
            // the popover nudges about.
            let document = try await core.workflows.seed(
                preferredDefaultId: WorkflowRepository.companion.MEMO_ID
            )
            workflows = try await core.workflows.summary()
            workflowId = WorkflowSelector.shared.select(
                doc: document,
                chosen: nil,
                deviceDefault: try await core.workflows.deviceDefault()
            )?.id
        } catch {
            // The names are the user's own text; the failure is worth a line, the document is not.
            logger.error("shell.workflows.failed error=\(String(describing: error), privacy: .private)")
        }
    }

    /// The popover's one choice: which workflow this Mac records with. It writes nothing to the
    /// document — the pointer is local (ADR-016), and the same call is what the workflow window's
    /// row action makes. [workflowId] follows from the observation below rather than from here, so
    /// what the chips show is the pointer the core actually holds.
    func selectWorkflow(_ id: String) async {
        guard let core = bridge?.core else { return }
        do {
            try await core.workflows.setDeviceDefault(workflowId: id)
        } catch {
            message = .key("Could not save")
            logger.error("shell.workflows.select.failed error=\(String(describing: error), privacy: .private)")
        }
    }

    /// ADR-016: the pointer is not in the document, so it moves without one arriving — a pick made
    /// in the popover or in the workflow window, a delete that cleared it. `onDocumentChanged`
    /// answers for the document; this answers for the pointer. SKIE hands the core's `Flow` over as
    /// an `AsyncSequence`.
    private func observeDeviceDefault(core: ReclyCore_) {
        Task { [weak self] in
            for await _ in core.workflows.observeDeviceDefault() {
                guard let self else { return }
                await self.refreshWorkflows()
            }
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
        RecorderStatusLine.text(state: state, note: note, count: noteCount, message: message)
    }

    /// The menu bar icon: the app mark's 22-point monochrome template (docs/09 "앱 아이콘"), so the
    /// status item is the same shape as the launcher icon. The idle one is a template image and
    /// AppKit paints it in the menu bar's own colour; the recording one is red (docs/12 "상태
    /// 아이콘") and a template would lose that, so it is an ordinary image with a light and a dark
    /// variant in the catalog instead — the appearance is what the menu bar hands it.
    /// docs/10 "macOS": 메뉴바 아이콘 오류 상태. A job the user has to do something about puts a mark
    /// on the icon — the same square badge the ledger draws, in the corner the recording tint does
    /// not use. Recording wins: a recording in progress is the more urgent thing to be told, and
    /// the queue's news is one click away in the popover either way.
    ///
    /// docs/09 "모든 상태는 색 + 텍스트": the mark is a *shape* added to the icon rather than a tint
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

    /// [mode] is the menu's own pick unless the caller has one of its own — which the detection
    /// paths do: a meeting Recly found by *hearing* it is a meeting, whatever the menu was last set
    /// to, and starting a one-track memo off an "Are you in a meeting?" notification would be
    /// answering a
    /// different question than the one asked. The menu's setting is read, never written.
    /// The popover's own start. `nil` like every other path: ADR-016 leaves the choice to this
    /// Mac's own pointer, which is exactly what the popover's chips set.
    func start() {
        start(workflowId: nil)
    }

    func start(workflowId: String?, mode: RecordingMode? = nil) {
        guard let session, isReady else { return }
        let mode = mode ?? self.mode
        // docs/03: before the question, not after it — a disconnect's clean-up walks the recording
        // directory, and there is nothing to ask about a capture that is about to be refused.
        if let blocker = DisconnectGate.startBlocker() {
            message = blocker
            logger.info("shell.recording.start.refused reason=disconnecting")
            return
        }
        // docs/12 M8: before the recording, unlike the speaker warning — telling the participants
        // after the fact is not telling them, and this is the one prompt the user can answer "no" to.
        guard askAboutConsentIfNeeded(mode: mode) else { return }
        Task {
            do {
                // The last look at the document before the core makes its own pick from it: what
                // the popover is showing and what `nil` is about to run are then the same answer.
                await refreshWorkflows()
                // docs/03: the gate is held across the last look at the document *and* the start
                // itself. The read above suspends, so the check `start` made before it says
                // nothing about the moment the capture actually opens — a disconnect that took the
                // gate inside that wait would be walking the recording directory while this capture
                // wrote into it. A start that finds it held is refused rather than queued.
                let opened = try await DisconnectGate.ifOpen {
                    try await session.start(workflowId: workflowId, mode: mode)
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
                // docs/12 "종료 감지" is about *this* recording, and only a meeting has one: a
                // microphone-only memo's own idle microphone is not a meeting that has ended.
                detector.recordingChanged(mode == .meeting)
                // After the recording is running, not before: the warning is about how the audio
                // will come out, and a modal in front of the start would cost the user the opening
                // of their meeting while they read it.
                warnAboutTheSpeakerIfNeeded(mode: mode)
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
            // missing parts are filed, which the next recovery pass does.
            note = "Deferred %@"
            noteCount = Int(pending)
            logger.error("shell.recording.deferred id=\(recordingId, privacy: .public) pending=\(pending, privacy: .public)")

        case .finalized(let outcome):
            if askingForTitle, let answer = askForTitle(),
               answer.title != nil || answer.participants != nil {
                // docs/03 `context.participants`: the count comes from the person who was in the
                // room, and "unknown" writes nothing.
                _ = try? await bridge?.core.recordings.updateTitle(
                    recordingId: outcome.recordingId,
                    title: answer.title,
                    participants: answer.participants.map { KotlinInt(int: Int32($0)) }
                )
            }
            // nil: the pick the user made when they started is in the meta, and that is what
            // `enqueue` falls back to (docs/05).
            //
            // The failure is caught rather than swallowed as `try?`, which is what the phone
            // already did: a recording whose job could not be made is one nothing will ever upload,
            // and the menu said "Waiting" over it.
            do {
                _ = try await bridge?.core.enqueue(recordingId: outcome.recordingId, chosenWorkflowId: nil)
                // docs/12 "실행기" (a): the job exists now, so a pass runs immediately rather than
                // waiting for the five-minute timer.
                runner?.jobsDue()
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
        if isRecording, !wasRecording { startTicking() }
        if !isRecording, wasRecording {
            stopTicking()
            capturedOutputDevice = nil
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

    // MARK: - Meeting detection (docs/12 "미팅 감지", ADR-011)

    /// The detector has decided something is worth saying. ADR-011: detect → confirm → record, so
    /// both prompts are a notification and nothing else — a recording never starts on its own.
    private func detected(_ prompt: MeetingDetectionRule.Prompt) {
        switch prompt {
        case .start:
            guard isIdle, isReady else { return }
            logger.info("detect.meeting")
            notifier.post(.start)

        case .stop:
            // Never a stop of its own (docs/12 "종료 감지": never an automatic stop).
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
        // No workflow submenu on a notification: `nil` is the source's default (ADR-016).
        case .start: start(workflowId: nil, mode: .meeting)
        case .stop: stop()
        }
    }

    // MARK: - Sign-in (docs/06)

    /// True when `Info.plist` still carries the placeholder client id — the menu offers the
    /// procedure rather than a sign-in that cannot succeed.
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
        Task {
            // `ASWebAuthenticationSession` needs a window to hang off, and `LSUIElement` means
            // there may be none — so one is made for the duration of the sign-in.
            let anchor = Self.makeAuthAnchor()
            defer { anchor.close() }
            do {
                account = try await auth.signIn(presenting: anchor)
                logger.info("auth.signIn.ok")
                // docs/06: a job parked in NEEDS_AUTH resumes when the user signs in.
                await unpark()
            } catch GoogleAuth.Failure.canceled {
                // The user closed the consent sheet. Nothing failed, so nothing is said.
                logger.info("auth.signIn.canceled")
            } catch {
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
        await ParkedJobs.unpark(core: core)
        runner?.jobsDue()
    }

    // MARK: - Recent recordings (docs/12 "메뉴바")

    /// A pass has finished: the ledger is redrawn from the queue it left behind, and the banner,
    /// the menu bar icon and the notifications are folded out of the same reading ([publishAlerts]).
    /// docs/10 replaced the sign-in-only line this used to raise — `NEEDS_AUTH` is one of its seven.
    private func passFinished() {
        Task {
            await refreshRecents()
            // A pass is also where a pull lands (docs/05), so the document another device edited is
            // in the local copy by now and the popover's default is one this one has never read.
            await refreshWorkflows()
        }
    }

    private func refreshRecents() async {
        guard let core = bridge?.core else { return }
        do {
            recents = try await Recents.load(core: core)
        } catch {
            logger.error("shell.recents.failed error=\(String(describing: error), privacy: .private)")
        }
        await publishAlerts()
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
    /// The *queue*, and not [recents]: the ledger is the newest five recordings, so a job blocked
    /// before those had its banner line, its menu bar badge and its notification taken away by
    /// nothing more than five newer recordings — while it was still blocked.
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

    /// docs/10: "탭하면 고칠 수 있는 화면으로 간다 — 로그인 화면, 시크릿 폼, 워크플로우 편집기.
    /// '앱 열기'로 끝내지 않는다." On a `LSUIElement` Mac that means the editor window, opened on the
    /// definition that has to change — [openEditor] is set by the scene, which is the only thing
    /// that can open one.
    func fix(_ alert: JobAlert) {
        switch alert.reason.fix {
        case .signIn:
            signIn()

        case .driveStorage:
            openDriveStorage()

        // docs/08 "오류": the key is the thing to look at, and looking at it means the form it is
        // entered in — not the editor with the form still to be found. The step named it, so the
        // form opens under that step (`SecretFormView` draws where `form.stepId` points) with the
        // key that was refused already in the name field.
        case .secrets:
            openWorkflow(alert.workflowId) { editor in
                editor.openSecrets(prefill: alert.secret, step: alert.stepId)
            }

        case .editor:
            openWorkflow(alert.workflowId) { _ in }
        }
    }

    /// docs/10 "Drive 용량 초과": the one fix that leaves the app, because the space is Google's to
    /// give back. Offered on the ledger row as well as on the banner.
    func openDriveStorage() {
        NSWorkspace.shared.open(driveStorageURL)
    }

    /// The editor window, open on one definition — and then whatever the fix has to do inside it.
    private func openWorkflow(_ workflowId: String?, then: @escaping (WorkflowsModel) -> Void) {
        guard let workflowId, let editor = workflowEditor else { return }
        Task {
            await editor.reload()
            editor.edit(workflowId)
            then(editor)
            NSApp.activate(ignoringOtherApps: true)
            self.openEditor?()
        }
    }

    /// How a window gets opened from a model: `openWindow` is an environment value and only a view
    /// has one, so the scene hands its own down (`MenuPopover`).
    var openEditor: (() -> Void)?

    // MARK: - Deleting a recording (docs/03 "앱에서 지우기")

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
            guard asked == self.deleteAsked else { return }
            deleteRequest = DeleteAsk(
                request: DeleteRequest(
                    recordingId: item.id,
                    title: item.titleLabel,
                    unuploaded: unuploaded,
                    remote: item.remote
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
                    self.message = .key(
                        "Deleted here, but Drive refused: %@",
                        args: [.verbatim(driveError)]
                    )
                }
                return driveError == nil

            case .notFound, .unavailable:
                return false
            }
        }
    }

    // MARK: - Disconnecting (docs/03 "로그아웃 vs 연결 해제" · docs/06)

    /// Opens the docs/03 warning. The count is read first because the dialog has to state it: a
    /// user about to lose the queue deserves to know what is still only on this Mac.
    func askToDisconnect() {
        guard let core = bridge?.core else { return }
        Task {
            disconnectPrompt = DisconnectPrompt(
                unuploaded: await Retention.unuploadedRecordings(core: core),
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

    /// docs/03 "연결 해제" · docs/06, all of it in [DisconnectFlow]: the Mac and the phone were running
    /// the same two hundred lines side by side, and what they differ by is what [disconnectFlow]
    /// is built with.
    func disconnect(alsoDeleteRecordings: Bool) {
        // The second half of a double-press must not catch the re-presented prompt below and
        // confirm a warning nobody has read: from the first activation until its re-read decides,
        // every further activation is a no-op.
        guard let shown = disconnectPrompt, !disconnectChecking else { return }
        disconnectChecking = true
        perform {
            defer { self.disconnectChecking = false }
            // What the dialog promised is read again before it is acted on; a warning it never
            // showed re-asks instead of destroying quietly (RecKit, and the phone asks the same).
            if let fresh = await DisconnectPrompt.rewarning(
                core: self.bridge?.core,
                recording: !self.isIdle,
                shown: shown
            ) {
                self.disconnectPrompt = fresh
                return false
            }
            self.disconnectPrompt = nil
            return await self.disconnectFlow.run(alsoDeleteRecordings: alsoDeleteRecordings)
        }
    }

    /// True from a confirm until its re-read decides — see [disconnect].
    private var disconnectChecking = false

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

    /// docs/08 "결과 파일": the local copies if the steps ran on this Mac, and Drive's if they ran
    /// elsewhere — `core.results` decides which, and keeps what it downloads.
    func showDetail(_ item: RecentItem) {
        guard let core = bridge?.core else { return }
        detail = RecordingDetailModel(core: core, recordingId: item.id, title: item.titleLabel)
    }

    /// docs/08 AUTH_REJECTED: the key is defined in the workflow, so that is where "check the key"
    /// lands.
    func editWorkflow(of item: RecentItem) {
        guard let workflowId = item.workflowId, let editor = workflowEditor else { return }
        Task {
            await editor.reload()
            editor.edit(workflowId)
        }
    }

    /// A popover button's window (docs/09 트렌드 2): the action reports its own outcome, so a retry
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

    // MARK: - Launch at login (docs/12 "실행기")

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
        // default output device has become (docs/12 "tap 재생성"), and a menu still naming the
        // headphones that were unplugged ten minutes ago is worse than naming nothing. `nil` in
        // microphone mode, where no tap was ever opened.
        capturedOutputDevice = recorder?.capturedOutputDevice
        let total = Int((recorder?.recordedSec ?? 0).rounded(.down))
        elapsed = LedgerFormat.elapsed(total)
    }

    /// docs/09 화면 원칙 6: the levels behind the live strip, asked for ten times a second by the
    /// view that draws it — not published, because a `@Published` array at that rate would redraw
    /// the whole popover for a picture. It takes the recorder's own lock and nothing of the menu's,
    /// and it answers with an empty array when there is no recording.
    func livePeaks() -> [Float] {
        recorder?.livePeaks() ?? []
    }

    // MARK: - Prompts

    /// What the stop asked for and got: the name, and how many people were in the room.
    struct NamingAnswer {
        let title: String?
        let participants: Int?
    }

    /// docs/03: the title, asked after the recording has ended. A popover cannot host it — it is
    /// closed by the time the stop finishes — so it is a [BlueprintPanel], which is the same
    /// question the phone's `NamingSheet` asks in the same shape rather than an `NSAlert` with a
    /// text field bolted to its side.
    private func askForTitle() -> NamingAnswer? {
        BlueprintPanel.run { finish in
            NamingSheet(
                onSave: { finish(NamingAnswer(title: $0, participants: $1)) },
                onSkip: { finish(nil) }
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
        // docs/research/02 §동의·법. Not legal advice and not a jurisdiction the app tries to guess:
        // the three lines are what the user needs to know that the question is not rhetorical.
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

    /// docs/12 "에코": with headphones the problem does not exist, and with the built-in speaker the
    /// microphone records the other side of the call back into the `mic` track. v1 has no AEC, so
    /// the honest thing is to say so — once, and only while it is true.
    private func warnAboutTheSpeakerIfNeeded(mode: RecordingMode) {
        guard mode == .meeting, !Defaults.speakerWarningSuppressed,
              SystemAudioDevice.defaultOutput()?.isBuiltInSpeaker == true
        else { return }
        let alert = NSAlert()
        alert.messageText = AppStrings.localized("You are listening on the built-in speaker")
        alert.informativeText = AppStrings.localized(
            "With headphones the other side does not bleed into your own track."
        )
        alert.addButton(withTitle: AppStrings.localized("OK"))
        alert.addButton(withTitle: AppStrings.localized("Do not show again"))
        NSApp.activate(ignoringOtherApps: true)
        if alert.runModal() == .alertSecondButtonReturn {
            Defaults.speakerWarningSuppressed = true
        }
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
        // docs/09 화면 원칙 5: two answers, not three. The third — "record the microphone only" —
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

    /// `ASWebAuthenticationSession` presents itself from a window, and a `LSUIElement` menu-bar app
    /// has none. This is that window: small, named, and closed the moment the sign-in ends.
    private static func makeAuthAnchor() -> NSWindow {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 360, height: 100),
            styleMask: [.titled],
            backing: .buffered,
            defer: false
        )
        window.title = "Recly"
        // A window made in code releases itself on `close()` by default, and this one is also held
        // by the caller for the length of the sign-in — the second release was a crash after
        // every failed sign-in, in the window's own closing animation.
        window.isReleasedWhenClosed = false
        let label = NSTextField(
            labelWithString: AppStrings.localized("Continue the Google sign-in in your browser.")
        )
        label.frame = NSRect(x: 20, y: 40, width: 320, height: 20)
        window.contentView?.addSubview(label)
        window.center()
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
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

    /// docs/12 "권한": a refusal is not something the app can retry its way out of, so the answer
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

/// docs/03 "앱에서 지우기": the delete question that is up, and the surface it was asked from.
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

/// The shell's settings, in one place. `UserDefaults` and not the core: none of them is worth
/// syncing between machines — which output device is in front of *this* user and whether they have
/// read the speaker warning are facts about one Mac.
///
/// The two a disconnect leaves behind are not here: they are written *before* the credentials they
/// are about are deleted and read back by the same rules on the phone, so they live in RecKit
/// ([DisconnectDefaults]).
private enum Defaults {
    private static let modeKey = "recordingMode"
    private static let speakerKey = "speakerWarningSuppressed"
    private static let voiceProcessingKey = "voiceProcessing"
    private static let consentReminderKey = "consentReminder"

    static var mode: RecordingMode {
        get { UserDefaults.standard.string(forKey: modeKey) == "meeting" ? .meeting : .microphone }
        set { UserDefaults.standard.set(newValue == .meeting ? "meeting" : "microphone", forKey: modeKey) }
    }

    static var speakerWarningSuppressed: Bool {
        get { UserDefaults.standard.bool(forKey: speakerKey) }
        set { UserDefaults.standard.set(newValue, forKey: speakerKey) }
    }

    /// docs/12 M8: on until the user turns it off — the one default here that is not `false`, so it
    /// is the absence of the key and not its value that has to be read.
    static var consentReminder: Bool {
        get { UserDefaults.standard.object(forKey: consentReminderKey) as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: consentReminderKey) }
    }

    /// docs/12 M4-L3 deliverable 4: an option flag and nothing more — off unless it was written by
    /// hand (`defaults write app.recly.mac voiceProcessing -bool YES`). There is no menu item for it,
    /// because v1's answer to echo is headphones.
    static var voiceProcessing: Bool {
        UserDefaults.standard.bool(forKey: voiceProcessingKey)
    }
}
