import XCTest
@testable import RecKit

/// docs/12 "Consent reminder · jurisdiction notices": the reminder gates the recording. The Mac's panel is drawn in
/// the app (2026-10-15, `BlueprintPanel` in place of `NSAlert`); what its two answers do is this, and it must not
/// change with the drawing.
final class ConsentGateTests: XCTestCase {

    func testIToldThemStartsTheRecording() {
        XCTAssertTrue(admits(Answer(confirmed: true, suppress: false)).starts)
    }

    func testCancelLeavesTheRecordingUnstarted() {
        XCTAssertFalse(admits(Answer(confirmed: false, suppress: false)).starts)
    }

    /// A panel ended with no button pressed is the same answer as Cancel.
    func testNoAnswerIsCancel() {
        XCTAssertFalse(admits(nil).starts)
    }

    /// `Do not ask again` turns the reminder off whichever button was pressed — and only starts what was confirmed.
    func testDoNotAskAgainAppliesToEitherAnswer() {
        let confirmed = admits(Answer(confirmed: true, suppress: true))
        XCTAssertTrue(confirmed.starts)
        XCTAssertTrue(confirmed.turnedOff)

        let cancelled = admits(Answer(confirmed: false, suppress: true))
        XCTAssertFalse(cancelled.starts)
        XCTAssertTrue(cancelled.turnedOff)

        XCTAssertFalse(admits(Answer(confirmed: true, suppress: false)).turnedOff)
    }

    /// With the reminder off — or a microphone memo, which has no participants — nothing is asked and the recording
    /// starts.
    func testNothingIsAskedWhileTheReminderIsNotDue() {
        var asked = false
        let starts = ConsentGate.admits(due: false, ask: { asked = true; return nil }, turnOff: {})
        XCTAssertTrue(starts)
        XCTAssertFalse(asked)
    }

    private typealias Answer = ConsentGate.Answer

    private func admits(_ answer: Answer?) -> (starts: Bool, turnedOff: Bool) {
        var turnedOff = false
        let starts = ConsentGate.admits(due: true, ask: { answer }, turnOff: { turnedOff = true })
        return (starts, turnedOff)
    }
}
