import XCTest
@testable import RecKit

/// docs/09 화면 원칙 6: the strip is a reading of the audio that is being written, so what it is
/// made of has to be exactly that — one window per tenth of a second, the loudest sample in it, and
/// nothing on screen that has not been recorded yet.
final class LiveWaveformTests: XCTestCase {

    private func add(_ samples: [Float], to waveform: inout LiveWaveform) {
        samples.withUnsafeBufferPointer { waveform.add($0) }
    }

    /// A tap delivers 4096 frames at a time and a window is 1600, so the cut nearly always falls
    /// inside a buffer: a counter that started over at each `add` would draw one window per buffer.
    func testAWindowIsCutAtItsFrameWhicheverBufferItFallsIn() {
        var waveform = LiveWaveform(windowFrames: 4, capacity: 8)

        add([0.1, 0.2, 0.3], to: &waveform)
        XCTAssertEqual(waveform.peaks, [], "a window is not finished until its last frame")

        add([0.4, 0.5], to: &waveform)
        XCTAssertEqual(waveform.peaks, [0.4], "the window closed on its fourth frame, not the buffer's end")
    }

    /// The peak is of |sample|: a waveform drawn from the raw value would show silence for the half
    /// of a loud wave that is below zero.
    func testTheLoudestSampleWinsWhicheverSideOfZeroItIsOn() {
        var waveform = LiveWaveform(windowFrames: 4, capacity: 8)

        add([0.2, -0.9, 0.3, 0.1], to: &waveform)

        XCTAssertEqual(waveform.peaks, [0.9])
    }

    /// A mix can add up past full scale (`SegmentedRecorder.mix` halves each track, but a converter
    /// can still overshoot), and a bar taller than the row it is drawn in is not louder.
    func testAPeakPastFullScaleIsClamped() {
        var waveform = LiveWaveform(windowFrames: 2, capacity: 4)

        add([1.4, 0.1], to: &waveform)

        XCTAssertEqual(waveform.peaks, [1])
    }

    /// Thirty seconds of windows and then the oldest goes: a strip that kept every window of a
    /// three-hour recording would be a hundred thousand floats copied ten times a second.
    func testTheOldestWindowFallsOffPastTheCapacity() {
        var waveform = LiveWaveform(windowFrames: 1, capacity: 3)

        add([0.1, 0.2, 0.3, 0.4, 0.5], to: &waveform)

        XCTAssertEqual(waveform.peaks, [0.3, 0.4, 0.5], "the ring is the newest windows, oldest first")
    }

    /// Nothing is drawn from the window still being counted: a bar that grows and then stops at the
    /// right edge reads as the level having dropped, when all that happened is the window ended.
    func testThePartialWindowIsNotOnScreen() {
        var waveform = LiveWaveform(windowFrames: 4, capacity: 8)

        add([1, 1, 1, 1, 1, 1], to: &waveform)

        XCTAssertEqual(waveform.peaks, [1], "the two frames of the open window are counted, not shown")
    }

    func testResetLeavesNothingBehind() {
        var waveform = LiveWaveform(windowFrames: 2, capacity: 4)
        add([0.5, 0.5, 0.5], to: &waveform)

        waveform.reset()

        XCTAssertEqual(waveform.peaks, [])
        add([0.25, 0.25], to: &waveform)
        XCTAssertEqual(waveform.peaks, [0.25], "the half-counted window survived the reset")
    }

    /// The curve the bars are drawn on: silence is nothing, full scale is the whole row, and every
    /// level between the two is taller than a linear reading of it — which is what makes a quiet
    /// room a visible bar rather than a line indistinguishable from a microphone that is off.
    func testTheBarCurveEndsWhereTheRowDoesAndRisesFasterInBetween() {
        XCTAssertEqual(LiveWaveformView.height(ofPeak: 0), 0)
        XCTAssertEqual(LiveWaveformView.height(ofPeak: 1), 1)
        for peak: Float in [0.05, 0.2, 0.5, 0.8] {
            XCTAssertGreaterThan(
                LiveWaveformView.height(ofPeak: peak), peak,
                "\(peak) full scale is drawn no taller than its own amplitude"
            )
        }
    }
}
