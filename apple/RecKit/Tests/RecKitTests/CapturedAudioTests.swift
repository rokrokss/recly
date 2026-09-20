import AVFoundation
import XCTest
@testable import RecKit

final class CapturedAudioTests: XCTestCase {
    private func buffer(_ count: Int, rate: Double = 16_000, value: Float = 0.5) -> AVAudioPCMBuffer {
        let format = AVAudioFormat(standardFormatWithSampleRate: rate, channels: 1)!
        let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(count))!
        buffer.frameLength = AVAudioFrameCount(count)
        buffer.floatChannelData![0].initialize(repeating: value, count: count)
        return buffer
    }

    func testFinishDrainsCopiedPacketsWithoutWaitingForAlignmentDelay() {
        let delivery = AudioDeliveryQueue(label: "test.delivery", delaySec: 60)
        var heard: [Float] = []
        delivery.start { heard.append($0.buffer.floatChannelData![0][0]) }
        let first = buffer(100, value: 1)
        delivery.submit(CapturedAudio(first, hostTimeSec: 10))
        first.floatChannelData![0][0] = 9
        delivery.submit(CapturedAudio(buffer(100, value: 2), hostTimeSec: 11))
        delivery.finish()
        XCTAssertEqual(heard, [1, 2], "hardware memory is copied and the delayed tail is saved")
        delivery.start { heard.append($0.buffer.floatChannelData![0][0]) }
        delivery.submit(CapturedAudio(buffer(100, value: 3)))
        delivery.finish()
        XCTAssertEqual(heard, [1, 2, 3], "an old generation must never deliver again")
    }

    func testSlowConsumerDoesNotBlockProducerAndQueueIsBounded() {
        let delivery = AudioDeliveryQueue(label: "test.slow", capacitySec: 0.1)
        let entered = expectation(description: "consumer entered")
        let release = DispatchSemaphore(value: 0)
        var first = true
        delivery.start { _ in
            if first { first = false; entered.fulfill() }
            _ = release.wait(timeout: .now() + 2)
        }
        delivery.submit(CapturedAudio(buffer(1600)))
        wait(for: [entered], timeout: 1)
        // The worker is blocked. The producer must still return and cap pending audio.
        delivery.submit(CapturedAudio(buffer(1600)))
        delivery.submit(CapturedAudio(buffer(1600)))
        XCTAssertEqual(delivery.droppedFrames, 1600)
        release.signal()
        release.signal()
        delivery.finish()
    }

    func testRateMonitorDetectsTwofoldMismatchButAcceptsBluetoothRates() {
        for rate in [8_000.0, 16_000, 24_000, 44_100, 48_000] {
            var monitor = CaptureRateMonitor()
            var results: [CaptureRateMonitor.Result] = []
            for frame in stride(from: 0, to: Int(rate * 2), by: 256) {
                results.append(monitor.observe(frames: 256, rate: rate, hostTimeSec: 10 + Double(frame) / rate))
            }
            XCTAssertFalse(results.contains(.mismatch), "\(rate) is a valid native rate")
            XCTAssertTrue(results.contains(.valid))
        }
        var monitor = CaptureRateMonitor()
        var detected = false
        for frame in stride(from: 0, to: 24_000, by: 256) {
            detected = monitor.observe(frames: 256, rate: 48_000, hostTimeSec: Double(frame) / 24_000) == .mismatch || detected
        }
        XCTAssertTrue(detected, "24 kHz samples labelled 48 kHz must not be accepted indefinitely")
    }

    func testTimestampLossAndFormatChangesResetTheRateEstimate() {
        var monitor = CaptureRateMonitor()
        _ = monitor.observe(frames: 12_000, rate: 48_000, hostTimeSec: 1)
        _ = monitor.observe(frames: 12_000, rate: 48_000, hostTimeSec: nil)
        XCTAssertEqual(monitor.observe(frames: 6000, rate: 24_000, hostTimeSec: 2), .warmingUp)
        XCTAssertEqual(monitor.observe(frames: 6000, rate: 24_000, hostTimeSec: 2.25), .valid)
        XCTAssertEqual(monitor.observe(frames: 12_000, rate: 48_000, hostTimeSec: 2.5), .warmingUp)
        XCTAssertEqual(monitor.observe(frames: 12_000, rate: 48_000, hostTimeSec: 2.75), .valid)
        XCTAssertEqual(monitor.observe(frames: 12_000, rate: 48_000, hostTimeSec: 10), .discontinuity)
    }

    func testLargeSystemBatchIsConsumedInOrderWithoutTrimmingToMicBufferSize() {
        var queue = TimedAudioSamples()
        queue.append(Array(repeating: 2, count: 4000), at: 10.25, rate: 16_000)
        queue.append(Array(repeating: 1, count: 4000), at: 10, rate: 16_000)
        for index in 0 ..< 8 {
            let taken = queue.take(count: 1000, at: 10 + Double(index) / 16, rate: 16_000)
            XCTAssertEqual(taken.missing, 0)
            XCTAssertTrue(taken.samples.allSatisfy { $0 == (index < 4 ? 1 : 2) })
        }
        XCTAssertEqual(queue.take(count: 1000, at: 10.5, rate: 16_000).missing, 1000)
    }

    func testMissingTimestampDoesNotInventAContinuousSystemTimeline() {
        let target = AVAudioFormat(standardFormatWithSampleRate: 16_000, channels: 1)!
        let drift = DriftCompensator(target: target)
        drift.append(buffer(8000), atSec: 0, captureTimeSec: 100)
        drift.append(buffer(8000), atSec: 0)
        drift.append(buffer(8000), atSec: 0, captureTimeSec: 101)
        let output = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: 4000)!
        drift.take(frames: 4000, into: output, atSec: 100.5)
        XCTAssertEqual(drift.lastMissingFrames, 4000)
        XCTAssertEqual(drift.droppedFrames, 8000)
        drift.take(frames: 4000, into: output, atSec: 101)
        XCTAssertEqual(drift.lastMissingFrames, 0)
    }

    func testHostTimestampsPreventSmallClockErrorsFromAccumulatingIntoTrackOffset() {
        let target = AVAudioFormat(standardFormatWithSampleRate: 16_000, channels: 1)!
        let drift = DriftCompensator(target: target)
        let actualRate = 48_048.0 // The descriptor says 48 kHz; the source clock is 1000 ppm fast.
        var sourceFrame = 0
        var firstHigh: Int?
        for tick in 0 ..< 820 {
            let until = Int((Double(tick) * 0.1 + 0.6) * actualRate)
            while sourceFrame < until {
                let count = min(2048, until - sourceFrame)
                let input = buffer(count, rate: 48_000, value: 0)
                for index in 0 ..< count {
                    input.floatChannelData![0][index] = Double(sourceFrame + index) / actualRate >= 80 ? 1 : 0
                }
                drift.append(input, atSec: 0, captureTimeSec: 100 + Double(sourceFrame) / actualRate)
                sourceFrame += count
            }
            let output = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: 1600)!
            drift.take(frames: 1600, into: output, atSec: 100 + Double(tick) * 0.1)
            if firstHigh == nil {
                for index in 0 ..< 1600 where output.floatChannelData![0][index] > 0.5 {
                    firstHigh = tick * 1600 + index
                    break
                }
            }
        }
        XCTAssertEqual(Double(firstHigh ?? 0) / 16_000, 80, accuracy: 0.003,
            "descriptor-only timing would place this event about 80 ms early")
    }

    func testTimestampedResamplingPreservesPitchAcrossRatesAndLargeBursts() throws {
        let target = AVAudioFormat(standardFormatWithSampleRate: 16_000, channels: 1)!
        for rate in [8_000.0, 16_000, 24_000, 44_100, 48_000] {
            let drift = DriftCompensator(target: target)
            var written: [Float] = []
            var sourceFrame = 0
            // Feed 0.6 seconds ahead, the same bounded delay as MicrophoneInput. Varying burst
            // sizes catches the old queue trimming that periodically discarded valid samples.
            for tick in 0 ..< 60 {
                let until = Int((Double(tick) * 0.05 + 0.6) * rate)
                while sourceFrame < until {
                    let count = min(sourceFrame % 2 == 0 ? 4096 : 511, until - sourceFrame)
                    let input = buffer(count, rate: rate)
                    for index in 0 ..< count {
                        input.floatChannelData![0][index] = Float(0.5 * sin(2 * .pi * 440 * Double(sourceFrame + index) / rate))
                    }
                    drift.append(input, atSec: 0, captureTimeSec: 100 + Double(sourceFrame) / rate)
                    sourceFrame += count
                }
                let output = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: 800)!
                drift.take(frames: 800, into: output, atSec: 100 + Double(tick) * 0.05)
                XCTAssertEqual(output.frameLength, 800)
                XCTAssertEqual(drift.lastMissingFrames, 0, "no periodic holes at \(rate) Hz, tick \(tick)")
                written.append(contentsOf: UnsafeBufferPointer(start: output.floatChannelData![0], count: 800))
            }
            let tone = Array(written.dropFirst(1600))
            let crossings = zip(tone, tone.dropFirst()).filter { $0.0 <= 0 && $0.1 > 0 }.count
            XCTAssertEqual(Double(crossings) * 16_000 / Double(tone.count), 440, accuracy: 1)
            let energy = sqrt(tone.reduce(0.0) { $0 + Double($1 * $1) } / Double(tone.count))
            XCTAssertEqual(energy, 0.5 / sqrt(2), accuracy: 0.01)
        }
    }
}
