import Foundation
import ReclyCore

/// [JobQueue] over the real core. Everything it does is one SKIE `async` call on a Kotlin object,
/// which is the supported direction — the wrappers only abort on a Kotlin interface implemented in
/// Swift.
public final class CoreJobQueue: JobQueue, @unchecked Sendable {
    private let core: ReclyCore_

    public init(core: ReclyCore_) {
        self.core = core
    }

    /// The core's clock, not `Date()`: the instants a job's `nextRunAt` carries came from it.
    public func now() -> Date { core.deps.clock.now().date }

    public func runDueJobs() async throws -> JobPass {
        let summary = try await core.runDueJobs(now: core.deps.clock.now())
        return JobPass(alreadyRunning: summary.alreadyRunning, ran: summary.jobIds.count)
    }

    public func jobs() async throws -> [JobRunSnapshot] {
        try await core.jobs.list().map {
            JobRunSnapshot(status: JobRunStatus($0.status), nextRunAt: $0.nextRunAt?.date)
        }
    }
}

/// docs/06: what a sign-in releases. A job that could only ever have been waiting for one goes back
/// to `PENDING`; the pass that runs them is the shell's own, because the runner is.
public enum ParkedJobs {
    public static func unpark(core: ReclyCore_) async {
        let parked = (try? await core.jobs.list())?.filter { $0.status == .needsAuth } ?? []
        for job in parked {
            _ = try? await core.jobs.retry(jobId: job.id)
        }
    }
}

public extension JobRunStatus {
    /// docs/10's states, as the executor wiring names them.
    init(_ status: JobStatus) {
        switch status {
        case .pending: self = .pending
        case .running: self = .running
        case .waiting: self = .waiting
        case .done: self = .done
        case .failed: self = .failed
        case .needsAuth: self = .needsAuth
        case .needsSpace: self = .needsSpace
        case .skippedShort: self = .skippedShort
        }
    }
}
