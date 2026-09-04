import AVFoundation
import Foundation

#if os(macOS)
import CoreAudio
#endif

/// Where a recording's audio comes from. `MicrophoneInput` is the microphone half (the system half
/// is [SystemAudioInput]), and it exists as a seam because the microphone is the one thing an
/// automated run cannot have: with an input of its own a test drives the boundary, the restart and
/// the stop through the real `SegmentedRecorder`, on real files, with no hardware and no prompt.
///
/// Everything except `onConfigurationChange` is touched on the recorder's control queue, one call
/// at a time; `onConfigurationChange` arrives on whatever thread the system used.
protocol AudioInput: AnyObject {
    /// The format buffers arrive in — `nil` when there is no usable input device.
    var format: AVAudioFormat? { get }

    var isRunning: Bool { get }

    /// Reports that the hardware moved under the tap (docs/12): a headset unplugged, the default
    /// input switched in System Settings, a format change. The string is the `gaps` reason the
    /// outage is written to the meta with.
    var onConfigurationChange: ((String) -> Void)? { get set }

    /// Reports the microphone being taken away by something else and given back (docs/03
    /// `silenced`): on iOS an interruption — a call, Siri. `true` when it goes, `false` when it is
    /// back. Never called by [MicrophoneInput]: nothing on a Mac takes the input away without also
    /// changing the device, which is [onConfigurationChange]'s business.
    var onSilence: ((Bool) -> Void)? { get set }

    /// Whatever has to be settled before a recording may be created — for the microphone, the
    /// permission prompt. Nothing is on disk yet when this throws.
    func authorize() async throws

    /// Installs the tap and starts delivering. Buffers arrive on the input's own thread.
    func start(_ onBuffer: @escaping (AVAudioPCMBuffer) -> Void) throws

    /// Removes the tap and stops. Doing this when nothing was started is not an error.
    func stop()
}

/// The system-audio half of a meeting recording — `ProcessTapCapture` in the app, a fake in the
/// tests. Separate from [AudioInput] because it does not share the recorder's restart machinery:
/// a tap that has to be re-created (default output device changed, format changed, the callback
/// went quiet) rebuilds *itself* and reports the outage, rather than taking the microphone down
/// with it. The microphone's audio is the recording's timeline, and it must not stop because the
/// system's did.
///
/// Started and stopped on the recorder's control queue, one call at a time; the buffer callback and
/// [onOutage] arrive on whatever thread Core Audio used.
protocol SystemAudioInput: AnyObject {
    /// The output device the tap is on, for the menu (docs/12 M4-L3 "캡처 중인 출력 장치명").
    var outputDeviceName: String? { get }

    /// An outage this input covered by itself: the reason for the meta's `gaps` and how long the
    /// audio was missing for.
    var onOutage: ((String, TimeInterval) -> Void)? { get set }

    /// Builds the tap and starts delivering. Buffers carry their own format — the output device's
    /// rate, which a re-creation may change — so the reader converts from whatever arrives.
    /// Throws `RecorderError(kind: .systemAudioUnavailable)` when there is no tap to be had.
    func start(_ onBuffer: @escaping (AVAudioPCMBuffer) -> Void) throws

    func stop()
}

/// `AVAudioEngine`'s input node, which on macOS is the default input device.
///
/// It owns the two notifications a device change produces as well, because they are about *this*
/// engine and nothing above it can subscribe to them without holding the engine itself.
final class MicrophoneInput: AudioInput {
    /// Replaced with a fresh one whenever it is idle and asked for anything (see [refreshIfIdle]).
    /// An engine that outlives a device change keeps the input node's *last* format, while the
    /// unit it builds at start-up reads the device's *current* one — and `installTap` raises an
    /// uncatchable `NSException` when the two disagree. Seen with AirPods, whose rate moves between
    /// 24 and 48 kHz as they leave and enter the call profile.
    private var engine = AVAudioEngine()
    /// docs/12 "에코": off by default and only ever turned on by an explicit flag. Apple's voice
    /// processing is tuned for telephony — it narrows the band and gates hard — so paying that for
    /// echo the user can avoid with headphones is the wrong default (M4-L3 deliverable 4).
    private let voiceProcessing: Bool
    private var tapped = false
    private var configurationObserver: NSObjectProtocol?
    #if os(macOS)
    private var inputListener: AudioObjectPropertyListenerBlock?
    private let listenerQueue = DispatchQueue(label: "app.recly.mac.recorder.devices")
    #endif

    var onConfigurationChange: ((String) -> Void)?
    /// Never called here — see the protocol. `IOSAudioInput` is the one that has interruptions.
    var onSilence: ((Bool) -> Void)?

    init(voiceProcessing: Bool = false) {
        self.voiceProcessing = voiceProcessing
    }

    var format: AVAudioFormat? {
        refreshIfIdle()
        return currentFormat
    }

    private var currentFormat: AVAudioFormat? {
        let format = engine.inputNode.outputFormat(forBus: 0)
        return format.sampleRate > 0 && format.channelCount > 0 ? format : nil
    }

    var isRunning: Bool { engine.isRunning }

    func authorize() async throws {
        try await Self.requireMicrophone()
    }

    func start(_ onBuffer: @escaping (AVAudioPCMBuffer) -> Void) throws {
        refreshIfIdle()
        guard let format = currentFormat else {
            throw RecorderError("the default input device reports no usable format")
        }
        // The check `installTap` makes, made here so it is an error and not an abort: the node's
        // format against the hardware's. A fresh engine keeps them together; this is for the case
        // where the device changed in the moment between the two.
        let hardware = engine.inputNode.inputFormat(forBus: 0)
        guard format.sampleRate == hardware.sampleRate, format.channelCount == hardware.channelCount else {
            throw RecorderError(
                "the input format moved under the tap (\(format.sampleRate) Hz/\(format.channelCount)ch"
                    + " against \(hardware.sampleRate) Hz/\(hardware.channelCount)ch)"
            )
        }
        engine.inputNode.installTap(onBus: 0, bufferSize: 4096, format: format) { buffer, _ in
            onBuffer(buffer)
        }
        tapped = true
        engine.prepare()
        try engine.start()
        observeConfigurationChanges()
    }

    func stop() {
        stopObservingConfigurationChanges()
        if tapped {
            engine.inputNode.removeTap(onBus: 0)
            tapped = false
        }
        engine.stop()
    }

    /// A stopped engine is thrown away rather than reused, so the format read next — by the
    /// recorder building its converter, and by [start] installing the tap — is the device's now.
    /// Voice processing goes on here, before any format is read: turning it on reconfigures the
    /// input node, which changes the format the tap then has to be installed with. `false` is
    /// already the engine's state, so saying it out loud would only be a chance to be wrong.
    private func refreshIfIdle() {
        guard !engine.isRunning else { return }
        engine = AVAudioEngine()
        if voiceProcessing {
            try? engine.inputNode.setVoiceProcessingEnabled(true)
        }
    }

    /// docs/12 "권한": `NSMicrophoneUsageDescription` is what makes the prompt possible; a refusal
    /// is not an error the app can retry its way out of, so it comes back as its own kind and the
    /// shell answers it with the System Settings deep link.
    static func requireMicrophone() async throws {
        #if os(watchOS)
        // `AVCaptureDevice` has no watchOS slice at all — the whole capture stack is missing there.
        // `AVAudioApplication` (watchOS 10, and the deployment floor RecKit already sets) answers
        // the same three states for the same TCC record.
        switch AVAudioApplication.shared.recordPermission {
        case .granted:
            return
        case .undetermined:
            guard await AVAudioApplication.requestRecordPermission() else {
                throw RecorderError("the microphone permission was refused", kind: .microphoneDenied)
            }
        default:
            throw RecorderError("the microphone permission is off", kind: .microphoneDenied)
        }
        #else
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized:
            return
        case .notDetermined:
            guard await AVCaptureDevice.requestAccess(for: .audio) else {
                throw RecorderError("the microphone permission was refused", kind: .microphoneDenied)
            }
        default:
            throw RecorderError("the microphone permission is off", kind: .microphoneDenied)
        }
        #endif
    }

    /// Both of these fire for one device change; the recorder coalesces them (see
    /// `SegmentedRecorder.requestRestart`), so subscribing to both costs nothing and neither one
    /// alone is reliable — the HAL listener misses a format change, the notification misses a
    /// device that was swapped while the engine was already down.
    private func observeConfigurationChanges() {
        configurationObserver = NotificationCenter.default.addObserver(
            forName: .AVAudioEngineConfigurationChange,
            object: engine,
            queue: nil
        ) { [weak self] _ in
            self?.onConfigurationChange?("engine_configuration_change")
        }
        #if os(macOS)
        let block: AudioObjectPropertyListenerBlock = { [weak self] _, _ in
            self?.onConfigurationChange?("input_device_change")
        }
        var address = Self.defaultInputAddress
        if AudioObjectAddPropertyListenerBlock(
            AudioObjectID(kAudioObjectSystemObject), &address, listenerQueue, block
        ) == noErr {
            inputListener = block
        }
        #endif
    }

    private func stopObservingConfigurationChanges() {
        if let configurationObserver {
            NotificationCenter.default.removeObserver(configurationObserver)
            self.configurationObserver = nil
        }
        #if os(macOS)
        if let inputListener {
            var address = Self.defaultInputAddress
            AudioObjectRemovePropertyListenerBlock(
                AudioObjectID(kAudioObjectSystemObject), &address, listenerQueue, inputListener
            )
            self.inputListener = nil
        }
        #endif
    }

    #if os(macOS)
    private static var defaultInputAddress: AudioObjectPropertyAddress {
        AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDefaultInputDevice,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
    }
    #endif
}
