import Darwin
import Foundation
import RecKitTestSupport
import ReclyCore
import XCTest
@testable import RecKit

/// docs/15 §10 "Sign in with ChatGPT": the loopback OpenAI sends the browser back to, over a real socket.
final class ChatGptLoopbackTests: XCTestCase {
    private var loopback: ChatGptLoopback!

    override func tearDown() {
        loopback?.stop()
        loopback = nil
    }

    func testTheCallbackWithItsStateIsAnsweredAndHandedOverWhole() async throws {
        loopback = ChatGptLoopback(matched: { _ in .page("connected") })
        let port = try await loopback.start()
        XCTAssertGreaterThan(port, 0)
        loopback.expect(state: "s1")

        let response = try await HTTP.get(port: port, target: "/auth/callback?code=c1&state=s1")
        XCTAssertTrue(response.hasPrefix("HTTP/1.1 200 OK"), response)
        XCTAssertTrue(response.contains("Cache-Control: no-store"), response)
        XCTAssertTrue(response.hasSuffix("connected"), response)
        let callback = await loopback.callback()
        XCTAssertEqual(callback, "http://127.0.0.1:\(port)/auth/callback?code=c1&state=s1")
        XCTAssertEqual(ChatGptLoopback.redirectUri(port: port), "http://127.0.0.1:\(port)/auth/callback")
    }

    func testAnythingElseIsA404AndTheWaitGoesOn() async throws {
        loopback = ChatGptLoopback(matched: { _ in .page("connected") })
        let port = try await loopback.start()
        // Before the sign-in has its state, not even the right path is the callback.
        let early = try await HTTP.get(port: port, target: "/auth/callback?code=c1&state=s1")
        XCTAssertTrue(early.hasPrefix("HTTP/1.1 404"), early)
        loopback.expect(state: "s1")

        for target in ["/favicon.ico", "/auth/callback?code=c1&state=other", "/auth/callback?code=c1", "/auth/callbackx?state=s1"] {
            let response = try await HTTP.get(port: port, target: target)
            XCTAssertTrue(response.hasPrefix("HTTP/1.1 404"), "\(target): \(response)")
        }
        let post = try await HTTP.send(port: port, request: "POST /auth/callback?state=s1 HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
        XCTAssertTrue(post.hasPrefix("HTTP/1.1 404"), post)

        let response = try await HTTP.get(port: port, target: "/auth/callback?state=s1&code=c2")
        XCTAssertTrue(response.hasPrefix("HTTP/1.1 200"), response)
        let callback = await loopback.callback()
        XCTAssertEqual(callback, "http://127.0.0.1:\(port)/auth/callback?state=s1&code=c2")
    }

    /// One callback only: a reload, or a second tab, gets the page that says it is done.
    func testASecondCallbackIsToldItIsAlreadyHandled() async throws {
        loopback = ChatGptLoopback(matched: { _ in .page("connected") })
        let port = try await loopback.start()
        loopback.expect(state: "s1")
        _ = try await HTTP.get(port: port, target: "/auth/callback?code=c1&state=s1")
        let again = try await HTTP.get(port: port, target: "/auth/callback?code=c9&state=s1")
        XCTAssertTrue(again.hasPrefix("HTTP/1.1 200"), again)
        XCTAssertTrue(again.contains(RecKitStrings.localized("This sign-in has already been handled. You can close this window.")), again)
        let callback = await loopback.callback()
        XCTAssertEqual(callback, "http://127.0.0.1:\(port)/auth/callback?code=c1&state=s1")
    }

    /// The iPhone's answer: a redirect to the scheme its authentication sheet closes on.
    func testTheCallbackCanBeAnsweredWithARedirect() async throws {
        loopback = ChatGptLoopback(matched: { _ in .redirect("recly-chatgpt://signed-in") })
        let port = try await loopback.start()
        loopback.expect(state: "s1")
        let response = try await HTTP.get(port: port, target: "/auth/callback?code=c1&state=s1")
        XCTAssertTrue(response.hasPrefix("HTTP/1.1 302 Found"), response)
        XCTAssertTrue(response.contains("\r\nLocation: recly-chatgpt://signed-in\r\n"), response)
        let callback = await loopback.callback()
        XCTAssertNotNil(callback)
    }

    func testStoppingEndsTheWaitWithNoCallbackAndClosesThePort() async throws {
        loopback = ChatGptLoopback(matched: { _ in .page("connected") })
        let port = try await loopback.start()
        loopback.expect(state: "s1")
        let waiting = Task { await loopback.callback() }
        try await Task.sleep(for: .milliseconds(50))
        loopback.stop()
        let callback = await waiting.value
        XCTAssertNil(callback)
        try await Task.sleep(for: .milliseconds(100))
        let refused = await HTTP.refused(host: "127.0.0.1", port: port)
        XCTAssertTrue(refused)
    }

    /// RFC 8252 §8.3: the loopback interface only. Another address of this machine reaches nothing.
    func testItListensOnTheLoopbackAddressOnly() async throws {
        loopback = ChatGptLoopback(matched: { _ in .page("connected") })
        let port = try await loopback.start()
        guard let other = HTTP.nonLoopbackIPv4() else { throw XCTSkip("no other IPv4 address on this machine") }
        let refused = await HTTP.refused(host: other, port: port)
        XCTAssertTrue(refused, "reachable on \(other)")
    }

    func testThePageIsOneInlineDocumentWithTheSentenceEscaped() {
        let page = ChatGptLoopback.page("A < B & C")
        XCTAssertTrue(page.contains("<p>A &lt; B &amp; C</p>"), page)
        XCTAssertFalse(page.contains("src="))
        XCTAssertFalse(page.contains("href="))
    }
}

/// docs/09 "AI view": the section's sign-in, run against the real core with a browser that stands in for
/// the user. None of these reach OpenAI: a decline is answered before any token request.
@MainActor
final class ChatGptSettingsTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("ChatGptSettingsTests-\(UUID().uuidString)")
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    #if os(macOS)
    func testADeclineAtOpenAIIsSilentAndAnsweredWithTheCancelledPage() async throws {
        let bridge = try await bridge()
        let browser = FakeBrowser(answer: DefaultBrowser().answer)
        browser.onOpen = { url, _ in
            let query = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
            let redirect = try XCTUnwrap(query.first { $0.name == "redirect_uri" }?.value)
            let state = try XCTUnwrap(query.first { $0.name == "state" }?.value)
            let callback = try XCTUnwrap(URLComponents(string: redirect))
            XCTAssertEqual(callback.host, "127.0.0.1")
            XCTAssertEqual(callback.path, "/auth/callback")
            let port = try XCTUnwrap(callback.port)
            browser.page = try await HTTP.get(port: UInt16(port), target: "/auth/callback?error=access_denied&state=\(state)")
        }
        let model = ChatGptSettingsModel(core: bridge.core, browser: browser)
        model.signIn()
        XCTAssertEqual(model.signInState, .processing)
        try await until { model.signInState != .processing }
        XCTAssertEqual(model.signInState, .idle)
        XCTAssertNil(model.failure)
        XCTAssertFalse(model.waitingForBrowser)
        XCTAssertFalse(model.welcome)
        XCTAssertTrue(browser.closed)
        XCTAssertTrue(browser.page?.contains(RecKitStrings.localized("The sign-in was cancelled. Close this window and go back to Recly.")) == true)
    }
    #endif

    func testClosingTheBrowserCancelsTheSignIn() async throws {
        let bridge = try await bridge()
        let browser = FakeBrowser(answer: { _ in .page("connected") })
        browser.onOpen = { _, dismissed in dismissed() }
        let model = ChatGptSettingsModel(core: bridge.core, browser: browser)
        model.signIn()
        try await until { model.signInState != .processing }
        XCTAssertEqual(model.signInState, .idle)
        XCTAssertNil(model.failure)
        // A callback after the cancel is the core's to refuse: it has no sign-in pending any more.
        let late = try await bridge.core.chatGpt.finishSignIn(callbackUrl: "http://127.0.0.1:1/auth/callback?code=c&state=s")
        XCTAssertTrue(ChatGptText.cancelled((late as? ChatGptResult.Failed)?.reason ?? ""))
    }

    /// No browser came up for the authorization page. That is not the user cancelling — nothing on screen would
    /// say what happened — so it is said, in Windows' words: `Could not sign in to ChatGPT` and `no browser opened`.
    func testABrowserThatNeverOpenedIsAFailureToSay() async throws {
        let bridge = try await bridge()
        let browser = FakeBrowser(answer: { _ in .page("connected") })
        browser.opens = false
        let model = ChatGptSettingsModel(core: bridge.core, browser: browser)
        model.signIn()
        try await until { model.signInState != .processing }
        XCTAssertEqual(model.signInState, .failed)
        XCTAssertEqual(model.failure, .signIn(ChatGptSettingsModel.noBrowser))
        XCTAssertFalse(model.waitingForBrowser)
        XCTAssertEqual(ChatGptText.detail(ChatGptSettingsModel.noBrowser), "no browser opened")
    }

    func testTheWaitEndsWhenTheUserCancelsOrTimeRunsOut() async throws {
        let bridge = try await bridge()
        let browser = FakeBrowser(answer: { _ in .page("connected") })
        let model = ChatGptSettingsModel(core: bridge.core, browser: browser)
        model.signIn()
        try await until { model.waitingForBrowser }
        model.cancelSignIn()
        try await until { model.signInState != .processing }
        XCTAssertEqual(model.signInState, .idle)
        XCTAssertNil(model.failure)

        let timed = ChatGptSettingsModel(core: bridge.core, browser: FakeBrowser(answer: { _ in .page("connected") }), timeout: .milliseconds(200))
        timed.signIn()
        try await until { timed.signInState != .processing }
        XCTAssertEqual(timed.signInState, .idle)
        XCTAssertFalse(timed.waitingForBrowser)
    }

    /// docs/09 "AI view" — Settings → Notes: the fields save when editing ends, trimmed, and what is being typed stays
    /// as typed; Sections are the four built-in groups in their own words, then the user's own — a section editing
    /// leaves without a name goes from the list, the rest go when the screen does; three of their own at most; Remove
    /// saves at once; and the saved sections are the list the next time Settings opens.
    func testNotesSettingsSaveWhenEditingEnds() async throws {
        AppLanguage.current = .en
        defer { AppLanguage.current = .system }
        let bridge = try await bridge()
        let model = ChatGptSettingsModel(core: bridge.core, browser: FakeBrowser(answer: { _ in .page("connected") }))
        // The saved preferences arrive first, as they do long before anyone types on a screen.
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(model.instructions, "")
        XCTAssertEqual(model.sections.map(\.name), ["Key points", "Decisions", "To-dos", "Questions"])
        XCTAssertEqual(model.sections.map(\.sectionId), ["point", "decision", "todo", "question"])

        model.instructions = "Group to-dos by owner.  "
        model.saveNotes()
        try await until { model.notesPreferences.instructions == "Group to-dos by owner." }
        XCTAssertEqual(model.instructions, "Group to-dos by owner.  ")

        let empty = try XCTUnwrap(model.addSection())
        model.saveNotes()
        XCTAssertEqual(model.sections.count, 5, "a section editing is still in stays")
        model.saveNotes(leaving: empty)
        XCTAssertEqual(model.sections.count, 4, "left without a name, it goes")

        let risks = try XCTUnwrap(model.addSection())
        model.sections[4].name = " Risks "
        model.sections[4].description = "Anything that could delay the launch "
        model.saveNotes(leaving: risks)
        try await until { model.notesPreferences.sections.count == 5 }
        let saved = try await bridge.core.notes.preferences()
        XCTAssertEqual(saved.sections.map(\.name), ["", "", "", "", "Risks"])
        XCTAssertEqual(saved.sections.last?.description_, "Anything that could delay the launch")
        XCTAssertEqual(model.sections.map(\.sectionId), saved.sections.map(\.id), "the section takes the id the core gave it")
        XCTAssertEqual(model.sections.last?.name, " Risks ")

        XCTAssertNotNil(model.addSection())
        XCTAssertNotNil(model.addSection())
        XCTAssertNil(model.addSection(), "three of their own at most")
        XCTAssertEqual(model.sections.count, 7)
        model.saveNotes(droppingNameless: true)
        XCTAssertEqual(
            model.sections.map(\.name), ["Key points", "Decisions", "To-dos", "Questions", " Risks "],
            "the screen goes, and the nameless ones with it"
        )

        let reopened = ChatGptSettingsModel(core: bridge.core, browser: FakeBrowser(answer: { _ in .page("connected") }))
        try await until { reopened.sections.count == 5 }
        XCTAssertEqual(reopened.sections.map(\.name), ["Key points", "Decisions", "To-dos", "Questions", "Risks"])
        XCTAssertEqual(reopened.instructions, "Group to-dos by owner.")

        model.removeSection(model.sections[4].key)
        XCTAssertEqual(model.sections.count, 4)
        try await until { model.notesPreferences.sections.count == 4 }
        let last = try await bridge.core.notes.preferences()
        XCTAssertEqual(last.sections, NoteSection.companion.DEFAULTS)
        XCTAssertEqual(last.instructions, "Group to-dos by owner.")
    }

    /// docs/09 "AI view": a built-in group's fields hold its own words while the user has given it none, and those save
    /// as empty — its own — so an untouched group is as it comes and nothing is saved for it. A field left blank stays
    /// in the list, saves as its own and shows its own words again once editing leaves the row.
    func testABuiltInGroupInItsOwnWordsOrLeftBlankSavesAsItsOwn() async throws {
        AppLanguage.current = .en
        defer { AppLanguage.current = .system }
        let bridge = try await bridge()
        let model = ChatGptSettingsModel(core: bridge.core, browser: FakeBrowser(answer: { _ in .page("connected") }))
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(model.sections.map(\.description), [
            "Facts, numbers, ideas and conclusions worth remembering.",
            "What was agreed or decided.",
            "Who will do what, and by when.",
            "Questions still without an answer.",
        ])
        XCTAssertEqual(model.shownNotes.sections.map(\.name), ["", "", "", ""], "untouched, a group is its own")
        XCTAssertEqual(model.shownNotes.sections.map(\.description_), ["", "", "", ""])
        XCTAssertEqual(model.shownNotes, model.notesPreferences, "nothing to save")
        XCTAssertFalse(model.restoresDefaults)

        let decisions = model.sections[1].key
        model.sections[1].name = "  "
        model.sections[1].description = ""
        XCTAssertEqual(model.shownNotes.sections.map(\.name), ["", "", "", ""], "a blank name is the group's own")
        XCTAssertEqual(model.shownNotes.sections[1].description_, "")
        model.saveNotes()
        XCTAssertEqual(model.sections[1].name, "  ", "editing is still in the row")
        model.saveNotes(leaving: decisions)
        XCTAssertEqual(model.sections.count, 4, "a built-in group left blank stays")
        XCTAssertEqual(model.sections[1].name, "Decisions")
        XCTAssertEqual(model.sections[1].description, "What was agreed or decided.")
        XCTAssertEqual(model.shownNotes, model.notesPreferences, "still nothing to save")

        model.sections[0].name = "Highlights "
        model.saveNotes(leaving: model.sections[0].key)
        try await until { model.notesPreferences.sections.first?.name == "Highlights" }
        XCTAssertEqual(model.sections[0].name, "Highlights ", "what is typed stays as typed")
        XCTAssertEqual(model.notesPreferences.sections.first?.description_, "", "what goes in it is still its own")
        XCTAssertTrue(model.restoresDefaults)
        let saved = try await bridge.core.notes.preferences()
        XCTAssertEqual(saved.sections.map(\.name), ["Highlights", "", "", ""])
    }

    /// docs/09 "AI view": Remove takes a built-in group like any section. `Restore defaults` is there while a built-in
    /// group is removed, renamed or described anew; it brings the four back in their own words, leaves the user's own
    /// sections as they are — named or not — and saves at once.
    func testRestoreDefaultsBringsTheBuiltInGroupsBackAndKeepsTheUsersOwn() async throws {
        AppLanguage.current = .en
        defer { AppLanguage.current = .system }
        let bridge = try await bridge()
        let model = ChatGptSettingsModel(core: bridge.core, browser: FakeBrowser(answer: { _ in .page("connected") }))
        try await Task.sleep(for: .milliseconds(300))
        let risks = try XCTUnwrap(model.addSection())
        model.sections[4].name = "Risks"
        model.saveNotes(leaving: risks)
        model.sections[2].description = "Only what the team owns."
        model.saveNotes(leaving: model.sections[2].key)
        model.removeSection(model.sections[0].key)
        XCTAssertEqual(model.sections.map(\.name), ["Decisions", "To-dos", "Questions", "Risks"])
        XCTAssertTrue(model.restoresDefaults)
        try await until { model.notesPreferences.sections.map { $0.kind } == [.decision, .todo, .question, .section] }
        let changed = try await bridge.core.notes.preferences()
        XCTAssertEqual(changed.sections[1].description_, "Only what the team owns.")

        let unnamed = try XCTUnwrap(model.addSection())
        model.restoreDefaults()
        XCTAssertEqual(model.sections.map(\.name), ["Key points", "Decisions", "To-dos", "Questions", "Risks", ""], "the user's own stay")
        XCTAssertEqual(model.sections[2].description, "Who will do what, and by when.")
        XCTAssertEqual(model.sections.last?.key, unnamed)
        XCTAssertFalse(model.restoresDefaults)
        try await until { model.notesPreferences.sections.count == 5 && !model.notesPreferences.builtInsChanged }
        let restored = try await bridge.core.notes.preferences()
        XCTAssertEqual(Array(restored.sections.prefix(4)), NoteSection.companion.DEFAULTS)
        XCTAssertEqual(restored.sections.last?.name, "Risks")
        XCTAssertEqual(model.sections[4].sectionId, restored.sections.last?.id, "kept, with the id it had")
    }

    /// docs/09 "AI view": `Add section` counts the user's own sections only — three of them beside the four built-in
    /// groups, and no more with the built-in groups removed.
    func testAddSectionCountsTheUsersOwnSectionsOnly() async throws {
        let bridge = try await bridge()
        let model = ChatGptSettingsModel(core: bridge.core, browser: FakeBrowser(answer: { _ in .page("connected") }))
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(model.sections.count, 4)
        XCTAssertTrue(model.addsSection)
        for _ in 0..<3 { XCTAssertNotNil(model.addSection()) }
        XCTAssertFalse(model.addsSection)
        XCTAssertNil(model.addSection(), "three of their own at most")
        XCTAssertEqual(model.sections.count, 7)
        for key in model.sections.filter(\.builtIn).map(\.key) { model.removeSection(key) }
        XCTAssertEqual(model.sections.count, 3)
        XCTAssertNil(model.addSection(), "the built-in groups gone make no room")
    }

    /// docs/07 rule 3: a built-in group's field still in its own words says them in the new language and still saves as
    /// its own; one the user changed stays as they wrote it.
    func testABuiltInGroupsOwnWordsFollowTheAppLanguage() async throws {
        AppLanguage.current = .en
        defer { AppLanguage.current = .system }
        let bridge = try await bridge()
        let model = ChatGptSettingsModel(core: bridge.core, browser: FakeBrowser(answer: { _ in .page("connected") }))
        try await Task.sleep(for: .milliseconds(300))
        model.sections[1].name = "Agreed"
        AppLanguage.current = .ko
        NotificationCenter.default.post(name: AppLanguage.didChange, object: nil)
        try await until { model.sections[0].name == "핵심" }
        XCTAssertEqual(model.sections.map(\.name), ["핵심", "Agreed", "할 일", "질문"])
        XCTAssertEqual(model.sections[3].description, "아직 답이 나오지 않은 질문.")
        XCTAssertEqual(model.shownNotes.sections.map(\.name), ["", "Agreed", "", ""])
        XCTAssertEqual(model.shownNotes.sections.map(\.description_), ["", "", "", ""])
    }

    /// docs/09 "AI view": About you, under Notes, saves when its editing ends, trimmed; the field keeps what is typed.
    func testAboutYouSavesWhenEditingEnds() async throws {
        let bridge = try await bridge()
        let model = ChatGptSettingsModel(core: bridge.core, browser: FakeBrowser(answer: { _ in .page("connected") }))
        try await Task.sleep(for: .milliseconds(300))

        model.aboutMe = " PM "
        model.savePreferences()
        try await until { model.preferences.aboutMe == "PM" }
        XCTAssertEqual(model.aboutMe, " PM ")
        let saved = try await bridge.core.summaries.preferences()
        XCTAssertEqual(saved.aboutMe, "PM")
    }

    /// docs/08 "Titles and speaker names": signed out, a publication fills nothing in, so the title sheet says
    /// `Untitled`; the Mac needs no permission for the plan as a destination.
    func testSignedOutTheTitleSheetSaysUntitled() async throws {
        let bridge = try await bridge()
        let model = ChatGptSettingsModel(core: bridge.core, browser: FakeBrowser(answer: { _ in .page("connected") }))
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertTrue(model.fillAllowed, "the Mac needs no permission")
        XCTAssertFalse(model.autoFill, "signed out")
        XCTAssertEqual(TitlePlaceholder.text(autoFill: model.autoFill), RecKitStrings.localized("Untitled"))
        XCTAssertEqual(TitlePlaceholder.text(autoFill: true), RecKitStrings.localized("Filled in automatically"))
    }

    private func bridge() async throws -> CoreBridge {
        try await CoreBridge.make(
            deviceName: "ChatGptSettingsTests", dataDirectory: directory,
            databaseName: "chatgpt-settings-tests.db", secureStore: InMemorySecureStore()
        )
    }

    private func until(_ condition: () -> Bool) async throws {
        for _ in 0..<200 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(25))
        }
        XCTFail("timed out")
    }
}

/// docs/09 "Detail header and More menu" · "AI view": the items' reasons, the notes' footer and editor, Share's Notes
/// and a search hit in the notes, and the words a failed Organize is shown with.
@MainActor
final class NotesMenuTests: XCTestCase {
    private let signedIn = ChatGptConnection.SignedIn(account: "a@example.com", models: [ChatGptModel(id: "gpt-x", label: "GPT X")], model: "gpt-x")

    /// docs/09 "AI view": Organize says the first reason in its order — Still recording, No transcript yet or the
    /// unsettled job's, the sign-in — then `Organizing…` while a pass runs.
    func testTheOrganizeReasonSaysWhatStandsInTheWay() {
        func reason(writing: Bool = false, transcript: Bool = true, busy: String? = nil,
                    connection: ChatGptConnection? = nil, organizing: Bool = false) -> String? {
            RecordingDetailModel.organizeReason(
                writing: writing, hasTranscript: transcript, busy: busy, connection: connection ?? signedIn, organizing: organizing
            )
        }
        XCTAssertNil(reason())
        XCTAssertEqual(reason(writing: true, transcript: false, organizing: true), RecKitStrings.localized("Still recording"))
        XCTAssertEqual(reason(transcript: false), RecKitStrings.localized("No transcript yet"))
        XCTAssertEqual(reason(busy: RecKitStrings.localized("Transcribing…")), RecKitStrings.localized("Transcribing…"))
        let signIn = CoreMessages.sentence(.chatgptSignInRequired)
        XCTAssertEqual(signIn, RecKitStrings.localized("Sign in to ChatGPT in Settings"))
        XCTAssertEqual(reason(connection: ChatGptConnection.SignedOut.shared, organizing: true), signIn)
        XCTAssertEqual(reason(connection: ChatGptConnection.Expired(account: "a@example.com")), signIn)
        XCTAssertEqual(reason(organizing: true), RecKitStrings.localized("Organizing…"))
    }

    /// docs/09 "AI view" — "Editing": Edit notes is off while the recording records, and while a pass writes the notes
    /// over the ones it would edit.
    func testEditNotesWaitsForTheRecordingAndForAPass() {
        XCTAssertNil(RecordingDetailModel.editNotesReason(writing: false, organizing: false))
        XCTAssertEqual(RecordingDetailModel.editNotesReason(writing: false, organizing: true), RecKitStrings.localized("Organizing…"))
        XCTAssertEqual(RecordingDetailModel.editNotesReason(writing: true, organizing: true), RecKitStrings.localized("Still recording"))
    }

    /// docs/09 "Detail header and More menu": Ask stands in the way of nothing but what Organize does — a pass being run
    /// is no reason.
    func testAskHasOrganizesReasonsButNotARunningPass() {
        func ask(writing: Bool = false, transcript: Bool = true, busy: String? = nil, connection: ChatGptConnection? = nil) -> String? {
            RecordingDetailModel.askReason(writing: writing, hasTranscript: transcript, busy: busy, connection: connection ?? signedIn)
        }
        XCTAssertNil(ask())
        XCTAssertEqual(ask(writing: true, transcript: false), RecKitStrings.localized("Still recording"))
        XCTAssertEqual(ask(transcript: false), RecKitStrings.localized("No transcript yet"))
        XCTAssertEqual(ask(busy: RecKitStrings.localized("Transcribing…")), RecKitStrings.localized("Transcribing…"))
        XCTAssertEqual(ask(connection: ChatGptConnection.SignedOut.shared), CoreMessages.sentence(.chatgptSignInRequired))
        XCTAssertEqual(ask(connection: ChatGptConnection.Expired(account: "a@example.com")), CoreMessages.sentence(.chatgptSignInRequired))
    }

    func testAFailureIsItsOwnSentenceWhenTheUserCanActOnIt() {
        let limit = CoreMessage.chatgptUsageLimit.code(arg: nil, detail: nil)
        XCTAssertEqual(ChatGptText.sentence(limit), CoreMessages.sentence(.chatgptUsageLimit))
        XCTAssertEqual(ChatGptText.detail(limit), CoreMessages.sentence(.chatgptUsageLimit))
        let provider = CoreMessage.providerError.code(arg: nil, detail: "500 server_error")
        XCTAssertNil(ChatGptText.sentence(provider))
        XCTAssertEqual(ChatGptText.detail(provider), "500 server_error")
        XCTAssertTrue(ChatGptText.cancelled(CoreMessage.signInCancelled.code(arg: nil, detail: nil)))
        XCTAssertFalse(ChatGptText.cancelled(provider))
    }

    /// The editor's second line: the notes reach the other devices only from Drive or iCloud.
    func testTheEditorsLineFollowsTheRecordingsStorage() {
        XCTAssertTrue(RecordingDetailModel.notesStayHere(storage: nil))
        XCTAssertTrue(RecordingDetailModel.notesStayHere(storage: .folder))
        XCTAssertFalse(RecordingDetailModel.notesStayHere(storage: .drive))
        XCTAssertFalse(RecordingDetailModel.notesStayHere(storage: .icloud))
    }

    /// Save is offered only for words that changed: not for spaces around them, nor for a field left as it was. An item
    /// emptied is a change — the core deletes it — and with no overview there is no overview to change.
    func testADraftChangesOnlyWithWordsThatChanged() {
        let decision = NoteGroupsTests.item("d1", .decision, text: "Launch on Friday")
        let todo = NoteGroupsTests.item("t1", .todo, text: "Send the notes", owner: "Minji", due: "Thursday")
        let notes = NoteGroupsTests.notes(overview: "The launch moved.", decisions: [decision], todos: [todo])
        var draft = NotesDraft(notes)
        XCTAssertFalse(draft.changed)
        draft.overview = "The launch moved.\n  "
        draft.texts["d1"] = " Launch on Friday "
        XCTAssertFalse(draft.changed)
        draft.texts["t1"] = ""
        XCTAssertTrue(draft.changed)
        XCTAssertNil(draft.edits.overview)
        XCTAssertEqual(draft.edits.items.map(\.itemId), ["t1"])
        XCTAssertEqual(draft.edits.items.map(\.text), [""])
        XCTAssertEqual(NotesDraft.meta(todo), "Minji · Thursday")

        var bare = NotesDraft(NoteGroupsTests.notes(questions: [NoteGroupsTests.item("q1", .question)]))
        XCTAssertNil(bare.originalOverview, "no overview, no field for one")
        bare.overview = "Typed where there is no field"
        XCTAssertFalse(bare.changed)
    }

    /// docs/09 "AI view": under the notes, the model as the plan names it, else its id.
    func testTheFooterNamesTheModel() {
        XCTAssertEqual(ChatGptText.notesFooter("gpt-x", connection: signedIn), RecKitStrings.localized("ChatGPT · %@", "GPT X"))
        XCTAssertEqual(ChatGptText.notesFooter("GPT-5.4", connection: signedIn), RecKitStrings.localized("ChatGPT · %@", "GPT-5.4"))
    }

    /// docs/09 "AI view": Fill in title and Fill in speaker names each say the one reason that stands first — Still
    /// recording, then No transcript yet or the unsettled job's, then the sign-in, then a run asking for that half, then
    /// nothing of that half left to fill.
    func testEachFillItemSaysTheFirstReasonInItsOrder() {
        func fill(_ half: RecordingDetailModel.FillHalf, writing: Bool = false, transcript: Bool = true, busy: String? = nil,
                  connection: ChatGptConnection? = nil, running: Bool = false, needed: Bool = true) -> String? {
            RecordingDetailModel.fillReason(
                half, writing: writing, hasTranscript: transcript, busy: busy, connection: connection ?? signedIn,
                running: running, needed: needed
            )
        }
        let still = RecKitStrings.localized("Still recording")
        let noTranscript = RecKitStrings.localized("No transcript yet")
        let transcribing = RecKitStrings.localized("Transcribing…")
        let signIn = CoreMessages.sentence(.chatgptSignInRequired)
        let running = RecKitStrings.localized("Filling in…")
        let done: [RecordingDetailModel.FillHalf: String] = [
            .title: RecKitStrings.localized("Already has a title"),
            .names: RecKitStrings.localized("No unnamed speakers"),
        ]
        for half in [RecordingDetailModel.FillHalf.title, .names] {
            XCTAssertNil(fill(half))
            // Each reason with every later one also true: the earlier one is what is said.
            XCTAssertEqual(fill(half, writing: true, transcript: false, busy: transcribing,
                                connection: ChatGptConnection.SignedOut.shared, running: true, needed: false), still)
            XCTAssertEqual(fill(half, transcript: false, busy: transcribing, connection: ChatGptConnection.SignedOut.shared,
                                running: true, needed: false), noTranscript)
            XCTAssertEqual(fill(half, busy: transcribing, connection: ChatGptConnection.SignedOut.shared, running: true,
                                needed: false), transcribing)
            XCTAssertEqual(fill(half, connection: ChatGptConnection.SignedOut.shared, running: true, needed: false), signIn)
            XCTAssertEqual(fill(half, connection: ChatGptConnection.Expired(account: "a@example.com"), running: true,
                                needed: false), signIn)
            XCTAssertEqual(fill(half, running: true, needed: false), running)
            XCTAssertEqual(fill(half, needed: false), done[half])
        }
    }

    /// docs/09 "AI view": an item is `Filling in…` only while a run asks for its own half — the title sheet's or
    /// More's — and the lines under the header name what the run asks for, the title's first.
    func testARunHoldsOnlyTheHalvesItAsksFor() {
        let title = FillRun(recordingId: "r", title: true, names: false)
        let names = FillRun(recordingId: "r", title: false, names: true)
        let both = FillRun(recordingId: "r", title: true, names: true)
        XCTAssertTrue(RecordingDetailModel.runs(title, .title))
        XCTAssertFalse(RecordingDetailModel.runs(title, .names), "the names wait for the title's run, and may be asked for")
        XCTAssertFalse(RecordingDetailModel.runs(names, .title))
        XCTAssertTrue(RecordingDetailModel.runs(names, .names))
        XCTAssertFalse(RecordingDetailModel.runs(nil, .title))

        XCTAssertEqual(RecordingDetailModel.fillLines(nil), [])
        XCTAssertEqual(RecordingDetailModel.fillLines(title), ["Filling in the title…"])
        XCTAssertEqual(RecordingDetailModel.fillLines(names), ["Filling in speaker names…"])
        XCTAssertEqual(RecordingDetailModel.fillLines(both), ["Filling in the title…", "Filling in speaker names…"])
    }

    /// docs/08 "Titles and speaker names": what each answer of `fillTitle` and `fillNames` comes to on the page — by
    /// the half asked for.
    func testAFillAnswerIsSaidOnlyWhenTheHalfAskedForWasNotGiven() {
        func outcome(_ result: FillResult, _ half: RecordingDetailModel.FillHalf) -> RecordingDetailModel.FillOutcome {
            RecordingDetailModel.fillOutcome(result, half: half, busyKey: "Transcribing…")
        }
        XCTAssertEqual(outcome(FillResultFilled(title: true, names: 0), .title), .quiet)
        XCTAssertEqual(outcome(FillResultFilled(title: false, names: 2), .names), .quiet)
        XCTAssertEqual(outcome(FillResultFilled(title: false, names: 0), .title), .notice(.failed(.title, reason: nil)))
        XCTAssertEqual(outcome(FillResultFilled(title: false, names: 0), .names), .notice(.failed(.names, reason: nil)))
        // A run that asked for both answers both items: each says only of its own half.
        XCTAssertEqual(outcome(FillResultFilled(title: false, names: 2), .title), .notice(.failed(.title, reason: nil)))
        XCTAssertEqual(outcome(FillResultFilled(title: true, names: 0), .names), .notice(.failed(.names, reason: nil)))
        XCTAssertEqual(outcome(FillResultNothingToFill.shared, .title), .notice(.refused("Already has a title")))
        XCTAssertEqual(outcome(FillResultNothingToFill.shared, .names), .notice(.refused("No unnamed speakers")))
        for half in [RecordingDetailModel.FillHalf.title, .names] {
            XCTAssertEqual(outcome(FillResultBusy.shared, half), .notice(.refused("Transcribing…")))
            XCTAssertEqual(outcome(FillResultNoTranscript.shared, half), .notice(.refused("No transcript yet")))
            let consent = CoreMessage.transferConsentRequired.code(arg: nil, detail: nil)
            XCTAssertEqual(outcome(FillResultFailed(reason: consent), half), .askConsent)
            let limit = CoreMessage.chatgptUsageLimit.code(arg: nil, detail: nil)
            XCTAssertEqual(outcome(FillResultFailed(reason: limit), half), .notice(.failed(half, reason: limit)))
            let provider = CoreMessage.providerError.code(arg: nil, detail: "500 server_error")
            XCTAssertEqual(outcome(FillResultFailed(reason: provider), half), .notice(.failed(half, reason: provider)))
        }
    }

    /// docs/09 "AI view": the text cut at the citations the core finds, in UTF-16 as the core counts — a line
    /// in Korean with an emoji before the citation lands on the same characters.
    func testCitationsSplitTheTextWhereTheCoreFindsThem() {
        let text = "- 결정 👍 [00:12:34]\n- Next [1:05] and [99:99] stays text"
        XCTAssertEqual(ChatGptText.runs(text), [
            .text("- 결정 👍 "),
            .citation("[00:12:34]", atSec: 754),
            .text("\n- Next "),
            .citation("[1:05]", atSec: 65),
            .text(" and [99:99] stays text"),
        ])
        XCTAssertEqual(ChatGptText.runs("No citations"), [.text("No citations")])
        XCTAssertEqual(ChatGptText.runs("[00:01]"), [.citation("[00:01]", atSec: 1)])
        XCTAssertEqual(ChatGptText.playFrom("[00:12:34]"), RecKitStrings.localized("Play from %@", "00:12:34"))

        // Only the seconds the recording here holds are links; the seek reads the second back off the link.
        let playable = CitedText.playable(ChatGptText.runs(text), seekableSec: 100)
        XCTAssertEqual(playable.map(\.atSec), [65])
        XCTAssertTrue(CitedText.playable(ChatGptText.runs(text), seekableSec: 0).isEmpty)
        XCTAssertEqual(CitedText.url(754).flatMap(CitedText.second), 754)
        XCTAssertNil(CitedText.second(URL(string: "https://chatgpt.com/settings/usage")!))
    }

    /// docs/09 "AI view" — "Elsewhere": Notes comes after Transcript for notes, exports the notes as text, and is off
    /// with `No notes yet` until the recording has some.
    func testShareOffersTheNotesOnceThereAreSome() {
        XCTAssertEqual(ShareFormat.allCases, [.transcript, .markdown, .notes, .subtitles, .webSubtitles, .audio])
        XCTAssertEqual(ShareFormat.notes.export, .notes)
        XCTAssertEqual(ShareFormat.markdown.export, .md)
        XCTAssertEqual(ShareFormat.notes.title, RecKitStrings.localized("Notes"))
        XCTAssertEqual(ShareFormat.markdown.title, RecKitStrings.localized("Transcript for notes"))
        XCTAssertEqual(ShareFormat.notes.detail, RecKitStrings.localized("Text · .txt"))
        XCTAssertFalse(ShareFormat.notes.needsTranscript)
        XCTAssertTrue(ShareFormat.markdown.needsTranscript)
        XCTAssertEqual(RecordingDetailModel.notesExportReason(nil), RecKitStrings.localized("No notes yet"))
        XCTAssertEqual(RecordingDetailModel.notesExportReason(NoteGroupsTests.notes(overview: " ")), RecKitStrings.localized("No notes yet"))
        XCTAssertNil(RecordingDetailModel.notesExportReason(NoteGroupsTests.notes(overview: "The launch moved.")))
        XCTAssertNil(RecordingDetailModel.notesExportReason(NoteGroupsTests.notes(points: [NoteGroupsTests.item("p1", .point)])))
    }

    /// docs/10 "Search": the notes' line carries its label, with the matches moved past it; a hit found only there opens
    /// the recording on its notes.
    func testANotesHitIsLabelledAndOpensOnTheNotes() {
        let match = NotesMatch(text: "- Budget review moved", ranges: [SearchRange(offset: 2, length: 6)])
        let (line, ranges) = SearchResultRow.notesLine(match)
        let label = RecKitStrings.localized("Notes") + " · "
        XCTAssertEqual(line, label + "- Budget review moved")
        let range = try! XCTUnwrap(ranges.first)
        let utf16 = Array(line.utf16)
        XCTAssertEqual(String(utf16CodeUnits: Array(utf16[Int(range.offset)..<Int(range.offset + range.length)]), count: Int(range.length)), "Budget")

        func hit(title: Bool, snippets: [SearchSnippet], notes: NotesMatch?) -> SearchHit {
            SearchHit(recordingId: "r1", title: "Weekly", startedAt: "2026-10-10T00:00:00Z", matchesInTitle: title,
                      titleRanges: [], snippets: snippets, notes: notes)
        }
        XCTAssertTrue(hit(title: false, snippets: [], notes: match).onlyInNotes)
        XCTAssertFalse(hit(title: true, snippets: [], notes: match).onlyInNotes)
        XCTAssertFalse(hit(title: false, snippets: [SearchSnippet(atSec: 1, text: "budget", ranges: [])], notes: match).onlyInNotes)
        XCTAssertFalse(hit(title: true, snippets: [], notes: nil).onlyInNotes)
    }

    /// docs/09 "Ask": Translate names the app's language as the App language list does.
    func testTranslateNamesTheAppLanguageInItsOwnWords() {
        AppLanguage.current = .ko
        defer { AppLanguage.current = .system }
        XCTAssertEqual(ChatGptText.presetLabel(.translate), RecKitStrings.localized("Translate to %@", "한국어"))
        XCTAssertEqual(ChatGptText.presetLabel(.actionItems), RecKitStrings.localized("Action items"))
    }

    func testTheModelIsNamedAsThePlanNamesIt() {
        XCTAssertEqual(ChatGptText.modelLabel("gpt-x", connection: signedIn), "GPT X")
        XCTAssertEqual(ChatGptText.modelLabel("gpt-old", connection: signedIn), "gpt-old")
        XCTAssertEqual(ChatGptText.modelLabel("gpt-x", connection: ChatGptConnection.SignedOut.shared), "gpt-x")
    }
}

/// docs/15 "iPhone providers": a summary's destination is asked about like a transcription provider's, with
/// the training question ChatGPT needs.
@MainActor
final class SummaryTransferTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("SummaryTransferTests-\(UUID().uuidString)")
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    func testTheSummaryDestinationIsNamedAndAsksWhetherTrainingIsOff() {
        let target = TransferTargets.shared.chatGptSummary()
        XCTAssertTrue(TransferTargetText.summary(target))
        XCTAssertEqual(TransferTargetText.name(target), RecKitStrings.localized("ChatGPT (OpenAI)"))
        XCTAssertTrue(TrainingOptOut.required([target]))
        XCTAssertEqual(PrivacyLinks.provider(target.provider)?.absoluteString, "https://openai.com/policies/privacy-policy/")
        XCTAssertEqual(TrainingOptOut.chatGptHelp.absoluteString, "https://help.openai.com/en/articles/7730893")
    }

    /// The iPhone asks before the first Organize sends anything; declined, nothing is sent and nothing is allowed;
    /// allowed, the Organize goes ahead — here it ends at the sign-in, before any request, as the notes' failure.
    func testTheIPhoneAsksBeforeTheFirstOrganizeAndGoesOnOnceAllowed() async throws {
        let bridge = try await CoreBridge.make(
            platform: .ios, deviceName: "Test iPhone",
            dataDirectory: directory, databaseName: "summary-consent.db", secureStore: InMemorySecureStore(),
            transcriptionPolicy: TranscriptionPolicy(region: nil)
        )
        let model = RecordingDetailModel(core: bridge.core, recordingId: "01JTESTRECORDING0000000000", title: "Meeting")
        await model.organize()
        XCTAssertEqual(model.summaryConsent.map(\.id), [TransferTargets.shared.chatGptSummary().id])
        XCTAssertFalse(model.organizing)

        await model.answerSummaryConsent(allow: false)
        XCTAssertTrue(model.summaryConsent.isEmpty)
        let still = try await bridge.core.transferConsents.missing(targets: [TransferTargets.shared.chatGptSummary()])
        XCTAssertEqual(still.count, 1)
        let untouched = try await bridge.core.notes.get(recordingId: model.recordingId)
        XCTAssertNil(untouched.failure, "declined, nothing was tried")

        await model.organize()
        await model.answerSummaryConsent(allow: true)
        let missing = try await bridge.core.transferConsents.missing(targets: [TransferTargets.shared.chatGptSummary()])
        XCTAssertTrue(missing.isEmpty)
        XCTAssertTrue(model.summaryConsent.isEmpty)
        XCTAssertFalse(model.organizing)
        let notes = try await bridge.core.notes.get(recordingId: model.recordingId)
        XCTAssertEqual(notes.failure.flatMap(ChatGptText.message), .chatgptSignInRequired)
    }

    /// docs/08 "Titles and speaker names" · docs/15 "iPhone providers": More → Fill in title asks for the ChatGPT
    /// permission first on the iPhone, and once allowed goes on — here to the recording that has no transcript. Fill in
    /// speaker names does the same.
    func testTheIPhoneAsksBeforeTheFirstFillAndGoesOnOnceAllowed() async throws {
        let bridge = try await CoreBridge.make(
            platform: .ios, deviceName: "Test iPhone",
            dataDirectory: directory, databaseName: "fill-detail-consent.db", secureStore: InMemorySecureStore(),
            transcriptionPolicy: TranscriptionPolicy(region: nil)
        )
        let chatGpt = TransferTargets.shared.chatGptSummary()
        let model = RecordingDetailModel(core: bridge.core, recordingId: "01JTESTRECORDING0000000000", title: "Meeting")
        await model.fill(.title)
        XCTAssertEqual(model.summaryConsent.map(\.id), [chatGpt.id])
        XCTAssertNil(model.fillNotice, "a question, not a failure")

        await model.answerSummaryConsent(allow: false)
        XCTAssertTrue(model.summaryConsent.isEmpty)
        XCTAssertNil(model.fillNotice)
        let still = try await bridge.core.transferConsents.missing(targets: [chatGpt])
        XCTAssertEqual(still.count, 1)

        await model.fill(.names)
        XCTAssertEqual(model.summaryConsent.map(\.id), [chatGpt.id])
        await model.answerSummaryConsent(allow: true)
        let missing = try await bridge.core.transferConsents.missing(targets: [chatGpt])
        XCTAssertTrue(missing.isEmpty)
        XCTAssertTrue(model.summaryConsent.isEmpty)
        XCTAssertEqual(model.fillNotice, .refused("No transcript yet"))

        await model.fill(.title)
        XCTAssertTrue(model.summaryConsent.isEmpty, "allowed once is allowed")
        XCTAssertEqual(model.fillNotice, .refused("No transcript yet"))
    }

    private func until(_ condition: () -> Bool) async throws {
        for _ in 0..<200 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(25))
        }
        XCTFail("timed out")
    }
}

/// A browser that does what the test says when the sign-in opens it.
private final class FakeBrowser: ChatGptBrowser, @unchecked Sendable {
    let answer: @Sendable (URLComponents) -> ChatGptLoopback.Answer
    var onOpen: ((URL, @escaping @MainActor () -> Void) async throws -> Void)?
    var page: String?
    var closed = false

    init(answer: @escaping @Sendable (URLComponents) -> ChatGptLoopback.Answer) { self.answer = answer }

    /// False is a browser that never opened (the Mac's `NSWorkspace.open`, the iPhone's `session.start()`).
    var opens = true

    @MainActor func open(_ url: URL, dismissed: @escaping @MainActor () -> Void) -> Bool {
        guard opens else { return false }
        if let onOpen { Task { try? await onOpen(url, dismissed) } }
        return true
    }

    @MainActor func close() { closed = true }
}

/// HTTP/1.1 by hand over a blocking socket, so the test sees exactly the bytes the loopback wrote — a
/// URLSession would follow the iPhone's redirect.
enum HTTP {
    static func get(port: UInt16, target: String) async throws -> String {
        try await send(port: port, request: "GET \(target) HTTP/1.1\r\nHost: 127.0.0.1:\(port)\r\nAccept: text/html\r\n\r\n")
    }

    static func send(port: UInt16, request: String) async throws -> String {
        try await Task.detached {
            let socket = try connect(host: "127.0.0.1", port: port)
            defer { close(socket) }
            let bytes = Array(request.utf8)
            guard write(socket, bytes, bytes.count) == bytes.count else { throw POSIXError(.EIO) }
            var response = [UInt8]()
            var buffer = [UInt8](repeating: 0, count: 4096)
            while true {
                let count = read(socket, &buffer, buffer.count)
                if count <= 0 { break }
                response += buffer[0..<count]
            }
            return String(decoding: response, as: UTF8.self)
        }.value
    }

    static func refused(host: String, port: UInt16) async -> Bool {
        await Task.detached {
            guard let socket = try? connect(host: host, port: port, timeout: 1) else { return true }
            close(socket)
            return false
        }.value
    }

    /// An IPv4 address of this machine that is not the loopback, if it has one.
    static func nonLoopbackIPv4() -> String? {
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return nil }
        defer { freeifaddrs(list) }
        for entry in sequence(first: first, next: { $0.pointee.ifa_next }) {
            guard let address = entry.pointee.ifa_addr, address.pointee.sa_family == UInt8(AF_INET) else { continue }
            var ipv4 = address.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr }
            var text = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            inet_ntop(AF_INET, &ipv4, &text, socklen_t(INET_ADDRSTRLEN))
            let string = String(cString: text)
            if !string.hasPrefix("127.") { return string }
        }
        return nil
    }

    private static func connect(host: String, port: UInt16, timeout: Int = 5) throws -> Int32 {
        let socket = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        guard socket >= 0 else { throw POSIXError(.EIO) }
        var interval = timeval(tv_sec: timeout, tv_usec: 0)
        setsockopt(socket, SOL_SOCKET, SO_RCVTIMEO, &interval, socklen_t(MemoryLayout<timeval>.size))
        setsockopt(socket, SOL_SOCKET, SO_SNDTIMEO, &interval, socklen_t(MemoryLayout<timeval>.size))
        var address = sockaddr_in()
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = port.bigEndian
        inet_pton(AF_INET, host, &address.sin_addr)
        let result = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.connect(socket, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        guard result == 0 else {
            close(socket)
            throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .ECONNREFUSED)
        }
        return socket
    }
}
