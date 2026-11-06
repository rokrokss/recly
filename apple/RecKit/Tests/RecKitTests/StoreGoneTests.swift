import Foundation
import RecKitTestSupport
import ReclyCore
import XCTest
@testable import RecKit

/// docs/01: the store gone from under an open database, which only Kotlin/Native can show. A flow that throws under a
/// Swift `for await`, or a read with no `@Throws` under a `try?`, ends the process here, and with it this test.
final class StoreGoneTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("StoreGoneTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    func testAStoreGoneEndsNothing() async throws {
        let bridge = try await CoreBridge.make(
            deviceName: "StoreGoneTests", dataDirectory: directory,
            databaseName: "store-gone-tests.db", secureStore: InMemorySecureStore()
        )
        let core = bridge.core
        try FileManager.default.removeItem(at: directory)

        let observers = [
            Task { for await _ in core.recordings.observe() {} },
            Task { for await _ in core.recordings.observeAudio(recordingId: "x") {} },
            Task { for await _ in core.jobs.observe() {} },
            Task { for await _ in core.jobs.observeSteps(recordingId: "x") {} },
            Task { for await _ in core.transferConsents.observe() {} },
            Task { for await _ in core.notes.observe(recordingId: "x") {} },
        ]
        let results = Task {
            for await result in core.observeResults(recordingId: "x") where result.availability == .unavailable { return true }
            return false
        }

        // What the bodies of those loops read: each one fails as an error.
        let record = try? await core.recordings.get(id: "x")
        XCTAssertNil(record)
        let records = try? await core.recordings.list(limit: 5)
        XCTAssertNil(records)
        let ids = try? await core.recordings.ids()
        XCTAssertNil(ids)
        let jobs = try? await core.jobs.list()
        XCTAssertNil(jobs)
        let steps = try? await core.jobs.steps(jobId: "x")
        XCTAssertNil(steps)

        let unavailable = await results.value
        XCTAssertTrue(unavailable)

        // Cancelled with the store still gone: the other path by which a failure reached the process.
        observers.forEach { $0.cancel() }
        for observer in observers { await observer.value }
        try await Task.sleep(for: .milliseconds(300))
    }
}
