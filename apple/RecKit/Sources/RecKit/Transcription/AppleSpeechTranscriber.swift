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

/// One native long-file session, with final-segment checkpoints and bounded PCM buffering.
@available(iOS 26, macOS 26, *)
private final class AppleSpeechTranscriber: LocalTranscriptionEngine, @unchecked Sendable {
    private let lock = NSLock()
    private var active: SpeechAnalyzer?

    func cancel() {
        let analyzer = lock.withLock { active }
        if let analyzer { Task { await analyzer.cancelAndFinishNow() } }
    }

    func __status(language: String) async throws -> LocalEngineInfo {
        guard SpeechTranscriber.isAvailable, let locale = await locale(language) else { return info(.unsupported) }
        let module = SpeechTranscriber(locale: locale, preset: .transcription)
        guard await AssetInventory.status(forModules: [module]) == .installed else { return info(.modelRequired) }
        guard ProcessInfo.processInfo.thermalState == .nominal, !ProcessInfo.processInfo.isLowPowerModeEnabled else { return info(.waiting) }
        return info(.ready)
    }

    func __prepare(language: String) async throws -> LocalEngineInfo {
        guard SpeechTranscriber.isAvailable, let locale = await locale(language) else { return info(.unsupported) }
        guard ProcessInfo.processInfo.thermalState == .nominal, !ProcessInfo.processInfo.isLowPowerModeEnabled else {
            // Keep the preparation action available if admission was denied before installation.
            return try await __status(language: language)
        }
        let module = SpeechTranscriber(locale: locale, preset: .transcription)
        if let request = try await AssetInventory.assetInstallationRequest(supporting: [module]) {
            try await request.downloadAndInstall()
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
        let module = SpeechTranscriber(locale: locale, preset: .transcription)
        let analyzer = SpeechAnalyzer(modules: [module], options: .init(priority: .utility, modelRetention: .whileInUse))
        lock.withLock { active = analyzer }
        let admission = SpeechAdmission()
        let monitor = Task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                if ProcessInfo.processInfo.thermalState != .nominal || ProcessInfo.processInfo.isLowPowerModeEnabled {
                    await admission.pause()
                    await analyzer.cancelAndFinishNow()
                    return
                }
            }
        }
        let reader = Task {
            for try await result in module.results where result.isFinal {
                try Task.checkCancellation()
                let segment = SttSegment(start: result.range.start.seconds, end: result.range.end.seconds,
                    speaker: nil, text: String(result.text.characters), words: nil)
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
                return LocalTranscriptionResult(segments: [], completed: !(await admission.paused))
            } catch {
                await analyzer.cancelAndFinishNow()
                if await admission.paused { return LocalTranscriptionResult(segments: [], completed: false) }
                throw error
            }
        } onCancel: {
            Task { await analyzer.cancelAndFinishNow() }
        }
    }

    private func locale(_ language: String) async -> Locale? {
        await LocalSpeechEngine.speechLocale(language)
    }

    private func info(_ status: LocalEngineStatus) -> LocalEngineInfo {
        LocalEngineInfo(status: status, name: "apple-speech", revision: "speech-\(ProcessInfo.processInfo.operatingSystemVersionString)", supportsDiarization: false)
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
    private let origin: Double
    private let totalFrames: AVAudioFramePosition

    init(file: AVAudioFile, format: AVAudioFormat, startTime: Double) throws {
        self.file = file
        self.format = format
        guard let converter = AVAudioConverter(from: file.processingFormat, to: format) else {
            throw SpeechFileError.unsupportedFormat
        }
        self.converter = converter
        file.framePosition = min(file.length, AVAudioFramePosition(max(0, startTime) * file.processingFormat.sampleRate))
        origin = Double(file.framePosition) / file.processingFormat.sampleRate
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
        let start = origin + Double(emitted) / format.sampleRate
        emitted += AVAudioFramePosition(output.frameLength)
        return AnalyzerInput(buffer: output, bufferStartTime: CMTime(seconds: start, preferredTimescale: 48_000))
    }
}
#endif
