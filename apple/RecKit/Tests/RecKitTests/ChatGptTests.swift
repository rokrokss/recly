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

/// docs/09 "Summary view": the section's sign-in, run against the real core with a browser that stands in for
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

    /// docs/08 "Summaries": the format saves at once, the fields when editing ends; the format shown falls back to
    /// General once My format has no words, and My format is offered only while it has some.
    func testSummaryPreferencesSaveAndShowTheFormatInEffect() async throws {
        let bridge = try await bridge()
        let model = ChatGptSettingsModel(core: bridge.core, browser: FakeBrowser(answer: { _ in .page("connected") }))
        XCTAssertEqual(model.shownPreferences.formats, [.auto, .oneOnOne, .lecture, .interview])
        // The saved preferences arrive first, as they do long before anyone types on a screen.
        try await Task.sleep(for: .milliseconds(300))

        model.customFormat = "Risks, Next steps  "
        XCTAssertEqual(model.shownPreferences.formats, [.auto, .oneOnOne, .lecture, .interview, .custom])
        model.selectFormat(.custom)
        XCTAssertEqual(model.preferences.format, .custom, "the pick is shown at once")
        try await until { model.preferences.customFormat == "Risks, Next steps" }
        // What was being typed stays as typed: the saved text is the same, trimmed.
        XCTAssertEqual(model.customFormat, "Risks, Next steps  ")

        model.aboutMe = " PM "
        model.savePreferences()
        try await until { model.preferences.aboutMe == "PM" }
        let saved = try await bridge.core.summaries.preferences()
        XCTAssertEqual(saved.format, .custom)
        XCTAssertEqual(saved.aboutMe, "PM")

        model.customFormat = ""
        XCTAssertEqual(model.shownPreferences.effectiveFormat, .auto)
        model.savePreferences()
        try await until { model.preferences.customFormat.isEmpty }
        XCTAssertEqual(model.preferences.effectiveFormat, .auto)

        // A field that ends its editing as the format menu closes saves with the format just picked.
        model.aboutMe = "PM, deadlines"
        model.selectFormat(.lecture)
        model.savePreferences()
        try await until { model.preferences.aboutMe == "PM, deadlines" && model.preferences.format == .lecture }
        let last = try await bridge.core.summaries.preferences()
        XCTAssertEqual(last.format, .lecture)
        XCTAssertEqual(last.aboutMe, "PM, deadlines")
    }

    /// docs/08 "Titles and speaker names": the title sheet's two boxes are off until ticked and each is kept as it was
    /// left — saved at once, signed in or out, on the Mac with nothing to ask — apart from the other, and stays as it is
    /// when the format or About you changes.
    func testTheTitleSheetBoxesAreKeptApartAndSurviveTheOtherPreferences() async throws {
        let bridge = try await bridge()
        let model = ChatGptSettingsModel(core: bridge.core, browser: FakeBrowser(answer: { _ in .page("connected") }))
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertFalse(model.preferences.fillTitleAfterStop)
        XCTAssertFalse(model.preferences.fillNamesAfterStop)
        XCTAssertFalse(model.fillOffered, "signed out, the sheet offers no box")

        XCTAssertTrue(model.fillAllowed, "the Mac needs no permission")
        model.setFillNamesAfterStop(true)
        try await until { model.preferences.fillNamesAfterStop }
        XCTAssertTrue(model.fillConsent.isEmpty, "the Mac never asks")
        XCTAssertTrue(model.fillNamesTicked)
        XCTAssertFalse(model.fillTitleTicked, "ticking the names leaves the title's box as it was")
        var saved = try await savedPreferences(bridge) { $0.fillNamesAfterStop }
        XCTAssertTrue(saved.fillNamesAfterStop)
        XCTAssertFalse(saved.fillTitleAfterStop)

        model.setFillTitleAfterStop(true)
        try await until { model.preferences.fillTitleAfterStop }
        XCTAssertTrue(model.fillTitleTicked)
        XCTAssertTrue(model.fillNamesTicked)
        saved = try await savedPreferences(bridge) { $0.fillTitleAfterStop }
        XCTAssertTrue(saved.fillTitleAfterStop)
        XCTAssertTrue(saved.fillNamesAfterStop)

        model.selectFormat(.lecture)
        model.aboutMe = "PM"
        model.savePreferences()
        try await until { model.preferences.aboutMe == "PM" && model.preferences.format == .lecture }
        XCTAssertTrue(model.preferences.fillTitleAfterStop)
        XCTAssertTrue(model.preferences.fillNamesAfterStop)
        saved = try await bridge.core.summaries.preferences()
        XCTAssertEqual(saved.format, .lecture)
        XCTAssertEqual(saved.aboutMe, "PM")
        XCTAssertTrue(saved.fillTitleAfterStop)
        XCTAssertTrue(saved.fillNamesAfterStop)

        // Off at once, the other box and the fields left as they were.
        model.setFillTitleAfterStop(false)
        XCTAssertFalse(model.preferences.fillTitleAfterStop, "off is shown at once")
        XCTAssertTrue(model.preferences.fillNamesAfterStop)
        let last = try await savedPreferences(bridge) { !$0.fillTitleAfterStop }
        XCTAssertFalse(last.fillTitleAfterStop)
        XCTAssertTrue(last.fillNamesAfterStop)
        XCTAssertEqual(last.format, .lecture)
        XCTAssertEqual(last.aboutMe, "PM")
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

/// docs/09 "Detail header and More menu" · "Summary view": the item's reason and title, and the words a
/// failed summary is shown with.
@MainActor
final class SummaryMenuTests: XCTestCase {
    private let signedIn = ChatGptConnection.SignedIn(account: "a@example.com", models: [ChatGptModel(id: "gpt-x", label: "GPT X")], model: "gpt-x")
    private let summary = Summary(recordingId: "r1", text: "Summary\n- one", model: "gpt-x", createdAt: "2026-10-09T00:00:00Z", editedAt: nil, format: nil, modelName: nil)

    func testTheReasonSaysWhatStandsInTheWay() {
        func reason(writing: Bool = false, transcript: Bool = true, busy: String? = nil,
                    connection: ChatGptConnection? = nil, state: SummaryState = SummaryState.None.shared, summarizing: Bool = false) -> String? {
            RecordingDetailModel.summarizeReason(
                writing: writing, hasTranscript: transcript, busy: busy, connection: connection ?? signedIn,
                summary: state, summarizing: summarizing
            )
        }
        XCTAssertNil(reason())
        XCTAssertEqual(reason(writing: true, transcript: false), RecKitStrings.localized("Still recording"))
        XCTAssertEqual(reason(transcript: false), RecKitStrings.localized("No transcript yet"))
        XCTAssertEqual(reason(busy: RecKitStrings.localized("Transcribing…")), RecKitStrings.localized("Transcribing…"))
        let signIn = CoreMessages.sentence(.chatgptSignInRequired)
        XCTAssertEqual(signIn, RecKitStrings.localized("Sign in to ChatGPT in Settings"))
        XCTAssertEqual(reason(connection: ChatGptConnection.SignedOut.shared), signIn)
        XCTAssertEqual(reason(connection: ChatGptConnection.Expired(account: "a@example.com")), signIn)
        XCTAssertEqual(reason(state: SummaryState.Running(previous: nil)), RecKitStrings.localized("Summarizing…"))
        XCTAssertEqual(reason(summarizing: true), RecKitStrings.localized("Summarizing…"))
        XCTAssertNil(reason(state: SummaryState.Ready(summary: summary)))
    }

    func testAgainOnlyWhenThereIsASummaryToReplace() {
        XCTAssertFalse(RecordingDetailModel.hasSummary(SummaryState.None.shared))
        XCTAssertFalse(RecordingDetailModel.hasSummary(SummaryState.Running(previous: nil)))
        XCTAssertFalse(RecordingDetailModel.hasSummary(SummaryState.Failed(reason: "PROVIDER_ERROR", previous: nil)))
        XCTAssertTrue(RecordingDetailModel.hasSummary(SummaryState.Ready(summary: summary)))
        XCTAssertTrue(RecordingDetailModel.hasSummary(SummaryState.Running(previous: summary)))
        XCTAssertTrue(RecordingDetailModel.hasSummary(SummaryState.Failed(reason: "PROVIDER_ERROR", previous: summary)))
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

    /// docs/09 "Summary view": Edit summary is there once a summary is, and off while a new one is written.
    func testEditSummaryWaitsForARunningSummary() {
        XCTAssertNil(RecordingDetailModel.savedSummary(SummaryState.None.shared))
        XCTAssertNil(RecordingDetailModel.savedSummary(SummaryState.Running(previous: nil)))
        XCTAssertEqual(RecordingDetailModel.savedSummary(SummaryState.Ready(summary: summary)), summary)
        XCTAssertEqual(RecordingDetailModel.savedSummary(SummaryState.Running(previous: summary)), summary)
        XCTAssertEqual(RecordingDetailModel.savedSummary(SummaryState.Failed(reason: "PROVIDER_ERROR", previous: summary)), summary)

        let running = RecKitStrings.localized("Summarizing…")
        XCTAssertNil(RecordingDetailModel.editSummaryReason(summary: SummaryState.Ready(summary: summary), summarizing: false))
        XCTAssertNil(RecordingDetailModel.editSummaryReason(summary: SummaryState.Failed(reason: "PROVIDER_ERROR", previous: summary), summarizing: false))
        XCTAssertEqual(RecordingDetailModel.editSummaryReason(summary: SummaryState.Running(previous: summary), summarizing: false), running)
        XCTAssertEqual(RecordingDetailModel.editSummaryReason(summary: SummaryState.Ready(summary: summary), summarizing: true), running)
    }

    /// The footer says a summary was edited; Summarize again asks before it replaces one that was.
    func testAnEditedSummaryIsSaidAndAskedAbout() {
        let edited = Summary(recordingId: "r1", text: "Mine", model: "gpt-x", createdAt: "2026-10-09T00:00:00Z", editedAt: "2026-10-09T01:00:00Z", format: nil, modelName: nil)
        XCTAssertEqual(ChatGptText.summaryFooter(summary, connection: signedIn), RecKitStrings.localized("ChatGPT · %@", "GPT X"))
        XCTAssertEqual(
            ChatGptText.summaryFooter(edited, connection: signedIn),
            RecKitStrings.localized("ChatGPT · %@", "GPT X") + " · " + RecKitStrings.localized("Edited")
        )
        XCTAssertEqual(RecordingDetailModel.savedSummary(SummaryState.Failed(reason: "PROVIDER_ERROR", previous: edited))?.editedAt, edited.editedAt)
    }

    /// Save is offered only for a text the core would keep: not an unchanged one, not an empty one.
    func testADraftChangesOnlyWithTextTheCoreWouldKeep() {
        var draft = SummaryDraft(summary)
        XCTAssertFalse(draft.changed)
        draft.text = summary.text + "\n  "
        XCTAssertFalse(draft.changed)
        draft.text = "   \n"
        XCTAssertFalse(draft.changed)
        draft.text = "Summary\n- one\n- two"
        XCTAssertTrue(draft.changed)
    }

    /// The editor's note: the summary reaches the other devices only from Drive or iCloud.
    func testTheNoteFollowsTheRecordingsStorage() {
        XCTAssertTrue(RecordingDetailModel.summaryStaysHere(storage: nil))
        XCTAssertTrue(RecordingDetailModel.summaryStaysHere(storage: .folder))
        XCTAssertFalse(RecordingDetailModel.summaryStaysHere(storage: .drive))
        XCTAssertFalse(RecordingDetailModel.summaryStaysHere(storage: .icloud))
    }

    /// docs/09 "Detail header and More menu": Ask stands in the way of nothing but what Summarize does — a summary
    /// being written is no reason.
    func testAskHasSummarizesReasonsButNotARunningSummary() {
        func ask(writing: Bool = false, transcript: Bool = true, busy: String? = nil, connection: ChatGptConnection? = nil) -> String? {
            RecordingDetailModel.askReason(writing: writing, hasTranscript: transcript, busy: busy, connection: connection ?? signedIn)
        }
        XCTAssertNil(ask())
        XCTAssertEqual(ask(writing: true, transcript: false), RecKitStrings.localized("Still recording"))
        XCTAssertEqual(ask(transcript: false), RecKitStrings.localized("No transcript yet"))
        XCTAssertEqual(ask(busy: RecKitStrings.localized("Transcribing…")), RecKitStrings.localized("Transcribing…"))
        XCTAssertEqual(ask(connection: ChatGptConnection.SignedOut.shared), CoreMessages.sentence(.chatgptSignInRequired))
        XCTAssertEqual(ask(connection: ChatGptConnection.Expired(account: "a@example.com")), CoreMessages.sentence(.chatgptSignInRequired))
        // Summarize as… shares Summarize's reasons, the running summary included.
        XCTAssertEqual(
            RecordingDetailModel.summarizeReason(writing: false, hasTranscript: true, busy: nil, connection: signedIn,
                                                 summary: SummaryState.Running(previous: nil), summarizing: false),
            RecKitStrings.localized("Summarizing…")
        )
    }

    /// docs/09 "Summary view": Fill in title and Fill in speaker names each say the one reason that stands first — Still
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

    /// docs/09 "Summary view": an item is `Filling in…` only while a run asks for its own half — the title sheet's or
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

    /// docs/09 screen principle 5: the title's box does nothing while the title field has a title — it is off then —
    /// and `Save` asks for each half as its box shows, the title's only with the field empty.
    func testTheTitleSheetAsksForWhatItsBoxesShow() {
        XCTAssertTrue(TitleSheetFill.titleBoxEnabled(typedTitle: nil))
        XCTAssertTrue(TitleSheetFill.titleBoxEnabled(typedTitle: ""))
        XCTAssertTrue(TitleSheetFill.titleBoxEnabled(typedTitle: "  "), "spaces are no title")
        XCTAssertFalse(TitleSheetFill.titleBoxEnabled(typedTitle: "Weekly sync"))

        func ask(_ title: Bool, _ names: Bool, _ typed: String?) -> [Bool] {
            let fill = TitleSheetFill(titleTicked: title, namesTicked: names, typedTitle: typed)
            return [fill.title, fill.names, fill.any]
        }
        XCTAssertEqual(ask(true, true, nil), [true, true, true])
        XCTAssertEqual(ask(true, false, nil), [true, false, true])
        XCTAssertEqual(ask(false, true, nil), [false, true, true])
        XCTAssertEqual(ask(false, false, nil), [false, false, false], "neither ticked: requestFill is not called")
        XCTAssertEqual(ask(true, true, "Weekly sync"), [false, true, true], "a typed title is never replaced")
        XCTAssertEqual(ask(true, false, "Weekly sync"), [false, false, false])
        XCTAssertEqual(ask(true, false, " "), [true, false, true])
        XCTAssertFalse(TitleSheetFill.none.any)
    }

    /// The footer names the model as the plan named it when the summary was written, even once the plan's list no
    /// longer has it; with no name kept, the list's label, then the id.
    func testTheFooterNamesTheModelAsItWasWhenWritten() {
        let named = Summary(recordingId: "r1", text: "Notes", model: "gpt-old", createdAt: "2026-10-09T00:00:00Z", editedAt: nil, format: nil, modelName: "GPT-5.4")
        let unnamed = Summary(recordingId: "r1", text: "Notes", model: "gpt-old", createdAt: "2026-10-09T00:00:00Z", editedAt: nil, format: nil, modelName: nil)
        XCTAssertEqual(ChatGptText.summaryFooter(named, connection: signedIn), RecKitStrings.localized("ChatGPT · %@", "GPT-5.4"))
        XCTAssertEqual(ChatGptText.summaryFooter(unnamed, connection: signedIn), RecKitStrings.localized("ChatGPT · %@", "gpt-old"))
        XCTAssertEqual(ChatGptText.summaryFooter(summary, connection: signedIn), RecKitStrings.localized("ChatGPT · %@", "GPT X"))
    }

    /// The footer names a format other than General, before `Edited`.
    func testTheFooterNamesTheFormat() {
        let lecture = Summary(recordingId: "r1", text: "Notes", model: "gpt-x", createdAt: "2026-10-09T00:00:00Z", editedAt: nil, format: "lecture", modelName: nil)
        let editedInterview = Summary(recordingId: "r1", text: "Notes", model: "gpt-x", createdAt: "2026-10-09T00:00:00Z",
                                      editedAt: "2026-10-09T01:00:00Z", format: "interview", modelName: nil)
        let made = RecKitStrings.localized("ChatGPT · %@", "GPT X")
        XCTAssertEqual(ChatGptText.summaryFooter(summary, connection: signedIn), made)
        XCTAssertEqual(ChatGptText.summaryFooter(lecture, connection: signedIn), made + " · " + RecKitStrings.localized("Lecture"))
        XCTAssertEqual(
            ChatGptText.summaryFooter(editedInterview, connection: signedIn),
            made + " · " + RecKitStrings.localized("Interview") + " · " + RecKitStrings.localized("Edited")
        )
        XCTAssertEqual(ChatGptText.formatLabel(.auto), RecKitStrings.localized("General"))
        XCTAssertEqual(ChatGptText.formatLabel(.oneOnOne), RecKitStrings.localized("One-on-one"))
        XCTAssertEqual(ChatGptText.formatLabel(.custom), RecKitStrings.localized("My format"))
    }

    /// docs/09 "Summary view": the text cut at the citations the core finds, in UTF-16 as the core counts — a line
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

    /// docs/09 "Share / export": Summary comes after Transcript for notes, exports the summary, and is off with
    /// `No summary yet` until the recording has one.
    func testShareOffersTheSummaryOnceThereIsOne() {
        XCTAssertEqual(ShareFormat.allCases, [.transcript, .notes, .summary, .subtitles, .webSubtitles, .audio])
        XCTAssertEqual(ShareFormat.summary.export, .summary)
        XCTAssertEqual(ShareFormat.summary.title, RecKitStrings.localized("Summary"))
        XCTAssertFalse(ShareFormat.summary.needsTranscript)
        XCTAssertEqual(RecordingDetailModel.summaryExportReason(SummaryState.None.shared), RecKitStrings.localized("No summary yet"))
        XCTAssertEqual(RecordingDetailModel.summaryExportReason(SummaryState.Running(previous: nil)), RecKitStrings.localized("No summary yet"))
        XCTAssertNil(RecordingDetailModel.summaryExportReason(SummaryState.Ready(summary: summary)))
        XCTAssertNil(RecordingDetailModel.summaryExportReason(SummaryState.Failed(reason: "PROVIDER_ERROR", previous: summary)))
    }

    /// docs/10 "Search": the summary's line carries its label, with the matches moved past it; a hit found only
    /// there opens the recording on its summary.
    func testASummaryHitIsLabelledAndOpensOnTheSummary() {
        let match = SummaryMatch(text: "- Budget review moved", ranges: [SearchRange(offset: 2, length: 6)])
        let (line, ranges) = SearchResultRow.summaryLine(match)
        let label = RecKitStrings.localized("Summary") + " · "
        XCTAssertEqual(line, label + "- Budget review moved")
        let range = try! XCTUnwrap(ranges.first)
        let utf16 = Array(line.utf16)
        XCTAssertEqual(String(utf16CodeUnits: Array(utf16[Int(range.offset)..<Int(range.offset + range.length)]), count: Int(range.length)), "Budget")

        func hit(title: Bool, snippets: [SearchSnippet], summary: SummaryMatch?) -> SearchHit {
            SearchHit(recordingId: "r1", title: "Weekly", startedAt: "2026-10-10T00:00:00Z", matchesInTitle: title,
                      titleRanges: [], snippets: snippets, summary: summary)
        }
        XCTAssertTrue(hit(title: false, snippets: [], summary: match).onlyInSummary)
        XCTAssertFalse(hit(title: true, snippets: [], summary: match).onlyInSummary)
        XCTAssertFalse(hit(title: false, snippets: [SearchSnippet(atSec: 1, text: "budget", ranges: [])], summary: match).onlyInSummary)
        XCTAssertFalse(hit(title: true, snippets: [], summary: nil).onlyInSummary)
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

    /// The iPhone asks before the first summary sends anything; allowed, the summary goes ahead — here it
    /// ends at the missing transcript, before any request.
    func testTheIPhoneAsksBeforeTheFirstSummaryAndGoesOnOnceAllowed() async throws {
        let bridge = try await CoreBridge.make(
            platform: .ios, deviceName: "Test iPhone",
            dataDirectory: directory, databaseName: "summary-consent.db", secureStore: InMemorySecureStore(),
            transcriptionPolicy: TranscriptionPolicy(region: nil)
        )
        let model = RecordingDetailModel(core: bridge.core, recordingId: "01JTESTRECORDING0000000000", title: "Meeting")
        await model.summarize()
        XCTAssertEqual(model.summaryConsent.map(\.id), [TransferTargets.shared.chatGptSummary().id])
        XCTAssertFalse(model.summarizing)

        await model.answerSummaryConsent(allow: false)
        XCTAssertTrue(model.summaryConsent.isEmpty)
        let still = try await bridge.core.transferConsents.missing(targets: [TransferTargets.shared.chatGptSummary()])
        XCTAssertEqual(still.count, 1)

        await model.summarize()
        await model.answerSummaryConsent(allow: true)
        let missing = try await bridge.core.transferConsents.missing(targets: [TransferTargets.shared.chatGptSummary()])
        XCTAssertTrue(missing.isEmpty)
        XCTAssertTrue(model.summaryConsent.isEmpty)
        let state = try await bridge.core.summaries.state(recordingId: model.recordingId)
        XCTAssertTrue(state is SummaryState.Failed, "\(state)")
    }

    /// docs/08 "Titles and speaker names": on the iPhone ticking either of the title sheet's boxes asks for the ChatGPT
    /// permission first, as Summarize does — declined, it stays off; allowed, it is ticked, and either box is ticked
    /// again later without asking.
    func testTheIPhoneAsksBeforeATitleSheetBoxIsTicked() async throws {
        let bridge = try await CoreBridge.make(
            platform: .ios, deviceName: "Test iPhone",
            dataDirectory: directory, databaseName: "fill-consent.db", secureStore: InMemorySecureStore(),
            transcriptionPolicy: TranscriptionPolicy(region: nil)
        )
        let model = ChatGptSettingsModel(core: bridge.core, browser: FakeBrowser(answer: { _ in .page("connected") }))
        let chatGpt = TransferTargets.shared.chatGptSummary()
        // The saved preferences arrive first, as they do long before anyone stops a recording.
        try await Task.sleep(for: .milliseconds(300))

        model.setFillNamesAfterStop(true)
        try await until { !model.fillConsent.isEmpty }
        XCTAssertEqual(model.fillConsent.map(\.id), [chatGpt.id])
        XCTAssertFalse(model.preferences.fillNamesAfterStop, "nothing is ticked before the answer")

        await model.answerFillConsent(allow: false)
        XCTAssertTrue(model.fillConsent.isEmpty)
        XCTAssertFalse(model.preferences.fillNamesAfterStop)
        let still = try await bridge.core.transferConsents.missing(targets: [chatGpt])
        XCTAssertEqual(still.count, 1)
        let declined = try await bridge.core.summaries.preferences()
        XCTAssertFalse(declined.fillNamesAfterStop)

        model.setFillTitleAfterStop(true)
        try await until { !model.fillConsent.isEmpty }
        await model.answerFillConsent(allow: true)
        try await until { model.preferences.fillTitleAfterStop }
        XCTAssertTrue(model.fillConsent.isEmpty)
        XCTAssertFalse(model.preferences.fillNamesAfterStop, "the permission ticks the box it was asked for")
        let missing = try await bridge.core.transferConsents.missing(targets: [chatGpt])
        XCTAssertTrue(missing.isEmpty)
        let saved = try await savedPreferences(bridge) { $0.fillTitleAfterStop }
        XCTAssertTrue(saved.fillTitleAfterStop)
        XCTAssertFalse(saved.fillNamesAfterStop)

        // Off never asks; on again — either box — once allowed, does not either.
        model.setFillTitleAfterStop(false)
        XCTAssertFalse(model.preferences.fillTitleAfterStop)
        XCTAssertTrue(model.fillConsent.isEmpty)
        model.setFillNamesAfterStop(true)
        try await until { model.preferences.fillNamesAfterStop }
        XCTAssertTrue(model.fillConsent.isEmpty, "allowed once is allowed")
        XCTAssertTrue(model.fillNamesTicked)
    }

    /// docs/08 "Titles and speaker names": on the iPhone both boxes show ticked only while the permission stands —
    /// withdrawn in Settings → Privacy, they show unticked though they were left ticked, so Save asks for nothing, and
    /// ticking one asks again.
    func testTheIPhoneBoxesAreUntickedOnceThePermissionIsWithdrawn() async throws {
        let bridge = try await CoreBridge.make(
            platform: .ios, deviceName: "Test iPhone",
            dataDirectory: directory, databaseName: "fill-withdrawn.db", secureStore: InMemorySecureStore(),
            transcriptionPolicy: TranscriptionPolicy(region: nil)
        )
        let model = ChatGptSettingsModel(core: bridge.core, browser: FakeBrowser(answer: { _ in .page("connected") }))
        let chatGpt = TransferTargets.shared.chatGptSummary()
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertFalse(model.fillAllowed)
        XCTAssertFalse(model.fillTitleTicked)
        XCTAssertFalse(model.fillNamesTicked)

        model.setFillTitleAfterStop(true)
        try await until { !model.fillConsent.isEmpty }
        await model.answerFillConsent(allow: true)
        try await until { model.fillTitleTicked }
        model.setFillNamesAfterStop(true)
        try await until { model.fillNamesTicked }
        XCTAssertTrue(model.fillAllowed)

        try await bridge.core.transferConsents.revoke(id: chatGpt.id)
        try await until { !model.fillTitleTicked && !model.fillNamesTicked }
        XCTAssertFalse(model.fillAllowed)
        XCTAssertTrue(model.preferences.fillTitleAfterStop, "how the boxes were left is kept")
        XCTAssertTrue(model.preferences.fillNamesAfterStop)
        let unticked = TitleSheetFill(titleTicked: model.fillTitleTicked, namesTicked: model.fillNamesTicked, typedTitle: nil)
        XCTAssertFalse(unticked.any, "Save asks for nothing")

        model.setFillNamesAfterStop(true)
        try await until { !model.fillConsent.isEmpty }
        XCTAssertFalse(model.fillNamesTicked, "nothing is ticked before the answer")
        await model.answerFillConsent(allow: false)
        XCTAssertFalse(model.fillNamesTicked)
        XCTAssertFalse(model.fillTitleTicked)
        let missing = try await bridge.core.transferConsents.missing(targets: [chatGpt])
        XCTAssertEqual(missing.count, 1)
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

/// The preferences as the core keeps them, once [condition] holds — a save runs after the screen shows it.
@MainActor
private func savedPreferences(_ bridge: CoreBridge, until condition: (SummaryPreferences) -> Bool) async throws -> SummaryPreferences {
    var saved = try await bridge.core.summaries.preferences()
    for _ in 0..<200 where !condition(saved) {
        try await Task.sleep(for: .milliseconds(25))
        saved = try await bridge.core.summaries.preferences()
    }
    return saved
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
