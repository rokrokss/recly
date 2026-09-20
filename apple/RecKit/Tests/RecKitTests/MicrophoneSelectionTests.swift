#if os(macOS)
import XCTest
@testable import RecKit

final class MicrophoneSelectionTests: XCTestCase {
    func testMeetingMicWinsOverDefaultAndStaysSelectedWhileMuted() {
        var policy = MicrophoneSelection()
        XCTAssertEqual(policy.resolve(available: ["built-in", "airpods"], preferred: nil,
            meeting: ["airpods"], defaultUID: "built-in", now: 0, initial: true), "airpods")
        XCTAssertEqual(policy.resolve(available: ["built-in", "airpods"], preferred: nil,
            meeting: [], defaultUID: "built-in", now: 10), "airpods")
        XCTAssertEqual(policy.resolve(available: ["built-in", "airpods"], preferred: "built-in",
            meeting: ["airpods"], defaultUID: "airpods", now: 11), "built-in")
    }

    func testDefaultDeviceSwitchSettlesWithoutBeingForgottenOnTheNextPoll() {
        var policy = MicrophoneSelection()
        _ = policy.resolve(available: ["built-in", "airpods"], preferred: nil,
            meeting: [], defaultUID: "built-in", now: 0, initial: true)
        XCTAssertEqual(policy.resolve(available: ["built-in", "airpods"], preferred: nil,
            meeting: [], defaultUID: "airpods", now: 1), "built-in")
        XCTAssertEqual(policy.resolve(available: ["built-in", "airpods"], preferred: nil,
            meeting: [], defaultUID: "airpods", now: 2.1), "airpods")
    }

    func testBriefDisconnectKeepsRouteButPersistentLossFallsBackAndReconnectRestoresPreference() {
        var policy = MicrophoneSelection()
        _ = policy.resolve(available: ["built-in", "airpods"], preferred: "airpods",
            meeting: [], defaultUID: "built-in", now: 0, initial: true)
        XCTAssertEqual(policy.resolve(available: ["built-in"], preferred: "airpods",
            meeting: [], defaultUID: "built-in", now: 1), "airpods")
        XCTAssertEqual(policy.resolve(available: ["built-in", "airpods"], preferred: "airpods",
            meeting: [], defaultUID: "built-in", now: 1.5), "airpods")
        _ = policy.resolve(available: ["built-in"], preferred: "airpods", meeting: [], defaultUID: "built-in", now: 2)
        XCTAssertEqual(policy.resolve(available: ["built-in"], preferred: "airpods",
            meeting: [], defaultUID: "built-in", now: 4.1), "built-in")
        _ = policy.resolve(available: ["built-in", "airpods"], preferred: "airpods",
            meeting: [], defaultUID: "built-in", now: 5)
        XCTAssertEqual(policy.resolve(available: ["built-in", "airpods"], preferred: "airpods",
            meeting: [], defaultUID: "built-in", now: 6.1), "airpods")
    }

    func testAmbiguousMeetingsUseDefaultAndNoDeviceHonorsDisconnectGrace() {
        var policy = MicrophoneSelection()
        XCTAssertEqual(policy.resolve(available: ["one", "two"], preferred: nil,
            meeting: ["one", "two"], defaultUID: "one", now: 0, initial: true), "one")
        XCTAssertEqual(policy.resolve(available: [], preferred: nil, meeting: [], defaultUID: nil, now: 1), "one")
        XCTAssertNil(policy.resolve(available: [], preferred: nil, meeting: [], defaultUID: nil, now: 3.1))
    }

    func testBrowserHelpersRequireEvidenceOfAMeetingWindow() {
        XCTAssertTrue(MicrophoneRouteResolver.isMeetingProcess("us.zoom.xos.helper"))
        XCTAssertFalse(MicrophoneRouteResolver.isMeetingProcess("com.google.Chrome.helper"))
        XCTAssertTrue(MicrophoneRouteResolver.isMeetingProcess("com.google.Chrome.helper",
            meetingBrowsers: ["com.google.Chrome"]))
        XCTAssertFalse(MicrophoneRouteResolver.isMeetingProcess("app.recly.mac"))
    }
}
#endif
