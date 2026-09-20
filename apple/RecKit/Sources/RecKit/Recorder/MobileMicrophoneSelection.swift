import Foundation

/// A stable external input wins over the built-in microphone. Within a class, retain the current
/// input to avoid bouncing between two headsets. A newly attached wired/USB input wins over radio.
struct MobileMicrophoneSelection {
    struct Input: Equatable {
        let id: String
        let priority: Int
    }

    static func choose(_ inputs: [Input], current: String?) -> String? {
        guard let best = inputs.map(\.priority).min() else { return nil }
        let candidates = inputs.filter { $0.priority == best }
        if let current, candidates.contains(where: { $0.id == current }) { return current }
        return candidates.sorted { $0.id < $1.id }.first?.id
    }
}
