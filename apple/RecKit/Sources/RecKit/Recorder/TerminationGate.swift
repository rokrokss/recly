import Foundation

/// What a quit has to do about a recording that is still running (docs/03: a stop is what writes
/// `status: finalized` and queues the job). `⌘Q` on a menu-bar app is one keystroke away at all
/// times, and an app that just exits leaves a row saying `recording` and a segment whose trailing
/// MPEG-4 atoms were never written — the crash case, entered on purpose.
///
/// It lives here, apart from `NSApplicationDelegate`, because `applicationShouldTerminate` is not
/// something a unit test can call: AppKit decides when it runs and what it does with the answer.
/// The rule it asks for is this type, and this type runs anywhere.
@MainActor
public final class TerminationGate {
    public enum Decision: Equatable, Sendable {
        /// Nothing is being recorded, so nothing is lost by going now.
        case now
        /// The recording is being finished. The shell holds the quit open and lets it go when
        /// `reply` runs (`NSApplication.reply(toApplicationShouldTerminate:)`).
        case later
    }

    /// Stop, finalize and enqueue — `RecorderSession.stop` and what follows it — for a quit that
    /// finds a recording running and nobody stopping it.
    private let stop: () async -> Void
    /// The finish in flight, whoever asked for it. A stop already running is the one a quit waits
    /// on: `RecorderSession.stop` answers a second caller `.notRecording` at once, and a quit that
    /// took that answer would end the process while the first stop is still closing the segment,
    /// finalizing and queueing. It outlives the session's `.stopping` state too — the session says
    /// `.idle` as soon as the meta is closed, and the title prompt and the enqueue come after that.
    private var inFlight: Task<Void, Never>?
    /// A quit already being served. `⌘Q` twice must not finalize twice, and the second answer is
    /// still `.later`: the first one's `reply` is what ends the process, for both of them.
    private var finishing = false

    public init(stop: @escaping () async -> Void) {
        self.stop = stop
    }

    /// Runs `work` — a stop, finalize and enqueue — unless one is already running, in which case
    /// that one is what comes back. The shell's own Stop goes through here as well as the quit, so
    /// that `⌘Q` pressed during it awaits that stop instead of starting a second one.
    @discardableResult
    public func finish(_ work: @escaping () async -> Void) -> Task<Void, Never> {
        if let inFlight { return inFlight }
        let task = Task { @MainActor [weak self] in
            await work()
            self?.inFlight = nil
        }
        inFlight = task
        return task
    }

    public func decide(_ state: RecorderState, then reply: @escaping @MainActor () -> Void) -> Decision {
        if state == .idle, inFlight == nil { return .now }
        guard !finishing else { return .later }
        finishing = true
        let finish = finish(stop)
        Task { @MainActor in
            await finish.value
            reply()
        }
        return .later
    }
}
