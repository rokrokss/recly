import XCTest
@testable import RecKit

/// docs/12 "실행기" deliverable 2, in the two halves the phone splits it into (docs/11 A5): the
/// arithmetic behind the successor, and what a pass does with it.
final class NextRunTests: XCTestCase {
    private let now = FakeJobQueue.now

    func testAnEmptyQueueArmsNothing() {
        XCTAssertNil(NextRun.delay([], now: now))
    }

    /// The one case in which nothing is armed: none of these comes back on its own, and waking the
    /// machine for them would be waking it forever.
    func testOnlyAJobAPersonCanUnblockArmsNothing() {
        let jobs = [job(.done), job(.failed), job(.needsAuth), job(.skippedShort)]
        XCTAssertNil(NextRun.delay(jobs, now: now))
    }

    func testAPendingJobIsDueImmediately() {
        XCTAssertEqual(NextRun.delay([job(.pending)], now: now), 0)
    }

    func testTheEarliestFutureBackoffWins() {
        let jobs = [
            job(.waiting, nextRunAt: now.addingTimeInterval(30 * 60)),
            job(.waiting, nextRunAt: now.addingTimeInterval(2 * 60)),
            job(.waiting, nextRunAt: now.addingTimeInterval(5 * 60)),
        ]
        XCTAssertEqual(NextRun.delay(jobs, now: now), 120)
    }

    /// An overdue `WAITING` job must not fall out of the calculation the way a filter on `> now`
    /// would drop it — a backoff that elapsed while a long upload held the pass is due, not never.
    func testABackoffThatElapsedDuringALongPassIsDueNow() {
        let jobs = [job(.waiting, nextRunAt: now.addingTimeInterval(-5 * 60))]
        XCTAssertEqual(NextRun.delay(jobs, now: now), 0)
    }

    func testAPendingJobBeatsAFutureBackoff() {
        let jobs = [job(.waiting, nextRunAt: now.addingTimeInterval(30 * 60)), job(.pending)]
        XCTAssertEqual(NextRun.delay(jobs, now: now), 0)
    }

    /// A lost paired write (docs/10 "짝 전이") must not cost the job its scheduler.
    func testAWaitingJobWithNoInstantIsTreatedAsDue() {
        XCTAssertEqual(NextRun.delay([job(.waiting)], now: now), 0)
    }

    /// Recovering it needs a pass, not a timer, and the five-minute one brings one.
    func testARunningRowIsLeftToTheTimer() {
        XCTAssertNil(NextRun.delay([job(.running)], now: now))
    }
}

@MainActor
final class JobRunnerTests: XCTestCase {
    private let now = FakeJobQueue.now

    func testAPassRunsTheQueueExactlyOnce() async {
        let queue = FakeJobQueue(pass: JobPass(alreadyRunning: false, ran: 2))
        let (runner, armed) = runner(queue)

        await runner.run().value

        XCTAssertEqual(queue.runs, 1, "runDueJobs is the whole job of a pass")
        XCTAssertEqual(armed.delays, [], "an empty queue arms nothing")
    }

    func testAParkedJobArmsTheSuccessorAtItsBackoff() async {
        let queue = FakeJobQueue(
            queueAfterRun: [
                job(.waiting, nextRunAt: now.addingTimeInterval(12 * 60)),
                job(.needsAuth),
            ]
        )
        let (runner, armed) = runner(queue)

        await runner.run().value

        XCTAssertEqual(armed.delays, [12 * 60])
    }

    /// `NEEDS_AUTH` is unblocked by the user, not by the scheduler.
    func testAJobParkedForSignInArmsNothingAndCancelsNothing() async {
        let queue = FakeJobQueue(queueAfterRun: [job(.needsAuth)])
        let (runner, armed) = runner(queue)

        await runner.run().value

        XCTAssertEqual(armed.delays, [])
    }

    /// The pass read the table before the job landed, so the read is empty and honest — and stale.
    /// The generation is the only evidence left that it happened.
    func testADueSignalDuringThePassBeatsAnEmptyQueueRead() async {
        let queue = FakeJobQueue(pass: JobPass(alreadyRunning: false, ran: 0), queueAfterRun: [])
        let (runner, armed) = runner(queue)
        queue.whileReadingJobs = { runner.signalDue() }

        await runner.run().value

        XCTAssertEqual(armed.delays, [0], "the successor the enqueue is owed")
    }

    /// Ten minutes is right for what the read saw and wrong for what arrived.
    func testADueSignalDuringThePassBeatsAComputedBackoff() async {
        let queue = FakeJobQueue(queueAfterRun: [job(.waiting, nextRunAt: now.addingTimeInterval(10 * 60))])
        let (runner, armed) = runner(queue)
        queue.whileReadingJobs = { runner.signalDue() }

        await runner.run().value

        XCTAssertEqual(armed.delays, [0])
    }

    /// The negative that matters: the generation check must not swallow every computed delay into
    /// zero and wake the machine on every backoff.
    func testNoSignalLeavesTheComputedBackoffAlone() async {
        let queue = FakeJobQueue(queueAfterRun: [job(.waiting, nextRunAt: now.addingTimeInterval(10 * 60))])
        let (runner, armed) = runner(queue)

        await runner.run().value

        XCTAssertEqual(armed.delays, [10 * 60])
    }

    /// The counter never resets, so what matters is only whether it moved *while this pass ran*. A
    /// signal from before the snapshot must not collapse every delay to zero.
    func testASignalFromBeforeThePassIsNotMistakenForOneDuringIt() async {
        let queue = FakeJobQueue(pass: JobPass(alreadyRunning: true, ran: 0))
        let (runner, armed) = runner(queue)

        runner.signalDue()
        await runner.run().value

        XCTAssertEqual(armed.delays, [JobRunner.followUp])
    }

    /// This pass saw an empty run because another one holds the core's lock; deciding anything from
    /// that snapshot would throw away the real pass's successor.
    func testAPassThatFoundTheCoreBusyArmsABoundedFollowUp() async {
        let queue = FakeJobQueue(pass: JobPass(alreadyRunning: true, ran: 0))
        let (runner, armed) = runner(queue)

        await runner.run().value

        XCTAssertEqual(armed.delays, [JobRunner.followUp])
        XCTAssertTrue((0 ... 5 * 60).contains(JobRunner.followUp), "the follow-up must stay bounded")
    }

    /// A busy pass reshapes nothing on the strength of what it saw — but a job that became runnable
    /// while it was in flight must not have to wait out the follow-up for it.
    func testADueSignalDuringABusyPassBeatsTheFollowUp() async {
        let queue = FakeJobQueue(pass: JobPass(alreadyRunning: true, ran: 0))
        let (runner, armed) = runner(queue)
        queue.whileRunning = { runner.signalDue() }

        await runner.run().value

        XCTAssertEqual(armed.delays, [0])
    }

    /// `jobsDue` raises the signal *before* the pass, so a pass that then finds the core busy is
    /// answered by the bounded follow-up: the enqueue is already inside the running pass's reach,
    /// or it is picked up a minute later. Nothing is lost either way.
    func testAnEnqueueThatMeetsABusyCoreGetsTheBoundedFollowUp() async {
        let queue = FakeJobQueue(pass: JobPass(alreadyRunning: true, ran: 0))
        let (runner, armed) = runner(queue)

        await runner.jobsDue().value

        XCTAssertEqual(armed.delays, [JobRunner.followUp])
    }

    /// A pass that never finished says nothing about the next one, so it arms nothing off a queue
    /// it failed to read; the five-minute timer is the retry.
    func testAPassThatThrowsArmsNothing() async {
        let queue = FakeJobQueue(failWith: URLError(.notConnectedToInternet))
        let (runner, armed) = runner(queue)

        await runner.run().value

        XCTAssertEqual(armed.delays, [])
    }

    /// What the menu reads to show "로그인 필요" and the recent list.
    func testTheQueueAfterAPassIsReportedToTheShell() async {
        let queue = FakeJobQueue(queueAfterRun: [job(.needsAuth), job(.done)])
        let armed = ArmRecorder()
        var reported: [JobRunSnapshot] = []
        let runner = JobRunner(queue: queue, arm: { armed.delays.append($0) }, onPass: { reported = $0 })

        await runner.run().value

        XCTAssertEqual(reported.map(\.status), [.needsAuth, .done])
    }

    private func runner(_ queue: FakeJobQueue) -> (JobRunner, ArmRecorder) {
        let armed = ArmRecorder()
        return (JobRunner(queue: queue, arm: { armed.delays.append($0) }), armed)
    }
}

/// Every successor the runner armed, in order. There is deliberately no cancel to record.
@MainActor
private final class ArmRecorder {
    var delays: [TimeInterval] = []
}
