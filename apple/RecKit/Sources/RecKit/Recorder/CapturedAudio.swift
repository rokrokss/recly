import AVFoundation
import Foundation

/// Time of the first acquired sample, on the host's monotonic clock. A missing hardware
/// timestamp remains missing; callback scheduling time must not masquerade as capture time.
struct CapturedAudio {
    let buffer: AVAudioPCMBuffer
    let hostTimeSec: Double?

    init(_ buffer: AVAudioPCMBuffer, hostTimeSec: Double? = nil) {
        self.buffer = buffer
        self.hostTimeSec = hostTimeSec
    }
}

public enum CaptureHealth: Sendable {
    case healthy, recovering, failed
}

struct CaptureDiagnostic: Codable {
    let event: String
    let source: String
    var device: String? = nil
    var detail: String? = nil
    var rateHz: Double? = nil
    var measuredRateHz: Double? = nil
    var frames: Int? = nil
    var hostTimeSec: Double? = nil
}

/// Bounded ownership transfer out of hardware callbacks. Only copying and a short queue lock
/// happen there; conversion and file writes run on this queue. finish drains accepted packets
/// before releasing the consumer, including packets held for microphone/system alignment.
final class AudioDeliveryQueue {
    private let queue: DispatchQueue
    private let queueKey = DispatchSpecificKey<Bool>()
    private let lock = NSLock()
    private let delaySec: Double
    private let capacitySec: Double
    private var pending: [(CapturedAudio, Double)] = []
    private var queuedSec = 0.0
    private var generation = 0
    private var accepting = false
    private var scheduled = false
    private var consumer: ((CapturedAudio) -> Void)?
    private var lostFrames = 0

    init(label: String, delaySec: Double = 0, capacitySec: Double = 2) {
        queue = DispatchQueue(label: label, qos: .userInitiated)
        queue.setSpecific(key: queueKey, value: true)
        self.delaySec = delaySec
        self.capacitySec = capacitySec
    }

    var droppedFrames: Int { lock.withLock { lostFrames } }

    func start(_ consumer: @escaping (CapturedAudio) -> Void) {
        lock.withLock {
            generation += 1
            pending.removeAll(keepingCapacity: true)
            queuedSec = 0
            lostFrames = 0
            scheduled = false
            self.consumer = consumer
            accepting = true
        }
    }

    func submit(_ packet: CapturedAudio) {
        let input = packet.buffer
        let duration = Double(input.frameLength) / input.format.sampleRate
        guard duration.isFinite, duration > 0 else { return }
        let launch: Int? = lock.withLock {
            guard accepting else { return nil }
            guard queuedSec + duration <= capacitySec,
                  let copy = AVAudioPCMBuffer(pcmFormat: input.format, frameCapacity: input.frameLength) else {
                lostFrames += Int(input.frameLength)
                return nil
            }
            // Copy and enqueue form one bounded operation so simultaneous producers cannot
            // invert acquisition order while one of them is copying its packet.
            copy.frameLength = input.frameLength
            let source = UnsafeMutableAudioBufferListPointer(input.mutableAudioBufferList)
            let target = UnsafeMutableAudioBufferListPointer(copy.mutableAudioBufferList)
            for index in source.indices {
                guard let from = source[index].mData, let to = target[index].mData else { continue }
                to.copyMemory(from: from, byteCount: Int(source[index].mDataByteSize))
            }
            queuedSec += duration
            pending.append((CapturedAudio(copy, hostTimeSec: packet.hostTimeSec), ProcessInfo.processInfo.systemUptime + delaySec))
            guard !scheduled else { return nil }
            scheduled = true
            return generation
        }
        if let token = launch { queue.async { [weak self] in self?.drain(token: token) } }
    }

    func finish() {
        let token = lock.withLock { accepting = false; return generation }
        if DispatchQueue.getSpecific(key: queueKey) == true { drain(token: token, flush: true) }
        else { queue.sync { drain(token: token, flush: true) } }
        lock.withLock {
            generation += 1
            consumer = nil
            pending.removeAll(keepingCapacity: true)
            queuedSec = 0
            scheduled = false
        }
    }

    private func drain(token: Int, flush: Bool = false) {
        while true {
            var waitSec: Double?
            var deliver: ((CapturedAudio) -> Void)?
            let packet: CapturedAudio? = lock.withLock {
                guard token == generation else { return nil }
                guard let next = pending.first else { scheduled = false; return nil }
                let wait = next.1 - ProcessInfo.processInfo.systemUptime
                if !flush, wait > 0 { waitSec = wait; return nil }
                pending.removeFirst()
                queuedSec -= Double(next.0.buffer.frameLength) / next.0.buffer.format.sampleRate
                deliver = consumer
                return next.0
            }
            if let waitSec {
                queue.asyncAfter(deadline: .now() + waitSec) { [weak self] in self?.drain(token: token) }
            }
            guard let packet else { return }
            deliver?(packet)
        }
    }
}

/// Detect a gross descriptor/delivery disagreement from acquisition timestamps, never callback
/// arrival times or signal amplitude. Silence is valid audio. A discontinuity resets the window.
struct CaptureRateMonitor {
    enum Result: Equatable { case warmingUp, valid, discontinuity, mismatch }
    private var previous: Double?
    private var previousFrames = 0
    private var start: Double?
    private var frames = 0
    private var badWindows = 0
    private var previousRate: Double?
    private(set) var measuredRateHz: Double?

    mutating func observe(frames count: Int, rate: Double, hostTimeSec: Double?) -> Result {
        guard let time = hostTimeSec, time.isFinite, rate.isFinite, rate > 0, count > 0 else {
            self = CaptureRateMonitor()
            return .warmingUp
        }
        if let previousRate, previousRate != rate { self = CaptureRateMonitor() }
        previousRate = rate
        defer { previous = time; previousFrames = count }
        guard let previous, let start else { self.start = time; return .warmingUp }
        let delta = time - previous
        if delta <= 0 || delta > max(0.5, Double(previousFrames) / rate * 4) {
            self.start = time; frames = 0; badWindows = 0
            return .discontinuity
        }
        frames += previousFrames
        let elapsed = time - start
        guard elapsed >= 0.25 else { return .warmingUp }
        let actualRate = Double(frames) / elapsed
        measuredRateHz = actualRate
        self.start = time
        frames = 0
        if abs(actualRate / rate - 1) > 0.03 {
            badWindows += 1
            return badWindows >= 2 ? .mismatch : .warmingUp
        }
        badWindows = 0
        return .valid
    }
}

/// Timestamped, resampled chunks. Batches are retained rather than trimmed to the size of a
/// microphone callback; this handles a large legitimate system callback without periodic holes.
struct TimedAudioSamples {
    struct Chunk { var start: Double; var samples: [Float] }
    private var chunks: [Chunk] = []
    private(set) var droppedFrames = 0

    mutating func append(_ samples: [Float], at start: Double, rate: Double) {
        guard !samples.isEmpty else { return }
        guard start.isFinite, rate.isFinite, rate > 0 else { return }
        chunks.append(Chunk(start: start, samples: samples))
        chunks.sort { $0.start < $1.start }
        let cutoff = start - 2
        while let first = chunks.first, first.start + Double(first.samples.count) / rate < cutoff {
            droppedFrames += chunks.removeFirst().samples.count
        }
    }

    mutating func take(count: Int, at start: Double, rate: Double) -> (samples: [Float], missing: Int) {
        var output = [Float](repeating: 0, count: count)
        var written = [Bool](repeating: false, count: count)
        let end = start + Double(count) / rate
        while let chunk = chunks.first {
            let chunkEnd = chunk.start + Double(chunk.samples.count) / rate
            if chunkEnd <= start { droppedFrames += chunks.removeFirst().samples.count; continue }
            if chunk.start >= end { break }
            let offset = Int(((chunk.start - start) * rate).rounded())
            let source = max(0, -offset)
            let destination = max(0, offset)
            let length = min(chunk.samples.count - source, count - destination)
            if length > 0 {
                for index in 0 ..< length {
                    if !written[destination + index] {
                        output[destination + index] = chunk.samples[source + index]
                        written[destination + index] = true
                    }
                }
            }
            if chunkEnd <= end + 0.5 / rate { chunks.removeFirst() }
            else { break }
        }
        return (output, written.filter { !$0 }.count)
    }
}
