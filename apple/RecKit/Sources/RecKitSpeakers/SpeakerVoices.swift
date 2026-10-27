#if canImport(FluidAudio)
import AVFoundation
import CoreML
import Foundation

/// docs/08 "Length · size limits" on the iPhone and Mac: the voices the core links a long recording's pieces by, from
/// the speaker-embedding half of the models [SpeakerSeparation] ships (§15 "Apple on-device speech model assets") —
/// the filter bank and the WeSpeaker ResNet the diarizer embeds its speakers with. FluidAudio 0.17.5 keeps the span
/// embedding it does with them (`OfflineEmbeddingExtractor.embedSpan`) internal, and its public extractor runs an
/// older model this build does not carry, so the two Core ML models run here the way `embedSpan` runs them: the span
/// at the start of a zero-filled window, pooled over the span's own frames. The models load the first time a voice
/// is asked for and are kept; the audio is not.
public final class SpeakerVoices: @unchecked Sendable {
    private let lock = NSLock()
    private var models: Models?

    /// The cosine two of these centroids must reach to be one person (`SpeakerEmbedder.linkThreshold`). Higher than
    /// the core's 0.6: on a real 133-minute recording of three briefings, one person's centroids over different audio
    /// came to 0.83–0.99, and different people's to as much as 0.70 — 0.77 where the provider had put two people under
    /// one label — so 0.6 would have joined different people.
    public static let linkThreshold: Float = 0.8

    public init() {}

    /// Whether this build carries the models, loading them the first time; throws when they are here but do not load.
    public func ready() throws -> Bool {
        try lock.withLock { try loaded() != nil }
    }

    /// The raw embedding (not unit length) of [start]–[end] seconds of the audio file at [url] — of its first ten
    /// seconds, when it is longer. Nil when less than a second of it can be read or the model gives nothing usable.
    public func embedding(of url: URL, from start: Double, to end: Double) throws -> [Float]? {
        try lock.withLock {
            guard let models = try loaded() else { throw VoicesError.noModels }
            let samples = try Self.samples(url, from: start, to: end, limit: models.windowSamples)
            // FluidAudio's community-1 setting: it embeds nothing shorter than a second.
            guard samples.count >= Int(Self.sampleRate) else { return nil }
            let embedding = try models.embed(samples)
            return embedding.allSatisfy(\.isFinite) && embedding.contains(where: { $0 != 0 }) ? embedding : nil
        }
    }

    /// The models, loaded the first time; nil when this build has none. Under [lock].
    private func loaded() throws -> Models? {
        if let models { return models }
        guard let directory = SpeakerSeparation.directory else { return nil }
        let loaded = try Models(directory)
        models = loaded
        return loaded
    }

    private static let sampleRate = 16_000.0

    /// [start]–[end] seconds of [url] as 16 kHz mono samples, at most [limit] of them. A part is 16 kHz mono already
    /// (ADR-006); one recorded at the 44.1 kHz fallback is converted.
    private static func samples(_ url: URL, from start: Double, to end: Double, limit: Int) throws -> [Float] {
        let file = try AVAudioFile(forReading: url)
        let source = file.processingFormat
        let first = AVAudioFramePosition((max(0, start) * source.sampleRate).rounded())
        let wanted = AVAudioFramePosition((Double(limit) * source.sampleRate / sampleRate).rounded(.up))
        let count = min(file.length, AVAudioFramePosition((end * source.sampleRate).rounded()), first + wanted) - first
        guard count > 0 else { return [] }
        guard let read = AVAudioPCMBuffer(pcmFormat: source, frameCapacity: AVAudioFrameCount(count)) else { throw VoicesError.audio }
        file.framePosition = first
        try file.read(into: read, frameCount: AVAudioFrameCount(count))
        let mono = source.sampleRate == sampleRate && source.channelCount == 1 ? read : try converted(read)
        guard let channel = mono.floatChannelData?[0] else { throw VoicesError.audio }
        return Array(UnsafeBufferPointer(start: channel, count: min(Int(mono.frameLength), limit)))
    }

    private static func converted(_ input: AVAudioPCMBuffer) throws -> AVAudioPCMBuffer {
        guard let target = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: sampleRate, channels: 1, interleaved: false),
              let converter = AVAudioConverter(from: input.format, to: target),
              let output = AVAudioPCMBuffer(
                  pcmFormat: target,
                  frameCapacity: AVAudioFrameCount((Double(input.frameLength) * sampleRate / input.format.sampleRate).rounded(.up)) + 1
              )
        else { throw VoicesError.audio }
        converter.downmix = true
        var given = false
        var error: NSError?
        let status = converter.convert(to: output, error: &error) { _, state in
            if given { state.pointee = .endOfStream; return nil }
            given = true
            state.pointee = .haveData
            return input
        }
        if let error { throw error }
        guard status != .error else { throw VoicesError.audio }
        return output
    }

    enum VoicesError: Error { case noModels, models, audio }

    /// The filter bank (on the CPU, as FluidAudio keeps it) and the embedding model, with the input shapes they
    /// declare: ten seconds of audio, and one pooling weight per frame of them.
    private struct Models {
        let fbank: MLModel
        let embedding: MLModel
        let audioShape: [NSNumber]
        let weightsShape: [NSNumber]

        var windowSamples: Int { audioShape.last?.intValue ?? 0 }

        init(_ directory: URL) throws {
            func model(_ name: String, _ units: MLComputeUnits) throws -> MLModel {
                let configuration = MLModelConfiguration()
                configuration.computeUnits = units
                return try MLModel(contentsOf: directory.appendingPathComponent(name, isDirectory: true), configuration: configuration)
            }
            fbank = try model("FBank.mlmodelc", .cpuOnly)
            embedding = try model("Embedding.mlmodelc", .all)
            guard let audio = fbank.modelDescription.inputDescriptionsByName["audio"]?.multiArrayConstraint?.shape,
                  let weights = embedding.modelDescription.inputDescriptionsByName["weights"]?.multiArrayConstraint?.shape,
                  (audio.last?.intValue ?? 0) > 0, (weights.last?.intValue ?? 0) > 0
            else { throw VoicesError.models }
            audioShape = audio
            weightsShape = weights
        }

        /// [samples] (at most [windowSamples]) at the start of the window, the rest zero, and every frame they
        /// cover weighted one — so the zeros after them count for nothing.
        func embed(_ samples: [Float]) throws -> [Float] {
            let audio = try MLMultiArray(shape: audioShape, dataType: .float32)
            audio.withUnsafeMutableBufferPointer(ofType: Float.self) { buffer, _ in
                buffer.update(repeating: 0)
                for i in 0 ..< min(samples.count, buffer.count) { buffer[i] = samples[i] }
            }
            guard let features = try fbank.prediction(from: MLDictionaryFeatureProvider(dictionary: ["audio": MLFeatureValue(multiArray: audio)]))
                .featureValue(for: "fbank_features")?.multiArrayValue
            else { throw VoicesError.models }
            let frames = weightsShape.last?.intValue ?? 0
            let active = max(1, min(frames, Int((Double(samples.count) / Double(windowSamples) * Double(frames)).rounded())))
            let weights = try MLMultiArray(shape: weightsShape, dataType: .float32)
            weights.withUnsafeMutableBufferPointer(ofType: Float.self) { buffer, _ in
                buffer.update(repeating: 0)
                for i in 0 ..< min(active, buffer.count) { buffer[i] = 1 }
            }
            guard let output = try embedding.prediction(from: MLDictionaryFeatureProvider(dictionary: [
                "fbank_features": MLFeatureValue(multiArray: features),
                "weights": MLFeatureValue(multiArray: weights),
            ])).featureValue(for: "embedding")?.multiArrayValue
            else { throw VoicesError.models }
            return (0 ..< output.count).map { output[$0].floatValue }
        }
    }
}
#endif
