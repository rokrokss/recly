import XCTest

/// The mobile editor must remain usable while its software keyboard occupies the lower screen.
final class MobileUxTests: XCTestCase {
    func testWorkflowActionsProtectTheSelectionAndStayOnTheRight() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US", "-appLanguage", "en"]
        app.launch()
        let workflows = app.tabBars.buttons["Workflows"]
        XCTAssertTrue(workflows.waitForExistence(timeout: 30))
        workflows.tap()
        let deletes = app.buttons.matching(identifier: "workflow-delete")
        XCTAssertTrue(deletes.firstMatch.waitForExistence(timeout: 30))
        let before = deletes.count
        let summary = app.staticTexts["workflow-name-label"].firstMatch
        XCTAssertTrue(summary.exists)
        summary.tap()
        XCTAssertTrue(app.buttons["newWorkflow"].exists)
        let edit = app.buttons["workflow-edit"].firstMatch
        XCTAssertEqual(edit.label, "Edit")
        edit.tap()
        XCTAssertTrue(app.textFields["Name"].firstMatch.waitForExistence(timeout: 10))
        app.buttons["Cancel"].firstMatch.tap()
        XCTAssertTrue(app.buttons["newWorkflow"].waitForExistence(timeout: 10))
        XCTAssertEqual(deletes.allElementsBoundByIndex.filter { !$0.isEnabled }.count, 1)
        XCTAssertFalse(app.staticTexts["workflow-delete-in-use"].exists)

        let addSecret = app.buttons["add-secret"]
        for _ in 0..<5 where !addSecret.isHittable { app.scrollViews.firstMatch.swipeUp() }
        XCTAssertTrue(addSecret.isHittable)
        XCTAssertGreaterThan(addSecret.frame.midX, app.frame.midX)
        addSecret.tap()
        let cancel = app.buttons["Cancel"].firstMatch
        for _ in 0..<5 where !cancel.isHittable { app.scrollViews.firstMatch.swipeUp() }
        XCTAssertTrue(cancel.isHittable)
        XCTAssertGreaterThan(cancel.frame.midX, app.frame.midX)
        cancel.tap()

        app.buttons["newWorkflow"].tap()
        let name = app.textFields["Name"].firstMatch
        XCTAssertTrue(name.waitForExistence(timeout: 10))
        name.tap()
        name.typeText("Workflow action test")
        app.buttons["saveWorkflow"].tap()
        XCTAssertTrue(app.buttons["newWorkflow"].waitForExistence(timeout: 15))
        XCTAssertEqual(deletes.count, before + 1)
        let enabledDelete = deletes.element(boundBy: before)
        for _ in 0..<5 where !enabledDelete.isHittable { app.scrollViews.firstMatch.swipeUp() }
        let use = app.buttons.matching(identifier: "Use").allElementsBoundByIndex.last!
        XCTAssertTrue(use.isHittable)
        XCTAssertTrue(enabledDelete.isEnabled)
        XCTAssertGreaterThan(enabledDelete.frame.midX, use.frame.maxX)
        enabledDelete.tap()
        let confirm = app.buttons["workflow-delete-confirm"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 5))
        XCTAssertTrue(confirm.isEnabled)
        confirm.tap()
        XCTAssertFalse(confirm.waitForExistence(timeout: 3))
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = "Workflow actions and protected selection"
        shot.lifetime = .keepAlways
        add(shot)
    }

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
        XCTAssertEqual(help.label, "How to set up transcription")
        let newWorkflow = app.buttons["newWorkflow"]
        XCTAssertLessThan(help.frame.maxX, newWorkflow.frame.minX)
        let originalFrame = newWorkflow.frame
        help.tap()
        let body = app.staticTexts["transcription-setup-body"]
        XCTAssertTrue(body.waitForExistence(timeout: 5))
        XCTAssertTrue(body.label.hasSuffix("are still saved."))
        XCTAssertGreaterThan(body.frame.height, 40)
        app.buttons["transcription-setup-close"].tap()
        XCTAssertTrue(newWorkflow.isHittable)
        XCTAssertEqual(newWorkflow.frame, originalFrame)
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

    func testKoreanLargeTextWorkflowActionsRemainReachable() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(ko)", "-AppleLocale", "ko_KR", "-appLanguage", "ko",
            "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()
        let workflows = app.tabBars.buttons["워크플로우"]
        XCTAssertTrue(workflows.waitForExistence(timeout: 30))
        workflows.tap()
        let newWorkflow = app.buttons["newWorkflow"]
        XCTAssertTrue(newWorkflow.waitForExistence(timeout: 30))
        XCTAssertTrue(newWorkflow.isHittable)
        XCTAssertEqual(newWorkflow.label, "새 워크플로우")
        XCTAssertFalse(app.staticTexts["workflow-delete-in-use"].exists)
        let addSecret = app.buttons["add-secret"]
        let tabBar = app.tabBars.firstMatch
        for _ in 0..<6 where !addSecret.isHittable || addSecret.frame.maxY > tabBar.frame.minY {
            app.scrollViews.firstMatch.swipeUp()
        }
        XCTAssertTrue(addSecret.isHittable)
        XCTAssertGreaterThan(addSecret.frame.midX, app.frame.midX)
        XCTAssertLessThanOrEqual(addSecret.frame.maxX, app.frame.maxX)
        XCTAssertLessThanOrEqual(addSecret.frame.maxY, tabBar.frame.minY)
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = "Korean accessibility text workflow actions"
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
