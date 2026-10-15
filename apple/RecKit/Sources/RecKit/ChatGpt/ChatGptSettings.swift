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
    ///
    /// - Returns: false when no browser opened at all — the Mac's default browser refused the URL, the iPhone's
    ///   sheet would not start — which is a failure to say rather than a cancel (Windows gives the same answer after
    ///   its 30 seconds; here the system answers at once).
    @MainActor func open(_ url: URL, dismissed: @escaping @MainActor () -> Void) -> Bool
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
    /// docs/08 "Summaries": this device's summary format, My format, About you and how the title sheet's ChatGPT box
    /// was left ("Titles and speaker names"), as saved — shown and editable signed in or out.
    @Published public private(set) var preferences = SummaryPreferences(format: .auto, customFormat: "", aboutMe: "", fillAfterStop: false)
    /// My format and About you as their fields hold them; saved when editing ends ([savePreferences]).
    @Published public var customFormat = ""
    @Published public var aboutMe = ""
    /// docs/15 "iPhone providers": the ChatGPT destination the title sheet's box waits on before it is ticked — asked
    /// about only where the core requires the permission, so never on the Mac.
    @Published public private(set) var fillConsent: [TransferTarget] = []
    /// docs/15 "iPhone providers": the transcript may go to ChatGPT now — at once where no permission is asked (the
    /// Mac); on the iPhone once allowed, and no longer once withdrawn in Settings → Privacy.
    @Published public private(set) var fillAllowed: Bool

    /// ChatGPT's own usage page — where the plan's limits are, and where Recly can be disconnected.
    public static let usage = URL(string: "https://chatgpt.com/settings/usage")!

    private let core: ReclyCore_
    private let browser: ChatGptBrowser
    private let timeout: Duration
    private var loopback: ChatGptLoopback?
    private var observer: Task<Void, Never>?
    private var preferencesObserver: Task<Void, Never>?
    private var consentObserver: Task<Void, Never>?
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
        fillAllowed = !core.transferConsents.enabled
        connection = core.chatGpt.observe().value
        observer = Task { [weak self] in
            for await connection in core.chatGpt.observe() {
                self?.connection = connection
            }
        }
        consentObserver = Task { [weak self] in
            guard core.transferConsents.enabled else { return }
            for await _ in core.transferConsents.observe() {
                guard let self else { return }
                await self.readFillConsent()
            }
        }
        preferencesObserver = Task { [weak self] in
            for await preferences in core.summaries.observePreferences() {
                self?.adopt(preferences)
            }
        }
    }

    deinit {
        observer?.cancel()
        preferencesObserver?.cancel()
        consentObserver?.cancel()
    }

    /// docs/15 "China mainland App Store": the whole section goes where ChatGPT is not offered.
    public var available: Bool { !(connection is ChatGptConnection.Unavailable) }

    /// docs/08 "Titles and speaker names": the title sheet offers its ChatGPT box only while signed in — an ended
    /// sign-in is not one, and signed in means ChatGPT is offered here. The shell adds that transcription is on.
    public var fillOffered: Bool { connection is ChatGptConnection.SignedIn }

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

    // MARK: - Summary preferences (docs/08 "Summaries")

    /// What the section shows: the saved format over the fields as they are typed, so `My format` is offered
    /// once it has words and General stands in for it once they are gone (`effectiveFormat`).
    public var shownPreferences: SummaryPreferences {
        SummaryPreferences(
            format: preferences.format, customFormat: customFormat, aboutMe: aboutMe,
            fillAfterStop: preferences.fillAfterStop
        )
    }

    /// Saved at once, like the model — with the fields as they are, so a pick made while one is being typed in
    /// keeps what was typed. Shown at once too: a field that ends its editing as the menu closes saves with the
    /// format just picked, not the one before it.
    public func selectFormat(_ format: SummaryFormat) {
        preferences = SummaryPreferences(
            format: format, customFormat: preferences.customFormat, aboutMe: preferences.aboutMe,
            fillAfterStop: preferences.fillAfterStop
        )
        save(shownPreferences)
    }

    /// docs/08 "Titles and speaker names": the title sheet's box as it shows — ticked only when it was left ticked and
    /// the transcript may go to ChatGPT now, so a permission withdrawn since never spends a request. Unticked so,
    /// ticking it asks again.
    public var fillTicked: Bool { preferences.fillAfterStop && fillAllowed }

    /// A keychain that will not be read proves no grant, as the core's own guard reads it.
    private func readFillConsent() async {
        let missing = try? await core.transferConsents.missing(targets: [TransferTargets.shared.chatGptSummary()])
        fillAllowed = missing?.isEmpty ?? false
    }

    /// docs/08 "Titles and speaker names": the title sheet's `Fill in the title and speaker names with ChatGPT`, as
    /// the next sheet starts it. Saved at once, like the format; see [switchFill].
    public func setFillAfterStop(_ on: Bool) {
        switchFill(on) { [weak self] in self?.showFill(on) }
    }

    /// Off goes at once and never asks. On sends the recording's transcript to OpenAI, so where the ChatGPT
    /// destination needs the user's permission and does not have it yet — the iPhone — it is asked for first
    /// ([fillConsent]), and the box stays off until it is given: a request the core could not run is never made.
    private func switchFill(_ on: Bool, apply: @escaping () -> Void) {
        guard on else { apply(); return }
        Task {
            do {
                let missing = try await core.transferConsents.missing(targets: [TransferTargets.shared.chatGptSummary()])
                if missing.isEmpty {
                    fillAllowed = true
                    apply()
                } else {
                    afterFillConsent = apply
                    fillConsent = missing
                }
            } catch {
                logger.error("shell.chatgpt.preferences.failed error=\(String(describing: error), privacy: .private)")
            }
        }
    }

    /// The box shown at once and saved, with the fields as they are — as [selectFormat] saves them.
    private func showFill(_ on: Bool) {
        preferences = SummaryPreferences(
            format: preferences.format, customFormat: preferences.customFormat, aboutMe: preferences.aboutMe,
            fillAfterStop: on
        )
        save(shownPreferences)
    }

    /// What [fillConsent] goes on to do once it is allowed: ticking the box it stood in front of.
    private var afterFillConsent: (() -> Void)?

    /// The answer to [fillConsent]: allowed, exactly the destination shown is granted and the box is ticked;
    /// declined, it stays off and nothing is sent.
    public func answerFillConsent(allow: Bool) async {
        let shown = fillConsent
        let next = afterFillConsent
        fillConsent = []
        afterFillConsent = nil
        guard allow, !shown.isEmpty else { return }
        do {
            try await core.transferConsents.grant(targets: shown)
        } catch {
            logger.error("shell.chatgpt.preferences.failed error=\(String(describing: error), privacy: .private)")
            return
        }
        // Ticked as it is allowed, not a moment later when the grant comes back through [consentObserver].
        fillAllowed = true
        next?()
    }

    /// The fields' end of editing — focus gone, Return, the screen closed. Nothing to do when what they hold is
    /// what is saved.
    public func savePreferences() {
        let next = shownPreferences
        guard Self.trimmed(next.customFormat) != preferences.customFormat || Self.trimmed(next.aboutMe) != preferences.aboutMe else { return }
        save(next)
    }

    /// One save after another, so the last thing the user did is what is kept.
    private func save(_ next: SummaryPreferences) {
        let previous = saving
        saving = Task {
            await previous?.value
            do {
                adopt(try await core.summaries.setPreferences(preferences: next))
            } catch {
                logger.error("shell.chatgpt.preferences.failed error=\(String(describing: error), privacy: .private)")
            }
        }
    }

    private var saving: Task<Void, Never>?

    /// The saved preferences; a field keeps its own text while that is the saved text, trimmed — the spaces
    /// being typed are not taken away.
    private func adopt(_ saved: SummaryPreferences) {
        preferences = saved
        if Self.trimmed(customFormat) != saved.customFormat { customFormat = saved.customFormat }
        if Self.trimmed(aboutMe) != saved.aboutMe { aboutMe = saved.aboutMe }
    }

    private static func trimmed(_ text: String) -> String { text.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// The sign-in's failure when no browser opened: `Could not sign in to ChatGPT`, with the detail in the words
    /// Windows writes — untranslated, as every detail is.
    static let noBrowser = CoreMessage.providerError.code(arg: nil, detail: "no browser opened")

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
            guard browser.open(url, dismissed: { [weak loopback] in loopback?.stop() }) else {
                core.chatGpt.cancelSignIn()
                logger.error("shell.chatgpt.signin.failed reason=no-browser")
                failure = .signIn(Self.noBrowser)
                signInState = .failed
                return
            }
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

    @MainActor func open(_ url: URL, dismissed: @escaping @MainActor () -> Void) -> Bool {
        NSWorkspace.shared.open(url)
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

    @MainActor func open(_ url: URL, dismissed: @escaping @MainActor () -> Void) -> Bool {
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
        return session.start()
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
