import XCTest

/// The phone's screens must stay usable in landscape, at the accessibility text sizes and in both languages.
final class MobileUxTests: XCTestCase {
    func testDriveSettingsFollowThemeChangesInBothLanguages() {
        continueAfterFailure = false
        for language in ["en", "ko"] {
            let app = XCUIApplication()
            app.launchArguments = ["-AppleLanguages", "(\(language))", "-AppleLocale", language,
                                   "-appLanguage", language, "-appTheme", "light"]
            app.launch()
            let settings = app.tabBars.buttons[language == "ko" ? "설정" : "Settings"]
            XCTAssertTrue(settings.waitForExistence(timeout: 30))
            settings.tap()
            XCTAssertTrue(app.buttons["signIn"].waitForExistence(timeout: 10))
            for theme in ["light", "dark"] {
                let chip = app.buttons["theme-" + theme]
                for _ in 0..<5 where !chip.isHittable { app.scrollViews.firstMatch.swipeUp() }
                XCTAssertTrue(chip.isHittable)
                chip.tap()
                XCTAssertTrue(chip.isSelected)
                for _ in 0..<5 where !app.buttons["signIn"].isHittable {
                    app.scrollViews.firstMatch.swipeDown()
                }
                XCTAssertTrue(app.buttons["signIn"].isHittable)
                let screenshot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
                screenshot.name = "Drive settings \(language) \(theme)"
                screenshot.lifetime = .keepAlways
                add(screenshot)
            }
            app.terminate()
        }
    }

    func testDriveSettingsExplainOptionalStorageAndKeepRecordingAvailable() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US", "-appLanguage", "en"]
        app.launch()
        let settings = app.tabBars.buttons["Settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 30))
        settings.tap()
        XCTAssertFalse(app.buttons["drive-manage"].exists, "Drive actions should not need an intermediate screen")
        if app.buttons["disconnect"].exists {
            XCTAssertFalse(app.buttons["signOut"].exists)
        } else {
            XCTAssertTrue(app.buttons["signIn"].waitForExistence(timeout: 10))
            XCTAssertTrue(app.staticTexts["Record locally. Connect Drive to upload."].exists)
        }
        let screenshot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        screenshot.name = "Optional Google Drive connection"
        screenshot.lifetime = .keepAlways
        add(screenshot)
        app.tabBars.buttons["Record"].tap()
        XCTAssertTrue(app.buttons["start"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["start"].isEnabled, "recording must remain available without Drive")
    }

    func testLandscapeDashboardKeepsRecordReachable() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US", "-appLanguage", "en"]
        app.launch()
        XCUIDevice.shared.orientation = .landscapeLeft
        defer { XCUIDevice.shared.orientation = .portrait }
        let start = app.buttons["start"]
        XCTAssertTrue(start.waitForExistence(timeout: 30))
        XCTAssertGreaterThan(app.frame.width, app.frame.height)
        for _ in 0..<5 where !start.isHittable { app.scrollViews.firstMatch.swipeUp() }
        XCTAssertTrue(start.isHittable, "The record action must be reachable in landscape")
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = "Landscape dashboard"
        shot.lifetime = .keepAlways
        add(shot)
    }

    func testKoreanLargeTextDashboardKeepsRecordReachable() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(ko)", "-AppleLocale", "ko_KR", "-appLanguage", "ko",
            "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()
        let start = app.buttons["start"]
        XCTAssertTrue(start.waitForExistence(timeout: 30))
        for _ in 0..<6 where !start.isHittable { app.scrollViews.firstMatch.swipeUp() }
        XCTAssertTrue(start.isHittable)
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = "Korean accessibility text dashboard"
        shot.lifetime = .keepAlways
        add(shot)
    }
}
