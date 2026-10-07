#if canImport(FluidAudio)
import CoreML
import FluidAudio
import Foundation

/// docs/09 "On-device speaker separation" · §15 "Apple on-device speech model assets": who speaks when in a
/// recording, worked out on this device by FluidAudio's offline diarizer (pyannote
/// speaker-diarization-community-1) from the Core ML models this target ships. Nothing is downloaded: the
/// models are loaded from the bundle by hand, so FluidAudio's own downloader is never reached, and its
/// offline switch is on besides.
public enum SpeakerSeparation {
    /// What the settings call the model — a name, not translated (docs/09 principle 4).
    public static let modelName = "pyannote community-1"

    /// One stretch of one speaker, in seconds of the file it was asked about. [label] is the
    /// diarizer's own (`S1`, `S2`, …); the core renames them in order of appearance.
    public struct Turn: Sendable, Equatable, Codable {
        public let start: Double
        public let end: Double
        public let label: String
    }

    /// Whether this build carries the models — the cheap answer the engine reports as
    /// `supportsDiarization`. A build made without `fetch-speaker-models.sh` has none.
    public static var available: Bool { directory != nil }

    /// The turns of the audio file at [url], from 0 s, or throws. [speakers] is the head count the
    /// user gave, which the clustering treats as a target rather than a guarantee; nil lets it decide.
    public static func turns(of url: URL, speakers: Int?) async throws -> [Turn] {
        ModelHub.offlineMode = true
        guard let directory else { throw SeparationError.noModels }
        var config = OfflineDiarizerConfig.default
        config.clustering.numSpeakers = speakers.flatMap { $0 > 0 ? $0 : nil }
        // One speaker at a time, so a transcript segment never has to choose between two turns.
        config.postProcessing.exclusiveSegments = true
        let manager = OfflineDiarizerManager(config: config)
        manager.initialize(models: try load(directory))
        let result = try await manager.process(url)
        return result.segments
            .map { Turn(start: Double($0.startTimeSeconds), end: Double($0.endTimeSeconds), label: $0.speakerId) }
            .sorted { $0.start < $1.start }
    }

    enum SeparationError: Error { case noModels, parameters }

    private static var directory: URL? {
        guard let models = Bundle.module.url(forResource: "Models", withExtension: nil) else { return nil }
        let folder = models.appendingPathComponent("speaker-diarization", isDirectory: true)
        return FileManager.default.fileExists(atPath: folder.appendingPathComponent("plda-parameters.json").path) ? folder : nil
    }

    /// The four models and the PLDA parameters, the way `OfflineDiarizerModels.load` reads them — but
    /// from the bundle, with no revision check and no network path behind it. The filter bank stays on
    /// the CPU, as FluidAudio keeps it.
    private static func load(_ directory: URL) throws -> OfflineDiarizerModels {
        let started = Date()
        func model(_ name: String, _ units: MLComputeUnits) throws -> MLModel {
            let configuration = MLModelConfiguration()
            configuration.computeUnits = units
            return try MLModel(contentsOf: directory.appendingPathComponent(name, isDirectory: true), configuration: configuration)
        }
        return OfflineDiarizerModels(
            segmentationModel: try model("Segmentation.mlmodelc", .all),
            fbankModel: try model("FBank.mlmodelc", .cpuOnly),
            embeddingModel: try model("Embedding.mlmodelc", .all),
            pldaRhoModel: try model("PldaRho.mlmodelc", .all),
            pldaPsi: try psi(directory.appendingPathComponent("plda-parameters.json")),
            compilationDuration: Date().timeIntervalSince(started)
        )
    }

    /// `tensors.psi.data_base64`: little-endian Float32s.
    private static func psi(_ url: URL) throws -> [Double] {
        let root = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any]
        guard let psi = (root?["tensors"] as? [String: Any])?["psi"] as? [String: Any],
              let base64 = psi["data_base64"] as? String,
              let data = Data(base64Encoded: base64, options: .ignoreUnknownCharacters),
              data.count >= MemoryLayout<Float>.size
        else { throw SeparationError.parameters }
        var floats = [Float](repeating: 0, count: data.count / MemoryLayout<Float>.size)
        _ = floats.withUnsafeMutableBytes { data.copyBytes(to: $0) }
        return floats.map(Double.init)
    }
}
#endif
