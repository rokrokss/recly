import AVFoundation
import Foundation

/// The rate estimate, as arithmetic: no audio, no converter, no clock. Both streams say how many
/// frames they have produced *on the 16 kHz timeline*, the wall clock says how long that took, and
/// the ratio between the two rates is the drift (docs/12 "누적 프레임 수 vs 벽시계").
///
/// It is a separate type from [DriftCompensator] so the claim the lane makes — an hour of a
/// synthetic rate difference ends under 20 ms apart — can be checked as a calculation rather than
/// as a recording.
struct DriftEstimator {
    /// docs/12: re-estimated every 60 seconds. Shorter and the estimate is jitter; longer and an
    /// hour is only a handful of corrections.
    static let intervalSec: Double = 60

    /// A real clock pair is within a hundred parts per million of each other (36 ms an hour). Ten
    /// thousand ppm is not a clock difference, it is a stream that stalled — and a ratio built from
    /// it would resample the system track into noise for the rest of the recording.
    ///
    /// Two jobs, and the first is the one that matters: it is the band a new estimate has to fall
    /// in to be believed at all, and only then the ceiling the compounded ratio is held to.
    static let maxDeviation: Double = 0.01

    /// What the system stream's frame count has to be multiplied by to land on the microphone's
    /// timeline. Applied to the resampler as an input rate, so 1 is "no correction yet".
    private(set) var ratio: Double = 1

    private let intervalSec: Double
    private var sinceSec: Double
    private var micAtInterval: Double
    private var sysAtInterval: Double
    /// The corrections lately accepted, each with the ratio that was in force before it. An outage
    /// is only ever known about once it has *ended*, and one that straddles an observation — three
    /// hundred milliseconds out of a minute is half a percent, well inside the band — has already
    /// moved the ratio by then. Two is enough: an outage long enough to reach a third observation
    /// is one whose intervals the band refuses on their own.
    private var accepted: [(atSec: Double, before: Double)] = []
    private static let acceptedKept = 2

    init(intervalSec: Double = DriftEstimator.intervalSec, startSec: Double = 0) {
        self.intervalSec = intervalSec
        sinceSec = startSec
        micAtInterval = 0
        sysAtInterval = 0
    }

    /// Starts the interval again from here, with no estimate taken: the frames of an interval the
    /// tap was re-created inside are missing rather than slow, and the rate across them is a
    /// fiction (see the refusal in [observe]).
    ///
    /// [outageSec] is how long the tap was away for, which is what says whether an interval that
    /// already closed measured part of the hole. Anything accepted since the audio stopped is put
    /// back — re-anchoring alone would leave the wrong ratio running for the whole next minute, and
    /// a ratio half a percent out drops or invents about as much audio again as the outage did.
    mutating func reanchor(micFrames: Double, sysFrames: Double, atSec: Double, outageSec: Double) {
        if let overlapping = accepted.first(where: { $0.atSec >= atSec - outageSec }) {
            ratio = overlapping.before
        }
        accepted.removeAll()
        sinceSec = atSec
        micAtInterval = micFrames
        sysAtInterval = sysFrames
    }

    /// Cumulative output frames of both streams at [atSec] on a monotonic clock: the microphone's
    /// own count, and what the system stream's resampler has produced. True when an interval closed
    /// and [ratio] moved, which is the caller's cue to rebuild that resampler.
    ///
    /// Rates, and only rates. The obvious extra — folding the queue's current depth in, so the
    /// correction fixed where the two streams *are* and not only how fast they are going — was tried
    /// and taken out: `AVAudioConverter` emits in blocks of its own, so the depth at any one instant
    /// swings by a block (85 ms at a 48 kHz tap) whatever the clocks are doing, and a correction
    /// built on that chases the resampler's phase instead of the drift. Measured: it saturated the
    /// clamp within one interval and stayed there. Frames per interval average the blocks away.
    ///
    /// What that leaves uncorrected is whatever the two streams drift apart by before the first
    /// interval closes — one minute of the difference, which at the tens of milliseconds an hour
    /// docs/12 describes is under a millisecond.
    mutating func observe(micFrames: Double, sysFrames: Double, atSec: Double) -> Bool {
        guard atSec - sinceSec >= intervalSec else { return false }
        let elapsed = atSec - sinceSec
        let mic = micFrames - micAtInterval
        let sys = sysFrames - sysAtInterval
        sinceSec = atSec
        micAtInterval = micFrames
        sysAtInterval = sysFrames

        // Half an interval's worth of frames from each, or one of them was not really running — a
        // paused microphone during a device change, a tap being re-created. There is no rate to
        // measure across that, and the last good ratio is a better guess than a made-up one.
        guard mic > 0, sys > 0, min(mic, sys) > elapsed * Double(SegmentedRecorder.sampleRateHz) / 2 else {
            return false
        }
        // The system frames were produced with the *current* ratio already applied, so the
        // correction compounds onto it rather than replacing it.
        let raw = ratio * sys / mic
        // A minute of two clocks is parts per million apart. A whole percent away from the ratio in
        // force is not a clock, it is an interval part of whose system audio never arrived — a tap
        // that was away for ten of the sixty seconds reads as a stream running a sixth slow. Folded
        // in, even clamped, that would resample the *next* minute by a percent (600 ms of system
        // audio dropped or filled with silence) to correct frames that were never late, only
        // missing. So it is refused; the interval is re-anchored above, and the next one measures
        // two streams that were both running.
        guard abs(raw - ratio) <= Self.maxDeviation else { return false }
        let corrected = raw.clamped(to: 1 - Self.maxDeviation ... 1 + Self.maxDeviation)
        guard corrected != ratio else { return false }
        accepted.append((atSec: atSec, before: ratio))
        if accepted.count > Self.acceptedKept { accepted.removeFirst() }
        ratio = corrected
        return true
    }
}

/// The system stream, put on the microphone's timeline: resampled to 16 kHz mono with the drift
/// correction folded into the resample ratio, and held in a queue the recorder draws from a
/// microphone buffer at a time.
///
/// The microphone is the clock. It has to be — the recording's length, its segment boundaries and
/// its part offsets are all counted in microphone frames, and a second stream that decided any of
/// those for itself would answer differently within the hour. So every microphone buffer that
/// reaches a file takes exactly as many system frames with it, and this is what makes sure those
/// frames are the right ones.
///
/// The queue opens with a block of silence in front of it, and that is not an accident.
/// `AVAudioConverter` consumes its input in blocks of its own — measured at 4096 input frames,
/// 85 ms of output from a 48 kHz tap, however small the buffers handed to it are — so it produces
/// nothing at all for eight tap callbacks and then a whole block at once. A queue drawn down one
/// microphone buffer at a time from a supply that arrives in 85 ms steps is empty half the time, and
/// "empty" here means a silence gap punched into the system track. One block of lead is what turns
/// that into a *constant* lag instead of a repeating hole.
///
/// Two more rules bound the rest, and both of them bound rather than accumulate: frames the
/// microphone asked for before they arrived are written as silence, and lead beyond one microphone
/// buffer past the block is dropped rather than carried (carrying it would delay the system track by
/// that much for the rest of the recording). What none of them corrects is the *constant* offset —
/// the block of lead, plus the two devices' hardware latencies. Removing it needs the host
/// timestamps both callbacks carry, and it is not in this lane; the drift is.
///
/// [append] runs on Core Audio's IO thread and [take] on the microphone's, so everything is behind
/// [lock] — its own, not the recorder's, because the recorder holds that one across an AAC encode.
final class DriftCompensator {
    /// `AVAudioConverter`'s own input block, measured: it emits `4096 × out/in` frames at a time and
    /// nothing in between, whatever it is fed. The lead the queue is primed with is one of them.
    static let converterBlockFrames = 4096

    /// The queue's own ceiling, past the lead, for when nothing is drawing it down: the microphone
    /// is being restarted for a device change and the tap keeps delivering into it. [take] trims to
    /// a microphone buffer's worth — but only when there *is* a take, and a microphone that never
    /// comes back would otherwise leave this growing at 64 KB a second for the rest of the
    /// recording. A second is more than any microphone buffer, so it never takes anything a running
    /// recording was about to ask for.
    static let maxQueuedSec: Double = 1

    private let target: AVAudioFormat
    private let lock = NSLock()

    private var estimator: DriftEstimator
    private var converter: AVAudioConverter?
    /// The rate the converter was built for: the tap's own rate, and the ratio that was applied to
    /// it. A re-created tap can arrive on a different one.
    private var sourceRateHz: Double = 0
    private var appliedRatio: Double = 1

    private var samples: [Float] = []
    /// What the tap has delivered, on the target's timeline — see the counting in [append].
    private var producedFrames: Double = 0
    /// One resampler block on the target's timeline, known once the tap's rate is. The queue is
    /// primed with this much silence and never allowed to hold much more.
    private var leadFrames = 0
    private var primed = false
    private(set) var underrunFrames = 0
    private(set) var droppedFrames = 0

    init(target: AVAudioFormat, intervalSec: Double = DriftEstimator.intervalSec, startSec: Double = 0) {
        self.target = target
        estimator = DriftEstimator(intervalSec: intervalSec, startSec: startSec)
    }

    var ratio: Double { lock.withLock { estimator.ratio } }

    /// System frames waiting for a microphone buffer to take them. It is the misalignment, measured:
    /// a queue that keeps growing is a system stream running ahead of the recording.
    var queuedFrames: Int { lock.withLock { samples.count } }

    /// One tap buffer at the output device's rate, resampled onto the microphone's timeline and
    /// queued. Silently drops what it cannot convert: the system track is the one that may have
    /// holes in it, and a failure here must not end the recording.
    func append(_ buffer: AVAudioPCMBuffer, atSec: Double) {
        lock.withLock {
            // A ratio that moved, or a tap that came back on a different device, is a new resampler.
            // The old one is drained first, exactly as the recorder drains its own: what it is
            // holding is audio the machine really did play.
            if buffer.format.sampleRate != sourceRateHz || estimator.ratio != appliedRatio {
                drainLocked()
                rebuild(sourceRateHz: buffer.format.sampleRate, ratio: estimator.ratio)
            }
            guard let converter, let relabelled = relabel(buffer) else { return }
            // Counted here, from what the tap delivered, and not in [enqueue] from what the
            // resampler has emitted. `AVAudioConverter` emits in blocks of 4096 input frames and
            // nothing in between, so the emitted count runs up to a block behind whatever it is fed
            // — a lag that is constant and says nothing about the two clocks, but that a rate read
            // between two arbitrary points carries as noise (±0.14% across a 60-second interval,
            // ±8% across the one-second interval a test can afford). The input frames have no such
            // phase: this is exactly what the tap produced, on the microphone's timeline, at the
            // rate in force.
            producedFrames += Double(buffer.frameLength) * target.sampleRate / converter.inputFormat.sampleRate
            guard let converted = try? Self.convert(relabelled, with: converter, to: target) else { return }
            enqueue(converted)
        }
    }

    /// [frames] of system audio to sit under the microphone frames the recorder is about to write,
    /// written into [buffer] from frame zero. Short of them, the rest is silence.
    func take(frames: AVAudioFrameCount, into buffer: AVAudioPCMBuffer) {
        guard let out = buffer.floatChannelData else { return }
        let count = Int(frames)
        lock.withLock {
            // The resampler's block, plus one microphone buffer for the two streams' latencies and
            // their scheduling jitter. Anything past that is audio that would be written that much
            // late for the rest of the recording, so it goes.
            dropDown(to: leadFrames + count)
            let taken = min(count, samples.count)
            for index in 0 ..< taken { out[0][index] = samples[index] }
            for index in taken ..< count { out[0][index] = 0 }
            samples.removeFirst(taken)
            underrunFrames += count - taken
        }
        buffer.frameLength = frames
    }

    /// The microphone's own progress — the other half of the estimate, and the only thing here that
    /// knows what time it is on the recording's clock.
    func observeMic(frames: AVAudioFramePosition, atSec: Double) {
        lock.withLock {
            _ = estimator.observe(micFrames: Double(frames), sysFrames: producedFrames, atSec: atSec)
        }
    }

    /// The tap was away for [outageSec] and is back (docs/12 "tap 재생성"). The frames it did not
    /// deliver are missing, not slow, so the interval they fell in is abandoned rather than
    /// measured, and an interval that already closed across the hole is put back — [micFrames] is
    /// the recording's own count, which is the only clock here.
    func reanchor(micFrames: AVAudioFramePosition, atSec: Double, outageSec: Double) {
        lock.withLock {
            estimator.reanchor(
                micFrames: Double(micFrames),
                sysFrames: producedFrames,
                atSec: atSec,
                outageSec: outageSec
            )
        }
    }

    /// The tail the resampler is still holding, for when the tap goes away — the same `.endOfStream`
    /// ask the recorder's own converter gets, and for the same reason (see `SegmentedRecorder.drain`).
    func drain() {
        lock.withLock { drainLocked() }
    }

    // MARK: - Under the lock

    private func drainLocked() {
        guard let converter else { return }
        guard let out = AVAudioPCMBuffer(
            pcmFormat: target,
            frameCapacity: AVAudioFrameCount(target.sampleRate)
        ) else { return }
        var failure: NSError?
        let status = converter.convert(to: out, error: &failure) { _, outStatus in
            outStatus.pointee = .endOfStream
            return nil
        }
        guard failure == nil, status != .error, out.frameLength > 0 else { return }
        enqueue(out)
    }

    /// The correction, applied where a resampler can take it: the converter is told the stream
    /// arrives at `rate × ratio`, so a system clock running fast produces proportionally fewer
    /// frames and lands back on the microphone's count.
    private func rebuild(sourceRateHz: Double, ratio: Double) {
        self.sourceRateHz = sourceRateHz
        appliedRatio = ratio
        converter = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: sourceRateHz * ratio,
            channels: 1,
            interleaved: false
        ).flatMap { AVAudioConverter(from: $0, to: target) }
        leadFrames = Int(
            (Double(Self.converterBlockFrames) * target.sampleRate / sourceRateHz).rounded(.up)
        )
        // Only ever once. A rebuild in the middle of a recording — a moved ratio, a re-created tap —
        // costs one block while the new resampler fills up, and the lead already in the queue is
        // exactly what covers it; priming again would push the whole track a second block late.
        guard !primed else { return }
        primed = true
        samples.append(contentsOf: repeatElement(0, count: leadFrames))
    }

    /// The same samples under the corrected rate. `AVAudioConverter` refuses a buffer whose format
    /// is not exactly its input format, and the correction lives in that format's sample rate — so
    /// the frames are copied into a buffer that claims it. A tap buffer is a few hundred frames.
    private func relabel(_ buffer: AVAudioPCMBuffer) -> AVAudioPCMBuffer? {
        guard let format = converter?.inputFormat,
              let out = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: buffer.frameLength),
              let source = buffer.floatChannelData,
              let destination = out.floatChannelData
        else { return nil }
        destination[0].update(from: source[0], count: Int(buffer.frameLength))
        out.frameLength = buffer.frameLength
        return out
    }

    private func enqueue(_ buffer: AVAudioPCMBuffer) {
        guard let data = buffer.floatChannelData else { return }
        samples.append(contentsOf: UnsafeBufferPointer(start: data[0], count: Int(buffer.frameLength)))
        dropDown(to: leadFrames + Int(target.sampleRate * Self.maxQueuedSec))
    }

    /// The oldest frames past [cap], counted as dropped. [producedFrames] is deliberately left
    /// alone: it is what the tap delivered, which is the rate the estimate is about, and a queue
    /// nobody emptied says nothing about how fast the two clocks are running.
    private func dropDown(to cap: Int) {
        guard samples.count > cap else { return }
        let excess = samples.count - cap
        samples.removeFirst(excess)
        droppedFrames += excess
    }

    /// The recorder's own conversion, with the same `.noDataNow` contract: what the resampler could
    /// not finish is kept for the buffer that follows, and [drain] is what empties it when there is
    /// no buffer that follows.
    private static func convert(
        _ buffer: AVAudioPCMBuffer,
        with converter: AVAudioConverter,
        to format: AVAudioFormat
    ) throws -> AVAudioPCMBuffer? {
        let ratio = format.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 1024
        guard let out = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: capacity) else { return nil }

        var supplied = false
        var failure: NSError?
        let status = converter.convert(to: out, error: &failure) { _, outStatus in
            if supplied {
                outStatus.pointee = .noDataNow
                return nil
            }
            supplied = true
            outStatus.pointee = .haveData
            return buffer
        }
        if let failure { throw failure }
        guard status != .error, out.frameLength > 0 else { return nil }
        return out
    }
}

private extension Double {
    func clamped(to range: ClosedRange<Double>) -> Double {
        Swift.min(Swift.max(self, range.lowerBound), range.upperBound)
    }
}
