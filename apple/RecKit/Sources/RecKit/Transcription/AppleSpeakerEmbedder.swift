#if os(iOS) || os(macOS)
import Foundation
import RecKitSpeakers
import ReclyCore

/// docs/08 "Length · size limits" on the iPhone and Mac: the voices the core links a long recording's pieces by,
/// from the bundled speaker-embedding model ([SpeakerVoices]) — null in a build without it. Each key's centroid is
/// worked out from its spans of the part files and handed back; nothing is kept.
final class AppleSpeakerEmbedder: SpeakerEmbedder {
    private let voices = SpeakerVoices()

    var linkThreshold: Float { SpeakerVoices.linkThreshold }

    func __centroids(spans: [String: [AudioSpan]]) async throws -> [String: KotlinFloatArray]? {
        guard try voices.ready() else { return nil }
        var centroids: [String: KotlinFloatArray] = [:]
        for (key, list) in spans {
            var embeddings: [KotlinFloatArray] = []
            for span in list {
                try Task.checkCancellation()
                let url = URL(fileURLWithPath: span.file.description(), isDirectory: false)
                guard let embedding = try voices.embedding(of: url, from: span.startSec, to: span.endSec) else { continue }
                let array = KotlinFloatArray(size: Int32(embedding.count))
                for (index, value) in embedding.enumerated() { array.set(index: Int32(index), value: value) }
                embeddings.append(array)
            }
            if let centroid = SpeakerTurns.shared.centroid(embeddings: embeddings) { centroids[key] = centroid }
        }
        return centroids
    }
}
#endif
