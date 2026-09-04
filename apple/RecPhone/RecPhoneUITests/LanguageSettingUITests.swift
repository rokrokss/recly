import XCTest

/// docs/07 rule 3 on the phone: picking a language changes the screen where it stands — no relaunch
/// — and the choice is still there the next time the app is opened (rule 2).
///
/// A UI test because that is the only thing that can answer it: the switch is a SwiftUI environment
/// change through a real `TabView`, and a unit test over `AppLanguage` can only say that the value
/// was stored. It drives the app the way the lane's acceptance does by hand.
///
/// It assumes the app has never been given a language on this device, which is what a fresh install
/// is — the run installs it.
final class LanguageSettingUITests: XCTestCase {

    override func setUp() {
        continueAfterFailure = false
    }

    func testPickingKoreanChangesTheScreenAndSurvivesARelaunch() {
        let app = XCUIApplication()
        app.launch()

        // The base language, because the simulator is in English and nothing has been picked.
        XCTAssertTrue(
            app.buttons["start"].waitForExistence(timeout: 30), "the app never opened the core"
        )
        XCTAssertEqual(app.staticTexts["status"].label, "Waiting")
        // The button's own title, not only the status line: a conditional title is the one place a
        // verbatim `String` slips past the catalog (docs/07 rule 3).
        XCTAssertEqual(app.buttons["start"].label, "Start recording")
        attach(app, named: "en-record")

        app.tabBars.buttons.element(boundBy: 3).tap()
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 10))
        attach(app, named: "en-settings")

        // docs/09 "형태": one row that names the language the app is in — "English", because nothing
        // has been picked and the simulator is English — and every choice in the dialog it opens,
        // with that same English marked as the one in effect.
        let row = app.buttons["language"]
        XCTAssertTrue(row.waitForExistence(timeout: 10))
        XCTAssertTrue(row.label.contains("English"), row.label)
        row.tap()
        XCTAssertTrue(app.buttons["language-en"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["language-en"].isSelected, "the language in effect is not marked")
        XCTAssertFalse(app.buttons["language-ko"].isSelected)
        app.buttons["language-ko"].tap()

        // Still on the settings screen, and already Korean: the tab bar under it and the bar above
        // it are redrawn by the same environment change.
        XCTAssertTrue(
            app.navigationBars["설정"].waitForExistence(timeout: 10),
            "the screen did not follow the language without a relaunch"
        )
        XCTAssertEqual(app.tabBars.buttons.element(boundBy: 3).label, "설정")
        // The dialog closed itself on the choice, and the row under it now says what was chosen.
        XCTAssertTrue(app.buttons["language"].label.contains("한국어"), app.buttons["language"].label)
        attach(app, named: "ko-settings")

        app.tabBars.buttons.element(boundBy: 0).tap()
        XCTAssertEqual(app.staticTexts["status"].label, "대기")
        XCTAssertEqual(
            app.buttons["start"].label, "녹음 시작", "the button's title did not follow the language"
        )
        attach(app, named: "ko-record")

        app.terminate()
        app.launch()
        XCTAssertTrue(app.buttons["start"].waitForExistence(timeout: 30))
        XCTAssertEqual(app.staticTexts["status"].label, "대기", "the choice did not survive a relaunch")
        XCTAssertEqual(app.buttons["start"].label, "녹음 시작")
        attach(app, named: "ko-record-after-relaunch")
    }

    private func attach(_ app: XCUIApplication, named name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
