import XCTest

/// Lane P1 deliverables 4·5·6 on the phone, driven the way a person reaches them: the delete
/// dialog, the disconnect warning, the recording-consent reminder and the provider disclosure are
/// all *dialogs and forms*, and the only thing that can prove one of them opens — and that its
/// options are there, and that Cancel means something — is the app running as its own process.
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

    /// docs/03 "로그아웃 vs 연결 해제": two rows and two meanings, and the warning says the thing that
    /// makes them different — every device loses access, not only this one.
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
                NSPredicate(format: "label CONTAINS[c] %@", "Every device signed in")
            ).firstMatch.exists,
            "the warning does not say the other devices lose access"
        )
        // docs/03: the recordings stay unless this is checked — never the default.
        XCTAssertEqual(app.switches["disconnect-also-delete"].value as? String, "0")
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

    /// ADR-016: a workflow deleted here is gone from this phone and nothing syncs it back, so the
    /// row's button asks before it writes — the same question Android asks, and Cancel has to mean
    /// something. The seeded row is the one this phone records with (`WorkflowRepository.seed`), so
    /// the warning about what that costs is in the dialog too, and no longer on the row.
    func testDeletingAWorkflowIsAskedFirstAndCancelKeepsIt() throws {
        open(tab: "Workflows")
        try XCTSkipUnless(
            app.buttons["workflow-delete"].firstMatch.waitForExistence(timeout: 30),
            "no workflow to delete"
        )
        let rows = app.buttons.matching(identifier: "workflow-delete")
        let before = rows.count
        // Every row, because only one of them is the workflow this phone uses and which one that is
        // comes from the seed rather than from anything this test can say.
        var sawInUseWarning = false
        for index in 0..<before {
            rows.allElementsBoundByIndex[index].tap()

            let confirm = app.buttons["workflow-delete-confirm"]
            XCTAssertTrue(confirm.waitForExistence(timeout: 10), "a workflow was deleted with no question")
            XCTAssertTrue(app.staticTexts["workflow-delete-body"].exists, "the dialog says nothing")
            if app.staticTexts["workflow-delete-in-use"].exists {
                sawInUseWarning = true
                attach("workflow delete dialog")
            }

            app.buttons["Cancel"].firstMatch.tap()
            XCTAssertFalse(confirm.waitForExistence(timeout: 3), "the dialog stayed up")
        }

        XCTAssertTrue(sawInUseWarning, "no row said what deleting the workflow in use costs")
        XCTAssertEqual(rows.count, before, "Cancel deleted a workflow anyway")
    }

    /// The record screen's picker offers the workflows and nothing else: picking one is this phone's
    /// own pointer (ADR-016), so the node names it afterwards and the Workflows tab marks the same
    /// row as the one in use. There is no "default" anywhere in it — that vocabulary is gone.
    func testThePickerOffersTheWorkflowsAndTheNodeNamesTheOnePicked() throws {
        let picker = app.buttons["workflow"]
        XCTAssertTrue(picker.waitForExistence(timeout: 60), "the core never opened")
        attach("record screen")
        picker.tap()

        // The seed makes two, and the phone starts on 메모 / Memo (`WorkflowRepository.seed`), so
        // the *other* one is what a pick can move the pointer to. The app runs in whatever language
        // the simulator is set to; this asserts on the base language, as the smoke does.
        let meeting = app.buttons["Meeting"]
        XCTAssertTrue(meeting.waitForExistence(timeout: 10), "the picker offers no workflow")
        XCTAssertEqual(
            app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Default")).count, 0,
            "the picker still offers an entry about a default"
        )
        attach("workflow picker")
        meeting.tap()

        // The node names what was picked, which is the pointer now and not a choice that lasts one
        // recording.
        let named = expectation(
            for: NSPredicate(format: "label CONTAINS %@", "Meeting"), evaluatedWith: picker
        )
        XCTAssertEqual(
            XCTWaiter().wait(for: [named], timeout: 10), .completed,
            "the node does not name the workflow that was picked: \(picker.label)"
        )
        attach("record screen after the pick")

        // The same pick, seen from the list: the badge marks the row the picker selected.
        open(tab: "Workflows")
        XCTAssertTrue(
            app.staticTexts["In use"].waitForExistence(timeout: 30),
            "no row is marked as the one in use"
        )
        attach("workflows list")
    }

    /// docs/15 §3, lane P1 deliverable 6: the editor says what leaves the phone when the step runs,
    /// under the provider picker of the `transcribe` form.
    ///
    /// Getting there walks the step picker docs/09 화면 원칙 5 turned from a four-button
    /// `confirmationDialog` into a dialog of this design. Nothing is saved.
    func testTheProviderDisclosureIsUnderTheProviderPickerOfTheTranscribeForm() throws {
        try openTheTranscribeForm()

        let disclosure = app.staticTexts["provider-disclosure"]
        XCTAssertTrue(disclosure.waitForExistence(timeout: 10), "no disclosure on the transcribe form")
        // docs/15 §3 "작성 규칙": whose policy it is, and never how long they keep it.
        XCTAssertTrue(disclosure.label.contains("provider"), disclosure.label)
        XCTAssertFalse(disclosure.label.contains("http"), "the disclosure carries a link")
        attach("transcribe form")

        app.buttons["Cancel"].firstMatch.tap()
    }

    /// docs/07 rule 4 · docs/08 "폴링 · 상태": fourteen provider ids are a list and not a row of
    /// chips, so the form's control is a button saying the one in effect and the list is behind it —
    /// in `WorkflowParser.STT_PROVIDERS` order, which is the order the core declares and the one
    /// thing a dialog can lose. Choosing one that answers on a single long request is also the one
    /// choice the phone has something to say about, and it says it under the picker. Nothing is
    /// saved.
    func testTheProviderPickerListsEveryProviderInOrderAndWarnsAPhoneAboutASynchronousOne() throws {
        try openTheTranscribeForm()

        let picker = app.buttons["step-provider"]
        XCTAssertTrue(picker.waitForExistence(timeout: 10), "no provider picker on the transcribe form")
        // The button says the value and the label above it says what the value is of, so what a
        // reader hears is "Provider, assemblyai" — the seeded default of a new `transcribe` step.
        XCTAssertTrue(shown(picker).contains("assemblyai"), shown(picker))
        picker.tap()

        // `WorkflowParser.STT_PROVIDERS`, verbatim and in order.
        let providers = [
            "assemblyai", "clova", "rtzr",
            "openai", "groq", "together", "mistral",
            "elevenlabs", "deepgram", "azure",
            "daglo", "speechmatics", "rev", "gladia",
        ]
        XCTAssertTrue(
            app.buttons["provider-assemblyai"].waitForExistence(timeout: 10),
            "the picker opened no list"
        )
        // The card is as tall as its list, and fourteen rows are taller than the sheet opens: the
        // last of them is reached the way a person reaches it.
        let gladia = app.buttons["provider-gladia"]
        if !gladia.isHittable { app.swipeUp() }
        var top = -CGFloat.greatestFiniteMagnitude
        for name in providers {
            let row = app.buttons["provider-" + name]
            XCTAssertTrue(row.exists, "the list has no \(name)")
            // Only against the row before it, and only among the ones on screen together: a frame
            // is what the list's order looks like, and an offscreen row has no honest one.
            if row.isHittable {
                XCTAssertGreaterThan(row.frame.minY, top, "\(name) is out of order")
                top = row.frame.minY
            }
        }
        attach("provider dialog")

        app.buttons["provider-openai"].tap()
        XCTAssertFalse(
            app.buttons["provider-gladia"].waitForExistence(timeout: 3),
            "the list stayed up after an answer"
        )
        XCTAssertTrue(shown(picker).contains("openai"), shown(picker))

        // docs/08: the background budget is a phone's, so this is the one warning the Mac's form
        // does not carry.
        let warning = app.staticTexts["provider-synchronous-hint"]
        XCTAssertTrue(warning.waitForExistence(timeout: 5), "no warning about a synchronous provider")
        XCTAssertTrue(warning.label.contains("asynchronous"), warning.label)
        attach("synchronous hint")

        app.buttons["Cancel"].firstMatch.tap()
    }

    // MARK: - Pieces

    /// The `transcribe` form, reached the way a person reaches it. The seeded workflows are
    /// `drive.upload` alone (`WorkflowRepository.defaults`), so the step is added here — which also
    /// walks the step picker on the way.
    private func openTheTranscribeForm() throws {
        open(tab: "Workflows")
        // The row is the way in: the editor is what a workflow row opens, as it is on Android.
        let edit = app.buttons["workflow-open"].firstMatch
        try XCTSkipUnless(edit.waitForExistence(timeout: 30), "no workflow to open")
        edit.tap()

        // The `+` on the closing connector: a step appended after the last one.
        let plus = app.buttons.matching(identifier: "Add a step here").allElementsBoundByIndex
        XCTAssertFalse(plus.isEmpty, "the node graph has no way to add a step")
        plus[plus.count - 1].tap()

        let add = app.buttons["add-Transcribe"]
        XCTAssertTrue(add.waitForExistence(timeout: 5), "the step picker has no Transcribe")
        attach("add a step")
        add.tap()
    }

    /// What a reader is told about a control that carries its value beside its name.
    private func shown(_ element: XCUIElement) -> String {
        "\(element.label) \(element.value as? String ?? "")"
    }

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
