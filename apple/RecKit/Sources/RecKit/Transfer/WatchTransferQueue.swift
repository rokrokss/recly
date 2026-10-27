import Foundation
import os
import ReclyCore

/// `WCSession` as the queue uses it. The real one is `RecWatch`'s `WatchLink`; a test hands in its
/// own and feeds the acks back by hand, which is the only way to exercise the contract without two
/// devices.
public protocol WatchTransferLink: AnyObject {
    /// The metadata of every file transfer `WCSession` still has queued
    /// (`outstandingFileTransfers`). It survives the app being killed, and it is the only thing that
    /// stops a resend handing the system a part it is already carrying.
    var outstandingTransfers: [[String: Any]] { get }

    func transfer(_ file: URL, metadata: [String: Any])

    /// Asks the system to start the phone app, which is the only way what was handed over gets
    /// there before the phone app is next opened: a file transfer waits for the receiving app to run,
    /// and only a live message from the watch launches the iPhone app in the background (docs/13
    /// "Apple Watch" Transfer). Possible only while the phone is reachable; otherwise nothing.
    func wake()
}

/// The one call the queue makes into the core — the Android `TransferSender.Recordings`, minus the
/// `get`, because the queue keeps the part list it was handed rather than re-reading the row.
public protocol WatchRecordings: AnyObject {
    /// Row, parts and the whole directory — the watch keeps no history (ADR-002).
    func delete(recordingId: String) async
}

/// One recording as the queue shows it (the screen's "Sending n", and what a test asserts on).
public struct WatchTransferEntry: Equatable {
    public let recordingId: String
    public let parts: Int
    public let ackedParts: Int
    /// Set once the phone has refused something a resend cannot fix. The audio is kept.
    public let failure: String?
}

/// One part the watch owes the phone.
public struct WatchTransferPart: Equatable {
    public let part: Int
    public let track: Track
    public let file: String
    public let sha256: String

    public init(part: Int, track: Track, file: String, sha256: String) {
        self.part = part
        self.track = track
        self.file = file
        self.sha256 = sha256
    }
}

/// docs/03 "Watch → phone transfer contract", the sending half on `WCSession`
/// (docs/13 "Apple Watch" Transfer, M5-L4 deliverable 2): per recording, every part in
/// `meta.parts` order, then `meta.json` last — in the same pass, right behind the parts. A recording
/// still being made goes part by part as its boundaries close them: each pass that hands a new part
/// over puts the in-progress `meta.json` (`TransferMetadata.progress`, never acked) ahead of it, and no
/// final meta — that one goes once the recording is finalized.
/// **`ack-meta ok:true` is the only thing that deletes audio from this watch.** A part ack is
/// recorded — it is what stops the part being sent again — but it does not license a delete: until
/// the phone has filed the meta it has loose parts and a 24-hour purge timer, and a watch that has
/// already deleted its copy has nothing to answer a `missing` list with.
///
/// The Android `TransferSender` is the same contract, and the differences are all `WCSession`'s:
///
/// - **`didFinish` is a hint, not a completion.** The system's own callback says the bytes left this
///   device, which is not the phone having hashed and filed them — and it can be missed entirely
///   (docs/13). The only completion is the phone's ack, which arrives as `didReceiveUserInfo`.
/// - **The meta does not wait for the part acks.** Every transfer waits for the phone app to run,
///   so waiting for the acks first would take two launches of it — one for the parts, one for the
///   meta — with the watch app awake in between to hand the meta over. `WCSession` delivers in the
///   order things were sent, and the phone files the meta only once every part it lists is on disk
///   with its hash; a part that is not answers `Incomplete(missing)`, which resends it.
/// - **No ack timeout.** `WCSession` keeps a queued transfer across launches and retries it itself,
///   so there is nothing to time out into: an item stays unacked until an ack arrives or [pump]
///   sends it again. What replaces Android's five-minute timer is [outstandingTransfers] — a part
///   the system is still carrying is not handed to it twice.
/// - **`SHA256_MISMATCH`** buys one resend, exactly as there: the phone deletes what it staged on a
///   mismatch, so a resend cannot duplicate anything and corruption on the wire is the likeliest
///   cause. A second mismatch on the same part is the file itself, and is fatal.
/// - **A fatal nack** marks the recording failed and *keeps* its audio, parts and meta both.
///   Nothing else on the watch will look at it again, so deleting it would be deleting the only copy.
///
/// An `actor` because that is Android's process-wide mutex: one pass at a time, and the queue file
/// is only ever written from inside it.
public actor WatchTransferQueue {
    /// A phone that answers a resend with the same `missing` list is not going to stop. Two rounds
    /// is generous — one covers the ordinary lost-ack case — and the third marks the recording
    /// failed with its audio intact rather than sending it forever (Android `MAX_RESENDS`).
    static let maxResends = 2

    private let link: any WatchTransferLink
    private let recordings: any WatchRecordings
    /// The queue as a JSON file next to the database: a recording waiting to be sent has to survive
    /// the watch app being killed, which is the ordinary way a watch app ends.
    private let file: URL
    private let logger = Logger(subsystem: CoreBridge.appName, category: "transfer")

    private var items: [Item]

    public init(link: any WatchTransferLink, recordings: any WatchRecordings, file: URL) {
        self.link = link
        self.recordings = recordings
        self.file = file
        items = Self.read(file)
    }

    public var entries: [WatchTransferEntry] {
        items.map {
            WatchTransferEntry(
                recordingId: $0.recordingId,
                parts: $0.parts.count,
                ackedParts: $0.parts.filter(\.acked).count,
                failure: $0.failure
            )
        }
    }

    /// Recordings still waiting to be handed over — what the complication counts.
    public var waiting: Int { items.filter { $0.failure == nil }.count }

    /// Recordings the phone refused for good, kept here with their audio — "Transfer failed" on the screen and the
    /// complication, ahead of the waiting ones (Wear's order): nothing will send them again.
    public var failed: Int { items.filter { $0.failure != nil }.count }

    /// Idempotent: a recording already queued keeps the acks it has. Called when a boundary files a part
    /// of a recording still being made ([recording], docs/03), when a recording is finalized, and again at
    /// launch for everything the core still holds — a recording finalized by `RecordingRecovery` after a
    /// crash was never queued by the stop that never ran.
    ///
    /// A recording queued while it is still being made only gains parts from a call that says it still is —
    /// two boundaries' calls can read the row out of order — and takes the finished part list from the one
    /// that says it is done, keeping the acks of the parts it already had. Once it is queued as finished,
    /// nothing changes it here: a late call that still says it is recording is older news.
    public func add(
        recordingId: String,
        directory: URL,
        metaFile: String,
        parts: [WatchTransferPart],
        recording: Bool = false
    ) {
        let incoming = parts.map {
            QueuedPart(part: $0.part, track: $0.track.wire, file: $0.file, sha256: $0.sha256)
        }
        guard let index = items.firstIndex(where: { $0.recordingId == recordingId }) else {
            items.append(
                Item(
                    recordingId: recordingId,
                    directory: directory.path,
                    metaFile: metaFile,
                    parts: incoming,
                    recording: recording ? true : nil
                )
            )
            save()
            logger.info(
                """
                transfer.queued recordingId=\(recordingId, privacy: .public) \
                parts=\(parts.count, privacy: .public) recording=\(recording, privacy: .public)
                """
            )
            return
        }
        guard items[index].recording == true else { return }
        let queued = items[index].parts
        var next = items[index]
        if recording {
            let added = incoming.filter { part in !queued.contains { $0.part == part.part && $0.track == part.track } }
            next.parts = (queued + added).sorted { ($0.part, $0.track) < ($1.part, $1.track) }
        } else {
            next.parts = incoming.map { part in
                queued.first { $0.part == part.part && $0.track == part.track && $0.sha256 == part.sha256 } ?? part
            }
            next.recording = nil
        }
        guard next != items[index] else { return }
        items[index] = next
        save()
        logger.info(
            """
            transfer.queued recordingId=\(recordingId, privacy: .public) \
            parts=\(next.parts.count, privacy: .public) recording=\(recording, privacy: .public)
            """
        )
    }

    /// The same, from the row the core holds — still being made while its status says `recording`.
    public func add(_ record: RecordingRecord) {
        let base = MetaWriter.shared.baseName(meta: record.meta)
        add(
            recordingId: record.id,
            directory: record.dir.url,
            metaFile: MetaWriter.shared.metaFileName(base: base),
            parts: record.meta.parts.map {
                WatchTransferPart(
                    part: Int($0.part),
                    track: $0.track,
                    file: $0.file,
                    sha256: $0.sha256
                )
            },
            recording: record.meta.status == RecordingStatus.recording
        )
    }

    /// Hands `WCSession` everything it is not already carrying: the unacked parts of each recording,
    /// and its meta right behind them. Called after a stop, when the app becomes active and when the
    /// session activates (docs/13 "Apple Watch" Transfer: unacked parts get resent on activation) —
    /// the phone ignores a duplicate part by its sha256, so re-sending one is only ever wasted radio,
    /// never a wrong file. Then, while anything is still the phone's to take, the phone is woken.
    public func pump() {
        let outstanding = Set(link.outstandingTransfers.compactMap { TransferMetadata.parse($0)?.key })
        var changed = false
        for index in items.indices {
            guard items[index].failure == nil else { continue }
            changed = send(&items[index], outstanding: outstanding) || changed
        }
        // A recording whose directory is gone was completed by a pass that died before it could
        // write the queue file (see [complete]). Nothing is left to send and nothing to wait for.
        let gone = items.filter { !FileManager.default.fileExists(atPath: $0.directory) }
        if !gone.isEmpty {
            items.removeAll { item in gone.contains { $0.recordingId == item.recordingId } }
            changed = true
        }
        if changed { save() }
        // After the transfers, so the launch it causes finds them queued — including the ones the
        // system was already carrying, which is what an earlier pass with the phone unreachable left.
        // A recording still being made whose filed parts the phone has all acked owes it nothing yet.
        if items.contains(where: \.owesPhone) { link.wake() }
    }

    /// The phone's answer, and the only thing that finishes anything here.
    ///
    /// - Returns: true when this ack finished a recording — `ack-meta ok:true`, the phone has it — which is the
    ///   moment the wrist is told it is out of its hands (docs/13 WA5).
    @discardableResult
    public func receive(_ ack: TransferAck) async -> Bool {
        guard let index = items.firstIndex(where: { $0.recordingId == ack.recordingId }) else {
            // An ack for a recording that is off this watch already: a duplicate of one acted on.
            return false
        }
        switch ack {
        case .part(_, let ref, let ok, let reason):
            guard apply(ref: ref, ok: ok, reason: reason, to: &items[index]) else { return false }
            // Recorded, and nothing to hand over: the meta went right behind the part. A pump here
            // would resend every later part the phone has taken and not acked yet.
            if ok {
                save()
                return false
            }

        case .meta(_, let ok, let reason, let missing):
            if ok {
                await complete(items[index])
                return true
            }
            apply(reason: reason, missing: missing, to: &items[index])
        }
        save()
        pump()
        return false
    }

    // MARK: - The rules

    /// Whether anything changed — a second ack for a part already acked is a duplicate the phone
    /// sent because the watch resent the part, and it must not restart anything.
    private func apply(ref: PartRef, ok: Bool, reason: String?, to item: inout Item) -> Bool {
        guard let index = item.parts.firstIndex(where: { $0.matches(ref) }), !item.parts[index].acked
        else { return false }
        if ok {
            // Recorded, not acted on: the part stays on disk until the meta is acked.
            item.parts[index].acked = true
            return true
        }
        if reason == TransferReason.shaMismatch, !item.parts[index].retried {
            item.parts[index].retried = true
            // Read out of the `inout` first: an `os.Logger` interpolation is an escaping autoclosure.
            let recordingId = item.recordingId
            logger.warning(
                """
                transfer.part.mismatch.retry recordingId=\(recordingId, privacy: .public) \
                part=\(ref.part, privacy: .public) track=\(ref.track.wire, privacy: .public)
                """
            )
            return true
        }
        fail(&item, reason: reason ?? TransferReason.unknown)
        return true
    }

    /// `ack-meta ok:false`. Never a completion — the two shapes it comes in are "send me these
    /// parts again" and "this recording is refused", and both leave every file where it is.
    private func apply(reason: String?, missing: [PartRef], to item: inout Item) {
        guard !missing.isEmpty else {
            fail(&item, reason: reason ?? TransferReason.unknown)
            return
        }
        let directory = URL(fileURLWithPath: item.directory, isDirectory: true)
        let gone = missing.filter { ref in
            guard let name = item.parts.first(where: { $0.matches(ref) })?.file else { return true }
            return !FileManager.default.fileExists(atPath: directory.appendingPathComponent(name).path)
        }
        if !gone.isEmpty {
            // Not reachable by any transfer this app ran: the watch keeps every part until
            // `ack-meta ok:true`, so a phone that is still asking cannot have licensed a delete.
            // Something outside deleted the audio, and there is no pass that can produce it — but
            // the phone has just said it does *not* have the recording, so completing here would
            // delete the rest of the only copy.
            fail(&item, reason: TransferReason.partMissingLocally)
            return
        }
        item.resends += 1
        if item.resends > Self.maxResends {
            fail(&item, reason: TransferReason.resendLoop)
            return
        }
        // Listed parts the queue already calls acked go out again too: the list is the phone saying
        // it lost them, and `acceptPart` overwrites what it staged.
        for ref in missing {
            guard let index = item.parts.firstIndex(where: { $0.matches(ref) }) else { continue }
            item.parts[index].acked = false
        }
    }

    /// `ack-meta ok:true` and nothing else — the one place in this type that deletes audio. Every
    /// part, the meta, the directory and the row go together, and the recording is off this watch.
    ///
    /// Files first, then the queue entry: if the process dies between the two, the next [pump] finds
    /// no directory and drops the entry. The other order would leave audio nothing looks at again.
    private func complete(_ item: Item) async {
        await recordings.delete(recordingId: item.recordingId)
        items.removeAll { $0.recordingId == item.recordingId }
        save()
        logger.info("transfer.complete recordingId=\(item.recordingId, privacy: .public)")
    }

    private func fail(_ item: inout Item, reason: String) {
        item.failure = reason
        let recordingId = item.recordingId
        logger.error(
            """
            transfer.failed recordingId=\(recordingId, privacy: .public) \
            reason=\(reason, privacy: .public)
            """
        )
    }

    // MARK: - Sending

    /// Whether the item changed. Everything it hands to [link] is a file that is on disk now, and
    /// nothing it does deletes one.
    private func send(_ item: inout Item, outstanding: Set<String>) -> Bool {
        var changed = false
        let directory = URL(fileURLWithPath: item.directory, isDirectory: true)
        var handing: [(url: URL, metadata: TransferMetadata)] = []
        for index in item.parts.indices where !item.parts[index].acked {
            let queued = item.parts[index]
            let url = directory.appendingPathComponent(queued.file)
            guard FileManager.default.fileExists(atPath: url.path) else {
                // Purged from under us, or deleted by a completed transfer whose queue write did not
                // survive. There is nothing to send and nothing to wait for; the meta ack settles
                // whether the phone has it.
                item.parts[index].acked = true
                changed = true
                continue
            }
            guard let track = Track.named(queued.track) else {
                fail(&item, reason: TransferReason.unknown)
                return true
            }
            let metadata = TransferMetadata.part(
                PartMetadata(
                    recordingId: item.recordingId,
                    part: queued.part,
                    track: track,
                    sha256: queued.sha256,
                    file: queued.file
                )
            )
            guard !outstanding.contains(metadata.key) else { continue }
            handing.append((url, metadata))
        }

        let meta = directory.appendingPathComponent(item.metaFile)
        if item.recording == true {
            // docs/03: the recording as it stands — start, time zone, segment length, highlights so far and every
            // part filed so far — ahead of the parts it lists, so the phone knows each part's place when it lands.
            // Not acked; the next part brings a fresher one, and the final meta waits for the recording to end.
            if !handing.isEmpty, let newest = item.parts.map(\.part).max(),
               FileManager.default.fileExists(atPath: meta.path) {
                let progress = TransferMetadata.progress(recordingId: item.recordingId, part: newest)
                if !outstanding.contains(progress.key) { link.transfer(meta, metadata: progress.dictionary) }
            }
            for handed in handing { link.transfer(handed.url, metadata: handed.metadata.dictionary) }
            return changed
        }
        for handed in handing { link.transfer(handed.url, metadata: handed.metadata.dictionary) }

        // The meta ends the transfer, so it goes behind every part of this recording, and the phone
        // files it only once every one of them is there (see the type's doc).
        guard FileManager.default.fileExists(atPath: meta.path) else {
            // The phone can never be told the recording is complete. Not recoverable by resending,
            // and the parts it has are orphans it purges after 24 hours (docs/03).
            fail(&item, reason: TransferReason.metaMissing)
            return true
        }
        let metadata = TransferMetadata.meta(recordingId: item.recordingId)
        if !outstanding.contains(metadata.key) {
            link.transfer(meta, metadata: metadata.dictionary)
        }
        return changed
    }

    // MARK: - The file

    private struct Item: Codable, Equatable {
        let recordingId: String
        /// The recording directory's path. Kept rather than derived, because the entry has to
        /// outlive the row: a completed transfer deletes the row first (see [complete]).
        let directory: String
        let metaFile: String
        var parts: [QueuedPart]
        /// How many times a phone's `missing` list has sent this recording round again.
        var resends = 0
        var failure: String?
        /// `true` while the recording is still being made — its parts go as they are filed, and its final
        /// meta waits — and nil once it is finished.
        var recording: Bool?

        /// Something here is still the phone's to take: a finished recording — its meta at least — or a
        /// part of one still being made that the phone has not acked.
        var owesPhone: Bool {
            failure == nil && (recording != true || parts.contains { !$0.acked })
        }
    }

    private struct QueuedPart: Codable, Equatable {
        let part: Int
        /// The `@SerialName` (`mono`, `mic`, …) — `Track` is a Kotlin class and not `Codable`.
        let track: String
        let file: String
        let sha256: String
        var acked = false
        /// A `SHA256_MISMATCH` has already bought this part its one resend.
        var retried = false

        func matches(_ ref: PartRef) -> Bool { part == ref.part && track == ref.track.wire }
    }

    private func save() {
        do {
            try JSONEncoder().encode(items).write(to: file, options: .atomic)
        } catch {
            // The transfer still works this run; what is lost is the memory of it across a kill,
            // and the recording is then re-sent whole (the phone ignores duplicates by sha256).
            logger.error("transfer.queue.save.failed error=\(String(describing: error), privacy: .public)")
        }
    }

    private static func read(_ file: URL) -> [Item] {
        guard let data = try? Data(contentsOf: file) else { return [] }
        return (try? JSONDecoder().decode([Item].self, from: data)) ?? []
    }
}
