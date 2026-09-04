import Foundation
import ReclyCore
import XCTest
@testable import RecKit

/// docs/03 "워치 → 폰 전송 계약" as the watch has to obey it (docs/lanes M5-L4 deliverable 6): what
/// may be sent when, and — the rule the user's only copy of a recording rests on — what may be
/// deleted and when.
///
/// Every one of these runs on real files in a temporary directory, because "the part is still on
/// disk" is the assertion most of them are actually making.
final class WatchTransferQueueTests: XCTestCase {
    private var directory: URL!
    private var link: FakeWatchLink!
    private var recordings: FakeWatchRecordings!

    private let recordingId = "01J9ABCDEFGHJKMNPQRSTVWXYZ"
    private let base = "20260826T010000Z_watch_01J9ABCD"

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("WatchTransferQueueTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        link = FakeWatchLink()
        recordings = FakeWatchRecordings()
        recordings.directories[recordingId] = recordingDirectory
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    // MARK: - Order

    /// The meta is last and nothing else will do: it is what tells the phone the recording is
    /// complete, and a phone that files it while a part is still in flight has an incomplete
    /// recording it thinks is whole.
    func testTheMetaGoesOnlyOnceEveryPartIsAcked() async throws {
        let queue = try await makeQueue(parts: 2)

        await queue.pump()
        XCTAssertEqual(link.sentKeys, ["\(recordingId)/1/mono", "\(recordingId)/2/mono"])

        await queue.receive(partAck(1))
        XCTAssertFalse(link.sentKeys.contains("\(recordingId)/meta"), "one part is still unacked")

        await queue.receive(partAck(2))
        XCTAssertEqual(link.sentKeys.last, "\(recordingId)/meta")
    }

    /// `WCSession` keeps a queued transfer across a kill and retries it itself, so a resend while it
    /// is still carrying the file would be the same bytes twice over Bluetooth.
    func testAPartTheSystemIsStillCarryingIsNotHandedOverTwice() async throws {
        let queue = try await makeQueue(parts: 2)
        link.outstandingTransfers = [metadata(part: 1)]

        await queue.pump()

        XCTAssertEqual(link.sentKeys, ["\(recordingId)/2/mono"])
    }

    // MARK: - What may be deleted

    /// The rule the whole contract turns on. Every part is acked, and not one byte may go: until the
    /// phone has filed the meta it has loose parts and a purge timer, and a watch that has already
    /// deleted its copy has nothing to answer a `missing` list with.
    func testAckedPartsAreKeptUntilTheMetaIsAcked() async throws {
        let queue = try await makeQueue(parts: 2)
        await queue.pump()

        await queue.receive(partAck(1))
        await queue.receive(partAck(2))

        XCTAssertEqual(recordings.deleted, [], "nothing may be deleted before the meta ack")
        XCTAssertTrue(FileManager.default.fileExists(atPath: partURL(1).path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: partURL(2).path))
        let entries = await queue.entries
        XCTAssertEqual(entries.first?.ackedParts, 2)
        XCTAssertNil(entries.first?.failure)
    }

    /// `ack-meta ok:true`, and only it: the recording leaves the watch whole — files, directory and
    /// the queue entry.
    func testTheMetaAckIsWhatDeletesTheRecording() async throws {
        let queue = try await makeQueue(parts: 1)
        await queue.pump()
        await queue.receive(partAck(1))

        await queue.receive(.meta(recordingId: recordingId, ok: true, reason: nil, missing: []))

        let entries = await queue.entries
        XCTAssertEqual(recordings.deleted, [recordingId])
        XCTAssertEqual(entries, [])
    }

    // MARK: - `ok:false` is never a completion

    /// The bytes arrived corrupted, so a resend is safe and is the likeliest fix — once. A second
    /// mismatch on the same part is the file itself, and re-sending it forever costs the battery a
    /// three-hour recording's worth of Bluetooth.
    func testASha256MismatchIsResentOnceAndThenHeldWithTheAudioIntact() async throws {
        let queue = try await makeQueue(parts: 1)
        await queue.pump()
        link.clear()

        await queue.receive(partAck(1, ok: false, reason: TransferReason.shaMismatch))
        XCTAssertEqual(link.sentKeys, ["\(recordingId)/1/mono"], "the first mismatch buys one resend")

        link.clear()
        await queue.receive(partAck(1, ok: false, reason: TransferReason.shaMismatch))

        let entries = await queue.entries
        XCTAssertEqual(link.sentKeys, [], "the second is the file, not the wire")
        XCTAssertEqual(entries.first?.failure, TransferReason.shaMismatch)
        XCTAssertEqual(recordings.deleted, [], "a refused recording keeps its audio")
        XCTAssertTrue(FileManager.default.fileExists(atPath: partURL(1).path))
    }

    /// Any other nack is fatal for the recording and, again, deletes nothing: nothing else on this
    /// watch will look at it, so deleting it would be deleting the only copy.
    func testAFatalPartNackKeepsTheAudio() async throws {
        let queue = try await makeQueue(parts: 1)
        await queue.pump()

        await queue.receive(partAck(1, ok: false, reason: "SOMETHING_ELSE"))

        let entries = await queue.entries
        XCTAssertEqual(entries.first?.failure, "SOMETHING_ELSE")
        XCTAssertEqual(recordings.deleted, [])
        XCTAssertTrue(FileManager.default.fileExists(atPath: partURL(1).path))
    }

    /// `ack-meta ok:false` with a `missing` list is the phone saying it lost exactly those parts.
    /// They go again — the one the queue still calls acked included — and the meta waits for their
    /// new acks, because the meta is what says the recording is complete.
    func testAMissingListResendsExactlyThoseParts() async throws {
        let queue = try await makeQueue(parts: 2)
        await queue.pump()
        await queue.receive(partAck(1))
        await queue.receive(partAck(2))
        link.clear()

        await queue.receive(
            .meta(
                recordingId: recordingId,
                ok: false,
                reason: nil,
                missing: [PartRef(part: 1, track: Track.mono)]
            )
        )

        XCTAssertEqual(link.sentKeys, ["\(recordingId)/1/mono"], "only the part the phone lost")
        XCTAssertEqual(recordings.deleted, [])

        await queue.receive(partAck(1))

        XCTAssertEqual(link.sentKeys.last, "\(recordingId)/meta")
    }

    /// A listed part that really is not on disk can only have been deleted from outside this app —
    /// and the phone has just said it does *not* have the recording, so completing here would delete
    /// the rest of the only copy.
    func testAMissingPartThatIsGoneFromDiskFailsRatherThanCompleting() async throws {
        let queue = try await makeQueue(parts: 2)
        await queue.pump()
        await queue.receive(partAck(1))
        await queue.receive(partAck(2))
        try FileManager.default.removeItem(at: partURL(1))

        await queue.receive(
            .meta(
                recordingId: recordingId,
                ok: false,
                reason: nil,
                missing: [PartRef(part: 1, track: Track.mono)]
            )
        )

        let entries = await queue.entries
        XCTAssertEqual(entries.first?.failure, TransferReason.partMissingLocally)
        XCTAssertEqual(recordings.deleted, [])
        XCTAssertTrue(FileManager.default.fileExists(atPath: partURL(2).path), "the rest is kept too")
    }

    /// A phone that answers every resend with the same list is not going to stop. Two rounds, then
    /// the recording is marked failed with its audio where it is.
    func testAPhoneThatKeepsAskingIsGivenUpOnWithTheAudioIntact() async throws {
        let queue = try await makeQueue(parts: 1)
        await queue.pump()
        await queue.receive(partAck(1))

        for _ in 0 ... WatchTransferQueue.maxResends {
            await queue.receive(
                .meta(
                    recordingId: recordingId,
                    ok: false,
                    reason: nil,
                    missing: [PartRef(part: 1, track: Track.mono)]
                )
            )
            await queue.receive(partAck(1))
        }

        let entries = await queue.entries
        XCTAssertEqual(entries.first?.failure, TransferReason.resendLoop)
        XCTAssertEqual(recordings.deleted, [])
        XCTAssertTrue(FileManager.default.fileExists(atPath: partURL(1).path))
    }

    // MARK: - Duplicates

    /// The phone acks every part it receives, and it receives resends: a second ack for a part
    /// already acked must change nothing — least of all send the meta a second time.
    func testASecondAckForAnAckedPartDoesNothing() async throws {
        let queue = try await makeQueue(parts: 1)
        await queue.pump()
        await queue.receive(partAck(1))
        let after = link.sentKeys

        await queue.receive(partAck(1))

        XCTAssertEqual(link.sentKeys, after)
    }

    /// An ack for a recording that has already left the watch — the completion's own ack arriving
    /// twice — has nothing to act on.
    func testAnAckForARecordingThatIsGoneIsIgnored() async throws {
        let queue = try await makeQueue(parts: 1)
        await queue.pump()
        await queue.receive(partAck(1))
        await queue.receive(.meta(recordingId: recordingId, ok: true, reason: nil, missing: []))

        await queue.receive(.meta(recordingId: recordingId, ok: true, reason: nil, missing: []))

        XCTAssertEqual(recordings.deleted, [recordingId], "the second ack deleted nothing again")
    }

    // MARK: - Across a kill

    /// A watch app ends by being killed. The acks it had collected have to still be acks when it
    /// comes back, or every part goes over the air again.
    func testTheAcksSurviveTheProcess() async throws {
        let file = directory.appendingPathComponent("transfer.json")
        let first = try await makeQueue(parts: 2, file: file)
        await first.pump()
        await first.receive(partAck(1))

        let second = WatchTransferQueue(link: link, recordings: recordings, file: file)
        link.clear()
        await second.pump()

        let entries = await second.entries
        XCTAssertEqual(link.sentKeys, ["\(recordingId)/2/mono"])
        XCTAssertEqual(entries.first?.ackedParts, 1)
    }

    /// The completion deletes the files before the queue entry, so a kill in between leaves an entry
    /// with no directory. The next pump drops it rather than sending an empty recording forever.
    func testAnEntryWhoseDirectoryIsGoneIsDropped() async throws {
        let queue = try await makeQueue(parts: 1)
        try FileManager.default.removeItem(at: recordingDirectory)

        await queue.pump()

        let entries = await queue.entries
        XCTAssertEqual(entries, [])
    }

    // MARK: - Fixtures

    private var recordingDirectory: URL {
        directory.appendingPathComponent(base, isDirectory: true)
    }

    private func partURL(_ part: Int) -> URL {
        recordingDirectory.appendingPathComponent(fileName(part))
    }

    private func fileName(_ part: Int) -> String {
        String(format: "%@_p%03d_mono.m4a", base, part)
    }

    private func sha256(_ part: Int) -> String {
        String(repeating: String(part % 10), count: 64)
    }

    private func metadata(part: Int) -> [String: Any] {
        PartMetadata(
            recordingId: recordingId,
            part: part,
            track: Track.mono,
            sha256: sha256(part),
            file: fileName(part)
        ).dictionary
    }

    private func partAck(_ part: Int, ok: Bool = true, reason: String? = nil) -> TransferAck {
        .part(
            recordingId: recordingId,
            ref: PartRef(part: part, track: Track.mono),
            ok: ok,
            reason: reason
        )
    }

    /// A recording on disk — parts and `meta.json` — and a queue that has been handed it.
    private func makeQueue(parts: Int, file: URL? = nil) async throws -> WatchTransferQueue {
        try FileManager.default.createDirectory(at: recordingDirectory, withIntermediateDirectories: true)
        for part in 1 ... parts {
            try Data("part \(part)".utf8).write(to: partURL(part))
        }
        try Data("{}".utf8).write(to: recordingDirectory.appendingPathComponent("\(base).meta.json"))

        let queue = WatchTransferQueue(
            link: link,
            recordings: recordings,
            file: file ?? directory.appendingPathComponent("transfer.json")
        )
        await queue.add(
            recordingId: recordingId,
            directory: recordingDirectory,
            metaFile: "\(base).meta.json",
            parts: (1 ... parts).map {
                WatchTransferPart(part: $0, track: Track.mono, file: fileName($0), sha256: sha256($0))
            }
        )
        return queue
    }
}
