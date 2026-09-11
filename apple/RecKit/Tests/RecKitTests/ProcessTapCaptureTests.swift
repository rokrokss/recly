#if os(macOS)
import AVFoundation
import CoreAudio
import XCTest
@testable import RecKit

/// Aggregate configuration and input format handling without creating a real tap or raising a
/// permission prompt. Format regressions also run through the real system-audio resampler.
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

    /// A 24 kHz Bluetooth stream labelled as 48 kHz doubles pitch and inserts about 85 ms of
    /// silence in every 171 ms microphone buffer. Checking output samples catches both failures;
    /// checking only the file's final 16 kHz header would catch neither.
    func testAggregateInputRatePreservesPitchAndContinuousSystemAudio() throws {
        let rate = 24_000.0
        let input = try Self.inputFormat(rate: rate)
        let target = try XCTUnwrap(AVAudioFormat(
            commonFormat: .pcmFormatFloat32, sampleRate: 16_000, channels: 1, interleaved: false
        ))
        let compensator = DriftCompensator(target: target)
        var sourceFrames = 0
        var micFrames: Int64 = 0
        var recorded: [Float] = []
        var startupUnderruns = 0

        // Sixteen 256-frame source callbacks per 4096-frame microphone callback, as on a 24 kHz
        // route. The fractional 16 kHz frame count is carried across microphone buffers.
        for tick in 1 ... 32 {
            for _ in 0 ..< 16 {
                let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: input.format, frameCapacity: 256))
                buffer.frameLength = 256
                for frame in 0 ..< 256 {
                    buffer.floatChannelData![0][frame] = Float(
                        0.5 * sin(2 * .pi * 440 * Double(sourceFrames + frame) / rate)
                    )
                }
                sourceFrames += 256
                compensator.append(buffer, atSec: Double(sourceFrames) / rate)
            }
            let total = Int64((Double(tick * 4096) * target.sampleRate / rate).rounded())
            let frames = AVAudioFrameCount(total - micFrames)
            micFrames = total
            let out = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: target, frameCapacity: frames))
            compensator.take(frames: frames, into: out)
            compensator.observeMic(frames: micFrames, atSec: Double(tick * 4096) / rate)
            if tick <= 8 {
                startupUnderruns = compensator.underrunFrames
            } else {
                recorded.append(contentsOf: UnsafeBufferPointer(start: out.floatChannelData![0], count: Int(frames)))
            }
        }

        XCTAssertEqual(compensator.underrunFrames, startupUnderruns, "no repeating silence after startup")
        let crossings = zip(recorded, recorded.dropFirst()).filter { $0.0 <= 0 && $0.1 > 0 }.count
        let frequency = Double(crossings) * target.sampleRate / Double(recorded.count)
        XCTAssertEqual(frequency, 440, accuracy: 1, "the system voice must keep its original pitch")
        let rms = sqrt(recorded.reduce(0.0) { $0 + Double($1 * $1) } / Double(recorded.count))
        XCTAssertEqual(rms, 0.5 / sqrt(2), accuracy: 0.01, "continuous tone energy survives capture")
    }

    func testReadingTheSameAggregateAgainUsesItsNewStreamRate() throws {
        var current = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 1))
        let read = {
            try ProcessTapCapture.inputFormat(
                on: 42, streams: { _ in [84] }, streamFormat: { _ in current.streamDescription.pointee }
            )
        }
        let before = try read()
        current = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: 24_000, channels: 1))
        let after = try read()

        XCTAssertEqual(before.streamID, after.streamID)
        XCTAssertEqual(before.format.sampleRate, 48_000)
        XCTAssertEqual(after.format.sampleRate, 24_000)
        XCTAssertNotEqual(before.format, after.format, "the format watchdog must see a Bluetooth rate change")
    }

    func testUnavailableOrAmbiguousInputDoesNotFallBackToATapRate() throws {
        for streams in [[], [84, 85]] as [[AudioStreamID]] {
            XCTAssertThrowsError(try ProcessTapCapture.inputFormat(
                on: 42, streams: { _ in streams }, streamFormat: { _ in nil }
            ))
        }
        XCTAssertThrowsError(try ProcessTapCapture.inputFormat(
            on: 42, streams: { _ in [84] }, streamFormat: { _ in nil }
        ))
    }

    func testUnsupportedStreamLayoutsAreNotReinterpretedAsMonoFloatSamples() throws {
        let formats = [
            AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 24_000, channels: 1, interleaved: false),
            AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 2),
        ]
        for format in formats {
            let format = try XCTUnwrap(format)
            XCTAssertThrowsError(try ProcessTapCapture.inputFormat(
                on: 42, streams: { _ in [84] }, streamFormat: { _ in format.streamDescription.pointee }
            ))
        }
        var invalidRate = try Self.inputFormat(rate: 24_000).format.streamDescription.pointee
        invalidRate.mSampleRate = .nan
        XCTAssertThrowsError(try ProcessTapCapture.inputFormat(
            on: 42, streams: { _ in [84] }, streamFormat: { _ in invalidRate }
        ))
    }

    private static func inputFormat(rate: Double) throws -> (streamID: AudioStreamID, format: AVAudioFormat) {
        let format = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: rate, channels: 1))
        return try ProcessTapCapture.inputFormat(
            on: 42, streams: { _ in [84] }, streamFormat: { _ in format.streamDescription.pointee }
        )
    }

    private static let device = SystemAudioDevice(
        name: "테스트 출력 장치", isBuiltInSpeaker: false, id: 42, uid: "test-output-uid"
    )

    /// Describing a tap does not create one, so no prompt and no aggregate device.
    private static let tap = CATapDescription(monoGlobalTapButExcludeProcesses: [])
}
#endif
