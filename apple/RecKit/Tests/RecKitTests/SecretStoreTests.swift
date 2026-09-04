import XCTest
@testable import RecKit

/// docs/05 "시크릿": every write goes through the core's `SecretsRepository`, and a store that
/// refuses — a locked Keychain, a missing entitlement — throws rather than answering. Nothing above
/// here would notice a swallowed refusal on its own: a `try await` that does not throw reads as a
/// value that was stored, and the editor above keeps its form open on the throw.
final class SecretStoreTests: XCTestCase {

    func testAPutAndADeleteReachTheStore() async throws {
        let secrets = FakeSecrets()
        let store = SecretStore(secrets: secrets)

        try await store.put(name: "clova_key", value: "sk-a")
        try await store.delete(name: "clova_key")

        XCTAssertEqual(secrets.puts, ["clova_key"])
        XCTAssertEqual(secrets.deletes, ["clova_key"])
    }

    /// The refusal travels rather than being read as a save — the one thing the editor's "Could not
    /// save the key" line depends on.
    func testAStoreThatRefusesThrowsRatherThanReportingASave() async {
        let store = SecretStore(secrets: FakeSecrets(refuses: true))

        do {
            try await store.put(name: "clova_key", value: "sk-a")
            XCTFail("a refused put must not read as a stored one")
        } catch {
            XCTAssertTrue(error is SecretsRefused)
        }
    }
}

/// The Keychain's refusal, as this test produces it.
private struct SecretsRefused: Error {}

/// `SecretsRepository` with its answer under the test's control.
private final class FakeSecrets: RecKit.Secrets {
    private let refuses: Bool
    private(set) var puts: [String] = []
    private(set) var deletes: [String] = []

    init(refuses: Bool = false) {
        self.refuses = refuses
    }

    func names() async throws -> [String] {
        puts
    }

    func put(name: String, value: String) async throws {
        if refuses { throw SecretsRefused() }
        puts.append(name)
    }

    func delete(name: String) async throws {
        if refuses { throw SecretsRefused() }
        deletes.append(name)
    }
}
