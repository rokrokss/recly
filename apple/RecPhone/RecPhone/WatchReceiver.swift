import Foundation
import os
import ReclyCore
import RecKit
import WatchConnectivity

/// Everything the receiving half of the transfer needs from the core and the executor, and so the
/// whole of what a test has to stand in for — the Android `MetaFacade`, widened by the part call
/// because on Apple both halves are one delegate.
protocol WatchTransferCore: AnyObject {
    /// Hashes what arrived against what the watch claimed, and files it under the name it came with.
    func acceptPart(recordingId: String, part: Int, track: Track, sha256: String, file: URL) async throws -> Ack

    func acceptMeta(json: String) async throws -> AcceptMetaResult

    /// The workflow the watch started the recording with, as the stored meta now records it.
    func workflowId(recordingId: String) async throws -> String?

    func enqueue(recordingId: String, workflowId: String?) async throws

    /// Wakes the executor — the same path the phone recorder's own stop uses.
    func onJobsDue() async
}

/// One ack back to the watch. Never throws: `transferUserInfo` is queued by the system, so there is
/// nothing here that can fail in a way the caller could act on.
protocol WatchAckSender: AnyObject {
    func send(_ ack: TransferAck)
}

/// docs/13 I6 · docs/03 "워치 → 폰 전송 계약", the receiving half. Parts arrive one at a time and
/// `meta.json` last; the meta ends the transfer and starts the work, and `ack-meta ok:true` is the
/// watch's licence to delete its only copy of the audio.
///
/// Three rules, and this class exists to keep all three testable off a pair of devices.
///
/// 1. **The file is moved inside the callback.** `WCSession` deletes what it handed over the moment
///    `session(_:didReceive:)` returns (docs/13 "주의"), so [received] is synchronous down to the
///    move and everything after it is `async`.
/// 2. **The meta must say it is the recording the metadata said it was.** The body's `recordingId`
///    is what the core files everything under, so a body that disagrees would write into a recording
///    the watch never named — checked before any core call, not after.
/// 3. **The ok ack goes out last**, after the recording is filed *and* queued *and* the executor
///    woken. Anything that throws before that leaves no ack, and the watch's resend brings the meta
///    back; `acceptMeta` and `enqueue` are both idempotent.
final class WatchReceiver: NSObject, WCSessionDelegate {
    private let core: any WatchTransferCore
    private let acks: any WatchAckSender
    /// Where a transfer is put down before the core takes it. Inside the app container rather than
    /// `NSTemporaryDirectory()`: the system may empty that one under us.
    private let staging: URL
    private let logger = Logger(subsystem: CoreBridge.appName, category: "transfer")
    /// The session is activated asynchronously and `updateApplicationContext` is refused until it
    /// has been (`WCErrorCodeSessionNotActivated`, measured on the paired simulators), so the
    /// workflow summary is published from here rather than from the call that started activation.
    var onActivated: (() -> Void)?
    /// The accept that is running or waiting, so the next one can be chained behind it. Touched from
    /// `WCSession`'s delegate queue and, in the tests, from the test thread — hence the lock.
    private let chain = NSLock()
    private var previous: Task<Void, Never>?

    init(core: any WatchTransferCore, acks: any WatchAckSender, staging: URL) {
        self.core = core
        self.acks = acks
        self.staging = staging
        // Delivery directories a previous launch left behind: a process that died between the move
        // and the core's own move owns nothing any more. Here rather than anywhere later because
        // nothing can be in flight yet — the delegate is not wired until after this returns.
        try? FileManager.default.removeItem(at: staging)
    }

    /// The synchronous half. Split out from the delegate method because a `WCSessionFile` cannot be
    /// made by hand, and rule 1 above is the one thing here worth a test of its own.
    func received(_ file: URL, metadata: [String: Any]) {
        guard let transfer = TransferMetadata.parse(metadata) else {
            // Not one of the two shapes. No ack: there is no recording id this side trusts to name
            // one with, and the watch's resend is the answer either way.
            logger.error("transfer.metadata.unreadable")
            return
        }
        guard let staged = stage(file, as: transfer) else { return }
        serially { await self.accept(transfer, staged: staged) }
    }

    /// **In arrival order, one at a time.** The staging above is synchronous and so already runs in
    /// the order the deliveries landed; this makes the accepts follow it.
    ///
    /// It has to, because the meta is what finalizes a recording and the two can overlap: the queue
    /// sends the meta once every part is acked, so a part whose ack was lost is resent and its
    /// duplicate delivery can arrive just before the meta. On independent tasks that duplicate's
    /// `acceptPart` could land *after* `acceptMeta` had already filed and enqueued the recording —
    /// re-opening it with a placeholder part behind a job that is already running.
    private func serially(_ work: @escaping @Sendable () async -> Void) {
        chain.lock()
        defer { chain.unlock() }
        let earlier = previous
        previous = Task {
            await earlier?.value
            await work()
        }
    }

    /// The move, and nothing else that could throw before it. The staged name is the watch's own
    /// (docs/03 "이름 규칙"): `acceptPart` files the part under it, and it is the name the meta will
    /// ask for.
    ///
    /// **A directory per delivery**, named by nothing but itself. Two deliveries of the same part
    /// are an ordinary event — the watch resends every unacked item on each activation (docs/13) —
    /// and one shared path would have the second one replace or delete the very file the first
    /// one's [accept] is still hashing: a part hashed from one file and filed from another is an
    /// ack for bytes nobody verified. Each [accept] owns its directory and takes it away with it
    /// ([discard]).
    private func stage(_ file: URL, as transfer: TransferMetadata) -> URL? {
        let directory = staging
            .appendingPathComponent(transfer.recordingId, isDirectory: true)
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let name: String
        switch transfer {
        case .part(let part): name = part.file
        case .meta: name = "meta.json"
        }
        let staged = directory.appendingPathComponent(name)
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try FileManager.default.moveItem(at: file, to: staged)
            return staged
        } catch {
            // Nothing is acked, so the watch still has it and sends it again.
            logger.error("transfer.stage.failed error=\(String(describing: error), privacy: .public)")
            return nil
        }
    }

    /// The delivery directory this staged file was alone in, gone once its accept is done however it
    /// ended. Only that one task ever names it, so this can never take a file from under another.
    private func discard(_ staged: URL) {
        try? FileManager.default.removeItem(at: staged.deletingLastPathComponent())
    }

    private func accept(_ transfer: TransferMetadata, staged: URL) async {
        switch transfer {
        case .part(let part): await accept(part, staged: staged)
        case .meta(let recordingId): await acceptMeta(recordingId: recordingId, staged: staged)
        }
    }

    /// The core hashes it, files it or deletes it, and says which — and the watch records the ack
    /// without acting on it: the part stays on the watch until the meta is acked.
    private func accept(_ part: PartMetadata, staged: URL) async {
        defer { discard(staged) }
        do {
            let ack = try await core.acceptPart(
                recordingId: part.recordingId,
                part: part.part,
                track: part.track,
                sha256: part.sha256,
                file: staged
            )
            acks.send(
                .part(
                    recordingId: part.recordingId,
                    ref: PartRef(part: part.part, track: part.track),
                    ok: ack.ok,
                    reason: ack.reason
                )
            )
            logger.info(
                """
                transfer.part recordingId=\(part.recordingId, privacy: .public) \
                part=\(part.part, privacy: .public) ok=\(ack.ok, privacy: .public)
                """
            )
        } catch {
            // No ack. The transfer is not lost — the watch still holds the audio and resends.
            logger.error("transfer.part.failed error=\(String(describing: error), privacy: .public)")
        }
    }

    private func acceptMeta(recordingId: String, staged: URL) async {
        defer { discard(staged) }
        guard let json = try? String(contentsOf: staged, encoding: .utf8) else {
            acks.send(.meta(recordingId: recordingId, ok: false, reason: TransferReason.unknown, missing: []))
            return
        }
        // A body whose id cannot be read at all is treated as a mismatch too: it is fatal for this
        // recording either way, and the alternative is handing the core a document that decides for
        // itself which directory it belongs to.
        guard Self.recordingId(of: json) == recordingId else {
            // The nack goes out first and on its own: nothing — not even logging — may stand between
            // a body the metadata does not vouch for and the watch being told so.
            acks.send(
                .meta(
                    recordingId: recordingId,
                    ok: false,
                    reason: TransferReason.recordingIdMismatch,
                    missing: []
                )
            )
            logger.warning("transfer.meta.mismatch recordingId=\(recordingId, privacy: .public)")
            return
        }
        do {
            switch onEnum(of: try await core.acceptMeta(json: json)) {
            case .complete:
                try await complete(recordingId: recordingId)

            case .incomplete(let incomplete):
                acks.send(
                    .meta(
                        recordingId: recordingId,
                        ok: false,
                        reason: nil,
                        missing: incomplete.missingParts.map {
                            PartRef(part: Int($0.part), track: $0.track)
                        }
                    )
                )

            case .invalid(let invalid):
                acks.send(
                    .meta(recordingId: recordingId, ok: false, reason: invalid.reason, missing: [])
                )
            }
        } catch {
            logger.error("transfer.meta.failed error=\(String(describing: error), privacy: .public)")
        }
    }

    /// The order is the contract: filed, queued, executor woken, and only then acked.
    private func complete(recordingId: String) async throws {
        let workflowId = try await core.workflowId(recordingId: recordingId)
        try await core.enqueue(recordingId: recordingId, workflowId: workflowId)
        await core.onJobsDue()
        // The ack is the last thing the protocol needs; the log is informational and must never
        // stand between a filed-and-woken recording and the watch being told so.
        acks.send(.meta(recordingId: recordingId, ok: true, reason: nil, missing: []))
        logger.info("transfer.enqueued recordingId=\(recordingId, privacy: .public)")
    }

    /// `recJson` is `internal` to the core, and one field is all this side needs.
    private static func recordingId(of json: String) -> String? {
        guard let data = json.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }
        return root["recordingId"] as? String
    }

    // MARK: - WCSessionDelegate

    func session(_ session: WCSession, didReceive file: WCSessionFile) {
        received(file.fileURL, metadata: file.metadata ?? [:])
    }

    func session(
        _ session: WCSession,
        activationDidCompleteWith activationState: WCSessionActivationState,
        error: Error?
    ) {
        logger.info(
            """
            transfer.session state=\(activationState.rawValue, privacy: .public) \
            error=\(error == nil ? "none" : "yes", privacy: .public)
            """
        )
        guard activationState == .activated else { return }
        onActivated?()
    }

    /// Both are required on iOS. A watch that was swapped for another one leaves the session
    /// deactivated, and the only useful thing to do with it is activate it again for the new pair.
    func sessionDidBecomeInactive(_ session: WCSession) {}

    func sessionDidDeactivate(_ session: WCSession) {
        session.activate()
    }
}

/// The real facade. Nothing is hopped onto a queue here — every one of these is a `suspend fun`
/// whose body already runs on `CoreDeps.io`.
final class CoreWatchTransfer: WatchTransferCore {
    private let core: ReclyCore_
    /// `RecordingModel`'s executor triggers, as one call.
    private let jobsDue: @Sendable () async -> Void

    init(core: ReclyCore_, jobsDue: @escaping @Sendable () async -> Void) {
        self.core = core
        self.jobsDue = jobsDue
    }

    func acceptPart(
        recordingId: String,
        part: Int,
        track: Track,
        sha256: String,
        file: URL
    ) async throws -> Ack {
        try await core.transfer.acceptPart(
            recordingId: recordingId,
            part: Int32(part),
            track: track,
            sha256Claimed: sha256,
            // RecKit's own `URL.okioPath` is internal to it; this is the same call.
            tmpPath: OkioPath.companion.toPath(file.path, normalize: true)
        )
    }

    func acceptMeta(json: String) async throws -> AcceptMetaResult {
        try await core.transfer.acceptMeta(json: json)
    }

    func workflowId(recordingId: String) async throws -> String? {
        try await core.recordings.get(id: recordingId)?.meta.workflowId
    }

    func enqueue(recordingId: String, workflowId: String?) async throws {
        _ = try await core.enqueue(recordingId: recordingId, chosenWorkflowId: workflowId)
    }

    func onJobsDue() async {
        await jobsDue()
    }
}

/// `transferUserInfo` rather than `sendMessage`: it is queued and delivered even with the watch app
/// asleep or out of range, which is what makes an ack the thing the watch may wait on.
final class WCSessionAcks: WatchAckSender {
    func send(_ ack: TransferAck) {
        WCSession.default.transferUserInfo(ack.userInfo)
    }
}
