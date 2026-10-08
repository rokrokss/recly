import Foundation
import RecKitTestSupport
import ReclyCore
import XCTest
@testable import RecKit

/// docs/03 "Storage location": what the Mac says about a recording bound for the local folder — the wait
/// while the folder cannot be reached, the folder "Show in Finder" opens — and the storage choice
/// that offers the folder, keeps the pick on this Mac and tells the runner.
final class LocalFolderStorageTests: XCTestCase {

    override func setUp() {
        super.setUp()
        AppLanguage.current = .en
    }

    override func tearDown() {
        AppLanguage.current = .system
        super.tearDown()
    }

    // MARK: - States

    /// The folder cannot be reached: a wait in the warning tone, as iCloud's is, counted with the
    /// waits, and asking again is worth offering once the user has picked the folder again.
    func testAnUploadWaitingForTheFolderSaysSoAndCanBeAskedAgain() {
        let unavailable = code(.folderUnavailable)
        let state = Recents.stateLabel(record: record(), job: job(.waiting), lastError: unavailable)

        XCTAssertEqual(state, "Waiting for the local folder")
        XCTAssertEqual(LedgerStatus.forRecent(state: state), LedgerStatus(code: "WAITING", tone: .warning, word: "Waiting for folder"))
        XCTAssertEqual(LedgerStatus.forRecent(state: state).label, "Waiting for folder")
        let waiting = item(state: state, lastError: unavailable)
        XCTAssertEqual(
            waiting.reason?.sentence,
            "The local folder cannot be reached. Choose it again in Settings."
        )
        XCTAssertEqual(waiting.reasonTone, .warning)
        XCTAssertTrue(waiting.canRetry)
        XCTAssertTrue(waiting.canDelete)
        XCTAssertEqual(Recents.summary([waiting]), "1 · 1 waiting · 0 failed")
        XCTAssertFalse(Recents.uploading([waiting]))
    }

    /// A wait and not a failure the user is called about: no banner line, no notification — the
    /// same as iCloud that cannot be used.
    func testAWaitForTheFolderRaisesNoAlert() {
        XCTAssertNil(JobAlerts.reason(status: .waiting, lastError: code(.folderUnavailable)))
        XCTAssertNil(JobAlerts.reason(status: .waiting, lastError: code(.icloudUnavailable)))
    }

    // MARK: - The folder

    /// A local folder recording has no Drive link, and still has a folder the delete can take with it.
    func testAFolderRecordingHasAFolderToDeleteWithoutADriveLink() {
        let folder = RecentItem(
            id: "01J9REC0000000000000000000", jobId: nil, title: "", startedAt: "2026-02-09T12:04:05.000Z",
            state: "Done", link: nil, storage: .folder, lastError: nil
        )

        XCTAssertTrue(folder.hasCloudFolder)
    }

    /// A row knows the local folder from its folder id, and the path it keeps is the one under the
    /// picked folder — no Drive URL, no iCloud path.
    func testTheRowReadsTheFolderOffTheFolderId() {
        let folder = record(folder: "folder:recly/memo/2026-02/20260209T120405Z_mac_01J9REC0")

        XCTAssertEqual(folder.storage, .folder)
        XCTAssertEqual(folder.localFolderPath, "recly/memo/2026-02/20260209T120405Z_mac_01J9REC0")
        XCTAssertNil(folder.icloudFolderPath)
        XCTAssertNil(folder.driveFolderUrl)
        XCTAssertNil(record(folder: "icloud:recly/memo").localFolderPath)
    }

    #if os(macOS)
    /// "Show in Finder" opens the recording's folder under the folder picked on this Mac, and there
    /// is nothing to open while none is picked.
    func testTheFinderFolderIsThePathUnderThePickedFolder() {
        XCTAssertEqual(
            LocalFolderPath.url("recly/memo/2026-02", under: "/Users/me/Notes"),
            URL(fileURLWithPath: "/Users/me/Notes/recly/memo/2026-02", isDirectory: true)
        )
        XCTAssertNil(LocalFolderPath.url("recly/memo/2026-02", under: nil))
    }
    #endif

    // MARK: - Pieces

    private func code(_ message: CoreMessage) -> String { message.code(arg: nil, detail: nil) }

    private func item(state: String, lastError: String? = nil) -> RecentItem {
        RecentItem(
            id: "01J9REC0000000000000000000",
            jobId: "01J9JOB0000000000000000000",
            title: "",
            startedAt: "2026-02-09T12:04:05.000Z",
            state: state,
            link: nil,
            storage: .folder,
            lastError: lastError
        )
    }

    private func job(_ status: JobStatus) -> ReclyCore.Job {
        let at = KotlinInstant.companion.fromEpochMilliseconds(epochMilliseconds: 0)
        return ReclyCore.Job(
            id: "01J9JOB0000000000000000000",
            recordingId: "01J9REC0000000000000000000",
            workflowId: "00000000000000000000REC100",
            workflow: nil,
            status: status,
            createdAt: at,
            updatedAt: at,
            nextRunAt: nil,
            snapshotError: nil
        )
    }

    static func record(folder: String? = nil, dir: String = "/tmp/recly-tests") -> RecordingRecord {
        RecordingRecord(
            id: "01J9REC0000000000000000000",
            meta: RecordingMeta(
                schema: 1,
                recordingId: "01J9REC0000000000000000000",
                source: .desktop,
                platform: Platform.macos,
                deviceId: "01J9DEV0000000000000000000",
                deviceName: "RecKitTests",
                workflowId: nil,
                title: nil,
                startedAt: "2026-02-09T12:04:05.000Z",
                endedAt: "2026-02-09T12:05:05.000Z",
                durationSec: 60,
                timezone: "Asia/Seoul",
                audio: AudioSettings(
                    codec: Codec.aacLc, container: Container.m4A, sampleRateHz: 16_000,
                    channels: 1, bitrateKbps: 32, segmentSec: 900
                ),
                tracks: [Track.mono],
                parts: [],
                gaps: [],
                silenced: [],
                context: nil,
                drive: nil,
                status: .finalized,
                highlights: []
            ),
            dir: OkioPath.companion.toPath(dir, normalize: false),
            driveFolderId: folder,
            remote: false,
            remotePending: [],
            driveSynced: false
        )
    }

    private func record(folder: String? = nil) -> RecordingRecord { Self.record(folder: folder) }
}

#if os(macOS)
/// docs/03 "Storage location": the storage choice on a Mac, over a real core with the Mac's own local folder —
/// the `PathFolder` over the path kept in this Mac's settings, which is what the row reads too.
@MainActor
final class LocalFolderChoiceTests: XCTestCase {
    private var dataDirectory: URL!
    private var picked: URL!
    /// What this process's defaults held under the key before the test, put back after it.
    private var previous: Any?

    override func setUp() async throws {
        let base = FileManager.default.temporaryDirectory
            .appendingPathComponent("LocalFolderChoiceTests-\(UUID().uuidString)", isDirectory: true)
        dataDirectory = base.appendingPathComponent("data", isDirectory: true)
        picked = base.appendingPathComponent("Notes", isDirectory: true)
        try FileManager.default.createDirectory(at: picked, withIntermediateDirectories: true)
        previous = UserDefaults.standard.object(forKey: LocalFolderPath.key)
        UserDefaults.standard.removeObject(forKey: LocalFolderPath.key)
    }

    override func tearDown() async throws {
        UserDefaults.standard.set(previous, forKey: LocalFolderPath.key)
        try? FileManager.default.removeItem(at: dataDirectory.deletingLastPathComponent())
    }

    /// The Mac offers the folder whatever its iCloud entitlement — the test bundle has none — and a
    /// tap on the chip saves it as the storage at once.
    func testTheMacOffersTheFolderAndSavesTheChoice() async throws {
        let bridge = try await makeBridge()
        let choice = StorageChoice(core: bridge.core)

        XCTAssertTrue(choice.folderOffered)
        XCTAssertFalse(choice.icloudOffered)
        XCTAssertTrue(choice.offered)

        await choice.select(.folder)

        XCTAssertEqual(choice.selected, .folder)
        XCTAssertNil(choice.message)
        let stored = try await bridge.core.processingSettings.storage()
        XCTAssertEqual(stored, .folder)
    }

    /// No folder, a folder that is there, and one that has gone: the row's three states, read again
    /// on every refresh.
    func testTheRowFollowsThePickedFolder() async throws {
        let choice = StorageChoice(core: try await makeBridge().core)

        await choice.refresh()
        XCTAssertEqual(choice.folder, .notChosen)
        XCTAssertNil(choice.folderPath)

        LocalFolderPath.current = picked.path
        await choice.refresh()
        XCTAssertEqual(choice.folder, .available)
        XCTAssertEqual(choice.folderPath, picked.path)

        try FileManager.default.removeItem(at: picked)
        await choice.refresh()
        XCTAssertEqual(choice.folder, .unavailable)
        XCTAssertEqual(choice.folderPath, picked.path)
    }

    /// A pick is kept on this Mac, and the runner is told the waiting jobs are due.
    func testAPickIsKeptAndTellsTheRunner() async throws {
        let choice = StorageChoice(core: try await makeBridge().core)
        var told = 0
        choice.onFolderPicked = { told += 1 }

        await choice.pickFolder(picked.path)

        XCTAssertEqual(LocalFolderPath.current, picked.path)
        XCTAssertEqual(told, 1)
        XCTAssertEqual(choice.folder, .available)
    }

    /// "Show in Finder" on a local folder recording: its folder under the picked one when it is
    /// there, and nothing when it is not — the folder picked since is another one.
    func testShowInFinderOnlyFindsAFolderThatIsThere() async throws {
        let bridge = try await makeBridge()
        LocalFolderPath.current = picked.path
        let path = "recly/memo/2026-02/20260209T120405Z_mac_01J9REC0"
        let record = LocalFolderStorageTests.record(folder: "folder:" + path)

        let missing = await Recents.cloudFolder(record: record, core: bridge.core)
        XCTAssertNil(missing)

        let folder = picked.appendingPathComponent(path, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let found = await Recents.cloudFolder(record: record, core: bridge.core)
        XCTAssertEqual(found?.standardizedFileURL.path, folder.standardizedFileURL.path)
    }

    /// A recording not copied yet has no folder to read its storage off: the delete dialog takes the
    /// storage it froze when it started, so it says "not yet in the local folder" and not Drive.
    func testARecordingNotCopiedYetIsBoundForTheStorageItFroze() async throws {
        let core = try await makeBridge().core
        _ = try await core.processingSettings.setStorage(provider: .folder)
        try await core.processingSettings.capture(recordingId: "01J9REC0000000000000000000")

        let frozen = await Retention.storage(core: core, recordingId: "01J9REC0000000000000000000")
        XCTAssertEqual(frozen, .folder)
        let unknown = await Retention.storage(core: core, recordingId: "01J9NONE000000000000000000")
        XCTAssertNil(unknown)
    }

    private func makeBridge() async throws -> CoreBridge {
        try await CoreBridge.make(
            deviceName: "RecKitTests",
            dataDirectory: dataDirectory,
            databaseName: "local-folder-tests.db",
            secureStore: InMemorySecureStore()
        )
    }
}
#endif
