import Foundation
import ReclyCore

/// The provider a shell without sign-in gets: every token request parks the job in `NEEDS_AUTH`
/// instead of burning retries — which is exactly what the core does with `AuthRequiredException`.
/// The Mac (M4-L4) and the phone (M5-L3) hand `CoreBridge.make` an `AppleTokenProvider` instead.
/// What is left on this one is the watch, which never touches Drive (ADR-002), and any shell that
/// opens the core before it has a sign-in.
///
/// `asError()` keeps the Kotlin exception itself inside the `NSError`, so the core's
/// `catch (e: AuthRequiredException)` still matches; a plain `NSError` would arrive as a generic
/// failure and the job would retry.
///
/// The `__`-prefixed names are the raw Kotlin members: SKIE hides them behind the `async` wrappers
/// that callers use, but a Swift *implementation* of the interface still fills in the originals.
public final class StubTokenProvider: ReclyCore.TokenProvider {
    public init() {}

    public func __accessToken() async throws -> String {
        // docs/07 §5: a key, not a sentence.
        throw AuthRequiredException(message: CoreMessage.needsAuth.code(arg: nil, detail: nil)).asError()
    }

    public func __invalidate() async throws {}
}
