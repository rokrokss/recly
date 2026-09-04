import Foundation
import ReclyCore

/// docs/11 A8's two Data Layer paths, as the `WCSession.transferFile` metadata dictionary the Apple
/// link carries instead (docs/13 "Apple Watch" 전송): the same five fields for a part, the recording
/// id alone for the meta. The Android `TransferPath` is the same grammar and the same checks, and
/// both sides of this one link this file for the same reason it named itself a grammar there — a
/// format two builds could disagree about is not a format.
///
/// Everything here arrives from another device, so [parse] is a whitelist rather than a read: the
/// `recordingId` becomes a directory name under `dataDir/recordings` and a `..` in it would be a
/// write anywhere on the phone. Anything that does not match exactly is refused, and there is no
/// partial understanding of a metadata dictionary.
public enum TransferMetadata: Equatable {
    case part(PartMetadata)
    case meta(recordingId: String)

    /// `nil` for anything that is not exactly one of the two shapes.
    public static func parse(_ metadata: [String: Any]) -> TransferMetadata? {
        guard let recordingId = metadata[Key.recordingId] as? String, isId(recordingId) else {
            return nil
        }
        // The meta carries the id and nothing else, so the presence of a part number is what tells
        // the two apart — as the `/rec/part/` and `/rec/meta/` prefixes do on Android.
        guard metadata[Key.part] != nil else { return .meta(recordingId: recordingId) }
        guard let part = PartMetadata(recordingId: recordingId, metadata: metadata) else {
            return nil
        }
        return .part(part)
    }

    public var dictionary: [String: Any] {
        switch self {
        case .part(let part): return part.dictionary
        case .meta(let recordingId): return [Key.recordingId: recordingId]
        }
    }

    public var recordingId: String {
        switch self {
        case .part(let part): return part.recordingId
        case .meta(let recordingId): return recordingId
        }
    }

    /// What two transfers of the same thing share, so a resend can tell whether `WCSession` is
    /// already carrying this file (see `WatchTransferQueue.pump`).
    public var key: String {
        switch self {
        case .part(let part): return "\(part.recordingId)/\(part.part)/\(part.track.wire)"
        case .meta(let recordingId): return "\(recordingId)/meta"
        }
    }

    public enum Key {
        public static let recordingId = "recordingId"
        public static let part = "part"
        public static let track = "track"
        public static let sha256 = "sha256"
        public static let file = "file"
    }

    /// A ULID is 26 Crockford base32 characters, but the id is the watch's and this is a safety
    /// check, not a format check: what matters is that it cannot escape a directory or collide with
    /// one. Length-bounded, and no character that means anything to a file system.
    static func isId(_ value: String) -> Bool {
        (1 ... 64).contains(value.count)
            && value.allSatisfy { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }
    }
}

/// One part file on the wire. [file] is the name the watch wrote the part under —
/// `{base}_pNNN_{track}.m4a` (docs/03 "이름 규칙") — where `{base}` comes from `startedAt` and so is
/// not knowable on the receiving side until the meta arrives, last. It travels with the part
/// precisely so the phone never has to rename: `acceptPart` files it under that name, which is the
/// one the meta will ask for.
public struct PartMetadata: Equatable {
    public let recordingId: String
    public let part: Int
    public let track: Track
    public let sha256: String
    public let file: String

    public init(recordingId: String, part: Int, track: Track, sha256: String, file: String) {
        self.recordingId = recordingId
        self.part = part
        self.track = track
        self.sha256 = sha256
        self.file = file
    }

    /// The whitelist. The name becomes a file in the recording directory, so it is checked against
    /// the schema's own pattern (spec/recording.meta.schema.json, `parts[].file`) — which admits no
    /// separator and no dot beyond the extension — and then against the rest of the dictionary: a
    /// name that disagrees with the `part`/`track` it travelled with describes two different files,
    /// and this side has no way to tell which one it is holding.
    init?(recordingId: String, metadata: [String: Any]) {
        guard let part = (metadata[TransferMetadata.Key.part] as? NSNumber)?.intValue, part > 0,
              let wire = metadata[TransferMetadata.Key.track] as? String,
              let track = Track.named(wire),
              let sha256 = metadata[TransferMetadata.Key.sha256] as? String, Self.isSha256(sha256),
              let file = metadata[TransferMetadata.Key.file] as? String,
              let named = Self.pattern.firstMatch(in: file, range: NSRange(file.startIndex..., in: file)),
              named.range.length == file.utf16.count,
              Self.group(1, of: named, in: file) == String(format: "%03d", part),
              Self.group(2, of: named, in: file) == wire
        else { return nil }
        self.init(recordingId: recordingId, part: part, track: track, sha256: sha256, file: file)
    }

    public var dictionary: [String: Any] {
        [
            TransferMetadata.Key.recordingId: recordingId,
            TransferMetadata.Key.part: part,
            TransferMetadata.Key.track: track.wire,
            TransferMetadata.Key.sha256: sha256,
            TransferMetadata.Key.file: file,
        ]
    }

    /// `parts[].file` of spec/recording.meta.schema.json, with `pNNN` and the track captured.
    private static let pattern = try! NSRegularExpression(
        pattern: "[0-9]{8}T[0-9]{6}Z_(?:watch|phone|desktop)_[0-9A-Z]{8}_p([0-9]{3})_(mono|mic|sys|mix)\\.m4a"
    )

    private static func group(_ index: Int, of match: NSTextCheckingResult, in file: String) -> String? {
        Swift.Range(match.range(at: index), in: file).map { String(file[$0]) }
    }

    private static func isSha256(_ value: String) -> Bool {
        value.count == 64 && value.allSatisfy(\.isHexDigit)
    }
}

public extension Track {
    /// The `@SerialName`s of `recly.core.model.Track` (docs/03: `mono`, `mic`, `sys`, `mix`).
    /// `Track` comes across the Obj-C bridge as a class whose `name` is the Kotlin constant
    /// (`MONO`), so the serialised one is derived rather than read.
    var wire: String { name.lowercased() }

    /// `nil` for anything that is not one of the four.
    static func named(_ wire: String) -> Track? {
        [Track.mono, Track.mic, Track.sys, Track.mix].first { $0.wire == wire }
    }
}
