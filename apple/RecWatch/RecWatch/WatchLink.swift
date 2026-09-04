import Foundation
import os
import RecKit
import WatchConnectivity

/// The watch's end of `WCSession` (docs/13 "Apple Watch" 전송, M5-L4 deliverable 2): the queue's way
/// out, the acks' way in, and the workflow summary's way in.
///
/// It is built before the core is — activating the session early is what lets the acks for a
/// transfer that finished while the app was gone be waiting when the queue opens — so the queue is
/// handed over afterwards through [adopt]. Anything that arrives before that is dropped: the queue
/// file still says the item is unacked, and the next [pump] sends it again.
final class WatchLink: NSObject, WCSessionDelegate, WatchTransferLink {
    /// Set once the core is open. Delegate callbacks arrive on `WCSession`'s own queue.
    private var queue: WatchTransferQueue?
    /// The phone's `updateApplicationContext`, as the model's picker wants it.
    var onWorkflows: (([WatchWorkflow]) -> Void)?
    /// docs/07 rule 2: the phone's language setting, which the watch follows.
    var onLanguage: ((AppLanguage.Choice) -> Void)?

    private let session: WCSession = .default
    private let logger = Logger(subsystem: CoreBridge.appName, category: "transfer")

    func activate() {
        session.delegate = self
        session.activate()
    }

    /// Hands over the queue and drains whatever the phone has already said, then sends what is
    /// still owed. Also the launch-time resend of docs/13: `WCSession` keeps a queued transfer
    /// across a kill, but a part it never got to keeps its place in the queue file instead.
    func adopt(_ queue: WatchTransferQueue) {
        self.queue = queue
        // The context is a snapshot, not a message: whatever is standing now is the current list.
        adopt(context: session.receivedApplicationContext)
        Task { await queue.pump() }
    }

    // MARK: - WatchTransferLink

    var outstandingTransfers: [[String: Any]] {
        session.outstandingFileTransfers.compactMap { $0.file.metadata }
    }

    func transfer(_ file: URL, metadata: [String: Any]) {
        session.transferFile(file, metadata: metadata)
        logger.info("transfer.sent file=\(file.lastPathComponent, privacy: .public)")
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
        // docs/13: unacked items are resent when the app activates and when `WCSession` does.
        guard let queue else { return }
        Task { await queue.pump() }
    }

    /// The only completion there is (docs/13: `didFinish` is only a hint). Everything the watch decides — a
    /// part filed, a resend, the delete — is decided from here.
    func session(_ session: WCSession, didReceiveUserInfo userInfo: [String: Any] = [:]) {
        guard let ack = TransferAck.parse(userInfo) else {
            logger.error("transfer.ack.unreadable")
            return
        }
        guard let queue else { return }
        Task { await queue.receive(ack) }
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

    /// The two things the context carries, each ignored on its own when it is not there — a phone
    /// too old to send the language still sends the workflows, and the watch keeps the language it
    /// has (docs/07 rule 2).
    private func adopt(context: [String: Any]) {
        if let workflows = WatchWorkflows.parse(context) { onWorkflows?(workflows) }
        if let language = WatchWorkflows.language(context) { onLanguage?(language) }
    }
}
