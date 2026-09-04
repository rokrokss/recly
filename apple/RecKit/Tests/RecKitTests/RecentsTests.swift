import Foundation
import RecKitTestSupport
import ReclyCore
import SQLite3
import XCTest
@testable import RecKit

/// The Drive link a finished upload leaves behind, read off step runs the core itself decoded.
///
/// `Recly-2026-09-02-214437.ips`: [Recents.driveLink] used to subscript `StepRun.output`, and
/// reading that property from Swift force-bridges the whole `JsonObject` into a typed Swift
/// dictionary — a `drive.upload` output's `files` crosses as an `NSArray`, fails the cast to
/// `JsonElement`, and aborts the process in `swift_dynamicCastFailure`. So the fixture here is not a
/// hand-made `StepRun`: Swift cannot build one (there is no way to make a `JsonPrimitive` on this
/// side of the bridge, which is the same fact stated the other way round). The output is written to
/// the core's own database as the text a real upload leaves there, and read back through
/// `JobService.steps` — the production path, with a real `JsonArray` in it.
final class RecentsTests: XCTestCase {
    private var dataDirectory: URL!
    private let databaseName = "recents-tests.db"

    override func setUpWithError() throws {
        dataDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("RecentsTests-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: dataDirectory)
    }

    /// The one an upload writes (`DriveUploadRunner`): the folder, the path, and the `files` array
    /// that used to end the process.
    private let uploadOutput = """
        {"folderId":"1AbCdEf",\
        "folderWebViewLink":"https://drive.google.com/drive/folders/1AbCdEf",\
        "path":"Recly/Meeting/2026-09-02_1400",\
        "files":[{"part":1,"track":"mic","name":"p001_mic.m4a","bytes":1234567,\
        "sha256":"9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",\
        "fileId":"1FileId","webViewLink":"https://drive.google.com/file/d/1FileId/view"}]}
        """

    /// A `transcribe` output: no link, and arrays of its own. The step before the upload in the
    /// list, so the link is only found by walking past it.
    private let transcribeOutput = """
        {"provider":"assemblyai","segments":[{"start":0.0,"end":1.5,"speaker":"A"}]}
        """

    func testTheUploadFolderLinkIsReadWithoutBridgingTheOutput() async throws {
        let bridge = try await makeBridge()
        try insert(step: "stt", ordinal: 0, output: transcribeOutput)
        try insert(step: "upload", ordinal: 1, output: uploadOutput)

        let steps = try await bridge.core.jobs.steps(jobId: Self.jobId)

        XCTAssertEqual(steps.count, 2)
        XCTAssertEqual(
            Recents.driveLink(steps),
            URL(string: "https://drive.google.com/drive/folders/1AbCdEf")
        )
    }

    /// An upload that has not run yet, and a step whose output is all arrays: nothing to open, and
    /// still nothing to trap on.
    func testAJobWithNoUploadedFolderHasNoLink() async throws {
        let bridge = try await makeBridge()
        try insert(step: "stt", ordinal: 0, output: transcribeOutput)

        let steps = try await bridge.core.jobs.steps(jobId: Self.jobId)

        XCTAssertEqual(steps.count, 1)
        XCTAssertNil(Recents.driveLink(steps))
    }

    // MARK: - Pieces

    private static let jobId = "01J9JOB0000000000000000000"

    private func makeBridge() async throws -> CoreBridge {
        try await CoreBridge.make(
            appVersion: "0.0.0-test",
            deviceName: "RecKitTests",
            dataDirectory: dataDirectory,
            databaseName: databaseName,
            secureStore: InMemorySecureStore()
        )
    }

    /// Straight into the core's table, because the row this test needs is one Swift has no way to
    /// make: `StepRun.output` is a Kotlin `JsonObject`, and nothing on this side can build one.
    /// The core decodes the text on the way out, so what the assertions see is a real `JsonObject`.
    private func insert(step: String, ordinal: Int, output: String) throws {
        var handle: OpaquePointer?
        let path = dataDirectory.appendingPathComponent(databaseName).path
        XCTAssertEqual(sqlite3_open(path, &handle), SQLITE_OK, "opening \(path)")
        defer { sqlite3_close(handle) }
        sqlite3_busy_timeout(handle, 5_000)
        let sql = """
            INSERT INTO step_run
              (id, job_id, step_id, ordinal, status, attempts, state_json, output_json)
            VALUES
              ('s\(ordinal)', '\(Self.jobId)', '\(step)', \(ordinal), 'SUCCEEDED', 0, NULL, '\(output)');
            """
        XCTAssertEqual(
            sqlite3_exec(handle, sql, nil, nil, nil),
            SQLITE_OK,
            String(cString: sqlite3_errmsg(handle))
        )
    }
}

/// docs/03 "다른 기기의 녹음": a recording another device made and uploaded, adopted from the folder
/// it left in Drive. There is no job here and no local audio, so the row cannot be read the way one
/// this device recorded is — and the ledger says whose it is rather than what this device failed to
/// do.
final class RecentsRemoteTests: XCTestCase {
    private var dataDirectory: URL!

    override func setUpWithError() throws {
        dataDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("RecentsRemoteTests-\(UUID().uuidString)", isDirectory: true)
        // The header below is a sentence, and this test is about the counting rather than the words.
        AppLanguage.current = .en
    }

    override func tearDownWithError() throws {
        AppLanguage.current = .system
        try? FileManager.default.removeItem(at: dataDirectory)
    }

    func testAnAdoptedRecordingReadsAsAFinishedRow() async throws {
        let bridge = try await CoreBridge.make(
            appVersion: "0.0.0-test",
            deviceName: "RecKitTests",
            dataDirectory: dataDirectory,
            databaseName: "recents-remote-tests.db",
            secureStore: InMemorySecureStore()
        )
        let adopted = try await bridge.core.recordings.adopt(
            meta: Self.meta(startedAt: bridge.deps.clock.now().isoUtc),
            folderId: "1FolderId",
            fileIds: [:]
        )
        XCTAssertTrue(adopted.boolValue, "the row was not adopted")

        let items = try await Recents.load(core: bridge.core)
        let item = try XCTUnwrap(items.first)

        // The row is the recording's own flag; nothing about the state says where it was recorded.
        XCTAssertTrue(item.remote)
        XCTAssertEqual(item.state, "Done")
        XCTAssertEqual(LedgerStatus.forRecent(state: item.state).code, "DONE")
        // No job of its own, so there is nothing to retry and no upload step to have left a link.
        XCTAssertNil(item.jobId)
        XCTAssertFalse(item.canRetry)
        XCTAssertNil(item.link)
        // A finished row is neither queued nor failed, so the header counts it as neither.
        XCTAssertEqual(Recents.summary([item]), "1 · 0 waiting · 0 failed")
    }

    /// The meta another device wrote beside its parts in Drive — finalized, because a recording that
    /// is still running is never adopted.
    private static func meta(startedAt: String) -> RecordingMeta {
        RecordingMeta(
            schema: 1,
            recordingId: "01J9REC0000000000000000000",
            source: Source.desktop,
            platform: Platform.macos,
            deviceId: "01J9DEV0000000000000000000",
            deviceName: "Another Mac",
            workflowId: nil,
            title: nil,
            startedAt: startedAt,
            endedAt: startedAt,
            durationSec: 1,
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
            status: RecordingStatus.finalized
        )
    }
}

/// What the dashboard's State node reads off the ledger: whether anything is running right now.
final class RecentsUploadingTests: XCTestCase {

    /// `RUNNING` is the one state [Recents.stateLabel] writes as `"Uploading"`, and one row of it
    /// among finished ones is enough.
    func testARunningJobAnywhereInTheLedgerIsUploading() {
        XCTAssertTrue(Recents.uploading([item(state: "Done"), item(state: "Uploading")]))
    }

    /// Nothing to say when there is nothing there yet.
    func testAnEmptyLedgerIsNotUploading() {
        XCTAssertFalse(Recents.uploading([]))
    }

    /// A queued job has not started and a finished one has stopped: neither is work in flight.
    func testQueuedAndFinishedRowsAreNotUploading() {
        XCTAssertFalse(Recents.uploading([item(state: "Waiting"), item(state: "Done")]))
    }

    private func item(state: String) -> RecentItem {
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
}

/// docs/08 "폴링 · 상태": a job waiting out a backoff says *when* it comes back — "재시도 대기" on
/// its own reads like "stuck". The rule is Android's (`JobsScreen.remaining`) down to its
/// truncation, because the two ledgers are one ledger seen on two devices.
final class RecentItemStateLabelTests: XCTestCase {

    /// Not a real wall clock: the label is a function of the two instants and nothing else.
    private static let now = Date(timeIntervalSince1970: 1_770_000_000)

    override func setUp() {
        super.setUp()
        AppLanguage.current = .en
    }

    override func tearDown() {
        AppLanguage.current = .system
        super.tearDown()
    }

    /// The unit is the largest one the wait has whole: seconds under a minute, minutes under an
    /// hour, hours after that — and each of them truncated, so 90 seconds is one minute rather than
    /// a "2 min" the row would then have to take back.
    func testTheWaitIsSaidInTheLargestWholeUnitAndTruncated() {
        let cases: [(TimeInterval, String)] = [
            (5, "in 5 s"),
            (59, "in 59 s"),
            (60, "in 1 min"),
            (90, "in 1 min"),
            (3599, "in 59 min"),
            (3600, "in 1 h"),
            (7300, "in 2 h"),
        ]
        for (seconds, expected) in cases {
            XCTAssertEqual(
                RecentItem.remaining(until: Self.now.addingTimeInterval(seconds), from: Self.now),
                expected,
                "\(seconds) s"
            )
        }
    }

    /// A `next_run_at` the queue has not caught up with yet is "soon", not a negative number: the
    /// job is due, and the runner is the only thing that knows why it has not started.
    func testAWaitThatHasRunOutIsSoon() {
        for seconds in [0.0, -1, -600] {
            XCTAssertEqual(
                RecentItem.remaining(until: Self.now.addingTimeInterval(seconds), from: Self.now),
                "soon",
                "\(seconds) s"
            )
        }
    }

    /// The whole sentence, as the row draws it — counted from the moment the row is drawn, so the
    /// wait here is mid-minute rather than on the boundary a truncation would drop below.
    func testARetryPendingRowSaysWhenItComesBack() {
        let label = item(state: "Retry pending", nextRunAt: Date().addingTimeInterval(200)).stateLabel

        XCTAssertEqual(label, "Retry pending · in 3 min")
    }

    /// A `WAITING` job the store never dated says what it can, which is the state and no more.
    func testARetryPendingRowWithNoNextRunSaysOnlyTheState() {
        XCTAssertEqual(item(state: "Retry pending").stateLabel, "Retry pending")
    }

    /// docs/08 "폴링 · 상태": a transcription in flight is parked on someone else rather than on a
    /// timer, so the elapsed sentence wins over the countdown even where the job has both.
    func testATranscriptionInFlightStillSaysHowLongItHasBeen() {
        let label = item(
            state: "Retry pending",
            waitingMinutes: 4,
            nextRunAt: Date().addingTimeInterval(180)
        ).stateLabel

        XCTAssertEqual(label, "Waiting for the transcription result — 4 min elapsed")
    }

    /// Every other state is a word of its own, and a `next_run_at` left on the row does not turn it
    /// into a countdown.
    func testNoOtherStateCountsDown() {
        XCTAssertEqual(
            item(state: "Uploading", nextRunAt: Date().addingTimeInterval(180)).stateLabel,
            "Uploading"
        )
    }

    private func item(
        state: String,
        waitingMinutes: Int? = nil,
        nextRunAt: Date? = nil
    ) -> RecentItem {
        RecentItem(
            id: "01J9REC0000000000000000000",
            jobId: "01J9JOB0000000000000000000",
            title: "",
            startedAt: "2026-02-09T12:04:05.000Z",
            state: state,
            link: nil,
            lastError: nil,
            waitingMinutes: waitingMinutes,
            nextRunAt: nextRunAt
        )
    }
}

/// docs/09 화면 원칙 2: the ledger's header counts what is under it — the Android ledger's own rule
/// (`JobsScreen.waiting` · `JobsScreen.failing`), so a user with both devices reads the same line.
final class RecentsSummaryTests: XCTestCase {

    override func setUp() {
        super.setUp()
        AppLanguage.current = .en
    }

    override func tearDown() {
        AppLanguage.current = .system
        super.tearDown()
    }

    /// A queued job, one waiting out a backoff and a recording no workflow picked up are all
    /// waiting; a failure, a sign-in, Drive's space and a recording too short to keep have all
    /// stopped. What is moving or finished is counted in the total and nowhere else.
    func testTheHeaderCountsWhatIsWaitingAndWhatHasStopped() {
        let items = [
            "Waiting", "Retry pending", "No workflow",
            "Failed", "Sign-in needed", "No space in Drive", "Too short",
            "Uploading", "Done", "Recording",
        ].map(item(state:))

        XCTAssertEqual(Recents.summary(items), "10 · 3 waiting · 4 failed")
    }

    /// Nothing recorded yet: the zeros are still said, because a header that changes shape as rows
    /// arrive is a header that has to be read twice.
    func testAnEmptyLedgerCountsZeroOfEach() {
        XCTAssertEqual(Recents.summary([]), "0 · 0 waiting · 0 failed")
    }

    private func item(state: String) -> RecentItem {
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
}

/// Which of a row's buttons are worth drawing at all. docs/10: a retry is for a job that has
/// stopped — everything else is either already moving or coming back on its own timer, and a button
/// that changes nothing is a button that lies about what it does.
final class RecentItemActionsTests: XCTestCase {

    /// The states [Recents.stateLabel] writes for `FAILED`, `NEEDS_AUTH` and `NEEDS_SPACE`.
    func testARetryIsOfferedOnlyForAJobThatHasStopped() {
        for state in ["Failed", "Sign-in needed", "No space in Drive"] {
            XCTAssertTrue(item(state: state).canRetry, "\(state) has nothing to retry it with")
        }
    }

    /// `WAITING` · `PENDING` · `RUNNING` · `DONE` · `SKIPPED_SHORT`, and a recording still being
    /// written to.
    func testNothingElseOffersARetry() {
        for state in ["Retry pending", "Waiting", "Uploading", "Done", "Too short", "Recording"] {
            XCTAssertFalse(item(state: state).canRetry, "\(state) offers a retry")
        }
    }

    /// `NO_WORKFLOW`: there is no job, so `jobs.retry` has nothing to take.
    func testARecordingWithNoJobOffersNoRetry() {
        XCTAssertFalse(item(state: "No workflow", jobId: nil).canRetry)
        XCTAssertFalse(item(state: "Failed", jobId: nil).canRetry)
    }

    private func item(state: String, jobId: String? = "01J9JOB0000000000000000000") -> RecentItem {
        RecentItem(
            id: "01J9REC0000000000000000000",
            jobId: jobId,
            title: "",
            startedAt: "2026-02-09T12:04:05.000Z",
            state: state,
            link: nil,
            lastError: nil
        )
    }
}
