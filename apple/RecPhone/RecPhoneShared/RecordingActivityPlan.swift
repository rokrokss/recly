import Foundation
import RecKit

/// What should be on the Lock Screen for what the recorder is doing (docs/13 "표시").
///
/// A decision of its own, apart from ActivityKit, because it is the part that can be checked: the
/// simulator will show a Live Activity but it will not tell a test what is on it.
enum RecordingActivityPlan: Equatable {
    /// Nothing to show: no recording, or one that is still opening the microphone — a pill with a
    /// clock that has not started is worse than no pill.
    case none
    case show(RecordingActivityAttributes.ContentState)

    static func plan(for state: RecorderState, startedAt: Date?) -> RecordingActivityPlan {
        guard case .recording = state, let startedAt else { return .none }
        return .show(RecordingActivityAttributes.ContentState(startedAt: startedAt))
    }

    /// docs/13 "8시간 상한이면 갱신": ActivityKit ends a Live Activity eight hours after it was
    /// requested, and a recording can outlast that. A new one is asked for before the cap rather
    /// than leaving a running recording with nothing on the Lock Screen.
    static let refreshAfterSec: TimeInterval = 7.5 * 3600

    static func needsRefresh(requestedAt: Date, now: Date) -> Bool {
        now.timeIntervalSince(requestedAt) >= refreshAfterSec
    }
}
