#if os(macOS)
import AVFoundation
import CoreAudio
import Foundation
import os

/// System audio as a Core Audio process tap (docs/12 "캡처 파이프라인", macOS 14.4+): a global tap
/// with this process left out of it, wrapped in a private aggregate device around the default output
/// device, read by an IOProc.
///
/// Global-and-excluded rather than per-app on purpose. The meeting apps worth capturing are a moving
/// target — Zoom, a browser tab, Teams inside a WebView — and a tap that has to name them is a tap
/// that misses the next one. Excluding ourselves is what keeps the recording from being fed back
/// into itself.
///
/// It heals itself. The default output device changes, its rate changes, or the IOProc simply stops
/// being called, and the tap has to be built again from nothing (the Screenpipe pattern); the
/// microphone, meanwhile, must not stop — it is the recording's timeline. So none of that goes
/// through the recorder's restart machinery: this rebuilds on its own queue and reports the outage
/// through [onOutage], which the recorder writes into the meta's `gaps`.
///
/// There is no way to ask whether the tap is permitted (docs/12 "권한"): the prompt is raised by the
/// attempt, `NSAudioCaptureUsageDescription` is what makes it possible, and a refusal shows up as an
/// error from `AudioHardwareCreateProcessTap` — or, on a machine that has already refused, as an
/// IOProc that runs and delivers silence. The first is [RecorderError.Kind.systemAudioUnavailable];
/// the second is indistinguishable from nobody playing anything, and is not detected here.
final class ProcessTapCapture: SystemAudioInput {
    /// docs/12: the IOProc runs on the output device's IO cycle and is called even when nothing is
    /// playing (measured: ~10.7 ms apart, silence in the buffers). Ten seconds of nothing is a tap
    /// that has died under us.
    static let silenceTimeoutSec: Double = 10
    private static let watchIntervalSec: Double = 2

    var onOutage: ((String, TimeInterval) -> Void)?

    private let control = DispatchQueue(label: "app.recly.mac.recorder.tap")
    /// The IOProc's own queue, and deliberately not [control]. Core Audio's IO thread dispatches
    /// the block onto whatever queue it was given and waits for it, while a teardown on [control]
    /// waits for the IOProc to quiesce (`AudioDeviceDestroyIOProcID` does not return while one is
    /// running) — one queue for both is those two waiting for each other.
    private let io = DispatchQueue(label: "app.recly.mac.recorder.tap.io")
    private let lock = NSLock()

    private var live: Live?
    private var onBuffer: ((AVAudioPCMBuffer) -> Void)?
    private var deviceListener: AudioObjectPropertyListenerBlock?
    private var watchdog: DispatchSourceTimer?
    /// Monotonic, and written by the IOProc: the watchdog's only evidence that the tap is alive.
    private var lastCallbackSec: Double = 0
    /// An outage that has begun and not yet been reported, because the tap has not come back yet.
    private var outage = TapOutage()
    /// A stream count that is not one has been logged: one line says it as well as one every IO
    /// cycle would.
    private var reportedStreams = false

    private static let log = os.Logger(subsystem: "app.recly.mac", category: "capture")

    /// Everything one tap owns, so a teardown is one nil-out and cannot half-happen.
    private struct Live {
        let tapID: AudioObjectID
        let aggregateID: AudioDeviceID
        let procID: AudioDeviceIOProcID
        let format: AVAudioFormat
        let deviceName: String
        let deviceRateHz: Double
    }

    var outputDeviceName: String? {
        lock.withLock { live?.deviceName }
    }

    /// Called from the recorder's control queue and run on this one, which is also the watchdog's
    /// and the device listener's: everything that builds or tears a tap down happens in one order,
    /// so a re-creation the watchdog started cannot be halfway through building when a stop
    /// arrives. The IOProc is on [io] and can be delivering throughout — [lock] is what makes that
    /// safe, and the teardown's `AudioDeviceDestroyIOProcID` is what ends it.
    ///
    /// The build is where the permission prompt happens, and only a build that worked arms the
    /// listener and the watchdog — a start that failed leaves nothing behind to fire.
    func start(_ onBuffer: @escaping (AVAudioPCMBuffer) -> Void) throws {
        try control.sync {
            lock.withLock { self.onBuffer = onBuffer }
            do {
                try build()
            } catch {
                lock.withLock { self.onBuffer = nil }
                throw error
            }
            watch()
        }
    }

    func stop() {
        // An outage that was still open when the recording ended is one the meta would otherwise
        // never hear about: the rebuild that would have closed it is not going to happen now.
        let open: (reason: String, seconds: TimeInterval)? = control.sync {
            unwatch()
            teardown()
            return lock.withLock {
                onBuffer = nil
                return outage.end(now: Date())
            }
        }
        if let open {
            onOutage?(open.reason, open.seconds)
        }
    }

    // MARK: - Building and tearing down

    private func build() throws {
        guard let device = SystemAudioDevice.defaultOutput() else {
            throw RecorderError("there is no output device to tap", kind: .systemAudioUnavailable)
        }
        guard let ourProcess = CoreAudioProperty.processObject(pid: getpid()) else {
            throw RecorderError("this app's audio process was not found", kind: .systemAudioUnavailable)
        }

        let description = CATapDescription(monoGlobalTapButExcludeProcesses: [ourProcess])
        description.name = "Rec system audio"
        // Visible only to us, and the audio still reaches the speakers: a muted tap would take the
        // meeting away from the user it is recording.
        description.isPrivate = true
        description.muteBehavior = .unmuted

        var tapID = AudioObjectID(kAudioObjectUnknown)
        let created = AudioHardwareCreateProcessTap(description, &tapID)
        guard created == noErr, tapID != AudioObjectID(kAudioObjectUnknown) else {
            throw RecorderError(
                "system audio recording is not permitted (\(created))",
                kind: .systemAudioUnavailable
            )
        }

        do {
            guard let asbd: AudioStreamBasicDescription = CoreAudioProperty.value(
                of: tapID, selector: kAudioTapPropertyFormat
            ), asbd.mSampleRate > 0 else {
                throw RecorderError("the tap reports no format", kind: .systemAudioUnavailable)
            }
            // A mono global tap is mono by construction; anything else and the buffer below would be
            // read as the wrong thing rather than merely sound wrong.
            guard asbd.mChannelsPerFrame == 1, let format = AVAudioFormat(
                commonFormat: .pcmFormatFloat32,
                sampleRate: asbd.mSampleRate,
                channels: 1,
                interleaved: false
            ) else {
                throw RecorderError(
                    "the tap format is not mono Float32 (\(asbd.mChannelsPerFrame)ch)",
                    kind: .systemAudioUnavailable
                )
            }

            let aggregateID = try Self.makeAggregate(around: device, tap: description)
            do {
                let procID = try makeIOProc(on: aggregateID, format: format)
                lock.withLock {
                    live = Live(
                        tapID: tapID,
                        aggregateID: aggregateID,
                        procID: procID,
                        format: format,
                        deviceName: device.name,
                        deviceRateHz: device.nominalSampleRateHz
                    )
                    lastCallbackSec = Self.nowSec
                }
                let started = AudioDeviceStart(aggregateID, procID)
                guard started == noErr else {
                    teardown()
                    throw RecorderError("the tap did not start (\(started))", kind: .systemAudioUnavailable)
                }
            } catch {
                AudioHardwareDestroyAggregateDevice(aggregateID)
                throw error
            }
        } catch {
            AudioHardwareDestroyProcessTap(tapID)
            throw error
        }
    }

    /// docs/12: the tap is read through an aggregate device, not directly. Private, so it does not
    /// appear in the user's sound settings, and built around the current default output device —
    /// which is why a change of that device means building this again.
    ///
    /// Separate from the call that creates it so a test can read the two keys that decide whether a
    /// recording starts at all and whether what arrives is the meeting or the user's own headset.
    static func aggregateDescription(around device: SystemAudioDevice, tap: CATapDescription) -> [String: Any] {
        [
            kAudioAggregateDeviceNameKey: "Rec System Audio",
            kAudioAggregateDeviceUIDKey: UUID().uuidString,
            kAudioAggregateDeviceMainSubDeviceKey: device.uid,
            kAudioAggregateDeviceIsPrivateKey: true,
            kAudioAggregateDeviceIsStackedKey: false,
            // `false`, and this is not a preference. AudioHardware.h on this key: "calling
            // AudioDeviceStart with the aggregate device will wait until a tapped process begins
            // receiving its first audio from any tapped applications." Starting a meeting recording
            // in a quiet room would park `AudioDeviceStart` on the control queue for as long as
            // nothing plays — with the microphone already attached and writing all three files, and
            // the session never leaving `.starting`. Off, the IOProc runs from the first cycle and
            // delivers silence until something plays, which is also what the watchdog expects.
            kAudioAggregateDeviceTapAutoStartKey: false,
            kAudioAggregateDeviceSubDeviceListKey: [[
                kAudioSubDeviceUIDKey: device.uid,
                // A duplex default output — AirPods, a USB headset, an audio interface, which is
                // exactly the hardware the echo policy asks the user for — presents its own input
                // channels through the aggregate device, and [receive] reads the first stream it is
                // handed. No input channels from the sub-device leaves the tap as the only input
                // there is, so the first stream is the meeting and not the user's own microphone.
                kAudioSubDeviceInputChannelsKey: 0,
            ]],
            // Core Audio's own drift compensation, between the tap and the device it is stacked on.
            // `DriftCompensator` is about the other drift — this stream against the microphone.
            kAudioAggregateDeviceTapListKey: [[
                kAudioSubTapUIDKey: tap.uuid.uuidString,
                kAudioSubTapDriftCompensationKey: true,
            ]],
        ]
    }

    private static func makeAggregate(
        around device: SystemAudioDevice,
        tap: CATapDescription
    ) throws -> AudioDeviceID {
        let description = aggregateDescription(around: device, tap: tap)
        var aggregateID = AudioDeviceID(kAudioObjectUnknown)
        let status = AudioHardwareCreateAggregateDevice(description as CFDictionary, &aggregateID)
        guard status == noErr, aggregateID != AudioDeviceID(kAudioObjectUnknown) else {
            throw RecorderError("the aggregate device could not be created (\(status))", kind: .systemAudioUnavailable)
        }
        return aggregateID
    }

    private func makeIOProc(on aggregateID: AudioDeviceID, format: AVAudioFormat) throws -> AudioDeviceIOProcID {
        var procID: AudioDeviceIOProcID?
        let status = AudioDeviceCreateIOProcIDWithBlock(&procID, aggregateID, io) {
            [weak self] _, inInputData, _, _, _ in
            self?.receive(inInputData, format: format)
        }
        guard status == noErr, let procID else {
            throw RecorderError("the tap IOProc could not be created (\(status))", kind: .systemAudioUnavailable)
        }
        return procID
    }

    /// On [io], dispatched from Core Audio's IO thread, and free to run alongside anything on
    /// [control]. The buffer it hands over is the device's own memory and is valid for exactly this
    /// call, so the frames are copied out before anything else happens to them.
    private func receive(_ input: UnsafePointer<AudioBufferList>, format: AVAudioFormat) {
        let list = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: input))
        // The tap is meant to be the only input stream on the aggregate device (see the zero input
        // channels in [aggregateDescription]); anything else and the buffer taken below could be a
        // duplex device's own microphone rather than the meeting. Still `first` — a diagnostic, not
        // a guess at which stream is the right one.
        if list.count > 1 {
            let unreported = lock.withLock {
                let firstTime = !reportedStreams
                reportedStreams = true
                return firstTime
            }
            if unreported {
                Self.log.error("rec.tap.unexpectedStreams count=\(list.count, privacy: .public)")
            }
        }
        guard let first = list.first,
              let source = first.mData?.assumingMemoryBound(to: Float.self)
        else { return }
        let frames = AVAudioFrameCount(first.mDataByteSize / UInt32(MemoryLayout<Float>.size))
        guard frames > 0,
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames),
              let destination = buffer.floatChannelData
        else { return }
        destination[0].update(from: source, count: Int(frames))
        buffer.frameLength = frames

        let deliver: ((AVAudioPCMBuffer) -> Void)? = lock.withLock {
            // No `live` is a tap that has been torn down: a callback that slipped in beside the
            // teardown has nothing to deliver into, and stamping the clock would tell the watchdog
            // a dead tap is alive.
            guard live != nil else { return nil }
            lastCallbackSec = Self.nowSec
            return onBuffer
        }
        deliver?(buffer)
    }

    /// On [control]. The IOProc is destroyed before anything else and before [live] is let go:
    /// `AudioDeviceDestroyIOProcID` does not return while a callback is running, so once it has
    /// there is no callback left that could reach a device already destroyed. The callbacks that
    /// arrive in the window before it see [live] and are delivered — they are real audio.
    private func teardown() {
        guard let open = lock.withLock({ live }) else { return }
        AudioDeviceStop(open.aggregateID, open.procID)
        AudioDeviceDestroyIOProcID(open.aggregateID, open.procID)
        lock.withLock { live = nil }
        AudioHardwareDestroyAggregateDevice(open.aggregateID)
        AudioHardwareDestroyProcessTap(open.tapID)
    }

    // MARK: - Re-creation

    /// On [control]. The three things that end a tap (docs/12 "tap 재생성"): the default output device changes,
    /// its format changes, or the IOProc goes quiet. The first is a listener because it is an event;
    /// the other two are a poll on the same queue, because a format listener would have to be moved
    /// to a different device every time the first one fires — two listeners' worth of bookkeeping to
    /// learn what one read every two seconds says just as well.
    private func watch() {
        let block: AudioObjectPropertyListenerBlock = { [weak self] _, _ in
            self?.recreate(reason: "output_device_change")
        }
        var address = CoreAudioProperty.address(kAudioHardwarePropertyDefaultOutputDevice)
        if AudioObjectAddPropertyListenerBlock(
            AudioObjectID(kAudioObjectSystemObject), &address, control, block
        ) == noErr {
            deviceListener = block
        }

        let timer = DispatchSource.makeTimerSource(queue: control)
        timer.schedule(deadline: .now() + Self.watchIntervalSec, repeating: Self.watchIntervalSec)
        timer.setEventHandler { [weak self] in self?.tick() }
        watchdog = timer
        timer.resume()
    }

    private func unwatch() {
        watchdog?.cancel()
        watchdog = nil
        if let deviceListener {
            var address = CoreAudioProperty.address(kAudioHardwarePropertyDefaultOutputDevice)
            AudioObjectRemovePropertyListenerBlock(
                AudioObjectID(kAudioObjectSystemObject), &address, control, deviceListener
            )
            self.deviceListener = nil
        }
    }

    /// On [control]. A callback can be running on [io] at the same time — [lastCallbackSec] and
    /// [live] are read under [lock] for exactly that reason, and the worst a race costs is a tick
    /// that finds the tap one callback older than it is.
    private func tick() {
        let (open, quietFor): (Live?, Double) = lock.withLock {
            (live, Self.nowSec - lastCallbackSec)
        }
        // No tap at all means a rebuild that failed; this is the retry, and the outage it belongs to
        // is still the one that opened when it first went away.
        guard let open else { return recreate(reason: "system_tap_rebuild") }
        if quietFor > Self.silenceTimeoutSec {
            // Not `now`: the audio stopped at the last callback, and the ten seconds it took to be
            // sure of that are missing from the recording exactly as the rebuild is.
            return recreate(reason: "system_tap_silent", since: Date().addingTimeInterval(-quietFor))
        }
        let rate = SystemAudioDevice.defaultOutput()?.nominalSampleRateHz ?? open.deviceRateHz
        if rate > 0, rate != open.deviceRateHz {
            recreate(reason: "output_format_change")
        }
    }

    /// On [control], with the listener and the watchdog, so two re-creations cannot interleave. The
    /// old tap stops delivering inside [teardown] rather than by being on this queue — the IOProc
    /// is destroyed there, and that is what a callback cannot outlive. What the recording loses is
    /// the wall-clock time this takes, which is what goes into the meta's `gaps`.
    ///
    /// A build that fails is not the end of the recording — a meeting with no `sys` track is worth
    /// more than no meeting — so nothing is reported and the watchdog comes back in two seconds.
    /// The microphone never learns any of this happened.
    private func recreate(reason: String, since: Date = Date()) {
        let live: Bool = lock.withLock {
            guard onBuffer != nil else { return false }
            outage.begin(reason: reason, since: since)
            return true
        }
        guard live else { return }
        teardown()
        guard (try? build()) != nil else { return }
        guard let closed = lock.withLock({ outage.end(now: Date()) }) else { return }
        onOutage?(closed.reason, closed.seconds)
    }

    private static var nowSec: Double { ProcessInfo.processInfo.systemUptime }
}

/// The outage bookkeeping, kept apart from Core Audio so it can be tested without a tap: when the
/// audio stopped, what stopped it, and how long it was missing for.
///
/// One outage stays open however many rebuild attempts it takes — a `gaps` list with an entry every
/// two seconds says nothing the one entry does not — and it began when the audio stopped, which for
/// a watchdog is [ProcessTapCapture.silenceTimeoutSec] before it could possibly have noticed.
struct TapOutage {
    private var open: (since: Date, reason: String)?

    /// Opens one if none is open, so the first reason and the first instant are the ones that
    /// survive: a rebuild that failed and is being retried is the same outage, not a new one.
    mutating func begin(reason: String, since: Date) {
        guard open == nil else { return }
        open = (since: since, reason: reason)
    }

    /// Closes whatever is open, if anything: what to call it, and how long the audio was gone for.
    mutating func end(now: Date) -> (reason: String, seconds: TimeInterval)? {
        guard let taken = open else { return nil }
        open = nil
        return (taken.reason, now.timeIntervalSince(taken.since))
    }
}
#endif
