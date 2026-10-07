import Foundation
import ReclyCore

public enum LocalSpeechEngine {
    /// Whether this OS can transcribe on device at all. Synchronous, so settings never flash the option.
    public static var available: Bool {
        #if os(iOS) || os(macOS)
        if #available(iOS 26, macOS 26, *) { return SpeechTranscriber.isAvailable }
        #endif
        return false
    }

    public static func supportedLanguages() async -> [Language] {
        #if os(iOS) || os(macOS)
        if #available(iOS 26, macOS 26, *) {
            guard SpeechTranscriber.isAvailable else { return [] }
            var supported: [Language] = []
            for language in TranscriptionLanguages.shared.explicit {
                if await speechLocale(language.name.lowercased().replacingOccurrences(of: "_", with: "-")) != nil { supported.append(language) }
            }
            return supported
        }
        #endif
        return []
    }

    #if os(iOS) || os(macOS)
    @available(iOS 26, macOS 26, *)
    fileprivate static func speechLocale(_ code: String) async -> Locale? {
        guard let language = TranscriptionLanguages.shared.explicit.first(where: {
            $0.name.lowercased().replacingOccurrences(of: "_", with: "-") == code
        }) else { return nil }
        let tag = TranscriptionLanguages.shared.localeTag(language: language)
        return await SpeechTranscriber.supportedLocale(equivalentTo: Locale(identifier: tag))
    }
    #endif

    public static func make() -> any LocalTranscriptionEngine {
        #if os(iOS) || os(macOS)
        if #available(iOS 26, macOS 26, *) { return AppleSpeechTranscriber() }
        #endif
        return UnavailableLocalTranscriptionEngine()
    }
}

#if os(iOS) || os(macOS)
import AVFoundation
import Speech
import CoreMedia
import RecKitSpeakers
import os

/// One native long-file session, with final-segment checkpoints and bounded PCM buffering.
@available(iOS 26, macOS 26, *)
private final class AppleSpeechTranscriber: LocalTranscriptionEngine, ModelDownloadCancelling, @unchecked Sendable {
    private let lock = NSLock()
    private var active: SpeechAnalyzer?
    /// The speaker separation in flight, before [active] exists — [cancel] stops it too.
    private var separating: Task<[SpeakerSeparation.Turn], Error>?
    /// docs/05 "Fixed processing settings": the system asset download in flight, kept so [__status] can say
    /// how far it has got — the request is `ProgressReporting` — and the shell can stop it.
    private var installing: Installing?

    private struct Installing {
        let language: String
        let request: AssetInstallationRequest
        let task: Task<Void, Error>
    }

    func cancel() {
        let (analyzer, separation) = lock.withLock { (active, separating) }
        separation?.cancel()
        if let analyzer { Task { await analyzer.cancelAndFinishNow() } }
    }

    func cancelDownload() {
        lock.withLock { installing }?.task.cancel()
    }

    func __status(language: String) async throws -> LocalEngineInfo {
        guard SpeechTranscriber.isAvailable, let locale = await locale(language) else { return info(.unsupported) }
        let module = SpeechTranscriber(locale: locale, preset: .transcription)
        guard await Self.installed(module, locale) else {
            // The system does not say how big its assets are: a share of them, and no byte count.
            let download = lock.withLock { installing }.flatMap { $0.language == language ? $0 : nil }
            return info(.modelRequired, progress: download?.request.progress.fractionCompleted, downloading: download != nil)
        }
        guard !Self.tooHot else { return info(.waiting) }
        return info(.ready)
    }

    func __prepare(language: String) async throws -> LocalEngineInfo {
        guard SpeechTranscriber.isAvailable, let locale = await locale(language) else { return info(.unsupported) }
        // No thermal or power gate here: that is for transcribing. The download is what the user just
        // asked for, and the install is the system's to schedule (docs/05 "Fixed processing settings").
        let module = SpeechTranscriber(locale: locale, preset: .transcription)
        if let request = try await AssetInventory.assetInstallationRequest(supporting: [module]) {
            // Its own task, so the shell's Cancel reaches it however the call above it is bridged.
            let task = Task { try await request.downloadAndInstall() }
            lock.withLock { installing = Installing(language: language, request: request, task: task) }
            defer { lock.withLock { installing = nil } }
            try await withTaskCancellationHandler { try await task.value } onCancel: { task.cancel() }
        }
        return try await __status(language: language)
    }

    func __transcribe(request: LocalTranscriptionRequest, progress: any LocalTranscriptionProgress) async throws -> LocalTranscriptionResult {
        guard try await __status(language: request.language).status == .ready,
              let locale = await locale(request.language) else {
            return LocalTranscriptionResult(segments: [], completed: false)
        }
        // Shared assets can already be installed without this app holding a locale reservation.
        _ = try await AssetInventory.reserve(locale: locale)
        let turns = request.diarize ? try await speakerTurns(request) : []
        let module = SpeechTranscriber(locale: locale, preset: .transcription)
        let analyzer = SpeechAnalyzer(modules: [module], options: .init(priority: .utility, modelRetention: .whileInUse))
        lock.withLock { active = analyzer }
        let admission = SpeechAdmission()
        let monitor = Task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                if Self.tooHot {
                    await admission.pause()
                    await analyzer.cancelAndFinishNow()
                    return
                }
            }
        }
        let reader = Task {
            for try await result in module.results where result.isFinal {
                try Task.checkCancellation()
                var segment = SttSegment(start: result.range.start.seconds, end: result.range.end.seconds,
                    speaker: nil, text: String(result.text.characters), words: nil)
                // docs/10 "Shared rules for the shells": the speaker under this segment, decided as it is
                // checkpointed — a checkpointed segment is never revisited.
                if !turns.isEmpty { segment = SpeakerTurns.shared.assign(segments: [segment], turns: turns, nearestSec: SpeakerTurns.shared.NEAREST_SEC)[0] }
                try await progress.checkpoint(segment: segment, completedThroughSec: result.range.end.seconds)
            }
        }
        defer {
            monitor.cancel()
            reader.cancel()
            lock.withLock { active = nil }
        }
        return try await withTaskCancellationHandler {
            do {
                let file = try AVAudioFile(forReading: URL(fileURLWithPath: request.path))
                guard let format = await SpeechAnalyzer.bestAvailableAudioFormat(compatibleWith: [module], considering: file.processingFormat) else {
                    throw SpeechFileError.unsupportedFormat
                }
                let source = try SpeechFileReader(file: file, format: format, startTime: request.startTimeSec)
                let inputs = AsyncThrowingStream<AnalyzerInput, Error>(unfolding: { try await source.next() })
                _ = try await analyzer.analyzeSequence(inputs)
                try Task.checkCancellation()
                try await analyzer.finalizeAndFinishThroughEndOfInput()
                try await reader.value
                let completed = !(await admission.paused)
                // The transcription that needed the turns is over; a later one separates afresh.
                if completed { try? FileManager.default.removeItem(at: SavedTurns.url(for: request)) }
                return LocalTranscriptionResult(segments: [], completed: completed)
            } catch {
                await analyzer.cancelAndFinishNow()
                if await admission.paused { return LocalTranscriptionResult(segments: [], completed: false) }
                throw error
            }
        } onCancel: {
            Task { await analyzer.cancelAndFinishNow() }
        }
    }

    /// docs/09 "On-device speaker separation" · §15 "Apple on-device speech model assets": the turns of the
    /// whole file, from 0 s, before the first segment is checkpointed — so every segment gets its speaker on
    /// the way out. Kept beside the input until the transcription completes, so a resumed run labels its
    /// segments with the same speakers as the segments already checkpointed. A failure costs the speakers,
    /// never the transcript; a cancel is a cancel.
    private func speakerTurns(_ request: LocalTranscriptionRequest) async throws -> [SpeakerTurn] {
        let speakers = request.expectedSpeakers.map { Int($0.int32Value) }
        let saved = SavedTurns.url(for: request)
        let bytes = (try? FileManager.default.attributesOfItem(atPath: request.path)[.size] as? Int) ?? -1
        if let data = try? Data(contentsOf: saved), let kept = try? JSONDecoder().decode(SavedTurns.self, from: data),
           kept.bytes == bytes, kept.speakers == speakers {
            return kept.turns.map { SpeakerTurn(start: $0.start, end: $0.end, label: $0.label) }
        }
        let task = Task { try await SpeakerSeparation.turns(of: URL(fileURLWithPath: request.path), speakers: speakers) }
        lock.withLock { separating = task }
        defer { lock.withLock { separating = nil } }
        do {
            let turns = try await withTaskCancellationHandler { try await task.value } onCancel: { task.cancel() }
            // A cancel that came as the separation finished is still a cancel.
            if task.isCancelled { throw CancellationError() }
            if let data = try? JSONEncoder().encode(SavedTurns(bytes: bytes, speakers: speakers, turns: turns)) {
                try? data.write(to: saved, options: .atomic)
            }
            return turns.map { SpeakerTurn(start: $0.start, end: $0.end, label: $0.label) }
        } catch {
            if error is CancellationError || task.isCancelled { throw CancellationError() }
            Self.logger.error("local.diarize.failed error=\(String(describing: error), privacy: .public)")
            return []
        }
    }

    private static let logger = Logger(subsystem: CoreBridge.appName, category: "transcribe")

    /// docs/05 "Fixed processing settings": transcribe unless the device is really hot. `.serious` is where Apple
    /// says the system itself cuts performance and apps should stop CPU work; `.fair` is routine while
    /// charging, and a user would never guess it is why nothing was transcribed. Low Power Mode does
    /// not hold it back either.
    private static var tooHot: Bool {
        ProcessInfo.processInfo.thermalState.rawValue >= ProcessInfo.ThermalState.serious.rawValue
    }

    /// docs/05 "Fixed processing settings": either answer that the assets are here is enough.
    /// `AssetInventory.status` went on saying `.supported` after the system had finished installing
    /// the Korean assets, while `installedLocales` already listed the locale; a locale that is only
    /// downloadable is in neither.
    private static func installed(_ module: SpeechTranscriber, _ locale: Locale) async -> Bool {
        if await AssetInventory.status(forModules: [module]) == .installed { return true }
        return await SpeechTranscriber.installedLocales.contains { $0.identifier == locale.identifier }
    }

    private func locale(_ language: String) async -> Locale? {
        await LocalSpeechEngine.speechLocale(language)
    }

    private func info(_ status: LocalEngineStatus, progress: Double? = nil, downloading: Bool = false) -> LocalEngineInfo {
        LocalEngineInfo(
            status: status, name: "apple-speech", revision: "speech-\(ProcessInfo.processInfo.operatingSystemVersionString)",
            supportsDiarization: status != .unsupported && SpeakerSeparation.available, supportsVocabulary: false, modelBytes: nil, progress: progress.map { KotlinDouble(double: $0) }, downloading: downloading
        )
    }
}

/// The speaker turns of one transcription input, beside it: the core joins the same parts into the same
/// bytes again on a resume (under a new modification time), so the size and the head count say whether
/// they are still this input's.
private struct SavedTurns: Codable {
    let bytes: Int
    let speakers: Int?
    let turns: [SpeakerSeparation.Turn]

    static func url(for request: LocalTranscriptionRequest) -> URL {
        let input = URL(fileURLWithPath: request.path)
        return input.deletingLastPathComponent().appendingPathComponent(".\(input.lastPathComponent).turns.json")
    }
}

@available(iOS 26, macOS 26, *)
private actor SpeechAdmission {
    private(set) var paused = false
    func pause() { paused = true }
}

private enum SpeechFileError: Error { case unsupportedFormat, conversion }

/// Pull-based input prevents a producer from decoding the entire recording into memory.
@available(iOS 26, macOS 26, *)
actor SpeechFileReader {
    let file: AVAudioFile
    let format: AVAudioFormat
    let converter: AVAudioConverter
    private var emitted: AVAudioFramePosition = 0
    /// The resume point as a frame of [format], so every buffer's start is a whole frame count.
    private let origin: AVAudioFramePosition
    private let totalFrames: AVAudioFramePosition

    init(file: AVAudioFile, format: AVAudioFormat, startTime: Double) throws {
        self.file = file
        self.format = format
        guard let converter = AVAudioConverter(from: file.processingFormat, to: format) else {
            throw SpeechFileError.unsupportedFormat
        }
        self.converter = converter
        file.framePosition = min(file.length, AVAudioFramePosition(max(0, startTime) * file.processingFormat.sampleRate))
        origin = AVAudioFramePosition((Double(file.framePosition) / file.processingFormat.sampleRate * format.sampleRate).rounded())
        totalFrames = AVAudioFramePosition((Double(file.length - file.framePosition) / file.processingFormat.sampleRate * format.sampleRate).rounded())
        converter.primeMethod = .none
    }

    func next() throws -> AnalyzerInput? {
        try Task.checkCancellation()
        guard emitted < totalFrames else { return nil }
        guard let output = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 16_384) else { throw SpeechFileError.conversion }
        var readError: Error?
        var conversionError: NSError?
        let status = converter.convert(to: output, error: &conversionError) { requested, state in
            if self.file.framePosition >= self.file.length { state.pointee = .endOfStream; return nil }
            let count = AVAudioFrameCount(min(Int64(min(requested, 16_384)), self.file.length - self.file.framePosition))
            guard let input = AVAudioPCMBuffer(pcmFormat: self.file.processingFormat, frameCapacity: count) else {
                state.pointee = .endOfStream; readError = SpeechFileError.conversion; return nil
            }
            do { try self.file.read(into: input, frameCount: count) }
            catch { state.pointee = .endOfStream; readError = error; return nil }
            state.pointee = .haveData
            return input
        }
        if let readError { throw readError }
        if let conversionError { throw conversionError }
        guard status != .error else { throw SpeechFileError.conversion }
        output.frameLength = min(output.frameLength, AVAudioFrameCount(totalFrames - emitted))
        guard output.frameLength > 0 else { return nil }
        // Counted in the buffer's own frames: the analyzer rejects a start in other units (seconds at
        // a 48 kHz timescale) as overlapping the previous buffer ten seconds into a 16 kHz recording.
        let start = CMTime(value: origin + emitted, timescale: CMTimeScale(format.sampleRate))
        emitted += AVAudioFramePosition(output.frameLength)
        return AnalyzerInput(buffer: output, bufferStartTime: start)
    }
}
#endif
