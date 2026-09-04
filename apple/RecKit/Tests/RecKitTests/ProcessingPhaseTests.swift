import XCTest
@testable import RecKit

/// docs/09 트렌드 2: the button says what the operation did, not what the clock did. The phase is
/// driven by the caller's real outcome — so a save that came back with validation errors never
/// wears a ✓, and work that is still running never stops looking like it.
final class ProcessingPhaseTests: XCTestCase {

    private func phase(_ succeeded: Bool?, work: Double, elapsed: Double) -> ProcessingPhase {
        Processing.phase(succeeded: succeeded, workSec: work, elapsedSec: elapsed)
    }

    func testInstantWorkStillShowsTheProcessingStateForTheMinimum() {
        XCTAssertEqual(Processing.hold(workSec: 0), 0.4, accuracy: 1e-9)
        XCTAssertEqual(Processing.hold(workSec: 0.1), 0.3, accuracy: 1e-9)
        XCTAssertEqual(Processing.hold(workSec: 0.399), 0.001, accuracy: 1e-9)
    }

    func testWorkThatTookTheMinimumOrLongerIsNotPadded() {
        XCTAssertEqual(Processing.hold(workSec: 0.4), 0)
        XCTAssertEqual(Processing.hold(workSec: 5), 0)
    }

    /// docs/09 "모션": at least 400 ms of processing, and the whole window closes at 800 ms.
    func testTheWholeWindowIsAtMostTheMaximum() {
        for work in [0.0, 0.05, 0.2, 0.399, 0.4] {
            let shown = work + Processing.hold(workSec: work)
            XCTAssertGreaterThanOrEqual(shown, Motion.processingMin - 1e-9, "\(work)s was shown for \(shown)s")
            let total = shown + Processing.doneBadge(workSec: work)
            XCTAssertEqual(total, Motion.processingMax, accuracy: 1e-9, "\(work)s did not fill the window")
        }
    }

    func testWorkPastTheWindowStillGetsAVisibleCompletionBadge() {
        XCTAssertEqual(Processing.doneBadge(workSec: 5), Motion.badgeFade)
    }

    /// docs/09 "모션" asks for "즉시 전환 + 텍스트 상태만" — instant transitions *and* the text
    /// state. Reduce motion takes the fade and leaves the labels, so the two windows are the same
    /// length as they are for everybody else: a user who has turned animations off is the one with
    /// nothing else to tell them the tap was heard.
    func testReduceMotionKeepsBothWindowsAndOnlyDropsTheFade() {
        XCTAssertEqual(Processing.hold(workSec: 0), Motion.processingMin, accuracy: 1e-9)
        XCTAssertEqual(Processing.doneBadge(workSec: 0), Motion.processingMax - Motion.processingMin, accuracy: 1e-9)
        XCTAssertNil(Motion.badgeAnimation(reduceMotion: true))
        XCTAssertNotNil(Motion.badgeAnimation(reduceMotion: false))
    }

    func testASuccessThatCameBackEarlyStillShowsTheProcessingStateUntilTheMinimum() {
        XCTAssertEqual(phase(true, work: 0.1, elapsed: 0.1), .processing)
        XCTAssertEqual(phase(true, work: 0.1, elapsed: 0.399), .processing)
        XCTAssertEqual(phase(true, work: 0.1, elapsed: 0.4), .done)
        XCTAssertEqual(phase(true, work: 0.1, elapsed: 0.799), .done)
        XCTAssertEqual(phase(true, work: 0.1, elapsed: 0.8), .idle)
    }

    func testAFailureHoldsTheProcessingStateOutAndThenShowsNothing() {
        XCTAssertEqual(phase(false, work: 0.1, elapsed: 0.399), .processing)
        XCTAssertEqual(phase(false, work: 0.1, elapsed: 0.4), .idle)
        // The screen owns the error message; the button owns no badge for it, ever.
        let everyMoment = stride(from: 0.0, through: 2.0, by: 0.01)
        XCTAssertFalse(everyMoment.contains { phase(false, work: 0.1, elapsed: $0) == .done })
    }

    func testWorkThatIsStillRunningKeepsTheProcessingStatePastTheWholeWindow() {
        XCTAssertEqual(phase(nil, work: 0.8, elapsed: 0.8), .processing)
        XCTAssertEqual(phase(nil, work: 30, elapsed: 30), .processing)
    }

    func testWorkThatOverranTheWindowIsNotHeldAndStillGetsItsBadge() {
        XCTAssertEqual(phase(true, work: 5, elapsed: 5), .done)
        XCTAssertEqual(phase(true, work: 5, elapsed: 5.149), .done)
        XCTAssertEqual(phase(true, work: 5, elapsed: 5.15), .idle)
    }

    /// docs/09 "접근성": what reduce motion switches off is the animation, and the three labels are
    /// not one — the tap still shows "…" and a success still shows its ✓ for as long as it would
    /// have. Only the transition between them becomes instant.
    func testReduceMotionKeepsTheTextStates() {
        XCTAssertEqual(phase(true, work: 0, elapsed: 0), .processing)
        XCTAssertEqual(phase(true, work: 0, elapsed: Motion.processingMin), .done)
        XCTAssertEqual(phase(false, work: 0, elapsed: 0), .processing)
        XCTAssertNil(Motion.standardAnimation(reduceMotion: true))
        XCTAssertNotNil(Motion.standardAnimation(reduceMotion: false))
    }
}

/// docs/09 화면 원칙 2: every row state has a code and a tone, the code is the word the core and the
/// logs already use, and no two states look the same to someone who cannot tell the tones apart.
final class LedgerStatusTests: XCTestCase {

    /// Every key `Recents.stateLabel` can produce — exhaustive over `Job.Status` plus the two the
    /// join adds, so a state the core grows without a code here is a failure rather than an
    /// `UNKNOWN` badge nobody notices.
    private let states = [
        "Recording", "No workflow", "Waiting", "Uploading",
        "Retry pending", "Done", "Failed", "Sign-in needed", "No space in Drive", "Too short",
    ]

    func testEveryStateHasACodeAndATone() {
        for state in states {
            let badge = LedgerStatus.forRecent(state: state)
            XCTAssertFalse(badge.code.isEmpty, "\(state) has no code")
            XCTAssertEqual(badge.code, badge.code.uppercased(), "\(state)'s code is not a code")
            XCTAssertNotEqual(badge.code, "UNKNOWN", "\(state) has no badge of its own")
        }
    }

    func testNoTwoStatesShareACode() {
        let codes = states.map { LedgerStatus.forRecent(state: $0).code }
        XCTAssertEqual(codes.count, Set(codes).count, "two states are told apart only by colour")
    }

    func testTheToneSaysWhatKindOfNewsItIs() {
        XCTAssertEqual(LedgerStatus.forRecent(state: "Done").tone, .success)
        XCTAssertEqual(LedgerStatus.forRecent(state: "Failed").tone, .danger)
        XCTAssertEqual(LedgerStatus.forRecent(state: "Recording").tone, .danger)
        XCTAssertEqual(LedgerStatus.forRecent(state: "Uploading").tone, .accent)
        // Something the user has to act on, but nothing is lost yet.
        XCTAssertEqual(LedgerStatus.forRecent(state: "Sign-in needed").tone, .warning)
        XCTAssertEqual(LedgerStatus.forRecent(state: "Retry pending").tone, .warning)
        // docs/10 "Drive 용량 초과": parked rather than failed, and the code says which.
        XCTAssertEqual(LedgerStatus.forRecent(state: "No space in Drive").code, "NO_SPACE")
        XCTAssertEqual(LedgerStatus.forRecent(state: "No space in Drive").tone, .warning)
        // Nothing is wrong and nothing is happening.
        XCTAssertEqual(LedgerStatus.forRecent(state: "Waiting").tone, .neutral)
        XCTAssertEqual(LedgerStatus.forRecent(state: "No workflow").tone, .neutral)
        XCTAssertEqual(LedgerStatus.forRecent(state: "Too short").tone, .neutral)
    }

    /// A `last_error` an older build wrote, or a state a future core grows: the row still draws a
    /// badge rather than an empty column.
    func testAStateWithNoCodeStillDrawsSomething() {
        XCTAssertEqual(LedgerStatus.forRecent(state: "Something new").code, "UNKNOWN")
    }
}
