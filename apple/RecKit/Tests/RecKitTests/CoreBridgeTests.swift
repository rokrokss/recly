import RecKitTestSupport
import ReclyCore
import XCTest
@testable import RecKit

/// docs/12 M1's acceptance check: a shell that only supplies `CoreDeps` gets a working core with an
/// open local database and the two docs/05 defaults in it.
final class CoreBridgeTests: XCTestCase {
    /// A directory of its own per test, and the database now lives inside it (`basePath`), so every
    /// test opens a genuinely fresh file and the teardown takes the whole thing with it. The names
    /// are unique too — nothing here can be handed a database another test seeded.
    private var dataDirectory: URL!

    override func setUpWithError() throws {
        dataDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("RecKitTests-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dataDirectory)
    }

    /// A never-opened database file, so these two are what the core seeded (docs/05), not leftovers.
    func testSeedsTheTwoDefaultWorkflows() async throws {
        let bridge = try await makeBridge()
        let document = try await bridge.core.workflows.current()

        XCTAssertEqual(document.workflows.count, 1)
        XCTAssertEqual(document.workflows.map(\.id), [WorkflowRepository.companion.MEMO_ID])
        // docs/07 §6: seeded in the device's language, which is what `CoreBridge` hands the core.
        let seeded = CoreBridge.deviceLanguage == "ko" ? ["메모"] : ["Memo"]
        XCTAssertEqual(document.workflows.map(\.name), seeded)

        let names = try await bridge.workflowNames()
        XCTAssertEqual(names, seeded)
    }

    /// They are placeholders until a device publishes them: epoch stamps, so any real `updatedAt`
    /// wins the merge (docs/05).
    func testDefaultsAreUnpublishedPlaceholders() async throws {
        let bridge = try await makeBridge()
        let document = try await bridge.core.workflows.current()

        XCTAssertEqual(document.revision, 0)
        for workflow in document.workflows {
            XCTAssertEqual(workflow.updatedAt, WorkflowRepository.companion.PLACEHOLDER_UPDATED_AT)
        }
    }

    /// ADR-016: which of the two starters this device runs is a local pointer, not a field of the
    /// document — and a device that has only *read* the seeds has not chosen one yet. `seed` is what
    /// takes the guess, and only on the call that put them there.
    func testTheSeedingDeviceTakesThePreferredDefaultAndAReadTakesNone() async throws {
        let reader = try await makeBridge()
        _ = try await reader.core.workflows.current()
        let unchosen = try await reader.core.workflows.deviceDefault()
        XCTAssertNil(unchosen, "reading the seeds is not choosing one of them")

        // A database of its own, so this really is a device that has never had a document.
        let seeder = try await makeBridge()
        _ = try await seeder.core.workflows.seed(
            preferredDefaultId: WorkflowRepository.companion.MEMO_ID
        )

        let chosen = try await seeder.core.workflows.deviceDefault()
        XCTAssertEqual(chosen, WorkflowRepository.companion.MEMO_ID)
        let memoIsDefault = try await seeder.core.workflows.isDeviceDefault(
            workflowId: WorkflowRepository.companion.MEMO_ID
        )
        XCTAssertTrue(memoIsDefault.boolValue, "the starter is this device's default")
    }

    /// docs/05 "워크플로우 내보내기 · 가져오기": definitions are per-device now, so the file *is* the
    /// transfer — and the round trip is what says so. Two databases, which is two devices: one
    /// exports what it holds, the other picks the file up, is told how many are in it and replaces
    /// its own whole document with them (there is no merge).
    ///
    /// Over the real core and a real file rather than a fake of either: what this is about is the
    /// bytes surviving `exportJson` → disk → `importJson`, and a fake of the core would only prove
    /// the model calls it.
    @MainActor
    func testAnExportedFileReplacesTheWholeDocumentOnAnotherDevice() async throws {
        let source = try await makeBridge()
        let carried = Workflow(
            id: "00000000000000000000CARRY0",
            name: "Carried",
            updatedAt: "2026-08-01T00:00:00.000Z",
            minDurationSec: 0,
            steps: [
                Step.DriveUpload(
                    id: "upload",
                    onError: .abort,
                    retry: StepDefaults.retry,
                    folder: StepDefaults.folder,
                    includeMeta: true
                ),
            ]
        )
        guard case .saved = onEnum(of: try await source.core.workflows.save(
            document: WorkflowsDocument(
                schema: 3,
                revision: 1,
                updatedAt: "2026-08-01T00:00:00.000Z",
                updatedBy: "device-a",
                workflows: [carried]
            )
        )) else { return XCTFail("the source device would not take the document") }

        let file = dataDirectory.appendingPathComponent(WorkflowTransferModel.fileName)
        await WorkflowTransferModel(core: source.core).export(to: file)

        // A database of its own: a device that has never seen the file, with its own starter.
        let target = try await makeBridge()
        let starters = try await target.core.workflows.current().workflows
        XCTAssertEqual(starters.count, 1)
        let transfer = WorkflowTransferModel(core: target.core)

        await transfer.pick(file)

        XCTAssertEqual(transfer.confirm?.workflows, 1, "the confirmation names what the file holds")
        let untouched = try await target.core.workflows.current().workflows
        XCTAssertEqual(untouched.count, 1, "the confirmation had not been answered yet")

        await transfer.confirmImport()

        let imported = try await target.core.workflows.current().workflows
        XCTAssertEqual(imported.map(\.id), [carried.id], "the file replaced the whole document")
        XCTAssertEqual(imported.map(\.name), ["Carried"])
        XCTAssertNil(transfer.confirm)
    }

    /// A file that is not a document is refused without writing anything, and what is shown is the
    /// parser's own list — docs/02 owns those words, so nothing here invents a sentence for them.
    @MainActor
    func testAFileThatIsNotAWorkflowDocumentLeavesTheDeviceAlone() async throws {
        let bridge = try await makeBridge()
        let file = dataDirectory.appendingPathComponent("not-a-document.json")
        try Data("{\"schema\":3}".utf8).write(to: file)
        let transfer = WorkflowTransferModel(core: bridge.core)

        await transfer.pick(file)

        XCTAssertNil(transfer.confirm, "nothing to confirm: there is nothing to import")
        XCTAssertTrue(transfer.failed)
        let stored = try await bridge.core.workflows.current().workflows
        XCTAssertEqual(stored.count, 1, "the starter is still what this device runs")
    }

    /// The device id is minted once and kept — the next launch must find the same value.
    func testDeviceIdIsMintedOnceAndReused() async throws {
        let first = try await makeBridge()
        let second = try await makeBridge()

        XCTAssertFalse(first.deps.device.deviceId.isEmpty)
        XCTAssertEqual(first.deps.device.deviceId, second.deps.device.deviceId)
        // docs/03 `platform`: what this install says produced a recording. The suite runs on the
        // iOS simulator too (M5-L2), where the honest answer is a different one.
        #if os(macOS)
        XCTAssertEqual(first.deps.device.platform, Platform.macos)
        #else
        XCTAssertEqual(first.deps.device.platform, Platform.ios)
        #endif
        XCTAssertEqual(
            try String(contentsOf: dataDirectory.appendingPathComponent(CoreBridge.deviceIdFile), encoding: .utf8),
            first.deps.device.deviceId
        )
    }

    /// Sol M4-L2 / lead: opening the core must not touch the Keychain. The device id is not a secret
    /// — it is written into every `meta.json` in the clear — and reading it through
    /// `SecItemCopyMatching` cost the app its launch: on an ad-hoc-signed build the signature changes
    /// with every rebuild, so the legacy file keychain answers with an ACL modal that the menu never
    /// gets past (measured, docs/measurements.md). The store is still handed to the core for the
    /// things that *are* secret; it is simply not on the path to the menu.
    func testOpeningTheCoreAsksTheSecureStoreForNothing() async throws {
        let store = InMemorySecureStore()

        let bridge = try await makeBridge(secureStore: store)

        XCTAssertEqual(store.calls, [], "a Keychain call here is a modal in front of the menu bar")
        XCTAssertFalse(bridge.deps.device.deviceId.isEmpty)
    }

    /// A file that was truncated (a machine that lost power mid-write) is not an id: minting a new
    /// one is the only thing left, and it is what a reinstall does anyway (docs/01).
    func testAnEmptyDeviceIdFileIsReplaced() throws {
        try FileManager.default.createDirectory(at: dataDirectory, withIntermediateDirectories: true)
        let url = dataDirectory.appendingPathComponent(CoreBridge.deviceIdFile)
        try Data().write(to: url)

        let minted = try CoreBridge.deviceId(in: dataDirectory)

        XCTAssertFalse(minted.isEmpty)
        XCTAssertEqual(try CoreBridge.deviceId(in: dataDirectory), minted, "and then it is kept")
    }

    /// Sign-in is M4-L3, so the stub is what a job hits — and it has to be the core's own
    /// `AuthRequiredException`, not some other error, or `Executor` would take the
    /// `catch (e: Throwable)` branch and burn retries instead of parking in `NEEDS_AUTH` (docs/05).
    ///
    /// Goes through `AppleRuntime.probeAccessToken`, so what is checked is the trip the core
    /// actually makes: Kotlin calls the *Swift* `TokenProvider`, the Swift throw crosses back into
    /// Kotlin, and only then comes out here. Asserting on the stub's own `throw` would have proved
    /// nothing about that boundary — it never enters Kotlin at all.
    ///
    /// It arrives as an `NSError` carrying the Kotlin exception (`userInfo["KotlinException"]`):
    /// that is Kotlin/Native's `@Throws` translation, and the instance inside it is the one Kotlin
    /// held. SKIE adds no typed throw of its own for a non-suspend `@Throws` function.
    func testStubTokenProviderReportsAuthRequired() throws {
        XCTAssertThrowsError(
            try AppleRuntime.shared.probeAccessToken(provider: StubTokenProvider())
        ) { error in
            XCTAssertTrue(
                (error as NSError).userInfo["KotlinException"] is AuthRequiredException,
                "expected AuthRequiredException, got \(error)"
            )
        }
    }

    /// The database and the `-wal`/`-shm` beside it are one unit, and a fresh destination takes all
    /// three. Distinct contents per file: a test that only checked existence would pass on a move
    /// that shuffled them.
    func testRelocationMovesTheWholeDatabaseUnit() throws {
        try seed(["rec.db": "db", "rec.db-wal": "wal", "rec.db-shm": "shm"], in: legacyDirectory)
        try FileManager.default.createDirectory(at: destinationDirectory, withIntermediateDirectories: true)
        let logger = RecordingLogger()

        try CoreBridge.relocateLegacyDatabase(
            named: "rec.db", from: legacyDirectory, into: destinationDirectory, logger: logger
        )

        for (name, marker) in ["rec.db": "db", "rec.db-wal": "wal", "rec.db-shm": "shm"] {
            XCTAssertEqual(contents(of: destinationDirectory, named: name), marker)
            XCTAssertNil(contents(of: legacyDirectory, named: name))
        }
        XCTAssertEqual(logger.events, ["db.relocate.ok"])
    }

    /// A database already at the destination ends it. The legacy `-wal`/`-shm` belong to the legacy
    /// database, and moving them on top of a live one is how it gets corrupted — so nothing moves,
    /// and the destination is left exactly as it was.
    func testRelocationMovesNothingWhenTheDestinationHasADatabase() throws {
        try seed(["rec.db": "legacy", "rec.db-wal": "legacy-wal", "rec.db-shm": "legacy-shm"], in: legacyDirectory)
        try seed(["rec.db": "current"], in: destinationDirectory)
        let logger = RecordingLogger()

        try CoreBridge.relocateLegacyDatabase(
            named: "rec.db", from: legacyDirectory, into: destinationDirectory, logger: logger
        )

        XCTAssertEqual(contents(of: destinationDirectory, named: "rec.db"), "current")
        XCTAssertNil(contents(of: destinationDirectory, named: "rec.db-wal"))
        XCTAssertNil(contents(of: destinationDirectory, named: "rec.db-shm"))
        for (name, marker) in ["rec.db": "legacy", "rec.db-wal": "legacy-wal", "rec.db-shm": "legacy-shm"] {
            XCTAssertEqual(contents(of: legacyDirectory, named: name), marker)
        }
        XCTAssertEqual(logger.events, ["db.relocate.skipped"])
    }

    /// A cleanly closed database has no `-wal`/`-shm` at all, which is the common case — the unit is
    /// whatever of the three is there, not all three.
    func testRelocationMovesADatabaseWithNoSidecars() throws {
        try seed(["rec.db": "db"], in: legacyDirectory)
        try FileManager.default.createDirectory(at: destinationDirectory, withIntermediateDirectories: true)
        let logger = RecordingLogger()

        try CoreBridge.relocateLegacyDatabase(
            named: "rec.db", from: legacyDirectory, into: destinationDirectory, logger: logger
        )

        XCTAssertEqual(contents(of: destinationDirectory, named: "rec.db"), "db")
        XCTAssertNil(contents(of: legacyDirectory, named: "rec.db"))
        XCTAssertEqual(logger.events, ["db.relocate.ok"])
    }

    /// Both under `dataDirectory`, so the teardown takes them with it.
    private var legacyDirectory: URL { dataDirectory.appendingPathComponent("databases", isDirectory: true) }
    private var destinationDirectory: URL { dataDirectory.appendingPathComponent("app.recly.mac", isDirectory: true) }

    private func seed(_ files: [String: String], in directory: URL) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        for (name, marker) in files {
            try Data(marker.utf8).write(to: directory.appendingPathComponent(name))
        }
    }

    private func contents(of directory: URL, named name: String) -> String? {
        try? String(decoding: Data(contentsOf: directory.appendingPathComponent(name)), as: UTF8.self)
    }

    /// A database that will not open has to arrive here as a Swift error, and this is the test that
    /// says so: without `AppleRuntime.openCore` forcing the open inside its `@Throws`, the native
    /// driver opens the file lazily inside a coroutine on `deps.io`, where the failure is an
    /// uncaught Kotlin exception that takes the process down — verified, it killed this test run.
    func testUnopenableDatabasePathThrowsRatherThanCrashing() async throws {
        let deps = try await makeBridge().deps

        XCTAssertThrowsError(
            try AppleRuntime.shared.openCore(
                deps: deps,
                name: "rec.db",
                basePath: "/dev/null/not-a-directory"
            )
        )
    }

    private func makeBridge(
        secureStore: any ReclyCore.SecureStore = InMemorySecureStore(),
        databaseName: String = "reckit-tests-\(UUID().uuidString).db"
    ) async throws -> CoreBridge {
        try await CoreBridge.make(
            appVersion: "0.0.0-test",
            deviceName: "RecKitTests",
            dataDirectory: dataDirectory,
            databaseName: databaseName,
            secureStore: secureStore
        )
    }
}
