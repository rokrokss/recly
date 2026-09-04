import Foundation
import Network
import os

/// docs/10 "잡 상태 머신", as much of it as the executor wiring has an opinion about.
public enum JobRunStatus: Sendable {
    case pending
    case running
    case waiting
    case done
    case failed
    case needsAuth
    /// docs/10 "Drive 용량 초과": parked until the user frees space and asks again. Like every other
    /// parked state it is not something [NextRun] comes back for — the core does not poll Drive.
    case needsSpace
    case skippedShort
}

/// One row of the queue, reduced to what deciding *when to come back* needs.
public struct JobRunSnapshot: Sendable, Equatable {
    public let status: JobRunStatus
    public let nextRunAt: Date?

    public init(status: JobRunStatus, nextRunAt: Date? = nil) {
        self.status = status
        self.nextRunAt = nextRunAt
    }
}

/// What one call of `ReclyCore.runDueJobs()` made of itself.
public struct JobPass: Sendable {
    /// The core's executor was already inside a pass, so this one saw nothing at all.
    public let alreadyRunning: Bool
    public let ran: Int

    public init(alreadyRunning: Bool, ran: Int) {
        self.alreadyRunning = alreadyRunning
        self.ran = ran
    }
}

/// Everything [JobRunner] needs from the core, and so the whole of what a test has to stand in for
/// (the Android twin is `JobFacade`). A runner that reached for `ReclyCore` directly could only be
/// tested against a database.
public protocol JobQueue: AnyObject, Sendable {
    func now() -> Date
    func runDueJobs() async throws -> JobPass
    /// The queue as it stands after a pass — read to arm the successor.
    func jobs() async throws -> [JobRunSnapshot]
}

/// When the queue has to be looked at again (docs/11 A5 deliverable 3, docs/12 "실행기"). Pure on
/// purpose: it is the one piece of the executor wiring with arithmetic in it.
public enum NextRun {
    /// The earliest moment a job could next make progress: a `PENDING` job is due immediately, a
    /// `WAITING` one when its backoff elapses — including a backoff that already elapsed while a
    /// long upload held the pass, which is why past instants are kept rather than filtered out.
    /// Everything else (`DONE`, `FAILED`, `NEEDS_AUTH`, `NEEDS_SPACE`, `SKIPPED_SHORT`) waits for a
    /// person.
    ///
    /// `RUNNING` is deliberately not here: a row left running by a kill is recovered by the next
    /// pass, and the five-minute timer brings one. Arming a zero-delay timer for it would wake the
    /// machine to do the same nothing.
    public static func at(_ jobs: [JobRunSnapshot], now: Date) -> Date? {
        jobs.compactMap { job -> Date? in
            switch job.status {
            case .pending: return now
            // A WAITING row with no instant is a lost write, not a job to strand: treat it as due.
            case .waiting: return job.nextRunAt ?? now
            default: return nil
            }
        }.min()
    }

    /// Never negative — an overdue job means "now". nil is "there is nothing to come back for".
    public static func delay(_ jobs: [JobRunSnapshot], now: Date) -> TimeInterval? {
        at(jobs, now: now).map { max(0, $0.timeIntervalSince(now)) }
    }
}

/// docs/12 "실행기": the app process is the only thing that runs the queue on a Mac, and it calls
/// `runDueJobs()` on four triggers — a job just enqueued, the five-minute timer, the network coming
/// back, and the successor armed from the queue itself. The phone shares it (docs/13 I3) and adds
/// a fifth of its own — the app becoming active — through [jobsDue]'s sibling [run].
///
/// Two invariants, ported from the phone's `WorkflowWorker` (docs/11 A5):
///
/// 1. **Every pass leaves a successor behind it.** The queue is only ever woken by an event or a
///    timer, so a pass that ends without arming the next one is a queue that stalls. The successor
///    is recomputed from the job table after the pass ([NextRun]), with [generation] catching
///    whatever landed while the pass was running. A pass never cancels the successor: a queue that
///    reads empty may simply have been read too early.
/// 2. **A pass that found the core busy reshapes nothing.** It saw no queue of its own, so all it
///    guarantees is that *someone* comes back — shortly after the pass in flight should be done.
@MainActor
public final class JobRunner {
    /// docs/12 "실행기": (b), the standing five-minute timer.
    public static let interval: TimeInterval = 5 * 60
    /// How long after a pass that found the core already busy to come back. Long enough that the
    /// pass in flight has usually finished and armed its own successor (which replaces this one),
    /// short enough that a pass killed mid-flight is picked up again.
    public static let followUp: TimeInterval = 60

    private let queue: JobQueue
    /// The queue as the last completed pass left it — the menu reads `NEEDS_AUTH` off this.
    private let onPass: ([JobRunSnapshot]) -> Void
    /// How the successor is armed, when it is not this runner's own one-shot `Timer`. A test hands
    /// in its own so that nothing actually fires and the delays can be asserted on.
    private let armOverride: ((TimeInterval) -> Void)?

    /// Ticks once per "something became runnable". It is why a pass can tell "the queue is
    /// genuinely empty" from "the queue was empty when I looked". Monotonic and never reset: a
    /// pass only ever compares its own before-and-after.
    ///
    /// Its own lock rather than the main actor's isolation: the signal is raised wherever the
    /// enqueue happened — the core answers on its own dispatcher — and a hop would put it *after*
    /// the comparison it exists to lose the race to.
    private let generation = Generation()

    private let logger = Logger(subsystem: CoreBridge.appName, category: "jobs")
    private var ticker: Timer?
    private var successor: Timer?
    private var monitor: NWPathMonitor?
    /// The last path state seen, so only the *return* of the network runs a pass.
    private var online = true

    public init(
        queue: JobQueue,
        arm: ((TimeInterval) -> Void)? = nil,
        onPass: @escaping ([JobRunSnapshot]) -> Void = { _ in }
    ) {
        self.queue = queue
        self.onPass = onPass
        self.armOverride = arm
    }

    /// Triggers (b) and (c). Not in `init` so a test can build a runner without a timer or a path
    /// monitor attached to it.
    public func start() {
        let ticker = Timer(timeInterval: Self.interval, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.run() }
        }
        RunLoop.main.add(ticker, forMode: .common)
        self.ticker = ticker

        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            let satisfied = path.status == .satisfied
            Task { @MainActor in self?.networkChanged(satisfied) }
        }
        monitor.start(queue: DispatchQueue.global(qos: .utility))
        self.monitor = monitor
        run()
    }

    /// Trigger (a): a stop that queued a job, a retry, a sign-in that unparked one. The signal goes
    /// first, so a pass already in flight can tell that its own view of the queue is stale even if
    /// the job landed after it read the table.
    @discardableResult
    public func jobsDue() -> Task<Void, Never> {
        signalDue()
        return run()
    }

    /// Ticks the counter without running a pass. Separate from [jobsDue] because that ordering is
    /// the contract: the signal has to be visible before the pass that might miss the job.
    public nonisolated func signalDue() {
        generation.tick()
    }

    /// One pass, and whatever it decides to arm behind it. The task is returned so a test can wait
    /// for it; nothing in the app does.
    @discardableResult
    public func run() -> Task<Void, Never> {
        Task { await pass() }
    }

    private func pass() async {
        // Snapshotted before the pass and compared after the queue is read, never in between: a
        // job enqueued while this pass runs lands in neither, and the counter is the only evidence
        // left that it happened.
        let generation = self.generation.current
        do {
            let summary = try await queue.runDueJobs()
            if summary.alreadyRunning {
                let delay = signalled(generation) ? 0 : Self.followUp
                arm(delay)
                logger.info("job.pass.busy followUpSec=\(Int(delay), privacy: .public)")
                return
            }
            let jobs = try await queue.jobs()
            // The compare has to come after the read. A signal that lands while the queue is being
            // read is exactly the race, and comparing first would look right and miss it.
            let delay = signalled(generation) ? 0 : NextRun.delay(jobs, now: queue.now())
            // Nothing to arm is not the same as cancelling what is there: a stale successor costs
            // one pass that finds nothing and arms nothing in turn, which terminates.
            if let delay { arm(delay) }
            onPass(jobs)
            logger.info(
                """
                job.pass ran=\(summary.ran, privacy: .public) queued=\(jobs.count, privacy: .public) \
                nextSec=\(delay.map { String(Int($0)) } ?? "-", privacy: .public)
                """
            )
        } catch {
            // The pass itself could not happen. The five-minute timer is the retry; arming a
            // successor off a queue we failed to read would be arming it off nothing.
            logger.error("job.pass.failed error=\(String(describing: error), privacy: .private)")
        }
    }

    /// True when something became runnable after this pass started, so the queue read is stale.
    private func signalled(_ generation: Int) -> Bool { self.generation.current != generation }

    private func networkChanged(_ satisfied: Bool) {
        let returned = satisfied && !online
        online = satisfied
        guard returned else { return }
        logger.info("job.network.back")
        run()
    }

    /// [JobRunner.generation], with the lock that lets any thread raise it.
    private final class Generation: @unchecked Sendable {
        private let lock = NSLock()
        private var value = 0

        var current: Int { lock.withLock { value } }

        func tick() { lock.withLock { value += 1 } }
    }

    /// Exactly one successor, always replaced. There is deliberately no way to cancel it: a pass
    /// that finds an empty queue arms nothing and leaves whatever is standing alone.
    private func arm(_ delay: TimeInterval) {
        if let armOverride {
            armOverride(delay)
            return
        }
        successor?.invalidate()
        let timer = Timer(timeInterval: max(delay, 0), repeats: false) { [weak self] _ in
            Task { @MainActor in self?.run() }
        }
        RunLoop.main.add(timer, forMode: .common)
        successor = timer
    }
}
