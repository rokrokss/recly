import Foundation
import ReclyCore
import XCTest
@testable import RecKit

/// The UX decisions of 2026-10-08: words where the screens showed codes (§1), the folder as two
/// choices (§10), and a job held up only by Drive reading as a wait for Drive (§7). The English and
/// Korean are the decision's own, word for word.
final class UxDecisionsTests: XCTestCase {

    override func tearDown() {
        AppLanguage.current = .system
        super.tearDown()
    }

    // MARK: - §1a: the badge is a word

    private static let badges: [(status: LedgerStatus, en: String, ko: String)] = [
        (LedgerStatus(code: "DONE", tone: .success), "Done", "완료"),
        (LedgerStatus(code: "FAILED", tone: .danger), "Failed", "실패"),
        (LedgerStatus(code: "RETRY", tone: .warning), "Retrying", "재시도 대기"),
        (LedgerStatus(code: "PENDING", tone: .neutral), "Waiting", "대기"),
        (LedgerStatus(code: "UPLOADING", tone: .accent), "Uploading", "업로드 중"),
        (LedgerStatus(code: "RECEIVING", tone: .accent), "Receiving", "받는 중"),
        (LedgerStatus(code: "TRANSCRIBING", tone: .accent), "Transcribing", "전사 중"),
        (LedgerStatus(code: "IMPORTING", tone: .accent), "Importing", "가져오는 중"),
        (LedgerStatus(code: "REC", tone: .danger), "Recording", "녹음 중"),
        (LedgerStatus(code: "NEEDS_AUTH", tone: .neutral), "Waiting for Drive", "Drive 연결 대기"),
        (LedgerStatus(code: "NEEDS_CONSENT", tone: .warning), "Needs permission", "허용 필요"),
        (LedgerStatus(code: "NEEDS_MODEL", tone: .warning), "Waiting for model", "모델 대기"),
        (LedgerStatus(code: "NO_SPACE", tone: .warning), "Storage full", "저장 공간 부족"),
        (LedgerStatus(code: "NEEDS_SPACE", tone: .warning), "Storage full", "저장 공간 부족"),
        (LedgerStatus(code: "SKIPPED", tone: .neutral), "Too short", "너무 짧음"),
        (LedgerStatus.forRecent(state: "Waiting for iCloud"), "Waiting for iCloud", "iCloud 대기"),
        (LedgerStatus.forRecent(state: "Waiting for the local folder"), "Waiting for folder", "폴더 대기"),
        (LedgerStatus(code: "UNKNOWN", tone: .neutral), "Unknown", "알 수 없음"),
    ]

    func testEveryBadgeIsTheDecidedWordInBothLanguages() {
        for (status, en, ko) in Self.badges {
            AppLanguage.current = .en
            XCTAssertEqual(status.label, en, status.code)
            AppLanguage.current = .ko
            XCTAssertEqual(status.label, ko, status.code)
        }
    }

    /// The status column is measured over what the ledger can show — both `WAITING` words included.
    func testTheStatusColumnMeasuresEveryWord() {
        let measured = Set(LedgerStatus.ledgerStatuses.map(\.label))
        for (status, _, _) in Self.badges where status.code != "NEEDS_SPACE" {
            XCTAssertTrue(measured.contains(status.label), "\(status.label) is not measured")
        }
    }

    /// A row waiting for Drive says the same word to VoiceOver as its badge does.
    func testTheSpokenStateOfAJobWaitingForDriveIsTheBadgesWord() {
        AppLanguage.current = .en
        XCTAssertEqual(RecKitStrings.localized("Sign-in needed"), "Waiting for Drive")
        AppLanguage.current = .ko
        XCTAssertEqual(RecKitStrings.localized("Sign-in needed"), "Drive 연결 대기")
        XCTAssertEqual(LedgerStatus.forRecent(state: "Sign-in needed").label, "Drive 연결 대기")
    }

    // MARK: - §1b·§1c: the dashboard's nodes

    func testTheStateAndDeviceNodesSayWords() {
        AppLanguage.current = .en
        XCTAssertEqual(
            [RecorderState.idle, .starting, .recording(recordingId: "r"), .stopping].map(StateNodeWords.recorder),
            ["Ready", "Starting", "Recording", "Saving"]
        )
        XCTAssertEqual([StateNodeWords.uploading, StateNodeWords.receiving], ["Uploading", "Receiving"])
        XCTAssertEqual([StateNodeWords.device(.phone), StateNodeWords.device(.desktop)], ["Phone", "Desktop"])
        AppLanguage.current = .ko
        XCTAssertEqual(
            [RecorderState.idle, .starting, .recording(recordingId: "r"), .stopping].map(StateNodeWords.recorder),
            ["준비", "시작 중", "녹음 중", "저장 중"]
        )
        XCTAssertEqual([StateNodeWords.uploading, StateNodeWords.receiving], ["업로드 중", "받는 중"])
        XCTAssertEqual([StateNodeWords.device(.phone), StateNodeWords.device(.desktop)], ["폰", "데스크톱"])
    }

    // MARK: - §7: a job held up only by Drive

    func testAFailureThatOnlyWantsDriveReadsAsAWaitForDrive() {
        let needsAuth = CoreMessage.needsAuth.code(arg: nil, detail: nil)
        let reauth = CoreMessage.driveReauth.code(arg: nil, detail: nil)
        let spent = CoreMessage.retryBudgetSpent.code(arg: needsAuth, detail: nil)
        let record = LocalFolderStorageTests.record()
        for code in [needsAuth, reauth, spent] {
            XCTAssertEqual(Recents.stateLabel(record: record, job: job(.failed), lastError: code), "Sign-in needed", code)
            XCTAssertEqual(JobAlerts.reason(status: .failed, lastError: code), .needsAuth, code)
            XCTAssertTrue(JobAlerts.waitsForDrive(status: .failed, lastError: code), code)
        }
        XCTAssertEqual(LedgerStatus.forRecent(state: "Sign-in needed").tone, .neutral, "never the red of a failure")
        XCTAssertTrue(JobAlerts.waitsForDrive(status: .needsAuth, lastError: nil))
    }

    func testAnyOtherFailureIsStillAFailure() {
        let other = CoreMessage.providerError.code(arg: nil, detail: nil)
        XCTAssertEqual(Recents.stateLabel(record: LocalFolderStorageTests.record(), job: job(.failed), lastError: other), "Failed")
        XCTAssertFalse(JobAlerts.waitsForDrive(status: .failed, lastError: other))
        XCTAssertFalse(JobAlerts.waitsForDrive(status: .failed, lastError: nil))
        XCTAssertFalse(JobAlerts.waitsForDrive(status: .waiting, lastError: CoreMessage.needsAuth.code(arg: nil, detail: nil)))
    }

    @MainActor
    func testTheDetailWaitsForDriveOffTheNewestJob() async {
        let parked = await RecordingDetailModel.waitsForDrive([job(.needsAuth)]) { _ in [] }
        XCTAssertTrue(parked)
        let done = await RecordingDetailModel.waitsForDrive([job(.done)]) { _ in [] }
        XCTAssertFalse(done)
        let none = await RecordingDetailModel.waitsForDrive([]) { _ in [] }
        XCTAssertFalse(none)
    }

    // MARK: - §10: the folder

    func testTheFolderIsMonthlyUnlessItIsTheOneFolder() {
        XCTAssertTrue(StorageFolder.isOne("recly/memo"))
        XCTAssertFalse(StorageFolder.isOne(StorageFolder.monthly))
        XCTAssertFalse(StorageFolder.isOne("recly/{{yyyy}}/{{MM}}"), "a template of the user's own shows as monthly")
    }

    func testThePreviewIsTodaysFolder() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try XCTUnwrap(TimeZone(identifier: "UTC"))
        let day = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 10, day: 8, hour: 9, minute: 5)))
        XCTAssertEqual(StorageFolder.preview(StorageFolder.monthly, at: day, calendar: calendar), "recly/memo/2026-10")
        XCTAssertEqual(StorageFolder.preview(StorageFolder.one, at: day, calendar: calendar), "recly/memo")
        XCTAssertEqual(
            StorageFolder.preview("x/{{yyyy}}/{{dd}}-{{HH}}{{mm}}/{{title}}", at: day, calendar: calendar),
            "x/2026/08-0905/{{title}}"
        )
    }

    func testTheFolderChoicesAreTheDecidedWords() {
        AppLanguage.current = .en
        XCTAssertEqual([RecKitStrings.localized("Monthly folders"), RecKitStrings.localized("One folder")], ["Monthly folders", "One folder"])
        AppLanguage.current = .ko
        XCTAssertEqual([RecKitStrings.localized("Monthly folders"), RecKitStrings.localized("One folder")], ["월별 폴더", "폴더 하나"])
    }

    // MARK: - Pieces

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
}
