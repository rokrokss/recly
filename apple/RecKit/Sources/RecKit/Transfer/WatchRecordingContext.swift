import Foundation

/// What the Apple Watch's recorder is doing, as the iPhone draws it (docs/03 "Watch → phone transfer contract").
public enum WatchRecordingState: Equatable, Sendable {
    case idle
    case recording(recordingId: String, startedAt: Date)

    /// When the recording began, while there is one.
    public var startedAt: Date? {
        guard case .recording(_, let startedAt) = self else { return nil }
        return startedAt
    }
}

/// docs/03 "Watch → phone transfer contract": the watch's own `updateApplicationContext` payload, and the same
/// dictionary as a live message while the phone is reachable — a context does not wake the iPhone app. It is
/// apart from the parts because the first part only comes a segment (15 minutes) in.
///
/// Like [WatchContext] it is a *replacing* snapshot: the phone keeps the newest one and draws it, and none of
/// it reaches the core or Drive (docs/15 §4). Linked by both apps and by neither extension.
public enum WatchRecordingContext {
    public static let recordingKey = "recording"
    static let startedAtKey = "startedAt"

    /// The earliest start a recording can claim. The upper bound is the reading device's clock plus [skew].
    static let earliest = Date(timeIntervalSince1970: 1_577_836_800) // 2020-01-01T00:00:00Z
    /// A day of slack ahead of the phone's clock: far more than two paired clocks disagree by, and short of a
    /// start the widget would count towards instead of from.
    static let skew: TimeInterval = 24 * 3600

    /// `{recording: {recordingId, startedAt}}` while the watch records, `startedAt` in seconds since 1970 — and
    /// nothing at all once it stops: a context without `recording` is "not recording".
    public static func context(_ state: WatchRecordingState) -> [String: Any] {
        guard case .recording(let recordingId, let startedAt) = state else { return [:] }
        return [
            recordingKey: [
                TransferMetadata.Key.recordingId: recordingId,
                startedAtKey: startedAt.timeIntervalSince1970,
            ] as [String: Any],
        ]
    }

    /// `nil` for a dictionary that is not shaped like one, which the phone ignores and keeps what it has. A
    /// whitelist, as every dictionary the two sides trade is: an id the transfer would take, a start that is
    /// a date.
    public static func state(_ context: [String: Any], now: Date = Date()) -> WatchRecordingState? {
        guard let value = context[recordingKey] else { return .idle }
        guard let body = value as? [String: Any],
              let recordingId = body[TransferMetadata.Key.recordingId] as? String,
              TransferMetadata.isId(recordingId),
              let seconds = (body[startedAtKey] as? NSNumber)?.doubleValue,
              seconds.isFinite
        else { return nil }
        let startedAt = Date(timeIntervalSince1970: seconds)
        guard startedAt >= earliest, startedAt <= now.addingTimeInterval(skew) else { return nil }
        return .recording(recordingId: recordingId, startedAt: startedAt)
    }

    /// A live message from the watch is its recorder's state when it is shaped like one — empty, or carrying
    /// `recording` — and the wake otherwise (docs/13 "Apple Watch" Transfer), whose content the phone never reads.
    public static func isState(_ message: [String: Any]) -> Bool {
        message.isEmpty || message[recordingKey] != nil
    }
}
