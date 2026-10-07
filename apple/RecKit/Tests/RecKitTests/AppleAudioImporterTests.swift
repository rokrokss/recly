import AVFoundation
import ReclyCore
import XCTest
@testable import RecKit

/// docs/03 "Naming rules": an imported file becomes ADR-006 parts — AAC at 16 kHz mono, cut every
/// `segmentSec` on the sample — and a file that is not audio, or not there, says which.
final class AppleAudioImporterTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("RecKitTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    func testAFileIsCutIntoPartsOfTheSegmentLength() async throws {
        let source = try tone(seconds: 2.5, sampleRate: 44_100)
        let out = directory.appendingPathComponent("out", isDirectory: true)
        try FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)

        let result = try await AppleAudioImporter().__transcode(sourcePath: source.path, outDir: out.path, segmentSec: 1)

        let done = try XCTUnwrap(result as? TranscodeResultDone)
        XCTAssertEqual(done.parts.count, 3)
        XCTAssertEqual(done.parts.map(\.durationSec).reduce(0, +), 2.5, accuracy: 0.01)
        XCTAssertEqual(done.parts[0].durationSec, 1, accuracy: 0.001)
        for part in done.parts {
            let tracks = try await AVURLAsset(url: out.appendingPathComponent(part.file)).loadTracks(withMediaType: .audio)
            let formats = try await XCTUnwrap(tracks.first).load(.formatDescriptions)
            let format = try XCTUnwrap(formats.first)
            XCTAssertEqual(CMFormatDescriptionGetMediaSubType(format), kAudioFormatMPEG4AAC)
            let asbd = try XCTUnwrap(CMAudioFormatDescriptionGetStreamBasicDescription(format))
            XCTAssertEqual(asbd.pointee.mSampleRate, 16_000)
            XCTAssertEqual(asbd.pointee.mChannelsPerFrame, 1)
        }
    }

    func testATextFileIsNotSupported() async throws {
        let text = directory.appendingPathComponent("notes.txt")
        try Data("not audio".utf8).write(to: text)
        let result = try await AppleAudioImporter().__transcode(sourcePath: text.path, outDir: directory.path, segmentSec: 900)
        XCTAssertTrue(result is TranscodeResultUnsupported)
    }

    func testAMissingFileIsUnreadable() async throws {
        let result = try await AppleAudioImporter().__transcode(
            sourcePath: directory.appendingPathComponent("gone.m4a").path, outDir: directory.path, segmentSec: 900
        )
        XCTAssertTrue(result is TranscodeResultUnreadable)
    }

    private func tone(seconds: Double, sampleRate: Double) throws -> URL {
        let url = directory.appendingPathComponent("tone.wav")
        let format = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 2)!
        let file = try AVAudioFile(forWriting: url, settings: [
            AVFormatIDKey: kAudioFormatLinearPCM, AVSampleRateKey: sampleRate, AVNumberOfChannelsKey: 2,
            AVLinearPCMBitDepthKey: 16, AVLinearPCMIsFloatKey: false,
        ])
        let frames = AVAudioFrameCount(seconds * sampleRate)
        let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames)!
        buffer.frameLength = frames
        for channel in 0 ..< 2 {
            for index in 0 ..< Int(frames) {
                buffer.floatChannelData![channel][index] = 0.3 * sin(2 * .pi * 440 * Float(index) / Float(sampleRate))
            }
        }
        try file.write(from: buffer)
        return url
    }
}
