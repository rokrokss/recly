#if os(iOS)
import AVFoundation
import XCTest
@testable import RecKit

final class PlaybackAudioSessionTests: XCTestCase {
    @MainActor
    func testPlaybackAfterRecordingReactivatesTheSessionAsMediaPlayback() async throws {
        let session = AVAudioSession.sharedInstance()
        // Stopping IOSAudioInput deactivates the session but leaves this category behind.
        try session.setCategory(.playAndRecord, mode: .default)
        try session.setActive(false)
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("playback-\(UUID().uuidString).wav")
        let format = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: 16_000, channels: 1))
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 16_000))
        buffer.frameLength = 16_000
        buffer.floatChannelData?.pointee.update(repeating: 0, count: Int(buffer.frameLength))
        try AVAudioFile(forWriting: file, settings: format.settings).write(from: buffer)
        let gate = RecordingPlaybackGate()
        let player = RecordingPlayer(gate: gate)
        defer {
            player.stop()
            try? FileManager.default.removeItem(at: file)
        }
        player.load(RecordingPlaylist.Selection(urls: [file], durations: [1]))
        player.play()
        XCTAssertEqual(session.category, .playback)
        for _ in 0..<50 {
            player.refreshStatus()
            if player.positionSec > 0 { break }
            try await Task.sleep(for: .milliseconds(20))
        }
        XCTAssertFalse(player.failed)
        XCTAssertGreaterThan(player.positionSec, 0)
        player.pause()
        player.play()
        XCTAssertEqual(session.category, .playback)
        gate.setBlocked(true)
        XCTAssertFalse(player.active, "capture must stop playback before it opens the input")
        try session.setCategory(.playAndRecord, mode: .default)
        player.load(RecordingPlaylist.Selection(urls: [file], durations: [1]))
        player.play()
        XCTAssertEqual(session.category, .playAndRecord, "blocked playback must not change the recorder's session")
        XCTAssertFalse(player.active)
    }
}
#endif
