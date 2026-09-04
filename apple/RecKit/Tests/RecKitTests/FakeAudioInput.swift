import AVFoundation
import Foundation
@testable import RecKit

/// The microphone, minus the microphone. It is what lets the recorder's real path — tap callback,
/// converter, AAC encoder, segment boundary, hash, `addPart` — be driven end to end by a test, on
/// real files, with no hardware and no TCC prompt in the way.
///
/// The recorder touches it on its control queue and the test touches it from wherever XCTest is,
/// so everything mutable is behind [lock].
final class FakeAudioInput: AudioInput {
    /// 16 kHz mono Float32, which is exactly what the segment file takes, so the recorder's
    /// converter is a pass-through: a frame pushed in here is that frame written out there. That is
    /// what makes an *exact* frame count assertable at the other end.
    static var recorderFormat: AVAudioFormat {
        AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16_000, channels: 1, interleaved: false)!
    }

    /// A device's own rate, which is what the hardware actually reports — 48 kHz on most Macs,
    /// 44.1 kHz on plenty of the rest — so the real `AVAudioConverter` and its resampler sit
    /// between the tap and the file the way they do on a machine.
    static func format(_ sampleRateHz: Double) -> AVAudioFormat {
        AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: sampleRateHz, channels: 1, interleaved: false)!
    }

    /// Settable so a test can make the hardware move under the tap: the recorder declines a restart
    /// when the input is already running on the format the session attached to, which is how one
    /// device change producing two notifications stays one restart.
    var format: AVAudioFormat?

    var onConfigurationChange: ((String) -> Void)?
    /// The interruption a phone call is, without the phone call: what `IOSAudioInput` reports when
    /// the microphone is taken away and given back (docs/03 `silenced`).
    var onSilence: ((Bool) -> Void)?

    private let lock = NSLock()
    private var _starts = 0
    private var _stops = 0
    private var _onBuffer: ((AVAudioPCMBuffer) -> Void)?
    private var _beforeStart: (() -> Void)?

    init(format: AVAudioFormat? = FakeAudioInput.recorderFormat) {
        self.format = format
    }

    /// How many times a tap has been installed: one for the start, one more for each restart.
    var starts: Int { lock.withLock { _starts } }
    var stops: Int { lock.withLock { _stops } }
    var isRunning: Bool { lock.withLock { _onBuffer != nil } }
    /// A recorder that ends with this true left a tap — and on a real machine a lit microphone —
    /// behind a recording nobody owns any more.
    var tapped: Bool { isRunning }

    /// Runs inside the next `start`, on the recorder's control queue, and only once: a gate a test
    /// can hold the control queue open with while it arranges a race against it.
    func gateNextStart(_ gate: @escaping () -> Void) {
        lock.withLock { _beforeStart = gate }
    }

    func authorize() async throws {}

    func start(_ onBuffer: @escaping (AVAudioPCMBuffer) -> Void) throws {
        let gate: (() -> Void)? = lock.withLock {
            let taken = _beforeStart
            _beforeStart = nil
            return taken
        }
        gate?()
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
    }

    /// Delivers one buffer the way a tap callback would — on the caller's thread, synchronously.
    /// False when nothing is tapped, which is what a restart's teardown looks like from here.
    @discardableResult
    func deliver(_ buffer: AVAudioPCMBuffer) -> Bool {
        guard let onBuffer = lock.withLock({ _onBuffer }) else { return false }
        onBuffer(buffer)
        return true
    }

    /// [frames] of [sample] at this input's own rate, in 4096-frame buffers — the size a real tap
    /// delivers, so a segment boundary falls inside one of them rather than politely between two.
    /// False if the tap went away partway through, or the format is gone.
    @discardableResult
    func push(frames: Int, sample: (Int) -> Float) -> Bool {
        guard let format else { return false }
        var written = 0
        while written < frames {
            let count = min(4096, frames - written)
            guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(count)),
                  let samples = buffer.floatChannelData
            else { return false }
            buffer.frameLength = AVAudioFrameCount(count)
            for index in 0 ..< count { samples[0][index] = sample(written + index) }
            guard deliver(buffer) else { return false }
            written += count
        }
        return true
    }
}
