import AVFoundation
import RecKitTestSupport
import ReclyCore
import XCTest
@testable import RecKit

/// The one thing the rest of the suite cannot check: that the microphone, the converter, the AAC
/// encoder and the boundary all work when they are wired to real hardware.
///
/// Skipped unless `REC_MIC_TEST=1`, because it opens the microphone and takes twelve seconds.
/// `xcodebuild` gives the test process a fresh environment, so the variable has to be handed over
/// under the `TEST_RUNNER_` prefix — it arrives inside without it:
///
/// ```
/// TEST_RUNNER_REC_MIC_TEST=1 xcodebuild -workspace apple/Rec.xcworkspace -scheme RecKit \
///   -destination 'platform=macOS' -only-testing:RecKitTests/MicrophoneSmokeTests test
/// ```
///
/// The first run puts a microphone prompt on screen, attributed to Xcode (the test runner is
/// `com.apple.xctest`), and blocks until somebody answers it.
///
/// It records at `segmentSec: 5`, so a twelve-second run crosses two real boundaries — the same
/// code path as the 900-second one, at a length a person can wait out.
final class MicrophoneSmokeTests: XCTestCase {
    private var dataDirectory: URL!

    override func setUpWithError() throws {
        try XCTSkipUnless(
            ProcessInfo.processInfo.environment["REC_MIC_TEST"] == "1",
            "set REC_MIC_TEST=1 to run the microphone smoke test"
        )
        dataDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("RecKitMic-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDownWithError() throws {
        guard let dataDirectory else { return }
        print("microphone smoke test kept its output at \(dataDirectory.path)")
    }

    func testARealRecordingSegmentsHashesAndFinalizes() async throws {
        let status = AVCaptureDevice.authorizationStatus(for: .audio)
        try XCTSkipIf(
            status == .denied || status == .restricted,
            "microphone access is \(status.rawValue): grant it to the test runner and re-run"
        )

        let bridge = try await CoreBridge.make(
            appVersion: "0.0.0-mic",
            deviceName: "RecKitMicTest",
            dataDirectory: dataDirectory,
            databaseName: "mic.db",
            secureStore: InMemorySecureStore()
        )
        var failures: [RecorderError] = []
        let recorder = SegmentedRecorder(core: bridge.core, segmentSec: 5) { failures.append($0) }

        let recordingId = try await recorder.start(workflowId: nil, title: nil)
        try await Task.sleep(nanoseconds: 12_000_000_000)
        XCTAssertGreaterThan(recorder.recordedSec, 11, "the tap delivered nothing")
        let result = await recorder.stop(title: "마이크 스모크")

        guard case .finalized(let outcome) = result else {
            return XCTFail("expected a finalized stop, got \(result) (errors: \(failures))")
        }
        XCTAssertEqual(outcome.recordingId, recordingId)
        XCTAssertEqual(failures.filter(\.fatal).count, 0, "\(failures)")

        let row = try await bridge.core.recordings.get(id: recordingId)
        let record = try XCTUnwrap(row)
        let directory = record.dir.url
        XCTAssertEqual(record.meta.status, RecordingStatus.finalized)
        XCTAssertEqual(record.meta.title, "마이크 스모크")
        XCTAssertEqual(record.meta.audio.sampleRateHz, 16_000)
        // Twelve seconds at five to a segment: two full parts and the tail.
        XCTAssertEqual(record.meta.parts.count, 3)

        // The parts are decoded rather than believed. `durationSec` is what the rollover wrote down,
        // and a rollover that dropped audio would write down the same number either way — so the
        // number that counts is the one the container gives back when it is read.
        var offset = 0.0
        var totalFrames = 0
        for part in record.meta.parts {
            let url = directory.appendingPathComponent(part.file)
            XCTAssertTrue(FileManager.default.fileExists(atPath: url.path), part.file)
            XCTAssertGreaterThan(part.bytes, 0, part.file)
            XCTAssertEqual(part.sha256.count, 64, part.file)
            XCTAssertEqual(part.startOffsetSec, offset, accuracy: 0.001, part.file)
            let frames = try decodedFrames(of: url)
            XCTAssertEqual(
                Double(frames) / Double(SegmentedRecorder.sampleRateHz), part.durationSec, accuracy: 0.001,
                "\(part.file) does not hold the audio the meta says it does"
            )
            totalFrames += frames
            offset += part.durationSec
        }
        // The boundary is cut on the frame, so each closed segment holds exactly five seconds of
        // decodable audio — this is the assertion a lossy rollover fails.
        XCTAssertEqual(try decodedFrames(of: directory.appendingPathComponent(record.meta.parts[0].file)),
                       5 * SegmentedRecorder.sampleRateHz)
        XCTAssertEqual(try decodedFrames(of: directory.appendingPathComponent(record.meta.parts[1].file)),
                       5 * SegmentedRecorder.sampleRateHz)
        print("microphone smoke test: \(totalFrames) frames decoded across \(record.meta.parts.count) parts")
        XCTAssertEqual(Double(totalFrames) / Double(SegmentedRecorder.sampleRateHz), 12, accuracy: 0.5)
        XCTAssertEqual(outcome.durationSec, 12, accuracy: 0.5)

        // And there is actual audio in it, not a chain of well-formed silence: an encoder that was
        // handed nothing would decode back to exact zeroes.
        let peak = try peakAmplitude(of: directory.appendingPathComponent(record.meta.parts[0].file))
        print("microphone smoke test: peak amplitude \(peak) in \(record.meta.parts[0].file)")
        XCTAssertGreaterThan(peak, 0.0001, "the microphone delivered silence")
    }

    private func decoded(_ url: URL) throws -> AVAudioPCMBuffer {
        let file = try AVAudioFile(forReading: url)
        let buffer = try XCTUnwrap(
            AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: AVAudioFrameCount(file.length))
        )
        try file.read(into: buffer)
        return buffer
    }

    private func decodedFrames(of url: URL) throws -> Int {
        Int(try decoded(url).frameLength)
    }

    private func peakAmplitude(of url: URL) throws -> Float {
        let buffer = try decoded(url)
        let samples = try XCTUnwrap(buffer.floatChannelData)[0]
        return (0 ..< Int(buffer.frameLength)).reduce(Float(0)) { max($0, abs(samples[$1])) }
    }
}
