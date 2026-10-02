import ReclyCore
import XCTest
@testable import RecKit

/// docs/03 "저장 위치": what the two Apple shells say about a recording bound for iCloud — the wait
/// while the system uploads, the wait while iCloud cannot be used, and an account out of space —
/// and that a build without the iCloud entitlement does not offer iCloud at all.
final class ICloudStorageTests: XCTestCase {

    override func setUp() {
        super.setUp()
        AppLanguage.current = .en
    }

    override func tearDown() {
        AppLanguage.current = .system
        super.tearDown()
    }

    // MARK: - States

    /// The system is uploading: in flight, as a running Drive upload is — not a retry timer, not a
    /// wait in the header's count, and nothing to retry — but still a recording the user may
    /// delete, since no step of this device holds it.
    func testAnUploadThatIsWithICloudIsInFlightNotRetryPending() {
        let state = Recents.stateLabel(record: record(), job: job(.waiting), lastError: code(.icloudUploading))

        XCTAssertEqual(state, "Uploading to iCloud")
        XCTAssertEqual(LedgerStatus.forRecent(state: state).code, "UPLOADING")
        // Progress, as a running Drive upload is: no line under the row, least of all a red one.
        XCTAssertNil(item(state: state, lastError: code(.icloudUploading)).reason)
        XCTAssertFalse(item(state: state).canRetry)
        XCTAssertTrue(item(state: state).canDelete)
        XCTAssertEqual(Recents.summary([item(state: state)]), "1 · 0 waiting · 0 failed")
        XCTAssertTrue(Recents.uploading([item(state: state)]))
    }

    /// iCloud cannot be used from here: a wait, and asking again is worth offering once the user
    /// has signed in or turned iCloud Drive back on.
    func testAnUploadWaitingForICloudSaysSoAndCanBeAskedAgain() {
        let state = Recents.stateLabel(record: record(), job: job(.waiting), lastError: code(.icloudUnavailable))

        XCTAssertEqual(state, "Waiting for iCloud")
        XCTAssertNotEqual(LedgerStatus.forRecent(state: state).code, "UNKNOWN")
        // What to turn on is said under the row, in the tone of a wait rather than a failure.
        let waiting = item(state: state, lastError: code(.icloudUnavailable))
        XCTAssertNotNil(waiting.reason)
        XCTAssertEqual(waiting.reasonTone, .warning)
        XCTAssertTrue(item(state: state).canRetry)
    }

    /// The same park as a full Drive, named for iCloud — and no Drive storage page to send anyone to.
    func testAFullICloudIsItsOwnNoSpace() {
        let full = code(.icloudStorageFull)

        XCTAssertEqual(Recents.stateLabel(record: record(), job: job(.needsSpace), lastError: full), "No space in iCloud")
        XCTAssertEqual(Recents.stateLabel(record: record(), job: job(.needsSpace), lastError: code(.driveStorageFull)), "No space in Drive")
        XCTAssertEqual(LedgerStatus.forRecent(state: "No space in iCloud").code, "NO_SPACE")
        XCTAssertTrue(item(state: "No space in iCloud").canRetry)
        XCTAssertEqual(Recents.summary([item(state: "No space in iCloud")]), "1 · 0 waiting · 1 failed")

        XCTAssertEqual(JobAlerts.reason(status: .needsSpace, lastError: full), .icloudSpace)
        XCTAssertEqual(JobAlerts.reason(status: .needsSpace, lastError: code(.driveStorageFull)), .needsSpace)
        XCTAssertTrue(AlertReason.icloudSpace.isWait)
        XCTAssertEqual(AlertReason.icloudSpace.fix, .retryUploads)
    }

    // MARK: - The folder

    /// An iCloud recording has no Drive link, and still has a folder the delete can take with it.
    func testAnICloudRecordingHasAFolderToDeleteWithoutADriveLink() {
        let icloud = RecentItem(
            id: "01J9REC0000000000000000000", jobId: nil, title: "", startedAt: "2026-02-09T12:04:05.000Z",
            state: "Done", link: nil, storage: .icloud, lastError: nil
        )
        let local = RecentItem(
            id: "01J9REC0000000000000000000", jobId: nil, title: "", startedAt: "2026-02-09T12:04:05.000Z",
            state: "Done", link: nil, lastError: nil
        )

        XCTAssertTrue(icloud.hasCloudFolder)
        XCTAssertFalse(local.hasCloudFolder)
    }

    /// A row knows its storage from its folder id, and an iCloud folder has no Drive URL to open.
    func testTheRowReadsItsStorageOffTheFolderId() {
        let icloud = record(folder: "icloud:recly/memo/2026-02/20260209T120405Z_phone_01J9REC0")
        let drive = record(folder: "1FolderId")

        XCTAssertEqual(icloud.storage, .icloud)
        XCTAssertEqual(icloud.icloudFolderPath, "recly/memo/2026-02/20260209T120405Z_phone_01J9REC0")
        XCTAssertNil(icloud.driveFolderUrl)
        XCTAssertEqual(drive.storage, .drive)
        XCTAssertNil(drive.icloudFolderPath)
    }

    // MARK: - The build

    /// The test bundle is not signed with the iCloud entitlement and has no container in its
    /// Info.plist, which is exactly a build that must not offer iCloud.
    func testABuildWithoutAContainerOffersNoICloud() {
        XCTAssertNil(ICloudContainer.configured)
        XCTAssertNil(CoreBridge.defaultUbiquity)
    }

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
            storage: .icloud,
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

    private func record(folder: String? = nil) -> RecordingRecord {
        RecordingRecord(
            id: "01J9REC0000000000000000000",
            meta: RecordingMeta(
                schema: 1,
                recordingId: "01J9REC0000000000000000000",
                source: .phone,
                platform: Platform.ios,
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
                status: .finalized
            ),
            dir: OkioPath.companion.toPath("/tmp/recly-tests", normalize: false),
            driveFolderId: folder,
            remote: false,
            remotePending: [],
            driveSynced: false
        )
    }
}
