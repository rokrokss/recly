import XCTest

/// The mobile editor must remain usable while its software keyboard occupies the lower screen.
final class MobileUxTests: XCTestCase {
    func testLandscapeDashboardAndWorkflowHelp() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US", "-appLanguage", "en"]
        app.launch()
        XCUIDevice.shared.orientation = .landscapeLeft
        defer { XCUIDevice.shared.orientation = .portrait }
        let workflows = app.tabBars.buttons["Workflows"]
        XCTAssertTrue(workflows.waitForExistence(timeout: 30))
        XCTAssertGreaterThan(app.frame.width, app.frame.height)
        let start = app.buttons["start"]
        for _ in 0..<5 where !start.isHittable { app.scrollViews.firstMatch.swipeUp() }
        XCTAssertTrue(start.isHittable, "The record action must be reachable in landscape")
        workflows.tap()
        let help = app.buttons["transcription-setup"]
        XCTAssertTrue(help.waitForExistence(timeout: 10))
        help.tap()
        let body = app.staticTexts["transcription-setup-body"]
        XCTAssertTrue(body.waitForExistence(timeout: 5))
        XCTAssertTrue(body.label.hasSuffix("are still saved."))
        XCTAssertGreaterThan(body.frame.height, 40)
        let newWorkflow = app.buttons["newWorkflow"]
        XCTAssertTrue(newWorkflow.isHittable)
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = "Landscape workflow help"
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

    func testKeyboardActionsAndParkedDraft() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US", "-appLanguage", "en"]
        app.launch()
        let workflows = app.tabBars.buttons["Workflows"]
        XCTAssertTrue(workflows.waitForExistence(timeout: 30))
        workflows.tap()
        let newWorkflow = app.buttons["newWorkflow"]
        XCTAssertTrue(newWorkflow.waitForExistence(timeout: 30))
        newWorkflow.tap()
        let name = app.textFields["Name"].firstMatch
        XCTAssertTrue(name.waitForExistence(timeout: 10))
        name.tap()
        name.typeText("UX test draft")
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10))
        let save = app.buttons["saveWorkflow"]
        XCTAssertTrue(save.isHittable, "Save disappeared behind the keyboard")
        XCTAssertLessThanOrEqual(save.frame.maxY, app.keyboards.firstMatch.frame.minY + 1)
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = "Editor with keyboard"
        shot.lifetime = .keepAlways
        add(shot)
        app.buttons["Cancel"].firstMatch.tap()
        let keep = app.buttons["Keep editing"]
        XCTAssertTrue(keep.waitForExistence(timeout: 5))
        keep.tap()
        XCTAssertEqual(name.value as? String, "UX test draft")
        XCTAssertTrue(app.tabBars.buttons["Settings"].isHittable)
        app.tabBars.buttons["Settings"].tap()
        workflows.tap()
        XCTAssertEqual(name.value as? String, "UX test draft")
        app.buttons["Cancel"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Discard changes"].waitForExistence(timeout: 5))
        app.buttons["Discard changes"].tap()
        XCTAssertTrue(newWorkflow.waitForExistence(timeout: 5))
    }
}
