import AVFoundation
import RecKitTestSupport
import ReclyCore
import XCTest
@testable import RecKit

/// M4-L3's three tracks through the real recorder, with a fake microphone and a fake tap in front of
/// it: the converter, the AAC encoder, the shared boundary, the hash and `addPart`, on real files.
/// Everything except Core Audio itself, which `ProcessTapCapture` owns and which no automated run
/// can have without the "시스템 오디오 녹음" prompt.
final class MeetingRecorderTests: XCTestCase {
    private var dataDirectory: URL!

    override func setUpWithError() throws {
        dataDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("RecKitTests-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dataDirectory)
    }

    /// Deliverable 7, the boundary half: three tracks, one set of part numbers, and the same
    /// `startOffsetSec` for every track of a part. The microphone is the clock, so the only way this
    /// can hold is if all three files are cut at the same frame — a track that decided for itself
    /// when its segment was full would be a part number out within the hour.
    func testTheThreeTracksShareThePartNumbersAndTheOffsets() async throws {
        let bridge = try await makeBridge()
        let mic = FakeAudioInput()
        let system = FakeSystemAudioInput()
        let failures = Failures()
        let recorder = SegmentedRecorder(
            core: bridge.core, segmentSec: 5, input: mic, systemInput: system
        ) { failures.record($0) }

        let recordingId = try await recorder.start(workflowId: nil, title: nil, mode: .meeting)
        XCTAssertEqual(system.starts, 1, "meeting mode opens the tap")
        push(seconds: 7, mic: mic, system: system, micSample: Self.micTone, systemSample: Self.systemTone)
        let result = await recorder.stop(title: nil)

        guard case .finalized(let outcome) = result else {
            return XCTFail("expected a finalized stop, got \(result) (errors: \(failures.all))")
        }
        XCTAssertEqual(failures.fatal.count, 0, "\(failures.all)")
        XCTAssertEqual(outcome.parts, 6, "two parts on each of three tracks")
        XCTAssertFalse(system.isRunning, "a tap left running is system audio still being captured")

        let row = try await bridge.core.recordings.get(id: recordingId)
        let record = try XCTUnwrap(row)
        XCTAssertEqual(record.meta.tracks, [Track.mic, Track.sys, Track.mix])
        XCTAssertEqual(record.meta.parts.map(\.part), [1, 1, 1, 2, 2, 2])
        XCTAssertEqual(
            record.meta.parts.map(\.track),
            [Track.mic, Track.sys, Track.mix, Track.mic, Track.sys, Track.mix]
        )

        for part in [1, 2] {
            let slice = record.meta.parts.filter { $0.part == Int32(part) }
            XCTAssertEqual(slice.count, 3)
            XCTAssertEqual(Set(slice.map(\.startOffsetSec)).count, 1, "part \(part) starts at one offset")
            XCTAssertEqual(Set(slice.map(\.durationSec)).count, 1, "and lasts one length")
            XCTAssertEqual(Set(slice.map(\.file)).count, 3, "three files, one per track")
        }
        XCTAssertEqual(record.meta.parts.map(\.startOffsetSec), [0, 0, 0, 5, 5, 5])

        // The frames themselves, not the numbers written about them: the closed segment of every
        // track is five seconds to the frame, which is what "같은 세그먼트 경계" means on disk.
        for part in record.meta.parts where part.part == 1 {
            let frames = try decode(record.dir.url.appendingPathComponent(part.file)).count
            XCTAssertEqual(frames, 5 * SegmentedRecorder.sampleRateHz, "\(part.file)")
        }
    }

    /// Deliverable 7, the mix half: `mix` is the two tracks summed at half scale (docs/12 "−6 dB
    /// 헤드룸"). Checked against the tracks as they came back off disk rather than against the tones
    /// that were pushed in, because that is the claim — whatever `mic` and `sys` ended up holding,
    /// `mix` is their average — and it is the claim that survives the encoder's own error.
    func testTheMixIsTheTwoTracksSummedWithHeadroom() async throws {
        let bridge = try await makeBridge()
        let mic = FakeAudioInput()
        let system = FakeSystemAudioInput()
        let failures = Failures()
        let recorder = SegmentedRecorder(
            core: bridge.core, segmentSec: 900, input: mic, systemInput: system
        ) { failures.record($0) }

        let recordingId = try await recorder.start(workflowId: nil, title: nil, mode: .meeting)
        // Both sides loud: summing without the headroom would clip, and the clipping is what the
        // last assertion looks for.
        push(seconds: 3, mic: mic, system: system, micSample: Self.loudMicTone, systemSample: Self.loudSystemTone)
        _ = await recorder.stop(title: nil)

        let row = try await bridge.core.recordings.get(id: recordingId)
        let record = try XCTUnwrap(row)
        XCTAssertEqual(failures.fatal.count, 0, "\(failures.all)")
        let byTrack = Dictionary(record.meta.parts.map { ($0.track, $0.file) }, uniquingKeysWith: { first, _ in first })
        let micTrack = try decode(record.dir.url.appendingPathComponent(try XCTUnwrap(byTrack[Track.mic])))
        let sysTrack = try decode(record.dir.url.appendingPathComponent(try XCTUnwrap(byTrack[Track.sys])))
        let mixTrack = try decode(record.dir.url.appendingPathComponent(try XCTUnwrap(byTrack[Track.mix])))

        XCTAssertEqual(micTrack.count, sysTrack.count)
        XCTAssertEqual(micTrack.count, mixTrack.count, "one boundary means one length")

        // The encoder's own edges at either end; the interior is what the sum is about.
        let interior = Self.edge ..< (mixTrack.count - Self.edge)
        let worst = interior.reduce(Float(0)) { max($0, abs(mixTrack[$1] - (micTrack[$1] + sysTrack[$1]) * 0.5)) }
        // Three independent AAC encodes at 32 kbps, so the comparison carries three encoders' worth
        // of error, and the encoder is not bit-identical run to run (measured: 0.053 and 0.070 worst
        // over the interior on two runs of the same input). The threshold is what that costs; the
        // assertion below is what says it still means something.
        XCTAssertLessThan(worst, 0.12, "mix is (mic + sys) × 0.5, sample for sample")
        let ifItWereMicAlone = interior.reduce(Float(0)) { max($0, abs(mixTrack[$1] - micTrack[$1])) }
        XCTAssertGreaterThan(
            ifItWereMicAlone, 0.4,
            "a `mix` that was only the microphone would be 0.92 out — so 0.12 is a real bar"
        )

        let loudest = interior.reduce(Float(0)) { max($0, abs(mixTrack[$1])) }
        XCTAssertLessThan(loudest, 1, "the headroom is what keeps two loud streams from clipping")
        XCTAssertGreaterThan(loudest, 0.3, "and there really was something loud in both of them")
        // The system track is the other side of the call and nothing else: if the mix were being
        // written into `sys` this would be the average instead of the tone that was pushed in.
        let systemPeak = interior.reduce(Float(0)) { max($0, abs(sysTrack[$1])) }
        XCTAssertGreaterThan(systemPeak, 0.7, "the system track carries the system tone at full size")
    }

    /// The arithmetic on its own, where the encoder cannot round anything: two streams at nine
    /// tenths sum to nine tenths, not to one and eight tenths.
    func testTheMixSumsAtHalfScale() throws {
        let format = FakeAudioInput.recorderFormat
        let mic = try XCTUnwrap(buffer(of: [0.9, -0.9, 0.4, 0], in: format))
        let system = try XCTUnwrap(buffer(of: [0.9, 0.9, -0.2, 1], in: format))

        let mixed = try XCTUnwrap(SegmentedRecorder.mix(mic, system))

        let samples = try XCTUnwrap(mixed.floatChannelData)
        XCTAssertEqual(mixed.frameLength, 4)
        XCTAssertEqual(samples[0][0], 0.9, accuracy: 1e-6, "two loud streams stay inside full scale")
        XCTAssertEqual(samples[0][1], 0, accuracy: 1e-6)
        XCTAssertEqual(samples[0][2], 0.1, accuracy: 1e-6)
        XCTAssertEqual(samples[0][3], 0.5, accuracy: 1e-6)
    }

    /// The microphone alone is still M4-L2's recording, unchanged: one `mono` track, one file per
    /// part, and no tap opened at all. Recording the room when the user asked for the microphone
    /// would be the worst kind of bug this lane could introduce.
    func testMicrophoneModeOpensNoTapAndStillWritesOneMonoTrack() async throws {
        let bridge = try await makeBridge()
        let mic = FakeAudioInput()
        let system = FakeSystemAudioInput()
        let failures = Failures()
        let recorder = SegmentedRecorder(
            core: bridge.core, segmentSec: 5, input: mic, systemInput: system
        ) { failures.record($0) }

        let recordingId = try await recorder.start(workflowId: nil, title: nil, mode: .microphone)
        XCTAssertTrue(mic.push(frames: 112_000) { Self.micTone($0) })
        _ = await recorder.stop(title: nil)

        XCTAssertEqual(system.starts, 0, "no tap, no system audio, no permission prompt")
        let row = try await bridge.core.recordings.get(id: recordingId)
        let record = try XCTUnwrap(row)
        XCTAssertEqual(record.meta.tracks, [Track.mono])
        XCTAssertEqual(record.meta.parts.map(\.part), [1, 2])
        XCTAssertEqual(record.meta.parts.map(\.track), [Track.mono, Track.mono])
    }

    /// docs/12 deliverable 1: a tap that cannot be built is its own kind of error, and the recording
    /// is not left half-made — the shell answers it with the permission deep link and the offer to
    /// record the microphone alone, and neither a row nor a directory may be waiting for it.
    func testAMeetingWhoseTapIsRefusedLeavesNothingBehind() async throws {
        let bridge = try await makeBridge()
        let mic = FakeAudioInput()
        let system = FakeSystemAudioInput()
        system.failure = RecorderError("denied", kind: .systemAudioUnavailable)
        let recorder = SegmentedRecorder(
            core: bridge.core, segmentSec: 5, input: mic, systemInput: system
        ) { _ in }

        do {
            _ = try await recorder.start(workflowId: nil, title: nil, mode: .meeting)
            XCTFail("expected the refused tap to be thrown")
        } catch let error as RecorderError {
            XCTAssertEqual(error.kind, .systemAudioUnavailable)
        }

        XCTAssertFalse(recorder.isRecording)
        XCTAssertFalse(mic.isRunning, "the microphone is given back too")
        let rows = try await bridge.core.recordings.list(limit: 10)
        XCTAssertEqual(rows.count, 0, "no row promising three tracks it never recorded")
    }

    /// docs/12 "tap 재생성": the tap rebuilds itself when the output device changes, and the
    /// microphone must not notice — the recording's timeline is the microphone's, and stopping it
    /// would cost the user audio that was never in danger. What the meta gets is the hole.
    func testATapThatRebuiltItselfIsAGapAndNotARestart() async throws {
        let bridge = try await makeBridge()
        let mic = FakeAudioInput()
        let system = FakeSystemAudioInput()
        let failures = Failures()
        let recorder = SegmentedRecorder(
            core: bridge.core, segmentSec: 900, input: mic, systemInput: system
        ) { failures.record($0) }

        let recordingId = try await recorder.start(workflowId: nil, title: nil, mode: .meeting)
        push(seconds: 1, mic: mic, system: system, micSample: Self.micTone, systemSample: Self.systemTone)
        system.onOutage?("output_device_change", 0.4)
        push(seconds: 1, mic: mic, system: system, micSample: Self.micTone, systemSample: Self.systemTone)
        _ = await recorder.stop(title: nil)

        XCTAssertEqual(mic.starts, 1, "the microphone was never restarted for the tap's sake")
        XCTAssertEqual(failures.fatal.count, 0, "\(failures.all)")
        let row = try await bridge.core.recordings.get(id: recordingId)
        let record = try XCTUnwrap(row)
        XCTAssertEqual(record.meta.gaps.count, 1)
        XCTAssertEqual(record.meta.gaps.first?.reason, "output_device_change")
        let gap = try XCTUnwrap(record.meta.gaps.first)
        XCTAssertEqual(gap.endSec - gap.startSec, 0.4, accuracy: 0.01)
        XCTAssertEqual(gap.endSec, 1, accuracy: 0.05, "the hole ends where the recording had got to")
    }

    /// The other end of the same rule: an outage that never closed. The tap was still failing to
    /// rebuild when the recording ended, so nothing reports it until the tap is told to stop — by
    /// which time the stop has already taken the session away. The hole happened and the meta has
    /// to say so, or the recording claims a `sys` track that was whole.
    func testAnOutageStillOpenWhenTheRecordingEndsIsStillAGap() async throws {
        let bridge = try await makeBridge()
        let mic = FakeAudioInput()
        let system = FakeSystemAudioInput()
        let failures = Failures()
        let recorder = SegmentedRecorder(
            core: bridge.core, segmentSec: 900, input: mic, systemInput: system
        ) { failures.record($0) }

        let recordingId = try await recorder.start(workflowId: nil, title: nil, mode: .meeting)
        push(seconds: 2, mic: mic, system: system, micSample: Self.micTone, systemSample: Self.systemTone)
        // Open when the recording ends, and reported the way the real tap reports it: from `stop`.
        system.outageAtStop = (reason: "system_tap_silent", seconds: 1.5)
        let result = await recorder.stop(title: nil)

        guard case .finalized = result else {
            return XCTFail("expected a finalized stop, got \(result) (errors: \(failures.all))")
        }
        let row = try await bridge.core.recordings.get(id: recordingId)
        let record = try XCTUnwrap(row)
        XCTAssertEqual(record.meta.gaps.count, 1, "the outage the tap was still in is still a hole")
        let gap = try XCTUnwrap(record.meta.gaps.first)
        XCTAssertEqual(gap.reason, "system_tap_silent")
        XCTAssertEqual(gap.endSec - gap.startSec, 1.5, accuracy: 0.01)
        XCTAssertEqual(gap.endSec, 2, accuracy: 0.05, "it ends where the recording had got to")
    }

    /// docs/12 "tap 재생성" has a mirror on the microphone's side: a device change stops the
    /// microphone for a moment and the tap keeps delivering, so the system frames that arrive
    /// meanwhile have no microphone frames under them. Measured across an interval they read as a
    /// system stream running fast, and a correction from that resamples the next minute of a track
    /// that was never late — so the restart re-anchors the estimator exactly as an outage does.
    ///
    /// The estimator's interval is zero here, so every microphone buffer closes one: it is what
    /// makes the interval *after* the restart a measurement of its own instead of one taken sixty
    /// seconds later. The arithmetic is the real thing's.
    func testAMicrophoneRestartIsNotMeasuredAsSystemDrift() async throws {
        let bridge = try await makeBridge()
        let mic = FakeAudioInput()
        let system = FakeSystemAudioInput()
        let failures = Failures()
        let recorder = SegmentedRecorder(
            core: bridge.core,
            segmentSec: 900,
            input: mic,
            systemInput: system,
            driftIntervalSec: 0
        ) { failures.record($0) }

        _ = try await recorder.start(workflowId: nil, title: nil, mode: .meeting)
        // Matched pairs: three tap frames under every microphone frame, which is what a 48 kHz tap
        // under a 16 kHz timeline is. Nothing drifts, so nothing is corrected.
        for _ in 0 ..< 4 { pair(mic: mic, system: system) }
        XCTAssertEqual(recorder.driftRatio ?? 0, 1, accuracy: 1e-12, "two streams in step need none")

        // The microphone goes away for a device change and the tap does not: 96 tap frames (2 ms,
        // 32 on the microphone's timeline) that no microphone frame was there for. Under the 1% the
        // estimator refuses outright, which is what makes it a false correction rather than a
        // rejected one.
        mic.stop()
        XCTAssertTrue(system.push(frames: 96) { _ in 0 })
        mic.onConfigurationChange?("input_device_change")
        pair(mic: mic, system: system)

        XCTAssertEqual(mic.starts, 2, "the microphone really was restarted")
        XCTAssertEqual(
            recorder.driftRatio ?? 0, 1, accuracy: 1e-12,
            "a microphone that paused is not a system stream running fast"
        )
        _ = await recorder.stop(title: nil)
        XCTAssertEqual(failures.fatal.count, 0, "\(failures.all)")
    }

    // MARK: - Driving the two streams

    /// One microphone buffer and the tap frames that belong under it, the tap first — the order a
    /// machine delivers them in, and the one that leaves the estimator a matched pair to read.
    private func pair(mic: FakeAudioInput, system: FakeSystemAudioInput) {
        XCTAssertTrue(system.push(frames: 4096 * 3) { _ in 0 }, "the tap went away mid-recording")
        XCTAssertTrue(mic.push(frames: 4096) { _ in 0 }, "the microphone went away mid-recording")
    }

    /// One second of both streams at a time, the tap first, in the order a machine delivers them:
    /// system audio arrives continuously and the microphone in buffers, so by the time a microphone
    /// buffer lands the frames that belong under it are already waiting.
    private func push(
        seconds: Int,
        mic: FakeAudioInput,
        system: FakeSystemAudioInput,
        micSample: (Int) -> Float,
        systemSample: (Int) -> Float
    ) {
        let micRate = SegmentedRecorder.sampleRateHz
        let systemRate = Int(system.format.sampleRate)
        for second in 0 ..< seconds {
            XCTAssertTrue(
                system.push(frames: systemRate) { systemSample(second * systemRate + $0) },
                "the tap went away mid-recording"
            )
            XCTAssertTrue(
                mic.push(frames: micRate) { micSample(second * micRate + $0) },
                "the microphone went away mid-recording"
            )
        }
    }

    /// The frames at either end of a run where the encoder's own edges live.
    private static let edge = 400

    /// 250 Hz at the microphone's rate, and 400 Hz at the tap's — two tones that are told apart by
    /// ear and by arithmetic, so a track carrying the wrong one is visible.
    private static func micTone(_ frame: Int) -> Float {
        Float(0.5 * sin(2 * .pi * 250 * Double(frame) / Double(SegmentedRecorder.sampleRateHz)))
    }

    private static func systemTone(_ frame: Int) -> Float {
        Float(0.5 * sin(2 * .pi * 400 * Double(frame) / 48_000))
    }

    private static func loudMicTone(_ frame: Int) -> Float {
        micTone(frame) * 1.8
    }

    private static func loudSystemTone(_ frame: Int) -> Float {
        systemTone(frame) * 1.8
    }

    private func buffer(of samples: [Float], in format: AVAudioFormat) -> AVAudioPCMBuffer? {
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(samples.count)),
              let data = buffer.floatChannelData
        else { return nil }
        for (index, sample) in samples.enumerated() { data[0][index] = sample }
        buffer.frameLength = AVAudioFrameCount(samples.count)
        return buffer
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

    private func makeBridge() async throws -> CoreBridge {
        try await CoreBridge.make(
            appVersion: "0.0.0-test",
            deviceName: "RecKitTests",
            dataDirectory: dataDirectory,
            databaseName: "reckit-tests-\(UUID().uuidString).db",
            logger: OSLogLogger(),
            secureStore: InMemorySecureStore()
        )
    }
}
