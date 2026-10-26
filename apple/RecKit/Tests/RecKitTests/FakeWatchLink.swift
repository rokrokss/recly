import Foundation
import ReclyCore
@testable import RecKit

/// `WCSession` as `WatchTransferQueue` sees it, with the transfers written down instead of sent and
/// the acks fed back by hand — which is the only way to drive the transfer contract without a phone.
final class FakeWatchLink: WatchTransferLink {
    /// What the system is still carrying. The queue must not hand it any of these a second time.
    var outstandingTransfers: [[String: Any]] = []

    private(set) var sent: [(url: URL, metadata: [String: Any])] = []
    private(set) var wakes = 0
    /// Every call in order — a transfer as its key, a wake as `wake` — for what came behind what.
    private(set) var calls: [String] = []

    func transfer(_ file: URL, metadata: [String: Any]) {
        sent.append((file, metadata))
        calls.append(TransferMetadata.parse(metadata)?.key ?? "?")
    }

    func wake() {
        wakes += 1
        calls.append("wake")
    }

    /// Everything handed over so far, as `TransferMetadata.key` — `{id}/{part}/{track}` and
    /// `{id}/meta`, which is what the assertions are about.
    var sentKeys: [String] {
        sent.compactMap { TransferMetadata.parse($0.metadata)?.key }
    }

    func clear() {
        sent.removeAll()
        wakes = 0
        calls.removeAll()
    }
}

/// The core's `recordings.delete` — row, parts and the whole directory. The directory really goes,
/// so the queue's own "the directory is gone" rule is exercised rather than assumed.
final class FakeWatchRecordings: WatchRecordings {
    private(set) var deleted: [String] = []
    var directories: [String: URL] = [:]

    func delete(recordingId: String) async {
        deleted.append(recordingId)
        if let directory = directories[recordingId] {
            try? FileManager.default.removeItem(at: directory)
        }
    }
}
