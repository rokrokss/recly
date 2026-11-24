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
        let summary = try await withTaskCancellationHandler {
            try await core.runDueJobs(now: core.deps.clock.now())
        } onCancel: { self.core.deps.localTranscription.cancel() }
        return JobPass(alreadyRunning: summary.alreadyRunning, ran: summary.jobIds.count)
    }

    /// The phone's background pass that needs no network. Here rather than in the app target: in a
    /// Debug build the app target's copy of `withTaskCancellationHandler` was linked with
    /// ReclyCore's optimized copy's context size and aborted the app in `swift_task_dealloc`.
    /// RecKit is prelinked, so its copy keeps its own.
    public func runLocalJobs() async throws -> JobPass {
        let summary = try await withTaskCancellationHandler {
            try await core.runLocalJobs()
        } onCancel: { self.core.deps.localTranscription.cancel() }
        return JobPass(alreadyRunning: summary.alreadyRunning, ran: summary.jobIds.count)
    }

    public func jobs() async throws -> [JobRunSnapshot] {
        try await core.jobs.list().map {
            JobRunSnapshot(status: JobRunStatus($0.status), nextRunAt: $0.nextRunAt?.date)
        }
    }
}

/// docs/06: verify the Drive owner and restore matching paused jobs without resetting progress.
/// The shell schedules the next pass, including after a transient verification failure.
public enum ParkedJobs {
    public static func unpark(core: ReclyCore_) async {
        _ = try? await core.reconnectDrive()
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
        case .needsConsent: self = .needsConsent
        case .needsModel: self = .needsModel
        case .skippedShort: self = .skippedShort
        }
    }
}
