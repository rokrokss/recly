import AVFoundation
import Combine
import Foundation
import RecKitTestSupport
import ReclyCore
import XCTest
@testable import RecKit

/// docs/09 screen principle 2: a recording's waveform is decoded once and kept beside its parts
/// (`WaveformPeaks`). The detail reads the kept peaks first and decodes only when they do not cover
/// the audio it has; while it decodes it shows the loader, never a flat baseline; and a recording
/// finalized on this device gets its peaks worked out in the background.
final class WaveformCacheTests: XCTestCase {
    private var dataDirectory: URL!

    override func setUpWithError() throws {
        dataDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("WaveformCacheTests-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dataDirectory)
    }

    // MARK: - The arithmetic

    func testTheWindowIsTheCores() {
        XCTAssertEqual(RecordingWaveform.windowSec, WaveformPeaks.shared.WINDOW_SEC)
    }

    /// Each part rounds up on its own, as the decode pads or truncates it.
    func testTheWindowCountIsEachPartRoundedUp() {
        let selection = RecordingPlaylist.Selection(urls: [part(1), part(2)], durations: [60, 30.1])
        XCTAssertEqual(RecordingWaveform.windowCount(for: selection), 240 + 121)
    }

    func testSavedPeaksAreUsedOnlyWhenTheyCoverTheAudio() {
        let selection = RecordingPlaylist.Selection(urls: [part(1)], durations: [2])
        let eight: [Float] = Array(repeating: 0.5, count: 8)
        XCTAssertEqual(RecordingWaveform.cached(eight, for: selection), eight)
        XCTAssertNil(RecordingWaveform.cached(Array(eight.prefix(7)), for: selection), "fewer windows")
        XCTAssertNil(RecordingWaveform.cached(eight + [0.5], for: selection), "more windows")
        XCTAssertNil(RecordingWaveform.cached([], for: selection))
        XCTAssertNil(RecordingWaveform.cached(nil, for: selection))
        XCTAssertNil(RecordingWaveform.cached(eight, for: .empty), "no audio has no waveform")
    }

    /// The recovery walk files only `_pNNN_<track>.m4a`: the kept peaks and their temporary file
    /// are neither registered as a part nor quarantined.
    func testRecoveryDoesNotTakeTheWaveformFileForAPart() {
        XCTAssertNil(PartReconciler.segment(of: WaveformPeaks.shared.FILE))
        XCTAssertNil(PartReconciler.segment(of: WaveformPeaks.shared.FILE + ".tmp"))
        XCTAssertNotNil(PartReconciler.segment(of: "260926-0912_p001_mono.m4a"))
    }

    // MARK: - The detail

    /// A kept waveform that covers the audio is drawn as it is — the part here cannot even be
    /// decoded, so anything but the kept peaks would be an empty bar.
    @MainActor
    func testKeptPeaksAreDrawnWithoutDecoding() async throws {
        let bridge = try await makeBridge()
        let id = try await seed(bridge, durationSec: 2, audio: .undecodable)
        let kept: [Float] = [0.1, 0.9, 0.2, 0.8, 0.3, 0.7, 0.4, 0.6]
        try await bridge.core.recordings.saveWaveform(recordingId: id, peaks: kept.map { KotlinFloat(float: $0) })

        let model = RecordingDetailModel(core: bridge.core, recordingId: id, title: "Kept")
        await model.load()

        XCTAssertEqual(model.waveform.count, kept.count)
        for (drawn, saved) in zip(model.waveform, kept) { XCTAssertEqual(drawn, saved, accuracy: 0.0001) }
        XCTAssertFalse(model.waveformPending)
    }

    /// Peaks that do not cover the audio (another count of windows) are decoded again, and the new
    /// ones are kept.
    @MainActor
    func testACountMismatchDecodesAgainAndKeepsTheResult() async throws {
        let bridge = try await makeBridge()
        let id = try await seed(bridge, durationSec: 2, audio: .tone)
        try await bridge.core.recordings.saveWaveform(recordingId: id, peaks: [0.5, 0.5, 0.5].map { KotlinFloat(float: $0) })

        let model = RecordingDetailModel(core: bridge.core, recordingId: id, title: "Stale")
        await model.load()

        XCTAssertEqual(model.waveform.count, 8, "2 s of 0.25 s windows")
        XCTAssertGreaterThan(model.waveform.max() ?? 0, 0.1, "the tone, not silence")
        let kept = try await bridge.core.recordings.waveform(recordingId: id)
        XCTAssertEqual(kept?.count, 8)
    }

    /// From the moment there is audio until its peaks are there, the bar is waiting for them — the
    /// loader — and it never stops waiting with an empty waveform for a recording that decodes.
    @MainActor
    func testTheBarWaitsForThePeaksInsteadOfDrawingABaseline() async throws {
        let bridge = try await makeBridge()
        let id = try await seed(bridge, durationSec: 2, audio: .tone)
        let model = RecordingDetailModel(core: bridge.core, recordingId: id, title: "Fresh")
        XCTAssertTrue(model.waveformPending, "waiting from the start")

        var baselines = 0
        let watch = model.objectWillChange.sink { _ in
            // Read on the next turn, once the change has been written.
            DispatchQueue.main.async {
                if model.hasAudio, model.waveform.isEmpty, !model.waveformPending, !model.loading { baselines += 1 }
            }
        }
        await model.load()
        try await Task.sleep(for: .milliseconds(50))
        watch.cancel()

        XCTAssertFalse(model.waveformPending)
        XCTAssertFalse(model.waveform.isEmpty)
        XCTAssertEqual(baselines, 0, "an empty bar that was not waiting is a flat baseline")
    }

    // MARK: - Precompute

    @MainActor
    func testAFinalizedRecordingGetsItsPeaksKept() async throws {
        let bridge = try await makeBridge()
        let id = try await seed(bridge, durationSec: 2, audio: .tone)
        let precompute = WaveformPrecompute(core: bridge.core)

        precompute.enqueue(recordingId: id)

        let kept = try await eventually { try await bridge.core.recordings.waveform(recordingId: id) }
        XCTAssertEqual(kept?.count, 8)
    }

    /// Nothing is decoded while a capture runs; the recording waits its turn and is done after.
    @MainActor
    func testACaptureHoldsThePrecomputeUntilItEnds() async throws {
        let bridge = try await makeBridge()
        let id = try await seed(bridge, durationSec: 2, audio: .tone)
        let precompute = WaveformPrecompute(core: bridge.core)
        precompute.capturing = true

        precompute.enqueue(recordingId: id)
        try await Task.sleep(for: .milliseconds(300))
        let during = try await bridge.core.recordings.waveform(recordingId: id)
        XCTAssertNil(during)

        precompute.capturing = false
        let after = try await eventually { try await bridge.core.recordings.waveform(recordingId: id) }
        XCTAssertEqual(after?.count, 8)
    }

    // MARK: - Fixtures

    private enum Audio { case tone, undecodable }

    private func part(_ number: Int) -> URL {
        URL(fileURLWithPath: "/recordings/01J9REC/p00\(number)_mono.m4a")
    }

    private func eventually<T>(_ read: () async throws -> T?) async throws -> T? {
        for _ in 0..<150 {
            if let value = try await read() { return value }
            try await Task.sleep(for: .milliseconds(20))
        }
        return nil
    }

    /// A finalized one-part recording whose part is a real AAC tone, or bytes no decoder reads.
    private func seed(_ bridge: CoreBridge, durationSec: Double, audio: Audio) async throws -> String {
        let startedAt = bridge.deps.clock.now()
        let recordingId = Ulid.shared.generate(clock: FixedKotlinClock(startedAt))
        let meta = RecordingMeta(
            schema: 1, recordingId: recordingId, source: Source.desktop, platform: Platform.macos,
            deviceId: bridge.deps.device.deviceId, deviceName: bridge.deps.device.name, workflowId: nil,
            title: nil, startedAt: startedAt.isoUtc, endedAt: nil, durationSec: nil, timezone: "Asia/Seoul",
            audio: AudioSettings(
                codec: Codec.aacLc, container: Container.m4A, sampleRateHz: 48_000, channels: 1,
                bitrateKbps: 96, segmentSec: 900
            ),
            tracks: [Track.mono], parts: [], gaps: [], silenced: [], context: nil, drive: nil,
            status: RecordingStatus.recording
        )
        let directory = dataDirectory
            .appendingPathComponent("recordings", isDirectory: true)
            .appendingPathComponent(MetaWriter.shared.baseName(meta: meta), isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try await bridge.core.recordings.create(meta: meta, dir: directory.okioPath)
        let file = directory.appendingPathComponent("p001_mono.m4a")
        switch audio {
        case .tone: try Self.writeTone(to: file, seconds: durationSec)
        case .undecodable: try Data("not audio".utf8).write(to: file)
        }
        let bytes = (try FileManager.default.attributesOfItem(atPath: file.path)[.size] as? NSNumber)?.int64Value ?? 0
        try await bridge.core.recordings.addPart(recordingId: recordingId, part: Part_(
            part: 1, track: .mono, file: file.lastPathComponent, bytes: bytes,
            sha256: String(repeating: "0", count: 64), startOffsetSec: 0, durationSec: durationSec
        ))
        _ = try await bridge.core.recordings.finalize(
            recordingId: recordingId, endedAt: bridge.deps.clock.now(),
            durationSec: durationSec, title: nil, silenced: [], gaps: []
        )
        return recordingId
    }

    /// A 440 Hz tone at half scale, as AAC in an MPEG-4 container — what the recorder writes.
    static func writeTone(to url: URL, seconds: Double, sampleRate: Double = 48_000) throws {
        let format = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 1))
        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: sampleRate,
            AVNumberOfChannelsKey: 1,
            AVEncoderBitRateKey: 96_000,
        ]
        let file = try AVAudioFile(forWriting: url, settings: settings, commonFormat: .pcmFormatFloat32, interleaved: false)
        let chunk = AVAudioFrameCount(sampleRate)
        var written: AVAudioFramePosition = 0
        let total = AVAudioFramePosition(seconds * sampleRate)
        while written < total {
            let frames = AVAudioFrameCount(min(Int64(chunk), total - written))
            let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames))
            buffer.frameLength = frames
            let samples = try XCTUnwrap(buffer.floatChannelData?.pointee)
            for index in 0..<Int(frames) {
                samples[index] = 0.5 * Float(sin(2 * Double.pi * 440 * Double(written + AVAudioFramePosition(index)) / sampleRate))
            }
            try file.write(from: buffer)
            written += AVAudioFramePosition(frames)
        }
    }

    private func makeBridge() async throws -> CoreBridge {
        try await CoreBridge.make(
            deviceName: "RecKitTests",
            dataDirectory: dataDirectory,
            databaseName: "waveform-cache-tests.db",
            secureStore: InMemorySecureStore()
        )
    }
}
