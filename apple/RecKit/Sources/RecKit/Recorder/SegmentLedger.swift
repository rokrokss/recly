import AVFoundation
import Foundation
import ReclyCore

/// One finished segment, named and placed on the timeline. Everything except the two facts that
/// need the file itself — its size and its hash — which are read on the IO queue afterwards.
struct ClosedSegment {
    let part: Int
    /// Which of a meeting recording's three files this is — the part carries it into the meta, and
    /// it is the only thing that tells two files of the same part apart.
    let track: Track
    let file: String
    let startOffsetSec: Double
    let durationSec: Double
}

/// Segment bookkeeping kept away from `AVAudioEngine` so it can be tested without a microphone:
/// what the open segment file is called, and where in the timeline each finished part starts.
///
/// `startOffsetSec` accumulates the durations actually written rather than `part * segmentSec`, so
/// a boundary that came in a little short does not shift every later part (docs/03 "parts").
struct SegmentLedger {
    /// 1-based; the same number across tracks of one time slice (docs/03 "이름 규칙").
    private(set) var openPart: Int = 1

    /// Audio confirmed so far — the `durationSec` handed to `finalize` if the directory is empty.
    private(set) var recordedSec: Double = 0

    private let base: String
    private let track: Track

    init(base: String, track: Track = Track.mono) {
        self.base = base
        self.track = track
    }

    func fileName(part: Int) -> String {
        MetaWriter.shared.partFileName(base: base, part: Int32(part), track: track)
    }

    /// The file the engine is writing into now.
    var openFileName: String { fileName(part: openPart) }

    /// Closes the open segment and opens the next one.
    mutating func close(durationSec: Double) -> ClosedSegment {
        let closed = ClosedSegment(
            part: openPart,
            track: track,
            file: openFileName,
            startOffsetSec: recordedSec,
            durationSec: durationSec
        )
        recordedSec += durationSec
        openPart += 1
        return closed
    }
}

/// Where the 900-second boundary falls inside a buffer the converter just produced.
///
/// The boundary has to be lossless (ADR-006), and the only way to get that out of `AVAudioFile` —
/// which has no "switch to the next file" of its own — is to cut the buffer at the exact frame the
/// segment fills up and write the remainder into the file that follows, in the same callback. This
/// is that cut, as arithmetic: no audio, no engine, no files.
struct SegmentSplitter {
    struct Chunk: Equatable {
        /// Frames into the buffer this chunk starts at.
        let offset: AVAudioFrameCount
        let count: AVAudioFrameCount
        /// The segment is exactly full after this chunk: close the file and open the next one.
        let closesSegment: Bool
    }

    let framesPerSegment: AVAudioFrameCount

    /// Frames written into the open segment. A stop reads it for the length of the tail.
    private(set) var framesInSegment: AVAudioFrameCount = 0

    init(framesPerSegment: AVAudioFrameCount) {
        precondition(framesPerSegment > 0, "a segment of no frames would never close")
        self.framesPerSegment = framesPerSegment
    }

    /// Cuts [frames] into the pieces the open segment and its successors can take. Every frame
    /// comes back in exactly one chunk, in order — a buffer longer than a whole segment (which a
    /// short `segmentSec` in a test makes easy) yields several.
    mutating func split(frames: AVAudioFrameCount) -> [Chunk] {
        var chunks: [Chunk] = []
        var offset: AVAudioFrameCount = 0
        var remaining = frames
        while remaining > 0 {
            let room = framesPerSegment - framesInSegment
            let take = min(room, remaining)
            let closes = take == room
            chunks.append(Chunk(offset: offset, count: take, closesSegment: closes))
            framesInSegment = closes ? 0 : framesInSegment + take
            offset += take
            remaining -= take
        }
        return chunks
    }
}
