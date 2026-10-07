#if os(macOS)
import AVFoundation
import ReclyCore
import RecKitSpeakers
import XCTest
@testable import RecKit

/// docs/10 "Shared rules for the shells": a final result of Apple's transcriber that runs on across a change of
/// speaker is cut there, word by word.
final class SpeakerCutTests: XCTestCase {
    private let turns = [SpeakerTurn(start: 0, end: 4.9, label: "S1"), SpeakerTurn(start: 5.2, end: 10, label: "S2")]

    func testAResultIsCutWhereTheSpeakerChanges() {
        // A word's time runs from the end of the one before, so the reply's first word starts in the pause.
        let runs = [run("Hello.", 0, 1), run(" We", 1, 3), run(" start.", 3, 4.9), run(" Yes,", 4.9, 5.8), run(" sure.", 5.8, 10)]
        let pieces = SpeakerCut.segments(of: whole(runs), runs: runs, turns: turns)
        XCTAssertEqual(pieces.map(\.text), ["Hello. We start.", "Yes, sure."])
        XCTAssertEqual(pieces.map(\.speaker), ["S1", "S2"])
        XCTAssertEqual(pieces.map(\.start), [0, 5.2])
        XCTAssertEqual(pieces.map(\.end), [4.9, 10])
    }

    func testAReplysFirstWordAfterAPauseIsTheRepliers() {
        // The first turn reaches 0.28 s past its last word, longer than the reply's "Yes," is heard in its own.
        let turns = [SpeakerTurn(start: 0, end: 5.86, label: "S1"), SpeakerTurn(start: 6.1, end: 10, label: "S2")]
        let runs = [run("Let us check the dates.", 0, 5.58), run(" Yes,", 5.58, 6.36), run(" it passed.", 6.36, 10)]
        let pieces = SpeakerCut.segments(of: whole(runs), runs: runs, turns: turns)
        XCTAssertEqual(pieces.map(\.text), ["Let us check the dates.", "Yes, it passed."])
        XCTAssertEqual(pieces.map(\.speaker), ["S1", "S2"])
        XCTAssertEqual(pieces.map(\.start), [0, 6.1])
    }

    func testARunWithNoTimeStaysWithTheRunBefore() {
        let runs = [run("So", nil, nil), run(" we", 0, 2), run(" start.", 2, 4.9), run("…", nil, nil), run(" ", 4.9, 5.3), run(" Yes.", 5.3, 10), run("!", nil, nil)]
        let pieces = SpeakerCut.segments(of: whole(runs), runs: runs, turns: turns)
        XCTAssertEqual(pieces.map(\.text), ["So we start.…", "Yes.!"])
        XCTAssertEqual(pieces.map(\.speaker), ["S1", "S2"])
        XCTAssertEqual(pieces.map(\.end), [4.9, 10])
    }

    func testAResultWithNoTimesIsOneSegmentWithOneSpeaker() {
        let runs = [run("Hello. Yes, sure.", nil, nil)]
        XCTAssertEqual(SpeakerCut.segments(of: whole(runs), runs: runs, turns: turns).map(\.speaker), ["S1"])
        XCTAssertEqual(SpeakerCut.segments(of: whole(runs), runs: runs, turns: []), [whole(runs)])
    }

    private func run(_ text: String, _ start: Double?, _ end: Double?) -> SpeakerCut.Run {
        SpeakerCut.Run(text: text, start: start, end: end)
    }

    private func whole(_ runs: [SpeakerCut.Run]) -> SttSegment {
        SttSegment(start: 0, end: 10, speaker: nil, text: runs.map(\.text).joined(), words: nil)
    }
}

/// The same through the production adapter: two voices taking turns, which Apple's Korean transcriber hands
/// back as results of several speakers each (macOS 26.6), come out as segments of one speaker each. The voices
/// are the system's own `say`, so the fixture is made here; a Mac without them, the Korean assets or the
/// speaker models skips. Opt-in like the smoke tests — the ordinary suite runs no speech inference: pass
/// REC_SPEECH_TEST=1, and optionally REC_SPEECH_CUT_CLIPS (paths joined by `:`, speakers alternating) for
/// other clips, through TEST_RUNNER_.
final class AppleSpeakerCutTests: XCTestCase {
    func testTwoSpeakersInOneResultAreCutAtTheirTurns() async throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["REC_SPEECH_TEST"] == "1", "set REC_SPEECH_TEST=1 to run local speech inference")
        guard #available(macOS 26, *) else { throw XCTSkip("on-device transcription needs macOS 26") }
        try XCTSkipUnless(SpeakerSeparation.available, "run apple/scripts/fetch-speaker-models.sh (make core)")
        let engine = LocalSpeechEngine.make()
        defer { engine.cancel() }
        let status = try await engine.status(language: "ko").status
        try XCTSkipUnless(status == .ready, "Korean speech assets are not installed (\(status)); this test downloads nothing")
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let clips: [URL]
        if let paths = ProcessInfo.processInfo.environment["REC_SPEECH_CUT_CLIPS"] {
            clips = paths.split(separator: ":").map { URL(fileURLWithPath: String($0)) }
        } else {
            let eddy = "com.apple.eloquence.ko-KR.Eddy"
            clips = try [
                ("Yuna", "안녕하세요. 이번 주 회의를 시작하겠습니다. 먼저 출시 일정부터 확인할게요."),
                (eddy, "네, 안드로이드 빌드는 어제 통과했고 워치 앱도 심사를 받았습니다."),
                ("Yuna", "좋아요. 그럼 남은 일은 무엇인가요?"),
                (eddy, "화자 분리 품질을 실제 음성으로 확인하는 일이 남았습니다. 다음 주까지 끝내겠습니다."),
                ("Yuna", "알겠습니다. 그럼 다음 주에 다시 봬요."),
            ].enumerated().map { try speak($0.element.0, $0.element.1, directory.appendingPathComponent("\($0.offset).wav")) }
        }
        let joined = directory.appendingPathComponent("joined.wav")
        let changes = try join(clips, gap: 0.3, into: joined)

        let turns = try await SpeakerSeparation.turns(of: joined, speakers: 2)
        let progress = CutProgress()
        let result = try await engine.transcribe(
            request: LocalTranscriptionRequest(path: joined.path, language: "ko", startTimeSec: 0, diarize: true, expectedSpeakers: KotlinInt(int: 2), vocabulary: []),
            progress: progress
        )

        let segments = progress.segments
        for segment in segments {
            print(String(format: "speaker cut: %6.2f–%6.2f %@ %@", segment.start, segment.end, segment.speaker ?? "-", segment.text))
        }
        print("speaker cut: turns \(turns.map { String(format: "%.2f–%.2f %@", $0.start, $0.end, $0.label) })")
        XCTAssertTrue(result.completed)
        XCTAssertGreaterThanOrEqual(Set(segments.compactMap(\.speaker)).count, 2, "\(turns)")
        // Between two segments: from the end of one to the start of the next.
        let cuts = zip(segments, segments.dropFirst()).map { ($0.end, $1.start) }
        for change in changes {
            XCTAssertTrue(cuts.contains { change >= $0.0 - 0.5 && change <= $0.1 + 0.5 }, "no segment boundary near the change of speaker at \(change) s: \(cuts)")
        }
        for segment in segments {
            let shares = Dictionary(grouping: turns, by: \.label)
                .mapValues { $0.reduce(0) { $0 + max(0, min($1.end, segment.end) - max($1.start, segment.start)) } }
                .values.sorted(by: >)
            XCTAssertLessThanOrEqual(shares.dropFirst().reduce(0, +), 0.5, "\(segment.start)–\(segment.end) spans two speakers' turns")
        }
    }

    private func speak(_ voice: String, _ text: String, _ url: URL) throws -> URL {
        let say = Process()
        say.executableURL = URL(fileURLWithPath: "/usr/bin/say")
        say.arguments = ["-v", voice, "-o", url.path, "--file-format=WAVE", "--data-format=LEI16@16000", text]
        try say.run()
        say.waitUntilExit()
        let length = (try? AVAudioFile(forReading: url).length) ?? 0
        try XCTSkipUnless(say.terminationStatus == 0 && length > 16_000, "no \(voice) voice")
        return url
    }

    /// The clips end to end in one 16 kHz mono file, [gap] seconds of silence between them; returns the
    /// middle of each gap, where the speaker changes.
    private func join(_ urls: [URL], gap: Double, into output: URL) throws -> [Double] {
        let format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16_000, channels: 1, interleaved: false)!
        let out = try AVAudioFile(forWriting: output, settings: format.settings)
        let silence = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(gap * format.sampleRate))!
        silence.frameLength = silence.frameCapacity
        var changes: [Double] = []
        for (index, url) in urls.enumerated() {
            let file = try AVAudioFile(forReading: url)
            let input = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: AVAudioFrameCount(file.length))!
            try file.read(into: input)
            let converter = try XCTUnwrap(AVAudioConverter(from: file.processingFormat, to: format))
            let capacity = AVAudioFrameCount(Double(file.length) * format.sampleRate / file.processingFormat.sampleRate) + 1_024
            let converted = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: capacity)!
            var fed = false
            var error: NSError?
            converter.convert(to: converted, error: &error) { _, state in
                if fed { state.pointee = .endOfStream; return nil }
                fed = true
                state.pointee = .haveData
                return input
            }
            if let error { throw error }
            try out.write(from: converted)
            if index < urls.count - 1 {
                changes.append(Double(out.length) / format.sampleRate + gap / 2)
                try out.write(from: silence)
            }
        }
        return changes
    }
}

private final class CutProgress: NSObject, LocalTranscriptionProgress, @unchecked Sendable {
    private let lock = NSLock()
    private var stored: [SttSegment] = []
    private var through = 0.0
    var segments: [SttSegment] { lock.withLock { stored } }

    func __checkpoint(segment: SttSegment, completedThroughSec: Double) async throws {
        XCTAssertGreaterThanOrEqual(completedThroughSec, segment.end)
        lock.withLock {
            // The core drops a segment that ends where the saved progress already is.
            XCTAssertGreaterThan(segment.end, through, "a checkpoint the core would drop")
            stored.append(segment)
            through = completedThroughSec
        }
    }
}
#endif
