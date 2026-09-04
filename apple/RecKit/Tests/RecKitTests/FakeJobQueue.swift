import Foundation
@testable import RecKit

/// Records what the runner asked of the core, and can be told to blow up in the middle of it —
/// the phone's `FakeJobFacade`, in Swift.
final class FakeJobQueue: JobQueue, @unchecked Sendable {
    static let now = Date(timeIntervalSince1970: 1_787_824_800)

    private let clock: Date
    private let pass: JobPass
    /// The job table as the runner will find it *after* the pass.
    private let queueAfterRun: [JobRunSnapshot]
    private let failWith: Error?
    /// Runs while `jobs()` is being answered — the window in which an enqueue can land between the
    /// pass and the recompute. A `var` so a test can point it back at the runner it is testing.
    var whileReadingJobs: @Sendable () -> Void = {}
    /// Runs while the pass itself is in flight — the window an enqueue lands in when another pass
    /// already holds the core's lock.
    var whileRunning: @Sendable () -> Void = {}

    private(set) var runs = 0

    init(
        now: Date = FakeJobQueue.now,
        pass: JobPass = JobPass(alreadyRunning: false, ran: 1),
        queueAfterRun: [JobRunSnapshot] = [],
        failWith: Error? = nil
    ) {
        self.clock = now
        self.pass = pass
        self.queueAfterRun = queueAfterRun
        self.failWith = failWith
    }

    func now() -> Date { clock }

    func runDueJobs() async throws -> JobPass {
        runs += 1
        whileRunning()
        if let failWith { throw failWith }
        return pass
    }

    func jobs() async throws -> [JobRunSnapshot] {
        whileReadingJobs()
        return queueAfterRun
    }
}

/// The only fields [NextRun] and the runner look at.
func job(_ status: JobRunStatus, nextRunAt: Date? = nil) -> JobRunSnapshot {
    JobRunSnapshot(status: status, nextRunAt: nextRunAt)
}
