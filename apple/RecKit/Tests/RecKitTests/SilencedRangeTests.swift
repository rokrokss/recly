import AVFoundation
import RecKitTestSupport
import ReclyCore
import XCTest
@testable import RecKit

/// docs/03 `silenced`: an interruption — a call, Siri — takes the microphone away and gives it
/// back, and the meta has to say where in the recording that happened and for how long. The
/// arithmetic is `SilenceMonitor`'s; the path from the input's report to the finalized meta is the
/// recorder's. (The Android `SilenceMonitorTest` asks the same questions of the same contract.)
final class SilencedRangeTests: XCTestCase {
    private var dataDirectory: URL!

    override func setUpWithError() throws {
        dataDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("RecKitTests-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dataDirectory)
    }

    // MARK: - The arithmetic

    /// The recording's position does not move while the engine is stopped, so the range starts
    /// where the audio stopped and is as long as the interruption was on the wall clock.
    func testTheRangeIsPlacedInTheFileAndStretchedByTheWallClock() {
        var monitor = SilenceMonitor()
        monitor.set(true, positionSec: 120, uptimeSec: 1_000)
        monitor.set(false, positionSec: 120, uptimeSec: 1_005.5)

        XCTAssertEqual(monitor.ranges.count, 1)
        XCTAssertEqual(monitor.ranges[0].startSec, 120, accuracy: 0.001)
        XCTAssertEqual(monitor.ranges[0].endSec, 125.5, accuracy: 0.001)
        XCTAssertEqual(monitor.ranges[0].reason, SilenceMonitor.reason)
    }

    /// Only transitions count. A second `.began` inside an interruption already open would
    /// otherwise move its start forward and lose the beginning of it.
    func testARepeatedBeganDoesNotMoveTheOpenRange() {
        var monitor = SilenceMonitor()
        monitor.set(true, positionSec: 10, uptimeSec: 100)
        monitor.set(true, positionSec: 10, uptimeSec: 103)
        monitor.set(false, positionSec: 10, uptimeSec: 104)

        XCTAssertEqual(monitor.ranges.map(\.startSec), [10])
        XCTAssertEqual(monitor.ranges[0].endSec, 14, accuracy: 0.001)
    }

    /// A call that Siri interrupted arrives as two interruptions with no audio in between: one
    /// hole, not two — the second starts where the first ended.
    func testAFlapAtTheSamePositionStaysOneRange() {
        var monitor = SilenceMonitor()
        monitor.set(true, positionSec: 30, uptimeSec: 100)
        monitor.set(false, positionSec: 30, uptimeSec: 102)
        monitor.set(true, positionSec: 30, uptimeSec: 102)
        monitor.set(false, positionSec: 30, uptimeSec: 105)

        XCTAssertEqual(monitor.ranges.count, 1, "\(monitor.ranges)")
        XCTAssertEqual(monitor.ranges[0].startSec, 30, accuracy: 0.001)
        XCTAssertEqual(monitor.ranges[0].endSec, 35, accuracy: 0.001)
    }

    /// Audio recorded between two interruptions moves the position, and that is what makes them
    /// two ranges rather than one long one.
    func testTwoInterruptionsWithAudioBetweenThemAreTwoRanges() {
        var monitor = SilenceMonitor()
        monitor.set(true, positionSec: 10, uptimeSec: 100)
        monitor.set(false, positionSec: 10, uptimeSec: 101)
        monitor.set(true, positionSec: 40, uptimeSec: 131)
        monitor.set(false, positionSec: 40, uptimeSec: 133)

        XCTAssertEqual(monitor.ranges.map(\.startSec), [10, 40])
        XCTAssertEqual(monitor.ranges.map(\.endSec), [11, 42])
    }

    /// The interruption lasted longer on the wall clock than the audio that follows it is long, so
    /// the first range's `endSec` reaches past where the second one starts. They are still two
    /// holes: frames were written in between, and that — not the ranges overlapping — is what makes
    /// them separate (Sol M5-L2 review).
    func testARangeThatOverlapsTheNextOneIsStillTwoRangesWhenAudioWasWritten() {
        var monitor = SilenceMonitor()
        monitor.set(true, positionSec: 10, uptimeSec: 100)
        monitor.set(false, positionSec: 10, uptimeSec: 110)
        // A second of audio: the recording's position moves, the wall clock moves with it.
        monitor.set(true, positionSec: 11, uptimeSec: 111)
        monitor.set(false, positionSec: 11, uptimeSec: 113)

        XCTAssertEqual(monitor.ranges.count, 2, "\(monitor.ranges)")
        XCTAssertEqual(monitor.ranges.map(\.startSec), [10, 11])
        XCTAssertEqual(monitor.ranges.map(\.endSec), [20, 13])
    }

    /// An interruption that never ended — the user stopped the recording during the call. Closing
    /// it at the stop is the only thing that puts it in the meta at all.
    func testAnInterruptionStillOpenIsClosedAtTheStop() {
        var monitor = SilenceMonitor()
        monitor.set(true, positionSec: 60, uptimeSec: 500)
        monitor.close(positionSec: 60, uptimeSec: 507)

        XCTAssertEqual(monitor.ranges.count, 1)
        XCTAssertEqual(monitor.ranges[0].endSec, 67, accuracy: 0.001)
    }

    /// Nothing to write when the microphone was ours all along, and `finalize` treats an empty list
    /// as "keep what the meta has" — so a range of no length must not be invented.
    func testNothingIsWrittenWhenTheMicrophoneWasNeverTaken() {
        var monitor = SilenceMonitor()
        monitor.close(positionSec: 10, uptimeSec: 100)
        monitor.set(true, positionSec: 10, uptimeSec: 100)
        monitor.set(false, positionSec: 10, uptimeSec: 100)

        XCTAssertTrue(monitor.ranges.isEmpty)
    }

    // MARK: - The path into the meta

    /// The whole way through, with the recorder's real path in the middle: the input reports the
    /// interruption, the recording carries on afterwards, and the finalized `meta.json` carries the
    /// range at the position the audio had reached when the microphone went.
    func testAnInterruptionReachesTheFinalizedMeta() async throws {
        let bridge = try await makeBridge()
        let input = FakeAudioInput()
        let failures = Failures()
        let recorder = SegmentedRecorder(
            core: bridge.core, segmentSec: 60, source: Source.phone, input: input
        ) { failures.record($0) }

        let recordingId = try await recorder.start(workflowId: nil, title: nil)
        // Two seconds of audio, then the call: the engine stops, so nothing more is pushed until
        // the interruption ends.
        XCTAssertTrue(input.push(frames: 2 * SegmentedRecorder.sampleRateHz) { _ in 0.1 })
        input.onSilence?(true)
        let position = recorder.recordedSec
        input.onSilence?(false)
        XCTAssertTrue(input.push(frames: SegmentedRecorder.sampleRateHz) { _ in 0.1 })
        let result = await recorder.stop(title: nil)

        guard case .finalized = result else {
            return XCTFail("expected a finalized stop, got \(result) (errors: \(failures.all))")
        }
        let row = try await bridge.core.recordings.get(id: recordingId)
        let record = try XCTUnwrap(row)
        XCTAssertEqual(record.meta.silenced.count, 1, "the interruption is in the meta")
        let silenced = try XCTUnwrap(record.meta.silenced.first)
        XCTAssertEqual(silenced.reason, SilenceMonitor.reason)
        XCTAssertEqual(silenced.startSec, position, accuracy: 0.05, "at the point the audio stopped")
        XCTAssertGreaterThanOrEqual(silenced.endSec, silenced.startSec)
        // The audio itself is untouched: three seconds went in, three seconds came out, and the
        // interruption is a note about the recording rather than a hole in it.
        XCTAssertEqual(recorder.recordedSec, 0, "the recording is over")
        XCTAssertEqual(record.meta.durationSec?.doubleValue ?? 0, 3, accuracy: 0.2)
        XCTAssertEqual(failures.fatal.count, 0, "\(failures.all)")
    }

    /// docs/03: `silenced` and `gaps` are different claims, and a recording can carry both — the
    /// interruption the input covered by itself, and the restart a route change asked for.
    func testSilencedAndGapsAreWrittenSideBySide() async throws {
        let bridge = try await makeBridge()
        let input = FakeAudioInput()
        let failures = Failures()
        let recorder = SegmentedRecorder(
            core: bridge.core, segmentSec: 60, source: Source.phone, input: input
        ) { failures.record($0) }

        let recordingId = try await recorder.start(workflowId: nil, title: nil)
        XCTAssertTrue(input.push(frames: SegmentedRecorder.sampleRateHz) { _ in 0.1 })
        input.onSilence?(true)
        input.onSilence?(false)
        // The resume that did not come back: `IOSAudioInput` reports it as a configuration change,
        // which is the restart path — and that is what writes the `gaps` entry.
        input.format = FakeAudioInput.format(48_000)
        input.onConfigurationChange?("interruption_resume_failed")
        // The restart runs on the recorder's control queue: a stop that beat it there would take
        // the session with it and the restart would find nothing to bring back.
        try await until("the input was tapped again") { input.starts == 2 }
        let result = await recorder.stop(title: nil)

        guard case .finalized = result else {
            return XCTFail("expected a finalized stop, got \(result) (errors: \(failures.all))")
        }
        let row = try await bridge.core.recordings.get(id: recordingId)
        let record = try XCTUnwrap(row)
        XCTAssertEqual(record.meta.silenced.count, 1)
        XCTAssertEqual(record.meta.gaps.map(\.reason), ["interruption_resume_failed"])
        XCTAssertEqual(failures.fatal.count, 0, "\(failures.all)")
    }

    /// Waits for something the recorder does on a queue of its own.
    private func until(
        _ what: String,
        timeoutSec: Double = 5,
        _ done: () -> Bool
    ) async throws {
        let deadline = Date().addingTimeInterval(timeoutSec)
        while !done() {
            if Date() > deadline { return XCTFail("timed out waiting until \(what)") }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
    }

    private func makeBridge() async throws -> CoreBridge {
        try await CoreBridge.make(
            appVersion: "0.0.0-test",
            deviceName: "RecKitTests",
            dataDirectory: dataDirectory,
            databaseName: "reckit-silence-\(UUID().uuidString).db",
            secureStore: InMemorySecureStore()
        )
    }
}
