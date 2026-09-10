import RecKitTestSupport
import ReclyCore
import XCTest
@testable import RecKit

@MainActor
final class WorkflowDeletionTests: XCTestCase {
    func testDeletionChecksTheLiveSelectionAndAllowsDeletionAfterSwitching() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let bridge = try await CoreBridge.make(
            appVersion: "test", platform: .ios, deviceName: "Test iPhone",
            dataDirectory: directory, databaseName: "workflow-delete.db", secureStore: InMemorySecureStore()
        )
        let core = bridge.core
        let model = WorkflowsModel(core: core)
        addTeardownBlock {
            await model.stopObserving()
            try? FileManager.default.removeItem(at: directory)
        }
        await model.reload()
        let original = try XCTUnwrap(model.items.first)
        model.add()
        model.update { $0.name = "Another workflow" }
        await model.save()
        let other = try XCTUnwrap(model.items.first { $0.id != original.id })

        // Keep an unselected row, then change the pointer outside the editor.
        try await core.workflows.setDeviceDefault(workflowId: other.id)
        let before = try await core.workflows.current()
        model.confirmDelete = other
        await model.delete(other)
        let protected = try await core.workflows.current()
        XCTAssertEqual(protected.revision, before.revision)
        XCTAssertEqual(protected.workflows.count, 2)
        XCTAssertNil(model.confirmDelete)
        XCTAssertEqual(model.message?.text, RecKitStrings.localized("Select another workflow before deleting this one."))

        await model.setDeviceDefault(original)
        await model.delete(other)
        let after = try await core.workflows.current()
        let selected = try await core.workflows.deviceDefault()
        XCTAssertEqual(after.workflows.map(\.id), [original.id])
        XCTAssertEqual(selected, original.id)
    }
}
