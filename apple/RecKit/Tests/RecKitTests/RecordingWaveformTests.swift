import Foundation
import XCTest
@testable import RecKit

/// docs/09 화면 원칙 2: the arithmetic under the detail's waveform. The decode itself needs a file
/// and a decoder; this is the half that turns whatever it found into the bars actually drawn, and
/// it is the half that has to be right at every width the row can be.
final class RecordingWaveformTests: XCTestCase {
    /// Fewer bars than windows: each bar is the loudest window under it, so a transient is still
    /// visible at the widths where several seconds share one column.
    func testFewerBarsThanWindowsTakeTheLoudestOfEach() {
        // Halves and quarters throughout, so what the assertion checks is the resampling and not
        // the last bit of a `Float` division.
        let bins = RecordingWaveform.bins(peaks: [0.125, 1, 0.25, 0.5, 0.375, 0.0625], count: 3)

        XCTAssertEqual(bins, [1, 0.5, 0.375], "the loudest of each pair, normalised by the loudest")
    }

    /// More bars than windows: the window is repeated across the bars that fall inside it rather
    /// than read off the end of the peaks.
    func testMoreBarsThanWindowsRepeatTheWindow() {
        let bins = RecordingWaveform.bins(peaks: [1, 0.5], count: 5)

        XCTAssertEqual(bins, [1, 1, 1, 0.5, 0.5])
    }

    /// The bars fill the row whatever the recording's own level was: what a waveform says is where
    /// the sound is, not how many decibels it had.
    func testTheLoudestBarFillsTheRow() {
        let bins = RecordingWaveform.bins(peaks: [0.03125, 0.015625], count: 2)

        XCTAssertEqual(bins, [1, 0.5])
    }

    /// …except a recording with nothing in it at all, which has no loudest bar to normalise by and
    /// stays flat rather than being blown up into a full-height wall of noise.
    func testSilenceStaysSilent() {
        XCTAssertEqual(RecordingWaveform.bins(peaks: [0, 0, 0], count: 3), [0, 0, 0])
    }

    /// Nothing decoded yet, and a row with no room in it: both are the baseline the view draws
    /// instead, and neither is a crash.
    func testNothingToDrawIsNoBars() {
        XCTAssertEqual(RecordingWaveform.bins(peaks: [], count: 8), [])
        XCTAssertEqual(RecordingWaveform.bins(peaks: [1, 0.5], count: 0), [])
        XCTAssertEqual(RecordingWaveform.bins(peaks: [1, 0.5], count: -3), [])
    }

    /// One bar for the whole recording is the narrowest the row ever gets, and it is the loudest
    /// moment of it.
    func testOneBarIsTheWholeRecording() {
        XCTAssertEqual(RecordingWaveform.bins(peaks: [0.1, 0.9, 0.3], count: 1), [1])
    }

    /// A width that does not divide the windows evenly still gets exactly as many bars as it asked
    /// for, and every window is under one of them.
    func testEveryWidthGetsTheBarsItAskedFor() {
        for count in 1...20 {
            XCTAssertEqual(
                RecordingWaveform.bins(peaks: Array(repeating: 0.5, count: 7), count: count).count,
                count
            )
        }
    }
}
