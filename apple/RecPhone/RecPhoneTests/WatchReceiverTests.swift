import Foundation
import ReclyCore
import RecKit
import XCTest

/// docs/13 I6 · docs/03 "워치 → 폰 전송 계약", the receiving half (docs/lanes M5-L4 deliverable 6):
/// the file is moved inside the callback, the sha check is the core's answer and not a guess, and
/// the meta's ok ack goes out *after* the recording is registered and queued.
final class WatchReceiverTests: XCTestCase {
    private var directory: URL!
    private var core: FakeWatchTransferCore!
    private var acks: FakeAckSender!
    private var receiver: WatchReceiver!
    private var staging: URL!

    private let recordingId = "01J9ABCDEFGHJKMNPQRSTVWXYZ"
    private let file = "20260826T010000Z_watch_01J9ABCD_p001_mono.m4a"
    private let sha = String(repeating: "a", count: 64)

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("WatchReceiverTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let transcript = Transcript()
        core = FakeWatchTransferCore(transcript: transcript)
        acks = FakeAckSender(transcript: transcript)
        staging = directory.appendingPathComponent("staging", isDirectory: true)
        receiver = WatchReceiver(core: core, acks: acks, staging: staging)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    /// docs/13 "주의", and the reason [WatchReceiver.received] is synchronous down to the move:
    /// `WCSession` deletes what it handed over the moment the callback returns. This is that delete,
    /// done by hand right after the call — and the bytes are still there afterwards.
    func testTheFileIsMovedBeforeTheCallbackReturns() async throws {
        let inbox = try write("audio", named: file)
        let received = expectation(description: "the core was handed the part")
        core.onPart = { received.fulfill() }

        receiver.received(inbox, metadata: partMetadata)
        // What the system does the instant the callback returns.
        try? FileManager.default.removeItem(at: inbox)

        await fulfillment(of: [received], timeout: 2)
        let staged = try XCTUnwrap(core.partFile)
        XCTAssertEqual(try String(contentsOf: staged, encoding: .utf8), "audio")
        XCTAssertFalse(FileManager.default.fileExists(atPath: inbox.path), "it was moved, not copied")
    }

    /// The watch resends every unacked item on each activation (docs/13), so two deliveries of the
    /// same part arriving back to back is an ordinary event — and one shared staging path had the
    /// second one replace or delete the file the first `accept` was about to hash, which is an ack
    /// for bytes nobody verified. The accepts are serial, so the second's is still waiting while the
    /// first is parked in the core; what matters is that its *file* is already down, untouched, in a
    /// directory of its own.
    func testTwoDeliveriesOfTheSamePartAreStagedApart() async throws {
        let gate = Gate()
        core.holdPart = { await gate.wait() }
        let firstReached = expectation(description: "the first delivery reached the core")
        firstReached.assertForOverFulfill = false
        core.onPart = { firstReached.fulfill() }

        receiver.received(try write("first", named: file, in: "inbox-1"), metadata: partMetadata)
        receiver.received(try write("second", named: file, in: "inbox-2"), metadata: partMetadata)

        await fulfillment(of: [firstReached], timeout: 2)
        // Both are on disk already — the staging is synchronous, inside the callback — so this is
        // the state the shared path used to corrupt, whether or not the accepts overlap.
        let staged = try stagedDeliveries()
        XCTAssertEqual(staged.count, 2)
        XCTAssertNotEqual(staged[0], staged[1], "each delivery is staged on its own path")
        XCTAssertEqual(
            try staged.map { try String(contentsOf: $0, encoding: .utf8) }.sorted(),
            ["first", "second"],
            "neither file was replaced or deleted by the other delivery"
        )
        XCTAssertEqual(
            Set(staged.map(\.lastPathComponent)),
            [file],
            "and both keep the watch's own name, which the meta will ask for"
        )

        let acked = expectation(description: "both were acked")
        acked.expectedFulfillmentCount = 2
        acks.onAck = { acked.fulfill() }
        await gate.open()
        await fulfillment(of: [acked], timeout: 2)
        XCTAssertEqual(Set(core.partFiles), Set(staged), "each accept was handed its own delivery")
        try await untilDiscarded(staged)
    }

    /// The ordering rule itself. The queue sends the meta once every part is acked, so a part whose
    /// ack was lost is resent and its duplicate can arrive just before the meta — and on independent
    /// tasks that duplicate's `acceptPart` could land *after* `acceptMeta` had filed and enqueued the
    /// recording, re-opening it behind a job that is already running. Deliveries are accepted in
    /// arrival order, one at a time.
    func testTheMetaWaitsForAPartThatArrivedBeforeIt() async throws {
        let gate = Gate()
        core.holdPart = { await gate.wait() }
        let partReached = expectation(description: "the part reached the core")
        core.onPart = { partReached.fulfill() }

        receiver.received(try write("audio", named: file), metadata: partMetadata)
        receiver.received(try writeMeta(recordingId), metadata: metaMetadata)

        await fulfillment(of: [partReached], timeout: 2)
        // Long enough for a meta accept on a task of its own to have reached the core — it is two
        // synchronous statements away from `acceptMeta` — and there is nothing else to wait on: the
        // assertion is that something does *not* happen.
        try await Task.sleep(nanoseconds: 100_000_000)
        XCTAssertEqual(
            core.transcript.entries,
            ["acceptPart"],
            "the meta must not be filed while an earlier part is still being verified"
        )

        let acked = expectation(description: "both were answered")
        acked.expectedFulfillmentCount = 2
        acks.onAck = { acked.fulfill() }
        await gate.open()
        await fulfillment(of: [acked], timeout: 2)
        XCTAssertEqual(
            core.transcript.entries,
            ["acceptPart", "ack", "acceptMeta", "workflowId", "enqueue", "onJobsDue", "ack"]
        )
    }

    /// The sha check is the core's — it hashes what actually arrived — and the ack says exactly what
    /// it decided, reason included, because that is what the watch's resend rules read.
    func testThePartAckCarriesWhatTheCoreDecided() async throws {
        core.partAck = Ack(ok: false, reason: "SHA256_MISMATCH")
        let received = expectation(description: "acked")
        acks.onAck = { received.fulfill() }

        receiver.received(try write("audio", named: file), metadata: partMetadata)

        await fulfillment(of: [received], timeout: 2)
        XCTAssertEqual(
            acks.sent,
            [
                .part(
                    recordingId: recordingId,
                    ref: PartRef(part: 1, track: Track.mono),
                    ok: false,
                    reason: "SHA256_MISMATCH"
                ),
            ]
        )
    }

    /// The order *is* the contract (the Android `MetaAcceptor`): filed, queued, executor woken, and
    /// only then acked. An ack that went out first would let the watch delete its only copy of a
    /// recording this phone had not finished filing.
    func testTheMetaIsRegisteredAndQueuedBeforeItIsAcked() async throws {
        let received = expectation(description: "acked")
        acks.onAck = { received.fulfill() }

        receiver.received(try writeMeta(recordingId), metadata: metaMetadata)

        await fulfillment(of: [received], timeout: 2)
        XCTAssertEqual(core.transcript.entries, ["acceptMeta", "workflowId", "enqueue", "onJobsDue", "ack"])
        XCTAssertEqual(
            acks.sent,
            [.meta(recordingId: recordingId, ok: true, reason: nil, missing: [])]
        )
    }

    /// The body's `recordingId` is what the core files everything under, so a body that disagrees
    /// with the metadata it travelled with is refused before any core call — not after.
    func testAMetaWhoseBodyNamesAnotherRecordingIsNackedWithNoCoreCall() async throws {
        let received = expectation(description: "nacked")
        acks.onAck = { received.fulfill() }

        receiver.received(try writeMeta("01SOMETHINGELSE"), metadata: metaMetadata)

        await fulfillment(of: [received], timeout: 2)
        XCTAssertEqual(core.transcript.entries, ["ack"], "the core was never called")
        XCTAssertEqual(
            acks.sent,
            [
                .meta(
                    recordingId: recordingId,
                    ok: false,
                    reason: TransferReason.recordingIdMismatch,
                    missing: []
                ),
            ]
        )
    }

    /// A recording that says it has two parts and has one is not a recording, it is a transfer that
    /// is still going: `ok:false` with the list, which is what the watch resends from.
    func testAnIncompleteMetaComesBackAsTheMissingList() async throws {
        // `Part_`, not `Part`: the SQLDelight row type has the plain name, and SKIE gives the
        // docs/03 model the underscore.
        core.metaResult = AcceptMetaResultIncomplete(missingParts: [
            Part_(
                part: 2,
                track: Track.mono,
                file: "20260826T010000Z_watch_01J9ABCD_p002_mono.m4a",
                bytes: 0,
                sha256: sha,
                startOffsetSec: 0,
                durationSec: 0
            ),
        ])
        let received = expectation(description: "acked")
        acks.onAck = { received.fulfill() }

        receiver.received(try writeMeta(recordingId), metadata: metaMetadata)

        await fulfillment(of: [received], timeout: 2)
        XCTAssertEqual(
            acks.sent,
            [
                .meta(
                    recordingId: recordingId,
                    ok: false,
                    reason: nil,
                    missing: [PartRef(part: 2, track: Track.mono)]
                ),
            ]
        )
        XCTAssertFalse(core.transcript.entries.contains("enqueue"), "nothing is queued yet")
    }

    /// Metadata this side cannot read names no recording, so there is nothing to ack and nothing to
    /// file. The watch's resend is the answer.
    func testUnreadableMetadataIsNeitherFiledNorAcked() throws {
        receiver.received(try write("audio", named: file), metadata: ["recordingId": "../escape"])

        XCTAssertEqual(core.transcript.entries, [])
        XCTAssertEqual(acks.sent, [])
    }

    /// Every file staged under this recording, one per delivery directory, in a stable order.
    private func stagedDeliveries() throws -> [URL] {
        let manager = FileManager.default
        let root = staging.appendingPathComponent(recordingId, isDirectory: true)
        return try manager.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)
            .flatMap { try manager.contentsOfDirectory(at: $0, includingPropertiesForKeys: nil) }
            .sorted { $0.path < $1.path }
    }

    /// Both delivery directories gone. Waited for rather than asserted outright, because the
    /// discard is the last thing each accept does and the ack the expectation above waits on goes
    /// out just before it — milliseconds in practice.
    private func untilDiscarded(_ staged: [URL]) async throws {
        for _ in 0 ..< 200 {
            let gone = staged.allSatisfy {
                !FileManager.default.fileExists(atPath: $0.deletingLastPathComponent().path)
            }
            if gone { return }
            try await Task.sleep(nanoseconds: 5_000_000)
        }
        XCTFail("each accept takes its own delivery directory away when it is done")
    }

    // MARK: - Fixtures

    private var partMetadata: [String: Any] {
        PartMetadata(
            recordingId: recordingId, part: 1, track: Track.mono, sha256: sha, file: file
        ).dictionary
    }

    private var metaMetadata: [String: Any] {
        TransferMetadata.meta(recordingId: recordingId).dictionary
    }

    /// [folder] so two deliveries of the same part can be waiting under the same name, which is
    /// exactly what a resend looks like.
    private func write(_ contents: String, named name: String, in folder: String = "inbox") throws -> URL {
        let inbox = directory.appendingPathComponent(folder, isDirectory: true)
        try FileManager.default.createDirectory(at: inbox, withIntermediateDirectories: true)
        let url = inbox.appendingPathComponent(name)
        try Data(contents.utf8).write(to: url)
        return url
    }

    private func writeMeta(_ id: String) throws -> URL {
        try write("{\"recordingId\":\"\(id)\"}", named: "meta.json")
    }
}

/// What happened, in the order it happened — shared by the two fakes because the order *between*
/// them is the assertion.
final class Transcript: @unchecked Sendable {
    private let lock = NSLock()
    private var written: [String] = []

    var entries: [String] { lock.withLock { written } }

    func record(_ entry: String) {
        lock.withLock { written.append(entry) }
    }
}

final class FakeWatchTransferCore: WatchTransferCore, @unchecked Sendable {
    let transcript: Transcript
    var partAck = Ack(ok: true, reason: nil)
    var metaResult: AcceptMetaResult
    /// Held inside the core call while a test needs two accepts in flight at once.
    var holdPart: (@Sendable () async -> Void)?
    var onPart: (() -> Void)?

    private let lock = NSLock()
    private var files: [URL] = []

    /// Every staged file the core was handed, in call order — which is what the move tests are
    /// about: each delivery has its own, and neither is taken away under the other.
    var partFiles: [URL] { lock.withLock { files } }

    var partFile: URL? { partFiles.last }

    init(transcript: Transcript) {
        self.transcript = transcript
        metaResult = AcceptMetaResultComplete(recordingId: "01J9ABCDEFGHJKMNPQRSTVWXYZ")
    }

    func acceptPart(
        recordingId: String,
        part: Int,
        track: Track,
        sha256: String,
        file: URL
    ) async throws -> Ack {
        transcript.record("acceptPart")
        lock.withLock { files.append(file) }
        onPart?()
        await holdPart?()
        return partAck
    }

    func acceptMeta(json: String) async throws -> AcceptMetaResult {
        transcript.record("acceptMeta")
        return metaResult
    }

    func workflowId(recordingId: String) async throws -> String? {
        transcript.record("workflowId")
        return nil
    }

    func enqueue(recordingId: String, workflowId: String?) async throws {
        transcript.record("enqueue")
    }

    func onJobsDue() async {
        transcript.record("onJobsDue")
    }
}

final class FakeAckSender: WatchAckSender, @unchecked Sendable {
    private let transcript: Transcript
    private let lock = NSLock()
    private var acks: [TransferAck] = []
    var onAck: (() -> Void)?

    init(transcript: Transcript) {
        self.transcript = transcript
    }

    var sent: [TransferAck] { lock.withLock { acks } }

    func send(_ ack: TransferAck) {
        transcript.record("ack")
        lock.withLock { acks.append(ack) }
        onAck?()
    }
}

/// Opened once, by the test, when it has finished looking at what both deliveries staged.
actor Gate {
    private var waiters: [CheckedContinuation<Void, Never>] = []
    private var opened = false

    func wait() async {
        guard !opened else { return }
        await withCheckedContinuation { waiters.append($0) }
    }

    func open() {
        opened = true
        waiters.forEach { $0.resume() }
        waiters.removeAll()
    }
}
