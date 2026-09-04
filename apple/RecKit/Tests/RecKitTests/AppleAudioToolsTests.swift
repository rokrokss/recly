import AVFoundation
import ReclyCore
import XCTest
@testable import RecKit

/// docs/08 "오디오 준비": the parts of one track joined into a single `m4a`, as long as the parts
/// put together and still decodable AAC.
///
/// The fixtures are written through `SegmentedRecorder.openSegmentFile` rather than checked in, so
/// what is joined is exactly the container the recorder produces (ADR-006) and not an approximation
/// of it.
final class AppleAudioToolsTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("RecKitTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    func testTwoPartsBecomeOneFileOfBothLengths() async throws {
        let first = try part(named: "20260826T010000Z_desktop_01J9ABCD_p001_mono.m4a", seconds: 2)
        let second = try part(named: "20260826T010000Z_desktop_01J9ABCD_p002_mono.m4a", seconds: 3)
        let out = directory.appendingPathComponent("joined.m4a")

        try await AppleAudioTools().__concat(parts: [first.okioPath, second.okioPath], out: out.okioPath)

        XCTAssertTrue(FileManager.default.fileExists(atPath: out.path), "nothing was written")
        let joined = try XCTUnwrap(PartReconciler.containerDurationSec(of: out))
        // docs/08: the joined length is the parts' lengths added up, to within a frame. Measured
        // against the parts themselves rather than against 2 + 3: each part carries the AAC
        // encoder's own priming and padding, so a part of two seconds of audio is a little longer
        // than two seconds of container.
        let parts = try XCTUnwrap(PartReconciler.containerDurationSec(of: first))
            + XCTUnwrap(PartReconciler.containerDurationSec(of: second))
        XCTAssertEqual(joined, parts, accuracy: frame, "joined is \(joined)s, the parts are \(parts)s")
    }

    func testTheFramesAreCopiedRatherThanReEncoded() async throws {
        let first = try part(named: "20260826T010000Z_desktop_01J9ABCD_p001_mono.m4a", seconds: 2)
        let second = try part(named: "20260826T010000Z_desktop_01J9ABCD_p002_mono.m4a", seconds: 2)
        let out = directory.appendingPathComponent("joined.m4a")

        try await AppleAudioTools().__concat(parts: [first.okioPath, second.okioPath], out: out.okioPath)

        let tracks = try await AVURLAsset(url: out).loadTracks(withMediaType: .audio)
        XCTAssertEqual(tracks.count, 1, "one track in, one track out")
        let formats = try await tracks[0].load(.formatDescriptions)
        let subtype = try XCTUnwrap(formats.first.map { CMFormatDescriptionGetMediaSubType($0) })
        XCTAssertEqual(subtype, kAudioFormatMPEG4AAC, "a passthrough export does not re-encode")
        let asbd = try XCTUnwrap(formats.first.flatMap { CMAudioFormatDescriptionGetStreamBasicDescription($0) })
        XCTAssertEqual(asbd.pointee.mSampleRate, 16_000)
        XCTAssertEqual(asbd.pointee.mChannelsPerFrame, 1)
    }

    func testOnePartIsStillCopiedThrough() async throws {
        let only = try part(named: "20260826T010000Z_desktop_01J9ABCD_p001_mono.m4a", seconds: 2)
        let out = directory.appendingPathComponent("joined.m4a")

        try await AppleAudioTools().__concat(parts: [only.okioPath], out: out.okioPath)

        let joined = try XCTUnwrap(PartReconciler.containerDurationSec(of: out))
        let part = try XCTUnwrap(PartReconciler.containerDurationSec(of: only))
        XCTAssertEqual(joined, part, accuracy: frame, "joined is \(joined)s, the part is \(part)s")
    }

    /// The failure that matters: a part that is not a readable container must stop the step rather
    /// than leave a transcript of whatever else was joinable.
    func testAPartWithNoAudioTrackIsRefused() async throws {
        let bogus = directory.appendingPathComponent("part-001.m4a")
        try Data(count: 4000).write(to: bogus)
        let out = directory.appendingPathComponent("joined.m4a")

        do {
            try await AppleAudioTools().__concat(parts: [bogus.okioPath], out: out.okioPath)
            XCTFail("a file with no audio track went unnoticed")
        } catch {
            XCTAssertFalse(FileManager.default.fileExists(atPath: out.path), "half a file was left behind")
        }
    }

    /// docs/08's tolerance: one AAC frame, which at 16 kHz is 64 ms.
    private let frame = 0.064

    /// One part of [seconds] of silence, in the recorder's own container.
    private func part(named name: String, seconds: Double) throws -> URL {
        var file: AVAudioFile? = try SegmentedRecorder.openSegmentFile(named: name, in: directory)
        let format = try XCTUnwrap(file?.processingFormat)
        let frames = AVAudioFrameCount(4_000)
        for _ in 0 ..< Int((seconds * format.sampleRate / Double(frames)).rounded()) {
            try file?.write(from: try XCTUnwrap(silence(of: frames, in: format)))
        }
        // Only the release writes the trailing MPEG-4 atoms (see `SegmentFileTests`).
        file = nil
        return directory.appendingPathComponent(name)
    }

    private func silence(of frames: AVAudioFrameCount, in format: AVAudioFormat) -> AVAudioPCMBuffer? {
        let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames)
        buffer?.frameLength = frames
        return buffer
    }
}
