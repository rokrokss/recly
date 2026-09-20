#if os(macOS) || os(iOS)
#if os(macOS)
import AppKit
#else
import UIKit
#endif
import AppAuth
import Foundation
import ReclyCore

/// Drive authorization for both Apple clients (docs/06). No Google profile or ID token is requested.
@MainActor
public final class GoogleAuth {
    #if os(macOS)
    public typealias Anchor = NSWindow
    #else
    public typealias Anchor = UIViewController
    #endif

    public static let scopes = DriveOAuth.scopes
    public static let clientIDPlaceholder = "GIDClientID.apps.googleusercontent.com"

    public enum Failure: Error, CustomStringConvertible {
        case notConfigured
        case scopesDeclined
        case invalidCredential
        case canceled

        public var description: String {
            switch self {
            case .notConfigured: return "GIDClientID in Info.plist is still a placeholder (see README)"
            case .scopesDeclined: return "Drive access has to be allowed before anything can be uploaded"
            case .invalidCredential: return "Could not save a usable Drive connection. Try connecting again."
            case .canceled: return "The sign-in was cancelled"
            }
        }
        public var message: String { RecKitStrings.localized(description) }
    }

    /// Drive-only authorization has no profile email. The settings show the connection state.
    public var account: String? { nil }
    public var restoration: GoogleRestoration { session.restoration }
    private let tokens: AppleTokenProvider
    private let session: DriveOAuthSession
    private static var flow: (any OIDExternalUserAgentSession)?
    private static var connecting = false

    public init(tokens: AppleTokenProvider) {
        self.tokens = tokens
        self.session = tokens.session!
    }

    public static var clientID: String? {
        guard let id = Bundle.main.object(forInfoDictionaryKey: "GIDClientID") as? String,
              id.hasSuffix(".apps.googleusercontent.com"), id != clientIDPlaceholder,
              !id.contains("$("), !id.contains(" ") else { return nil }
        return id
    }
    public static var isConfigured: Bool { clientID != nil }

    @discardableResult
    public func restore() async -> GoogleRestoration {
        guard let clientID = Self.clientID else { return .none }
        await tokens.invalidate()
        return session.restore(clientID: clientID)
    }

    @discardableResult
    public func signIn(presenting anchor: Anchor) async throws -> String? {
        guard let clientID = Self.clientID else { throw Failure.notConfigured }
        // A second click must not start another web session or replace its callback.
        guard !Self.connecting else { throw Failure.canceled }
        Self.connecting = true
        defer { Self.connecting = false; Self.flow = nil }
        let generation = session.generation
        #if os(macOS)
        let agent = OIDExternalUserAgentMac(presenting: anchor)
        #else
        guard let agent = OIDExternalUserAgentIOS(presenting: anchor) else { throw Failure.invalidCredential }
        #endif
        do {
            let state: OIDAuthState = try await withCheckedThrowingContinuation { continuation in
                Self.flow = OIDAuthState.authState(
                    byPresenting: DriveOAuth.request(clientID: clientID), externalUserAgent: agent
                ) { state, error in
                    if let error { continuation.resume(throwing: error) }
                    else if let state { continuation.resume(returning: state) }
                    else { continuation.resume(throwing: Failure.invalidCredential) }
                }
            }
            try session.accept(state, clientID: clientID, generation: generation)
            await tokens.invalidate()
            return nil
        } catch {
            throw Self.isCanceled(error) ? Failure.canceled : error
        }
    }

    static func missingScopes(grantedScopes: [String]?) -> [String] {
        let granted = Set(grantedScopes ?? [])
        return scopes.filter { !granted.contains($0) }
    }
    static func isCanceled(_ error: Error) -> Bool { DriveOAuth.isCanceled(error) }

    public static func handle(_ url: URL) -> Bool {
        // AppAuth 2.1's optional NSError overload crashes Swift 6.3 IR generation. Both entry
        // points perform the same redirect/state validation; use the stable Boolean wrapper.
        flow?.resumeExternalUserAgentFlow(with: url) ?? false
    }

    /// The flow calls the throwing form again during local cleanup, so a keychain failure remains
    /// an owed cleanup instead of a successful disconnect that silently restores on next launch.
    public func signOut() async {
        try? await clearCredentials()
    }

    public func clearCredentials() async throws {
        await Self.flow?.cancel()
        defer { Self.flow = nil }
        await tokens.invalidate()
        try session.clear()
    }

    /// Revocation and local cleanup remain separate steps of the persisted DisconnectFlow.
    public func disconnect() async throws {
        guard let token = session.account?.state.refreshToken else { throw Failure.invalidCredential }
        try await DriveRevokeTransport().revoke(token)
    }
}

@MainActor
public protocol GoogleAccount: AnyObject {
    var tokenExpiry: Date? { get }
    func freshAccessToken() async throws -> String
    func invalidateToken()
}

/// docs/06 "the core interface" on the Apple side.
///
/// The `__`-prefixed names are the raw Kotlin members: SKIE hides them behind the `async` wrappers
/// that callers use, but a Swift *implementation* of the interface still fills in the originals.
@MainActor
public final class AppleTokenProvider: ReclyCore.TokenProvider {
    /// Reuse a token only while its expiry is known and at least a minute away.
    private var cached: String?
    /// Ticks on every [invalidate] — which is a 401, a sign-in and a sign-out. A refresh that
    /// started before a tick belongs to a sign-in state the shell has already left.
    private var generation = 0

    /// The active Drive credential. Read again after the refresh, never captured
    /// once at the top.
    private let currentAccount: @MainActor () -> (any GoogleAccount)?

    let session: DriveOAuthSession?

    public init() {
        let session = DriveOAuthSession(store: KeychainDriveCredentialStore())
        self.session = session
        currentAccount = { session.account }
    }

    /// The seam the sign-out races are tested through.
    init(currentAccount: @escaping @MainActor () -> (any GoogleAccount)?) {
        session = nil
        self.currentAccount = currentAccount
    }

    /// A refresh takes as long as a network round trip, and the user may sign out or sign in as
    /// somebody else while it is in flight. Two things follow, and they are the whole of this
    /// method's care: the cache is only ever served while *somebody* is signed in, and a token is
    /// only ever cached if the sign-in state is still the one the refresh was started under.
    ///
    /// A refusal here is `AuthRequiredException`, so the step parks in `NEEDS_AUTH` rather than
    /// burning retries. It does not strand the job: a sign-in unparks every parked job, and a pass
    /// that finds the core busy comes back inside a minute (`JobRunner.followUp`).
    public func __accessToken() async throws -> String {
        // Before the cache, not after: a provider whose user has signed out must not serve the
        // token that user left behind.
        guard let account = currentAccount() else {
            cached = nil
            // docs/07 §5: a key, not a sentence — the screen that shows `last_error` says it in
            // words.
            throw AuthRequiredException(message: CoreMessage.needsAuth.code(arg: nil, detail: nil)).asError()
        }
        if let cached, let expiry = account.tokenExpiry, expiry.timeIntervalSinceNow > 60 {
            return cached
        }

        let generation = self.generation
        let token: String
        do {
            token = try await account.freshAccessToken()
        } catch let error as URLError {
            // Offline is not "sign in again": the core keeps its retries and the job comes back on
            // the next pass (docs/06 — only a grant that needs the user parks a job).
            throw error
        } catch {
            throw AuthRequiredException(
                message: CoreMessage.needsAuth.code(arg: nil, detail: error.localizedDescription)
            ).asError()
        }

        guard generation == self.generation, currentAccount() === account else {
            // The 401 that invalidated this very token, a sign-out, or a sign-in as another
            // account landed while the refresh was suspended. Caching now would hand the core a
            // token belonging to a state nobody is in any more.
            throw AuthRequiredException(
                message: CoreMessage.needsAuth.code(arg: nil, detail: "sign-in changed under the refresh")
            ).asError()
        }
        cached = token
        return token
    }

    /// A 401 drops the shell cache and forces AppAuth to refresh on the next request.
    public func __invalidate() async throws {
        invalidateNow()
    }

    /// A connection change clears the shell cache without refreshing the newly issued token.
    /// Call directly from Swift rather than through SKIE's Kotlin interface wrapper.
    public func invalidate() async {
        cached = nil
        generation += 1
    }

    private func invalidateNow() {
        cached = nil
        currentAccount()?.invalidateToken()
        generation += 1
    }
}
#endif
