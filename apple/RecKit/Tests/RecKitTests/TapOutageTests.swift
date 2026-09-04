#if os(macOS)
import XCTest
@testable import RecKit

/// `ProcessTapCapture`'s outage bookkeeping, which is the only part of it an automated run can
/// reach: everything else is Core Audio and the "시스템 오디오 녹음" prompt. What it decides is what
/// the meta's `gaps` say about audio nobody recorded (docs/12 "tap 재생성").
final class TapOutageTests: XCTestCase {
    /// The watchdog's outage did not begin when the watchdog noticed. Ten seconds of silence have
    /// to pass before it can be sure the tap is dead, and that silence is missing from the
    /// recording exactly as the rebuild that follows it is — so the gap has to cover both.
    func testAWatchdogsOutageBeginsWhenTheAudioStoppedAndNotWhenItWasNoticed() {
        let noticed = Date()
        var outage = TapOutage()

        outage.begin(reason: "system_tap_silent", since: noticed - ProcessTapCapture.silenceTimeoutSec)
        let closed = outage.end(now: noticed + 2)

        XCTAssertEqual(closed?.reason, "system_tap_silent")
        XCTAssertEqual(
            closed?.seconds ?? 0, ProcessTapCapture.silenceTimeoutSec + 2, accuracy: 0.01,
            "the silence the watchdog waited through is part of the hole"
        )
    }

    /// One outage however many attempts it takes to come back. A rebuild that failed is retried
    /// every two seconds, and a `gaps` entry every two seconds says nothing the one entry does not
    /// — the first reason and the first instant are the ones that survive.
    func testRebuildAttemptsDoNotEachOpenAnOutageOfTheirOwn() {
        let began = Date()
        var outage = TapOutage()

        outage.begin(reason: "output_device_change", since: began)
        outage.begin(reason: "system_tap_rebuild", since: began + 2)
        outage.begin(reason: "system_tap_rebuild", since: began + 4)
        let closed = outage.end(now: began + 6)

        XCTAssertEqual(closed?.reason, "output_device_change", "what took the tap away, not what retried")
        XCTAssertEqual(closed?.seconds ?? 0, 6, accuracy: 0.01)
        XCTAssertNil(outage.end(now: began + 8), "and it is only reported once")
    }
}
#endif
