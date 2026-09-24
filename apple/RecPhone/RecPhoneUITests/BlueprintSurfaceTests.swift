import XCTest

/// Lane P1 deliverables 4·5 on the phone, driven the way a person reaches them: the delete dialog,
/// the disconnect warning and the recording-consent reminder are all *dialogs*, and the only thing
/// that can prove one of them opens — and that its options are there, and that Cancel means
/// something — is the app running as its own process.
///
/// Every case attaches what it saw, which is also where the lane's screenshots come from:
///
/// ```
/// xcodebuild -workspace apple/Rec.xcworkspace -scheme Recly -destination 'id=…' ARCHS=arm64 \
///   -only-testing:ReclyUITests/BlueprintSurfaceTests -resultBundlePath /tmp/shots.xcresult \
///   -collect-test-diagnostics never test
/// xcrun xcresulttool export attachments --path /tmp/shots.xcresult --output-path /tmp/shots
/// ```
final class BlueprintSurfaceTests: XCTestCase {

    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US", "-appLanguage", "en"]
        app.launch()
        // docs/10: the alert notifier asks for permission the first time the queue has something to
        // say, which may be during launch. Whatever the answer, it must not be left in front of the
        // screen the test is about — the banner is what this checks, and docs/10 makes the banner
        // the half that needs no permission at all.
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let allow = springboard.buttons["Allow"]
        if allow.waitForExistence(timeout: 5) { allow.tap() }
    }

    /// docs/12 M8 · ADR-011: the reminder is asked before the *first* recording, and answering "no"
    /// leaves the recording unstarted — it is a question, and Cancel has to mean something.
    func testTheConsentReminderIsAskedBeforeTheFirstRecordingAndCancelMeansNo() {
        let start = app.buttons["start"]
        XCTAssertTrue(waitEnabled(start, timeout: 60), "the core never opened")
        start.tap()

        let confirm = app.buttons["consent-confirm"]
        XCTAssertTrue(
            confirm.waitForExistence(timeout: 30),
            "no consent reminder before the first recording"
        )
        // docs/12: the question, the jurisdictions, the link and the box, all of them the Mac's.
        let body = app.staticTexts["consent-body"]
        XCTAssertTrue(body.exists)
        // `BlueprintCheckRow` announces itself as the switch it is, not as a button.
        XCTAssertTrue(app.switches["consent-suppress"].exists)
        XCTAssertTrue(body.label.contains("Korea"), body.label)
        attach("consent reminder")

        app.buttons["Cancel"].firstMatch.tap()
        XCTAssertFalse(
            app.buttons["stop"].waitForExistence(timeout: 3),
            "Cancel started the recording anyway"
        )
    }

    /// docs/03 "앱에서 지우기": two answers about Drive, and the default is the one that can be undone.
    func testTheDeleteDialogDefaultsToLeavingDriveAlone() throws {
        open(tab: "List")
        let delete = app.buttons["delete"].firstMatch
        try XCTSkipUnless(
            delete.waitForExistence(timeout: 10) || expandFirstRow(),
            "this simulator has no recording to delete"
        )
        attach("recordings list")
        app.buttons["delete"].firstMatch.tap()

        XCTAssertTrue(app.buttons["delete-local-only"].waitForExistence(timeout: 5), "no delete dialog")
        // docs/03: "되돌릴 수 없는 쪽을 기본값으로 두지 않는다."
        XCTAssertTrue(app.buttons["delete-local-only"].isSelected, "Drive was the default answer")
        XCTAssertFalse(app.buttons["delete-with-drive"].isSelected)
        attach("delete dialog")

        app.buttons["Cancel"].firstMatch.tap()
    }

    /// docs/03 "로그아웃 vs 연결 해제": the single disconnect action revokes the Google grant,
    /// so the warning names its effect on other devices.
    func testTheDisconnectWarningNamesTheOtherDevices() throws {
        open(tab: "Settings")
        attach("settings")
        let disconnect = app.buttons["disconnect"]
        try XCTSkipUnless(
            disconnect.waitForExistence(timeout: 10),
            "this simulator is signed out, so there is no grant to disconnect"
        )
        disconnect.tap()

        let confirm = app.buttons["disconnect-confirm"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 5), "no disconnect warning")
        XCTAssertTrue(
            app.staticTexts.containing(
                NSPredicate(format: "label CONTAINS[c] %@", "Recly loses Drive access on all devices connected to this Google account")
            ).firstMatch.exists,
            "the warning does not say the other devices lose access"
        )
        // Revoking storage access never offers to delete recordings.
        XCTAssertFalse(app.switches["disconnect-also-delete"].exists)
        attach("disconnect dialog")

        // Never confirmed: this would revoke the grant on every device the account is signed in on.
        app.buttons["Cancel"].firstMatch.tap()
    }

    /// docs/09 "형태": the language is one row that names the language the app is in, with every
    /// choice in the dialog behind it — and the reminder is a switch that can put the dialog back
    /// after "Do not ask again".
    func testTheSettingsRowsAreARowWithADialogAndSwitches() {
        open(tab: "Settings")

        let row = app.buttons["language"]
        XCTAssertTrue(row.waitForExistence(timeout: 10))
        // The language the app is in, which on a fresh install of an English simulator is the one
        // it followed the system to. There is no "system default" to offer or to say.
        XCTAssertTrue(row.label.contains("English"), row.label)
        row.tap()

        XCTAssertTrue(app.buttons["language-en"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["language-en"].isSelected, "the language in effect is not marked")
        XCTAssertTrue(app.buttons["language-ko"].exists)
        XCTAssertFalse(app.buttons["language-system"].exists, "the system default is still offered")
        attach("language dialog")
        // Closed rather than answered: this case is about what the screen offers, and picking one
        // would leave the rest of the run in another language.
        app.buttons["Close"].firstMatch.tap()

        XCTAssertTrue(
            app.switches["consent-reminder"].waitForExistence(timeout: 10),
            "no way back on after ‘do not ask again’"
        )
    }

    // MARK: - Pieces

    private func open(tab: String) {
        let button = app.tabBars.buttons[tab]
        XCTAssertTrue(button.waitForExistence(timeout: 30), "no \(tab) tab")
        button.tap()
    }

    /// The row actions are behind the row: the ledger opens where the recording stands, and the
    /// buttons with it.
    private func expandFirstRow() -> Bool {
        let row = app.buttons["state"].firstMatch
        guard row.waitForExistence(timeout: 10) else { return false }
        row.tap()
        return app.buttons["delete"].firstMatch.waitForExistence(timeout: 5)
    }

    private func waitEnabled(_ element: XCUIElement, timeout: TimeInterval) -> Bool {
        guard element.waitForExistence(timeout: timeout) else { return false }
        let enabled = expectation(for: NSPredicate(format: "isEnabled == true"), evaluatedWith: element)
        return XCTWaiter().wait(for: [enabled], timeout: timeout) == .completed
    }

    private func attach(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
