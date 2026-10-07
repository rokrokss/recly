import Foundation
import ReclyCore

/// One paragraph of the reader (docs/09 "Transcript reader"): consecutive segments of one speaker, cut where
/// the core's `TranscriptDocument` cuts its blocks — a new speaker, a minute of speech, or 1 200 characters —
/// but carrying the segments it is made of, which the speaker menu and the editor act on and the block does
/// not say.
public struct TranscriptGroup: Identifiable, Equatable, Sendable {
    public let id: Int
    public let start: Double
    public let speaker: String
    /// Indices into `transcript.segments`.
    public let segments: [Int]
    public let text: String

    /// Built once per transcript, never per playback tick.
    public static func make(_ transcript: Transcript) -> [TranscriptGroup] {
        var groups: [TranscriptGroup] = []
        var speaker: String?
        var start = 0.0
        var indices: [Int] = []
        var words = ""
        func flush() {
            if !words.isEmpty {
                groups.append(TranscriptGroup(id: groups.count, start: start, speaker: speaker ?? "", segments: indices, text: words))
            }
            indices = []
            words = ""
        }
        for (index, segment) in transcript.segments.enumerated() {
            let text = segment.text.trimmingCharacters(in: .whitespacesAndNewlines)
            if text.isEmpty { continue }
            if speaker != segment.speaker || segment.end - start > 60 || words.count + text.count > 1200 { flush() }
            if words.isEmpty {
                speaker = segment.speaker
                start = max(0, segment.start)
            } else {
                words += " "
            }
            words += text
            indices.append(index)
        }
        flush()
        return groups
    }

    /// The group playing at [sec]: the last one that has started.
    public static func active(_ groups: [TranscriptGroup], at sec: Double) -> TranscriptGroup? {
        groups.last { $0.start <= sec + 0.05 }
    }
}

/// docs/10 "Search" · docs/09 find bar: every place [query] occurs in the groups, in reading order — found
/// the way the core's search finds them, so the find bar counts what the search row promised.
public struct TranscriptMatch: Equatable, Sendable {
    public let group: Int
    /// In UTF-16 code units of the group's text, as the core counts.
    public let offset: Int
    public let length: Int

    public static func all(_ query: String, in groups: [TranscriptGroup]) -> [TranscriptMatch] {
        guard !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return [] }
        return groups.flatMap { group in
            findRanges(group.text, query).map { TranscriptMatch(group: group.id, offset: $0.offset, length: $0.length) }
        }
    }

    /// Stands in for the core's `findRanges(text, query)` until it lands: the same answer from Foundation's
    /// folding, in UTF-16 units.
    private static func findRanges(_ text: String, _ query: String) -> [(offset: Int, length: Int)] {
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines)
        var found: [(offset: Int, length: Int)] = []
        var from = text.startIndex
        while let range = text.range(of: needle, options: [.caseInsensitive, .diacriticInsensitive, .widthInsensitive], range: from ..< text.endIndex) {
            let offset = text.utf16.distance(from: text.startIndex, to: range.lowerBound)
            found.append((offset, text.utf16.distance(from: range.lowerBound, to: range.upperBound)))
            from = range.upperBound
        }
        return found
    }
}

extension Transcript {
    /// What a speaker id is written as: the name the user gave it, else the id (`S1`).
    public func label(of speaker: String) -> String {
        let name = speakers.first { $0.id == speaker }?.name?.trimmingCharacters(in: .whitespacesAndNewlines)
        return (name?.isEmpty == false ? name : nil) ?? speaker
    }

    /// Whether the user's own work is in it — an edit, or a speaker given a name — which a new
    /// transcription would replace (docs/10 "Re-transcription").
    public var hasEdits: Bool {
        editedAt != nil || speakers.contains { !($0.name ?? "").trimmingCharacters(in: .whitespaces).isEmpty }
    }
}

/// A spoken language as the settings name it — and the Transcribe again confirmation after them.
enum SpeechLanguageName {
    static func title(_ language: Language) -> String {
        if language == .auto { return RecKitStrings.localized("Automatic") }
        if language == .koEn { return RecKitStrings.localized("Korean and English") }
        let tag = TranscriptionLanguages.shared.localeTag(language: language)
        return Locale(identifier: tag).localizedString(forIdentifier: tag) ?? tag
    }
}
