#if os(macOS)
import AVFoundation
import RecKitSpeakers
import XCTest

/// docs/09 "On-device speaker separation" · §15 "Apple on-device speech model assets": the bundled models
/// load with no network, and two plainly different voices come back as two speakers. The voices are the
/// system's own `say`, so the fixture is made here rather than checked in; a Mac without them skips.
final class SpeakerSeparationTests: XCTestCase {
    func testTwoVoicesAreTwoSpeakers() async throws {
        XCTAssertTrue(SpeakerSeparation.available, "run apple/scripts/fetch-speaker-models.sh (make core)")
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let first = try speak("Samantha", "The quarterly numbers are in and the team did better than we planned for. Sales grew in every region, and the new product line is ahead of the forecast by a wide margin.", directory)
        let second = try speak("Daniel", "That is good to hear. I would still like to look at the costs before we decide anything about hiring. Can you send me the full breakdown by Friday afternoon?", directory)
        let joined = directory.appendingPathComponent("joined.caf")
        let split = try join([first, second], into: joined)

        let turns = try await SpeakerSeparation.turns(of: joined, speakers: nil)

        XCTAssertGreaterThanOrEqual(Set(turns.map(\.label)).count, 2, "\(turns)")
        func dominant(_ range: ClosedRange<Double>) -> String? {
            Dictionary(grouping: turns) { $0.label }
                .mapValues { $0.reduce(0) { $0 + max(0, min($1.end, range.upperBound) - max($1.start, range.lowerBound)) } }
                .max { $0.value < $1.value }?.key
        }
        XCTAssertNotEqual(dominant(0 ... split), dominant(split ... split * 3))
    }

    private func speak(_ voice: String, _ text: String, _ directory: URL) throws -> URL {
        let url = directory.appendingPathComponent("\(voice).wav")
        let say = Process()
        say.executableURL = URL(fileURLWithPath: "/usr/bin/say")
        say.arguments = ["-v", voice, "-o", url.path, "--file-format=WAVE", "--data-format=LEI16@16000", text]
        try say.run()
        say.waitUntilExit()
        try XCTSkipUnless(say.terminationStatus == 0 && FileManager.default.fileExists(atPath: url.path), "no \(voice) voice")
        return url
    }

    /// The files end to end in one 16 kHz mono file; returns where the second one starts.
    private func join(_ urls: [URL], into output: URL) throws -> Double {
        let format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16_000, channels: 1, interleaved: false)!
        let out = try AVAudioFile(forWriting: output, settings: format.settings)
        var firstLength = 0.0
        for (index, url) in urls.enumerated() {
            let file = try AVAudioFile(forReading: url, commonFormat: .pcmFormatFloat32, interleaved: false)
            let buffer = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: AVAudioFrameCount(file.length))!
            try file.read(into: buffer)
            try out.write(from: buffer)
            if index == 0 { firstLength = Double(file.length) / file.processingFormat.sampleRate }
        }
        return firstLength
    }
}
#endif
