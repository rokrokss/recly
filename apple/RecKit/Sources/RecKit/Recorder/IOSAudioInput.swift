#if os(iOS) || os(watchOS)
import AVFoundation
import Foundation

/// The iPhone's and the Apple Watch's microphone (docs/13 "iPhone"·"Apple Watch" 녹음):
/// `AVAudioEngine`'s input node on top of an `AVAudioSession` that is allowed to keep recording once
/// the screen locks or the wrist drops.
///
/// It is a type of its own rather than a branch inside [MicrophoneInput] because everything that
/// makes these two platforms themselves is the session around the engine, not the engine: the
/// category that earns the `UIBackgroundModes: audio` entitlement, the interruption a phone call is
/// (docs/03 `silenced`), and the route change a headset unplugged is (`gaps`, through the recorder's
/// own restart). The watch shares all three — only the category differs (see [activate]) — so
/// M5-L4 gave it this input rather than a variant of it.
///
/// Started and stopped on the recorder's control queue; the notifications arrive on whatever thread
/// the system used, which is why nothing here is read from two places at once except through them.
final class IOSAudioInput: AudioInput {
    private let engine = AVAudioEngine()
    private let session: AVAudioSession
    /// Injected so a test can post the interruption a phone call would (there is no way to make the
    /// simulator ring), on the real notification names with the real user-info keys.
    private let notifications: NotificationCenter
    private var observers: [NSObjectProtocol] = []
    /// Covers [interruption] and the resume it asks for, which is the one thing here that arrives
    /// on a thread of its own: an interruption ending as the recorder's control queue is running
    /// `stop` must either resume before it — and be torn down by it — or find the input stopped and
    /// do nothing. Without that, a call that ends after the user has already stopped brings the
    /// session and the microphone indicator back up under a recording that is finalized.
    private let lock = NSLock()
    /// Everything this input knows about being interrupted, and every decision it makes about it.
    private var interruption = Interruption()

    var onConfigurationChange: ((String) -> Void)?
    var onSilence: ((Bool) -> Void)?

    init(session: AVAudioSession = .sharedInstance(), notifications: NotificationCenter = .default) {
        self.session = session
        self.notifications = notifications
    }

    /// Configuring the session is what decides this — the input node reports the session's own
    /// format — and the recorder reads it *before* it calls [start], to build its converter with.
    /// So the configuration happens here, and it is idempotent: the restart path reads the format
    /// again after a [stop] has deactivated the session.
    var format: AVAudioFormat? {
        try? activate()
        let format = engine.inputNode.outputFormat(forBus: 0)
        return format.sampleRate > 0 && format.channelCount > 0 ? format : nil
    }

    var isRunning: Bool { engine.isRunning }

    func authorize() async throws {
        try await MicrophoneInput.requireMicrophone()
    }

    func start(_ onBuffer: @escaping (AVAudioPCMBuffer) -> Void) throws {
        try activate()
        guard let format else {
            throw RecorderError("the audio session reports no usable input format")
        }
        engine.inputNode.installTap(onBus: 0, bufferSize: 4096, format: format) { buffer, _ in
            onBuffer(buffer)
        }
        lock.withLock { interruption.tapped = true }
        // Before the engine rather than after it: an engine that refuses to start is a recording
        // that never begins, and the observers a failed start left behind are taken off by the
        // [stop] the recorder's own teardown makes.
        observe()
        engine.prepare()
        try engine.start()
    }

    func stop() {
        stopObserving()
        // The state first, and under the lock: a resume that is halfway through is waited for here
        // and undone below, and one that has not started yet finds the input stopped.
        let wasTapped: Bool = lock.withLock {
            let was = interruption.tapped
            interruption.stopped()
            return was
        }
        if wasTapped {
            engine.inputNode.removeTap(onBus: 0)
        }
        engine.stop()
        // Handing the session back is what lets music the recording interrupted come back on.
        try? session.setActive(false, options: .notifyOthersOnDeactivation)
    }

    /// docs/13 "iPhone": `.playAndRecord`/`.default` with `.allowBluetooth`, 16 kHz asked for
    /// (ADR-006 — the hardware answers with whatever it has and `AVAudioConverter` resamples).
    /// `.playAndRecord` rather than `.record` because it is the category `UIBackgroundModes: audio`
    /// is granted for, and the one a recording survives the lock screen under.
    ///
    /// docs/13 "Apple Watch" asks for `.record` instead, and it is the one difference between the
    /// two platforms that is a choice: a watch has nothing to play back while it records, and
    /// `.playAndRecord` would have it hold an output route — and duck whatever the paired phone is
    /// playing — for the hours a recording lasts.
    ///
    /// The other two are the SDK's. `.allowBluetooth` is watchOS 11 API and RecKit's floor is 10, so
    /// the watch asks for no options at all — a headset it is already routed to is used anyway.
    /// `setPreferredSampleRate` is *unavailable* on watchOS: the rate is the hardware's, which is
    /// what `AVAudioConverter` was already resampling from on the other platforms (ADR-006).
    private func activate() throws {
        #if os(watchOS)
        try session.setCategory(.record, mode: .default)
        #else
        try session.setCategory(.playAndRecord, mode: .default, options: [.allowBluetooth])
        try session.setPreferredSampleRate(Double(SegmentedRecorder.sampleRateHz))
        #endif
        try session.setActive(true)
    }

    private func observe() {
        // `object: nil`: there is one `AVAudioSession` in a process and the notification is about
        // that one. Naming it would only be a way to miss a notification the system posted with
        // something else in the object.
        observers.append(
            notifications.addObserver(
                forName: AVAudioSession.interruptionNotification, object: nil, queue: nil
            ) { [weak self] note in
                self?.interrupted(by: note)
            }
        )
        // docs/13 deliverable 1: a route change goes through the recorder's existing restart
        // coalescing — the same path a Mac's device change takes, and the same `gaps` entry.
        observers.append(
            notifications.addObserver(
                forName: AVAudioSession.routeChangeNotification, object: nil, queue: nil
            ) { [weak self] _ in
                self?.deviceChanged("route_change")
            }
        )
        observers.append(
            notifications.addObserver(
                forName: .AVAudioEngineConfigurationChange, object: engine, queue: nil
            ) { [weak self] _ in
                self?.deviceChanged("engine_configuration_change")
            }
        )
    }

    private func stopObserving() {
        observers.forEach { notifications.removeObserver($0) }
        observers.removeAll()
    }

    /// A call or Siri (docs/03 `silenced`): what [Interruption] decided, done.
    ///
    /// Under [lock] from the decision to the resume, so that `stop` cannot land in the middle of it.
    private func interrupted(by note: Notification) {
        lock.lock()
        defer { lock.unlock() }
        act(on: interruption.notified(note.userInfo))
    }

    /// The hardware moved under the tap. While a call holds the microphone it is only remembered
    /// (see [Interruption.deviceChanged]); otherwise it goes straight to the recorder's restart.
    private func deviceChanged(_ reason: String) {
        lock.lock()
        defer { lock.unlock() }
        act(on: interruption.deviceChanged(reason: reason))
    }

    /// On [lock], from one of the two above.
    private func act(on action: Interruption.Action) {
        switch action {
        case .ignore:
            break

        case .silenced:
            onSilence?(true)

        case .resume:
            onSilence?(false)
            do {
                try session.setActive(true)
                try engine.start()
            } catch {
                // The tap and the converter are still the ones the recording started with, and
                // there is no audio reaching them: the restart path takes the input down and builds
                // it again, and writes the `gaps` entry for the time it was gone.
                onConfigurationChange?(Interruption.resumeFailed)
            }

        case .resumeByRestart(let reason):
            onSilence?(false)
            onConfigurationChange?(reason)

        case .restart(let reason):
            onConfigurationChange?(reason)
        }
    }
}

/// What one input knows about being interrupted, and every decision that follows from it
/// (docs/13 deliverable 1).
///
/// A value, and pure, because the doing needs a microphone a test process cannot have: an
/// `AVAudioEngine` input tap inside an xctest bundle is killed by TCC — the runner carries no
/// `NSMicrophoneUsageDescription` — so the session and the engine are exercised by the app's own
/// recording test, and the rules are exercised here.
struct Interruption: Equatable {
    enum Action: Equatable {
        /// An `.ended` for an interruption that began before this recording did — the app was
        /// launched during a call — a notification with nothing in it, or anything at all once the
        /// input has been stopped.
        case ignore
        /// docs/03 `silenced` starts: the system has already stopped the engine.
        case silenced
        /// `.ended` with `.shouldResume` — the session and the engine come back, and the tap that
        /// is still installed carries on into the same segment.
        case resume
        /// The interruption is over and resuming in place is not the answer: the silence closes and
        /// the recorder's restart path rebuilds the input and writes the `gaps` entry.
        case resumeByRestart(reason: String)
        /// The hardware moved with no call in the way — straight to the restart path.
        case restart(reason: String)
    }

    /// The tap is installed and this input is the running one. A recording the user stopped during
    /// a call takes the tap and the session down, and the `.ended` that arrives after that has
    /// nothing to resume — reactivating the session then would light the microphone indicator for
    /// a recording that is already finalized.
    var tapped = false
    private(set) var interrupted = false
    /// A device change that arrived while the call held the microphone, kept for the `.ended`.
    private(set) var pendingRestart: String?

    /// The `gaps` reason a resume the system offered but that did not come back is written with.
    static let resumeFailed = "interruption_resume_failed"
    /// … and the one for an interruption the system does not offer to resume at all.
    static let noResume = "interruption_no_resume"

    mutating func notified(_ userInfo: [AnyHashable: Any]?) -> Action {
        guard tapped else { return .ignore }
        guard let raw = userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw)
        else { return .ignore }
        switch type {
        case .began:
            interrupted = true
            return .silenced

        case .ended:
            guard interrupted else { return .ignore }
            interrupted = false
            // A device change waited out the call (see [deviceChanged]): the session is not the one
            // the recording attached to any more, so it is rebuilt rather than resumed in place.
            if let deferred = pendingRestart {
                pendingRestart = nil
                return .resumeByRestart(reason: deferred)
            }
            let options = AVAudioSession.InterruptionOptions(
                rawValue: userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
            )
            return options.contains(.shouldResume) ? .resume : .resumeByRestart(reason: Self.noResume)

        @unknown default:
            return .ignore
        }
    }

    /// A route change or an engine configuration change — a Bluetooth headset connecting, say.
    ///
    /// While a call holds the microphone this is *not* handed on: the recorder's restart would
    /// activate a session the call still owns, throw, and end as a fatal error the recording that
    /// was about to resume (Sol M5-L2 review). It is remembered instead and taken at the `.ended`,
    /// the latest one winning — they are all the same question, and the answer is one restart.
    mutating func deviceChanged(reason: String) -> Action {
        guard tapped else { return .ignore }
        guard !interrupted else {
            pendingRestart = reason
            return .ignore
        }
        return .restart(reason: reason)
    }

    /// The input was stopped: nothing is owed to a recording that is over.
    mutating func stopped() {
        tapped = false
        interrupted = false
        pendingRestart = nil
    }
}
#endif
