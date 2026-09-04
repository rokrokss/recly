import AVFoundation
import XCTest
@testable import RecKit

/// M4-L3 deliverable 2: the microphone runs on the input device's clock and the tap on the output
/// device's, and docs/12 puts the difference at "시간당 수십 ms". These are the claim that an hour of
/// it ends under 20 ms apart — as arithmetic first (`DriftEstimator`, no audio at all), then through
/// the real resampler.
final class DriftCompensatorTests: XCTestCase {
    /// docs/12's own number, as a rate: fifty milliseconds an hour is fourteen parts per million.
    private static let documentedDrift = 50.0 / 3_600_000

    /// The lane's target, verified the way the lead asked for — the same source into both streams
    /// with a synthetic rate difference on one of them, run for an hour of simulated time.
    ///
    /// Both directions, and a hundred parts per million as well as the documented fourteen: seven
    /// times worse than docs/12 describes still finishes inside the target.
    func testAnHourOfDriftEndsUnderTwentyMilliseconds() {
        for drift in [Self.documentedDrift, -Self.documentedDrift, 0.0001, -0.0001] {
            let run = simulate(drift: drift, seconds: 3600)

            XCTAssertLessThan(
                abs(run.finalSkewSec), 0.020,
                "\(drift * 1e6) ppm: an hour ends \(run.finalSkewSec * 1000) ms apart"
            )
        }
    }

    /// What is left over, and why it is what it is: the correction is of the *rate*, so the first
    /// interval runs before there is anything to estimate from and its drift stays. Sixty seconds of
    /// it, and nothing after — which is what makes the hour above land where it does, and what puts
    /// a floor under how good this can get without the host timestamps.
    func testWhatIsLeftOverIsExactlyTheFirstIntervalsWorth() {
        for drift in [Self.documentedDrift, 0.0001] {
            let run = simulate(drift: drift, seconds: 3600)

            XCTAssertEqual(
                run.finalSkewSec, drift * DriftEstimator.intervalSec, accuracy: 0.0005,
                "one interval's drift, and not the other fifty-nine"
            )
        }
    }

    /// The negative check the target needs to mean anything: without the correction the same hour
    /// ends far outside it, so it is the estimator being tested and not the simulation being kind.
    func testTheSameHourWithoutTheCorrectionMissesTheTargetByAMile() {
        let run = simulate(drift: 0.0001, seconds: 3600)

        XCTAssertEqual(run.uncorrectedSkewSec, 0.36, accuracy: 0.001, "0.01% of an hour")
        XCTAssertGreaterThan(
            abs(run.uncorrectedSkewSec) / max(abs(run.finalSkewSec), 1e-9), 55,
            "the correction is worth an order of magnitude and a half, not a rounding difference"
        )
    }

    /// The estimator refuses to guess from an interval one of the streams sat out — a microphone
    /// being restarted for a device change, a tap being re-created. A ratio built from that would
    /// resample the system track into noise for the rest of the recording.
    func testAnIntervalWithAStalledStreamDoesNotMoveTheRatio() {
        var estimator = DriftEstimator(intervalSec: 60)
        let full = Double(SegmentedRecorder.sampleRateHz) * 60

        XCTAssertFalse(
            estimator.observe(micFrames: full, sysFrames: full / 10, atSec: 60),
            "a tenth of an interval's frames is not a rate"
        )
        XCTAssertEqual(estimator.ratio, 1)
        XCTAssertFalse(estimator.observe(micFrames: full * 2, sysFrames: full / 10, atSec: 120))
        XCTAssertEqual(estimator.ratio, 1, "and the microphone alone is not one either")
    }

    /// A clock difference is parts per million. A ratio of anything like a percent is a stream that
    /// stopped, not a clock — and it is *refused* rather than clamped into range, because a clamped
    /// one is still a correction: a whole percent of the next minute resampled to chase frames that
    /// were never late.
    func testARatioNoClockCouldProduceIsRefusedRatherThanClamped() {
        var estimator = DriftEstimator(intervalSec: 60)
        let mic = Double(SegmentedRecorder.sampleRateHz) * 60

        // Twice as many system frames as microphone frames — impossible from a clock.
        XCTAssertFalse(estimator.observe(micFrames: mic, sysFrames: mic * 2, atSec: 60))

        XCTAssertEqual(estimator.ratio, 1, "not 1.01, which would resample the next minute by 1%")
    }

    /// The case the refusal is really for: a tap that was away for twenty of the sixty seconds
    /// delivered two thirds of an interval's frames, which is enough to pass the "was it running at
    /// all" guard and reads as a stream running a third slow. Nothing about the clocks changed.
    func testAnIntervalWithATapOutageInItLeavesTheRatioAlone() {
        var estimator = DriftEstimator(intervalSec: 60)
        let rate = Double(SegmentedRecorder.sampleRateHz)

        // Forty seconds of system audio against sixty of microphone: past the half-interval guard.
        XCTAssertFalse(estimator.observe(micFrames: rate * 60, sysFrames: rate * 40, atSec: 60))

        XCTAssertEqual(estimator.ratio, 1)
        // And the interval that follows is measured normally, from the frames as they now stand.
        XCTAssertTrue(estimator.observe(
            micFrames: rate * 120, sysFrames: rate * 40 + rate * 60 * 1.0001, atSec: 120
        ))
        XCTAssertEqual(estimator.ratio, 1.0001, accuracy: 1e-9)
    }

    /// The outage the refusal cannot catch: three hundred milliseconds missing out of a minute is
    /// half a percent, well inside the band, and if the observation that closes the interval falls
    /// inside the hole the ratio moves before anyone knows there was one. Reported afterwards, the
    /// correction taken across it is put back — otherwise a ratio half a percent out runs for the
    /// next minute and drops about as much audio again as the outage did.
    func testACorrectionTakenAcrossAnOutageIsPutBackWhenTheOutageIsReported() {
        var estimator = DriftEstimator(intervalSec: 60)
        let rate = Double(SegmentedRecorder.sampleRateHz)

        // A clean minute at a real 100 ppm difference, believed.
        XCTAssertTrue(estimator.observe(micFrames: rate * 60, sysFrames: rate * 60 * 1.0001, atSec: 60))
        let believed = estimator.ratio

        // The minute that follows has a 300 ms hole in it, and the tap is still away when the
        // interval closes: 59.8 seconds of system audio against 60 of microphone.
        let sysAcrossTheHole = rate * 60 * 1.0001 + rate * 59.8 * 1.0001
        XCTAssertTrue(estimator.observe(micFrames: rate * 120, sysFrames: sysAcrossTheHole, atSec: 120))
        XCTAssertLessThan(estimator.ratio, believed - 0.001, "the hole reads as a stream running slow")

        // The tap comes back and says how long it was gone for.
        estimator.reanchor(
            micFrames: rate * 120.2, sysFrames: sysAcrossTheHole, atSec: 120.2, outageSec: 0.3
        )

        XCTAssertEqual(estimator.ratio, believed, accuracy: 1e-12, "the hole's correction is undone")
        // And the interval after it is measured from the new baselines like any other: a minute of
        // both streams on top of where the re-anchor left them.
        XCTAssertTrue(estimator.observe(
            micFrames: rate * 120.2 + rate * 60,
            sysFrames: sysAcrossTheHole + rate * 60 * 1.0001,
            atSec: 180.5
        ))
        XCTAssertEqual(estimator.ratio, believed * 1.0001, accuracy: 1e-9)
    }

    /// An outage that is over before any interval closed has nothing to put back: the re-anchor is
    /// the whole of the answer, and a correction from *before* the hole is not one of its victims.
    func testAnOutageDoesNotUndoACorrectionThatCameBeforeIt() {
        var estimator = DriftEstimator(intervalSec: 60)
        let rate = Double(SegmentedRecorder.sampleRateHz)

        XCTAssertTrue(estimator.observe(micFrames: rate * 60, sysFrames: rate * 60 * 1.0001, atSec: 60))
        let believed = estimator.ratio

        // Away for 300 ms, forty seconds after that correction and long before the next interval.
        estimator.reanchor(
            micFrames: rate * 100, sysFrames: rate * 100, atSec: 100.3, outageSec: 0.3
        )

        XCTAssertEqual(estimator.ratio, believed, accuracy: 1e-12, "a minute that was clean stands")
    }

    /// The clamp is still there, for the one thing the refusal cannot catch: corrections that are
    /// each believable and compound. Nine tenths of the deviation, twice, is 1.8% — and 1% is where
    /// it stops.
    func testTheCompoundedRatioIsStillClampedToWhatAClockCanDo() {
        var estimator = DriftEstimator(intervalSec: 60)
        let rate = Double(SegmentedRecorder.sampleRateHz)
        let step = 1 + DriftEstimator.maxDeviation * 0.9

        XCTAssertTrue(estimator.observe(micFrames: rate * 60, sysFrames: rate * 60 * step, atSec: 60))
        XCTAssertTrue(estimator.observe(
            micFrames: rate * 120, sysFrames: rate * 60 * step + rate * 60 * step, atSec: 120
        ))

        XCTAssertEqual(estimator.ratio, 1 + DriftEstimator.maxDeviation, accuracy: 1e-12)
    }

    // MARK: - Through the resampler

    /// The other half of deliverable 2: the estimate is applied by `AVAudioConverter`, as the rate
    /// its input format claims. A tap running fast is told its rate is *higher* than it is, so the
    /// resampler takes proportionally more of its frames to make one — which is the direction it is
    /// easy to get backwards, and the reason the ratio is asserted against the drift rather than
    /// merely "it moved".
    func testTheResamplerAppliesTheRatioInTheDirectionThatRemovesTheDrift() throws {
        let target = try XCTUnwrap(AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: Double(SegmentedRecorder.sampleRateHz),
            channels: 1,
            interleaved: false
        ))
        // A tap running 0.1% fast: 48,048 frames a second where the microphone's timeline wants
        // 16,000 out of them, not 16,016. One-second intervals so a twenty-second run closes twenty
        // of them; the real one is sixty seconds and the arithmetic is the same.
        let fast = 1.001
        let compensator = DriftCompensator(target: target, intervalSec: 1, startSec: 0)
        let tap = try XCTUnwrap(AVAudioFormat(
            commonFormat: .pcmFormatFloat32, sampleRate: 48_000, channels: 1, interleaved: false
        ))

        var micFrames: AVAudioFramePosition = 0
        var underrunAfterTheFirstSecond = 0
        for tick in 1 ... 200 {
            let atSec = Double(tick) / 10
            let frames = AVAudioFrameCount((48_000 * fast / 10).rounded())
            let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: tap, frameCapacity: frames))
            buffer.frameLength = frames
            compensator.append(buffer, atSec: atSec)

            let take = AVAudioFrameCount(SegmentedRecorder.sampleRateHz / 10)
            let out = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: target, frameCapacity: take))
            compensator.take(frames: take, into: out)
            micFrames += AVAudioFramePosition(take)
            compensator.observeMic(frames: micFrames, atSec: atSec)
            if tick == 20 { underrunAfterTheFirstSecond = compensator.underrunFrames }
        }

        XCTAssertEqual(micFrames, 320_000, "twenty seconds of microphone timeline")
        XCTAssertGreaterThan(compensator.ratio, 1, "a tap running fast is told its rate is higher")
        XCTAssertEqual(compensator.ratio, fast, accuracy: 0.0002, "and by exactly how much it is fast")
        // The queue's lead is what keeps the resampler's block from becoming a hole in the system
        // track: after the first second, when it is established, nothing is ever short again.
        XCTAssertEqual(
            compensator.underrunFrames, underrunAfterTheFirstSecond,
            "nineteen more seconds and the system track never went silent for want of a frame"
        )
        // The ratio moves once, at the end of the first interval, and every interval after it reads
        // the corrected stream as already right — so one resampler is drained and replaced, not
        // twenty, and what it was holding is the only thing thrown away.
        XCTAssertLessThan(
            compensator.droppedFrames, 400,
            "the only thing thrown away is the part-block a replaced resampler was holding"
        )
    }

    /// The queue never grows without end. A microphone that stalls — the engine down for a device
    /// change — leaves the tap delivering into it, and an hour of that is an hour of audio nobody
    /// will ever take.
    func testSystemAudioTheMicrophoneNeverAsksForIsDroppedRatherThanQueued() throws {
        let target = try XCTUnwrap(AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: Double(SegmentedRecorder.sampleRateHz),
            channels: 1,
            interleaved: false
        ))
        let compensator = DriftCompensator(target: target)
        let tap = try XCTUnwrap(AVAudioFormat(
            commonFormat: .pcmFormatFloat32, sampleRate: 16_000, channels: 1, interleaved: false
        ))
        // Ten seconds of system audio and no microphone at all.
        for tick in 1 ... 10 {
            let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: tap, frameCapacity: 16_000))
            buffer.frameLength = 16_000
            compensator.append(buffer, atSec: Double(tick))
        }
        let out = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: target, frameCapacity: 1600))

        compensator.take(frames: 1600, into: out)

        XCTAssertLessThanOrEqual(
            compensator.queuedFrames, 1600 + DriftCompensator.converterBlockFrames,
            "the resampler's block plus one microphone buffer is kept, the rest is gone"
        )
        XCTAssertGreaterThan(compensator.droppedFrames, 100_000)
    }

    /// The cap that holds when there is no `take` at all: the microphone is down for a device
    /// change and the tap keeps delivering. `take`'s own trim never runs in that window, so without
    /// this the queue grows at 64 KB a second for as long as the microphone is away.
    func testAQueueNothingIsTakingFromIsStillBounded() throws {
        let target = try XCTUnwrap(AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: Double(SegmentedRecorder.sampleRateHz),
            channels: 1,
            interleaved: false
        ))
        let compensator = DriftCompensator(target: target)
        let tap = try XCTUnwrap(AVAudioFormat(
            commonFormat: .pcmFormatFloat32, sampleRate: 48_000, channels: 1, interleaved: false
        ))

        // Ten seconds of system audio and not one microphone buffer.
        for tick in 1 ... 10 {
            let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: tap, frameCapacity: 48_000))
            buffer.frameLength = 48_000
            compensator.append(buffer, atSec: Double(tick))
        }

        XCTAssertLessThanOrEqual(
            compensator.queuedFrames,
            Int(Double(SegmentedRecorder.sampleRateHz) * DriftCompensator.maxQueuedSec)
                + DriftCompensator.converterBlockFrames,
            "a second past the lead, and the ten seconds before it are gone"
        )
        XCTAssertGreaterThan(compensator.droppedFrames, 140_000, "nine of the ten seconds")
    }

    // MARK: - The simulation

    private struct Run {
        /// How far apart the two streams end, in seconds — the number the lane's target is about.
        let finalSkewSec: Double
        /// The same run with the estimator's answer ignored: the drift itself.
        let uncorrectedSkewSec: Double
    }

    /// An hour of two clocks, one of them [drift] fast, sampled every second the way the recorder
    /// samples it — the microphone's cumulative frames against the system frames that reached its
    /// timeline. No audio: this is the arithmetic the resampler is handed.
    private func simulate(drift: Double, seconds: Int) -> Run {
        let rate = Double(SegmentedRecorder.sampleRateHz)
        var estimator = DriftEstimator(intervalSec: DriftEstimator.intervalSec, startSec: 0)
        var micFrames = 0.0
        var sysFrames = 0.0
        var uncorrected = 0.0

        for second in 1 ... seconds {
            micFrames += rate
            // The tap delivers its hardware's frames; the resampler turns them into output frames at
            // the corrected ratio, which is where the correction actually lands. The estimate is fed
            // the frames as produced, and the skew — how far apart the streams have ended up — is
            // their running difference, which is what the queue would be carrying.
            sysFrames += rate * (1 + drift) / estimator.ratio
            uncorrected += rate * (1 + drift)
            _ = estimator.observe(micFrames: micFrames, sysFrames: sysFrames, atSec: Double(second))
        }
        return Run(
            finalSkewSec: (sysFrames - micFrames) / rate,
            uncorrectedSkewSec: (uncorrected - micFrames) / rate
        )
    }
}
