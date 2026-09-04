import Foundation
import os
import ReclyCore
import RecKit
import SwiftUI
import WatchConnectivity

/// docs/13: the phone's four tabs. Named so that a screen can send the user to another one — the
/// Mac opens a window for that, and a phone switches tabs.
enum PhoneTab: Hashable {
    case record
    case recordings
    case workflows
    case settings
}

/// docs/13 I2·I3: the iPhone's screens, as one model — the macOS `MenuModel` with a phone's entry
/// points instead of a menu bar. It owns the one `CoreBridge`, the one recorder and the one
/// executor for the life of the process, because the screen, the Live Activity's stop button and
/// Siri all have to be stopping the same recording, and the list has to be showing the queue the
/// executor is running.
@MainActor
final class RecordingModel: ObservableObject, RecordingCommands {
    /// One for the process. The intents reach the model through this too — an app launched into the
    /// background to serve `StopRecordingIntent` must find the recorder that is running, not a
    /// second one that knows nothing.
    static let shared = RecordingModel()

    /// What the recorder says it is doing. The screen is drawn from this and nothing else.
    @Published private(set) var state: RecorderState = .idle
    /// The line under the button when nothing is being recorded.
    ///
    /// docs/07 rule 3: a *key*, resolved by [status] where the screen draws it, so a note already
    /// on screen follows a language change. [AppStrings.localized] hands a sentence it does not
    /// know back unchanged.
    @Published private(set) var note = "Opening" {
        // The count belongs to the note it was set with; a new note has none until it says so.
        didSet { noteCount = nil }
    }
    /// The argument of the one note that takes one (`Deferred %@`), set right after the key so that
    /// [status] can format it in the language the screen is being drawn in.
    private var noteCount: Int?
    /// ADR-016: every workflow the document has — a definition says nothing about which device may
    /// run it.
    @Published private(set) var workflows: [WorkflowSummary] = []
    /// Which of them this phone records with: a mirror of the local pointer (ADR-016), resolved
    /// against the document. Nil when this phone has picked none, or points at one the document no
    /// longer resolves; both are "pick one", and the screen says so.
    ///
    /// Read-only from outside, because the pointer is the truth and [selectWorkflow] is the one way
    /// it moves — a value written here would be a second answer that the next observation undoes.
    @Published private(set) var workflowId: String?
    @Published private(set) var elapsed = ""
    /// False until the core is open: there is nothing to record against before that.
    @Published private(set) var isReady = false
    /// docs/03: the title is asked for *after* the recording has ended, and the job is queued once
    /// the answer is in. Non-nil is the prompt on screen.
    @Published var naming: Naming?
    /// docs/13 deliverable 1: a refusal is not something the app can retry its way out of, so the
    /// screen offers the one thing that can undo it — Settings.
    @Published var microphoneDenied = false
    /// docs/13 I3 "목록": the last five recordings, refreshed after every executor pass.
    @Published private(set) var recents: [RecentItem] = []
    /// The signed-in Google account, or nil.
    @Published private(set) var account: String?
    /// docs/07 rule 3: what became of the last sign-in attempt, kept as the failure rather than as
    /// words — [authNote] makes the sentence where the settings tab draws it.
    @Published private(set) var authError: Error?
    /// docs/13 I5. Built once the core is open; the workflow tab waits for it.
    @Published private(set) var workflowEditor: WorkflowsModel?
    /// docs/05 "워크플로우 내보내기 · 가져오기": the settings tab's file section. Built with the core,
    /// like the editor, and nil until then — there is no document to export before the core.
    @Published private(set) var workflowTransfer: WorkflowTransferModel?
    /// docs/09 트렌드 2: where the one operation a ledger row can start — an upload now, a retry —
    /// actually is. `ProcessingButton` owns the *window* around it and this owns the truth, so a
    /// retry that took two seconds looks like two seconds and one that was refused wears no ✓.
    @Published private(set) var action: ProcessingState = .idle
    /// docs/09 화면 원칙 1·4: this install, for the dashboard's header and the About block. Empty
    /// until the core is open, which is the only thing that knows it.
    @Published private(set) var deviceId = ""
    /// Which of the four tabs is on screen, so an action taken on one can land on another —
    /// docs/08 "오류": "check the key" is on the list and the editor it means is a tab away.
    @Published var tab: PhoneTab = .record

    /// A recording that has ended and has not been named yet.
    struct Naming: Identifiable, Equatable {
        let id: String
        var title = ""
    }

    /// docs/10: the user-fixable failures across the queue, folded one line per reason — the
    /// banner at the top of the list, and the local notifications.
    @Published private(set) var alerts: [JobAlert] = []
    /// docs/07 rule 3: what the last delete or disconnect had to say, kept as a message rather than
    /// as words so a line still on screen answers a language change.
    @Published private(set) var message: UiMessage?
    /// Non-nil while the docs/03 delete dialog is up.
    @Published var deleteRequest: DeleteRequest?
    /// Non-nil while the docs/03 disconnect warning is up, with the count it has to name.
    @Published var disconnectPrompt: DisconnectPrompt?
    /// docs/06: how far the last disconnect got, and so whether one is still owed. Stored, because
    /// a relaunch is the most likely place the retry happens from — the account is gone by then and
    /// this is the only thing that keeps the Disconnect row on screen.
    @Published private(set) var disconnectPhase = DisconnectDefaults.phase
    /// docs/03: true while Google is still listing Recly because the revoke failed. It outlives the
    /// disconnect, the phase and the account — only the user's own word clears it.
    @Published private(set) var revokeDebt = DisconnectDefaults.revokeDebt
    /// docs/12 M8: true while the consent reminder is up and the recording is waiting on it.
    @Published var consentPrompt = false
    /// docs/12 M8 · ADR-011: on until the user turns it off. Switching it back on means "ask me
    /// again", so it clears the answer too — a switch that could never make the reminder appear
    /// again would be a dead one.
    @Published var consentReminder: Bool = Defaults.consentReminder {
        didSet {
            Defaults.consentReminder = consentReminder
            if consentReminder { Defaults.consentAsked = false }
        }
    }
    /// docs/11 A5 · parity with Android's `settings_wifi_only`: off until the user turns it on, and
    /// the *next* chunk is what it reaches — the one in flight keeps the network it left on
    /// ([UploadNetwork]).
    @Published var wifiOnly: Bool = UploadNetwork.wifiOnly {
        didSet { UploadNetwork.wifiOnly = wifiOnly }
    }

    private let logger = Logger(subsystem: CoreBridge.appName, category: "shell")
    private let dataDirectory: URL
    /// ADR-006: the wall-clock length the recorder cuts a segment at. Nothing on screen says it —
    /// docs/09 화면 원칙 1 keeps segment boundaries out of the UI.
    private let segmentSec: Int
    private var bridge: CoreBridge?
    private var recorder: SegmentedRecorder?
    private var session: RecorderSession?
    private let activity = RecordingLiveActivity()
    /// docs/06: the core's `TokenProvider`, and the sign-in that fills it.
    private let tokens = AppleTokenProvider()
    private var auth: GoogleAuth?
    /// docs/13 I3 "실행기": the same `JobRunner` the Mac runs (docs/12), plus the phone's own
    /// trigger — the app becoming active.
    private var runner: JobRunner?
    /// ADR-015 · docs/13 I4. Built here rather than inside [load] so that an app relaunched into
    /// `handleEventsForBackgroundURLSession` can reconnect the session without waiting for the
    /// database to open.
    private let transport: BackgroundTransport
    private lazy var background = BackgroundJobs { [weak self] in await self?.runOneJobPass() }
    /// docs/03 "연결 해제" · docs/06: the whole of a disconnect, which is RecKit's and not this
    /// model's — the Mac runs the same one. Lazy because every one of its closures reads `self`.
    private lazy var disconnectFlow = DisconnectFlow(
        device: .phone,
        logger: logger,
        core: { [weak self] in self?.bridge?.core },
        auth: { [weak self] in self?.auth },
        // A capture that is running has no job yet, so `core.disconnect`'s own busy guard — which
        // is over the queue — does not cover it.
        isRecording: { [weak self] in (self?.state ?? .idle) != .idle },
        phase: { [weak self] in self?.disconnectPhase ?? .none },
        debt: { [weak self] in self?.revokeDebt ?? false },
        publishPhase: { [weak self] in self?.disconnectPhase = $0 },
        publishDebt: { [weak self] in self?.revokeDebt = $0 },
        publishMessage: { [weak self] in self?.message = $0 },
        accountChanged: { [weak self] in self?.account = $0 },
        accountRevoked: { [weak self] in
            self?.account = nil
            self?.authError = nil
        },
        refresh: { [weak self] in await self?.refreshRecents() }
    )
    /// docs/13 I6 · M5-L4 deliverable 3: the watch's parts arrive here. Held for the life of the
    /// process because it is `WCSession`'s delegate, which the session does not retain.
    private var watchReceiver: WatchReceiver?
    private var activeObserver: NSObjectProtocol?
    /// docs/07 rule 3: the two things the picker has to reach that are not SwiftUI bodies — the
    /// watch and the Live Activity's own process.
    private var languageObserver: NSObjectProtocol?
    /// docs/10 "iPhone": `UNUserNotificationCenter` 로컬 알림 + 목록 상단 배너. Nothing else in this
    /// process claims the notification delegate, so this is the one that takes it — from
    /// [installNotificationDelegate], which the app calls at launch.
    ///
    /// Built with the model rather than lazily at the first reading of the queue: the delegate has
    /// to be Notification Center's before the response of a tap that *launched* the app is
    /// delivered, which is long before the core is open.
    private let alertNotifier = JobAlertNotifier(subsystem: CoreBridge.appName)
    /// Where that tap waits while there is no editor to take it to (docs/10).
    private let alertRouter = AlertRouter<JobAlert>()
    private var ticker: Timer?
    /// When the recording on screen began — the Live Activity counts up from it.
    private var startedAt: Date?
    /// The one load. Everything an intent can reach waits for it: the app may have been launched
    /// into the background by Siri a moment ago and the core is opened asynchronously.
    private var loading: Task<Void, Never>?

    /// The device's Keychain, and the one seam the tests replace. `xctest` is not the app: its host
    /// has no keychain-access-group entitlement, so every `SecItem*` this process makes answers
    /// `errSecMissingEntitlement` — and the core is right to treat that as a store it cannot read
    /// rather than an empty one, which leaves a test bundle killing itself on a real failure it has
    /// no business producing. The tests hand in an in-memory store and never reach the Keychain.
    private let secureStore: any ReclyCore.SecureStore

    init(
        dataDirectory: URL = CoreBridge.defaultDataDirectory,
        segmentSec: Int = CoreBridge.configuredSegmentSec,
        secureStore: any ReclyCore.SecureStore = KeychainSecureStore()
    ) {
        self.dataDirectory = dataDirectory
        self.segmentSec = segmentSec
        self.secureStore = secureStore
        transport = BackgroundTransport(
            staging: dataDirectory.appendingPathComponent("chunks", isDirectory: true)
        )
        // Before the load and not after it: a cold launch from Siri, the action button or the
        // Control runs `perform()` as soon as the process is up, and an intent that found nothing
        // registered would report success and record nothing. Everything the intents call waits on
        // [loaded] instead.
        RecordingIntentTarget.commands = self
        // The same argument for a notification tap, which is what launched the app just as often:
        // the response is delivered as soon as the launch finishes, and there is nothing to route
        // it to until [load] has built the editor — so it is buffered rather than dropped.
        alertNotifier.onFix = { [weak self] alert in self?.alertRouter.deliver(alert) }
        // Before the load, so that a language picked while the core is still opening is not lost.
        observeLanguage()
        loading = Task { await load() }
    }

    /// docs/10: Notification Center's delegate, installed from `RecPhoneApp.init` — the earliest
    /// this shell runs any code of its own, and long before the core is open. Nothing else in this
    /// process claims the delegate.
    ///
    /// The app's call and not the model's own initialiser: reaching Notification Center is only
    /// legal from an app, and a unit test that builds a model to check the intent registration must
    /// not be made to launch one.
    func installNotificationDelegate() {
        alertNotifier.adoptDelegate()
    }

    /// docs/13 I2: open the core, finish whatever the last run left behind, then offer to record.
    private func load() async {
        do {
            let bridge = try await CoreBridge.make(
                appVersion: CoreBridge.appVersion,
                dataDirectory: dataDirectory,
                secureStore: secureStore,
                tokenProvider: tokens,
                transport: transport
            )
            self.bridge = bridge
            let recorder = SegmentedRecorder(
                core: bridge.core,
                segmentSec: segmentSec,
                // docs/03: the phone records one `mono` track and says `phone` in every meta.
                source: Source.phone
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
            // docs/13 deliverable 2: `RecordingRecovery` before anything else at launch — before
            // anything can touch the directories a killed run left parts in.
            let recovered = await session.recoverIfIdle()
            // And before the first pill of this run: the last one's is still counting up.
            await activity.endStale()
            // docs/05 "첫 기기": the first read seeds the starters on a phone that has never had a
            // document, and — ADR-016 — points this phone's own default at 메모, because what a
            // phone records is far more often one. A phone adopting an existing document seeds
            // nothing and keeps a null pointer, which the record screen nudges about.
            _ = try await bridge.core.workflows.seed(
                preferredDefaultId: WorkflowRepository.companion.MEMO_ID
            )
            workflows = try await bridge.core.workflows.summary()
            observeDeviceDefault(core: bridge.core)
            observeJobs(core: bridge.core)
            observeRecordings(core: bridge.core)
            // [workflowId] comes from the observation above rather than from here: the pointer is
            // what the screen shows, and the seed has just set it on a phone that has never had a
            // document.

            workflowEditor = WorkflowsModel(core: bridge.core)
            workflowTransfer = WorkflowTransferModel(core: bridge.core)
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
            // Slices a killed run staged for chunks nobody is uploading any more (docs/13 I4).
            //
            // Before the executor, and that order is the whole point: the sweep deletes every
            // staged file the session is not carrying, off a snapshot of the running tasks. A pass
            // started first would stage and start chunks *after* that snapshot was taken, and the
            // sweep would delete the file out from under a task that is uploading it.
            await transport.sweep()
            // docs/12 "실행기" (b)·(c) and a pass now: a job the last run left parked is due.
            runner.start()
            // After the executor exists: the meta's ack is only sent once the recording is queued
            // *and* the executor woken, and a part could arrive the instant the session activates.
            openWatchSession(core: bridge.core)
            observeBecomingActive()
            deviceId = bridge.deps.device.deviceId
            isReady = true
            note = "Waiting"
            // The device id, the container path and the workflow names are the user's; counts are
            // what the line is for (as on macOS).
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

    /// ADR-016: which workflow a Start runs is this phone's own local pointer, and it moves without
    /// the document moving — a pick made on the record screen or on the workflows tab, a delete that
    /// cleared it. The document moves without the pointer moving too — an editor save, a pull, a
    /// rename on another device — and the node, the picker, the intents and the watch context read
    /// both. So both are followed, and nothing is a name read once at launch. SKIE hands the core's
    /// `Flow`s over as `AsyncSequence`s.
    private func observeDeviceDefault(core: ReclyCore_) {
        Task { [weak self] in
            for await document in core.workflows.observe() {
                guard let self else { return }
                self.workflows = document.workflows.map { WorkflowSummary(id: $0.id, name: $0.name) }
                let id = try? await core.workflows.deviceDefault()
                self.workflowId = document.workflows.first { $0.id == id }?.id
                // The watch shows whatever this session last published, so a stale list there is
                // a stale list until the next launch — republish on every document move.
                if self.watchReceiver != nil {
                    await self.publishWorkflowsToWatch(core: core)
                }
            }
        }
        Task { [weak self] in
            for await id in core.workflows.observeDeviceDefault() {
                guard let self else { return }
                // Against the document rather than against [workflows]: a pointer at a workflow
                // another device deleted resolves to nothing, which is the nudge.
                let document = try? await core.workflows.current()
                self.workflowId = document?.workflows.first { $0.id == id }?.id
            }
        }
    }

    /// The list while a pass is still running. `onPass` only fires once `runDueJobs` has returned,
    /// and the core claims a job `RUNNING` and carries the whole upload out inside that one call —
    /// so a list fed by the pass alone goes straight from `PENDING` to `DONE` and the State node
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

    /// docs/03: a recording another device made and uploaded arrives as a row with no job at all, so
    /// nothing about the queue moves when a pull adopts one — or drops one whose Drive folder is
    /// gone. The recordings table is what changes, and this is what reads it.
    private func observeRecordings(core: ReclyCore_) {
        Task { [weak self] in
            for await _ in core.recordings.observe() {
                guard let self else { return }
                await self.refreshRecents()
            }
        }
    }

    /// The record screen's picker, and the one way [workflowId] moves: a pick is this phone's own
    /// pointer (ADR-016), which is the same write the workflows tab's row action makes. Nothing is
    /// written to the document — the pointer is local.
    func selectWorkflow(_ id: String) async {
        guard let core = bridge?.core else { return }
        do {
            try await core.workflows.setDeviceDefault(workflowId: id)
        } catch {
            message = .key("Could not save")
            logger.error("shell.workflows.select.failed error=\(String(describing: error), privacy: .private)")
        }
    }

    /// Waits for the core to be open. An intent is served through this; the screen simply redraws
    /// when [isReady] changes.
    func loaded() async {
        await loading?.value
    }

    var isRecording: Bool {
        if case .recording = state { return true }
        return false
    }

    /// The stop is offered while the microphone is still opening too — the session parks it and
    /// serves it the moment there is a recording, which is the whole point of parking it.
    var canStop: Bool {
        switch state {
        case .starting, .recording: return true
        case .idle, .stopping: return false
        }
    }

    var status: String {
        RecorderStatusLine.text(state: state, note: note, count: noteCount)
    }

    // MARK: - Start and stop

    /// docs/12 M8 · ADR-011: a local capture shows the other people nothing at all, so the app says
    /// so once before it records for the first time. The Mac asks before every meeting recording; a
    /// phone cannot tell a meeting from anything else, so the trigger is the first recording and the
    /// settings switch says as much.
    func start() {
        message = nil
        // docs/03: before the question, not after it — a disconnect's clean-up walks the recording
        // directory, and there is nothing to ask about a capture that is about to be refused.
        if let blocker = DisconnectGate.startBlocker() {
            message = blocker
            logger.info("shell.recording.start.refused reason=disconnecting")
            return
        }
        guard !Defaults.askConsent else {
            consentPrompt = true
            return
        }
        Task { _ = await start(workflowId: nil) }
    }

    /// The reminder's answer. [suppress] is the "do not ask again" box, which — as on the Mac —
    /// applies whichever button was pressed; only [confirmed] starts the recording, because this is
    /// a question and Cancel has to mean something.
    func consentAnswered(confirmed: Bool, suppress: Bool) {
        consentPrompt = false
        if suppress { consentReminder = false }
        guard confirmed else { return }
        Defaults.consentAsked = true
        Task { _ = await start(workflowId: nil) }
    }

    func stop() {
        Task { await finish(askingForTitle: true) }
    }

    /// - Returns: what refused the start, or nil when the recorder was asked for one.
    private func start(workflowId: String?) async -> UiMessage? {
        guard let session, isReady else { return nil }
        // docs/03: the gate is held across the start itself rather than merely read before it —
        // `session.start` suspends, and a disconnect that took the gate inside that wait would be
        // walking the recording directory while this capture wrote into it.
        let opened: Void? = await DisconnectGate.ifOpen {
            do {
                // `nil` means the session was not idle — a second tap, or a stop still finishing.
                // The state it published already says so; there is nothing to tell the user.
                guard let recordingId = try await session.start(workflowId: workflowId) else { return }
                startedAt = Date()
                microphoneDenied = false
                await updateActivity()
                logger.info("shell.recording.start id=\(recordingId, privacy: .public)")
            } catch let error as RecorderError where error.kind == .microphoneDenied {
                note = "The microphone permission is needed"
                microphoneDenied = true
            } catch {
                note = "The recording failed"
                logger.error("shell.recording.start.failed error=\(String(describing: error), privacy: .public)")
            }
        }
        guard opened == nil else { return nil }
        // The gate was shut. By a disconnect — which is what to say — or by another start that is
        // already opening, which the session would have refused anyway and has nothing to add.
        let blocker = DisconnectGate.startBlocker()
        if let blocker {
            message = blocker
            logger.info("shell.recording.start.refused reason=disconnecting")
        }
        return blocker
    }

    /// docs/03: a stop finalizes at once; the title comes after that and the job after the title.
    ///
    /// A stop that came from an intent has nobody to ask — the phone is locked and the app is in
    /// the background — so it queues the job straight away. The name can still be given later, and
    /// a recording left unnamed and unqueued is what `RecordingRecovery` picks up anyway.
    private func finish(askingForTitle: Bool) async {
        guard let session else { return }
        switch await session.stop(title: nil) {
        case .notRecording:
            break

        case .deferred(let recordingId, let pending):
            // Not finalized on purpose: there is nothing to name and nothing to queue until the
            // missing parts are filed, which the next recovery pass does.
            note = "Deferred %@"
            noteCount = Int(pending)
            logger.error(
                """
                shell.recording.deferred id=\(recordingId, privacy: .public) \
                pending=\(pending, privacy: .public)
                """
            )

        case .finalized(let outcome):
            note = "Waiting"
            logger.info(
                """
                shell.recording.stop id=\(outcome.recordingId, privacy: .public) \
                parts=\(outcome.parts, privacy: .public) \
                durationSec=\(outcome.durationSec, privacy: .public)
                """
            )
            if askingForTitle {
                naming = Naming(id: outcome.recordingId)
            } else {
                await enqueue(recordingId: outcome.recordingId)
            }
        }
    }

    /// The title prompt's answer. Skipping it is an answer too — the recording keeps the name it
    /// has (none) and the job is queued either way.
    ///
    /// - Parameter participants: how many people were in the room, or nil for "unknown" — docs/03's
    ///   `context.participants`, which docs/08 lets override the workflow's speaker hint.
    func finishNaming(with title: String?, participants: Int? = nil) async {
        guard let naming else { return }
        self.naming = nil
        let trimmed = title?.trimmingCharacters(in: .whitespacesAndNewlines)
        let name = (trimmed?.isEmpty ?? true) ? nil : trimmed
        if name != nil || participants != nil {
            _ = try? await bridge?.core.recordings.updateTitle(
                recordingId: naming.id,
                title: name,
                participants: participants.map { KotlinInt(int: Int32($0)) }
            )
        }
        await enqueue(recordingId: naming.id)
    }

    /// docs/08 결과 파일: the detail screen of one recent recording.
    func detail(for item: RecentItem) -> RecordingDetailModel? {
        guard let core = bridge?.core else { return nil }
        return RecordingDetailModel(core: core, recordingId: item.id, title: item.titleLabel)
    }

    /// docs/08 AUTH_REJECTED: the key is defined in the workflow, so that is where "check the key"
    /// lands — which on a phone means the workflow tab, not just an editor behind the list.
    func editWorkflow(of item: RecentItem) {
        guard let workflowId = item.workflowId, let editor = workflowEditor else { return }
        tab = .workflows
        Task {
            await editor.reload()
            editor.edit(workflowId)
        }
    }

    /// `nil` for the workflow: the pick the user made when they started is in the meta, and that is
    /// what `enqueue` falls back to (docs/05).
    private func enqueue(recordingId: String) async {
        do {
            _ = try await bridge?.core.enqueue(recordingId: recordingId, chosenWorkflowId: nil)
            logger.info("shell.recording.enqueued id=\(recordingId, privacy: .public)")
            // docs/12 "실행기" (a): the job exists now, so a pass runs immediately rather than
            // waiting for the five-minute timer …
            runner?.jobsDue()
            // … and docs/13 deliverable 4: right after the stop — the user is about to put the
            // phone in a
            // pocket, and the steps after the upload need the app to be running.
            background.schedule()
        } catch {
            note = "Could not create the job"
            logger.error("shell.recording.enqueue.failed error=\(String(describing: error), privacy: .public)")
        }
    }

    /// A fatal capture error ends the recording the way the screen's own stop does, and there is
    /// nobody to ask for a title in the middle of a failure.
    private func captureFailed(_ error: RecorderError) {
        logger.error(
            """
            shell.recording.error fatal=\(error.fatal, privacy: .public) \
            \(error.description, privacy: .public)
            """
        )
        guard error.fatal else { return }
        Task { await finish(askingForTitle: false) }
    }

    // MARK: - The intents (docs/13 I7)

    func recordableWorkflows() async -> [WorkflowChoice] {
        await loaded()
        return workflows.map { WorkflowChoice(id: $0.id, name: $0.name) }
    }

    /// docs/12 M8 · ADR-011: an intent is served with the phone locked and the app in the
    /// background, where there is nobody to ask the consent question and no screen to ask it on.
    /// That is a reason to *refuse*, not a reason to skip it — a first recording made by saying
    /// "Hey Siri" is exactly the one the reminder is for. So the start is refused in words until
    /// the reminder has been answered on the screen, or turned off.
    ///
    /// - Returns: nil when the recording was started, or the sentence the intent reports.
    func startFromIntent(workflowId: String?) async -> String? {
        await loaded()
        if let refusal = BackgroundStart.refusal(askConsent: Defaults.askConsent) {
            logger.info("shell.intent.refused reason=consent")
            return refusal
        }
        // docs/03: and the same for a disconnect that is running — the clean-up would delete the
        // recording this intent is about to open. Siri reads the refusal out.
        // ADR-016: the intent's own workflow when the shortcut names one, and otherwise `nil` — the
        // pointer the record screen shows is what the core resolves it to.
        return await start(workflowId: workflowId)?.text
    }

    func stopFromIntent() async {
        await loaded()
        await finish(askingForTitle: false)
    }

    // MARK: - The clock and the Live Activity

    /// The one place the published state moves, so the clock and the pill start and stop with the
    /// recording rather than with whoever asked for it.
    private func adopt(_ next: RecorderState) {
        let wasRecording = isRecording
        state = next
        if isRecording, !wasRecording { startTicking() }
        if !isRecording, wasRecording {
            stopTicking()
            startedAt = nil
        }
        Task { await updateActivity() }
    }

    private func updateActivity() async {
        await activity.apply(
            RecordingActivityPlan.plan(for: state, startedAt: startedAt),
            workflowName: workflows.first { $0.id == workflowId }?.name
        )
    }

    private func startTicking() {
        tick()
        let ticker = Timer(timeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
        RunLoop.main.add(ticker, forMode: .common)
        self.ticker = ticker
    }

    private func stopTicking() {
        ticker?.invalidate()
        ticker = nil
        elapsed = ""
    }

    /// Audio actually written, which is what the recorder counts — a device change that cost three
    /// seconds is not three seconds of recording.
    private func tick() {
        let total = Int((recorder?.recordedSec ?? 0).rounded(.down))
        elapsed = LedgerFormat.elapsed(total)
        // docs/13 "8시간 상한이면 갱신", checked here rather than from a timer of its own.
        Task { await activity.refreshIfNeeded() }
    }

    /// docs/09 화면 원칙 6: the levels behind the live strip under the timer, asked for ten times a
    /// second by the view that draws it — not published, because a `@Published` array at that rate
    /// would redraw the whole screen for a picture. It takes the recorder's own lock and nothing of
    /// the model's, and it answers with an empty array when there is no recording.
    func livePeaks() -> [Float] {
        recorder?.livePeaks() ?? []
    }

    // MARK: - The executor away from the screen (docs/13 I4)

    /// Both `BGTaskScheduler` handlers, from `didFinishLaunching` — the system may be launching the
    /// app *for* one of them, and a handler registered any later is not there when it does.
    func registerBackgroundTasks() {
        background.register()
    }

    /// `application(_:handleEventsForBackgroundURLSession:completionHandler:)`: iOS started the app
    /// again because the upload session has events to deliver. The transport is built in [init], so
    /// by the time this runs the session is already reconnected and the events are on their way.
    func handleBackgroundSessionEvents(completion: @escaping () -> Void) {
        logger.info("shell.upload.relaunch")
        transport.adoptBackgroundEvents(completion: completion)
        // The chunks that just landed are not a finished job: `meta.json` and the webhook are still
        // core work, and the core only runs while the app does.
        background.schedule()
    }

    /// The pass a granted `BGProcessingTask` buys. One pass, not a loop: the successor [JobRunner]
    /// arms is a `Timer`, which does not fire once the app is suspended again — what brings the
    /// next pass is the next grant, and an upload still in flight asks for one through
    /// [handleBackgroundSessionEvents]. It waits for the core because the app may have been
    /// launched into the background for exactly this.
    private func runOneJobPass() async {
        await loaded()
        guard let runner else { return }
        await runner.run().value
    }

    /// The phone's fifth trigger, on top of the four [JobRunner] has of its own: coming back to the
    /// app is the moment a parked job most often becomes runnable — the user just signed in, or the
    /// phone just found a network.
    private func observeBecomingActive() {
        activeObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                _ = self.runner?.run()
                // Whatever relaunch a latched upload finish belonged to, it ended here: nobody came
                // for it with a system completion handler (docs/13 I4).
                self.transport.clearEarlyFinish()
            }
        }
    }

    /// docs/07 rule 3: a language picked in Settings has to reach the surfaces SwiftUI's `\.locale`
    /// does not — the watch, which has no setting of its own (rule 2), and the Live Activity, which
    /// is drawn by another process from what the pill carries. Both are told at once rather than at
    /// their next activation, which for a watch already connected and a pill already on the Lock
    /// Screen would be never.
    private func observeLanguage() {
        languageObserver = NotificationCenter.default.addObserver(
            forName: AppLanguage.didChange,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                // [watchReceiver] is the only proof there is a `WCSession` to publish on: it is
                // set exactly when `isSupported()` was true.
                if let core = self.bridge?.core, self.watchReceiver != nil {
                    Task { await self.publishWorkflowsToWatch(core: core) }
                }
                Task { await self.updateActivity() }
                // A job alert already standing in Notification Center was painted once and is still
                // in the old language; posting it again under the same identifier replaces it.
                Task { await self.alertNotifier.relocalize() }
            }
        }
    }

    // MARK: - The watch (docs/13 I6)

    /// docs/13 deliverable 3: the watch's parts come in here, and the workflow summary goes out to
    /// the watch on `updateApplicationContext`.
    ///
    /// The context is published from here and nowhere else on purpose: it is a *replacing* snapshot,
    /// so the list the watch shows is whatever this session last set, and a watch that was out of
    /// range simply gets it when it comes back.
    private func openWatchSession(core: ReclyCore_) {
        guard WCSession.isSupported() else { return }
        let receiver = WatchReceiver(
            core: CoreWatchTransfer(core: core) { [weak self] in
                await MainActor.run {
                    // The same two triggers a stop of the phone's own recording fires.
                    self?.runner?.jobsDue()
                    self?.background.schedule()
                }
            },
            acks: WCSessionAcks(),
            staging: dataDirectory.appendingPathComponent("watch", isDirectory: true)
        )
        // On every activation, not once: a session that was refused the context while it was still
        // activating would leave the watch with an empty picker until the next launch.
        receiver.onActivated = { [weak self] in
            Task { @MainActor in await self?.publishWorkflowsToWatch(core: core) }
        }
        watchReceiver = receiver
        WCSession.default.delegate = receiver
        WCSession.default.activate()
    }

    /// docs/05 "워치" row: the workflows and only their names — the watch never runs a step and never
    /// touches Drive (ADR-002), and after ADR-016 an id and a name are the whole of a definition it
    /// could act on. Which of them this watch starts with is the watch's own local pointer and never
    /// travels this way.
    ///
    /// docs/07 rule 2: the app's language rides along, because the watch has no language setting of
    /// its own and following the phone is the only choice a user ever made about it. What rides is
    /// the *resolved* tag rather than the raw choice: `system` read on the watch means the watch's
    /// own locale, which is the one thing the watch must not fall back to while a phone is telling
    /// it.
    private func publishWorkflowsToWatch(core: ReclyCore_) async {
        do {
            let watchable = try await core.workflows.summary()
                .map { WatchWorkflow(id: $0.id, name: $0.name) }
            try WCSession.default.updateApplicationContext(
                WatchWorkflows.context(watchable, language: AppLanguage.resolvedCode)
            )
            logger.info("watch.workflows count=\(watchable.count, privacy: .public)")
        } catch {
            // A session that is not paired refuses the context; there is nothing to do about it and
            // nothing is lost — a recording that carries no pick runs this phone's own default
            // workflow (ADR-016).
            logger.info("watch.workflows.skipped error=\(String(describing: error), privacy: .public)")
        }
    }

    // MARK: - Sign-in (docs/06 · docs/13 I3)

    /// False while `Info.plist` still carries the placeholder client id — the settings tab offers
    /// the README procedure rather than a sign-in that cannot succeed.
    var canSignIn: Bool { GoogleAuth.isConfigured }

    /// The last sign-in failure in words, made where the settings tab draws it (docs/07 rule 3).
    /// `GoogleAuth.Failure.message` is itself resolved on read; anything else has nothing to say
    /// beyond that it failed.
    var authNote: String? {
        guard let authError else { return nil }
        return (authError as? GoogleAuth.Failure)?.message
            ?? AppStrings.localized("The sign-in failed")
    }

    /// docs/03: why the settings tab's sign-in is refused, or nil when it is not. A second account
    /// signed in over a disconnect that still owes its local clean-up is one the retry could revoke
    /// by mistake, so the sign-in is what waits.
    var signInBlocker: UiMessage? { DisconnectGuard.signInBlocker(pending: disconnectPhase.owed) }

    func signIn() {
        if let blocker = signInBlocker {
            message = blocker
            return
        }
        guard let auth, let anchor = Self.anchor() else { return }
        Task {
            do {
                account = try await auth.signIn(presenting: anchor)
                authError = nil
                logger.info("auth.signIn.ok")
                // docs/06: a job parked in NEEDS_AUTH resumes when the user signs in.
                await unpark()
            } catch GoogleAuth.Failure.canceled {
                // The user closed the consent sheet. Nothing failed, so nothing is said.
                authError = nil
                logger.info("auth.signIn.canceled")
            } catch {
                authError = error
                logger.error("auth.signIn.failed error=\(String(describing: error), privacy: .public)")
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
            authError = nil
            logger.info("auth.signOut")
        }
    }

    /// Every job that was only waiting for a sign-in goes back to `PENDING`, then one pass.
    private func unpark() async {
        guard let core = bridge?.core else { return }
        await ParkedJobs.unpark(core: core)
        runner?.jobsDue()
    }

    /// `GIDSignIn` presents the consent web view from a view controller, and SwiftUI hands none
    /// out — the key window's root is the one the user is looking at.
    private static func anchor() -> UIViewController? {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first { $0.isKeyWindow }?
            .rootViewController
    }

    // MARK: - The list (docs/13 I3 "목록")

    /// A pass has finished: the list is redrawn from the queue it left behind, and the banner and
    /// the notifications are folded out of the same reading ([publishAlerts]). docs/10 replaced the
    /// sign-in-only banner this used to raise — `NEEDS_AUTH` is one of its seven reasons.
    private func passFinished() {
        Task { await refreshRecents() }
    }

    /// docs/13 I3: the phone's Recordings tab is a screen and not the Mac's popover, so it reads as
    /// deep as Android's list does (`JobsViewModel.LIMIT`) — enough for months of daily recordings,
    /// shallow enough to join in one pass. [Recents.page] is what the desktop ledger reads at a time.
    private static let recentsLimit: Int32 = 100

    func refreshRecents() async {
        guard let core = bridge?.core else { return }
        do {
            recents = try await Recents.load(core: core, limit: Self.recentsLimit)
            // The states are the list's whole content and none of them is the user's text — the
            // titles are, and they stay out of the log (as on macOS).
            logger.info("shell.recents states=\(self.recents.map(\.state).joined(separator: ","), privacy: .public)")
        } catch {
            logger.error("shell.recents.failed error=\(String(describing: error), privacy: .private)")
        }
        await publishAlerts()
    }

    /// docs/03: what the other devices have uploaded since this one last looked. The list itself is
    /// drawn from what is already here — this runs beside it and never in front of it, and the rows
    /// a pull adopts (or drops) come back through [observeRecordings] whenever Drive answers.
    ///
    /// `force`, because the screen coming up is the user asking: the throttle is for the job pass.
    func pullRemoteRecordings() async {
        guard let core = bridge?.core else { return }
        _ = try? await core.pullRemoteRecordings(force: true)
    }

    // MARK: - The failures a person has to fix (docs/10)

    /// docs/10 rule 3: what is standing is whatever this reading of the queue says, empty included
    /// — a reason that has been cleared is withdrawn from Notification Center by the same call that
    /// takes it off the banner. Every reading goes through here, so the two cannot drift.
    ///
    /// The *queue*, and not [recents]: the ledger is the newest five recordings, so a job blocked
    /// before those had its banner line and its notification taken away by nothing more than five
    /// newer recordings — while it was still blocked.
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
    /// '앱 열기'로 끝내지 않는다." On a phone that means a tab, and for the two reasons a workflow
    /// holds the fix for, the editor open on the definition that has to change.
    func fix(_ alert: JobAlert) {
        switch alert.reason.fix {
        case .signIn:
            tab = .settings

        case .driveStorage:
            openDriveStorage()

        // docs/08 "오류": the key is the thing to look at, and looking at it means the form it is
        // entered in — not the editor with the form still to be found. The step named it, so the
        // form opens under that step (`SecretFormView` draws where `form.stepId` points) with the
        // key that was refused already in the name field.
        case .secrets:
            openEditor(alert.workflowId) { editor in
                editor.openSecrets(prefill: alert.secret, step: alert.stepId)
            }

        case .editor:
            openEditor(alert.workflowId) { _ in }
        }
    }

    /// docs/10 "Drive 용량 초과": the one fix that leaves the app, because the space is Google's to
    /// give back. Offered on the ledger row as well as on the banner.
    func openDriveStorage() {
        UIApplication.shared.open(driveStorageURL)
    }

    /// The workflow tab, with the editor open on one definition — and then whatever the fix has to
    /// do inside it.
    private func openEditor(_ workflowId: String?, then: @escaping (WorkflowsModel) -> Void) {
        tab = .workflows
        guard let workflowId, let editor = workflowEditor else { return }
        Task {
            await editor.reload()
            editor.edit(workflowId)
            then(editor)
        }
    }

    // MARK: - Deleting a recording (docs/03 "앱에서 지우기")

    /// The dialog is asked every time, because the Drive half of it is a separate question whose
    /// answer is never remembered. The part count is read here rather than carried on every row —
    /// only the recording being deleted needs it.
    ///
    /// Only the newest ask becomes the question. The count is a trip to the core, so two taps in a
    /// row are two reads in flight, and the slower one finishing last would otherwise replace the
    /// dialog the user is looking at with the row they left behind.
    func confirmDelete(_ item: RecentItem) {
        guard let core = bridge?.core else { return }
        deleteAsked += 1
        let asked = deleteAsked
        Task {
            let unuploaded = await Retention.unuploadedParts(core: core, recordingId: item.id)
            guard asked == self.deleteAsked else { return }
            deleteRequest = DeleteRequest(
                recordingId: item.id,
                title: item.titleLabel,
                unuploaded: unuploaded,
                remote: item.remote
            )
        }
    }

    /// The answer that deletes nothing — and the answer to a question that was never asked, which is
    /// what a dismissal is while a count is still being read: the counter goes up, so the read that
    /// comes back has nothing left to ask about.
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
                self.message = .key("Still uploading — try again once it has finished")
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
    /// user about to lose the queue deserves to know what is still only on this phone.
    func askToDisconnect() {
        guard let core = bridge?.core else { return }
        Task {
            disconnectPrompt = DisconnectPrompt(
                unuploaded: await Retention.unuploadedRecordings(core: core),
                // A capture that is running has no job yet, so `core.disconnect`'s own busy guard —
                // which is over the queue — does not cover it, and "also delete the recordings"
                // would delete the one being written. Read here rather than when the button is
                // pressed so the dialog can say so instead of refusing silently.
                recording: state != .idle
            )
        }
    }

    func cancelDisconnect() {
        disconnectPrompt = nil
    }

    /// docs/03 "연결 해제" · docs/06, all of it in [DisconnectFlow]: the phone and the Mac were running
    /// the same two hundred lines side by side, and what they differ by is what [disconnectFlow]
    /// is built with.
    func disconnect(alsoDeleteRecordings: Bool) {
        // The second half of a double-tap must not catch the re-presented prompt below and confirm a
        // warning nobody has read: from the first activation until its re-read decides, every
        // further activation is a no-op.
        guard let shown = disconnectPrompt, !disconnectChecking else { return }
        disconnectChecking = true
        perform {
            defer { self.disconnectChecking = false }
            // What the dialog promised is read again before it is acted on; a warning it never
            // showed re-asks instead of destroying quietly (RecKit, and the Mac asks the same).
            if let fresh = await DisconnectPrompt.rewarning(
                core: self.bridge?.core,
                recording: self.state != .idle,
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
        UIApplication.shared.open(url)
    }

    func dismissMessage() {
        message = nil
    }

    /// docs/10: `FAILED`·`SKIPPED_SHORT`·`NEEDS_AUTH` go back to `PENDING` with a fresh budget.
    ///
    /// docs/13 deliverable 4: a retry is the one thing on this screen that asks for an upload
    /// *now*, so it is what carries the background pass — the foreground executor only runs while
    /// the app is on screen, and a user who taps Retry and locks the phone asked for the upload,
    /// not for the app.
    func retry(_ item: RecentItem) {
        guard let jobId = item.jobId else { return }
        perform {
            guard let core = self.bridge?.core else { return false }
            guard (try? await core.jobs.retry(jobId: jobId).boolValue) == true else {
                self.note = "This cannot be retried right now"
                return false
            }
            self.runner?.jobsDue()
            // Unconditionally, and before the iOS 26 path: `uploadNow` is `#available`-gated and
            // does nothing at all below it, so this is the only thing that brings the job back if
            // the user leaves the app before the upload has a background task of its own.
            self.background.schedule()
            self.background.uploadNow(recordingId: item.id, title: item.titleLabel)
            return true
        }
    }

    /// A row button's window (docs/09 트렌드 2): the action reports its own outcome, so a retry that
    /// could not be made due shows no ✓. [action] is moved *before* the `Task`, not inside it: the
    /// button reads it the moment it is tapped, and a hop to the next main-actor turn would let the
    /// previous operation's `.done` be the state a fresh tap sees.
    private func perform(_ work: @escaping () async -> Bool) {
        action = .processing
        Task {
            let succeeded = await work()
            action = succeeded ? .done : .failed
        }
    }

    func openInDrive(_ item: RecentItem) {
        guard let link = item.link else { return }
        UIApplication.shared.open(link)
    }

    // MARK: - Settings

    /// docs/13 deliverable 1: the deep link the microphone refusal is answered with.
    func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }

}

/// The shell's own settings. `UserDefaults` and not the core: none of them is worth syncing between
/// devices — whether *this* phone has already been reminded about consent is a fact about it.
///
/// The two a disconnect leaves behind are not here: they are written *before* the credentials they
/// are about are deleted and read back by the same rules on the Mac, so they live in RecKit
/// ([DisconnectDefaults]).
private enum Defaults {
    private static let consentReminderKey = "consentReminder"
    private static let consentAskedKey = "consentAsked"

    /// docs/12 M8: on until the user turns it off — the one default here that is not `false`, so it
    /// is the absence of the key and not its value that has to be read (the Mac's `Defaults` reads
    /// the same setting the same way).
    static var consentReminder: Bool {
        get { UserDefaults.standard.object(forKey: consentReminderKey) as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: consentReminderKey) }
    }

    /// True once the reminder has been answered, either way.
    static var consentAsked: Bool {
        get { UserDefaults.standard.bool(forKey: consentAskedKey) }
        set { UserDefaults.standard.set(newValue, forKey: consentAskedKey) }
    }

    /// docs/12 M8: the Mac asks before every meeting recording; a phone cannot tell a meeting from
    /// anything else, so it asks once — the setting *and* the answer together.
    static var askConsent: Bool { consentReminder && !consentAsked }
}
