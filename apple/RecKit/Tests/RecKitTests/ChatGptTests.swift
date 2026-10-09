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
    private let summary = Summary(recordingId: "r1", text: "Summary\n- one", model: "gpt-x", createdAt: "2026-10-09T00:00:00Z", editedAt: nil)

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
        let edited = Summary(recordingId: "r1", text: "Mine", model: "gpt-x", createdAt: "2026-10-09T00:00:00Z", editedAt: "2026-10-09T01:00:00Z")
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
}

/// A browser that does what the test says when the sign-in opens it.
private final class FakeBrowser: ChatGptBrowser, @unchecked Sendable {
    let answer: @Sendable (URLComponents) -> ChatGptLoopback.Answer
    var onOpen: ((URL, @escaping @MainActor () -> Void) async throws -> Void)?
    var page: String?
    var closed = false

    init(answer: @escaping @Sendable (URLComponents) -> ChatGptLoopback.Answer) { self.answer = answer }

    @MainActor func open(_ url: URL, dismissed: @escaping @MainActor () -> Void) {
        guard let onOpen else { return }
        Task { try? await onOpen(url, dismissed) }
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
