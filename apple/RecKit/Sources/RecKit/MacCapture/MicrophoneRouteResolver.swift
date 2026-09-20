#if os(macOS)
import AppKit
import CoreGraphics
import CoreAudio
import Foundation

public struct MicrophoneDevice: Identifiable, Equatable, Sendable {
    public let id: String
    public let name: String
    let audioID: AudioDeviceID

    public static func available() -> [MicrophoneDevice] {
        let devices: [AudioDeviceID] = CoreAudioProperty.array(
            of: AudioObjectID(kAudioObjectSystemObject), selector: kAudioHardwarePropertyDevices
        )
        return devices.compactMap { device in
            let streams: [AudioStreamID] = CoreAudioProperty.array(
                of: device, selector: kAudioDevicePropertyStreams, scope: kAudioObjectPropertyScopeInput
            )
            let alive: UInt32 = CoreAudioProperty.value(of: device, selector: kAudioDevicePropertyDeviceIsAlive) ?? 0
            guard alive != 0, !streams.isEmpty,
                  let uid = CoreAudioProperty.string(of: device, selector: kAudioDevicePropertyDeviceUID),
                  let name = CoreAudioProperty.string(of: device, selector: kAudioObjectPropertyName)
            else { return nil }
            return MicrophoneDevice(id: uid, name: name, audioID: device)
        }.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }
}

/// Selection and hysteresis are independent of Core Audio so disconnects, mute transitions and
/// simultaneous meetings can be tested without acquiring a microphone.
struct MicrophoneSelection {
    private(set) var selected: String?
    private var candidate: String?
    private var hasCandidate = false
    private var candidateSince = 0.0
    private var lastDefault: String?
    private var lastPreferred: String?

    mutating func resolve(
        available: Set<String>, preferred: String?, meeting: Set<String>, defaultUID: String?,
        now: Double, initial: Bool = false
    ) -> String? {
        let active = meeting.intersection(available)
        let explicitChanged = preferred != lastPreferred
        let defaultChanged = lastDefault != nil && defaultUID != lastDefault
        defer { lastDefault = defaultUID; lastPreferred = preferred }
        let requested: String?
        if let preferred, available.contains(preferred) { requested = preferred }
        else if active.count == 1 { requested = active.first }
        else if let selected, available.contains(selected), !defaultChanged, !explicitChanged, !(hasCandidate && candidate == defaultUID) {
            // A call being muted or ending does not move an ongoing recording to another mic.
            requested = selected
        } else { requested = defaultUID.flatMap { available.contains($0) ? $0 : nil } }

        guard requested != selected else { candidate = nil; hasCandidate = false; return selected }
        if initial || selected == nil || explicitChanged {
            selected = requested; candidate = nil; hasCandidate = false; return selected
        }
        if !hasCandidate || candidate != requested { candidate = requested; candidateSince = now; hasCandidate = true }
        let settling = selected.map { available.contains($0) } == true ? 1.0 : 2.0
        if now - candidateSince >= settling { selected = requested; candidate = nil; hasCandidate = false }
        return selected
    }
}

final class MicrophoneRouteResolver {
    private let lock = NSLock()
    private var policy = MicrophoneSelection()
    private var preferred: String?
    private var boundUID: String?
    private var boundName: String?
    private var boundFormat: [Double] = []

    var name: String? { lock.withLock { boundName } }

    func beginSession() { lock.withLock { policy = MicrophoneSelection(); boundUID = nil; boundName = nil } }

    func prefer(_ uid: String?) { lock.withLock { preferred = uid } }

    func bind(_ device: MicrophoneDevice) {
        let format = Self.inputFormat(device.audioID)
        lock.withLock { boundUID = device.id; boundName = device.name; boundFormat = format }
    }

    func changed() -> Bool {
        let next = resolve()
        let format = next.map { Self.inputFormat($0.audioID) } ?? []
        return lock.withLock { next?.id != boundUID || format != boundFormat }
    }

    func resolve(initial: Bool = false) -> MicrophoneDevice? {
        let devices = MicrophoneDevice.available()
        let defaultID: AudioDeviceID? = CoreAudioProperty.value(
            of: AudioObjectID(kAudioObjectSystemObject), selector: kAudioHardwarePropertyDefaultInputDevice
        )
        let fallback = devices.first { $0.audioID == defaultID }?.id
        let meeting = Self.meetingInputs(devices: devices)
        let selected = lock.withLock {
            policy.resolve(
                available: Set(devices.map(\.id)), preferred: preferred, meeting: meeting,
                defaultUID: fallback, now: ProcessInfo.processInfo.systemUptime, initial: initial
            )
        }
        return devices.first { $0.id == selected }
    }

    private static func inputFormat(_ device: AudioDeviceID) -> [Double] {
        let streams: [AudioStreamID] = CoreAudioProperty.array(
            of: device, selector: kAudioDevicePropertyStreams, scope: kAudioObjectPropertyScopeInput
        )
        return streams.flatMap { stream -> [Double] in
            guard let format: AudioStreamBasicDescription = CoreAudioProperty.value(
                of: stream, selector: kAudioStreamPropertyVirtualFormat
            ) else { return [] }
            return [format.mSampleRate, Double(format.mChannelsPerFrame), Double(format.mFormatID),
                    Double(format.mFormatFlags), Double(format.mBitsPerChannel), Double(format.mBytesPerFrame),
                    Double(format.mBytesPerPacket), Double(format.mFramesPerPacket)]
        }
    }

    static func isMeetingProcess(_ bundle: String, meetingBrowsers: Set<String> = []) -> Bool {
        let roots = MeetingAppMonitor.meetingApps.union(meetingBrowsers)
        return roots.contains { bundle == $0 || bundle.hasPrefix($0 + ".") }
    }

    private static func meetingInputs(devices: [MicrophoneDevice]) -> Set<String> {
        let processes: [AudioObjectID] = CoreAudioProperty.array(
            of: AudioObjectID(kAudioObjectSystemObject), selector: kAudioHardwarePropertyProcessObjectList
        )
        let ours = CoreAudioProperty.processObject(pid: getpid())
        let windows = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID)
            as? [[String: Any]] ?? []
        let browsers = Set(windows.compactMap { window -> String? in
            guard let title = (window[kCGWindowName as String] as? String)?.lowercased(),
                  MeetingAppMonitor.meetingTitles.contains(where: title.contains),
                  let pid = window[kCGWindowOwnerPID as String] as? Int32,
                  let bundle = NSRunningApplication(processIdentifier: pid)?.bundleIdentifier,
                  MeetingAppMonitor.browsers.contains(bundle) else { return nil }
            return bundle
        })
        var inputs = Set<AudioDeviceID>()
        for process in processes where process != ours {
            let running: UInt32 = CoreAudioProperty.value(of: process, selector: kAudioProcessPropertyIsRunningInput) ?? 0
            guard running != 0,
                  let bundle = CoreAudioProperty.string(of: process, selector: kAudioProcessPropertyBundleID),
                  isMeetingProcess(bundle, meetingBrowsers: browsers) else { continue }
            let used: [AudioDeviceID] = CoreAudioProperty.array(
                of: process, selector: kAudioProcessPropertyDevices, scope: kAudioObjectPropertyScopeInput
            )
            inputs.formUnion(used)
        }
        return Set(devices.filter { inputs.contains($0.audioID) }.map(\.id))
    }
}
#endif
