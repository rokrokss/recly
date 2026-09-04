#if os(macOS)
import CoreAudio
import Foundation

/// The default output device, as much of it as the tap and the menu need: what to name in the menu
/// (docs/12 M4-L3 "캡처 중인 출력 장치명"), what UID to build the aggregate device around, and
/// whether it is the built-in speaker — which is the whole of the echo policy's question.
public struct SystemAudioDevice: Sendable {
    public let name: String
    /// docs/12 "에코": headphones and the problem does not exist; the built-in speaker and the
    /// microphone records the other side of the call back into the `mic` track.
    public let isBuiltInSpeaker: Bool

    let id: AudioObjectID
    let uid: String

    /// `nil` when there is no default output device at all — a Mac with everything unplugged.
    public static func defaultOutput() -> SystemAudioDevice? {
        guard let id: AudioObjectID = CoreAudioProperty.value(
            of: AudioObjectID(kAudioObjectSystemObject),
            selector: kAudioHardwarePropertyDefaultOutputDevice
        ), id != AudioObjectID(kAudioObjectUnknown) else { return nil }
        guard let uid: String = CoreAudioProperty.string(of: id, selector: kAudioDevicePropertyDeviceUID) else {
            return nil
        }
        let transport: UInt32 = CoreAudioProperty.value(of: id, selector: kAudioDevicePropertyTransportType) ?? 0
        return SystemAudioDevice(
            name: CoreAudioProperty.string(of: id, selector: kAudioObjectPropertyName) ?? uid,
            isBuiltInSpeaker: transport == kAudioDeviceTransportTypeBuiltIn,
            id: id,
            uid: uid
        )
    }

    /// The output device's own rate, which is the rate the tap will deliver at. Polled rather than
    /// listened to: it is one read every couple of seconds against a listener that would have to be
    /// moved every time the default device changes (see `ProcessTapCapture.watch`).
    var nominalSampleRateHz: Double {
        CoreAudioProperty.value(of: id, selector: kAudioDevicePropertyNominalSampleRate) ?? 0
    }
}

/// The three shapes of `AudioObjectGetPropertyData` this file needs, in one place: a plain value, a
/// CFString, and a value that takes a qualifier. Each of them is the same eight lines otherwise, and
/// eight lines repeated is where the wrong `mScope` hides.
enum CoreAudioProperty {
    static func address(
        _ selector: AudioObjectPropertySelector,
        scope: AudioObjectPropertyScope = kAudioObjectPropertyScopeGlobal
    ) -> AudioObjectPropertyAddress {
        AudioObjectPropertyAddress(
            mSelector: selector,
            mScope: scope,
            mElement: kAudioObjectPropertyElementMain
        )
    }

    static func value<T>(of object: AudioObjectID, selector: AudioObjectPropertySelector) -> T? {
        var address = address(selector)
        var size = UInt32(MemoryLayout<T>.size)
        let out = UnsafeMutablePointer<T>.allocate(capacity: 1)
        defer { out.deallocate() }
        guard AudioObjectGetPropertyData(object, &address, 0, nil, &size, out) == noErr else { return nil }
        return out.pointee
    }

    /// A property whose size is the answer — Core Audio's process object list, which `MicInUseMonitor`
    /// walks. Empty when the property cannot be read at all, which the caller has to read as "no
    /// answer" rather than "nothing there".
    static func array<T>(of object: AudioObjectID, selector: AudioObjectPropertySelector) -> [T] {
        var address = address(selector)
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(object, &address, 0, nil, &size) == noErr,
              size >= UInt32(MemoryLayout<T>.stride)
        else { return [] }
        let count = Int(size) / MemoryLayout<T>.stride
        let out = UnsafeMutablePointer<T>.allocate(capacity: count)
        defer { out.deallocate() }
        guard AudioObjectGetPropertyData(object, &address, 0, nil, &size, out) == noErr else { return [] }
        return Array(UnsafeBufferPointer(start: out, count: count))
    }

    static func string(of object: AudioObjectID, selector: AudioObjectPropertySelector) -> String? {
        var address = address(selector)
        var size = UInt32(MemoryLayout<CFString?>.size)
        var out: Unmanaged<CFString>?
        guard AudioObjectGetPropertyData(object, &address, 0, nil, &size, &out) == noErr,
              let value = out?.takeRetainedValue()
        else { return nil }
        return value as String
    }

    /// This process as Core Audio knows it — the one thing the global tap has to be told to leave
    /// out, or the recording plays back into itself.
    static func processObject(pid: pid_t) -> AudioObjectID? {
        var address = address(kAudioHardwarePropertyTranslatePIDToProcessObject)
        var qualifier = pid
        var out = AudioObjectID(kAudioObjectUnknown)
        var size = UInt32(MemoryLayout<AudioObjectID>.size)
        guard AudioObjectGetPropertyData(
            AudioObjectID(kAudioObjectSystemObject),
            &address,
            UInt32(MemoryLayout<pid_t>.size),
            &qualifier,
            &size,
            &out
        ) == noErr, out != AudioObjectID(kAudioObjectUnknown) else { return nil }
        return out
    }
}
#endif
