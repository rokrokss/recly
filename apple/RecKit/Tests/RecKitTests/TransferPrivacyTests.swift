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
            appVersion: "test", platform: .ios, deviceName: "Test iPhone",
            dataDirectory: directory, databaseName: database, secureStore: InMemorySecureStore()
        )
    }

    func testSwitchingKeyFormsProtectsInputAndTheSameKeyKeepsItsDraft() async throws {
        let bridge = try await bridge()
        let model = WorkflowsModel(core: bridge.core)
        addTeardownBlock { await model.stopObserving() }
        await model.reload()
        model.openSecrets(prefill: "first_key")
        model.secretForm?.value = "unsaved test value"
        model.openSecrets(prefill: "first_key")
        XCTAssertNil(model.protection)
        XCTAssertEqual(model.secretForm?.value, "unsaved test value")
        model.openSecrets(prefill: "second_key")
        XCTAssertNotNil(model.protection)
        XCTAssertEqual(model.secretForm?.name, "first_key")
        model.answerProtection(false)
        XCTAssertEqual(model.secretForm?.value, "unsaved test value")
        model.openSecrets(prefill: "second_key")
        model.answerProtection(true)
        XCTAssertEqual(model.secretForm?.name, "second_key")
        XCTAssertEqual(model.secretForm?.value, "")
        model.closeSecrets()
        XCTAssertNil(model.secretForm)
    }

    // A valid exported document, with a key name already present on the receiving device.
    private let imported = """
    {"schema":3,"revision":1,"updatedAt":"2026-09-09T00:00:00.000Z","updatedBy":"test",
     "workflows":[{"id":"00000000000000000000CARRY0","name":"Automation",
     "updatedAt":"2026-09-09T00:00:00.000Z","minDurationSec":0,
     "steps":[{"id":"up","type":"drive.upload"},
              {"id":"stt","type":"transcribe","provider":"openai","secretRef":"existing"},
              {"id":"hook","type":"webhook","url":"https://example.com/rec"}]}]}
    """

    func testImportShowsOnlyNewDestinationsAndPermissionDoesNotTravelInTheFile() async throws {
        let source = try await bridge()
        try await source.core.secrets.put(name: "existing", value: "test-key")
        let file = directory.appendingPathComponent("workflows.json")
        try Data(imported.utf8).write(to: file)
        let transfer = WorkflowTransferModel(core: source.core)
        await transfer.pick(file)
        XCTAssertEqual(transfer.confirm?.transfers.count, 2)
        var granted = try await source.core.transferConsents.approved()
        XCTAssertTrue(granted.isEmpty, "reading a file or having an API key cannot grant permission")
        transfer.cancelImport()
        granted = try await source.core.transferConsents.approved()
        XCTAssertTrue(granted.isEmpty)
        await transfer.pick(file)
        await transfer.confirmImport()
        XCTAssertNil(transfer.confirm)
        XCTAssertFalse(transfer.failed)
        granted = try await source.core.transferConsents.approved()
        XCTAssertEqual(granted.count, 2)

        await transfer.pick(file)
        XCTAssertEqual(transfer.confirm?.transfers.count, 0)
        transfer.cancelImport()
        await transfer.export(to: file)
        let receiver = try await bridge("other-device.db")
        let incoming = WorkflowTransferModel(core: receiver.core)
        await incoming.pick(file)
        XCTAssertEqual(incoming.confirm?.transfers.count, 2)
    }

    func testPrivacySettingsRevokesWithoutDeletingTheSavedKey() async throws {
        let bridge = try await bridge()
        _ = try await bridge.core.workflows.importJson(json: imported)
        let document = try await bridge.core.workflows.current()
        let targets = TransferTargets.shared.forWorkflow(workflow: document.workflows[0])
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

    func testDraftAndKeyDeletionRequireAnExplicitAnswer() async throws {
        let bridge = try await bridge()
        _ = try await bridge.core.workflows.importJson(json: imported)
        let model = WorkflowsModel(core: bridge.core)
        addTeardownBlock { await model.stopObserving() }
        await model.reload()
        let id = try await bridge.core.workflows.current().workflows[0].id
        model.edit(id)
        model.update { $0.name = "Unsaved draft" }
        model.cancel()
        XCTAssertNotNil(model.protection)
        model.answerProtection(false)
        XCTAssertEqual(model.editor?.edit.name, "Unsaved draft")
        model.add()
        XCTAssertEqual(model.editor?.edit.id, id)
        model.answerProtection(true)
        XCTAssertTrue(model.editor?.isNew == true)
        model.cancel()
        XCTAssertNil(model.editor)
        model.openSecrets(prefill: "draft_key")
        model.secretForm?.value = "unsaved key"
        model.add()
        XCTAssertEqual(model.secretForm?.value, "unsaved key")
        model.answerProtection(true)
        XCTAssertNil(model.secretForm)
        XCTAssertTrue(model.editor?.isNew == true)
        try await bridge.core.secrets.put(name: "existing", value: "test-key")
        await model.reload()
        model.askToDeleteSecret("existing")
        model.answerProtection(false)
        let names = try await bridge.core.secrets.names()
        XCTAssertTrue(names.contains("existing"))
    }


    func testEditorRequiresTheExplicitAllowActionAndReusesSavedPermission() async throws {
        let bridge = try await bridge()
        _ = try await bridge.core.workflows.importJson(json: imported)
        let model = WorkflowsModel(core: bridge.core)
        addTeardownBlock { await model.stopObserving() }
        await model.reload()
        let id = try await bridge.core.workflows.current().workflows[0].id
        model.edit(id)
        XCTAssertEqual(model.pendingTransfers.count, 2)
        await model.save()
        var grants = try await bridge.core.transferConsents.approved()
        XCTAssertTrue(grants.isEmpty, "an ordinary save must not silently grant permission")
        await model.reload()
        model.edit(id)
        await model.save(allowing: model.pendingTransfers)
        grants = try await bridge.core.transferConsents.approved()
        XCTAssertEqual(grants.count, 2)
        await model.reload()
        model.edit(id)
        XCTAssertTrue(model.pendingTransfers.isEmpty)
    }

    func testPermissionAlertRoutesToPrivacyAndHasARecognizableBadge() {
        let reason = JobAlerts.reason(status: .needsConsent, lastError: nil)
        XCTAssertEqual(reason, .needsConsent)
        XCTAssertEqual(reason?.fix, .privacy)
        XCTAssertEqual(LedgerStatus.forRecent(state: "Transfer permission needed").code, "NEEDS_CONSENT")
    }

    func testAllowActionGrantsOnlyTheDestinationsShownWhenItWasTapped() async throws {
        let bridge = try await bridge()
        _ = try await bridge.core.workflows.importJson(json: imported)
        let document = try await bridge.core.workflows.current()
        let targets = TransferTargets.shared.forWorkflow(workflow: document.workflows[0])
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
