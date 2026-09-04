import XCTest

/// docs/lanes M5-L2 시뮬레이터 스모크: a real recording, made the way a person makes one — tap
/// Start recording, put the phone away, come back, stop, name it — with the real `AVAudioSession`,
/// the
/// real AAC encoder and the real segment boundary underneath.
///
/// It is a UI test because that is the only thing that can drive the app as a separate process:
/// the microphone is the app's (an xctest bundle touching it is killed by TCC), and going to the
/// home screen mid-recording is exactly the claim `UIBackgroundModes: audio` makes.
///
/// Skipped unless `REC_MIC_TEST=1`, as `MicrophoneSmokeTests` is on the Mac, and for one more
/// reason: a simulator records through the *host's* microphone, and macOS asks for that once, for
/// `SimulatorTrampoline.xpc`, in a dialog somebody has to click. Until it is answered
/// `AURemoteIO::Initialize()` waits for an audio server that never replies and aborts the app
/// (measured — see docs/measurements.md), which is not a failure of anything in this repository.
///
/// The parts and the `meta.json` it leaves behind are read out of the app container afterwards:
///
/// ```
/// xcrun simctl privacy booted grant microphone app.recly
/// TEST_RUNNER_REC_MIC_TEST=1 xcodebuild -workspace apple/Rec.xcworkspace -scheme Recly \
///   -destination 'platform=iOS Simulator,name=iPhone 17' ARCHS=arm64 \
///   -collect-test-diagnostics never -only-testing:ReclyUITests test
/// ls "$(xcrun simctl get_app_container booted app.recly data)/Library/Application Support/app.recly/recordings/"
/// ```
final class RecordingSmokeTests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
        try XCTSkipUnless(
            ProcessInfo.processInfo.environment["REC_MIC_TEST"] == "1",
            "set REC_MIC_TEST=1 (and answer the Mac's microphone prompt for the Simulator) to run the recording smoke test"
        )
    }

    func testARecordingSurvivesTheHomeScreenAndIsNamedAtTheStop() {
        let app = XCUIApplication()
        // Five-second segments, so a recording a person can wait out still crosses real boundaries
        // (the argument lands in `NSArgumentDomain`, which is where `RecordingModel` reads it).
        app.launchArguments = ["-segmentSec", "5"]
        app.launch()

        let start = app.buttons["start"]
        XCTAssertTrue(start.waitForExistence(timeout: 30), "the core never opened")
        // Enabled only once the core is open and the recovery pass has run.
        XCTAssertTrue(
            NSPredicate(format: "isEnabled == true").expect(on: start, in: self, timeout: 30),
            "the start button stayed disabled"
        )
        start.tap()

        let stop = app.buttons["stop"]
        XCTAssertTrue(stop.waitForExistence(timeout: 15), "the recording never started")

        // docs/13: it keeps going while locked — the home screen is as far as a simulator goes,
        // and the recording
        // has to keep counting through it.
        XCUIDevice.shared.press(.home)
        Thread.sleep(forTimeInterval: 10)
        add(screenshot(named: "home screen while recording (Live Activity)"))
        app.activate()

        let elapsed = app.staticTexts["elapsed"]
        XCTAssertTrue(elapsed.waitForExistence(timeout: 15))
        XCTAssertTrue(
            NSPredicate(format: "label >= %@", "00:16").expect(on: elapsed, in: self, timeout: 30),
            "the recording did not keep going: \(elapsed.label)"
        )
        add(screenshot(named: "recording"))

        app.buttons["stop"].tap()

        // docs/03: the title is asked for after the recording has ended.
        let field = app.textFields["titleField"]
        XCTAssertTrue(field.waitForExistence(timeout: 30), "no title prompt after the stop")
        field.tap()
        field.typeText("시뮬레이터 스모크")
        app.buttons["saveTitle"].tap()

        XCTAssertTrue(
            // The app runs in whatever language the simulator is set to; the smoke asks for none
            // in particular, so it asserts on the base language (docs/07 rule 1).
            NSPredicate(format: "label == %@", "Waiting").expect(
                on: app.staticTexts["status"], in: self, timeout: 30
            ),
            "the app did not settle after the stop"
        )
        add(screenshot(named: "after the stop"))
    }

    private func screenshot(named name: String) -> XCTAttachment {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        return attachment
    }
}

private extension NSPredicate {
    /// `waitForExistence` only answers one question; the rest of them are predicates.
    func expect(on element: XCUIElement, in test: XCTestCase, timeout: TimeInterval) -> Bool {
        let expectation = test.expectation(for: self, evaluatedWith: element)
        return XCTWaiter().wait(for: [expectation], timeout: timeout) == .completed
    }
}
