import ReclyCore
import XCTest
@testable import RecKit

/// docs/09 screen principle 1: what the line under the record control says once a stop has been saved, and the
/// recorder's own words while it works — the same on every shell, so the iPhone, the Mac and the watch read them
/// off RecKit rather than each saying its own.
final class StopLineTests: XCTestCase {

    override func setUp() {
        super.setUp()
        AppLanguage.current = .en
    }

    override func tearDown() {
        AppLanguage.current = .system
        super.tearDown()
    }

    /// Android's four sentences (`EnqueueResult.describe`), and nothing at all for a recording queued as asked.
    func testTheQueuesAnswerIsSaidInTheWordsOfThePersonWhoStopped() {
        XCTAssertNil(StopLine.message(EnqueueResultEnqueued(jobId: "job")))
        XCTAssertEqual(StopLine.message(EnqueueResultSkippedShort(jobId: "job"))?.text, "Too short — skipped")
        XCTAssertEqual(StopLine.message(EnqueueResultNoWorkflow.shared)?.text, "Review recording settings to continue")
        XCTAssertEqual(StopLine.message(EnqueueResultPartsPurged.shared)?.text, "The audio has already been cleaned up")
        XCTAssertEqual(StopLine.message(EnqueueResultAlreadySynced.shared)?.text, "Already handled")
        XCTAssertEqual(StopLine.message(EnqueueResultAlreadyDone(jobId: "job"))?.text, "Already handled")
    }

    /// Every sentence the line can say is in RecKit's catalog in both languages — a key left standing in English
    /// on a Korean screen is the bug this catches.
    func testEverySentenceHasItsTranslation() {
        let messages: [UiMessage] = [
            StopLine.message(EnqueueResultSkippedShort(jobId: "job"))!,
            StopLine.message(EnqueueResultNoWorkflow.shared)!,
            StopLine.message(EnqueueResultPartsPurged.shared)!,
            StopLine.message(EnqueueResultAlreadySynced.shared)!,
            StopLine.enqueueFailed, StopLine.titleTooLate, StopLine.recordingFailed,
        ]
        for message in messages {
            AppLanguage.current = .en
            let english = message.text
            AppLanguage.current = .ko
            XCTAssertNotEqual(message.text, english, "no Korean for \(english)")
        }
    }

    /// The phones' words for a recorder at work, on the Mac's and the watch's line too: `Starting` — not the
    /// launch-time `Opening` — and `Saving`.
    func testTheRecorderAtWorkIsSaidInThePhonesWords() {
        XCTAssertEqual(RecorderStatusLine.text(state: .starting, note: "Waiting"), "Starting")
        XCTAssertEqual(RecorderStatusLine.text(state: .stopping, note: "Waiting"), "Saving")
        XCTAssertEqual(RecorderStatusLine.text(state: .recording(recordingId: "rec"), note: "Waiting"), "Recording")
        XCTAssertEqual(
            RecorderStatusLine.text(state: .idle, note: "Waiting", message: StopLine.message(EnqueueResultSkippedShort(jobId: "job"))),
            "Too short — skipped",
            "the queue's answer outranks the note while the recorder is idle"
        )
    }

    /// docs/08 "Polling · status": a job waiting out a backoff comes back by itself — a wait, in the warning tone,
    /// not a failure in red.
    func testARetryThePendingQueueWillMakeIsWarningTonedNotRed() {
        XCTAssertEqual(item(state: "Retry pending").reasonTone, .warning)
        XCTAssertEqual(item(state: "Failed").reasonTone, .danger)
    }

    /// The Mac's Details window lists a page at a time: the header counts every recording, and the waiting and
    /// failed ones it has read.
    func testTheHeaderCountsEveryRecordingWhereTheListIsAPage() {
        let items = [item(state: "Waiting"), item(state: "Failed"), item(state: "Done")]
        XCTAssertEqual(Recents.summary(items), "3 · 1 waiting · 1 failed")
        XCTAssertEqual(Recents.summary(items, count: 40), "40 · 1 waiting · 1 failed")
    }

    /// docs/08 "Exports": a take still being written has no whole audio file, whatever else is true of it.
    @MainActor
    func testTheAudioCannotBeExportedWhileItIsStillBeingRecorded() {
        XCTAssertEqual(RecordingDetailModel.audioExportReason(writing: true, unavailable: false), "Still recording")
        XCTAssertEqual(RecordingDetailModel.audioExportReason(writing: true, unavailable: true), "Still recording")
        XCTAssertEqual(RecordingDetailModel.audioExportReason(writing: false, unavailable: true), "No audio on this device")
        XCTAssertNil(RecordingDetailModel.audioExportReason(writing: false, unavailable: false))
    }

    private func item(state: String) -> RecentItem {
        RecentItem(
            id: UUID().uuidString,
            jobId: "01J9JOB0000000000000000000",
            title: "",
            startedAt: "2026-02-09T12:04:05.000Z",
            state: state,
            link: nil,
            lastError: "STEP_FAILED"
        )
    }
}
