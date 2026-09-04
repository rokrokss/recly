import Foundation
import os
import ReclyCore
import RecKit
import SwiftUI
import WatchKit
import WidgetKit

/// docs/13 WA2·WA3: the watch's one screen, as one model. It owns the one `CoreBridge`, the one
/// recorder and the one transfer queue for the life of the process, because the screen, the
/// complication's tap and the action button all have to be driving the same recording.
///
/// What it deliberately does *not* own is an executor or a `TokenProvider`: the watch never talks to
/// Drive (ADR-002). A recording's whole life here is record → hand to the phone → delete on the
/// phone's ack.
@MainActor
final class WatchRecordingModel: ObservableObject, WatchRecordingCommands {
    /// One for the process. The intents reach the model through this — an app launched by the
    /// action button must find the recorder that is running, not a second one that knows nothing.
    static let shared = WatchRecordingModel()

    @Published private(set) var state: RecorderState = .idle
    /// docs/07 rule 3: a *key*, resolved by [status] where the screen draws it.
    @Published private(set) var note = "Opening" {
        // The count belongs to the note it was set with; a new note has none until it says so.
        didSet { noteCount = nil }
    }
    /// The argument of the one note that takes one (`Deferred %@`), set right after the key.
    private var noteCount: Int?
    @Published private(set) var elapsed = ""
    @Published private(set) var isReady = false
    /// Recordings the phone has not acked yet — the audio is still on this watch until it does.
    @Published private(set) var waiting = 0
    /// docs/13 deliverable 3: the summary the phone publishes with `updateApplicationContext`.
    @Published private(set) var workflows: [WatchWorkflow] = []
    /// ADR-016 on the watch: the phone keeps one local pointer at the workflow *it* runs, and this
    /// watch keeps its own. Tapping a name in the picker sets it, and the recording carries it to
    /// the phone as the chosen id — so the two are never merged and neither is ever written into
    /// `workflows.json`. `nil` is the honest starting state, not "the first workflow": the phone's
    /// own default is what runs then, which is what a user who has never opened the picker expects.
    @Published private(set) var workflowId: String?
    /// A refusal is not something the app can retry its way out of; the screen says so.
    @Published private(set) var microphoneDenied = false

    private let logger = Logger(subsystem: CoreBridge.appName, category: "shell")
    private let dataDirectory: URL
    private let segmentSec: Int
    private let link = WatchLink()
    private var bridge: CoreBridge?
    private var recorder: SegmentedRecorder?
    private var session: RecorderSession?
    private var queue: WatchTransferQueue?
    private var activeObserver: NSObjectProtocol?
    /// docs/07 rule 3: the complication is drawn by another process from a file, so a language
    /// change has to be written into it rather than waiting for the next recording.
    private var languageObserver: NSObjectProtocol?
    private var ticker: Timer?
    private var startedAt: Date?
    private var loading: Task<Void, Never>?
    /// The stored pointer, kept apart from [workflowId] so a workflow the phone has temporarily
    /// stopped publishing does not silently lose the user's choice: what the screen shows is this
    /// resolved against the last publish, and it comes back when the workflow does.
    private var storedWorkflowId = Defaults.workflowId

    init(
        dataDirectory: URL = CoreBridge.defaultDataDirectory,
        segmentSec: Int = CoreBridge.configuredSegmentSec
    ) {
        self.dataDirectory = dataDirectory
        self.segmentSec = segmentSec
        // Before the load: the action button and the complication run their intent as soon as the
        // process is up, and an intent that found nothing registered would report success and
        // record nothing.
        WatchRecordingTarget.commands = self
        // And before the load too, so that the acks for a transfer that finished while the app was
        // gone are already queued when the queue opens.
        link.activate()
        link.onWorkflows = { [weak self] workflows in
            Task { @MainActor in self?.adopt(workflows: workflows) }
        }
        // docs/07 rule 2: the watch has no language setting of its own; the phone's arrives with
        // the workflow list and is applied where it stands.
        link.onLanguage = { choice in
            Task { @MainActor in AppLanguage.shared.choice = choice }
        }
        // Before the load: the phone's context can land while the core is still opening.
        observeLanguage()
        loading = Task { await load() }
    }

    private func load() async {
        do {
            let bridge = try await CoreBridge.make(appVersion: CoreBridge.appVersion, dataDirectory: dataDirectory)
            self.bridge = bridge
            let recorder = SegmentedRecorder(
                core: bridge.core,
                segmentSec: segmentSec,
                // docs/03: the watch records one `mono` track and says `watch` in every meta.
                source: Source.watch
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
            // docs/13 deliverable 1: recovery first at launch — before anything can touch the
            // directories a killed run left parts in.
            let recovered = await session.recoverIfIdle()

            let queue = WatchTransferQueue(
                link: link,
                recordings: CoreWatchRecordings(core: bridge.core),
                file: dataDirectory.appendingPathComponent("transfer.json")
            )
            self.queue = queue
            // Everything the watch still holds belongs to the phone: a recording finalized by the
            // recovery pass above was never queued by the stop that never ran, and one queued by an
            // earlier run is already in the file (`add` is idempotent).
            await enqueueEverythingFinalized(core: bridge.core, into: queue)
            link.adopt(queue)
            await refreshWaiting()

            observeBecomingActive()
            isReady = true
            note = "Idle"
            logger.info(
                """
                shell.ready device=\(bridge.deps.device.deviceId, privacy: .private) \
                dataDir=\(bridge.dataDirectory.path, privacy: .private) \
                recovered=\(recovered, privacy: .public)
                """
            )
        } catch {
            note = "Core error"
            logger.error("shell.failed error=\(String(describing: error), privacy: .private)")
        }
    }

    func loaded() async {
        await loading?.value
    }

    var isRecording: Bool {
        if case .recording = state { return true }
        return false
    }

    var canStop: Bool {
        switch state {
        case .starting, .recording: return true
        case .idle, .stopping: return false
        }
    }

    var status: String {
        RecorderStatusLine.text(state: state, note: note, count: noteCount)
    }

    // MARK: - The pick

    /// The picker's only write: this watch's own default, stored now so the next launch starts on it.
    func selectWorkflow(_ id: String?) {
        storedWorkflowId = id
        Defaults.workflowId = id
        workflowId = resolvedWorkflowId
    }

    /// A pick the user made outlives a republish, but only while the published list still has it:
    /// the phone can delete the workflow this watch was pointing at, and silently recording against
    /// one that is gone would be worse than falling back to the phone's own default.
    private func adopt(workflows: [WatchWorkflow]) {
        self.workflows = workflows
        workflowId = resolvedWorkflowId
    }

    private var resolvedWorkflowId: String? {
        storedWorkflowId.flatMap { id in workflows.contains { $0.id == id } ? id : nil }
    }

    // MARK: - Start and stop

    func start() {
        Task { await start(workflowId: workflowId) }
    }

    func stop() {
        Task { await finish() }
    }

    func startFromIntent() async {
        await loaded()
        await start(workflowId: workflowId)
    }

    func stopFromIntent() async {
        await loaded()
        await finish()
    }

    private func start(workflowId: String?) async {
        guard let session, isReady else { return }
        do {
            guard let recordingId = try await session.start(workflowId: workflowId) else { return }
            startedAt = Date()
            microphoneDenied = false
            // docs/13 WA5.
            WKInterfaceDevice.current().play(.start)
            logger.info("shell.recording.start id=\(recordingId, privacy: .public)")
        } catch let error as RecorderError where error.kind == .microphoneDenied {
            note = "The microphone permission is needed"
            microphoneDenied = true
        } catch {
            note = "The recording failed"
            logger.error("shell.recording.start.failed error=\(String(describing: error), privacy: .public)")
        }
    }

    /// docs/03: a stop finalizes at once. There is no title prompt on a watch and no job to queue —
    /// the recording's next step is the phone, so the queue takes it and `pump` hands it over.
    private func finish() async {
        guard let session else { return }
        switch await session.stop(title: nil) {
        case .notRecording:
            break

        case .deferred(let recordingId, let pending):
            // Not finalized on purpose, so nothing is handed over that is missing a part: the next
            // recovery pass files them and the launch-time sweep queues the recording then.
            note = "Deferred %@"
            noteCount = Int(pending)
            logger.error(
                """
                shell.recording.deferred id=\(recordingId, privacy: .public) \
                pending=\(pending, privacy: .public)
                """
            )

        case .finalized(let outcome):
            note = "Idle"
            WKInterfaceDevice.current().play(.stop)
            logger.info(
                """
                shell.recording.stop id=\(outcome.recordingId, privacy: .public) \
                parts=\(outcome.parts, privacy: .public) \
                durationSec=\(outcome.durationSec, privacy: .public)
                """
            )
            await hand(over: outcome.recordingId)
        }
    }

    /// The recording goes on the queue and the queue goes to the phone. Nothing is deleted here —
    /// `ack-meta ok:true` is the only thing that deletes audio from this watch (docs/03).
    private func hand(over recordingId: String) async {
        guard let core = bridge?.core, let queue else { return }
        guard let record = try? await core.recordings.get(id: recordingId) else { return }
        await queue.add(record)
        await queue.pump()
        await refreshWaiting()
        // docs/13 WA5: the third haptic is for "it is out of your hands", which is what queuing it
        // for the phone means — the ack itself may be minutes away.
        WKInterfaceDevice.current().play(.success)
    }

    private func captureFailed(_ error: RecorderError) {
        logger.error(
            """
            shell.recording.error fatal=\(error.fatal, privacy: .public) \
            \(error.description, privacy: .public)
            """
        )
        guard error.fatal else { return }
        Task { await finish() }
    }

    // MARK: - The queue

    private func enqueueEverythingFinalized(core: ReclyCore_, into queue: WatchTransferQueue) async {
        do {
            for record in try await core.recordings.list(limit: 50)
            where record.meta.status == RecordingStatus.finalized {
                await queue.add(record)
            }
        } catch {
            logger.error("transfer.sweep.failed error=\(String(describing: error), privacy: .public)")
        }
    }

    private func refreshWaiting() async {
        guard let queue else { return }
        waiting = await queue.waiting
        publishStatus()
    }

    /// docs/13: unacked items are sent again when the app becomes active. The complication's tap
    /// and the action button both come
    /// through here as well, because both bring the app to the front.
    private func observeBecomingActive() {
        activeObserver = NotificationCenter.default.addObserver(
            forName: WKApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, let queue = self.queue else { return }
                Task {
                    await queue.pump()
                    await self.refreshWaiting()
                }
            }
        }
    }

    // MARK: - The clock and the complication

    private func adopt(_ next: RecorderState) {
        let wasRecording = isRecording
        state = next
        if isRecording, !wasRecording { startTicking() }
        if !isRecording, wasRecording {
            stopTicking()
            startedAt = nil
        }
        publishStatus()
    }

    /// docs/13 진입점: the complication is drawn from a file in the app group, so every state change
    /// writes it and asks WidgetKit to redraw.
    private func publishStatus() {
        WatchStatusStore.save(
            WatchStatus(
                state: isRecording ? .recording : .idle,
                startedAt: isRecording ? startedAt : nil,
                waiting: waiting,
                // docs/07 rule 2: the phone's choice, which the extension has no other way to read.
                language: AppLanguage.current.code ?? ""
            )
        )
        WidgetCenter.shared.reloadAllTimelines()
    }

    /// docs/07 rule 3: the phone's language arrives on the application context and the screen
    /// follows it through `\.locale`, but the complication is another process reading a file — so
    /// the file is rewritten and the timelines reloaded, where they stand.
    private func observeLanguage() {
        languageObserver = NotificationCenter.default.addObserver(
            forName: AppLanguage.didChange,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.publishStatus() }
        }
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

    /// Audio actually written, which is what the recorder counts.
    private func tick() {
        let total = Int((recorder?.recordedSec ?? 0).rounded(.down))
        elapsed = LedgerFormat.elapsed(total)
    }
}

/// The watch's own settings. `UserDefaults` and not the core: which workflow *this* watch starts a
/// recording with is a fact about it (ADR-016 · 원칙 2) and nothing about it is synced — the phone
/// never publishes it and never reads it.
private enum Defaults {
    private static let workflowKey = "defaultWorkflowId"

    /// `nil` removes the key, which is the same state a watch that has never chosen is in.
    static var workflowId: String? {
        get { UserDefaults.standard.string(forKey: workflowKey) }
        set { UserDefaults.standard.set(newValue, forKey: workflowKey) }
    }
}

/// The queue's one call into the core: row, parts and the whole directory, once the phone has said
/// it has them (docs/03 "워치는 폰 ack 즉시 삭제").
private final class CoreWatchRecordings: WatchRecordings {
    private let core: ReclyCore_

    init(core: ReclyCore_) {
        self.core = core
    }

    func delete(recordingId: String) async {
        try? await core.recordings.delete(recordingId: recordingId)
    }
}
