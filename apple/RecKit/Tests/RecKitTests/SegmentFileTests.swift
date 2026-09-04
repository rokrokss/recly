import AVFoundation
import ReclyCore
import XCTest
@testable import RecKit

/// The segment file itself, without a microphone: silence goes in through the same opener the
/// recorder uses, and what comes back has to be the container ADR-006 promises. The meta says
/// `aac-lc` / `m4a` / 16 kHz / mono / 32 kbps whether or not the platform agreed, so something has
/// to check that it did.
final class SegmentFileTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("RecKitTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    /// The processing format is what the tap's converter has to target and what `write(from:)`
    /// refuses to take anything else than, so it is part of the contract, not an implementation
    /// detail: 16 kHz, one channel, non-interleaved Float32.
    func testASegmentFileTakesTheFormatTheConverterProduces() throws {
        let file = try SegmentedRecorder.openSegmentFile(named: segment, in: directory)

        XCTAssertEqual(file.processingFormat.sampleRate, 16_000)
        XCTAssertEqual(file.processingFormat.channelCount, 1)
        XCTAssertEqual(file.processingFormat.commonFormat, .pcmFormatFloat32)
        XCTAssertFalse(file.processingFormat.isInterleaved)
        XCTAssertEqual(file.fileFormat.streamDescription.pointee.mFormatID, kAudioFormatMPEG4AAC)
    }

    /// A whole segment's life in miniature: open, write, release — and only the release writes the
    /// trailing MPEG-4 atoms, which is why the recorder drops the file before it hashes it. What
    /// comes back is the duration `PartReconciler` puts in the part, computed from the container
    /// rather than from the bitrate.
    func testAClosedSegmentReadsBackAtTheDurationItWasWritten() throws {
        var file: AVAudioFile? = try SegmentedRecorder.openSegmentFile(named: segment, in: directory)
        let format = try XCTUnwrap(file?.processingFormat)
        // Two seconds in the buffer sizes a tap actually delivers.
        for _ in 0 ..< 8 {
            try file?.write(from: try XCTUnwrap(silence(of: 4000, in: format)))
        }
        file = nil

        let url = directory.appendingPathComponent(segment)
        let bytes = try XCTUnwrap(
            (try FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.intValue
        )
        XCTAssertGreaterThan(bytes, 0, "a released AVAudioFile has flushed its container")

        let duration = try XCTUnwrap(PartReconciler.containerDurationSec(of: url))
        // AAC frames are 1024 samples, so the encoder rounds the tail up; anything near two seconds
        // means the audio went in and came back, which is what this is asking.
        XCTAssertEqual(duration, 2, accuracy: 0.1)
    }

    /// The fallback the reconciler leans on: a file the process died in the middle of is not a
    /// readable container, and guessing zero would put a zero-length part in the meta.
    func testAnUnreadableFileHasNoContainerDuration() throws {
        let url = directory.appendingPathComponent(segment)
        try Data(count: 4000).write(to: url)

        XCTAssertNil(PartReconciler.containerDurationSec(of: url))
    }

    private let segment = "20260826T010000Z_desktop_01J9ABCD_p001_mono.m4a"

    private func silence(of frames: AVAudioFrameCount, in format: AVAudioFormat) -> AVAudioPCMBuffer? {
        let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames)
        buffer?.frameLength = frames
        return buffer
    }
}
