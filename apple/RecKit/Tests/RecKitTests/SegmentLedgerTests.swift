import AVFoundation
import ReclyCore
import XCTest
@testable import RecKit

/// The two halves of a segment boundary that need no microphone: where the cut falls inside a
/// buffer, and what the part on either side of it is called and where it sits on the timeline.
final class SegmentLedgerTests: XCTestCase {
    // MARK: - The cut

    /// A buffer that fits leaves the segment open — one chunk, the whole thing, no rollover.
    func testABufferInsideTheSegmentIsNotCut() {
        var splitter = SegmentSplitter(framesPerSegment: 1000)

        XCTAssertEqual(
            splitter.split(frames: 400),
            [SegmentSplitter.Chunk(offset: 0, count: 400, closesSegment: false)]
        )
        XCTAssertEqual(splitter.framesInSegment, 400)
    }

    /// The boundary is lossless: the frames before it and the frames after it are both accounted
    /// for, in one pass, and the second chunk starts exactly where the first ended.
    func testABufferStraddlingTheBoundaryIsCutAtTheFrameThatFillsTheSegment() {
        var splitter = SegmentSplitter(framesPerSegment: 1000)
        _ = splitter.split(frames: 900)

        XCTAssertEqual(
            splitter.split(frames: 250),
            [
                SegmentSplitter.Chunk(offset: 0, count: 100, closesSegment: true),
                SegmentSplitter.Chunk(offset: 100, count: 150, closesSegment: false),
            ]
        )
        XCTAssertEqual(splitter.framesInSegment, 150)
    }

    /// A buffer that lands exactly on the boundary closes the segment and adds nothing to the next
    /// one — the file that follows must not be opened with a zero-frame write already in it.
    func testABufferThatFillsTheSegmentExactlyClosesItAndOpensNothing() {
        var splitter = SegmentSplitter(framesPerSegment: 1000)

        XCTAssertEqual(
            splitter.split(frames: 1000),
            [SegmentSplitter.Chunk(offset: 0, count: 1000, closesSegment: true)]
        )
        XCTAssertEqual(splitter.framesInSegment, 0)
    }

    /// A short `segmentSec` (a smoke test) makes a buffer longer than a whole segment ordinary, and
    /// then one callback has to cross two boundaries.
    func testABufferLongerThanASegmentCrossesEveryBoundaryItSpans() {
        var splitter = SegmentSplitter(framesPerSegment: 100)
        _ = splitter.split(frames: 60)

        XCTAssertEqual(
            splitter.split(frames: 250),
            [
                SegmentSplitter.Chunk(offset: 0, count: 40, closesSegment: true),
                SegmentSplitter.Chunk(offset: 40, count: 100, closesSegment: true),
                SegmentSplitter.Chunk(offset: 140, count: 100, closesSegment: true),
                SegmentSplitter.Chunk(offset: 240, count: 10, closesSegment: false),
            ]
        )
        XCTAssertEqual(splitter.framesInSegment, 10)
    }

    /// The property the whole design rests on: over an arbitrary run of buffer sizes every frame
    /// comes back exactly once, in order, and each segment is filled to the frame before the next
    /// one opens. A cut that lost or duplicated audio would show up here as a gap in the run.
    func testNoFrameIsLostOrRepeatedAcrossManyBuffers() {
        var splitter = SegmentSplitter(framesPerSegment: 512)
        var consumed: AVAudioFrameCount = 0
        var closed = 0

        for frames in [1, 511, 2, 513, 1024, 7, 500, 1536, 3] as [AVAudioFrameCount] {
            var expectedOffset: AVAudioFrameCount = 0
            for chunk in splitter.split(frames: frames) {
                XCTAssertEqual(chunk.offset, expectedOffset, "chunks must tile the buffer")
                expectedOffset += chunk.count
                consumed += chunk.count
                if chunk.closesSegment { closed += 1 }
            }
            XCTAssertEqual(expectedOffset, frames, "every frame of the buffer is placed")
        }

        XCTAssertEqual(consumed, 4097)
        // 4097 frames at 512 to a segment: eight closed, one still open with a single frame in it.
        XCTAssertEqual(closed, 8)
        XCTAssertEqual(splitter.framesInSegment, 1)
    }

    /// 900 s × 16 kHz, the real number, and it fits in the 32-bit frame count AVFoundation counts in.
    func testTheRealSegmentIsFourteenPointFourMillionFrames() {
        let splitter = SegmentSplitter(
            framesPerSegment: AVAudioFrameCount(SegmentedRecorder.defaultSegmentSec * SegmentedRecorder.sampleRateHz)
        )

        XCTAssertEqual(splitter.framesPerSegment, 14_400_000)
    }

    // MARK: - Names and offsets

    /// docs/03 "이름 규칙": `{base}_p{NNN}_{track}.m4a`, three digits from one.
    func testPartFileNamesArePaddedToThreeDigits() {
        let ledger = SegmentLedger(base: base)

        XCTAssertEqual(ledger.openFileName, "\(base)_p001_mono.m4a")
        XCTAssertEqual(ledger.fileName(part: 10), "\(base)_p010_mono.m4a")
        XCTAssertEqual(ledger.fileName(part: 123), "\(base)_p123_mono.m4a")
    }

    /// `startOffsetSec` accumulates the durations actually written, not `part × segmentSec`: a
    /// boundary that came in short must not shift every later part (docs/03 "parts").
    func testOffsetsAccumulateTheDurationsActuallyWritten() {
        var ledger = SegmentLedger(base: base)

        let first = ledger.close(durationSec: 900)
        let second = ledger.close(durationSec: 899.5)
        let third = ledger.close(durationSec: 900)

        XCTAssertEqual([first.part, second.part, third.part], [1, 2, 3])
        XCTAssertEqual(first.file, "\(base)_p001_mono.m4a")
        XCTAssertEqual(third.file, "\(base)_p003_mono.m4a")
        XCTAssertEqual(first.startOffsetSec, 0)
        XCTAssertEqual(second.startOffsetSec, 900)
        XCTAssertEqual(third.startOffsetSec, 1799.5)
        XCTAssertEqual(ledger.recordedSec, 2699.5)
        XCTAssertEqual(ledger.openFileName, "\(base)_p004_mono.m4a")
    }

    /// The reconciler reads the part number back off the name a stop or a crash left behind; the
    /// two have to agree, so they are checked against each other rather than against a literal.
    func testThePartNumberIsReadBackOutOfTheName() {
        let ledger = SegmentLedger(base: base)

        for part in [1, 9, 10, 100, 999] {
            XCTAssertEqual(PartReconciler.partNumber(of: ledger.fileName(part: part)), part)
        }
        XCTAssertNil(PartReconciler.partNumber(of: "\(base).meta.json"))
        XCTAssertNil(PartReconciler.partNumber(of: "\(base)_p001_mono.m4a.pending"))
    }

    private let base = "20260826T010000Z_desktop_01J9ABCD"
}
