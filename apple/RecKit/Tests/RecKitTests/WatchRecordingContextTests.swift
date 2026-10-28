import Foundation
import XCTest
@testable import RecKit

/// docs/03 "Watch → phone transfer contract": the watch's recorder state as it rides to the iPhone — its application
/// context, and the same dictionary as a live message — and the whitelist the phone reads it through.
final class WatchRecordingContextTests: XCTestCase {
    private let recordingId = "01J9ABCDEFGHJKMNPQRSTVWXYZ"
    private let startedAt = Date(timeIntervalSince1970: 1_800_000_000)
    private let now = Date(timeIntervalSince1970: 1_800_000_600)

    func testARecordingRoundTrips() {
        let state = WatchRecordingState.recording(recordingId: recordingId, startedAt: startedAt)

        XCTAssertEqual(WatchRecordingContext.state(WatchRecordingContext.context(state), now: now), state)
        XCTAssertEqual(state.startedAt, startedAt)
    }

    /// The wire the contract names, which the phone of the other build reads: `startedAt` in seconds since 1970.
    func testTheWireIsTheRecordingIdAndSecondsSince1970() throws {
        let context = WatchRecordingContext.context(.recording(recordingId: recordingId, startedAt: startedAt))

        let body = try XCTUnwrap(context["recording"] as? [String: Any])
        XCTAssertEqual(Set(context.keys), ["recording"])
        XCTAssertEqual(body["recordingId"] as? String, recordingId)
        XCTAssertEqual(body["startedAt"] as? Double, 1_800_000_000)
    }

    /// Stopped is the absence of `recording` — so a watch whose context was never written reads as not recording.
    func testNotRecordingIsAContextWithoutRecording() {
        XCTAssertTrue(WatchRecordingContext.context(.idle).isEmpty)
        XCTAssertEqual(WatchRecordingContext.state([:], now: now), .idle)
        XCTAssertNil(WatchRecordingState.idle.startedAt)
    }

    /// Anything not shaped like the state is ignored, not read as a stop: the phone keeps what it has.
    func testAStateNotShapedLikeOneIsIgnored() {
        let shapes: [[String: Any]] = [
            ["recording": "yes"],
            ["recording": ["startedAt": 1_800_000_000.0]],
            ["recording": ["recordingId": recordingId]],
            ["recording": ["recordingId": recordingId, "startedAt": "1800000000"]],
            ["recording": ["recordingId": "../etc", "startedAt": 1_800_000_000.0]],
            ["recording": ["recordingId": "", "startedAt": 1_800_000_000.0]],
            ["recording": ["recordingId": recordingId, "startedAt": Double.nan]],
            ["recording": ["recordingId": recordingId, "startedAt": Double.infinity]],
        ]
        for shape in shapes {
            XCTAssertNil(WatchRecordingContext.state(shape, now: now), "\(shape)")
        }
    }

    /// A start before there could have been a Recly recording, or one further ahead of this clock than two paired
    /// clocks disagree by, is not a date this side draws a clock from.
    func testAStartThatIsNotASaneDateIsIgnored() {
        for seconds in [0, -1, 1_577_836_799, now.timeIntervalSince1970 + 24 * 3600 + 1] {
            let context: [String: Any] = ["recording": ["recordingId": recordingId, "startedAt": seconds]]
            XCTAssertNil(WatchRecordingContext.state(context, now: now), "\(seconds)")
        }
        let ahead: [String: Any] = ["recording": ["recordingId": recordingId, "startedAt": now.timeIntervalSince1970 + 60]]
        XCTAssertNotNil(WatchRecordingContext.state(ahead, now: now), "a minute of clock skew is still a start")
    }

    /// The wake is still a wake: it carries `wake` and nothing the phone reads, and only a dictionary shaped like the
    /// recorder's state — empty, or with `recording` — is read as one.
    func testAMessageIsTheStateOnlyWhenItIsShapedLikeIt() {
        XCTAssertFalse(WatchRecordingContext.isState(["wake": true]))
        XCTAssertTrue(WatchRecordingContext.isState(WatchRecordingContext.context(.idle)))
        XCTAssertTrue(
            WatchRecordingContext.isState(
                WatchRecordingContext.context(.recording(recordingId: recordingId, startedAt: startedAt))
            )
        )
    }
}
