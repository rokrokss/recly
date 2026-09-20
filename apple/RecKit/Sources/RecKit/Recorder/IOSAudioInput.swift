#if os(iOS) || os(watchOS)
import AVFoundation
import Foundation

/// Microphone-only capture for iPhone and Apple Watch. Engine/session operations and notifications
/// share one queue; hardware callbacks only transfer owned buffers to the writer queue.
final class IOSAudioInput: AudioInput {
    private var engine = AVAudioEngine()
    private let session: AVAudioSession
    private let notifications: NotificationCenter
    private let control = DispatchQueue(label: "app.recly.mobile.microphone.control")
    private let delivery = AudioDeliveryQueue(label: "app.recly.mobile.microphone.delivery")
    private var observers: [NSObjectProtocol] = []
    private var interruption = Interruption()
    private var generation = 0
    private var prepared = false
    private var mediaServicesLost = false
    private var resumingSilence = false
    private var routeCheck: DispatchWorkItem?
    private var attachedRoute: String?
    private var rejectedInputs: Set<String> = []

    var onConfigurationChange: ((String) -> Void)?
    var onSilence: ((Bool) -> Void)?
    var onFailure: ((RecorderError) -> Void)?
    var onDiagnostic: ((CaptureDiagnostic) -> Void)?
    var retriesTransientStart: Bool { true }

    init(session: AVAudioSession = .sharedInstance(), notifications: NotificationCenter = .default) {
        self.session = session
        self.notifications = notifications
    }

    var format: AVAudioFormat? { control.sync { currentFormat } }
    var isRunning: Bool { control.sync { !mediaServicesLost && engine.isRunning } }
    var configurationID: String? { control.sync { mediaServicesLost ? attachedRoute : routeIdentity } }
    var deviceName: String? { control.sync { session.currentRoute.inputs.first?.portName } }

    private var currentFormat: AVAudioFormat? {
        guard !mediaServicesLost else { return nil }
        let format = engine.inputNode.outputFormat(forBus: 0)
        return format.sampleRate > 0 && format.channelCount > 0 ? format : nil
    }

    private var routeIdentity: String {
        #if os(iOS)
        return session.currentRoute.inputs.map { "\($0.uid):\($0.selectedDataSource?.dataSourceID.stringValue ?? "")" }
            .joined(separator: ",")
        #else
        return session.currentRoute.inputs.map(\.uid).joined(separator: ",")
        #endif
    }

    func authorize() async throws {
        try await MicrophoneInput.requireMicrophone()
        control.sync {
            resumingSilence = false
            // A new user-initiated recording may retry after the previous media-server failure.
            // Internal restarts must keep the failure latched until that explicit action.
            mediaServicesLost = false
            rejectedInputs.removeAll()
        }
    }

    func prepare() throws { try control.sync { try prepareLocked() } }

    private func prepareLocked() throws {
        guard !mediaServicesLost else { throw RecorderError("audio services are unavailable") }
        guard !prepared else { return }
        // A fresh engine cannot keep an input node's format from the previous Bluetooth profile.
        engine = AVAudioEngine()
        #if os(watchOS)
        if #available(watchOS 11, *) {
            try session.setCategory(.record, mode: .default, options: [.allowBluetoothHFP])
        } else {
            try session.setCategory(.record, mode: .default)
        }
        #else
        try session.setCategory(.playAndRecord, mode: .default, options: [.allowBluetoothHFP])
        try session.setPreferredSampleRate(Double(SegmentedRecorder.sampleRateHz))
        #endif
        try session.setActive(true)
        #if os(iOS)
        // watchOS exposes the actual route but does not allow setPreferredInput. Its system
        // chooses from the routes enabled by the category above.
        let ports = session.availableInputs ?? []
        rejectedInputs.formIntersection(Set(ports.map(\.uid)))
        let current = session.currentRoute.inputs.first?.uid
        let selected = MobileMicrophoneSelection.choose(
            ports.filter { !self.rejectedInputs.contains($0.uid) }.compactMap(Self.candidate), current: current
        )
        let preferred = ports.first { $0.uid == selected }
        if session.preferredInput?.uid != preferred?.uid {
            do {
                try session.setPreferredInput(preferred)
            } catch {
                if let preferred { rejectedInputs.insert(preferred.uid) }
                // A refused external input must not discard a recording the OS default can make.
                try session.setPreferredInput(nil)
                onDiagnostic?(CaptureDiagnostic(event: "input_preference_rejected", source: "mic",
                    device: preferred?.portName, detail: String(describing: error)))
            }
        }
        #endif
        guard session.isInputAvailable, currentFormat != nil else {
            throw RecorderError("no microphone is available")
        }
        prepared = true
    }

    private static func candidate(_ port: AVAudioSessionPortDescription) -> MobileMicrophoneSelection.Input? {
        let priority: Int
        switch port.portType {
        case .headsetMic, .usbAudio, .lineIn: priority = 0
        case .bluetoothHFP, .bluetoothLE: priority = 1
        case .builtInMic: priority = 2
        default: return nil
        }
        return .init(id: port.uid, priority: priority)
    }

    func start(_ onBuffer: @escaping (AVAudioPCMBuffer) -> Void) throws {
        try startCaptured { onBuffer($0.buffer) }
    }

    func startCaptured(_ onBuffer: @escaping (CapturedAudio) -> Void) throws {
        try control.sync {
            try prepareLocked()
            guard let format = currentFormat else { throw RecorderError("the microphone format is unavailable") }
            let hardware = engine.inputNode.inputFormat(forBus: 0)
            guard format.sampleRate == hardware.sampleRate, format.channelCount == hardware.channelCount else {
                throw RecorderError("the microphone format changed while starting")
            }
            delivery.start(onBuffer)
            engine.inputNode.installTap(onBus: 0, bufferSize: 4096, format: format) { [delivery] buffer, when in
                let time = when.isHostTimeValid ? AVAudioTime.seconds(forHostTime: when.hostTime) : nil
                delivery.submit(CapturedAudio(buffer, hostTimeSec: time))
            }
            interruption.tapped = true
            engine.prepare()
            try engine.start()
            attachedRoute = routeIdentity
            observe()
            if resumingSilence { resumingSilence = false; onSilence?(false) }
            onDiagnostic?(CaptureDiagnostic(event: "format", source: "mic",
                device: session.currentRoute.inputs.first?.portName, rateHz: format.sampleRate))
        }
    }

    func stop() {
        control.sync {
            generation += 1
            routeCheck?.cancel()
            routeCheck = nil
            observers.forEach { notifications.removeObserver($0) }
            observers.removeAll()
            let wasTapped = interruption.tapped
            interruption.stopped()
            if !mediaServicesLost {
                if wasTapped { engine.inputNode.removeTap(onBus: 0) }
                engine.stop()
                try? session.setActive(false, options: .notifyOthersOnDeactivation)
            }
            delivery.finish()
            if delivery.droppedFrames > 0 {
                onDiagnostic?(CaptureDiagnostic(event: "delivery_overflow", source: "mic", frames: delivery.droppedFrames))
            }
            prepared = false
            attachedRoute = nil
            // Discard orphaned objects without sending them messages after a media-server loss.
            engine = AVAudioEngine()
        }
    }

    private func observe() {
        let token = generation
        func subscribe(_ name: Notification.Name, object: Any? = nil, action: @escaping (IOSAudioInput, Notification) -> Void) {
            observers.append(notifications.addObserver(forName: name, object: object, queue: nil) { [weak self] note in
                self?.control.async { [weak self] in
                    guard let self, generation == token, interruption.tapped else { return }
                    action(self, note)
                }
            })
        }
        subscribe(AVAudioSession.interruptionNotification) { input, note in
            guard !input.mediaServicesLost else { return }
            input.act(on: input.interruption.notified(note.userInfo))
        }
        subscribe(AVAudioSession.routeChangeNotification) { input, note in
            let reason = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt)
                .flatMap(AVAudioSession.RouteChangeReason.init(rawValue:))
            if reason == .newDeviceAvailable || reason == .oldDeviceUnavailable {
                input.rejectedInputs.removeAll()
            }
            input.scheduleRouteCheck()
        }
        subscribe(.AVAudioEngineConfigurationChange, object: engine) { input, _ in
            guard !input.mediaServicesLost else { return }
            input.act(on: input.interruption.deviceChanged(reason: "engine_configuration_change"))
        }
        subscribe(AVAudioSession.mediaServicesWereLostNotification) { input, _ in
            // Orphaned audio objects must not be restarted while the media server is unavailable.
            input.mediaServicesLost = true
            input.routeCheck?.cancel()
            input.interruption.stopped()
            input.delivery.finish()
            input.onSilence?(true)
            input.onDiagnostic?(CaptureDiagnostic(event: "media_services_lost", source: "mic"))
            input.onFailure?(RecorderError("audio services are unavailable; the captured audio will be saved"))
        }
        subscribe(AVAudioSession.mediaServicesWereResetNotification) { input, _ in
            input.mediaServicesLost = true
            input.delivery.finish()
            input.interruption.stopped()
            input.prepared = false
            input.engine = AVAudioEngine()
            input.onDiagnostic?(CaptureDiagnostic(event: "media_services_reset", source: "mic"))
            // Apple's reset contract requires a fresh user action before recording again.
            // Finalize the audio already captured through the shell's normal fatal-stop path.
            input.onFailure?(RecorderError("audio services reset; the captured audio will be saved"))
        }
    }

    private func scheduleRouteCheck() {
        routeCheck?.cancel()
        let token = generation
        let work = DispatchWorkItem { [weak self] in
            guard let self, generation == token, interruption.tapped, !mediaServicesLost else { return }
            #if os(iOS)
            let selected = MobileMicrophoneSelection.choose(
                (session.availableInputs ?? []).filter { !self.rejectedInputs.contains($0.uid) }.compactMap(Self.candidate),
                current: session.currentRoute.inputs.first?.uid
            )
            let changed = attachedRoute != routeIdentity || selected != session.currentRoute.inputs.first?.uid
            #else
            let changed = attachedRoute != routeIdentity
            #endif
            guard changed || !engine.isRunning else { return }
            act(on: interruption.deviceChanged(reason: "input_route_change"))
        }
        routeCheck = work
        control.asyncAfter(deadline: .now() + 0.35, execute: work)
    }

    private func act(on action: Interruption.Action) {
        switch action {
        case .ignore: break
        case .silenced:
            engine.pause()
            delivery.finish()
            onSilence?(true)
        case .resume:
            resumingSilence = true
            // Rebuild instead of resuming a tap whose hardware format may have changed during
            // the interruption. The recorder drains its converter before attaching the new one.
            onConfigurationChange?("interruption_ended")
        case .resumeByRestart(let reason):
            resumingSilence = true
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
        /// `.ended` with `.shouldResume` — rebuild the input using the current hardware format.
        case resume
        /// The interruption is over: the recorder rebuilds the input and writes a `gaps` entry.
        /// The silence closes only after the new input successfully starts.
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
