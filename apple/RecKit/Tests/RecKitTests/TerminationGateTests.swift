import XCTest
@testable import RecKit

/// The quit decision, which is the half of `applicationShouldTerminate` a test can reach: AppKit
/// owns when the delegate runs and what it does with the answer, but the rule it asks for is here.
@MainActor
final class TerminationGateTests: XCTestCase {
    /// Nothing is being recorded, so there is nothing to hold the quit open for — and nothing to
    /// stop either. A gate that stopped anyway would finalize a recording that does not exist.
    func testAQuitWithNothingRunningGoesNow() async {
        var stops = 0
        let gate = TerminationGate { stops += 1 }

        XCTAssertEqual(gate.decide(.idle, then: {}), .now)
        XCTAssertEqual(stops, 0)
    }

    /// `⌘Q` mid-recording is otherwise the crash case entered on purpose: the row still says
    /// `recording` and the open segment never gets its trailing MPEG-4 atoms. So the quit waits,
    /// and it is the stop that lets it go.
    func testAQuitWhileRecordingStopsFirstAndRepliesAfterwards() async {
        let stopped = expectation(description: "the recording is finished")
        let replied = expectation(description: "the quit is let go")
        var stops = 0
        let gate = TerminationGate {
            stops += 1
            stopped.fulfill()
        }

        let decision = gate.decide(.recording(recordingId: "01ABC", workflowId: nil)) { replied.fulfill() }

        XCTAssertEqual(decision, .later)
        await fulfillment(of: [stopped, replied], timeout: 5, enforceOrder: true)
        XCTAssertEqual(stops, 1)
    }

    /// The two states either side of it. `starting` has a recording the user cannot see yet — the
    /// session parks the stop and serves it the moment there is one — and `stopping` is a finalize
    /// already in flight, which the quit must not exit out from under.
    func testAQuitWhileStartingOrStoppingWaitsToo() async {
        for state in [RecorderState.starting, .stopping] {
            let gate = TerminationGate {}
            XCTAssertEqual(gate.decide(state, then: {}), .later, "\(state)")
        }
    }

    /// Sol M4-L2 (round 3): `⌘Q` after the menu's own Stop. The session answers a second stop
    /// `.notRecording` at once, and a quit that took that answer would let the process go while the
    /// first stop is still closing the segment, finalizing and queueing. So the quit awaits the
    /// finish already in flight — and starts no second one.
    func testAQuitDuringAStopAwaitsThatStopInsteadOfStartingAnother() async {
        let finished = expectation(description: "the running stop is let through")
        let replied = expectation(description: "the quit is let go")
        var stops = 0
        var stopEnded = false
        var repliedEarly = false
        let gate = TerminationGate { stops += 1 }
        var release: CheckedContinuation<Void, Never>?
        gate.finish {
            await withCheckedContinuation { release = $0 }
            stopEnded = true
            finished.fulfill()
        }

        let decision = gate.decide(.stopping) {
            if !stopEnded { repliedEarly = true }
            replied.fulfill()
        }

        XCTAssertEqual(decision, .later)
        while release == nil { await Task.yield() }
        XCTAssertEqual(stops, 0, "the stop in flight is the one the quit waits on")
        release?.resume()
        await fulfillment(of: [finished, replied], timeout: 5, enforceOrder: true)
        XCTAssertFalse(repliedEarly, "the reply comes after the stop, not alongside it")
        XCTAssertEqual(stops, 0)
    }

    /// The session says `.idle` the moment the meta is closed; the title prompt and the enqueue
    /// come after that, inside the same finish. A quit that read `.idle` as "nothing to lose"
    /// would exit between the two and leave a finalized recording with no job.
    func testAQuitAfterTheSessionWentIdleStillWaitsForTheFinishToEnd() async {
        let replied = expectation(description: "the quit is let go")
        let gate = TerminationGate {}
        var release: CheckedContinuation<Void, Never>?
        gate.finish { await withCheckedContinuation { release = $0 } }
        while release == nil { await Task.yield() }

        XCTAssertEqual(gate.decide(.idle) { replied.fulfill() }, .later, "idle, but the finish is still running")
        release?.resume()
        await fulfillment(of: [replied], timeout: 5)

        XCTAssertEqual(gate.decide(.idle, then: {}), .now, "and once it has ended there is nothing to wait for")
    }

    /// `⌘Q` twice is one keystroke repeated, not two quits. Finalizing twice would hand the same
    /// recording to the queue a second time; the second answer is still `.later`, because the first
    /// one's reply is what ends the process for both of them.
    func testASecondQuitDoesNotStopASecondTime() async {
        let stopped = expectation(description: "the recording is finished")
        var stops = 0
        let gate = TerminationGate {
            stops += 1
            stopped.fulfill()
        }

        let first = gate.decide(.recording(recordingId: "01ABC", workflowId: nil), then: {})
        let second = gate.decide(.recording(recordingId: "01ABC", workflowId: nil), then: {})

        XCTAssertEqual(first, .later)
        XCTAssertEqual(second, .later)
        await fulfillment(of: [stopped], timeout: 5)
        XCTAssertEqual(stops, 1, "the recording is finalized once, however many times ⌘Q is pressed")
    }
}
