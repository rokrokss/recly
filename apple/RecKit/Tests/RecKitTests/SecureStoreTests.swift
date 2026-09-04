import RecKitTestSupport
import ReclyCore
import XCTest
@testable import RecKit

final class SecureStoreTests: XCTestCase {
    func testPutGetDeleteRoundTrip() async throws {
        let store = InMemorySecureStore()
        let secret = Data("hmac-key".utf8)

        try await store.put(ns: SecureStoreCompanion.shared.SECRETS, key: "webhook", value: secret.kotlinByteArray)
        let read = try await store.get(ns: SecureStoreCompanion.shared.SECRETS, key: "webhook")
        XCTAssertEqual(read?.data, secret)

        try await store.delete(ns: SecureStoreCompanion.shared.SECRETS, key: "webhook")
        let gone = try await store.get(ns: SecureStoreCompanion.shared.SECRETS, key: "webhook")
        XCTAssertNil(gone)
    }

    func testMissingKeyIsNilNotAnError() async throws {
        let store = InMemorySecureStore()
        let read = try await store.get(ns: SecureStoreCompanion.shared.TOKENS, key: "absent")
        XCTAssertNil(read)
    }

    /// The namespaces are what keeps two stores of the same key apart (docs/01).
    func testNamespacesDoNotCollide() async throws {
        let store = InMemorySecureStore()
        try await store.put(ns: SecureStoreCompanion.shared.SECRETS, key: "k", value: Data("a".utf8).kotlinByteArray)
        try await store.put(ns: SecureStoreCompanion.shared.TOKENS, key: "k", value: Data("b".utf8).kotlinByteArray)

        let secrets = try await store.get(ns: SecureStoreCompanion.shared.SECRETS, key: "k")
        let tokens = try await store.get(ns: SecureStoreCompanion.shared.TOKENS, key: "k")
        XCTAssertEqual(secrets?.data, Data("a".utf8))
        XCTAssertEqual(tokens?.data, Data("b".utf8))
    }

    /// Bytes are not text: the conversion has to survive values that are not valid UTF-8.
    func testArbitraryBytesSurviveTheKotlinBoundary() async throws {
        let store = InMemorySecureStore()
        let raw = Data([0x00, 0x7F, 0x80, 0xFF, 0x01])

        try await store.put(ns: "device", key: "raw", value: raw.kotlinByteArray)
        let read = try await store.get(ns: "device", key: "raw")
        XCTAssertEqual(read?.data, raw)
    }
}
