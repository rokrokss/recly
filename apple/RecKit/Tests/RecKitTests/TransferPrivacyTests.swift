import RecKitTestSupport
import ReclyCore
import XCTest
@testable import RecKit

@MainActor
final class TransferPrivacyTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    private func bridge(_ database: String = "consent.db") async throws -> CoreBridge {
        try await CoreBridge.make(
            platform: .ios, deviceName: "Test iPhone",
            dataDirectory: directory, databaseName: database, secureStore: InMemorySecureStore(),
            transcriptionPolicy: TranscriptionPolicy(region: nil)
        )
    }

    /// Two transcription destinations, as the fixed plan's `transcribe` step names them.
    private func transcribeTargets() -> [TransferTarget] {
        ["openai", "groq"].compactMap { provider in
            TransferTargets.shared.forStep(step: Step.Transcribe(
                id: "transcribe",
                onError: .abort,
                retry: Retry(maxAttempts: 5, initialDelaySec: 30, maxDelaySec: 3600),
                provider: provider,
                secretRef: "existing",
                invokeUrl: nil,
                language: .auto,
                diarize: false,
                speakers: Speakers(min: 1, max: 8),
                model: nil
            ))
        }
    }

    func testPrivacySettingsRevokesWithoutDeletingTheSavedKey() async throws {
        let bridge = try await bridge()
        let targets = transcribeTargets()
        XCTAssertEqual(targets.count, 2)
        try await bridge.core.secrets.put(name: "existing", value: "test-key")
        try await bridge.core.transferConsents.grant(targets: targets)
        let privacy = TransferPrivacyModel(core: bridge.core)
        addTeardownBlock { await privacy.stopObserving() }
        await privacy.reload()
        XCTAssertEqual(privacy.approved.count, 2)
        await privacy.revoke(targets[0])
        let missing = try await bridge.core.transferConsents.missing(targets: targets)
        XCTAssertEqual(missing.map(\.id), [targets[0].id])
        let names = try await bridge.core.secrets.names()
        XCTAssertTrue(names.contains("existing"))
    }

    func testPermissionAlertRoutesToPrivacyAndHasARecognizableBadge() {
        let reason = JobAlerts.reason(status: .needsConsent, lastError: nil)
        XCTAssertEqual(reason, .needsConsent)
        XCTAssertEqual(reason?.fix, .privacy)
        XCTAssertEqual(LedgerStatus.forRecent(state: "Transfer permission needed").code, "NEEDS_CONSENT")
    }

    func testAllowActionGrantsOnlyTheDestinationsShownWhenItWasTapped() async throws {
        let bridge = try await bridge()
        let targets = transcribeTargets()
        XCTAssertEqual(targets.count, 2)
        let privacy = TransferPrivacyModel(core: bridge.core)
        addTeardownBlock { await privacy.stopObserving() }
        await privacy.allowPending([targets[0]])
        XCTAssertNil(privacy.message)
        let approved = try await bridge.core.transferConsents.approved()
        XCTAssertEqual(approved.map(\.id), [targets[0].id])
        let missing = try await bridge.core.transferConsents.missing(targets: targets)
        XCTAssertEqual(missing.map(\.id), [targets[1].id])
    }
}
