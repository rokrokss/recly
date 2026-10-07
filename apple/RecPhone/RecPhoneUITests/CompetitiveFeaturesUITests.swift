import XCTest

/// docs/09 §1–§11 on the phone, driven end to end for the lane's screenshots: Highlight while recording,
/// the detail's More, Share, speed, highlights, edit mode and speaker menu, search, and the vocabulary
/// row. Expects a recording titled `1-Weekly meeting` with a transcript on the simulator (the lane's
/// seed: an imported file and its transcript); every step saves a screenshot as an attachment.
final class CompetitiveFeaturesUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    func testRecordingScreenHighlightEnglish() { recordWithHighlight("en") }
    func testRecordingScreenHighlightKorean() { recordWithHighlight("ko") }
    func testDetailEnglish() { detail("en") }
    func testDetailKorean() { detail("ko") }
    func testSearchAndVocabularyEnglish() { searchAndSettings("en") }
    func testSearchAndVocabularyKorean() { searchAndSettings("ko") }

    private func launch(_ language: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(\(language))", "-AppleLocale", language == "ko" ? "ko_KR" : "en_US",
                               "-appLanguage", language, "-appTheme", "light"]
        app.launch()
        // The system's notification question, on a fresh install: not what these screens are about.
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        if springboard.alerts.firstMatch.waitForExistence(timeout: 3) {
            springboard.alerts.firstMatch.buttons.element(boundBy: 0).tap()
        }
        return app
    }

    private func recordWithHighlight(_ language: String) {
        let app = launch(language)
        let start = app.buttons["start"]
        XCTAssertTrue(start.waitForExistence(timeout: 30))
        start.tap()
        if app.buttons["consent-confirm"].waitForExistence(timeout: 3) { app.buttons["consent-confirm"].tap() }
        let highlight = app.buttons["highlight"]
        XCTAssertTrue(highlight.waitForExistence(timeout: 15))
        sleep(3)
        highlight.tap()
        sleep(1)
        shot("record-highlight-\(language)")
        app.buttons["stop"].tap()
        if app.buttons["saveTitle"].waitForExistence(timeout: 10) { app.buttons["saveTitle"].tap() }
    }

    private func openMeeting(_ app: XCUIApplication, _ language: String) {
        app.tabBars.buttons[language == "ko" ? "목록" : "List"].tap()
        let row = app.staticTexts["1-Weekly meeting"].firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 20))
        row.tap()
        let open = app.buttons.matching(identifier: "open-detail").firstMatch
        XCTAssertTrue(open.waitForExistence(timeout: 5))
        open.tap()
        XCTAssertTrue(app.buttons["detail-more"].waitForExistence(timeout: 20))
        sleep(3)
    }

    private func detail(_ language: String) {
        let app = launch(language)
        openMeeting(app, language)
        shot("detail-\(language)")

        app.buttons["detail-more"].tap()
        sleep(1)
        shot("detail-more-menu-\(language)")
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", language == "ko" ? "00:00:00에" : "Add highlight at")).firstMatch.tap()
        sleep(1)

        app.buttons["playback-speed"].tap()
        sleep(1)
        shot("detail-speed-menu-\(language)")
        app.buttons["1.5×"].tap()
        app.buttons["playback-speed"].tap()
        app.switches.firstMatch.exists ? app.switches.firstMatch.tap() : app.buttons[language == "ko" ? "무음 건너뛰기" : "Skip silence"].tap()
        sleep(1)
        shot("detail-highlight-speed-\(language)")

        let speaker = app.buttons["speaker-0"]
        if speaker.waitForExistence(timeout: 5) {
            speaker.tap()
            sleep(1)
            shot("detail-speaker-menu-\(language)")
            app.buttons[language == "ko" ? "화자 이름 바꾸기" : "Rename speaker"].tap()
            let field = app.textFields["speaker-name-field"]
            XCTAssertTrue(field.waitForExistence(timeout: 5))
            // The field starts with the name there is; replace it.
            field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 20) + "Minsu")
            shot("detail-speaker-name-\(language)")
            app.buttons["speaker-name-save"].tap()
            sleep(2)
            shot("detail-speaker-renamed-\(language)")
        }

        app.buttons["detail-more"].tap()
        app.buttons[language == "ko" ? "전사본 편집" : "Edit transcript"].tap()
        sleep(1)
        shot("detail-edit-\(language)")
        app.buttons["edit-speaker-1"].tap()
        sleep(1)
        shot("detail-edit-speaker-menu-\(language)")
        app.buttons[language == "ko" ? "이 줄의 화자 바꾸기" : "Change speaker for this line"].tap()
        sleep(1)
        shot("detail-edit-speaker-submenu-\(language)")
        app.buttons[language == "ko" ? "새 화자" : "New speaker"].tap()
        sleep(1)
        app.buttons.matching(identifier: language == "ko" ? "취소" : "Cancel").firstMatch.tap()
        sleep(1)
        shot("detail-discard-\(language)")
        app.buttons["discard-edits"].tap()

        app.buttons["detail-more"].tap()
        app.buttons[language == "ko" ? "다시 전사" : "Transcribe again"].tap()
        sleep(1)
        shot("detail-transcribe-again-\(language)")
        app.buttons[language == "ko" ? "취소" : "Cancel"].firstMatch.tap()

        app.buttons["detail-share"].tap()
        XCTAssertTrue(app.buttons["share-srt"].waitForExistence(timeout: 5))
        sleep(1)
        shot("detail-share-sheet-\(language)")
        app.buttons["share-srt"].tap()
        sleep(4)
        shot("detail-system-share-\(language)")
    }

    private func searchAndSettings(_ language: String) {
        let app = launch(language)
        app.tabBars.buttons[language == "ko" ? "목록" : "List"].tap()
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 20))
        shot("list-\(language)")
        search.tap()
        search.typeText("friday")
        XCTAssertTrue(app.buttons["search-result"].firstMatch.waitForExistence(timeout: 10))
        shot("search-results-\(language)")
        app.buttons["search-result"].firstMatch.tap()
        sleep(2)
        shot("search-opened-\(language)")
        XCTAssertTrue(app.buttons["find-close"].waitForExistence(timeout: 20))
        sleep(3)
        shot("search-find-bar-\(language)")
        app.buttons["detail-close"].tap()
        search.buttons.firstMatch.exists ? search.buttons.firstMatch.tap() : ()
        search.typeText("zzzz")
        sleep(1)
        shot("search-no-results-\(language)")
        app.terminate()

        let settingsApp = launch(language)
        settingsApp.tabBars.buttons[language == "ko" ? "설정" : "Settings"].tap()
        let external = settingsApp.buttons[language == "ko" ? "외부 API" : "External API"]
        for _ in 0..<6 where !external.isHittable { settingsApp.swipeUp() }
        external.tap()
        let field = settingsApp.textFields["vocabulary-field"]
        for _ in 0..<6 where !field.isHittable { settingsApp.swipeUp() }
        field.tap()
        field.typeText("Recly\n")
        field.tap()
        field.typeText("Minsu\n")
        settingsApp.swipeUp()
        sleep(1)
        shot("settings-vocabulary-\(language)")
    }

    /// docs/09 §10: the Record widget as the system's gallery offers it (the simulator's system language).
    func testRecordWidgetInGallery() {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        XCUIDevice.shared.press(.home)
        springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.65)).press(forDuration: 2.5)
        sleep(1)
        let edit = springboard.descendants(matching: .any).matching(NSPredicate(format: "label IN %@", ["Edit", "편집"])).firstMatch
        if !edit.waitForExistence(timeout: 5) { print(springboard.debugDescription) }
        XCTAssertTrue(edit.exists)
        edit.tap()
        let add = springboard.descendants(matching: .any).matching(NSPredicate(format: "label IN %@", ["Add Widget", "위젯 추가"])).firstMatch
        XCTAssertTrue(add.waitForExistence(timeout: 5))
        add.tap()
        let search = springboard.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        search.tap()
        search.typeText("Recly")
        sleep(2)
        shot("widget-gallery-search")
        let recly = springboard.cells.matching(NSPredicate(format: "label CONTAINS %@", "Recly")).firstMatch
        if recly.waitForExistence(timeout: 5) {
            recly.tap()
        } else {
            springboard.staticTexts["Recly"].firstMatch.tap()
        }
        sleep(2)
        shot("widget-gallery-record-small")
        springboard.swipeLeft()
        sleep(1)
        shot("widget-gallery-record-next")
    }

    private func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
