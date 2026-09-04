#if os(macOS)
import CoreAudio
import XCTest
@testable import RecKit

/// The aggregate device's description, which is the only part of `ProcessTapCapture` an automated
/// run can look at — creating one raises the "시스템 오디오 녹음" prompt. Two of its keys decide
/// whether a meeting recording starts at all and whether what arrives is the meeting or the user's
/// own headset, and neither of them shows up as a compile error when it is wrong.
final class ProcessTapCaptureTests: XCTestCase {
    /// AudioHardware.h on `kAudioAggregateDeviceTapAutoStartKey`: "calling AudioDeviceStart with the
    /// aggregate device will wait until a tapped process begins receiving its first audio from any
    /// tapped applications." A meeting started in a quiet room would hang on that wait with the
    /// microphone already running.
    func testTheTapDoesNotWaitForSomethingToStartPlaying() {
        let description = ProcessTapCapture.aggregateDescription(around: Self.device, tap: Self.tap)

        XCTAssertEqual(
            description[kAudioAggregateDeviceTapAutoStartKey] as? Bool, false,
            "true parks AudioDeviceStart until an app plays something"
        )
    }

    /// A duplex output — AirPods, a USB headset — would otherwise present its own input channels
    /// through the aggregate, and the first stream `receive` reads could be the user's headset
    /// microphone instead of the tap.
    func testTheSubDeviceContributesNoInputChannelsSoTheTapIsTheOnlyStream() {
        let description = ProcessTapCapture.aggregateDescription(around: Self.device, tap: Self.tap)

        let subDevices = try? XCTUnwrap(description[kAudioAggregateDeviceSubDeviceListKey] as? [[String: Any]])
        let subDevice = try? XCTUnwrap(subDevices?.first)
        XCTAssertEqual(subDevice?[kAudioSubDeviceUIDKey] as? String, Self.device.uid)
        XCTAssertEqual(subDevice?[kAudioSubDeviceInputChannelsKey] as? Int, 0)
        XCTAssertEqual(description[kAudioAggregateDeviceIsPrivateKey] as? Bool, true, "not in Sound settings")
        XCTAssertEqual(
            description[kAudioAggregateDeviceMainSubDeviceKey] as? String, Self.device.uid,
            "the aggregate is built around the output device the audio is going to"
        )
    }

    private static let device = SystemAudioDevice(
        name: "테스트 출력 장치", isBuiltInSpeaker: false, id: 42, uid: "test-output-uid"
    )

    /// Describing a tap does not create one, so no prompt and no aggregate device.
    private static let tap = CATapDescription(monoGlobalTapButExcludeProcesses: [])
}
#endif
