import AVFoundation
import Foundation
@testable import RecKit

/// The process tap, minus Core Audio. It is what lets a meeting recording — three tracks, one
/// boundary, one set of part numbers — be driven end to end by a test, on real files, with no
/// aggregate device and no "시스템 오디오 녹음" prompt.
///
/// The recorder touches it on its control queue and the test pushes buffers from wherever XCTest is,
/// so everything mutable is behind [lock].
final class FakeSystemAudioInput: SystemAudioInput {
    /// The rate a real tap reports: the output device's own, which on this machine is 48 kHz
    /// (measured with the probe in M4-L3). Settable so a test can make it something else.
    var format: AVAudioFormat

    var outputDeviceName: String? = "테스트 출력 장치"
    var onOutage: ((String, TimeInterval) -> Void)?

    private let lock = NSLock()
    private var _onBuffer: ((AVAudioPCMBuffer) -> Void)?
    private var _starts = 0
    private var _stops = 0
    /// Set to make `start` fail the way a refused tap does.
    var failure: RecorderError?
    /// An outage that is still open when the recording ends — a tap whose rebuild was still
    /// failing. The real one reports it from `stop` (`ProcessTapCapture.stop`), because the rebuild
    /// that would have closed it is never going to happen now.
    var outageAtStop: (reason: String, seconds: TimeInterval)?

    init(sampleRateHz: Double = 48_000) {
        format = AVAudioFormat(
            commonFormat: .pcmFormatFloat32, sampleRate: sampleRateHz, channels: 1, interleaved: false
        )!
    }

    var starts: Int { lock.withLock { _starts } }
    var stops: Int { lock.withLock { _stops } }
    var isRunning: Bool { lock.withLock { _onBuffer != nil } }

    func start(_ onBuffer: @escaping (AVAudioPCMBuffer) -> Void) throws {
        if let failure { throw failure }
        lock.withLock {
            _starts += 1
            _onBuffer = onBuffer
        }
    }

    func stop() {
        lock.withLock {
            _stops += 1
            _onBuffer = nil
        }
        if let outageAtStop { onOutage?(outageAtStop.reason, outageAtStop.seconds) }
    }

    /// [frames] of [sample] at the tap's own rate, in 512-frame buffers — the size the real IOProc
    /// delivers (measured: ~10.7 ms at 48 kHz). False if nothing is tapped.
    @discardableResult
    func push(frames: Int, sample: (Int) -> Float) -> Bool {
        guard let onBuffer = lock.withLock({ _onBuffer }) else { return false }
        var written = 0
        while written < frames {
            let count = min(512, frames - written)
            guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(count)),
                  let samples = buffer.floatChannelData
            else { return false }
            buffer.frameLength = AVAudioFrameCount(count)
            for index in 0 ..< count { samples[0][index] = sample(written + index) }
            onBuffer(buffer)
            written += count
        }
        return true
    }
}
