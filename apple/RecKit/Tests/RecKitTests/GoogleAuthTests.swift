// Same condition as the type under test: no watch
// sign-in (ADR-002).
#if os(macOS) || os(iOS)
import AppAuth
import XCTest
@testable import RecKit

/// docs/06 "iOS · macOS", for the two decisions in `GoogleAuth` that do not need the SDK to be
/// standing: what a partial consent leaves missing, and which failures are not failures.
@MainActor
final class GoogleAuthTests: XCTestCase {
    /// Google documents `grantedScopes` as the thing to check before calling the API. The consent
    /// screen lets the user tick profile and Drive separately, so "signed in" and "may upload" are
    /// different questions.
    func testDriveScopeGrantedLeavesNothingMissing() {
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
    func testNoGrantedScopesLeavesDriveMissing() {
        XCTAssertEqual(GoogleAuth.missingScopes(grantedScopes: nil), GoogleAuth.scopes)
    }

    /// Closing the authorization sheet is a cancellation, not an alert (docs/06).
    func testACanceledSignInIsToldApartFromARealFailure() {
        XCTAssertTrue(GoogleAuth.isCanceled(signInError(code: OIDErrorCode.userCanceledAuthorizationFlow.rawValue)))
        XCTAssertFalse(GoogleAuth.isCanceled(signInError(code: OIDErrorCode.networkError.rawValue)))
        XCTAssertFalse(GoogleAuth.isCanceled(URLError(.notConnectedToInternet)))
        // A matching code in another domain is unrelated.
        XCTAssertFalse(GoogleAuth.isCanceled(NSError(domain: "app.recly.other", code: OIDErrorCode.userCanceledAuthorizationFlow.rawValue)))
    }

    private func signInError(code: Int) -> Error {
        NSError(domain: OIDGeneralErrorDomain, code: code)
    }
}
#endif
