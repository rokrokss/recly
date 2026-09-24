#if os(macOS) || os(iOS)
import AVFoundation
import XCTest
@testable import RecKit

final class SpeechFileReaderTests: XCTestCase {
    func testConversionDrainsFinalFramesAndPreservesResumeTime() async throws {
        guard #available(macOS 26, iOS 26, *) else { throw XCTSkip("Requires SpeechAnalyzer input types") }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".caf")
        defer { try? FileManager.default.removeItem(at: url) }
        let source = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 1))
        let target = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: 16_000, channels: 1))
        let pcm = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: source, frameCapacity: 44_100))
        pcm.frameLength = pcm.frameCapacity
        for frame in 0..<Int(pcm.frameLength) { pcm.floatChannelData![0][frame] = 0 }
        do {
            let writer = try AVAudioFile(forWriting: url, settings: source.settings)
            try writer.write(from: pcm)
        }
        for offset in [0.0, 0.5] {
            let reader = try SpeechFileReader(file: AVAudioFile(forReading: url), format: target, startTime: offset)
            var frames = 0
            var previousEnd = offset
            while let input = try await reader.next() {
                XCTAssertEqual(input.bufferStartTime!.seconds, previousEnd, accuracy: 0.0001)
                XCTAssertLessThanOrEqual(input.buffer.frameLength, 16_384)
                frames += Int(input.buffer.frameLength)
                previousEnd += Double(input.buffer.frameLength) / target.sampleRate
            }
            XCTAssertEqual(Double(frames), (1 - offset) * target.sampleRate, accuracy: 1)
            XCTAssertEqual(previousEnd, 1, accuracy: 0.0001)
        }
    }
}
#endif
