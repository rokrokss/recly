import AVFoundation
import Foundation
import ReclyCore

/// One track's chain of segment files: the `.m4a` that is open now, and the ledger that says what
/// it is called and where on the timeline it starts.
///
/// A mono recording has one of these and a meeting recording has three (`mic`, `sys`, `mix`), and
/// the three are deliberately dumb about each other: the boundary lives in the recorder's single
/// `SegmentSplitter`, which is what makes the part numbers, the offsets and the durations identical
/// across the tracks (docs/03 "같은 시간 구간이면 같은 번호"). A writer that decided for itself when
/// its segment was full would drift out of step with its siblings within the hour.
///
/// Everything here runs under the recorder's lock, on whatever thread delivered the audio.
final class TrackWriter {
    let track: Track
    /// The file's own PCM format — what the converter targets and what `write(from:)` demands.
    let format: AVAudioFormat

    /// Called with the file a boundary has just closed and released, before anything reads it.
    /// `nil` in the app: it exists because "the container came back unreadable" is the one state no
    /// fake input can produce from outside — that file is written by the real AAC encoder.
    var afterSegmentClosed: ((URL) -> Void)?

    private let directory: URL
    private var ledger: SegmentLedger
    private var file: AVAudioFile?

    /// Opens part 1. Throws if the very first segment cannot be created, which is a recording that
    /// never started rather than one that lost a part.
    init(track: Track, base: String, directory: URL) throws {
        self.track = track
        self.directory = directory
        ledger = SegmentLedger(base: base, track: track)
        let file = try SegmentedRecorder.openSegmentFile(named: ledger.openFileName, in: directory)
        self.file = file
        format = file.processingFormat
    }

    /// The part number the open segment will be filed under — the same across a recording's tracks.
    var openPart: Int { ledger.openPart }

    /// False once the recording has let its files go, or after a rollover could not open the next
    /// segment — either way there is nowhere left to put audio.
    var isOpen: Bool { file != nil }

    /// The write itself, in a scope of its own on purpose: the strong reference to the `AVAudioFile`
    /// is born and dies inside this call. A local that outlived it would still be holding the file
    /// when the caller closes the segment — the container would stay open, its trailing MPEG-4 atoms
    /// unwritten, and the sha256 taken next would be the hash of a file no reader can open.
    ///
    /// False means there is no open file, because a rollover could not open the next one and said so
    /// as a fatal error. The caller stops counting the audio: a recording's length is what reached a
    /// file, and audio that went nowhere is not part of it.
    func write(_ piece: AVAudioPCMBuffer) throws -> Bool {
        guard let file else { return false }
        try file.write(from: piece)
        return true
    }

    /// The segment is full. Returns only once the closed file has been released, which is what
    /// writes the trailing atoms that make it readable — so the caller may hash it the moment this
    /// comes back. [openNext] is a separate call because a failure to open the *next* segment is a
    /// different event from finishing this one, and the part just closed must be filed either way.
    func closeSegment(durationSec: Double) -> ClosedSegment {
        let closed = ledger.close(durationSec: durationSec)
        file = nil
        afterSegmentClosed?(directory.appendingPathComponent(closed.file))
        return closed
    }

    func openNext() throws {
        file = try SegmentedRecorder.openSegmentFile(named: ledger.openFileName, in: directory)
    }

    /// Releasing the file is what writes the trailing MPEG-4 atoms; until it happens the last part
    /// is a container `AVAudioFile(forReading:)` cannot open.
    func release() {
        file = nil
    }
}
