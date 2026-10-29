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

/// docs/09 "AI view" · docs/15 §10: the ChatGPT section's state and the sign-in it runs. The tokens never
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
    /// The first sign-in on this device, confirmed once (docs/09 "AI view").
    @Published public var welcome = false
    /// docs/08 "Organizing": the consent question after a sign-in, while the core says it is unanswered. Asked before
    /// the welcome.
    @Published public var organizeConsent = false
    /// docs/08 "Organizing": signed in and not agreed (`notes.observeNeedsConsent()`) — a start from the app's own
    /// screen asks first.
    @Published public private(set) var needsOrganizeConsent = false
    /// docs/08 "AI requests": this device's About you, as saved — shown and editable signed in or out.
    @Published public private(set) var preferences = SummaryPreferences(aboutMe: "")
    /// About you as its field holds it; saved when editing ends ([savePreferences]).
    @Published public var aboutMe = ""
    /// docs/08 "Organizing" — "Settings": what this device asks every recording's notes for, as saved — Settings → Notes.
    @Published public private(set) var notesPreferences = NotesPreferences(instructions: "", sections: [])
    /// Notes instructions and My sections as their fields hold them; saved when editing ends ([saveNotes]).
    @Published public var instructions = ""
    @Published public var sections: [NoteSectionDraft] = []
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
    private var notesObserver: Task<Void, Never>?
    private var consentObserver: Task<Void, Never>?
    private var organizeObserver: Task<Void, Never>?
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
        organizeObserver = Task { [weak self] in
            for await needs in core.notes.observeNeedsConsent() {
                self?.needsOrganizeConsent = needs.boolValue
            }
        }
        notesObserver = Task { [weak self] in
            for await preferences in core.notes.observePreferences() {
                self?.adoptNotes(preferences)
            }
        }
    }

    deinit {
        observer?.cancel()
        preferencesObserver?.cancel()
        notesObserver?.cancel()
        consentObserver?.cancel()
        organizeObserver?.cancel()
    }

    /// docs/15 "China mainland App Store": the whole section goes where ChatGPT is not offered.
    public var available: Bool { !(connection is ChatGptConnection.Unavailable) }

    /// docs/08 "Titles and speaker names": a recording published now has its missing title and its speakers' names filled
    /// in — signed in (an ended sign-in is not one, and signed in means ChatGPT is offered here), agreed to the notes'
    /// consent, and the plan a destination the transcript may go to (the iPhone's permission). The shell adds that
    /// transcription is on.
    public var autoFill: Bool { connection is ChatGptConnection.SignedIn && !needsOrganizeConsent && fillAllowed }

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

    /// docs/08 "Organizing" · docs/15 "iPhone providers": the destination the consent sheet discloses while the plan is
    /// not yet an allowed one ([fillAllowed]) — the iPhone's, since Continue allows it.
    public var organizePermission: [TransferTarget] { OrganizeConsent.permission(destinationAllowed: fillAllowed) }

    /// docs/08 "Organizing": the consent sheet's answer — after a sign-in, or at a start. Continue agrees and, on the
    /// iPhone, allows the plan as a destination in the same action; Sign out is the section's own sign-out. Signed out,
    /// there is no welcome to show either.
    public func answerOrganizeConsent(_ answer: OrganizeConsent.Answer) async {
        organizeConsent = false
        if answer == .signOut { welcome = false }
        let core = core
        // Only where the core asks for the permission at all: it refuses a grant anywhere else.
        var allowDestination: (() async throws -> Void)?
        if core.transferConsents.enabled {
            allowDestination = { try await core.transferConsents.grant(targets: [TransferTargets.shared.chatGptSummary()]) }
        }
        let agreed = await OrganizeConsent.apply(
            answer,
            consent: { try await core.notes.consent() },
            allowDestination: allowDestination,
            signOut: { signOut() }
        )
        if !agreed { logger.error("shell.chatgpt.organize.consent.failed") }
    }

    public func selectModel(_ id: String) {
        Task { try? await core.chatGpt.selectModel(id: id) }
    }

    // MARK: - About you (docs/08 "AI requests")

    /// About you as its field holds it.
    public var shownPreferences: SummaryPreferences { SummaryPreferences(aboutMe: aboutMe) }

    /// A keychain that will not be read proves no grant, as the core's own guard reads it.
    private func readFillConsent() async {
        let missing = try? await core.transferConsents.missing(targets: [TransferTargets.shared.chatGptSummary()])
        fillAllowed = missing?.isEmpty ?? false
    }

    /// About you's end of editing — focus gone, Return, the screen closed. Nothing to do when what it holds is what is
    /// saved.
    public func savePreferences() {
        let next = shownPreferences
        guard Self.trimmed(next.aboutMe) != preferences.aboutMe else { return }
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
        if Self.trimmed(aboutMe) != saved.aboutMe { aboutMe = saved.aboutMe }
    }

    // MARK: - Settings → Notes (docs/08 "Organizing" — "Settings")

    /// `Add section`, while there are fewer than [NotesPreferences.SECTIONS_MAX]: an empty section at the end, kept in
    /// the list until editing leaves it without a name. Answers its key, for the name field to take the focus.
    @discardableResult
    public func addSection() -> UUID? {
        guard sections.count < Int(NotesPreferences.companion.SECTIONS_MAX) else { return nil }
        let section = NoteSectionDraft()
        sections.append(section)
        return section.key
    }

    /// `Remove`: gone from the list and saved at once.
    public func removeSection(_ key: UUID) {
        sections.removeAll { $0.key == key }
        saveNotes()
    }

    /// The notes fields' end of editing — focus gone, Return, the screen closed. [leaving] is the section editing has
    /// just left, which goes from the list if it has no name; [droppingNameless] takes every such one, as the screen
    /// goes. Nothing is saved when what the fields hold is what is saved.
    public func saveNotes(leaving: UUID? = nil, droppingNameless: Bool = false) {
        sections.removeAll { section in
            (droppingNameless || section.key == leaving) && Self.trimmed(section.name).isEmpty
        }
        guard shownNotes != notesPreferences else { return }
        let previous = notesSaving
        notesSaving = Task {
            await previous?.value
            // Read once the save before has given its new sections their ids, so a section is not sent twice without one.
            let next = shownNotes
            guard next != notesPreferences else { return }
            do {
                adoptNotes(try await core.notes.setPreferences(preferences: next))
            } catch {
                logger.error("shell.notes.preferences.failed error=\(String(describing: error), privacy: .private)")
            }
        }
    }

    private var notesSaving: Task<Void, Never>?

    /// What the fields hold, as the core would keep it: trimmed, a section without a name left out.
    var shownNotes: NotesPreferences {
        NotesPreferences(
            instructions: Self.trimmed(instructions),
            sections: sections.compactMap { section in
                let name = Self.trimmed(section.name)
                return name.isEmpty ? nil : NoteSection(id: section.sectionId, name: name, description: Self.trimmed(section.description))
            }
        )
    }

    /// The saved notes preferences. A field keeps its own text while that is the saved text, trimmed, as About you does;
    /// a section given a name takes the id the core gave it; a section still without a name stays in the list while
    /// editing is in it. With nothing named in the list — the first read — the saved sections are the list.
    func adoptNotes(_ saved: NotesPreferences) {
        notesPreferences = saved
        if Self.trimmed(instructions) != saved.instructions { instructions = saved.instructions }
        let named = sections.indices.filter { !Self.trimmed(sections[$0].name).isEmpty }
        if named.isEmpty {
            sections = saved.sections.map(NoteSectionDraft.init) + sections
            return
        }
        // A name typed while a save was on its way: the save that ends its editing settles the list.
        guard named.count == saved.sections.count else { return }
        for (index, kept) in zip(named, saved.sections) {
            sections[index].sectionId = kept.id
            if Self.trimmed(sections[index].name) != kept.name { sections[index].name = kept.name }
            if Self.trimmed(sections[index].description) != kept.description_ { sections[index].description = kept.description_ }
        }
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
                // docs/08 "Organizing": the sign-in shows the consent — unless it was given before and not withdrawn
                // since (a sign-in OpenAI ended keeps it). A store that cannot be read asks.
                organizeConsent = (try? await core.notes.needsConsent())?.boolValue ?? true
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

/// One of My sections as Settings → Notes holds it while it is edited (docs/08 "Organizing" — "Settings"). [key] is the
/// row's own, for the list and the focus; [sectionId] is the core's, empty until the section is first saved.
public struct NoteSectionDraft: Identifiable, Equatable {
    public let key = UUID()
    public var sectionId: String
    public var name: String
    public var description: String

    public var id: UUID { key }

    public init(sectionId: String = "", name: String = "", description: String = "") {
        self.sectionId = sectionId
        self.name = name
        self.description = description
    }

    init(_ saved: NoteSection) {
        self.init(sectionId: saved.id, name: saved.name, description: saved.description_)
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
