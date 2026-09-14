import RecKitTestSupport
import ReclyCore
import XCTest
@testable import RecKit

@MainActor
final class TranscriptionRegionTests: XCTestCase {
    private final class Region: AppStoreRegion {
        var code: String?
        init(_ code: String?) { self.code = code }
        func __countryCode() async throws -> String? { code }
    }

    private func bridge(_ region: Region) async throws -> CoreBridge {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        return try await CoreBridge.make(
            appVersion: "test", platform: .ios, deviceName: "Test iPhone",
            dataDirectory: directory, secureStore: InMemorySecureStore(),
            transcriptionPolicy: TranscriptionPolicy(region: region)
        )
    }

    private let imported = """
    {"schema":3,"revision":1,"updatedAt":"2026-09-14T00:00:00.000Z","updatedBy":"test",
     "workflows":[{"id":"00000000000000000000CARRY0","name":"Imported workflow",
     "updatedAt":"2026-09-14T00:00:00.000Z","minDurationSec":0,
     "steps":[{"id":"up","type":"drive.upload"},
              {"id":"stt","type":"transcribe","provider":"openai","secretRef":"test_key"}]}]}
    """

    func testProviderListFollowsRegionChangesAndAnUnknownRegionDoesNotReuseAnAllowance() async throws {
        let region = Region(nil)
        let bridge = try await bridge(region)
        let model = WorkflowsModel(core: bridge.core)
        addTeardownBlock { await model.stopObserving() }
        await model.reload()
        XCTAssertFalse(model.availableProviders.contains("openai"))
        XCTAssertTrue(model.availableProviders.contains("groq"))
        region.code = "USA"
        await model.refreshRegion()
        XCTAssertTrue(model.availableProviders.contains("openai"))
        region.code = "CHN"
        await model.refreshRegion()
        XCTAssertFalse(model.availableProviders.contains("openai"))
        XCTAssertEqual(model.availableProviders.count, 13)
        region.code = "HKG"
        await model.refreshRegion()
        XCTAssertTrue(model.availableProviders.contains("openai"))
        region.code = nil
        await model.refreshRegion()
        XCTAssertFalse(model.availableProviders.contains("openai"))
    }

    func testChinaImportIsRefusedWithoutReplacingWorkflowsOrGrantingPermission() async throws {
        let bridge = try await bridge(Region("CHN"))
        let before = try await bridge.core.workflows.exportJson()
        let file = bridge.dataDirectory.appendingPathComponent("import.json")
        try Data(imported.utf8).write(to: file)
        let transfer = WorkflowTransferModel(core: bridge.core)
        await transfer.pick(file)
        XCTAssertNil(transfer.confirm)
        XCTAssertTrue(transfer.failed)
        XCTAssertEqual(transfer.message, .core("PROVIDER_REGION_RESTRICTED"))
        let after = try await bridge.core.workflows.exportJson()
        XCTAssertEqual(after, before)
        let approved = try await bridge.core.transferConsents.approved()
        XCTAssertTrue(approved.isEmpty)
    }

    func testRegionIsRecheckedWhenAnImportIsConfirmed() async throws {
        let region = Region("USA")
        let bridge = try await bridge(region)
        let file = bridge.dataDirectory.appendingPathComponent("import.json")
        try Data(imported.utf8).write(to: file)
        let transfer = WorkflowTransferModel(core: bridge.core)
        await transfer.pick(file)
        XCTAssertNotNil(transfer.confirm)
        region.code = "CHN"
        await transfer.confirmImport()
        XCTAssertTrue(transfer.failed)
        let approved = try await bridge.core.transferConsents.approved()
        XCTAssertTrue(approved.isEmpty)
    }

    func testAnExistingRestrictedWorkflowCanBeRepairedByChoosingAnotherProvider() async throws {
        let region = Region("CHN")
        let bridge = try await bridge(region)
        // Definitions are portable data. Runtime policy applies even to a restored database.
        _ = try await bridge.core.workflows.importJson(json: imported)
        let model = WorkflowsModel(core: bridge.core)
        addTeardownBlock { await model.stopObserving() }
        await model.reload()
        model.edit("00000000000000000000CARRY0")
        await model.save()
        XCTAssertEqual(model.message, .core("PROVIDER_REGION_RESTRICTED"))
        XCTAssertNotNil(model.editor)
        model.updateStep(at: 1) { step in
            guard case .transcribe(var edit) = step else { return }
            edit.provider = "groq"
            step = .transcribe(edit)
        }
        await model.save(allowing: model.pendingTransfers)
        XCTAssertNil(model.editor)
    }
}
