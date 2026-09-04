// `GoogleAuth` — and with it `GoogleAccount` and `AppleTokenProvider` — is the Mac's and the
// phone's (M5-L3); there is no watchOS slice of GoogleSignIn and no watch sign-in (ADR-002), so
// what does not exist there is not compiled there.
#if os(macOS) || os(iOS)
import ReclyCore
import XCTest
@testable import RecKit

/// docs/06 on the Apple side, and the one thing about it that is not a straight line: a refresh is
/// a network round trip, and the user can sign out — or a 401 can arrive — while it is suspended.
/// A token handed to the core after that belongs to a sign-in state nobody is in any more.
@MainActor
final class AppleTokenProviderTests: XCTestCase {
    /// Two steps of one job must not walk the SDK twice; that is what the cache is for.
    func testASecondStepOfTheSameJobReusesTheCachedToken() async throws {
        let account = FakeAccount(token: "ya29.first")
        let provider = AppleTokenProvider(currentAccount: { account })

        let first = try await provider.__accessToken()
        let second = try await provider.__accessToken()

        XCTAssertEqual(first, "ya29.first")
        XCTAssertEqual(second, "ya29.first")
        XCTAssertEqual(account.refreshes, 1, "the cache is what keeps a multi-step job off the SDK")
    }

    /// The 401 path. `invalidate()` lands while the refresh it raced is still suspended; the token
    /// that comes back is the one the server just rejected, so it must not become the cache.
    func testAnInvalidateDuringARefreshDoesNotRepopulateTheCache() async throws {
        let account = FakeAccount(token: "ya29.rejected")
        let provider = AppleTokenProvider(currentAccount: { account })
        account.whileRefreshing = { await provider.invalidate() }

        await assertAuthRequired { try await provider.__accessToken() }

        // The next call has to go back to the SDK: had the stale token been cached, this would
        // hand it straight back and the upload would keep 401-ing under a dead token.
        account.whileRefreshing = nil
        account.token = "ya29.fresh"
        let next = try await provider.__accessToken()
        XCTAssertEqual(next, "ya29.fresh")
        XCTAssertEqual(account.refreshes, 2)
    }

    /// The sign-out path: the account the refresh was started for is gone by the time it answers.
    func testASignOutDuringARefreshIsNotServedTheOldAccountsToken() async throws {
        let account = FakeAccount(token: "ya29.signedOut")
        var signedIn: FakeAccount? = account
        let provider = AppleTokenProvider(currentAccount: { signedIn })
        account.whileRefreshing = { signedIn = nil }

        await assertAuthRequired { try await provider.__accessToken() }

        // And nothing was kept: a later call with nobody signed in is still refused.
        await assertAuthRequired { try await provider.__accessToken() }
    }

    /// Signing in as somebody else while a refresh is in flight. The generation moves too (the
    /// shell invalidates on sign-in), but identity alone has to be enough — this asserts the
    /// `===` half by leaving the generation untouched.
    func testASignInAsAnotherAccountDuringARefreshIsRefused() async throws {
        let first = FakeAccount(token: "ya29.first")
        let second = FakeAccount(token: "ya29.second")
        var signedIn: any GoogleAccount = first
        let provider = AppleTokenProvider(currentAccount: { signedIn })
        first.whileRefreshing = { signedIn = second }

        await assertAuthRequired { try await provider.__accessToken() }

        XCTAssertEqual(second.refreshes, 0, "the other account's token was never asked for either")
    }

    /// The fast path is guarded too: a cache filled while signed in is worthless once the user has
    /// signed out, and serving it would upload under an account that has left.
    func testASignedOutProviderNeverServesItsCache() async throws {
        let account = FakeAccount(token: "ya29.cached")
        var signedIn: FakeAccount? = account
        let provider = AppleTokenProvider(currentAccount: { signedIn })

        let cached = try await provider.__accessToken()
        XCTAssertEqual(cached, "ya29.cached")
        signedIn = nil

        await assertAuthRequired { try await provider.__accessToken() }
    }

    /// docs/06: only a grant that needs the user parks a job. Offline keeps the core's retries, so
    /// the error has to reach it as itself rather than as `AuthRequiredException`.
    func testOfflineIsNotSignInAgain() async {
        let account = FakeAccount(token: "unused")
        account.failWith = URLError(.notConnectedToInternet)
        let provider = AppleTokenProvider(currentAccount: { account })

        do {
            _ = try await provider.__accessToken()
            XCTFail("expected the URLError to come through")
        } catch let error as URLError {
            XCTAssertEqual(error.code, .notConnectedToInternet)
        } catch {
            XCTFail("expected a URLError, got \(error)")
        }
    }

    /// The other half of that line: a refresh the SDK refuses because the Keychain has nothing left
    /// to refresh with (`GIDSignInError.hasNoAuthInKeychain`, -4 — "the user has not signed in
    /// before or … have since signed out") is exactly the case NEEDS_AUTH is for.
    func testAKeychainWithNoAuthLeftParksTheJob() async {
        let account = FakeAccount(token: "unused")
        account.failWith = NSError(domain: "com.google.GIDSignIn", code: -4)
        let provider = AppleTokenProvider(currentAccount: { account })

        await assertAuthRequired { try await provider.__accessToken() }
    }

    /// `AuthRequiredException` and not some other error, or `Executor` takes the `catch (Throwable)`
    /// branch and burns retries instead of parking in `NEEDS_AUTH` (docs/05).
    private func assertAuthRequired(
        file: StaticString = #filePath,
        line: UInt = #line,
        _ body: () async throws -> String
    ) async {
        do {
            let token = try await body()
            XCTFail("expected a refusal, got \(token)", file: file, line: line)
        } catch {
            XCTAssertTrue(
                (error as NSError).userInfo["KotlinException"] is AuthRequiredException,
                "expected AuthRequiredException, got \(error)",
                file: file,
                line: line
            )
        }
    }
}

/// A signed-in account whose refresh can be held open — the only way to sign out underneath one.
@MainActor
private final class FakeAccount: GoogleAccount {
    var token: String
    var failWith: Error?
    /// Runs while the refresh is suspended: the window a 401, a sign-out or another sign-in lands in.
    var whileRefreshing: (@MainActor () async -> Void)?
    private(set) var refreshes = 0

    init(token: String) {
        self.token = token
    }

    func freshAccessToken() async throws -> String {
        refreshes += 1
        if let failWith { throw failWith }
        // A real refresh suspends here, and this is that suspension: whatever the hook does — a
        // 401's invalidate, a sign-out — has landed by the time the provider looks again.
        await whileRefreshing?()
        return token
    }
}
#endif
