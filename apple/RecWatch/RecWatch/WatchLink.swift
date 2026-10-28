import Foundation
import os
import RecKit
import WatchConnectivity

/// The watch's end of `WCSession` (docs/13 "Apple Watch" Transfer, M5-L4 deliverable 2): the
/// queue's way out, the acks' way in, the phone's language setting's way in, and the recorder's state
/// on its way out (docs/03 "Watch → phone transfer contract").
///
/// It is built before the core is — activating the session early is what lets the acks for a
/// transfer that finished while the app was gone be waiting when the queue opens — so the queue is
/// handed over afterwards through [adopt]. An ack that arrives before that is kept and acted on
/// there: the phone sent it once, and in a background launch for the acks they all arrive the moment
/// the session activates, long before the core is open.
final class WatchLink: NSObject, WCSessionDelegate, WatchTransferLink {
    /// Set once the core is open. Delegate callbacks arrive on `WCSession`'s own queue — hence the lock,
    /// which also covers [early] and [previous].
    private var queue: WatchTransferQueue?
    /// The acks that arrived before [queue], in arrival order.
    private var early: [TransferAck] = []
    /// The ack handling that is running or waiting, so the next one goes behind it and [settle] can
    /// wait for the last.
    private var previous: Task<Void, Never>?
    /// The recorder's state as last published. Kept because the session refuses a context until it
    /// has activated, and the activation publishes it then.
    private var recording: WatchRecordingState?
    /// [recording] has not gone out as a live message yet — the phone was out of reach, or the session
    /// had not said it was in reach — so it goes when [sessionReachabilityDidChange] says so.
    private var recordingOwed = false
    private let lock = NSLock()
    /// docs/07 rule 2: the phone's language setting, which the watch follows.
    var onLanguage: ((AppLanguage.Choice) -> Void)?
    /// Every ack the queue has acted on — true when it finished a recording (`ack-meta ok:true`).
    var onAck: ((Bool) -> Void)?

    private let session: WCSession = .default
    private let logger = Logger(subsystem: CoreBridge.appName, category: "transfer")

    /// What the phone is woken with. Its content is never read: the message is the wake.
    private static let wakeMessage = ["wake": true]

    func activate() {
        session.delegate = self
        session.activate()
    }

    /// Hands over the queue, acts on the acks that came before it and whatever the phone has already
    /// said, then sends what is still owed. Also the launch-time resend of docs/13: `WCSession` keeps
    /// a queued transfer across a kill, but a part it never got to keeps its place in the queue file
    /// instead.
    func adopt(_ queue: WatchTransferQueue) {
        lock.lock()
        self.queue = queue
        let early = self.early
        self.early = []
        lock.unlock()
        // The context is a snapshot, not a message: whatever is standing now is the current one.
        adopt(context: session.receivedApplicationContext)
        for ack in early { receive(ack, into: queue) }
        // Behind the acks, so a part the phone has already filed is not sent again.
        serially { await queue.pump() }
    }

    /// docs/13 "Apple Watch" Transfer: what a WatchConnectivity background launch waits on before the
    /// watch may be suspended again — whatever the session still has to deliver to this launch, then
    /// the queue acting on it. Bounded: the launch has a budget, and an ack not acted on is only the
    /// recording waiting until the app runs next.
    func settle() async {
        var waited = 0
        while session.hasContentPending, waited < 30 {
            waited += 1
            try? await Task.sleep(for: .milliseconds(100))
        }
        let last = lock.withLock { previous }
        await last?.value
    }

    // MARK: - WatchTransferLink

    var outstandingTransfers: [[String: Any]] {
        session.outstandingFileTransfers.compactMap { $0.file.metadata }
    }

    func transfer(_ file: URL, metadata: [String: Any]) {
        session.transferFile(file, metadata: metadata)
        logger.info("transfer.sent file=\(file.lastPathComponent, privacy: .public)")
    }

    /// A live message is the only thing that launches the iPhone app in the background, and only
    /// while this app is in front — or in a high-priority background — with the phone in range
    /// (`isReachable`). It fails harmlessly: the transfers stay queued for the phone app's next run.
    func wake() {
        guard session.activationState == .activated, session.isReachable else {
            logger.info("transfer.wake.unreachable")
            return
        }
        session.sendMessage(Self.wakeMessage, replyHandler: nil) { [logger] error in
            logger.info("transfer.wake.failed error=\(String(describing: error), privacy: .public)")
        }
        logger.info("transfer.wake")
    }

    /// docs/03 "Watch → phone transfer contract": the phone shows that the watch is recording. The context
    /// is what the phone reads whenever its app runs next; the live message is what reaches the app now,
    /// because a context does not wake it — sent only while the phone is in reach, and fails harmlessly.
    func publish(_ state: WatchRecordingState) {
        lock.withLock {
            recording = state
            recordingOwed = true
        }
        send(state)
    }

    private func send(_ state: WatchRecordingState) {
        // Before the activation the context is refused; [activationDidCompleteWith] sends what is kept.
        guard session.activationState == .activated else { return }
        let context = WatchRecordingContext.context(state)
        do {
            try session.updateApplicationContext(context)
        } catch {
            // No phone paired, or no Recly on it: there is nobody to tell.
            logger.info("transfer.recording.skipped error=\(String(describing: error), privacy: .public)")
            return
        }
        let live = session.isReachable
        logger.info("transfer.recording recording=\(state != .idle, privacy: .public) live=\(live, privacy: .public)")
        guard live else { return }
        lock.withLock { recordingOwed = false }
        session.sendMessage(context, replyHandler: nil) { [logger] error in
            logger.info("transfer.recording.failed error=\(String(describing: error), privacy: .public)")
        }
    }

    // MARK: - WCSessionDelegate

    func session(
        _ session: WCSession,
        activationDidCompleteWith activationState: WCSessionActivationState,
        error: Error?
    ) {
        if let error {
            logger.error("transfer.session.failed error=\(String(describing: error), privacy: .public)")
            return
        }
        logger.info("transfer.session state=\(activationState.rawValue, privacy: .public)")
        if activationState == .activated, let recording = lock.withLock({ self.recording }) { send(recording) }
        // docs/13: unacked items are resent when the app activates and when `WCSession` does.
        lock.lock()
        let queue = self.queue
        lock.unlock()
        guard let queue else { return }
        serially { await queue.pump() }
    }

    /// The phone came into reach — or this app far enough into the front for the session to say so,
    /// which can be after the pass it became active for. Run that pass again: it is what wakes the phone.
    /// And tell it the recorder's state, if that was published before: at a launch the session is
    /// activated before it is in reach, so the activation's own send went out as a context only.
    func sessionReachabilityDidChange(_ session: WCSession) {
        guard session.isReachable else { return }
        if let recording = lock.withLock({ recordingOwed ? self.recording : nil }) { send(recording) }
        guard let queue = lock.withLock({ self.queue }) else { return }
        serially { await queue.pump() }
    }

    /// The only completion there is (docs/13: `didFinish` is only a hint). Everything the watch decides — a
    /// part filed, a resend, the delete — is decided from here.
    func session(_ session: WCSession, didReceiveUserInfo userInfo: [String: Any] = [:]) {
        guard let ack = TransferAck.parse(userInfo) else {
            logger.error("transfer.ack.unreadable")
            return
        }
        lock.lock()
        guard let queue else {
            early.append(ack)
            lock.unlock()
            return
        }
        lock.unlock()
        receive(ack, into: queue)
    }

    /// A hint, and logged as one: the bytes left this device, which is not the phone having hashed
    /// and filed them. An error here is not a failure either — the item is still unacked, and the
    /// next pump hands it over again.
    func session(_ session: WCSession, didFinish fileTransfer: WCSessionFileTransfer, error: Error?) {
        logger.info(
            """
            transfer.didFinish file=\(fileTransfer.file.fileURL.lastPathComponent, privacy: .public) \
            error=\(error == nil ? "none" : "yes", privacy: .public)
            """
        )
    }

    func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        adopt(context: applicationContext)
    }

    private func receive(_ ack: TransferAck, into queue: WatchTransferQueue) {
        serially { [weak self] in
            let completed = await queue.receive(ack)
            self?.onAck?(completed)
        }
    }

    /// In arrival order, one at a time: the acks have to reach the queue in the order the phone sent
    /// them, and [settle] waits for the last one.
    private func serially(_ work: @escaping @Sendable () async -> Void) {
        lock.lock()
        defer { lock.unlock() }
        let earlier = previous
        previous = Task {
            await earlier?.value
            await work()
        }
    }

    /// A context with no language in it leaves the watch on the language it has (docs/07 rule 2).
    private func adopt(context: [String: Any]) {
        if let language = WatchContext.language(context) { onLanguage?(language) }
    }
}
