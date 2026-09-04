// Same condition as the type under test: there is no GoogleSignIn slice for watchOS and no watch
// sign-in (ADR-002).
#if os(macOS) || os(iOS)
import XCTest
@testable import RecKit

/// docs/06 "iOS · macOS", for the two decisions in `GoogleAuth` that do not need the SDK to be
/// standing: what a partial consent leaves missing, and which failures are not failures.
@MainActor
final class GoogleAuthTests: XCTestCase {
    /// Google documents `grantedScopes` as the thing to check before calling the API. The consent
    /// screen lets the user tick the two Drive boxes apart, so "signed in" and "may upload" are
    /// different questions.
    func testBothDriveScopesGrantedLeavesNothingMissing() {
        XCTAssertEqual(GoogleAuth.missingScopes(grantedScopes: GoogleAuth.scopes + ["openid"]), [])
    }

    func testAWithheldScopeIsNamed() {
        let granted = ["email"]

        XCTAssertEqual(
            GoogleAuth.missingScopes(grantedScopes: granted),
            ["https://www.googleapis.com/auth/drive.file"]
        )
    }

    /// `grantedScopes` is nullable, and a nil is every scope missing rather than none.
    func testNoGrantedScopesAtAllIsBothMissing() {
        XCTAssertEqual(GoogleAuth.missingScopes(grantedScopes: nil), GoogleAuth.scopes)
    }

    /// `GIDSignInError.canceled` is the user closing the sheet — the shells must not turn it into
    /// an alert (docs/06). The literals are `kGIDSignInErrorDomain` and `kGIDSignInErrorCodeCanceled`
    /// (-5) / `kGIDSignInErrorCodeKeychain` (-2); the test target does not link the SDK itself.
    func testACanceledSignInIsToldApartFromARealFailure() {
        XCTAssertTrue(GoogleAuth.isCanceled(signInError(code: -5)))
        XCTAssertFalse(GoogleAuth.isCanceled(signInError(code: -2)))
        XCTAssertFalse(GoogleAuth.isCanceled(URLError(.notConnectedToInternet)))
        // Same code, somebody else's domain: -5 means nothing outside GoogleSignIn's.
        XCTAssertFalse(GoogleAuth.isCanceled(NSError(domain: "app.recly.other", code: -5)))
    }

    private func signInError(code: Int) -> Error {
        NSError(domain: "com.google.GIDSignIn", code: code)
    }
}
#endif
