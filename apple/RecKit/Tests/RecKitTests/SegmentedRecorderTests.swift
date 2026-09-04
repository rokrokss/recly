import AVFoundation
import CryptoKit
import RecKitTestSupport
import ReclyCore
import XCTest
@testable import RecKit

/// The recorder's real path with a fake input in front of it: the tap callback, the converter, the
/// AAC encoder, the 900-second boundary (five, here), the hash and `addPart`. Everything except the
/// microphone itself, which is the one thing an automated run cannot have — and which
/// `MicrophoneSmokeTests` covers by hand.
///
/// The input is 16 kHz mono Float32, the format the segment file takes, so the converter is a
/// pass-through and a frame pushed in is that frame written out. That is what lets these assert an
/// *exact* frame count rather than "about the right length".
final class SegmentedRecorderTests: XCTestCase {
    private var dataDirectory: URL!

    override func setUpWithError() throws {
        dataDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("RecKitTests-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dataDirectory)
    }

    // MARK: - The boundary

    /// The claim ADR-006 makes and nothing else here can check: the boundary loses nothing. Seven
    /// seconds of a known tone go in across a five-second boundary; the two parts are decoded and
    /// laid end to end, and what comes back has to be the same tone, sample for sample, with the
    /// seam invisible in it.
    ///
    /// A dropped or duplicated frame at the seam would shift everything after it by one sample, and
    /// the last assertion is what says that would be caught: the same comparison one frame out of
    /// step is four times the error.
    func testTheBoundaryLosesNoFrameAndRepeatsNone() async throws {
        try await assertTheSeamIsInvisible(inputRateHz: 16_000, tolerance: 0)
    }

    /// The same claim with the resampler in the path, which is the only way it ever runs on a Mac:
    /// the device's rate is 48 kHz and `AVAudioConverter` is between the tap and the file. A
    /// resampler carries state across buffers, so a boundary is one more place it could drop or
    /// repeat a frame — and the converter's own tail has to reach the file too, or the recording is
    /// short by a filter's worth of audio (see `SegmentedRecorder.drain`).
    func testTheBoundaryLosesNoFrameAtTheDeviceRateEither() async throws {
        try await assertTheSeamIsInvisible(inputRateHz: 48_000, tolerance: 1)
    }

    private func assertTheSeamIsInvisible(inputRateHz: Double, tolerance: Int) async throws {
        let bridge = try await makeBridge()
        let input = FakeAudioInput(format: FakeAudioInput.format(inputRateHz))
        let failures = Failures()
        let recorder = SegmentedRecorder(core: bridge.core, segmentSec: 5, input: input) { failures.record($0) }

        let recordingId = try await recorder.start(workflowId: nil, title: nil)
        let pushed = Int((Double(Self.totalFrames) * inputRateHz / Double(SegmentedRecorder.sampleRateHz)).rounded())
        XCTAssertTrue(
            input.push(frames: pushed) { Self.tone($0, rateHz: inputRateHz) },
            "the tap went away mid-recording"
        )
        let result = await recorder.stop(title: nil)

        guard case .finalized(let outcome) = result else {
            return XCTFail("expected a finalized stop, got \(result) (errors: \(failures.all))")
        }
        XCTAssertEqual(failures.fatal.count, 0, "\(failures.all)")
        XCTAssertEqual(outcome.parts, 2)

        let row = try await bridge.core.recordings.get(id: recordingId)
        let record = try XCTUnwrap(row)
        let parts = record.meta.parts
        XCTAssertEqual(parts.map(\.part), [1, 2])
        // The frames the container itself carries, not the durations the rollover wrote down.
        let decoded = try parts.map { try decode(record.dir.url.appendingPathComponent($0.file)) }
        XCTAssertEqual(decoded[0].count, Self.boundaryFrame, "the closed segment is five seconds to the frame")

        let joined = decoded.flatMap { $0 }
        XCTAssertLessThanOrEqual(
            abs(joined.count - Self.totalFrames), tolerance,
            "every frame pushed in came back out, once (\(joined.count) of \(Self.totalFrames)) — "
                + "resampled, and including the converter's tail"
        )
        // The first and last few milliseconds are the encoder's own edges; the seam is in the middle
        // of the run, which is what this is about.
        let interior = Self.edge ..< (joined.count - Self.edge)
        let worst = interior.reduce(Float(0)) { max($0, abs(joined[$1] - Self.sample($1))) }
        XCTAssertLessThan(worst, 0.03, "the decoded run is the tone that was pushed in, seam included")

        let shifted = interior.reduce(Float(0)) { max($0, abs(joined[$1] - Self.sample($1 + 1))) }
        XCTAssertGreaterThan(
            shifted, 0.04,
            "one frame of drift would be visible in that comparison — so its absence means something"
        )
    }

    /// Sol M4-L2: `AVAudioConverter` is asked for more with `.noDataNow`, which tells it to keep
    /// what it could not finish for the callback that follows. At a stop there is no callback that
    /// follows, and what it is holding — the resampler's filter delay — is audio the microphone
    /// really did capture. `drain` is the `.endOfStream` ask that gets it out.
    ///
    /// Frames, not seconds: at 48 kHz in and 16 kHz out, three input frames are one output frame,
    /// and the file has to carry exactly the third of what went in.
    func testTheConvertersTailReachesTheFileAtEveryDeviceRate() async throws {
        for rate in [48_000.0, 44_100.0] {
            let bridge = try await makeBridge()
            let input = FakeAudioInput(format: FakeAudioInput.format(rate))
            let failures = Failures()
            // One segment: this is about the end of the recording, not the boundary.
            let recorder = SegmentedRecorder(core: bridge.core, segmentSec: 900, input: input) { failures.record($0) }

            let recordingId = try await recorder.start(workflowId: nil, title: nil)
            let pushed = Int(rate * 2)
            XCTAssertTrue(input.push(frames: pushed) { Self.tone($0, rateHz: rate) })
            let result = await recorder.stop(title: nil)

            guard case .finalized = result else {
                return XCTFail("expected a finalized stop at \(rate) Hz, got \(result) (errors: \(failures.all))")
            }
            let row = try await bridge.core.recordings.get(id: recordingId)
            let record = try XCTUnwrap(row)
            let file = try XCTUnwrap(record.meta.parts.first?.file)
            let frames = try decode(record.dir.url.appendingPathComponent(file)).count
            let expected = Int(
                (Double(pushed) * Double(SegmentedRecorder.sampleRateHz) / rate).rounded()
            )
            XCTAssertLessThanOrEqual(
                abs(frames - expected), 1,
                "\(rate) Hz in: the file is what the resampler owes, tail included (\(frames) of \(expected))"
            )
        }
    }

    /// Sol M4-L2: a rollover that hashes the segment while something still holds the `AVAudioFile`
    /// hashes a container with no trailing MPEG-4 atoms in it — bytes that are still growing, and a
    /// sha256 of a file no reader can open.
    ///
    /// `bytes` and `sha256` in the part are what the registration task measured at hash time, and
    /// nothing has touched the file since, so comparing them with the file as it now stands is
    /// exactly the "size at hash time, size after a delay" check.
    func testTheClosedSegmentIsReleasedBeforeItIsHashed() async throws {
        let bridge = try await makeBridge()
        let input = FakeAudioInput()
        let failures = Failures()
        let recorder = SegmentedRecorder(core: bridge.core, segmentSec: 5, input: input) { failures.record($0) }

        let recordingId = try await recorder.start(workflowId: nil, title: nil)
        XCTAssertTrue(input.push(frames: Self.totalFrames) { Self.sample($0) })
        _ = await recorder.stop(title: nil)

        let row = try await bridge.core.recordings.get(id: recordingId)
        let record = try XCTUnwrap(row)
        let closed = try XCTUnwrap(record.meta.parts.first)
        let url = record.dir.url.appendingPathComponent(closed.file)

        // Long enough for a file that was still open to have been flushed behind the hash's back.
        try await Task.sleep(nanoseconds: 200_000_000)
        let onDisk = try XCTUnwrap(
            (try FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value
        )
        XCTAssertEqual(closed.bytes, onDisk, "the file was whole when its size went into the part")
        XCTAssertEqual(try sha256(of: url), closed.sha256, "and the hash is of the file that is there now")
        // The trailing atoms are what a reader needs, and they are written by the release: this is
        // `nil` for a container that was hashed before it was let go.
        XCTAssertNotNil(PartReconciler.containerDurationSec(of: url))
        XCTAssertEqual(failures.fatal.count, 0, "\(failures.all)")
    }

    /// Sol M4-L2: the container of a segment the boundary has just closed can come back unreadable —
    /// the trailing MPEG-4 atoms never landed, the volume filled up, something truncated it. Its
    /// length is then a thing nobody on this machine knows, and registering it anyway puts a part in
    /// the meta carrying an invented one: uploaded and transcribed as though it were whole, and
    /// shifting every offset after it. So it is a registration failure like any other — no hash, no
    /// row, a `.pending` marker — and the rule for an unreadable segment (docs/03) takes it from
    /// there: `.corrupt`, and the recording finalizes through the parts that *are* readable.
    ///
    /// The stop's own reconcile is the pass that meets it first, so the quarantine happens there
    /// rather than at the next launch; a later recovery pass has nothing left to find. That is the
    /// same order Android's `PartReconciler` walks — the marker is cleared *by* the quarantine,
    /// because a marker whose file will never be filable would defer the finalize for good.
    func testAnUnreadableClosedSegmentIsNotRegistered() async throws {
        let logger = RecordingLogger()
        let bridge = try await makeBridge(logger: logger)
        let input = FakeAudioInput()
        let failures = Failures()
        let recorder = SegmentedRecorder(core: bridge.core, segmentSec: 1, input: input) { failures.record($0) }
        // The one state no fake input can produce from outside: that file is the real encoder's.
        recorder.afterSegmentClosed = { url in
            guard url.lastPathComponent.contains("_p001_") else { return }
            try? Data(count: 6_000).write(to: url)
        }

        let recordingId = try await recorder.start(workflowId: nil, title: nil)
        let opened = try await bridge.core.recordings.get(id: recordingId)
        let directory = try XCTUnwrap(opened).dir.url
        let corrupted = MetaWriter.shared.partFileName(
            base: directory.lastPathComponent, part: 1, track: Track.mono
        )

        // Just past the first boundary: part 1 is closed, corrupted behind the recorder's back and
        // handed to the registration, which is where the marker comes from.
        XCTAssertTrue(input.push(frames: 20_000) { Self.sample($0) })
        let marker = directory.appendingPathComponent(corrupted + PartReconciler.pendingSuffix)
        await waitUntil { !failures.all.isEmpty }
        XCTAssertTrue(
            FileManager.default.fileExists(atPath: marker.path),
            "a segment that cannot be read back is deferred, not filed"
        )
        let midway = try await bridge.core.recordings.get(id: recordingId)
        XCTAssertEqual(try XCTUnwrap(midway).meta.parts.count, 0, "and it is certainly not a part")
        XCTAssertTrue(logger.events.contains("rec.part.unreadable"))
        XCTAssertEqual(
            failures.all.map(\.fatal), [false],
            "one unfilable segment is reported, and does not end the recording: \(failures.all)"
        )

        // The rest of the recording: a second boundary and a tail, both of them fine.
        XCTAssertTrue(input.push(frames: 20_000) { Self.sample($0 + 20_000) })
        let result = await recorder.stop(title: nil)

        guard case .finalized(let outcome) = result else {
            return XCTFail("expected a finalized stop, got \(result) (errors: \(failures.all))")
        }
        XCTAssertEqual(outcome.parts, 2)
        let row = try await bridge.core.recordings.get(id: recordingId)
        let record = try XCTUnwrap(row)
        XCTAssertEqual(record.meta.parts.map(\.part), [2, 3], "the readable parts, and only those")
        XCTAssertEqual(record.meta.durationSec?.doubleValue ?? 0, 1.5, accuracy: 0.05)
        XCTAssertTrue(logger.events.contains("rec.part.corrupt"))

        let quarantined = directory.appendingPathComponent(corrupted + PartReconciler.corruptSuffix)
        XCTAssertTrue(FileManager.default.fileExists(atPath: quarantined.path), "the bytes are kept")
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.appendingPathComponent(corrupted).path))
        XCTAssertFalse(
            FileManager.default.fileExists(atPath: marker.path),
            "and its marker went with it — one that outlived its file would defer the finalize for good"
        )

        // And the next launch has nothing left to meet.
        let touched = await RecordingRecovery(core: bridge.core).reconcile()
        let after = try await bridge.core.recordings.get(id: recordingId)
        XCTAssertEqual(try XCTUnwrap(after).meta.parts.map(\.part), [2, 3], "\(touched)")
    }

    // MARK: - The input's lifecycle

    /// Both the HAL listener and `AVAudioEngineConfigurationChange` fire for one device change, and
    /// a third can arrive while the first is still being served. Two teardowns for one change take
    /// the tap down again the instant it came back — and write a second `gaps` entry for an outage
    /// that had already ended.
    ///
    /// The control queue is held open inside the restart's reattach while the second notification
    /// lands, so this is the "within a restart" case, not merely "two at once".
    func testTwoNotificationsAboutOneDeviceChangeAreOneRestart() async throws {
        let bridge = try await makeBridge()
        let input = FakeAudioInput()
        let failures = Failures()
        let recorder = SegmentedRecorder(core: bridge.core, segmentSec: 5, input: input) { failures.record($0) }
        let recordingId = try await recorder.start(workflowId: nil, title: nil)
        XCTAssertEqual(input.starts, 1)

        let entered = expectation(description: "the restart is on the control queue")
        let gate = DispatchSemaphore(value: 0)
        input.gateNextStart { entered.fulfill(); gate.wait() }
        // The hardware moved: the session is attached to a format the input no longer reports.
        input.format = Self.otherFormat
        input.onConfigurationChange?("input_device_change")
        await fulfillment(of: [entered], timeout: 5)

        // Armed for the *next* attach, which is the one that must never come. Waiting on it is also
        // what drains the control queue, so by the time it times out every restart that was ever
        // going to run has run — and the recording is still going, so nothing else cancelled them.
        let second = expectation(description: "a second restart")
        second.isInverted = true
        input.gateNextStart { second.fulfill() }
        input.onConfigurationChange?("engine_configuration_change")
        gate.signal()
        await fulfillment(of: [second], timeout: 1)

        XCTAssertEqual(input.starts, 2, "one device change is one restart, however many notifications")
        XCTAssertEqual(input.stops, 1, "and one teardown — the second notification touched nothing")
        _ = await recorder.stop(title: nil)
        let row = try await bridge.core.recordings.get(id: recordingId)
        let record = try XCTUnwrap(row)
        XCTAssertEqual(record.meta.gaps.count, 1, "one outage, one entry")
        XCTAssertEqual(record.meta.gaps.first?.reason, "input_device_change")
    }

    /// A stop that lands while the tap is being rebuilt must still end with the microphone given
    /// back. It goes through the same queue as the restart, so it cannot pull the input out from
    /// under one halfway through — it lands behind it, and it is the last word.
    func testAStopDuringARestartEndsWithTheInputStoppedAndNoTap() async throws {
        let bridge = try await makeBridge()
        let input = FakeAudioInput()
        let failures = Failures()
        let recorder = SegmentedRecorder(core: bridge.core, segmentSec: 5, input: input) { failures.record($0) }
        _ = try await recorder.start(workflowId: nil, title: nil)

        let entered = expectation(description: "the restart is on the control queue")
        let gate = DispatchSemaphore(value: 0)
        input.gateNextStart { entered.fulfill(); gate.wait() }
        input.format = Self.otherFormat
        input.onConfigurationChange?("input_device_change")
        await fulfillment(of: [entered], timeout: 5)

        // The stop arrives while the restart is halfway through putting the tap back.
        let stopping = Task { await recorder.stop(title: nil) }
        try await Task.sleep(nanoseconds: 100_000_000)
        gate.signal()
        _ = await stopping.value

        XCTAssertFalse(input.isRunning, "the input has to end stopped, whatever the restart was doing")
        XCTAssertFalse(input.tapped, "a tap left behind is a microphone left lit")
    }

    /// The other order: the restart was asked for, has not run yet, and then the recording ends.
    /// Reattaching now would leave a live tap behind a recording nobody owns — so the queued restart
    /// finds its session gone and does nothing at all.
    ///
    /// The control queue is held inside the *first* attach, which is what keeps the restart queued
    /// while the stop is arranged. (Nothing above this layer would ask for a stop during a start
    /// like this and get away with it — `RecorderSession` parks that one — but the recorder alone
    /// still has to end in a sane place.)
    func testARestartAskedForBeforeAStopNeverReattaches() async throws {
        let bridge = try await makeBridge()
        let input = FakeAudioInput()
        let failures = Failures()
        let recorder = SegmentedRecorder(core: bridge.core, segmentSec: 5, input: input) { failures.record($0) }

        let entered = expectation(description: "the first attach is on the control queue")
        let gate = DispatchSemaphore(value: 0)
        input.gateNextStart { entered.fulfill(); gate.wait() }
        let starting = Task { try await recorder.start(workflowId: nil, title: nil) }
        await fulfillment(of: [entered], timeout: 5)

        input.format = Self.otherFormat
        input.onConfigurationChange?("input_device_change")
        let stopping = Task { await recorder.stop(title: nil) }
        try await Task.sleep(nanoseconds: 100_000_000)
        gate.signal()
        _ = try await starting.value
        _ = await stopping.value

        XCTAssertEqual(input.starts, 1, "the queued restart found its session gone and never reattached")
        XCTAssertFalse(input.isRunning)
        XCTAssertFalse(input.tapped)
    }

    // MARK: - The live strip

    /// docs/09 화면 원칙 6: the strip the shells draw while a recording runs is read off the frames
    /// the writer took, so it says "this is being captured" about the file and not about a second
    /// tap on the microphone. And it belongs to the session: once the recording is filed there is
    /// nothing to draw, which is what lets a shell make "while recording" the whole condition.
    func testTheLiveStripFollowsTheRecordingAndEndsWithIt() async throws {
        let bridge = try await makeBridge()
        let input = FakeAudioInput()
        let failures = Failures()
        let recorder = SegmentedRecorder(core: bridge.core, segmentSec: 5, input: input) { failures.record($0) }

        XCTAssertEqual(recorder.livePeaks(), [], "there is nothing to draw before a recording")
        _ = try await recorder.start(workflowId: nil, title: nil)
        // A fifth of a second: two finished windows, whatever the buffer sizes fall out as.
        XCTAssertTrue(input.push(frames: SegmentedRecorder.sampleRateHz / 5) { Self.sample($0) })

        let peaks = recorder.livePeaks()
        XCTAssertGreaterThanOrEqual(peaks.count, 2, "0.2 s of audio is two 0.1 s windows")
        XCTAssertGreaterThan(peaks[0], 0, "a tone was recorded and the strip drew silence")

        let result = await recorder.stop(title: nil)
        guard case .finalized = result else {
            return XCTFail("expected a finalized stop, got \(result) (errors: \(failures.all))")
        }
        XCTAssertEqual(recorder.livePeaks(), [], "the strip outlived the recording")
    }

    // MARK: - The signal

    /// Seven seconds at 16 kHz, with the five-second boundary inside one of the pushed buffers.
    private static let totalFrames = 112_000
    private static let boundaryFrame = 5 * SegmentedRecorder.sampleRateHz
    /// The frames at either end of the run where the encoder's own edges live.
    private static let edge = 400

    /// 250 Hz at half scale. Low enough that AAC at 32 kbps carries it almost exactly (measured:
    /// worst sample error 0.014 over the interior), and high enough that being one frame out of step
    /// is four times that — so "continuous across the seam" is a claim with teeth. Well under the
    /// 8 kHz the 16 kHz file can hold, so resampling it from 48 kHz is not supposed to change it
    /// either: the same `sample(n)` is expected out whatever rate went in.
    private static func tone(_ frame: Int, rateHz: Double) -> Float {
        Float(0.5 * sin(2 * .pi * 250 * Double(frame) / rateHz))
    }

    /// The tone as the 16 kHz file should carry it.
    private static func sample(_ frame: Int) -> Float {
        tone(frame, rateHz: Double(SegmentedRecorder.sampleRateHz))
    }

    /// A rate and channel count the session was not attached on, so a restart is not declined as a
    /// duplicate notification.
    private static var otherFormat: AVAudioFormat {
        AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 48_000, channels: 2, interleaved: false)!
    }

    /// A boundary's registration runs off the audio thread, so what it leaves behind arrives a
    /// moment after the buffer that closed the segment. Bounded, and the assertion that follows is
    /// what fails if it never comes.
    private func waitUntil(_ ready: () -> Bool) async {
        for _ in 0 ..< 100 where !ready() {
            try? await Task.sleep(nanoseconds: 20_000_000)
        }
    }

    private func decode(_ url: URL) throws -> [Float] {
        let file = try AVAudioFile(forReading: url)
        let buffer = try XCTUnwrap(
            AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: AVAudioFrameCount(file.length))
        )
        try file.read(into: buffer)
        let samples = try XCTUnwrap(buffer.floatChannelData)[0]
        return (0 ..< Int(buffer.frameLength)).map { samples[$0] }
    }

    /// Computed here rather than with the core's `PartHasher`, which is what wrote the value being
    /// checked — two implementations agreeing is the point.
    private func sha256(of url: URL) throws -> String {
        SHA256.hash(data: try Data(contentsOf: url)).map { String(format: "%02x", $0) }.joined()
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

/// The recorder reports capture failures on the main queue, and every test here wants to be able to
/// say "and nothing went wrong" from whatever thread it happens to be on.
final class Failures {
    private let lock = NSLock()
    private var errors: [RecorderError] = []

    func record(_ error: RecorderError) {
        lock.withLock { errors.append(error) }
    }

    var all: [RecorderError] { lock.withLock { errors } }
    var fatal: [RecorderError] { all.filter(\.fatal) }
}
