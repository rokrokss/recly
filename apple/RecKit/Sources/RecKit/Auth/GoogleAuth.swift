// GoogleSignIn ships no watchOS slice and the watch never touches Drive (ADR-002), so this is the
// whole of the Apple sign-in: the Mac (M4-L4) and the phone (docs/13 I3) share every line of it.
#if os(macOS) || os(iOS)
#if os(macOS)
import AppKit
import GTMAppAuth
#else
import UIKit
#endif
import Foundation
import GoogleSignIn
import ReclyCore

/// docs/06 "iOS · macOS": the interactive half of Google sign-in. The SDK keeps the refresh token
/// in the Keychain and hands the signed-in user back across launches, so this type holds no
/// credential of its own — [AppleTokenProvider] reads `GIDSignIn.sharedInstance.currentUser`
/// whenever the core asks for a token.
///
/// `@MainActor` throughout: `GIDSignIn` presents an `ASWebAuthenticationSession` and calls its
/// completions on the main queue.
@MainActor
public final class GoogleAuth {
    /// What the SDK wants to hang the consent web view off: a window on the Mac, the view
    /// controller the user is looking at on the phone. `signIn(withPresenting:)` takes one of each
    /// under the same label, so the two shells call the same method.
    #if os(macOS)
    public typealias Anchor = NSWindow
    #else
    public typealias Anchor = UIViewController
    #endif
    /// The two ADR-009 scopes and nothing else — anything more turns the app sensitive (docs/06).
    public static let scopes = [
        "https://www.googleapis.com/auth/drive.file",
    ]

    /// What `Info.plist` ships with until someone follows the README procedure. `GIDSignIn` raises
    /// an Obj-C exception — not an error — when it is asked to sign in with no client id, so every
    /// entry point here checks first.
    public static let clientIDPlaceholder = "GIDClientID.apps.googleusercontent.com"

    public enum Failure: Error, CustomStringConvertible {
        /// No usable `GIDClientID` in `Info.plist`.
        case notConfigured
        /// The user signed in but withheld one of the two Drive scopes.
        case scopesDeclined
        /// `GIDSignInError.canceled` — the user closed the consent sheet. Nothing failed and there
        /// is nothing to tell them; the shells swallow this one instead of raising a banner.
        case canceled

        /// English, because this is what the logs carry — and it is also the docs/07 key
        /// [message] resolves, so the two cannot drift.
        public var description: String {
            switch self {
            case .notConfigured:
                return "GIDClientID in Info.plist is still a placeholder (see README)"
            case .scopesDeclined:
                return "Drive access has to be allowed before anything can be uploaded"
            case .canceled:
                return "The sign-in was cancelled"
            }
        }

        /// The same thing in the app's language, for a screen to show (docs/07).
        public var message: String { RecKitStrings.localized(description) }
    }

    /// The signed-in account's email, or nil. Read off the SDK, so it survives a restart with the
    /// sign-in it belongs to and cannot drift from it.
    ///
    /// Never the answer to "is this device signed in": Google's profile is optional and a perfectly
    /// valid user may carry no email at all. [restoration] is that answer.
    public private(set) var account: String?

    /// docs/06: what the last [restore] found, and what every sign-in and sign-out since has done
    /// to it — the whole of what this device knows about whether it still holds a credential. A
    /// disconnect reads this and not [account] ([DisconnectGuard.revokeDecision]).
    public private(set) var restoration: GoogleRestoration = .none

    private let tokens: AppleTokenProvider

    public init(tokens: AppleTokenProvider) {
        self.tokens = tokens
    }

    /// nil when `Info.plist` still carries the placeholder — the menu shows sign-in as unavailable
    /// rather than raising a prompt that cannot succeed.
    public static var clientID: String? {
        guard let id = Bundle.main.object(forInfoDictionaryKey: "GIDClientID") as? String,
              !id.isEmpty, id != clientIDPlaceholder
        else { return nil }
        return id
    }

    public static var isConfigured: Bool { clientID != nil }

    /// docs/06: the SDK's own Keychain entry is the source of truth for "who is signed in", so a
    /// launch restores from it rather than from anything this app stores.
    ///
    /// The outcome is returned and kept rather than swallowed. `try?` here read every failure as
    /// "nobody is signed in", which is right for the menu — it has nothing else to show — and
    /// wrong for a disconnect, which would skip the revoke over a grant that may well still be
    /// standing. [GoogleRestoration.failed] is the third answer that mistake had no room for.
    @discardableResult
    public func restore() async -> GoogleRestoration {
        guard Self.configure() != nil, GIDSignIn.sharedInstance.hasPreviousSignIn() else {
            restoration = .none
            return restoration
        }
        do {
            let user = try await GIDSignIn.sharedInstance.restorePreviousSignIn()
            account = user.profile?.email
            Self.hint = account ?? Self.hint
            // The SDK's own reading rather than the user handed back, and never the email: what
            // matters downstream is that a credential is held, and a profile is optional.
            restoration = .restored(hasCredential: GIDSignIn.sharedInstance.currentUser != nil)
        } catch let error as GIDSignInError where error.code == .hasNoAuthInKeychain {
            // "The user has not signed in before or … have since signed out" — which is not news
            // to anybody, and is exactly the nobody-is-signed-in state.
            account = nil
            restoration = .none
        } catch {
            // Anything else — a Keychain that would not open, a network the refresh needed — leaves
            // it unknown, which is the one thing a disconnect must not guess at.
            restoration = .failed(error.localizedDescription)
        }
        return restoration
    }

    /// `signIn(withPresenting:hint:additionalScopes:)`, the docs/06 call. The anchor is only what
    /// the consent web view is presented from; `LSUIElement` means the Mac may not have a window,
    /// and the caller passes whatever it has.
    ///
    /// Both Drive scopes are asked for here rather than at the first upload: Recly wants a Google
    /// account for Drive and for nothing else, so a sign-in without them is a sign-in the app
    /// cannot use (docs/06 "iOS · macOS").
    @discardableResult
    public func signIn(presenting anchor: Anchor) async throws -> String? {
        guard Self.configure() != nil else { throw Failure.notConfigured }
        let result: GIDSignInResult
        do {
            result = try await GIDSignIn.sharedInstance.signIn(
                withPresenting: anchor,
                // OAuth's `login_hint`, "to be prefilled if possible". Set only when the last
                // sign-in ended in something other than a sign-out (see [hint]) — a user who
                // signed out may well be here to switch accounts, and a hint would pick for them.
                hint: Self.hint,
                additionalScopes: Self.scopes
            )
        } catch {
            throw Self.isCanceled(error) ? Failure.canceled : error
        }
        guard Self.missingScopes(grantedScopes: result.user.grantedScopes).isEmpty else {
            GIDSignIn.sharedInstance.signOut()
            throw Failure.scopesDeclined
        }
        // Whatever was cached belonged to whoever was signed in a moment ago.
        await tokens.invalidate()
        account = result.user.profile?.email
        // A consent that came back is a credential held, whether or not it carries an email.
        restoration = .restored(hasCredential: true)
        Self.hint = account
        return account
    }

    /// Which of [scopes] a `GIDGoogleUser` has not granted. Google documents this check —
    /// "check which scopes have already been granted to your app, using the `grantedScopes`
    /// property" — as the thing to do before calling the API; here it runs on the consent that just
    /// came back, because a partial grant is the one answer that leaves the app unable to upload.
    static func missingScopes(grantedScopes: [String]?) -> [String] {
        let granted = Set(grantedScopes ?? [])
        return scopes.filter { !granted.contains($0) }
    }

    /// `GIDSignInError.canceled` (-5), "the user canceled the sign in request" — told apart from a
    /// real failure so that closing the sheet leaves no error on screen.
    static func isCanceled(_ error: Error) -> Bool {
        (error as? GIDSignInError)?.code == .canceled
    }

    /// The redirect back from the consent web view, on the reversed-client-id scheme the
    /// `Info.plist` claims. The shell owns the URL callback and the SDK owns what to do with it;
    /// this is the seam, so no shell has to import GoogleSignIn for one line.
    ///
    /// Both shells owe the SDK this call and macOS is not exempt — Google's guide gives the Mac its
    /// own step (an `applicationDidFinishLaunching` handler for `kAEGetURL`, whose modern AppKit
    /// form is `application(_:open:)`); the phone does it with `.onOpenURL`.
    public static func handle(_ url: URL) -> Bool {
        GIDSignIn.sharedInstance.handle(url)
    }

    /// Sign out, never `disconnect`. `signOut` "clears the sign-in state stored in `GIDSignIn` and
    /// removes the user's credentials for your app from the Keychain" and goes no further —
    /// "Signing out only applies to your app… it does not revoke the permissions the user granted".
    /// `disconnect` would, and revocation is per Cloud project rather than per device: it would
    /// take the grant away from the user's PC and Android phone as well (docs/06 "iOS · macOS").
    ///
    /// The hint goes with it: somebody who signs out may be here to sign in as somebody else.
    public func signOut() async {
        GIDSignIn.sharedInstance.signOut()
        await tokens.invalidate()
        account = nil
        restoration = .none
        Self.hint = nil
    }

    /// docs/03 "연결 해제": the other one. `disconnect` "revokes all scopes the user granted" and
    /// signs out as well, and the revocation is per Cloud project rather than per device — which is
    /// exactly why the ordinary sign-out must never call it, and why the dialog in front of this one
    /// has to say that the user's other devices lose access too.
    ///
    /// The local half is the core's (`ReclyCore.disconnect`), which this does not do: the shell
    /// owns the grant and the core owns the database. Call both, in that order.
    ///
    /// Throws whatever the SDK said. The caller signs out locally anyway — this device was asked to
    /// be done with the account — and tells the user that the grant is still standing.
    public func disconnect() async throws {
        guard Self.configure() != nil else { throw Failure.notConfigured }
        defer {
            Task { await tokens.invalidate() }
            account = nil
            restoration = .none
            Self.hint = nil
        }
        try await GIDSignIn.sharedInstance.disconnect()
    }

    /// The address the *next* sign-in is prefilled with — the last account seen on this device.
    /// Not a credential and not a secret: `UserDefaults` is where a hint belongs, and losing it
    /// only costs a prefilled field. Cleared by [signOut], so it survives exactly the endings the
    /// user did not choose — a reinstall, a lost Keychain — which are the ones worth a hint.
    private static var hint: String? {
        get { UserDefaults.standard.string(forKey: hintKey) }
        set { UserDefaults.standard.set(newValue, forKey: hintKey) }
    }

    private static let hintKey = "app.recly.auth.lastAccount"

    /// The SDK reads `GIDClientID` from `Info.plist` itself, but only if it can — setting the
    /// configuration explicitly is what lets the placeholder be refused before anything is
    /// presented. Returns nil when there is no usable client id.
    @discardableResult
    private static func configure() -> String? {
        guard let clientID else { return nil }
        #if os(macOS)
        useLoginKeychain()
        #endif
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID)
        return clientID
    }

    #if os(macOS)
    /// docs/06 "iOS · macOS": the SDK saves the credential in the data-protection keychain, which
    /// on macOS refuses a process without a team-signed keychain access group — every ad-hoc
    /// build, so a consent that came back was lost on the way to the keychain and the sign-in
    /// reported failure. The login keychain takes it from anyone, so the SDK's store is swapped
    /// for one that uses it, under the SDK's own item name so a restore finds what a sign-in
    /// saved.
    ///
    /// The SDK keeps the store in a private ivar with no public way in; KVC on the ivar's name is
    /// the one door. An SDK release that renames it would put the sign-in back on the
    /// data-protection keychain, which fails the same visible way as before rather than silently
    /// — and the read-back below says so in the log on the first launch.
    private static var loginKeychainInstalled = false

    private static func useLoginKeychain() {
        guard !loginKeychainInstalled else { return }
        loginKeychainInstalled = true
        let store = KeychainStore(itemName: sdkKeychainItemName, keychainAttributes: [.useFileBasedKeychain])
        let signIn = GIDSignIn.sharedInstance
        signIn.setValue(store, forKey: sdkKeychainStoreKey)
        if signIn.value(forKey: sdkKeychainStoreKey) as? KeychainStore !== store {
            NSLog("GoogleAuth: the SDK's keychain store could not be replaced; sign-in will use the data-protection keychain")
        }
    }

    /// `kGTMAppAuthKeychainName` in the SDK: the item its own store reads and writes.
    private static let sdkKeychainItemName = "auth"
    /// The SDK's `_keychainStore` ivar, as KVC names it.
    private static let sdkKeychainStoreKey = "keychainStore"
    #endif
}

/// What [AppleTokenProvider] needs of a signed-in account: a token fresh enough to use. The SDK's
/// own `GIDGoogleUser` conforms; a test conforms its own, which is the only way to hold a refresh
/// suspended and sign out underneath it.
public protocol GoogleAccount: AnyObject {
    func freshAccessToken() async throws -> String
}

extension GIDGoogleUser: GoogleAccount {
    /// docs/06: the refresh token is the SDK's and never this app's, so `refreshTokensIfNeeded` is
    /// the whole of the refresh.
    public func freshAccessToken() async throws -> String {
        try await refreshTokensIfNeeded().accessToken.tokenString
    }
}

/// docs/06 "the core interface" on the Apple side.
///
/// The `__`-prefixed names are the raw Kotlin members: SKIE hides them behind the `async` wrappers
/// that callers use, but a Swift *implementation* of the interface still fills in the originals.
@MainActor
public final class AppleTokenProvider: ReclyCore.TokenProvider {
    /// The last token handed to the core. Held so a job with several steps does not walk the SDK
    /// once per step, and dropped by [invalidate].
    private var cached: String?
    /// Ticks on every [invalidate] — which is a 401, a sign-in and a sign-out. A refresh that
    /// started before a tick belongs to a sign-in state the shell has already left.
    private var generation = 0

    /// Who the SDK says is signed in *right now*. Read again after the refresh, never captured
    /// once at the top.
    private let currentAccount: @MainActor () -> (any GoogleAccount)?

    public init() {
        currentAccount = { GIDSignIn.sharedInstance.currentUser }
    }

    /// The seam the sign-out races are tested through.
    init(currentAccount: @escaping @MainActor () -> (any GoogleAccount)?) {
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
        if let cached { return cached }

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

    /// docs/06: called after a 401 so the next [__accessToken] does not hand the rejected token
    /// back. Dropping the cache is the whole of it — `disconnect()` would revoke the grant and
    /// cost the user the consent screen, and `signOut()` would end a session they never left.
    ///
    /// The residual: `refreshTokensIfNeeded` goes to the network only when the token is near
    /// expiry, so a 401 on a token the SDK still believes in produces the same string once more.
    /// The core then parks the step in `NEEDS_AUTH` after its one retry rather than looping.
    public func __invalidate() async throws {
        invalidateNow()
    }

    /// The same, callable from Swift without SKIE's async wrapper — which would abort the process
    /// on a Swift-implemented Kotlin interface.
    public func invalidate() async {
        invalidateNow()
    }

    private func invalidateNow() {
        cached = nil
        generation += 1
    }
}
#endif
