#if os(iOS) || os(macOS)
import Foundation
import os
import ReclyCore
#if os(iOS)
import AuthenticationServices
import UIKit
#elseif os(macOS)
import AppKit
#endif

/// Where the authorization page is shown, and what the loopback answers it with once OpenAI sends the
/// browser back (docs/15 §10): the Mac's default browser, the iPhone's authentication sheet.
public protocol ChatGptBrowser: AnyObject {
    /// Served by the loopback's queue for the callback it matched.
    var answer: @Sendable (URLComponents) -> ChatGptLoopback.Answer { get }
    /// [dismissed]: the user closed the browser before OpenAI sent it back — the iPhone's sheet only.
    @MainActor func open(_ url: URL, dismissed: @escaping @MainActor () -> Void)
    /// The sign-in is over: anything still on screen for it goes.
    @MainActor func close()
}

/// docs/09 "Summary view" · docs/15 §10: the ChatGPT section's state and the sign-in it runs. The tokens never
/// pass through here — the core exchanges the code and keeps them; this owns the socket, the browser and
/// the five minutes the user has to finish.
@MainActor
public final class ChatGptSettingsModel: ObservableObject {
    public enum Failure: Equatable {
        /// The sign-in ended with a [CoreMessage] code other than a cancel.
        case signIn(String)
        /// Signed out here; OpenAI did not confirm the revocation.
        case signOutUnconfirmed
    }

    @Published public private(set) var connection: ChatGptConnection = ChatGptConnection.SignedOut.shared
    /// docs/09 screen principle 5: the Continue button's own progress.
    @Published public private(set) var signInState: ProcessingState = .idle
    /// The authorization page is open and the loopback is waiting for it — "Finish signing in in your browser".
    @Published public private(set) var waitingForBrowser = false
    @Published public private(set) var failure: Failure?
    /// The first sign-in on this device, confirmed once (docs/09 "Summary view").
    @Published public var welcome = false

    /// ChatGPT's own usage page — where the plan's limits are, and where Recly can be disconnected.
    public static let usage = URL(string: "https://chatgpt.com/settings/usage")!

    private let core: ReclyCore_
    private let browser: ChatGptBrowser
    private let timeout: Duration
    private var loopback: ChatGptLoopback?
    private var observer: Task<Void, Never>?
    private let logger = Logger(subsystem: CoreBridge.appName, category: "chatgpt")

    public convenience init(core: ReclyCore_) {
        #if os(iOS)
        self.init(core: core, browser: AuthenticationSheet())
        #else
        self.init(core: core, browser: DefaultBrowser())
        #endif
    }

    init(core: ReclyCore_, browser: ChatGptBrowser, timeout: Duration = .seconds(300)) {
        self.core = core
        self.browser = browser
        self.timeout = timeout
        connection = core.chatGpt.observe().value
        observer = Task { [weak self] in
            for await connection in core.chatGpt.observe() {
                self?.connection = connection
            }
        }
    }

    deinit { observer?.cancel() }

    /// docs/15 "China mainland App Store": the whole section goes where ChatGPT is not offered.
    public var available: Bool { !(connection is ChatGptConnection.Unavailable) }

    /// Reads the sign-in this device holds and the plan's models — when the app opens and when Settings
    /// does. A keychain that will not be read leaves what the screen shows as it was.
    public func refresh() async {
        do {
            _ = try await core.chatGpt.refresh()
        } catch {
            logger.error("shell.chatgpt.refresh.failed error=\(String(describing: error), privacy: .private)")
        }
    }

    public func signIn() {
        guard loopback == nil else { return }
        failure = nil
        signInState = .processing
        let loopback = ChatGptLoopback(matched: browser.answer)
        self.loopback = loopback
        Task { await run(loopback) }
    }

    /// The user's Cancel: the wait ends as if the browser had never come back.
    public func cancelSignIn() {
        loopback?.stop()
    }

    public func signOut() {
        failure = nil
        Task {
            do {
                if case .failed = onEnum(of: try await core.chatGpt.signOut()) { failure = .signOutUnconfirmed }
            } catch {
                logger.error("shell.chatgpt.signout.failed error=\(String(describing: error), privacy: .private)")
            }
        }
    }

    public func selectModel(_ id: String) {
        Task { try? await core.chatGpt.selectModel(id: id) }
    }

    private func run(_ loopback: ChatGptLoopback) async {
        // The limit and Cancel hold from the first moment, before the browser is open too.
        let timeout = self.timeout
        let clock = Task {
            guard (try? await Task.sleep(for: timeout)) != nil else { return }
            loopback.stop()
        }
        waitingForBrowser = true
        defer {
            clock.cancel()
            loopback.stop()
            browser.close()
            waitingForBrowser = false
            self.loopback = nil
        }
        do {
            let port = try await loopback.start()
            let started = try await core.chatGpt.beginSignIn(redirectUri: ChatGptLoopback.redirectUri(port: port))
            loopback.expect(state: started.state)
            guard let url = URL(string: started.authorizationUrl) else { throw URLError(.badURL) }
            browser.open(url) { [weak loopback] in loopback?.stop() }
            let callback = await loopback.callback()
            clock.cancel()
            waitingForBrowser = false
            // The browser has its answer; the port has nothing left to wait for.
            loopback.stop()
            browser.close()
            guard let callback else {
                core.chatGpt.cancelSignIn()
                signInState = .idle
                return
            }
            switch onEnum(of: try await core.chatGpt.finishSignIn(callbackUrl: callback)) {
            case .done(let done):
                welcome = done.welcome
                signInState = .done
            case .failed(let failed):
                // A sign-in the user declined at OpenAI is not a failure to report.
                if ChatGptText.cancelled(failed.reason) {
                    signInState = .idle
                } else {
                    failure = .signIn(failed.reason)
                    signInState = .failed
                }
            }
        } catch {
            core.chatGpt.cancelSignIn()
            // The domain and code only: a message could carry the callback, and with it the code.
            let cause = error as NSError
            logger.error("shell.chatgpt.signin.failed domain=\(cause.domain, privacy: .public) code=\(cause.code, privacy: .public)")
            failure = .signIn(CoreMessage.providerError.code(arg: nil, detail: error.localizedDescription))
            signInState = .failed
        }
    }
}

#if os(macOS)
/// The Mac's default browser; the tab is answered with a page that says where to go next.
final class DefaultBrowser: ChatGptBrowser {
    let answer: @Sendable (URLComponents) -> ChatGptLoopback.Answer = { callback in
        let declined = callback.queryItems?.contains { $0.name == "error" } == true
        return .page(ChatGptLoopback.page(declined
            ? RecKitStrings.localized("The sign-in was cancelled. Close this window and go back to Recly.")
            : RecKitStrings.localized("ChatGPT is connected. Close this window and return to Recly.")))
    }

    @MainActor func open(_ url: URL, dismissed: @escaping @MainActor () -> Void) {
        if !NSWorkspace.shared.open(url) { dismissed() }
    }

    @MainActor func close() {}
}
#endif

#if os(iOS)
/// The iPhone's authentication sheet. The app stays in front while it is up, so the loopback keeps
/// listening; its answer is a redirect to [scheme], which the sheet takes as its cue to close.
final class AuthenticationSheet: NSObject, ChatGptBrowser, ASWebAuthenticationPresentationContextProviding {
    nonisolated static let scheme = "recly-chatgpt"

    let answer: @Sendable (URLComponents) -> ChatGptLoopback.Answer = { _ in .redirect("\(AuthenticationSheet.scheme)://signed-in") }
    private var session: ASWebAuthenticationSession?

    @MainActor func open(_ url: URL, dismissed: @escaping @MainActor () -> Void) {
        // An error is the sheet closed before the redirect — Cancel, or a swipe down. After the redirect
        // the loopback already has the callback, and a late error changes nothing.
        let completion: ASWebAuthenticationSession.CompletionHandler = { _, error in
            guard error != nil else { return }
            Task { @MainActor in dismissed() }
        }
        let session: ASWebAuthenticationSession
        if #available(iOS 17.4, *) {
            session = ASWebAuthenticationSession(url: url, callback: .customScheme(Self.scheme), completionHandler: completion)
        } else {
            session = ASWebAuthenticationSession(url: url, callbackURLScheme: Self.scheme, completionHandler: completion)
        }
        session.presentationContextProvider = self
        // The browser's ChatGPT sign-in is the one to use; an ephemeral sheet would ask for it every time.
        session.prefersEphemeralWebBrowserSession = false
        self.session = session
        if !session.start() { dismissed() }
    }

    @MainActor func close() {
        session?.cancel()
        session = nil
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        MainActor.assumeIsolated {
            UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .flatMap(\.windows)
                .first { $0.isKeyWindow } ?? ASPresentationAnchor()
        }
    }
}
#endif
#endif
