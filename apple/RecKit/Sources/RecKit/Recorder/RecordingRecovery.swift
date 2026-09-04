import Foundation
import ReclyCore

/// What the process finds when it comes back from a kill: docs/03 promises a recording is
/// recoverable "up to the last boundary", and that promise is only kept if somebody looks.
///
/// Four things get left behind. Segment files the engine finished but the database never heard
/// about — the boundary registration lost its race with the kill. Parts whose `addPart` failed and
/// left a `.pending` marker, which a stop refuses to finalize over. A row still marked `recording`,
/// which no list and no executor will ever act on. And a recording that was finalized while the
/// user was still being asked for its title, and so was never enqueued.
///
/// Runs at process start and again before every new recording. An `actor` with the passes chained
/// end to end, because a `for await` over the recordings is full of suspension points and two
/// overlapping scans would fight over the same directories.
public actor RecordingRecovery {
    /// Deep enough that a machine left offline for weeks still gets everything reconciled.
    private static let limit: Int32 = 200

    private let core: ReclyCore_
    private let reconciler: PartReconciler
    private var pass: Task<Int, Never>?

    public init(core: ReclyCore_) {
        self.core = core
        reconciler = PartReconciler(core: core)
    }

    /// How many recordings this changed — recovered, enqueued or dropped.
    @discardableResult
    public func reconcile() async -> Int {
        let previous = pass
        let next = Task { () -> Int in
            _ = await previous?.value
            return await self.walk()
        }
        pass = next
        return await next.value
    }

    private func walk() async -> Int {
        var touched = 0
        do {
            for record in try await core.recordings.list(limit: Self.limit) {
                let handled: Bool
                if record.meta.status == RecordingStatus.recording {
                    handled = try await recover(record)
                } else if record.meta.status == RecordingStatus.finalized {
                    handled = try await settle(record)
                } else {
                    handled = false
                }
                if handled { touched += 1 }
            }
        } catch {
            // A scan that cannot finish is not a reason to refuse the recording the user is asking
            // for: whatever it did not reach is still there for the next pass.
            log(.error, "rec.recovery.failed", ["error": "\(error)"])
        }
        return touched
    }

    /// Files first, then the row: a finalize with parts missing would write the wrong duration.
    private func recover(_ record: RecordingRecord) async throws -> Bool {
        guard let reconciled = try await reconciler.reconcile(recordingId: record.id) else { return false }

        if reconciled.files == 0 {
            // Nothing readable was ever recorded: no file at all, or only the tail the process died
            // inside — quarantined by this pass or an earlier one. A row that stays `recording`
            // for good is one the user can do nothing with from the app, so the row and the
            // directory go, the quarantined bytes with them (2026-09-04 decision, docs/03). (The
            // Android and Windows `RecordingRecovery` do the same.)
            try await core.recordings.delete(recordingId: record.id)
            log(.warn, "rec.recovered.empty", ["recordingId": record.id, "parts": 0, "durationSec": 0.0])
            return true
        }
        if reconciled.pending > 0 {
            // Still unfilable — the disk or the database is unhappy. Leave the row open so the next
            // pass tries again rather than finalizing a recording that is missing audio.
            log(.error, "rec.recovered.pendingRemains", fields(record.id, reconciled.pending, reconciled))
            return true
        }

        _ = try await core.recordings.finalize(
            recordingId: record.id,
            endedAt: KotlinInstant.companion.fromEpochMilliseconds(
                epochMilliseconds: Int64((reconciled.endedAt.timeIntervalSince1970 * 1000).rounded())
            ),
            durationSec: reconciled.durationSec,
            title: nil,
            silenced: [],
            gaps: []
        )
        log(.warn, "rec.recovered", fields(record.id, reconciled.files, reconciled))
        // The finalize is the recovery; whether the queue took it is its own line in the log.
        _ = try await ready(record.id)
        return true
    }

    /// A finalized recording is normally finished business, and two things can still be wrong with
    /// it: a part that never made it in (a stop that deferred, or a boundary marker), and a missing
    /// job (the process died while the title prompt was open).
    ///
    /// A finalized row with a job and no markers is skipped without touching the disk — a part can
    /// only go missing while the row still says `recording`, which the other path covers.
    private func settle(_ record: RecordingRecord) async throws -> Bool {
        let jobs = try await core.recordings.jobStatuses(recordingId: record.id)
        if !jobs.isEmpty, reconciler.markers(in: record.dir.url).isEmpty { return false }

        guard let reconciled = try await reconciler.reconcile(recordingId: record.id) else { return false }
        if reconciled.registered > 0 {
            // The meta's duration was written without this part; it has to say so now.
            try await reconciler.refinalize(record: record, reconciled: reconciled)
            let done = jobs.contains { $0 == JobStatus.done }
            log(.warn, done ? "rec.recovered.partLate" : "rec.recovered.part",
                fields(record.id, reconciled.registered, reconciled))
        }
        if reconciled.pending > 0 {
            log(.error, "rec.recovered.pendingRemains", fields(record.id, reconciled.pending, reconciled))
            return true
        }
        if jobs.isEmpty {
            let enqueued = try await ready(record.id)
            return enqueued || reconciled.registered > 0
        }
        return reconciled.registered > 0
    }

    /// A recovered recording is finalized and nobody is going to be asked to name it, so it goes
    /// straight to the queue. `chosenWorkflowId` is nil on purpose: the pick the user made when
    /// they started is in the meta, and that is what `enqueue` falls back to (docs/05).
    ///
    /// The queue is allowed to refuse: a device that has not chosen a default resolves `NoWorkflow`
    /// (ADR-016), and calling that a recovery would count the same recording on every pass and log
    /// a success that never happened. The result goes in the log as it is and only `Enqueued`
    /// counts — the retry itself is the self-heal, so a later pass queues it once a default exists.
    /// (The Windows `RecordingRecovery.enqueueIfNoJob` reads the same way.)
    private func ready(_ recordingId: String) async throws -> Bool {
        let result = try await core.enqueue(recordingId: recordingId, chosenWorkflowId: nil)
        log(.info, "rec.recovered.enqueue", ["recordingId": recordingId, "result": Self.name(of: result)])
        if case .enqueued = onEnum(of: result) { return true }
        return false
    }

    /// The Kotlin subclass name, so the log line reads the same on both desktops.
    private static func name(of result: any EnqueueResult) -> String {
        switch onEnum(of: result) {
        case .enqueued: return "Enqueued"
        case .alreadyDone: return "AlreadyDone"
        case .skippedShort: return "SkippedShort"
        case .partsPurged: return "PartsPurged"
        case .noWorkflow: return "NoWorkflow"
        }
    }

    private func fields(_ recordingId: String, _ parts: Int, _ reconciled: Reconciled) -> [String: Any] {
        ["recordingId": recordingId, "parts": parts, "durationSec": reconciled.durationSec]
    }

    private func log(_ level: LoggerLevel, _ event: String, _ fields: [String: Any]) {
        core.deps.logger.log(level: level, event: event, fields: fields, error: nil)
    }
}
