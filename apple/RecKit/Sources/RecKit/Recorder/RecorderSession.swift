import Foundation
import ReclyCore

/// What the recorder is doing. One value, one owner ([RecorderSession]) — a shell that keeps its
/// own `isRecording` beside this ends up with two answers to the same question, and the one the
/// menu draws is the stale one. (The Android `RecorderState` is the same four; the two are meant to
/// stay readable side by side.)
public enum RecorderState: Equatable, Sendable {
    case idle

    /// Between the click and the first sample: the recording id does not exist yet, and a second
    /// start must not be accepted in the meantime.
    case starting

    case recording(recordingId: String, workflowId: String?)

    case stopping
}

/// The capture as [RecorderSession] sees it: `SegmentedRecorder` in the app, a fake in the tests —
/// which is what lets the lifecycle rules below be checked without a microphone.
public protocol Capture: AnyObject {
    func start(workflowId: String?, title: String?, mode: RecordingMode, context: Context?) async throws -> String
    func stop(title: String?) async -> StopResult
}

extension SegmentedRecorder: Capture {}

/// Which start counts, what a stop that arrives while the microphone is still opening does, and
/// when a recovery pass is allowed to walk the recording directories.
///
/// It is an `actor` because that is exactly the guarantee the rules need: every transition below is
/// decided and written before the method reaches its first `await`, so two clicks, a menu item and
/// a fatal capture error cannot each see `idle` and each start a recording. (The Android
/// `RecorderSession` is the same contract with a `MutableStateFlow` in place of the actor.)
public actor RecorderSession {
    private let capture: any Capture
    /// `RecordingRecovery.reconcile`, which chains its own passes end to end — so a start that
    /// waits for this one waits for any pass already walking, too.
    private let recover: () async -> Int
    /// Published to whoever draws the menu. Called on the actor; the shell hops to the main queue.
    private let onState: (RecorderState) -> Void

    private var state: RecorderState = .idle

    /// A stop that arrived while the microphone was still opening. It cannot be served then — there
    /// is no recording to finalize yet — and it must not be dropped either, so its caller waits
    /// here and is let go the instant the start has settled.
    private var parked: CheckedContinuation<Void, Never>?

    public init(
        capture: any Capture,
        recover: @escaping () async -> Int,
        onState: @escaping (RecorderState) -> Void
    ) {
        self.capture = capture
        self.recover = recover
        self.onState = onState
    }

    public var current: RecorderState { state }

    /// `nil` when something is already running: one recording at a time, and the second click is
    /// not an error to show the user.
    @discardableResult
    public func start(
        workflowId: String?,
        title: String? = nil,
        mode: RecordingMode = .microphone,
        context: Context? = nil
    ) async throws -> String? {
        guard state == .idle else { return nil }
        // Before the first `await`, so a second start finds `starting` however fast it arrives.
        transition(.starting)

        // docs/03: a pass before every new recording, not only at launch — a stop that deferred
        // must not still be deferred when the next recording starts writing next to it. Here is the
        // only place it can run safely while a start is in flight: the state already says
        // `starting`, so nothing else can begin, and this recording's directory does not exist yet.
        _ = await recover()

        do {
            let recordingId = try await capture.start(
                workflowId: workflowId, title: title, mode: mode, context: context
            )
            transition(.recording(recordingId: recordingId, workflowId: workflowId))
            release()
            return recordingId
        } catch {
            // The microphone never opened: back to idle, and a stop that was waiting on it finds
            // nothing to do.
            transition(.idle)
            release()
            throw error
        }
    }

    /// The menu item, a second click on it and a fatal capture error can all land. A stop happens
    /// once: finalize is not idempotent from the user's side, and handing the same recording to the
    /// queue twice is not free.
    public func stop(title: String?) async -> StopResult {
        switch state {
        case .idle, .stopping:
            return .notRecording

        case .starting:
            guard parked == nil else { return .notRecording }
            await withCheckedContinuation { continuation in parked = continuation }
            // Let go by the start, one way or the other. A start that failed leaves nothing to
            // stop; a stop that beat this one to it leaves `stopping`.
            guard case .recording = state else { return .notRecording }
            return await finish(title: title)

        case .recording:
            return await finish(title: title)
        }
    }

    /// A recovery pass walks the very directories a running recorder is writing into, so it runs
    /// only when nothing is running — at launch, and from inside [start].
    @discardableResult
    public func recoverIfIdle() async -> Int {
        guard state == .idle else { return 0 }
        return await recover()
    }

    private func finish(title: String?) async -> StopResult {
        // Before the first `await`, so the second stop sees `stopping` and returns.
        transition(.stopping)
        let result = await capture.stop(title: title)
        transition(.idle)
        return result
    }

    /// Hands the state back to a stop that parked while the start was in flight.
    private func release() {
        guard let continuation = parked else { return }
        parked = nil
        continuation.resume()
    }

    private func transition(_ next: RecorderState) {
        state = next
        onState(next)
    }
}
