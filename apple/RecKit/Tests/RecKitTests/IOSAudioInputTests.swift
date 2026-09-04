#if os(iOS)
import AVFoundation
import XCTest
@testable import RecKit

/// docs/13 deliverable 1: what the iPhone's input does when a call arrives — `began` → `silenced`,
/// `ended` + `shouldResume` → resume, anything else about an interruption we are inside → the
/// restart path and its `gaps` entry — and what it does with the hardware moving while the call
/// still holds the microphone.
///
/// The user info is the real one, keys and all; the simulator cannot ring, and the engine that
/// would answer it cannot run here either (an input tap inside an xctest bundle is killed by TCC —
/// the runner carries no `NSMicrophoneUsageDescription`). The session and the engine are exercised
/// by the app's own recording test, on a simulator whose microphone `simctl` has granted.
final class IOSAudioInputTests: XCTestCase {
    /// The tap is installed: what the input looks like for the whole of a recording.
    private var interruption = Interruption(tapped: true)

    /// The system has already taken the microphone by the time this arrives; the recording only
    /// notes when it went (docs/03 `silenced`).
    func testABeganStartsTheSilence() {
        XCTAssertEqual(interruption.notified(userInfo(.began)), .silenced)
    }

    /// The call ended and the system is offering the microphone back, which is the whole of a
    /// resume: the tap is still installed, so the same segment carries on.
    func testAnEndedThatMayResumeResumes() {
        XCTAssertEqual(interruption.notified(userInfo(.began)), .silenced)

        XCTAssertEqual(
            interruption.notified(userInfo(.ended, options: .shouldResume)), .resume
        )
    }

    /// docs/13: a resume that fails becomes a `gaps` entry. The system declining to offer a resume is
    /// the same answer — the input hands it to the recorder's restart path, and that is what writes the
    /// range.
    func testAnEndedThatMayNotResumeGoesToTheRestartPath() {
        XCTAssertEqual(interruption.notified(userInfo(.began)), .silenced)

        XCTAssertEqual(
            interruption.notified(userInfo(.ended)),
            .resumeByRestart(reason: Interruption.noResume)
        )
    }

    /// An `.ended` for an interruption that began before this recording did — the app was launched
    /// during a call. There is no silence to close and nothing to resume.
    func testAnEndedWithNoBeganIsIgnored() {
        XCTAssertEqual(interruption.notified(userInfo(.ended, options: .shouldResume)), .ignore)
    }

    /// The user stopped the recording during the call. The tap is gone and the session has been
    /// handed back, so the `.ended` that arrives after that must not bring either of them up again
    /// — a resumed session lights the microphone indicator for a recording that is finalized.
    func testAnEndedAfterTheRecordingWasStoppedResumesNothing() {
        XCTAssertEqual(interruption.notified(userInfo(.began)), .silenced)
        interruption.stopped()

        XCTAssertEqual(interruption.notified(userInfo(.ended, options: .shouldResume)), .ignore)
        XCTAssertEqual(interruption.notified(userInfo(.began)), .ignore)
    }

    /// A notification with nothing this understands in it changes nothing.
    func testANotificationWithoutATypeIsIgnored() {
        XCTAssertEqual(interruption.notified(nil), .ignore)
        XCTAssertEqual(interruption.notified(["nonsense": 1]), .ignore)
    }

    // MARK: - The hardware moving during a call (Sol M5-L2 review)

    /// docs/13 deliverable 1: with no call in the way a route change goes straight to the
    /// recorder's restart coalescing, the same path a Mac's device change takes.
    func testARouteChangeAsksForARestart() {
        XCTAssertEqual(
            interruption.deviceChanged(reason: "route_change"), .restart(reason: "route_change")
        )
        XCTAssertNil(interruption.pendingRestart)
    }

    /// (a) A Bluetooth headset connecting while the call holds the microphone. Restarting now would
    /// have the recorder activate a session the call owns, throw, and end the recording as a fatal
    /// error — the one thing that must not happen to a recording that was going to resume. So it
    /// waits.
    func testADeviceChangeDuringAnInterruptionIsDeferred() {
        XCTAssertEqual(interruption.notified(userInfo(.began)), .silenced)

        XCTAssertEqual(interruption.deviceChanged(reason: "route_change"), .ignore)
        XCTAssertEqual(interruption.pendingRestart, "route_change")
    }

    /// (b) …and is taken when the call ends: the silence closes and the input is rebuilt rather
    /// than resumed in place, because the session it attached to is not the one there is now. The
    /// latest of several device changes is the one that matters — they are all the same question.
    func testAnEndedWithADeferredDeviceChangeRestartsInsteadOfResuming() {
        XCTAssertEqual(interruption.notified(userInfo(.began)), .silenced)
        XCTAssertEqual(interruption.deviceChanged(reason: "route_change"), .ignore)
        XCTAssertEqual(interruption.deviceChanged(reason: "engine_configuration_change"), .ignore)

        XCTAssertEqual(
            interruption.notified(userInfo(.ended, options: .shouldResume)),
            .resumeByRestart(reason: "engine_configuration_change")
        )
        XCTAssertNil(interruption.pendingRestart, "taken, not kept for the next call")
    }

    /// (c) Nothing moved during the call: the resume is still a resume, and the recording carries
    /// on into the same segment without a `gaps` entry.
    func testAnEndedWithNoDeferredDeviceChangeStillResumes() {
        XCTAssertEqual(interruption.notified(userInfo(.began)), .silenced)

        XCTAssertEqual(interruption.notified(userInfo(.ended, options: .shouldResume)), .resume)
    }

    /// (d) The recording was stopped during the call: the device change that was waiting is not
    /// owed to anybody, and the recording that starts next must not inherit it.
    func testAStopClearsADeferredDeviceChange() {
        XCTAssertEqual(interruption.notified(userInfo(.began)), .silenced)
        XCTAssertEqual(interruption.deviceChanged(reason: "route_change"), .ignore)

        interruption.stopped()
        XCTAssertNil(interruption.pendingRestart)

        // The next recording, on the same input.
        interruption.tapped = true
        XCTAssertEqual(interruption.notified(userInfo(.began)), .silenced)
        XCTAssertEqual(interruption.notified(userInfo(.ended, options: .shouldResume)), .resume)
    }

    private func userInfo(
        _ type: AVAudioSession.InterruptionType,
        options: AVAudioSession.InterruptionOptions = []
    ) -> [AnyHashable: Any] {
        [
            AVAudioSessionInterruptionTypeKey: type.rawValue,
            AVAudioSessionInterruptionOptionKey: options.rawValue,
        ]
    }
}
#endif
