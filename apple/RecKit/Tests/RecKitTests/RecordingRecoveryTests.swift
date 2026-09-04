import AVFoundation
import RecKitTestSupport
import ReclyCore
import XCTest
@testable import RecKit

/// The half of M4-L2 that runs without a microphone: the directory is the truth about what was
/// recorded, the rows are what the app managed to write down, and these are the rules for making
/// the two agree — the same rules `PartReconciler`/`RecordingRecovery` enforce on Android.
///
/// The core here is a real one on a throwaway file database. `AppleRuntime.driverFactory` has no
/// in-memory mode to ask for: the only knob it exposes is sqliter's `basePath`, and a `name` of
/// `:memory:` is taken as a file name, so it would put a file called `:memory:` in the temporary
/// directory rather than skipping the disk. A fresh file per test is the same isolation for the
/// same effort (`CoreBridgeTests` already works this way).
final class RecordingRecoveryTests: XCTestCase {
    private var dataDirectory: URL!

    override func setUpWithError() throws {
        dataDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("RecKitTests-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dataDirectory)
    }

    /// Segment files no row knows about are filed in part order, the arming that never got a frame
    /// is deleted, and the offsets are the durations of the parts before them — not `part × 900`.
    ///
    /// The arming file is a real one: `AVAudioFile` writes a header the moment it is opened, so what
    /// a crash leaves behind is not zero bytes but a container with nothing behind it. It is not a
    /// part, and a length guessed from its size (a few hundred bytes of MPEG-4 boilerplate) would be
    /// a part of a tenth of a second that never existed.
    func testUnfiledSegmentsAreRegisteredInOrderAndTheEmptyTailIsDropped() async throws {
        let bridge = try await makeBridge()
        let seeded = try await seed(bridge, status: RecordingStatus.recording)
        let first = try writeSegment(seconds: 1, to: seeded.directory, part: 1)
        let second = try writeSegment(seconds: 0.5, to: seeded.directory, part: 2)
        try armSegment(in: seeded.directory, part: 3)

        let pass = try await PartReconciler(core: bridge.core).reconcile(recordingId: seeded.recordingId)

        let reconciled = try XCTUnwrap(pass)

        XCTAssertEqual(reconciled.files, 2)
        XCTAssertEqual(reconciled.registered, 2)
        XCTAssertEqual(reconciled.pending, 0)
        // The containers' own lengths, which is the only honest source there is.
        XCTAssertEqual(reconciled.durationSec, first + second, accuracy: 0.0001)
        XCTAssertFalse(FileManager.default.fileExists(atPath: file(in: seeded.directory, part: 3).path))

        let filed = try await bridge.core.recordings.get(id: seeded.recordingId)
        let parts = try XCTUnwrap(filed).meta.parts
        XCTAssertEqual(parts.map(\.part), [1, 2])
        XCTAssertEqual(parts.map(\.file), [name(seeded.base, 1), name(seeded.base, 2)])
        XCTAssertEqual(parts.map(\.bytes), [size(in: seeded.directory, part: 1), size(in: seeded.directory, part: 2)])
        XCTAssertEqual(parts.map(\.durationSec), [first, second])
        XCTAssertEqual(parts.map(\.startOffsetSec), [0, first])
        // Two real hashes of two different files, and not the same one.
        XCTAssertEqual(parts[0].sha256.count, 64)
        XCTAssertNotEqual(parts[0].sha256, parts[1].sha256)
    }

    /// docs/03: the segment a process died inside has no trailing MPEG-4 atoms, so nothing on this
    /// machine can say how long it is. Registering it with an invented length would put a part in
    /// the meta that is uploaded and transcribed as though it were whole, and would shift every
    /// offset after it — so it is renamed out of the way instead (not deleted: those bytes are the
    /// user's audio) and the recording finalizes through the last part that *is* readable.
    func testAnUnreadableTailIsQuarantinedAndTheReadablePartsAreRecovered() async throws {
        let logger = RecordingLogger()
        let bridge = try await makeBridge(logger: logger)
        try await chooseDeviceDefault(bridge)
        let seeded = try await seed(bridge, status: RecordingStatus.recording)
        let first = try writeSegment(seconds: 1, to: seeded.directory, part: 1)
        // The tail: bytes the encoder was still writing when the process went away.
        try Data(count: 6000).write(to: file(in: seeded.directory, part: 2))

        let touched = await RecordingRecovery(core: bridge.core).reconcile()

        XCTAssertEqual(touched, 1)
        let row = try await bridge.core.recordings.get(id: seeded.recordingId)
        let record = try XCTUnwrap(row)
        XCTAssertEqual(record.meta.status, RecordingStatus.finalized)
        XCTAssertEqual(record.meta.parts.map(\.part), [1], "only the part that could be read")
        XCTAssertEqual(record.meta.durationSec?.doubleValue ?? 0, first, accuracy: 0.0001)
        XCTAssertTrue(logger.events.contains("rec.part.corrupt"))

        let tail = file(in: seeded.directory, part: 2)
        XCTAssertFalse(FileManager.default.fileExists(atPath: tail.path))
        XCTAssertTrue(
            FileManager.default.fileExists(atPath: tail.path + PartReconciler.corruptSuffix),
            "the audio is kept, just out of the way"
        )

        // And the next pass has nothing left to meet: the quarantined file is not a segment any more.
        let again = await RecordingRecovery(core: bridge.core).reconcile()
        XCTAssertEqual(again, 0)
    }

    /// The crash this is all for: the boundary wrote its `.pending` marker and died before the row
    /// landed. The next pass files the part, clears the marker, closes the meta and queues the job.
    func testAPendingMarkerIsClearedWhenTheNextPassFilesThePart() async throws {
        let bridge = try await makeBridge()
        try await chooseDeviceDefault(bridge)
        let seeded = try await seed(bridge, status: RecordingStatus.recording)
        let first = try writeSegment(seconds: 1, to: seeded.directory, part: 1)
        markPending(in: seeded.directory, part: 1)

        let touched = await RecordingRecovery(core: bridge.core).reconcile()

        XCTAssertEqual(touched, 1)
        let row = try await bridge.core.recordings.get(id: seeded.recordingId)
        let record = try XCTUnwrap(row)
        XCTAssertEqual(record.meta.status, RecordingStatus.finalized)
        XCTAssertEqual(record.meta.parts.count, 1)
        XCTAssertEqual(record.meta.durationSec?.doubleValue ?? 0, first, accuracy: 0.0001)
        XCTAssertEqual(markers(in: seeded.directory), [])
        let jobs = try await bridge.core.recordings.jobStatuses(recordingId: seeded.recordingId)
        XCTAssertFalse(jobs.isEmpty, "a recovered recording is queued — nobody is going to name it")
    }

    /// A marker that survives the pass means audio exists that could not be filed. The meta is left
    /// open on purpose: a row that says `finalized` is a row nothing looks at again, and the missing
    /// part would be uploaded away.
    func testAMarkerThatCannotBeClearedHoldsTheFinalizeBack() async throws {
        let bridge = try await makeBridge()
        let seeded = try await seed(bridge, status: RecordingStatus.recording)
        try writeSegment(seconds: 1, to: seeded.directory, part: 1)
        // The marker of a part whose audio is no longer there to file — nothing this pass can do.
        markPending(in: seeded.directory, part: 2)

        let result = await PartReconciler(core: bridge.core).closeOut(
            recordingId: seeded.recordingId, ledgerSec: 1, title: nil, gaps: []
        )

        guard case .deferred(let recordingId, let pending) = result else {
            return XCTFail("expected a deferred stop, got \(result)")
        }
        XCTAssertEqual(recordingId, seeded.recordingId)
        XCTAssertEqual(pending, 1)
        let row = try await bridge.core.recordings.get(id: seeded.recordingId)
        let record = try XCTUnwrap(row)
        XCTAssertEqual(record.meta.status, RecordingStatus.recording)
        // The part that *could* be filed still was: the deferral is about what is missing.
        XCTAssertEqual(record.meta.parts.count, 1)
    }

    /// A recording whose only segment was the tail a crash left unreadable, quarantined by an
    /// earlier pass — so this one finds no segment files at all. Nothing in it can be played, sent
    /// or repaired from the app, and a row that stayed `recording` for good was one the user could
    /// do nothing with (2026-09-04): the row and the directory go, the `.corrupt` bytes with them.
    /// (The Android and Windows `RecordingRecovery` do the same.)
    func testARecordingWithNothingButACorruptSegmentIsDropped() async throws {
        let logger = RecordingLogger()
        let bridge = try await makeBridge(logger: logger)
        let seeded = try await seed(bridge, status: RecordingStatus.recording)
        let quarantined = file(in: seeded.directory, part: 1)
            .appendingPathExtension(String(PartReconciler.corruptSuffix.dropFirst()))
        try truncatedTail(samples: 6000).write(to: quarantined)

        let touched = await RecordingRecovery(core: bridge.core).reconcile()

        XCTAssertEqual(touched, 1)
        XCTAssertTrue(logger.events.contains("rec.recovered.empty"))
        let row = try await bridge.core.recordings.get(id: seeded.recordingId)
        XCTAssertNil(row)
        XCTAssertFalse(FileManager.default.fileExists(atPath: seeded.directory.path))
        let jobs = try await bridge.core.recordings.jobStatuses(recordingId: seeded.recordingId)
        XCTAssertTrue(jobs.isEmpty, "nothing was queued from it")
    }

    /// The same recording, met on the pass that does the quarantining: the file is a segment when
    /// the walk starts and a `.corrupt` one when it ends, and it counts as neither a part nor a
    /// reason to keep the row — the recording is dropped on this pass, not finalized with zero
    /// parts (which would queue a job with nothing to send).
    func testARecordingWhoseOnlySegmentIsUnreadableIsDroppedOnTheSamePass() async throws {
        let logger = RecordingLogger()
        let bridge = try await makeBridge(logger: logger)
        let seeded = try await seed(bridge, status: RecordingStatus.recording)
        let tail = file(in: seeded.directory, part: 1)
        try truncatedTail(samples: 6000).write(to: tail)

        let touched = await RecordingRecovery(core: bridge.core).reconcile()

        XCTAssertEqual(touched, 1)
        XCTAssertTrue(logger.events.contains("rec.part.corrupt"))
        XCTAssertTrue(logger.events.contains("rec.recovered.empty"))
        let row = try await bridge.core.recordings.get(id: seeded.recordingId)
        XCTAssertNil(row)
        XCTAssertFalse(FileManager.default.fileExists(atPath: seeded.directory.path))
        let jobs = try await bridge.core.recordings.jobStatuses(recordingId: seeded.recordingId)
        XCTAssertTrue(jobs.isEmpty, "nothing to upload, so nothing is queued")
    }

    /// A segment that never got a sample — the container's opening and nothing behind it, which is
    /// what a kill before the first frame leaves (28 bytes of `ftyp`, measured) — is quarantined
    /// like any tail, and the recording is dropped like any other with nothing readable in it.
    func testARecordingWhoseOnlySegmentNeverGotASampleIsDropped() async throws {
        let logger = RecordingLogger()
        let bridge = try await makeBridge(logger: logger)
        let seeded = try await seed(bridge, status: RecordingStatus.recording)
        try mp4Opening().write(to: file(in: seeded.directory, part: 1))

        let touched = await RecordingRecovery(core: bridge.core).reconcile()

        XCTAssertEqual(touched, 1)
        XCTAssertTrue(logger.events.contains("rec.part.corrupt"))
        XCTAssertTrue(logger.events.contains("rec.recovered.empty"))
        let row = try await bridge.core.recordings.get(id: seeded.recordingId)
        XCTAssertNil(row)
        XCTAssertFalse(FileManager.default.fileExists(atPath: seeded.directory.path))

        // And one quarantined by an earlier pass goes the same way — here with Core Audio's
        // reserve written and nothing after it.
        let again = try await seed(bridge, status: RecordingStatus.recording)
        let quarantined = file(in: again.directory, part: 1)
            .appendingPathExtension(String(PartReconciler.corruptSuffix.dropFirst()))
        try coreAudioReserve().write(to: quarantined)
        let dropped = await RecordingRecovery(core: bridge.core).reconcile()
        XCTAssertEqual(dropped, 1)
        let later = try await bridge.core.recordings.get(id: again.recordingId)
        XCTAssertNil(later)
    }

    /// M4-L3: a meeting recording leaves three files per part, and the reconciler has to walk them
    /// as one slice of the timeline — same part number, same `startOffsetSec`, three rows. Nothing
    /// on disk says which of them is which except the name, so this is also the check that the name
    /// the recorder writes is the name the reconciler reads back.
    ///
    /// The `sys` file of part 1 is the tail a crash left unreadable, which is the case that decides
    /// whether the tracks are really independent: it is quarantined, `mic` and `mix` of the same
    /// part are still filed, and part 2 still starts where part 1 ended.
    func testAMeetingRecordingsThreeTracksAreOnePartWithOneOffset() async throws {
        let bridge = try await makeBridge()
        let seeded = try await seed(bridge, status: RecordingStatus.recording, tracks: Self.meetingTracks)
        var written: [Track: Double] = [:]
        for track in Self.meetingTracks {
            written[track] = try writeSegment(seconds: 1, to: seeded.directory, part: 1, track: track)
            _ = try writeSegment(seconds: 0.5, to: seeded.directory, part: 2, track: track)
        }
        // The one file the process died inside; the other two of its part are whole.
        let tail = file(in: seeded.directory, part: 1, track: Track.sys)
        try Data(count: 6000).write(to: tail)

        let pass = try await PartReconciler(core: bridge.core).reconcile(recordingId: seeded.recordingId)

        let reconciled = try XCTUnwrap(pass)
        XCTAssertEqual(reconciled.files, 5, "five readable files, not five parts")
        XCTAssertEqual(reconciled.quarantined, 1)
        XCTAssertEqual(reconciled.registered, 5)
        XCTAssertEqual(reconciled.pending, 0)

        let row = try await bridge.core.recordings.get(id: seeded.recordingId)
        let parts = try XCTUnwrap(row).meta.parts
        XCTAssertEqual(parts.map(\.part), [1, 1, 2, 2, 2])
        XCTAssertEqual(
            parts.map(\.track),
            [Track.mic, Track.mix, Track.mic, Track.sys, Track.mix],
            "the unreadable `sys` of part 1 is the only one missing"
        )
        let first = try XCTUnwrap(written[Track.mic])
        XCTAssertEqual(parts.map(\.startOffsetSec), [0, 0, first, first, first])
        XCTAssertEqual(
            reconciled.durationSec, first + (parts.last?.durationSec ?? 0), accuracy: 0.0001,
            "a part is one slice of the timeline however many files it took"
        )
        XCTAssertTrue(
            FileManager.default.fileExists(atPath: tail.path + PartReconciler.corruptSuffix),
            "and the bytes of the one that could not be read are kept"
        )
    }

    /// A start that died between creating the row and producing a sample. Nothing to keep, and a
    /// row nobody will ever act on is worse than no row.
    func testARecordingWithNoAudioIsDropped() async throws {
        let bridge = try await makeBridge()
        let seeded = try await seed(bridge, status: RecordingStatus.recording)

        let touched = await RecordingRecovery(core: bridge.core).reconcile()

        XCTAssertEqual(touched, 1)
        let record = try await bridge.core.recordings.get(id: seeded.recordingId)
        XCTAssertNil(record)
        XCTAssertFalse(FileManager.default.fileExists(atPath: seeded.directory.path))
    }

    /// The process died while the title prompt was open: the recording is finalized and complete,
    /// and the only thing missing is the job the stop was going to create.
    func testAFinalizedRecordingWithNoJobIsEnqueued() async throws {
        let logger = RecordingLogger()
        let bridge = try await makeBridge(logger: logger)
        try await chooseDeviceDefault(bridge)
        let seeded = try await seed(bridge, status: RecordingStatus.finalized)
        try writeSegment(seconds: 1, to: seeded.directory, part: 1)

        let touched = await RecordingRecovery(core: bridge.core).reconcile()

        XCTAssertEqual(touched, 1)
        let jobs = try await bridge.core.recordings.jobStatuses(recordingId: seeded.recordingId)
        XCTAssertFalse(jobs.isEmpty)
        XCTAssertTrue(logger.events.contains("rec.recovered.enqueue"))

        // And it is not enqueued a second time on the next launch.
        let again = await RecordingRecovery(core: bridge.core).reconcile()
        XCTAssertEqual(again, 0)
        let after = try await bridge.core.recordings.jobStatuses(recordingId: seeded.recordingId)
        XCTAssertEqual(after.count, jobs.count)
    }

    /// ADR-016: a device that has not picked a default resolves `NO_WORKFLOW`, and the queue refusing
    /// the recording is not the same thing as having recovered it. Counting it would report a
    /// recovery on every pass forever, and `rec.recovered.ready` would claim a success that never
    /// happened — the log says what the queue actually answered instead. (The Windows
    /// `RecordingRecovery.enqueueIfNoJob` draws the same line.)
    func testAFinalizedRecordingIsNotCountedWhenTheQueueHasNoWorkflowToRunIt() async throws {
        let logger = RecordingLogger()
        let bridge = try await makeBridge(logger: logger)
        let seeded = try await seed(bridge, status: RecordingStatus.finalized)
        try writeSegment(seconds: 1, to: seeded.directory, part: 1)
        let recovery = RecordingRecovery(core: bridge.core)

        // The first pass has the unfiled part to register, which *is* work; the queue refusing the
        // recording afterwards is not, and the pass after it has nothing else left to do.
        let first = await recovery.reconcile()
        XCTAssertEqual(first, 1)
        let again = await recovery.reconcile()

        XCTAssertEqual(again, 0, "a refused enqueue is not a recovery")
        XCTAssertTrue(logger.events.contains("rec.recovered.enqueue"))
        XCTAssertFalse(logger.events.contains("rec.recovered.ready"), "no success is claimed")
        let jobs = try await bridge.core.recordings.jobStatuses(recordingId: seeded.recordingId)
        XCTAssertTrue(jobs.isEmpty)
    }

    /// The other half of the same rule: retrying on every pass is the self-heal, so the pass after
    /// the user finally picks a default is the one that queues the recording — and *that* pass is
    /// the one that counts it.
    func testTheRecordingIsQueuedByTheFirstPassAfterADefaultIsChosen() async throws {
        let bridge = try await makeBridge()
        let seeded = try await seed(bridge, status: RecordingStatus.finalized)
        try writeSegment(seconds: 1, to: seeded.directory, part: 1)
        let recovery = RecordingRecovery(core: bridge.core)
        _ = await recovery.reconcile()
        let refused = await recovery.reconcile()
        XCTAssertEqual(refused, 0, "nothing but the refused enqueue is left")

        try await bridge.core.workflows.setDeviceDefault(workflowId: WorkflowRepository.companion.MEMO_ID)
        let touched = await recovery.reconcile()

        XCTAssertEqual(touched, 1)
        let jobs = try await bridge.core.recordings.jobStatuses(recordingId: seeded.recordingId)
        XCTAssertFalse(jobs.isEmpty)
    }

    /// The done condition's shape, on disk: a `meta.json` next to the parts, `status: finalized`,
    /// one part with its bytes and sha256 — plus the `gaps` an engine restart put there, which is
    /// the only thing a stop can still add to the meta.
    func testTheStopWritesFinalizedMetaWithPartsAndGaps() async throws {
        let bridge = try await makeBridge()
        let seeded = try await seed(bridge, status: RecordingStatus.recording)
        let first = try writeSegment(seconds: 1, to: seeded.directory, part: 1)

        let result = await PartReconciler(core: bridge.core).closeOut(
            recordingId: seeded.recordingId,
            ledgerSec: 1,
            title: "주간 회의",
            gaps: [ReclyCore.Range(startSec: 0.4, endSec: 0.9, reason: "input_device_change")]
        )

        guard case .finalized(let outcome) = result else {
            return XCTFail("expected a finalized stop, got \(result)")
        }
        XCTAssertEqual(outcome.parts, 1)
        XCTAssertEqual(outcome.durationSec, first, accuracy: 0.0001)

        let onDisk = try JSONSerialization.jsonObject(
            with: Data(contentsOf: seeded.directory.appendingPathComponent("\(seeded.base).meta.json"))
        ) as? [String: Any]
        XCTAssertEqual(onDisk?["status"] as? String, "finalized")
        XCTAssertEqual(onDisk?["title"] as? String, "주간 회의")
        XCTAssertEqual((onDisk?["tracks"] as? [String]), ["mono"])
        let parts = try XCTUnwrap(onDisk?["parts"] as? [[String: Any]])
        XCTAssertEqual(parts.count, 1)
        XCTAssertEqual(parts[0]["file"] as? String, name(seeded.base, 1))
        XCTAssertEqual(parts[0]["bytes"] as? Int64, size(in: seeded.directory, part: 1))
        XCTAssertEqual((parts[0]["sha256"] as? String)?.count, 64)
        let gaps = try XCTUnwrap(onDisk?["gaps"] as? [[String: Any]])
        XCTAssertEqual(gaps.count, 1)
        XCTAssertEqual(gaps[0]["reason"] as? String, "input_device_change")
    }

    // MARK: - Seeding

    private struct Seeded {
        let recordingId: String
        let base: String
        let directory: URL
    }

    /// A recording row and its directory, the way `SegmentedRecorder.start` leaves them — minus the
    /// microphone, which is the whole point of doing it here.
    private static let meetingTracks = [Track.mic, Track.sys, Track.mix]

    private func seed(
        _ bridge: CoreBridge,
        status: RecordingStatus,
        tracks: [Track] = [Track.mono]
    ) async throws -> Seeded {
        let startedAt = bridge.deps.clock.now()
        let recordingId = Ulid.shared.generate(clock: FixedKotlinClock(startedAt))
        let meta = RecordingMeta(
            schema: 1,
            recordingId: recordingId,
            source: Source.desktop,
            platform: Platform.macos,
            deviceId: bridge.deps.device.deviceId,
            deviceName: bridge.deps.device.name,
            workflowId: nil,
            title: nil,
            startedAt: startedAt.isoUtc,
            endedAt: status == RecordingStatus.finalized ? startedAt.isoUtc : nil,
            durationSec: status == RecordingStatus.finalized ? 1 : nil,
            timezone: "Asia/Seoul",
            audio: AudioSettings(
                codec: Codec.aacLc,
                container: Container.m4A,
                sampleRateHz: Int32(SegmentedRecorder.sampleRateHz),
                channels: 1,
                bitrateKbps: Int32(SegmentedRecorder.bitrateKbps),
                segmentSec: Int32(SegmentedRecorder.defaultSegmentSec)
            ),
            tracks: tracks,
            parts: [],
            gaps: [],
            silenced: [],
            context: nil,
            status: status
        )
        let base = MetaWriter.shared.baseName(meta: meta)
        let directory = dataDirectory
            .appendingPathComponent("recordings", isDirectory: true)
            .appendingPathComponent(base, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try await bridge.core.recordings.create(meta: meta, dir: directory.okioPath)
        return Seeded(recordingId: recordingId, base: base, directory: directory)
    }

    private func name(_ base: String, _ part: Int, _ track: Track = Track.mono) -> String {
        MetaWriter.shared.partFileName(base: base, part: Int32(part), track: track)
    }

    private func file(in directory: URL, part: Int, track: Track = Track.mono) -> URL {
        directory.appendingPathComponent(name(directory.lastPathComponent, part, track))
    }

    /// The boxes a muxer writes before the first sample: `ftyp` and nothing else.
    private func mp4Opening() -> Data {
        var data = Data([0, 0, 0, 28])
        data.append(Data("ftypM4A ".utf8))
        data.append(Data([0, 0, 0, 0]))
        data.append(Data("M4A mp42isom".utf8))
        return data
    }

    /// The tail a kill leaves, laid out the way `AVAudioFile` leaves it (measured, not assumed):
    /// the opening, the zero-filled run Core Audio reserves for the `mdat` header it only writes at
    /// close, and this many bytes of encoder output after it — no `moov`, so nothing can read it,
    /// but the audio is there.
    private func truncatedTail(samples: Int) -> Data {
        var data = coreAudioReserve()
        data.append(Data((0..<samples).map { UInt8(truncatingIfNeeded: $0 &* 31 &+ 7) }))
        return data
    }

    /// The same, with nothing written after the reserve.
    private func coreAudioReserve() -> Data {
        var data = mp4Opening()
        data.append(Data(count: 24_549))
        return data
    }

    /// A real segment, written through the recorder's own opener and released the way a boundary
    /// releases one — the release is what writes the trailing atoms the reconciler reads. Returns
    /// the seconds the container ends up carrying, which is what the part will say (AAC rounds the
    /// tail up to its own frame, so it is read back rather than assumed).
    @discardableResult
    private func writeSegment(
        seconds: Double,
        to directory: URL,
        part: Int,
        track: Track = Track.mono
    ) throws -> Double {
        let named = name(directory.lastPathComponent, part, track)
        var handle: AVAudioFile? = try SegmentedRecorder.openSegmentFile(named: named, in: directory)
        let format = try XCTUnwrap(handle?.processingFormat)
        let frames = AVAudioFrameCount(seconds * Double(SegmentedRecorder.sampleRateHz))
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames))
        buffer.frameLength = frames
        try handle?.write(from: buffer)
        handle = nil
        return try XCTUnwrap(PartReconciler.containerDurationSec(of: file(in: directory, part: part, track: track)))
    }

    /// The segment the engine had opened and was about to write into: a header and nothing behind it.
    private func armSegment(in directory: URL, part: Int) throws {
        var handle: AVAudioFile? = try SegmentedRecorder.openSegmentFile(
            named: name(directory.lastPathComponent, part), in: directory
        )
        handle = nil
    }

    private func size(in directory: URL, part: Int) -> Int64 {
        let attributes = try? FileManager.default.attributesOfItem(atPath: file(in: directory, part: part).path)
        return (attributes?[.size] as? NSNumber)?.int64Value ?? 0
    }

    private func markPending(in directory: URL, part: Int) {
        try? Data().write(
            to: directory.appendingPathComponent(name(directory.lastPathComponent, part) + PartReconciler.pendingSuffix)
        )
    }

    private func markers(in directory: URL) -> [String] {
        ((try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? [])
            .filter { $0.hasSuffix(PartReconciler.pendingSuffix) }
    }

    /// ADR-016: what a recovered recording runs is this device's own default, and a shell picks one
    /// at startup (`seed(preferredDefaultId:)`). A device that has *not* chosen parks the recording
    /// as `NO_WORKFLOW`, which is the selector's business rather than the reconciler's — so the
    /// cases below that are about the queue start from a device that has one.
    private func chooseDeviceDefault(_ bridge: CoreBridge) async throws {
        _ = try await bridge.core.workflows.seed(
            preferredDefaultId: WorkflowRepository.companion.MEMO_ID
        )
    }

    private func makeBridge(logger: any ReclyCore.Logger = OSLogLogger()) async throws -> CoreBridge {
        try await CoreBridge.make(
            appVersion: "0.0.0-test",
            deviceName: "RecKitTests",
            dataDirectory: dataDirectory,
            databaseName: "reckit-tests-\(UUID().uuidString).db",
            logger: logger,
            secureStore: InMemorySecureStore()
        )
    }
}
