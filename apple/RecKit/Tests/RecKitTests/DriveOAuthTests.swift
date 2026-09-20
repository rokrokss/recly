#if os(macOS) || os(iOS)
import AppAuth
import CryptoKit
import ReclyCore
import XCTest
@testable import RecKit

@MainActor
final class DriveOAuthTests: XCTestCase {
    private let clientID = "recly-test.apps.googleusercontent.com"

    func testAuthorizationRequestsOnlyDriveWithFreshStateAndS256PKCE() throws {
        let first = DriveOAuth.request(clientID: clientID)
        let second = DriveOAuth.request(clientID: clientID)
        let values = Dictionary(uniqueKeysWithValues: URLComponents(
            url: first.authorizationRequestURL(), resolvingAgainstBaseURL: false
        )!.queryItems!.map { ($0.name, $0.value ?? "") })
        XCTAssertEqual(values["scope"], "https://www.googleapis.com/auth/drive.file")
        XCTAssertEqual(values["response_type"], "code")
        XCTAssertEqual(values["redirect_uri"], "com.googleusercontent.apps.recly-test:/oauth2callback")
        XCTAssertEqual(values["code_challenge_method"], "S256")
        XCTAssertEqual(values["access_type"], "offline")
        XCTAssertNil(values["nonce"])
        XCTAssertNil(values["login_hint"])
        XCTAssertNil(values["include_granted_scopes"])
        XCTAssertNotEqual(first.state, second.state)
        XCTAssertNotEqual(first.codeVerifier, second.codeVerifier)
        let verifier = try XCTUnwrap(first.codeVerifier)
        XCTAssertGreaterThanOrEqual(verifier.count, 43)
        let hash = Data(SHA256.hash(data: Data(verifier.utf8))).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        XCTAssertEqual(values["code_challenge"], hash)
    }

    func testProfileOnlyOrMissingRefreshTokenNeverBecomesConnected() throws {
        let store = MemoryDriveStore()
        let session = DriveOAuthSession(store: store)
        for state in [fixture(scopes: "openid email profile"), fixture(refresh: nil), fixture(client: "another-client")] {
            XCTAssertThrowsError(try session.accept(state, clientID: clientID, generation: session.generation))
            XCTAssertNil(session.account)
            XCTAssertEqual(store.writes, 0)
        }
    }

    func testSavingMustSucceedBeforeConnectionIsPublished() throws {
        let store = MemoryDriveStore()
        store.failWrite = true
        let session = DriveOAuthSession(store: store)
        XCTAssertThrowsError(try session.accept(fixture(), clientID: clientID, generation: session.generation))
        XCTAssertNil(session.account)
        XCTAssertEqual(session.restoration, .none)
    }

    func testRestoreDistinguishesNoCredentialFromUnreadableKeychain() {
        let store = MemoryDriveStore()
        let session = DriveOAuthSession(store: store)
        XCTAssertEqual(session.restore(clientID: clientID), .none)
        store.failRead = true
        guard case .failed = session.restore(clientID: clientID) else { return XCTFail("read failure was lost") }
        XCTAssertNil(session.account)
    }

    func testExistingGrantRestoresWithoutProfileAndWithoutInteractiveConsent() throws {
        let old = fixture(scopes: "openid email profile https://www.googleapis.com/auth/drive.file", idToken: "legacy-id-token")
        let migrated = DriveOAuth.withoutProfile(old, clientID: clientID)
        XCTAssertEqual(migrated.refreshToken, "refresh-old")
        XCTAssertEqual(migrated.lastTokenResponse?.accessToken, "access-old")
        XCTAssertNil(migrated.lastTokenResponse?.idToken)
        XCTAssertNil(migrated.lastAuthorizationResponse.idToken)
        XCTAssertEqual(migrated.scope, old.scope, "retain the actual grant, without claiming old permissions were revoked")
        XCTAssertEqual(migrated.lastAuthorizationResponse.request.scope, DriveOAuth.scopes.joined(separator: " "))
        let data = try NSKeyedArchiver.archivedData(withRootObject: migrated, requiringSecureCoding: true)
        let decoded = try XCTUnwrap(NSKeyedUnarchiver.unarchivedObject(ofClass: OIDAuthState.self, from: data))
        let store = MemoryDriveStore()
        store.state = decoded
        let session = DriveOAuthSession(store: store)
        XCTAssertEqual(session.restore(clientID: clientID), .restored(hasCredential: true))
        XCTAssertEqual(session.account?.state.refreshToken, "refresh-old")
    }

    func testDisconnectPreventsLateAuthorizationOrRefreshFromRestoringCredentials() throws {
        let store = MemoryDriveStore()
        let session = DriveOAuthSession(store: store)
        try session.accept(fixture(), clientID: clientID, generation: session.generation)
        let old = try XCTUnwrap(session.account)
        let generation = session.generation
        try session.clear()
        XCTAssertThrowsError(try session.accept(fixture(), clientID: clientID, generation: generation))
        XCTAssertThrowsError(try session.persist(old))
        XCTAssertNil(store.state)
        XCTAssertNil(session.account)
    }

    func testFailedCredentialDeletionIsReportedAndCanBeRetried() throws {
        let store = MemoryDriveStore()
        let session = DriveOAuthSession(store: store)
        try session.accept(fixture(), clientID: clientID, generation: session.generation)
        store.failClear = true
        XCTAssertThrowsError(try session.clear())
        XCTAssertNil(session.account, "never keep using a connection that is being removed")
        guard case .failed = session.restoration else { return XCTFail("cleanup failure was lost") }
        store.failClear = false
        try session.clear()
        XCTAssertNil(store.state)
        XCTAssertEqual(session.restoration, .none)
    }

    func testFreshAccessTokenUsesAppAuthCacheAndPersistsBeforeReturning() async throws {
        let store = MemoryDriveStore()
        let session = DriveOAuthSession(store: store)
        try session.accept(fixture(), clientID: clientID, generation: session.generation)
        let token = try await XCTUnwrap(session.account).freshAccessToken()
        XCTAssertEqual(token, "access-old")
        XCTAssertEqual(store.writes, 2)
    }

    func testDecliningConsentIsTreatedAsCancellation() {
        XCTAssertTrue(GoogleAuth.isCanceled(NSError(
            domain: OIDOAuthAuthorizationErrorDomain,
            code: OIDErrorCodeOAuthAuthorization.accessDenied.rawValue
        )))
        XCTAssertFalse(GoogleAuth.isCanceled(NSError(domain: OIDGeneralErrorDomain, code: OIDErrorCode.networkError.rawValue)))
    }

    func testRefreshRotatesAndPersistsTokensWithoutLosingTheGrantedScope() async throws {
        try await withTransport { configuration in
            OAuthURLProtocol.respond = { stub in
                XCTAssertEqual(stub.request.url?.absoluteString, "https://oauth2.googleapis.com/token")
                XCTAssertEqual(stub.request.httpMethod, "POST")
                let body = stub.body()
                XCTAssertTrue(body.contains("grant_type=refresh_token"))
                XCTAssertTrue(body.contains("refresh_token=refresh-old"))
                stub.finish(status: 200, json: #"{"access_token":"access-new","refresh_token":"refresh-new","expires_in":3600,"token_type":"Bearer"}"#)
            }
            let store = MemoryDriveStore()
            let session = DriveOAuthSession(store: store)
            try session.accept(fixture(), clientID: clientID, generation: session.generation)
            let account = try XCTUnwrap(session.account)
            account.invalidateToken()
            let token = try await account.freshAccessToken()
            XCTAssertEqual(token, "access-new")
            XCTAssertEqual(store.state?.refreshToken, "refresh-new")
            XCTAssertTrue(DriveOAuth.hasDriveAccess(account.state))
            XCTAssertEqual(store.writes, 2)
        }
    }

    func testOfflineRefreshKeepsTheCredentialAndRemainsRetryable() async throws {
        try await withTransport { _ in
            OAuthURLProtocol.respond = { stub in
                stub.client?.urlProtocol(stub, didFailWithError: URLError(.notConnectedToInternet))
            }
            let store = MemoryDriveStore()
            let session = DriveOAuthSession(store: store)
            try session.accept(fixture(), clientID: clientID, generation: session.generation)
            let account = try XCTUnwrap(session.account)
            account.invalidateToken()
            do {
                _ = try await account.freshAccessToken()
                XCTFail("offline refresh succeeded")
            } catch let error as URLError {
                XCTAssertEqual(error.code, .notConnectedToInternet)
            }
            XCTAssertTrue(account.state.isAuthorized)
            XCTAssertEqual(store.state?.refreshToken, "refresh-old")
        }
    }

    func testRejectedRefreshStaysInvalidAfterRemovingProfileAndRestoring() async throws {
        try await withTransport { _ in
            OAuthURLProtocol.respond = { $0.finish(status: 400, json: #"{"error":"invalid_grant"}"#) }
            let store = MemoryDriveStore()
            let session = DriveOAuthSession(store: store)
            try session.accept(fixture(), clientID: clientID, generation: session.generation)
            let account = try XCTUnwrap(session.account)
            account.invalidateToken()
            do {
                _ = try await account.freshAccessToken()
                XCTFail("rejected grant succeeded")
            } catch {
                XCTAssertEqual((error as NSError).domain, OIDOAuthTokenErrorDomain)
            }
            let stripped = DriveOAuth.withoutProfile(account.state, clientID: clientID)
            XCTAssertFalse(stripped.isAuthorized)
            XCTAssertNotNil(stripped.authorizationError)
            let data = try NSKeyedArchiver.archivedData(withRootObject: stripped, requiringSecureCoding: true)
            store.state = try NSKeyedUnarchiver.unarchivedObject(ofClass: OIDAuthState.self, from: data)
            guard case .failed = session.restore(clientID: clientID) else { return XCTFail("rejected grant was restored") }
        }
    }

    func testRefreshFinishingAfterDisconnectCannotWriteCredentialsBack() async throws {
        try await withTransport { _ in
            let store = MemoryDriveStore()
            let session = DriveOAuthSession(store: store)
            try session.accept(fixture(), clientID: clientID, generation: session.generation)
            let account = try XCTUnwrap(session.account)
            account.invalidateToken()
            OAuthURLProtocol.respond = { stub in
                Task { @MainActor in
                    do { try session.clear() } catch { XCTFail("cleanup failed: \(error)") }
                    stub.finish(status: 200, json: #"{"access_token":"late-token","refresh_token":"late-refresh","expires_in":3600,"token_type":"Bearer"}"#)
                }
            }
            do {
                _ = try await account.freshAccessToken()
                XCTFail("late refresh was accepted")
            } catch { XCTAssertTrue(error is GoogleAuth.Failure) }
            XCTAssertEqual(store.writes, 1)
            XCTAssertNil(store.state)
            XCTAssertNil(session.account)
        }
    }

    func testRevokeEncodesTheTokenInTheBodyAndAcceptsOnlyRevokedResponses() async throws {
        try await withTransport { configuration in
            let transport = DriveRevokeTransport(configuration: configuration)
            OAuthURLProtocol.respond = { stub in
                XCTAssertEqual(stub.request.url?.absoluteString, "https://oauth2.googleapis.com/revoke")
                XCTAssertEqual(stub.request.httpMethod, "POST")
                XCTAssertEqual(stub.request.value(forHTTPHeaderField: "Content-Type"), "application/x-www-form-urlencoded")
                XCTAssertEqual(stub.body(), "token=a%2Bb%2Fc%3D%26d")
                stub.finish(status: 200, json: "{}")
            }
            try await transport.revoke("a+b/c=&d")
            OAuthURLProtocol.respond = { $0.finish(status: 400, json: #"{"error":"invalid_token"}"#) }
            try await transport.revoke("already-revoked")
            for status in [302, 400, 401, 500] {
                OAuthURLProtocol.respond = { $0.finish(status: status, json: #"{"error":"server_error"}"#) }
                do {
                    try await transport.revoke("still-owed")
                    XCTFail("status \(status) was treated as successful revocation")
                } catch let error as URLError { XCTAssertEqual(error.code, .badServerResponse) }
            }
            let request = URLRequest(url: URL(string: "https://untrusted.example/revoke")!)
            let response = HTTPURLResponse(url: request.url!, statusCode: 302, httpVersion: nil, headerFields: nil)!
            transport.urlSession(.shared, task: URLSession.shared.dataTask(with: request),
                                 willPerformHTTPRedirection: response, newRequest: request) { redirected in
                XCTAssertNil(redirected, "never forward a credential to a redirect destination")
            }
        }
    }

    private func withTransport(_ body: (URLSessionConfiguration) async throws -> Void) async throws {
        let original = OIDURLSessionProvider.session()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [OAuthURLProtocol.self]
        let session = URLSession(configuration: configuration)
        OIDURLSessionProvider.setSession(session)
        defer {
            OIDURLSessionProvider.setSession(original)
            session.invalidateAndCancel()
            OAuthURLProtocol.respond = nil
        }
        try await body(configuration)
    }

    private func fixture(scopes: String = "https://www.googleapis.com/auth/drive.file",
                         refresh: String? = "refresh-old", client: String? = nil,
                         idToken: String? = nil) -> OIDAuthState {
        let request = DriveOAuth.request(clientID: client ?? clientID)
        let authorization = OIDAuthorizationResponse(request: request, parameters: ["code": "test-code" as NSString])
        var values: [String: NSCopying & NSObjectProtocol] = [
            "access_token": "access-old" as NSString, "token_type": "Bearer" as NSString,
            "expires_in": 3600 as NSNumber, "scope": scopes as NSString,
        ]
        if let refresh { values["refresh_token"] = refresh as NSString }
        if let idToken { values["id_token"] = idToken as NSString }
        let response = OIDTokenResponse(request: authorization.tokenExchangeRequest()!, parameters: values)
        return OIDAuthState(authorizationResponse: authorization, tokenResponse: response)
    }
}

@MainActor
private final class MemoryDriveStore: DriveCredentialStore {
    var state: OIDAuthState?
    var failRead = false
    var failWrite = false
    var failClear = false
    var writes = 0
    func load(clientID: String) throws -> OIDAuthState? {
        if failRead { throw URLError(.cannotOpenFile) }
        return state
    }
    func save(_ state: OIDAuthState) throws {
        if failWrite { throw URLError(.cannotWriteToFile) }
        writes += 1
        self.state = state
    }
    func clear() throws {
        if failClear { throw URLError(.cannotRemoveFile) }
        state = nil
    }
}

/// Exercise AppAuth's actual request/refresh path without contacting Google or touching Keychain.
private final class OAuthURLProtocol: URLProtocol, @unchecked Sendable {
    private static let lock = NSLock()
    private static var handler: ((OAuthURLProtocol) -> Void)?
    static var respond: ((OAuthURLProtocol) -> Void)? {
        get { lock.withLock { handler } }
        set { lock.withLock { handler = newValue } }
    }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        guard let handler = Self.respond else {
            client?.urlProtocol(self, didFailWithError: URLError(.unsupportedURL))
            return
        }
        handler(self)
    }
    override func stopLoading() { }
    func finish(status: Int, json: String) {
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1",
                                      headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(json.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    func body() -> String {
        if let data = request.httpBody { return String(decoding: data, as: UTF8.self) }
        guard let stream = request.httpBodyStream else { return "" }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 1024)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            if count <= 0 { break }
            data.append(contentsOf: buffer.prefix(count))
        }
        return String(decoding: data, as: UTF8.self)
    }
}
#endif
