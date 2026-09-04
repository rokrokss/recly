import Foundation

/// What the recording captures, picked in the menu before it starts (docs/12 M4-L3 "메뉴바").
///
/// It decides the track set, and the track set is written into the meta at `start`, so it cannot
/// change once a recording is running: a `sys` track that begins at part 4 has no honest
/// `startOffsetSec`.
public enum RecordingMode: Sendable {
    /// The microphone alone, as one `mono` track — M4-L2's recording, unchanged.
    case microphone

    /// docs/03 desktop: `mic`, `sys` and `mix`, sharing one start time and one segment boundary.
    case meeting
}

/// What [SegmentedRecorder.stop] hands back once the recording is on disk and finalized.
public struct RecordingOutcome: Sendable {
    public let recordingId: String
    public let durationSec: Double
    public let parts: Int
}

/// The result of the core-only half of a stop (the Android `StopResult`, same three cases).
public enum StopResult: Sendable {
    /// A second stop: the menu item and a fatal capture error can both land.
    case notRecording

    case finalized(RecordingOutcome)

    /// Audio is on disk that could not be filed, so the meta is deliberately left open: a row that
    /// says `finalized` is a row nothing goes looking at again, and the missing part would be
    /// uploaded away. The next `RecordingRecovery` pass finishes the job.
    case deferred(recordingId: String, pending: Int)
}

/// Capture failures, surfaced instead of thrown at whatever thread the audio callback used.
///
/// `fatal` separates "the capture is over" from "one segment could not be filed": the second must
/// not cost the user the rest of a three-hour recording, so it is reported and the engine keeps
/// running (the part is picked up by `RecordingRecovery`).
public struct RecorderError: Error, CustomStringConvertible {
    public enum Kind: Sendable {
        /// TCC said no. The shell answers this one with the System Settings deep link, not an
        /// error message — nothing else the user does in the app can fix it.
        case microphoneDenied

        /// The process tap could not be built: TCC refused it, or Core Audio would not give up an
        /// aggregate device. There is no API to ask before trying (docs/12 "권한"), so this is the
        /// answer — and the shell's answer to it is the deep link plus the offer to record the
        /// microphone alone (M4-L3 deliverable 1).
        case systemAudioUnavailable

        case capture
    }

    public let kind: Kind
    public let message: String
    public let fatal: Bool
    public let underlying: Error?

    public init(_ message: String, kind: Kind = .capture, fatal: Bool = true, underlying: Error? = nil) {
        self.kind = kind
        self.message = message
        self.fatal = fatal
        self.underlying = underlying
    }

    public var description: String {
        underlying.map { "\(message): \($0)" } ?? message
    }
}
