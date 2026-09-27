import Foundation
import RecKitTestSupport
import ReclyCore
import XCTest
@testable import RecKit

/// docs/05 "고정 처리 설정 도입" · docs/10: a recording whose on-device speech model is missing waits
/// for it (`NEEDS_MODEL`) instead of failing — its row, its badge, the banner and the runner all
/// read it as a wait, and the fix is the download.
final class ModelWaitTests: XCTestCase {

    override func tearDown() {
        AppLanguage.current = .system
        super.tearDown()
    }

    private let modelRequired = CoreMessage.localModelRequired.code(arg: nil, detail: nil)

    func testTheWaitIsARowStateOfItsOwnWithAWarningBadge() {
        XCTAssertEqual(Recents.stateLabel(record: record(), job: job(.needsModel)), "Waiting for speech model")
        XCTAssertEqual(
            LedgerStatus.forRecent(state: "Waiting for speech model"),
            LedgerStatus(code: "NEEDS_MODEL", tone: .warning)
        )
        // The same tone consent waits in.
        XCTAssertEqual(LedgerStatus.forRecent(state: "Transfer permission needed").tone, .warning)
    }

    func testTheRowSaysItInBothLanguages() {
        AppLanguage.current = .en
        XCTAssertEqual(item("Waiting for speech model").stateLabel, "Waiting for speech model")
        AppLanguage.current = .ko
        XCTAssertEqual(item("Waiting for speech model").stateLabel, "음성 모델 대기")
    }

    /// The download is the row's action; a retry would only park it again.
    func testAWaitingRowOffersTheDownloadAndNoRetry() {
        let waiting = item("Waiting for speech model")
        XCTAssertTrue(waiting.waitingForModel)
        XCTAssertFalse(waiting.canRetry)
        XCTAssertFalse(item("Failed").waitingForModel)
    }

    func testTheHeaderCountsItAsWaitingAndNotAsFailed() {
        AppLanguage.current = .en
        XCTAssertEqual(Recents.summary([item("Waiting for speech model")]), "1 · 1 waiting · 0 failed")
    }

    /// Parked like consent: no timer brings it back, the download does.
    func testTheRunnerDoesNotComeBackForIt() {
        XCTAssertEqual(JobRunStatus(JobStatus.needsModel), .needsModel)
        XCTAssertNil(NextRun.at([JobRunSnapshot(status: .needsModel)], now: Date()))
    }

    func testTheBannerReasonComesFromTheStatus() {
        XCTAssertEqual(JobAlerts.reason(status: .needsModel, lastError: modelRequired), .localModel)
        XCTAssertEqual(JobAlerts.reason(status: .needsModel, lastError: nil), .localModel)
        // A failed job no longer carries the missing model, so it no longer means one.
        XCTAssertNil(JobAlerts.reason(status: .failed, lastError: modelRequired))
    }

    func testTheWaitingStepIsTheOneTheRowExplains() {
        let steps = [step(0, .succeeded, nil), step(1, .needsModel, modelRequired), step(2, .pending, nil)]
        XCTAssertEqual(JobAlerts.blockingError(steps: steps), modelRequired)
        XCTAssertEqual(JobAlerts.source(status: .needsModel, steps: steps).reason, .localModel)
    }

    /// The banner starts the download where it stands rather than sending the user to settings.
    func testTheBannersFixIsTheDownload() {
        XCTAssertEqual(AlertReason.localModel.fix, .modelDownload)
        // The job's own status, like consent's — the long message code squeezed the banner.
        XCTAssertEqual(AlertReason.localModel.code, "NEEDS_MODEL")
        AppLanguage.current = .en
        XCTAssertEqual(FixSurface.modelDownload.label, "Download model")
        AppLanguage.current = .ko
        XCTAssertEqual(FixSurface.modelDownload.label, "모델 다운로드")
    }

    /// The core's sentence says what is missing, not where to go — and the banner line follows it.
    func testTheSentenceSaysTheModelIsNotHereYet() {
        AppLanguage.current = .en
        let english = "The speech recognition model isn't downloaded yet."
        XCTAssertEqual(CoreMessages.sentence(.localModelRequired), english)
        XCTAssertEqual(AlertReason.localModel.label, english)
        AppLanguage.current = .ko
        let korean = "음성 인식 모델을 아직 다운로드하지 않았습니다."
        XCTAssertEqual(CoreMessages.sentence(.localModelRequired), korean)
        XCTAssertEqual(AlertReason.localModel.label, korean)
    }

    /// A row downloads the model its own recording waits for, in the core's code for the language.
    func testTheRowDownloadsTheLanguageItsOwnStepWaitsFor() throws {
        let draft = ProcessingDraft.companion.from(settings: ProcessingSettings.companion.defaults())
        draft.mode = .local
        draft.language = .zhCn
        let document = ProcessingSettingsDocument(
            schema: 1, revision: 1, updatedAt: "2026-09-26T00:00:00Z", updatedBy: "tests", settings: draft.settings()
        )
        let plan = ProcessingPlan.shared.compile(document: document)
        let local = try XCTUnwrap(plan.steps.compactMap { $0 as? Step.LocalTranscribe }.first)
        let steps = [step(0, .succeeded, nil, id: "upload"), step(1, .needsModel, modelRequired, id: local.id)]

        XCTAssertEqual(Recents.localLanguage(job: job(.needsModel, workflow: plan), steps: steps), "zh-cn")
        XCTAssertNil(Recents.localLanguage(job: job(.needsModel, workflow: nil), steps: steps))
    }

    // MARK: - Fixtures

    private func item(_ state: String) -> RecentItem {
        RecentItem(
            id: "01J9REC0000000000000000000",
            jobId: "01J9JOB0000000000000000000",
            title: "",
            startedAt: "2026-02-09T12:04:05.000Z",
            state: state,
            link: nil,
            lastError: nil
        )
    }

    private func step(_ ordinal: Int32, _ status: StepStatus, _ lastError: String?, id: String? = nil) -> StepRun {
        StepRun(
            id: "s\(ordinal)",
            jobId: "j",
            stepId: id ?? "step\(ordinal)",
            ordinal: ordinal,
            status: status,
            attempts: 0,
            nextAttemptAt: nil,
            lastError: lastError,
            state: nil,
            output: nil
        )
    }

    private func job(_ status: JobStatus, workflow: Workflow? = nil) -> ReclyCore.Job {
        let at = KotlinInstant.companion.fromEpochMilliseconds(epochMilliseconds: 0)
        return ReclyCore.Job(
            id: "01J9JOB0000000000000000000",
            recordingId: "01J9REC0000000000000000000",
            workflowId: "00000000000000000000REC100",
            workflow: workflow,
            status: status,
            createdAt: at,
            updatedAt: at,
            nextRunAt: nil,
            snapshotError: nil
        )
    }

    /// A finished recording of this device's own: nothing about it answers before the job does.
    private func record() -> RecordingRecord {
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
                    codec: Codec.aacLc,
                    container: Container.m4A,
                    sampleRateHz: 48_000,
                    channels: 1,
                    bitrateKbps: 96,
                    segmentSec: 900
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
            driveFolderId: nil,
            remote: false,
            remotePending: [],
            driveSynced: false
        )
    }
}

/// docs/05 "고정 처리 설정 도입": the first-run card, and the words every download surface shares.
final class ModelPromptTests: XCTestCase {

    override func tearDown() {
        AppLanguage.current = .system
        super.tearDown()
    }

    func testTheCardIsForADeviceSetToTranscribeHereWithoutTheModel() {
        XCTAssertTrue(visible())
    }

    func testEveryOtherReadingHidesIt() {
        for mode in [TranscriptionMode.external, .off] {
            XCTAssertFalse(visible(mode: mode), "\(mode)")
        }
        XCTAssertFalse(visible(mode: nil), "settings not read yet")
        for status in [LocalEngineStatus.ready, .waiting, .unsupported] {
            XCTAssertFalse(visible(status: status), "\(status)")
        }
        XCTAssertFalse(visible(status: nil), "status not read yet")
        XCTAssertFalse(visible(engineInstalled: false), "the placeholder engine")
        XCTAssertFalse(visible(dismissed: true), "Not now")
        XCTAssertFalse(visible(capturing: true), "a capture is running")
    }

    /// The banner, with its count, is the one prompt once a recording is waiting for the model.
    func testTheCardStepsAsideForTheBannerOfWaitingRecordings() {
        XCTAssertFalse(visible(waiting: true))
    }

    func testTheProgressIsAWholePercentage() {
        AppLanguage.current = .en
        XCTAssertEqual(ModelDownload.progressText(nil), "Downloading model…")
        XCTAssertEqual(ModelDownload.progressText(0), "Downloading model… 0%")
        XCTAssertEqual(ModelDownload.progressText(0.425), "Downloading model… 42%")
        XCTAssertEqual(ModelDownload.progressText(0.999), "Downloading model… 99%")
        XCTAssertEqual(ModelDownload.progressText(1), "Downloading model… 100%")
        XCTAssertEqual(ModelDownload.progressText(1.4), "Downloading model… 100%")
        XCTAssertEqual(ModelDownload.progressText(-0.2), "Downloading model… 0%")
        AppLanguage.current = .ko
        XCTAssertEqual(ModelDownload.progressText(0.07), "모델 다운로드 중… 7%")
    }

    /// The cross-shell dictionary's wording, in both languages (Apple's body names no size).
    func testTheCardSaysTheDictionaryWording() {
        let texts: [(en: String, ko: String)] = [
            ("Transcribe on this device", "이 기기에서 전사하기"),
            (
                "On-device transcription needs Apple’s speech recognition model.",
                "기기 내 전사에는 Apple 음성 인식 모델이 필요합니다."
            ),
            ("Not now", "나중에"),
            ("Download model", "모델 다운로드"),
            // Never the bare "Cancel", which the form's own Cancel already says.
            ("Cancel download", "다운로드 취소"),
            ("Waiting for speech model", "음성 모델 대기"),
        ]
        for text in texts {
            AppLanguage.current = .en
            XCTAssertEqual(RecKitStrings.localized(text.en), text.en)
            AppLanguage.current = .ko
            XCTAssertEqual(RecKitStrings.localized(text.en), text.ko)
        }
    }

    private func visible(
        mode: TranscriptionMode? = .local,
        engineInstalled: Bool = true,
        status: LocalEngineStatus? = .modelRequired,
        dismissed: Bool = false,
        capturing: Bool = false,
        waiting: Bool = false
    ) -> Bool {
        ModelPrompt.visible(
            mode: mode, engineInstalled: engineInstalled, status: status,
            dismissed: dismissed, capturing: capturing, waiting: waiting
        )
    }
}

/// The one download controller, over a real core.
@MainActor
final class ModelDownloadTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    private func core() async throws -> ReclyCore_ {
        try await CoreBridge.make(
            platform: .macos, deviceName: "Test Mac",
            dataDirectory: directory, databaseName: "model.db", secureStore: InMemorySecureStore(),
            transcriptionPolicy: TranscriptionPolicy(region: nil)
        ).core
    }

    /// docs/05: "녹음 시작·진행·종료 중에는 모델 다운로드를 누를 수 없다" — wherever it is pressed.
    func testNothingDownloadsWhileACaptureIsRunning() async throws {
        let download = ModelDownload(core: try await core())
        download.capturing = true
        download.start()
        XCTAssertFalse(download.downloading)
    }

    func testASettingOtherThanOnDeviceNeverPrompts() async throws {
        let core = try await core()
        let state = try await core.initializeProcessing()
        let draft = ProcessingDraft.companion.from(settings: state.document.settings)
        draft.mode = .off
        let saved = try await core.processingSettings.save(settings: draft.settings(), expectedRevision: state.document.revision)
        XCTAssertTrue(saved is ProcessingSaveResultSaved)

        let download = ModelDownload(core: core)
        await download.refresh()
        XCTAssertEqual(download.savedMode, .off)
        XCTAssertNil(download.savedStatus)
        XCTAssertFalse(download.promptVisible(dismissed: false, waiting: false))
    }
}

/// docs/09 "모든 상태는 색 + 텍스트": red means failed. A job that is only waiting — consent, sign-in,
/// Drive space, the speech model — says so in its badge's warning tone, in the banner and in its row.
final class WaitToneTests: XCTestCase {

    func testTheWaitsAreNotFailures() {
        for reason in [AlertReason.needsConsent, .needsAuth, .needsSpace, .localModel] {
            XCTAssertTrue(reason.isWait, "\(reason)")
        }
        for reason in [AlertReason.localUnavailable, .localDiarization, .missingSecret, .authRejected, .quota] {
            XCTAssertFalse(reason.isWait, "\(reason)")
        }
    }

    func testAWaitingRowSaysItsReasonInTheWarningToneAndAFailedOneInRed() {
        let modelRequired = CoreMessage.localModelRequired.code(arg: nil, detail: nil)
        let waiting = item("Waiting for speech model", lastError: modelRequired, alert: .localModel)
        XCTAssertEqual(waiting.reasonTone, .warning)
        XCTAssertEqual(waiting.reasonTone, waiting.badge.tone, "the sentence wears its badge's tone")
        let failed = item("Failed", lastError: CoreMessage.authRejected.code(arg: nil, detail: "401"), alert: .authRejected)
        XCTAssertEqual(failed.reasonTone, .danger)
        XCTAssertEqual(item("Failed", lastError: nil, alert: nil).reasonTone, .danger)
    }

    private func item(_ state: String, lastError: String?, alert: AlertReason?) -> RecentItem {
        RecentItem(
            id: "01J9REC0000000000000000000", jobId: "01J9JOB0000000000000000000", title: "",
            startedAt: "2026-02-09T12:04:05.000Z", state: state, link: nil, lastError: lastError, alert: alert
        )
    }
}

/// The ledger's status column is measured against every code it can show, so none is shrunk.
final class LedgerCodeTests: XCTestCase {

    func testEveryCodeARowCanShowIsMeasured() {
        let states = [
            "Receiving from the watch", "Uploading on another device", "Transcription pending",
            "Transcribing on another device", "Transcribing on this device", "Recording", "Waiting",
            "Uploading", "Retry pending", "Done", "Failed", "Transfer permission needed",
            "Waiting for speech model", "Sign-in needed", "No space in Drive", "Too short", "not a state",
        ]
        for state in states {
            let code = LedgerStatus.forRecent(state: state).code
            XCTAssertTrue(LedgerStatus.ledgerCodes.contains(code), "\(state) mints \(code), which the column does not measure")
        }
        // A provider transcribing is its own code on a WAITING row (RecentItem.badge).
        XCTAssertTrue(LedgerStatus.ledgerCodes.contains("TRANSCRIBING"))
        XCTAssertTrue(LedgerStatus.ledgerCodes.contains("NEEDS_MODEL"))
    }
}

/// English counts without "(s)": the number stands after a colon, and Korean keeps its own sentence.
final class CountWordingTests: XCTestCase {

    override func tearDown() {
        AppLanguage.current = .system
        super.tearDown()
    }

    func testTheCountsReadWithoutParentheses() {
        let cases: [(key: String, en: String, ko: String)] = [
            ("alert.waiting", "Recordings waiting: 3", "녹음 3건이 기다리는 중입니다."),
            (
                "Parts not yet in Drive, deleted with it: %@",
                "Parts not yet in Drive, deleted with it: 3", "아직 Drive에 올라가지 않은 파트 3개가 함께 지워집니다."
            ),
            (
                DisconnectDevice.mac.unuploadedStay,
                "Recordings not yet in Drive, kept on this Mac: 3", "아직 Drive에 올라가지 않은 녹음 3건은 이 Mac에 남습니다."
            ),
            (
                DisconnectDevice.phone.unuploadedStay,
                "Recordings not yet in Drive, kept on this phone: 3", "아직 Drive에 올라가지 않은 녹음 3건은 이 폰에 남습니다."
            ),
            (
                DisconnectDevice.mac.deleted,
                "Disconnected. Recordings deleted from this Mac: 3", "연결을 해제했습니다 — 이 Mac의 녹음 3건을 삭제했습니다."
            ),
            (
                DisconnectDevice.phone.deleted,
                "Disconnected. Recordings deleted from this phone: 3", "연결을 해제했습니다 — 이 폰의 녹음 3건을 삭제했습니다."
            ),
        ]
        for text in cases {
            AppLanguage.current = .en
            XCTAssertEqual(RecKitStrings.localized(text.key, "3"), text.en)
            AppLanguage.current = .ko
            XCTAssertEqual(RecKitStrings.localized(text.key, "3"), text.ko)
        }
    }
}
