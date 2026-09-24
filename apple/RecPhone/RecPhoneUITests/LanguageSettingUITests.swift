import XCTest

/// The real tab bar, language picker, and existing screen must change without a relaunch.
final class LanguageSettingUITests: XCTestCase {
    private let settingsLabels = ["Settings", "설정", "設定", "设置", "Ajustes", "Réglages", "Einstellungen", "Configurações", "الإعدادات", "सेटिंग", "Настройки"]

    override func setUp() { continueAfterFailure = false }

    func testGlobalLanguagesChangeImmediatelyAndArabicSurvivesRelaunch() {
        let app = XCUIApplication()
        // Do not override appLanguage: the argument domain would mask choices written by the UI.
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        openSettings(app)
        choose("en", in: app)
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 10))

        for (tag, title, name) in [("ja", "設定", "日本語"), ("zh-Hant", "設定", "繁體中文"), ("ar", "الإعدادات", "العربية")] {
            choose(tag, in: app)
            XCTAssertTrue(app.navigationBars[title].waitForExistence(timeout: 10))
            XCTAssertTrue(app.buttons["language"].label.contains(name))
            attach(named: tag + "-settings")
        }
        let record = app.tabBars.buttons["تسجيل"]
        let settings = app.tabBars.buttons["الإعدادات"]
        XCTAssertGreaterThan(record.frame.midX, settings.frame.midX, "Arabic tabs should flow from right to left")
        record.tap()
        XCTAssertTrue(app.buttons["start"].waitForExistence(timeout: 30))
        XCTAssertEqual(app.buttons["start"].label, "بدء التسجيل")
        app.terminate()
        app.launch()
        XCTAssertTrue(app.buttons["start"].waitForExistence(timeout: 30))
        XCTAssertEqual(app.buttons["start"].label, "بدء التسجيل", "The choice must survive a relaunch")

        openSettings(app)
        choose("ko", in: app)
        XCTAssertTrue(app.navigationBars["설정"].waitForExistence(timeout: 10))
        choose("en", in: app)
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 10))
    }

    private func openSettings(_ app: XCUIApplication) {
        let tab = app.tabBars.buttons.matching(NSPredicate(format: "label IN %@", settingsLabels)).firstMatch
        XCTAssertTrue(tab.waitForExistence(timeout: 30))
        tab.tap()
    }

    private func choose(_ tag: String, in app: XCUIApplication) {
        let row = app.buttons["language"]
        XCTAssertTrue(row.waitForExistence(timeout: 10))
        for _ in 0..<8 where !row.isHittable { app.scrollViews.firstMatch.swipeUp() }
        XCTAssertTrue(row.isHittable)
        row.tap()
        let choice = app.buttons["language-" + tag]
        XCTAssertTrue(choice.waitForExistence(timeout: 10))
        for _ in 0..<8 where !choice.isHittable { app.scrollViews.firstMatch.swipeUp() }
        XCTAssertTrue(choice.isHittable)
        choice.tap()
    }

    private func attach(named name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
