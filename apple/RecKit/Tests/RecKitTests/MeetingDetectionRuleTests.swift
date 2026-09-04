#if os(macOS)
import XCTest
@testable import RecKit

/// M4-L5 deliverable 6: the detection rule as pure logic — mic in use × running app × cooldown.
///
/// The two monitors it reads from are Core Audio and the window server, neither of which a test can
/// stage; the decision they feed is entirely here, and it is the part that can be wrong in a way the
/// user notices (a notification every two seconds, or none at all).
final class MeetingDetectionRuleTests: XCTestCase {
    private let start = Date(timeIntervalSince1970: 1_800_000_000)

    private func signals(
        mic: Bool = false,
        app: String? = nil,
        recording: Bool = false
    ) -> MeetingDetectionRule.Signals {
        MeetingDetectionRule.Signals(micInUse: mic, meetingApp: app, isRecording: recording)
    }

    // MARK: - The invitation to record

    /// Either signal alone is somebody working, not somebody in a meeting: a voice memo in another
    /// app, or Slack sitting in the dock all day.
    func testNeitherSignalAloneOffersARecording() {
        var rule = MeetingDetectionRule()

        XCTAssertNil(rule.evaluate(signals(mic: true), now: start))
        XCTAssertNil(rule.evaluate(signals(app: "us.zoom.xos"), now: start))
    }

    /// ADR-011: detect → confirm → record. Both signals together is the offer, and docs/20 M4 says it is
    /// made once — the tick two seconds later must not make it again.
    func testBothSignalsOfferARecordingExactlyOnce() {
        var rule = MeetingDetectionRule()

        XCTAssertEqual(rule.evaluate(signals(mic: true, app: "us.zoom.xos"), now: start), .start)
        XCTAssertNil(rule.evaluate(signals(mic: true, app: "us.zoom.xos"), now: start.addingTimeInterval(2)))
        XCTAssertNil(rule.evaluate(signals(mic: true, app: "us.zoom.xos"), now: start.addingTimeInterval(120)))
    }

    /// The meeting ends and another begins. The signal going away is what re-arms the offer — but
    /// not before the cooldown is up, so a microphone that flickers cannot spam the user.
    func testASecondMeetingIsOfferedOnlyAfterTheCooldown() {
        var rule = MeetingDetectionRule()
        XCTAssertEqual(rule.evaluate(signals(mic: true, app: "us.zoom.xos"), now: start), .start)

        let leftEarly = start.addingTimeInterval(60)
        XCTAssertNil(rule.evaluate(signals(), now: leftEarly))
        XCTAssertNil(
            rule.evaluate(signals(mic: true, app: "us.zoom.xos"), now: leftEarly.addingTimeInterval(30)),
            "within the cooldown"
        )

        let later = start.addingTimeInterval(MeetingDetectionRule.cooldownSec + 1)
        XCTAssertEqual(rule.evaluate(signals(mic: true, app: "us.zoom.xos"), now: later), .start)
    }

    /// A recording the user stopped by hand while still in the meeting is a decision, not an
    /// oversight: offering it straight back is the app arguing with them.
    func testARecordingStoppedByHandIsNotOfferedBackWhileTheMeetingRuns() {
        var rule = MeetingDetectionRule()
        XCTAssertNil(rule.evaluate(signals(mic: true, app: "us.zoom.xos", recording: true), now: start))

        let stopped = start.addingTimeInterval(MeetingDetectionRule.cooldownSec + 1)
        XCTAssertNil(rule.evaluate(signals(mic: true, app: "us.zoom.xos"), now: stopped))
    }

    // MARK: - The offer to end it

    /// docs/12 "종료 감지": the microphone unused for 60 seconds straight — and only then. A meeting
    /// that goes quiet for fifty seconds is a meeting.
    func testAnIdleMicrophoneOffersToEndTheRecordingOnlyAfterSixtySeconds() {
        var rule = MeetingDetectionRule()
        _ = rule.evaluate(signals(mic: true, recording: true), now: start)

        let idleFrom = start.addingTimeInterval(10)
        XCTAssertNil(rule.evaluate(signals(recording: true), now: idleFrom))
        XCTAssertNil(
            rule.evaluate(signals(recording: true), now: idleFrom.addingTimeInterval(MeetingDetectionRule.micIdleSec - 1))
        )
        XCTAssertEqual(
            rule.evaluate(signals(recording: true), now: idleFrom.addingTimeInterval(MeetingDetectionRule.micIdleSec)),
            .stop
        )
    }

    /// The offer is made once. It is never a stop (docs/12: never an automatic stop), so the recording is
    /// still running afterwards and the same idle microphone is still being read.
    func testTheOfferToEndIsMadeOnceAndTheClockRestartsWhenTheMicrophoneComesBack() {
        var rule = MeetingDetectionRule()
        let idleFrom = start
        _ = rule.evaluate(signals(recording: true), now: idleFrom)
        XCTAssertEqual(
            rule.evaluate(signals(recording: true), now: idleFrom.addingTimeInterval(60)),
            .stop
        )
        XCTAssertNil(rule.evaluate(signals(recording: true), now: idleFrom.addingTimeInterval(200)))

        // Someone speaks again, then the meeting really ends.
        XCTAssertNil(rule.evaluate(signals(mic: true, recording: true), now: idleFrom.addingTimeInterval(210)))
        XCTAssertNil(rule.evaluate(signals(recording: true), now: idleFrom.addingTimeInterval(220)))
        XCTAssertEqual(
            rule.evaluate(signals(recording: true), now: idleFrom.addingTimeInterval(280)),
            .stop
        )
    }

    /// The end offer belongs to the recording, not to the app: nothing is offered once it is over.
    func testAnIdleMicrophoneWithNoRecordingOffersNothing() {
        var rule = MeetingDetectionRule()

        XCTAssertNil(rule.evaluate(signals(), now: start))
        XCTAssertNil(rule.evaluate(signals(), now: start.addingTimeInterval(600)))
    }
}
#endif
