#if os(macOS)
import AVFoundation
import ReclyCore
import XCTest
@testable import RecKit

/// docs/08 "Length · size limits": the voices the core links a long recording's pieces by, through the core's own
/// `SpeakerEmbedder` bridge. Two of the system's `say` voices stand in for two people — one at 22.05 kHz, so the
/// conversion to the model's 16 kHz runs too — and a Mac without them skips.
final class SpeakerVoicesTests: XCTestCase {
    func testOneVoiceIsCloserToItselfThanToAnother() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let first = try speak("Samantha", 16_000, "The quarterly numbers are in and the team did better than we planned for. Sales grew in every region, and the new product line is ahead of the forecast by a wide margin.", directory)
        let second = try speak("Daniel", 22_050, "That is good to hear. I would still like to look at the costs before we decide anything about hiring. Can you send me the full breakdown by Friday afternoon?", directory)
        func halves(_ url: URL) throws -> [AudioSpan] {
            let file = try AVAudioFile(forReading: url)
            let duration = Double(file.length) / file.processingFormat.sampleRate
            return [AudioSpan(file: url.okioPath, startSec: 0.2, endSec: duration / 2), AudioSpan(file: url.okioPath, startSec: duration / 2, endSec: duration - 0.2)]
        }
        let a = try halves(first)
        let b = try halves(second)

        let answer = try await AppleSpeakerEmbedder().centroids(spans: [
            "a1": [a[0]], "a2": [a[1]], "b1": [b[0]], "b2": [b[1]],
            "both": [a[0], b[0]],
            // Under a second: not enough to say anything.
            "short": [AudioSpan(file: first.okioPath, startSec: 1, endSec: 1.5)],
        ])

        let voices = try XCTUnwrap(answer, "run apple/scripts/fetch-speaker-models.sh (make core)").mapValues(floats)
        XCTAssertEqual(Set(voices.keys), ["a1", "a2", "b1", "b2", "both"])
        for key in ["a1", "a2", "b1", "b2"] { XCTAssertEqual(norm(voices[key]!), 1, accuracy: 1e-4, "\(key) is one unit embedding") }
        XCTAssertLessThan(norm(voices["both"]!), 1, "a mean of two different unit vectors")
        let same = [cosine(voices["a1"]!, voices["a2"]!), cosine(voices["b1"]!, voices["b2"]!)]
        let different = [("a1", "b1"), ("a1", "b2"), ("a2", "b1"), ("a2", "b2")].map { cosine(voices[$0.0]!, voices[$0.1]!) }
        print("speaker voices: same=\(same) different=\(different)")
        XCTAssertGreaterThan(same.min()!, different.max()!)
    }

    /// Opt-in: the voices of a real recording's labels, the way the core asks for them, printed as cosines. Point
    /// REC_VOICES_RECORDING (through TEST_RUNNER_) at a recording folder with its parts and `transcript.json`; nothing
    /// of it is kept or asserted on. Besides each label's voice, each label's segments are split in two — every other
    /// one — so the same person is also compared over audio that differs.
    func testRealRecordingCosines() async throws {
        let environment = ProcessInfo.processInfo.environment
        guard let path = environment["REC_VOICES_RECORDING"] else { throw XCTSkip("set REC_VOICES_RECORDING to a recording folder") }
        let folder = URL(fileURLWithPath: path, isDirectory: true)
        let names = try FileManager.default.contentsOfDirectory(atPath: path).sorted()
        var parts: [Part] = []
        for name in names where name.hasSuffix("_mono.m4a") {
            let url = folder.appendingPathComponent(name)
            let file = try AVAudioFile(forReading: url)
            parts.append(Part(url: url, start: parts.last.map { $0.start + $0.duration } ?? 0, duration: Double(file.length) / file.processingFormat.sampleRate))
        }
        let transcript = try XCTUnwrap(names.first { $0.hasSuffix(".transcript.json") })
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: folder.appendingPathComponent(transcript))) as? [String: Any])
        let segments = (root["segments"] as? [[String: Any]] ?? []).compactMap { segment -> Segment? in
            guard let label = segment["speaker"] as? String,
                  let start = (segment["start"] as? NSNumber)?.doubleValue,
                  let end = (segment["end"] as? NSNumber)?.doubleValue else { return nil }
            return Segment(label: label, start: start, end: end)
        }
        let labels = Dictionary(grouping: segments, by: \.label)
        var spans: [String: [AudioSpan]] = [:]
        for (label, list) in labels {
            spans[label] = Self.spans(list, parts)
            let usable = list.filter { $0.end - $0.start >= Self.minSegment }.sorted { $0.start < $1.start }
            spans["\(label)/even"] = Self.spans(usable.enumerated().filter { $0.offset % 2 == 0 }.map(\.element), parts)
            spans["\(label)/odd"] = Self.spans(usable.enumerated().filter { $0.offset % 2 == 1 }.map(\.element), parts)
        }
        let started = Date()
        let answer = try await AppleSpeakerEmbedder().centroids(spans: spans.filter { !$0.value.isEmpty })
        let voices = try XCTUnwrap(answer).mapValues(floats)
        print(String(format: "speaker voices: %d keys, %d spans, %.1f s", voices.count, spans.values.map(\.count).reduce(0, +), Date().timeIntervalSince(started)))

        let order = labels.keys.filter { voices[$0] != nil }.sorted { $0.count != $1.count ? $0.count < $1.count : $0 < $1 }
        print("speaker voices: " + order.map { "\($0)=\(spans[$0]!.count) spans/\(String(format: "%.0f", spans[$0]!.map { $0.endSec - $0.startSec }.reduce(0, +))) s" }.joined(separator: " "))
        print("speaker voices matrix\n      " + order.map { $0.padding(toLength: 6, withPad: " ", startingAt: 0) }.joined())
        for row in order {
            print(row.padding(toLength: 6, withPad: " ", startingAt: 0) + order.map { String(format: "%5.2f ", cosine(voices[row]!, voices[$0]!)) }.joined())
        }
        for label in order {
            if let even = voices["\(label)/even"], let odd = voices["\(label)/odd"] {
                print(String(format: "speaker voices: %@ even vs odd %.3f", label, cosine(even, odd)))
            }
        }
        // Pairs to look at, "A-B" in REC_VOICES_PAIRS: the voices as asked, and A's even segments against B's odd ones.
        for pair in (environment["REC_VOICES_PAIRS"] ?? "").split(separator: ",") {
            let ends = pair.split(separator: "-").map(String.init)
            guard ends.count == 2, let a = voices[ends[0]], let b = voices[ends[1]] else { continue }
            let crossed = voices["\(ends[0])/even"].flatMap { even in voices["\(ends[1])/odd"].map { cosine(even, $0) } }
            print(String(format: "speaker voices: %@ %.3f (even vs odd %@)", String(pair), cosine(a, b), crossed.map { String(format: "%.3f", $0) } ?? "-"))
        }
    }

    // MARK: - The core's span rule (TranscribeChunks.spans), for the opt-in measurement

    private struct Part { let url: URL; let start: Double; let duration: Double }
    private struct Segment { let label: String; let start: Double; let end: Double }

    private static let minSegment = 1.5
    private static let trim = 0.25
    private static let maxSpan = 10.0
    private static let maxVoice = 60.0
    private static let minSide = 1.0

    private static func spans(_ segments: [Segment], _ parts: [Part]) -> [AudioSpan] {
        let usable = segments.filter { $0.end - $0.start >= minSegment }.sorted { $0.start < $1.start }.map { segment -> (Double, Double) in
            let start = segment.start + trim
            return (start, min(segment.end - trim, start + maxSpan))
        }
        func evenly(_ count: Int) -> [(Double, Double)] { (0 ..< count).map { usable[Int((Double($0) + 0.5) * Double(usable.count) / Double(count))] } }
        var count = usable.count
        while count > 1, evenly(count).map({ $0.1 - $0.0 }).reduce(0, +) > maxVoice { count -= 1 }
        return evenly(count).flatMap { start, end -> [AudioSpan] in
            let sides = parts.compactMap { part -> AudioSpan? in
                let from = max(start, part.start)
                let to = min(end, part.start + part.duration)
                return to > from ? AudioSpan(file: part.url.okioPath, startSec: from - part.start, endSec: to - part.start) : nil
            }
            return sides.count > 1 ? sides.filter { $0.endSec - $0.startSec >= minSide } : sides
        }
    }

    // MARK: -

    private func speak(_ voice: String, _ rate: Int, _ text: String, _ directory: URL) throws -> URL {
        let url = directory.appendingPathComponent("\(voice).wav")
        let say = Process()
        say.executableURL = URL(fileURLWithPath: "/usr/bin/say")
        say.arguments = ["-v", voice, "-o", url.path, "--file-format=WAVE", "--data-format=LEI16@\(rate)", text]
        try say.run()
        say.waitUntilExit()
        try XCTSkipUnless(say.terminationStatus == 0 && FileManager.default.fileExists(atPath: url.path), "no \(voice) voice")
        return url
    }
}

private func floats(_ array: KotlinFloatArray) -> [Float] { (0 ..< array.size).map { array.get(index: $0) } }

private func norm(_ v: [Float]) -> Float { sqrt(v.reduce(0) { $0 + $1 * $1 }) }

private func cosine(_ a: [Float], _ b: [Float]) -> Float {
    let dot = zip(a, b).reduce(0) { $0 + $1.0 * $1.1 }
    return dot / (norm(a) * norm(b))
}
#endif

