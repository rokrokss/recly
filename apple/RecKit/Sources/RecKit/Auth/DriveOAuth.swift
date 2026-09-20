#if os(macOS) || os(iOS)
import AppAuth
import Foundation
import GTMAppAuth
import Security

/// Drive-only OAuth, shared by iPhone and Mac (docs/06). AppAuth owns PKCE, state validation,
/// the code exchange, and refresh coalescing; Recly owns the credential's lifetime and storage.
@MainActor
enum DriveOAuth {
    static let scopes = ["https://www.googleapis.com/auth/drive.file"]
    static let configuration = OIDServiceConfiguration(
        authorizationEndpoint: URL(string: "https://accounts.google.com/o/oauth2/v2/auth")!,
        tokenEndpoint: URL(string: "https://oauth2.googleapis.com/token")!
    )

    static func request(clientID: String) -> OIDAuthorizationRequest {
        let scheme = clientID.split(separator: ".").reversed().joined(separator: ".")
        return OIDAuthorizationRequest(
            configuration: configuration,
            clientId: clientID,
            scopes: scopes,
            redirectURL: URL(string: "\(scheme):/oauth2callback")!,
            responseType: OIDResponseTypeCode,
            nonce: nil,
            additionalParameters: ["access_type": "offline"]
        )
    }

    static func hasDriveAccess(_ state: OIDAuthState) -> Bool {
        let granted = Set((state.scope ?? "").split(separator: " ").map(String.init))
        return Set(scopes).isSubset(of: granted)
    }

    static func validate(_ state: OIDAuthState, clientID: String) throws {
        guard state.lastAuthorizationResponse.request.clientID == clientID else {
            throw GoogleAuth.Failure.invalidCredential
        }
        guard hasDriveAccess(state) else { throw GoogleAuth.Failure.scopesDeclined }
        guard state.isAuthorized, let refresh = state.refreshToken, !refresh.isEmpty else {
            throw GoogleAuth.Failure.invalidCredential
        }
    }

    /// A legacy GoogleSignIn archive contains an ID token and profile metadata. Migrate only the
    /// OAuth tokens, actual granted scopes and expiry; no profile request or permission revocation.
    static func withoutProfile(_ state: OIDAuthState, clientID: String) -> OIDAuthState {
        let request = request(clientID: clientID)
        let authorization = OIDAuthorizationResponse(request: request, parameters: [:])
        let tokenRequest = OIDTokenRequest(
            configuration: configuration, grantType: OIDGrantTypeRefreshToken,
            authorizationCode: nil, redirectURL: nil, clientID: clientID, clientSecret: nil,
            scope: nil, refreshToken: state.refreshToken, codeVerifier: nil, additionalParameters: nil
        )
        var parameters: [String: NSCopying & NSObjectProtocol] = [
            "token_type": "Bearer" as NSString,
            "scope": (state.scope ?? "") as NSString,
            "expires_in": NSNumber(value: max(0, state.lastTokenResponse?.accessTokenExpirationDate?.timeIntervalSinceNow ?? 0)),
        ]
        if let token = state.lastTokenResponse?.accessToken { parameters["access_token"] = token as NSString }
        if let token = state.refreshToken { parameters["refresh_token"] = token as NSString }
        let response = OIDTokenResponse(request: tokenRequest, parameters: parameters)
        let stripped = OIDAuthState(authorizationResponse: authorization, tokenResponse: response)
        if let error = state.authorizationError { stripped.update(withAuthorizationError: error) }
        return stripped
    }

    static func isCanceled(_ error: Error) -> Bool {
        let error = error as NSError
        if error.domain == OIDGeneralErrorDomain {
            return error.code == OIDErrorCode.userCanceledAuthorizationFlow.rawValue
                || error.code == OIDErrorCode.programCanceledAuthorizationFlow.rawValue
        }
        // Denying the requested permission is also a choice, not a failed login.
        return error.domain == OIDOAuthAuthorizationErrorDomain
            && error.code == OIDErrorCodeOAuthAuthorization.accessDenied.rawValue
    }

    /// Keep temporary network/server failures retryable. Only a rejected grant requires consent.
    static func refreshError(_ error: Error) -> Error {
        let ns = error as NSError
        if ns.domain == OIDGeneralErrorDomain,
           ns.code == OIDErrorCode.networkError.rawValue || ns.code == OIDErrorCode.serverError.rawValue {
            return ns.userInfo[NSUnderlyingErrorKey] as? URLError ?? URLError(.badServerResponse)
        }
        return error
    }
}

@MainActor
protocol DriveCredentialStore {
    func load(clientID: String) throws -> OIDAuthState?
    func save(_ state: OIDAuthState) throws
    func clear() throws
}

/// Uses the same platform keychain as the previous SDK. The new archive is independent of
/// GoogleSignIn; its old item is removed only after the migrated state has been saved successfully.
@MainActor
final class KeychainDriveCredentialStore: DriveCredentialStore {
    private let current: KeychainStore
    private let legacy: KeychainStore

    init() {
        #if os(macOS)
        let attributes: Set<KeychainAttribute> = [.useFileBasedKeychain]
        #else
        let attributes: Set<KeychainAttribute> = []
        #endif
        current = KeychainStore(itemName: "app.recly.drive.oauth", keychainAttributes: attributes)
        legacy = KeychainStore(itemName: "auth", keychainAttributes: attributes)
    }

    func load(clientID: String) throws -> OIDAuthState? {
        do {
            let data = try current.keychainHelper.passwordData(forService: current.itemName)
            guard let state = try NSKeyedUnarchiver.unarchivedObject(ofClass: OIDAuthState.self, from: data) else {
                throw GoogleAuth.Failure.invalidCredential
            }
            guard state.lastAuthorizationResponse.request.clientID == clientID else {
                throw GoogleAuth.Failure.invalidCredential
            }
            // A crash between saving the migration and removing the old item is safe to retry.
            try removeLegacy()
            UserDefaults.standard.removeObject(forKey: "app.recly.auth.lastAccount")
            return state
        } catch KeychainStore.Error.passwordNotFound {
            // Only absence allows migration; a denied or unreadable keychain remains an error.
        }
        let old: OIDAuthState
        do {
            old = try legacy.retrieveAuthSession().authState
        } catch KeychainStore.Error.passwordNotFound {
            return nil
        }
        try DriveOAuth.validate(old, clientID: clientID)
        let state = DriveOAuth.withoutProfile(old, clientID: clientID)
        try save(state)
        try removeLegacy()
        UserDefaults.standard.removeObject(forKey: "app.recly.auth.lastAccount")
        return state
    }

    func save(_ state: OIDAuthState) throws {
        // A refresh of an old grant can still return an ID token. Never persist it again.
        let stripped = DriveOAuth.withoutProfile(state, clientID: state.lastAuthorizationResponse.request.clientID)
        let data = try NSKeyedArchiver.archivedData(withRootObject: stripped, requiringSecureCoding: true)
        let query = current.keychainHelper.keychainQuery(forService: current.itemName)
        let status = SecItemUpdate(query as CFDictionary, [kSecValueData: data] as CFDictionary)
        if status == errSecSuccess { return }
        guard status == errSecItemNotFound else { throw KeychainSecureStore.Failure(status: status) }
        var insert = query
        insert[kSecValueData as String] = data
        #if os(iOS)
        insert[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        #endif
        let added = SecItemAdd(insert as CFDictionary, nil)
        guard added == errSecSuccess else { throw KeychainSecureStore.Failure(status: added) }
    }

    func clear() throws {
        // Remove the legacy copy first, so a partial cleanup cannot resurrect it on next launch.
        try removeLegacy()
        do { try current.removeAuthSession() }
        catch KeychainStore.Error.failedToDeletePasswordBecauseItemNotFound { }
        UserDefaults.standard.removeObject(forKey: "app.recly.auth.lastAccount")
    }

    private func removeLegacy() throws {
        do { try legacy.removeAuthSession() }
        catch KeychainStore.Error.failedToDeletePasswordBecauseItemNotFound { }
    }
}

@MainActor
final class DriveOAuthSession {
    private let store: any DriveCredentialStore
    private(set) var account: DriveOAuthAccount?
    private(set) var restoration: GoogleRestoration = .none
    private(set) var generation = 0

    init(store: any DriveCredentialStore) { self.store = store }

    func restore(clientID: String) -> GoogleRestoration {
        generation += 1
        account = nil
        do {
            if let state = try store.load(clientID: clientID) {
                try DriveOAuth.validate(state, clientID: clientID)
                account = DriveOAuthAccount(state: state, owner: self)
                restoration = .restored(hasCredential: true)
            } else {
                restoration = .none
            }
        } catch {
            restoration = .failed(String(describing: error))
        }
        return restoration
    }

    func accept(_ state: OIDAuthState, clientID: String, generation expected: Int) throws {
        guard expected == generation else { throw GoogleAuth.Failure.canceled }
        try DriveOAuth.validate(state, clientID: clientID)
        // Persist before publishing success; failed writes never create an ephemeral connection.
        try store.save(state)
        generation += 1
        account = DriveOAuthAccount(state: state, owner: self)
        restoration = .restored(hasCredential: true)
    }

    func clear() throws {
        generation += 1
        account = nil
        do {
            try store.clear()
            restoration = .none
        } catch {
            restoration = .failed(String(describing: error))
            throw error
        }
    }

    func persist(_ account: DriveOAuthAccount) throws {
        guard self.account === account else { throw GoogleAuth.Failure.canceled }
        try store.save(account.state)
    }
}

@MainActor
final class DriveOAuthAccount: GoogleAccount {
    let state: OIDAuthState
    private weak var owner: DriveOAuthSession?

    init(state: OIDAuthState, owner: DriveOAuthSession) {
        self.state = state
        self.owner = owner
    }

    var tokenExpiry: Date? { state.lastTokenResponse?.accessTokenExpirationDate }
    func invalidateToken() { state.setNeedsTokenRefresh() }

    func freshAccessToken() async throws -> String {
        guard owner?.account === self, DriveOAuth.hasDriveAccess(state) else {
            throw GoogleAuth.Failure.scopesDeclined
        }
        let result: Result<String, Error> = await withCheckedContinuation { continuation in
            state.performAction { token, _, error in
                if let error { continuation.resume(returning: .failure(DriveOAuth.refreshError(error))) }
                else if let token, !token.isEmpty { continuation.resume(returning: .success(token)) }
                else { continuation.resume(returning: .failure(GoogleAuth.Failure.invalidCredential)) }
            }
        }
        guard let owner, owner.account === self else { throw GoogleAuth.Failure.canceled }
        // Includes refresh-token rotation and invalid_grant state. No writes after disconnect.
        try owner.persist(self)
        guard DriveOAuth.hasDriveAccess(state) else { throw GoogleAuth.Failure.scopesDeclined }
        return try result.get()
    }
}

/// Revocation uses a form POST. Never forward the credential to a redirect target.
final class DriveRevokeTransport: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    private let configuration: URLSessionConfiguration

    init(configuration: URLSessionConfiguration = .ephemeral) {
        self.configuration = configuration
    }

    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }

    func revoke(_ token: String) async throws {
        var request = URLRequest(url: URL(string: "https://oauth2.googleapis.com/revoke")!)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        let encoded = token.addingPercentEncoding(withAllowedCharacters: .alphanumerics)!
        request.httpBody = Data("token=\(encoded)".utf8)
        request.timeoutInterval = 30
        let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        if http.statusCode == 200 { return }
        // An already-revoked token has no grant left to remove. Other 400 responses are failures.
        if http.statusCode == 400,
           let body = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           body["error"] as? String == "invalid_token" { return }
        throw URLError(.badServerResponse)
    }
}
#endif
