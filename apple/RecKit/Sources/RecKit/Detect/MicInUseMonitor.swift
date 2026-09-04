#if os(macOS)
import CoreAudio
import Foundation

/// Is someone *else* listening to the default input device (docs/12 "미팅 감지")?
///
/// The signal the lane names is `kAudioDevicePropertyDeviceIsRunningSomewhere` on the default input,
/// with a listener on the default input itself so the answer follows the device the user switches
/// to. That property alone cannot carry the second half of the deliverable, though: once Recly is
/// recording, *we* are the process keeping the device running, so "마이크 미사용 60초" would never
/// come true and the "녹음을 끝낼까요?" prompt would be dead code. So the device property is the
/// cheap gate and the answer is refined by the per-process input flags Core Audio has had since
/// 14.2 (`kAudioProcessPropertyIsRunningInput`), with this process left out — the same exclusion
/// `ProcessTapCapture` makes for the tap, for the same reason.
///
/// Both a listener and a poll. The listener is what makes joining a meeting register at once; the
/// poll is what notices the meeting app releasing the microphone while we are still holding it,
/// which no listener can report because the device property never changes.
public final class MicInUseMonitor {
    /// The same two seconds `ProcessTapCapture` watches its tap on: fast enough that the 60-second
    /// idle rule is not measurably late, cheap enough to leave running all day.
    static let pollIntervalSec: Double = 2

    private let queue = DispatchQueue(label: "app.recly.mac.detect.mic")
    private let lock = NSLock()

    private var onChange: ((Bool) -> Void)?
    private var poll: DispatchSourceTimer?
    /// Armed on the system object, so a change of default input moves [deviceListener] with it.
    private var defaultInputListener: AudioObjectPropertyListenerBlock?
    private var deviceListener: AudioObjectPropertyListenerBlock?
    /// The device [deviceListener] is armed on, so it can be taken off the right object again.
    private var listeningTo: AudioObjectID?
    private var value = false

    public init() {}

    /// The last reading. `false` before [start].
    public var inUse: Bool { lock.withLock { value } }

    /// [onChange] is called on this monitor's own queue, and only when the answer actually changes.
    public func start(onChange: @escaping (Bool) -> Void) {
        queue.async {
            self.lock.withLock { self.onChange = onChange }
            self.armDefaultInputListener()
            self.armDeviceListener()
            self.reread()

            let poll = DispatchSource.makeTimerSource(queue: self.queue)
            poll.schedule(deadline: .now() + Self.pollIntervalSec, repeating: Self.pollIntervalSec)
            poll.setEventHandler { [weak self] in self?.reread() }
            poll.resume()
            self.poll = poll
        }
    }

    public func stop() {
        queue.sync {
            poll?.cancel()
            poll = nil
            disarmDeviceListener()
            if let block = defaultInputListener {
                var address = CoreAudioProperty.address(kAudioHardwarePropertyDefaultInputDevice)
                AudioObjectRemovePropertyListenerBlock(
                    AudioObjectID(kAudioObjectSystemObject), &address, queue, block
                )
                defaultInputListener = nil
            }
            lock.withLock { onChange = nil }
        }
    }

    // MARK: - Listeners

    private func armDefaultInputListener() {
        guard defaultInputListener == nil else { return }
        let block: AudioObjectPropertyListenerBlock = { [weak self] _, _ in
            guard let self else { return }
            // The old device's listener is on a device nobody is talking to any more.
            self.disarmDeviceListener()
            self.armDeviceListener()
            self.reread()
        }
        var address = CoreAudioProperty.address(kAudioHardwarePropertyDefaultInputDevice)
        guard AudioObjectAddPropertyListenerBlock(
            AudioObjectID(kAudioObjectSystemObject), &address, queue, block
        ) == noErr else { return }
        defaultInputListener = block
    }

    private func armDeviceListener() {
        guard let device = Self.defaultInput() else { return }
        let block: AudioObjectPropertyListenerBlock = { [weak self] _, _ in self?.reread() }
        var address = CoreAudioProperty.address(kAudioDevicePropertyDeviceIsRunningSomewhere)
        guard AudioObjectAddPropertyListenerBlock(device, &address, queue, block) == noErr else { return }
        deviceListener = block
        listeningTo = device
    }

    private func disarmDeviceListener() {
        guard let device = listeningTo, let block = deviceListener else { return }
        var address = CoreAudioProperty.address(kAudioDevicePropertyDeviceIsRunningSomewhere)
        AudioObjectRemovePropertyListenerBlock(device, &address, queue, block)
        deviceListener = nil
        listeningTo = nil
    }

    // MARK: - Reading

    private func reread() {
        let next = Self.read()
        let handler: ((Bool) -> Void)? = lock.withLock {
            guard next != value, let onChange else { return nil }
            value = next
            return onChange
        }
        handler?(next)
    }

    private static func read() -> Bool {
        guard let device = defaultInput() else { return false }
        let running: UInt32 = CoreAudioProperty.value(
            of: device, selector: kAudioDevicePropertyDeviceIsRunningSomewhere
        ) ?? 0
        guard running != 0 else { return false }

        // An empty list is Core Audio declining to answer, not "nobody is recording": the device
        // property said the device is running, and that stands on its own.
        let processes: [AudioObjectID] = CoreAudioProperty.array(
            of: AudioObjectID(kAudioObjectSystemObject), selector: kAudioHardwarePropertyProcessObjectList
        )
        guard !processes.isEmpty else { return true }

        let ours = CoreAudioProperty.processObject(pid: getpid())
        return processes.contains { process in
            guard process != ours else { return false }
            let input: UInt32 = CoreAudioProperty.value(
                of: process, selector: kAudioProcessPropertyIsRunningInput
            ) ?? 0
            return input != 0
        }
    }

    private static func defaultInput() -> AudioObjectID? {
        guard let id: AudioObjectID = CoreAudioProperty.value(
            of: AudioObjectID(kAudioObjectSystemObject), selector: kAudioHardwarePropertyDefaultInputDevice
        ), id != AudioObjectID(kAudioObjectUnknown) else { return nil }
        return id
    }
}
#endif
