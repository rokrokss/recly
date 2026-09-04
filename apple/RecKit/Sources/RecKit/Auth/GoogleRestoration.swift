import Foundation

/// docs/06: what a launch's restore of the Google sign-in actually found.
///
/// The regression it exists for: `GoogleAuth.restore()` swallowed every failure, and the shells
/// then read "signed in" off the account *email* — which a valid user need not carry at all, and
/// which a restore that never came back leaves nil as well. A disconnect read "nobody is signed
/// in" out of two states that mean opposite things: one where there is no grant left to take away,
/// and one where it is simply not known. Only the first may skip the revoke.
///
/// It is a plain enum in RecKit rather than a type of the SDK's, so the rule that reads it
/// ([DisconnectGuard.revokeDecision]) is testable without a Keychain and compiles on every platform
/// RecKit ships to.
public enum GoogleRestoration: Equatable, Sendable {
    /// The SDK answered. [hasCredential] is whether it handed a signed-in user back, which is what
    /// "this device still holds a grant" means — never the email.
    case restored(hasCredential: Bool)
    /// Nothing to restore: no usable client id, or the SDK has no previous sign-in on this device.
    case none
    /// The restore itself failed, so whether this device still holds a credential is not known.
    /// Not the same as [none], and a disconnect must not read it as one.
    case failed(String)
}
