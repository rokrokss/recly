#if os(macOS)
import AVFoundation
import ReclyCore
import Speech
import XCTest
@testable import RecKit

/// Opt-in coverage of the production adapter, including the Kotlin/Swift suspend bridge.
/// Pass REC_SPEECH_TEST, REC_SPEECH_AUDIO and optional REC_SPEECH_PREPARE through TEST_RUNNER_.
/// The ordinary suite neither downloads models nor runs speech inference.
final class LocalSpeechSmokeTests: XCTestCase {
    func testPreparedModelTranscribesShortFileThroughProductionAdapter() async throws {
        let environment = ProcessInfo.processInfo.environment
        try XCTSkipUnless(environment["REC_SPEECH_TEST"] == "1", "set REC_SPEECH_TEST=1 to run local speech inference")
        let path = try XCTUnwrap(environment["REC_SPEECH_AUDIO"], "provide a short local speech fixture")
        let language = environment["REC_SPEECH_LANGUAGE"] ?? "ko"
        let audio = try AVAudioFile(forReading: URL(fileURLWithPath: path))
        let duration = Double(audio.length) / audio.processingFormat.sampleRate
        guard duration > 0, duration <= 20 else { return XCTFail("the smoke fixture must be at most 20 seconds") }
        guard ProcessInfo.processInfo.thermalState == .nominal,
              !ProcessInfo.processInfo.isLowPowerModeEnabled else {
            return XCTFail("run the smoke test only while thermal state is nominal and Low Power Mode is off")
        }

        let engine = LocalSpeechEngine.make()
        defer { engine.cancel() }
        let initial = try await engine.status(language: language)
        print("local speech smoke: initial=\(initial.status), duration=\(duration)")
        if initial.status == .modelRequired, environment["REC_SPEECH_PREPARE"] == "1" {
            let prepared = try await engine.prepare(language: language)
            print("local speech smoke: prepared=\(prepared.status)")
        }
        let ready = try await engine.status(language: language)
        guard ready.status == .ready else { return XCTFail("local speech engine is not ready: \(ready.status)") }
        let progress = SpeechSmokeProgress()
        let started = ProcessInfo.processInfo.systemUptime
        let result = try await engine.transcribe(
            request: LocalTranscriptionRequest(path: path, language: language, startTimeSec: 0, diarize: false),
            progress: progress
        )
        let segments = progress.segments
        let text = segments.map(\.text).joined(separator: " ")
        print("local speech smoke: completed=\(result.completed), seconds=\(ProcessInfo.processInfo.systemUptime - started), thermal=\(ProcessInfo.processInfo.thermalState.rawValue), segments=\(segments.count), text=\(text)")
        XCTAssertTrue(result.completed, "the short sample must finish without an admission pause")
        if #available(macOS 26, *), let locale = await SpeechTranscriber.supportedLocale(equivalentTo: Locale(identifier: language)) {
            let reserved = await AssetInventory.reservedLocales
            XCTAssertTrue(reserved.contains(locale), "the app must reserve a locale even when another app installed its assets")
        }
        XCTAssertFalse(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, "no final speech result was checkpointed")
        if let expected = environment["REC_SPEECH_EXPECTED"] {
            XCTAssertTrue(text.localizedCaseInsensitiveContains(expected), "expected fixture phrase was not recognized")
        }
        var end = 0.0
        for segment in segments {
            XCTAssertTrue(segment.start.isFinite && segment.end.isFinite)
            XCTAssertGreaterThanOrEqual(segment.start, end - 0.01)
            XCTAssertGreaterThan(segment.end, segment.start)
            XCTAssertLessThanOrEqual(segment.end, duration + 0.25)
            end = segment.end
        }
    }
}

private final class SpeechSmokeProgress: NSObject, LocalTranscriptionProgress, @unchecked Sendable {
    private let lock = NSLock()
    private var stored: [SttSegment] = []
    var segments: [SttSegment] { lock.withLock { stored } }

    func __checkpoint(segment: SttSegment, completedThroughSec: Double) async throws {
        XCTAssertEqual(completedThroughSec, segment.end, accuracy: 0.001)
        lock.withLock { stored.append(segment) }
    }
}
#endif
