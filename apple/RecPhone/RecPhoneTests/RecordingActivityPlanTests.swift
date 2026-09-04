import RecKit
import XCTest

/// docs/13 "표시": what is on the Lock Screen, decided from what the recorder is doing. The pill is
/// the only thing that tells a user with a locked phone that Recly is still recording (and App
/// Review 2.5.14 asks for it), so the mapping is checked rather than trusted to the screen.
final class RecordingActivityPlanTests: XCTestCase {
    private let startedAt = Date(timeIntervalSince1970: 1_800_000_000)

    func testARunningRecordingIsShownCountingFromItsStart() {
        let plan = RecordingActivityPlan.plan(
            for: .recording(recordingId: "01J9", workflowId: "w1"), startedAt: startedAt
        )

        XCTAssertEqual(plan, .show(RecordingActivityAttributes.ContentState(startedAt: startedAt)))
    }

    /// Nothing is shown until there is a recording. `starting` is the microphone opening — a pill
    /// with a clock that has not started is worse than no pill — and `stopping` is on its way out.
    func testNothingIsShownWhenThereIsNoRecording() {
        for state in [RecorderState.idle, .starting, .stopping] {
            XCTAssertEqual(
                RecordingActivityPlan.plan(for: state, startedAt: startedAt), .none, "\(state)"
            )
        }
    }

    /// The recording is running but the model has no start time for it — a state nothing should
    /// produce, and one that would put a Live Activity counting from 1970 on the Lock Screen.
    func testARecordingWithNoStartTimeIsNotShown() {
        XCTAssertEqual(
            RecordingActivityPlan.plan(
                for: .recording(recordingId: "01J9", workflowId: nil), startedAt: nil
            ),
            .none
        )
    }

    /// docs/13 "8시간 상한이면 갱신": ActivityKit takes the activity away eight hours after it was
    /// requested, so a new one is asked for before that — and not a moment sooner, because every
    /// hand-over is a flicker on the Lock Screen.
    func testTheActivityIsRefreshedBeforeTheEightHourCap() {
        let requestedAt = Date(timeIntervalSince1970: 0)

        XCTAssertFalse(
            RecordingActivityPlan.needsRefresh(
                requestedAt: requestedAt, now: requestedAt.addingTimeInterval(7 * 3600)
            )
        )
        XCTAssertTrue(
            RecordingActivityPlan.needsRefresh(
                requestedAt: requestedAt,
                now: requestedAt.addingTimeInterval(RecordingActivityPlan.refreshAfterSec)
            )
        )
        XCTAssertLessThan(RecordingActivityPlan.refreshAfterSec, 8 * 3600, "before the cap, not at it")
    }

    /// docs/07 rule 3 put the app's language in the *state*, and ActivityKit hands this build the
    /// state the previous one stored — an update under a running recording. A decoder that insisted
    /// on the new key would throw and take the pill off the Lock Screen of a recording still going.
    func testAStateStoredBeforeThereWasALanguageStillDecodes() throws {
        let stored = Data(#"{"startedAt":1800000000}"#.utf8)

        let state = try JSONDecoder().decode(
            RecordingActivityAttributes.ContentState.self, from: stored
        )

        XCTAssertEqual(state.startedAt, Date(timeIntervalSinceReferenceDate: 1_800_000_000))
        XCTAssertEqual(state.language, "", "a state with no language means the device's own")
        XCTAssertEqual(state.appLocale, .current)
    }
}
