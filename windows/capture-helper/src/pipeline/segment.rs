//! Segment bookkeeping: where the 900-second boundary falls inside a buffer, and what the file on
//! either side of it is called. Ported from `apple/RecKit/Sources/RecKit/Recorder/SegmentLedger.swift`
//! so the two desktops name and number parts identically (docs/03 "이름 규칙").
//!
//! No audio, no files, no encoder — this is the part of the rules a macOS host can check.

use crate::protocol::Track;

/// Where the segment boundary falls inside a buffer.
///
/// The boundary has to be lossless (ADR-006), and the only way to get that is to cut the buffer at
/// the exact frame the segment fills up and write the remainder into the file that follows, in the
/// same callback. This is that cut, as arithmetic.
pub struct SegmentSplitter {
    frames_per_segment: u64,
    frames_in_segment: u64,
}

#[derive(Debug, PartialEq, Eq)]
pub struct Chunk {
    /// Frames into the buffer this chunk starts at.
    pub offset: usize,
    pub count: usize,
    /// The segment is exactly full after this chunk: close the files and open the next ones.
    pub closes_segment: bool,
}

impl SegmentSplitter {
    pub fn new(frames_per_segment: u64) -> Self {
        assert!(frames_per_segment > 0, "a segment of no frames would never close");
        Self {
            frames_per_segment,
            frames_in_segment: 0,
        }
    }

    /// Frames written into the open segment. A stop reads it for the length of the tail.
    pub fn frames_in_segment(&self) -> u64 {
        self.frames_in_segment
    }

    /// A full segment, in frames — the length every part but the last one has.
    pub fn frames_per_segment(&self) -> u64 {
        self.frames_per_segment
    }

    /// Cuts [frames] into the pieces the open segment and its successors can take. Every frame comes
    /// back in exactly one chunk, in order — a buffer longer than a whole segment (which a short
    /// `segmentSec` in a test makes easy) yields several.
    pub fn split(&mut self, frames: usize) -> Vec<Chunk> {
        let mut chunks = Vec::new();
        let mut offset = 0usize;
        let mut remaining = frames as u64;
        while remaining > 0 {
            let room = self.frames_per_segment - self.frames_in_segment;
            let take = room.min(remaining);
            let closes = take == room;
            chunks.push(Chunk {
                offset,
                count: take as usize,
                closes_segment: closes,
            });
            self.frames_in_segment = if closes { 0 } else { self.frames_in_segment + take };
            offset += take as usize;
            remaining -= take;
        }
        chunks
    }
}

/// One finished segment, named and placed on the timeline. Everything except the two facts that
/// need the file itself — its size and its hash — which are read once it is closed.
#[derive(Debug, PartialEq)]
pub struct ClosedSegment {
    pub part: u32,
    pub track: Track,
    pub file: String,
    pub start_offset_sec: f64,
    pub duration_sec: f64,
}

/// What the open segment file of one track is called, and where in the timeline each finished part
/// starts.
///
/// `start_offset_sec` accumulates the durations actually written rather than `part × segmentSec`,
/// so a boundary that came in a little short does not shift every later part (docs/03 "parts").
pub struct SegmentLedger {
    base: String,
    track: Track,
    open_part: u32,
    recorded_sec: f64,
}

impl SegmentLedger {
    pub fn new(base: &str, track: Track) -> Self {
        Self {
            base: base.to_string(),
            track,
            open_part: 1,
            recorded_sec: 0.0,
        }
    }

    /// 1-based; the same number across the tracks of one time slice (docs/03 "이름 규칙").
    pub fn open_part(&self) -> u32 {
        self.open_part
    }

    /// The file the encoder is writing into now.
    pub fn open_file_name(&self) -> String {
        format!("{}_p{:03}_{}.m4a", self.base, self.open_part, self.track.name())
    }

    /// Closes the open segment and opens the next one.
    pub fn close(&mut self, duration_sec: f64) -> ClosedSegment {
        let closed = ClosedSegment {
            part: self.open_part,
            track: self.track,
            file: self.open_file_name(),
            start_offset_sec: self.recorded_sec,
            duration_sec,
        };
        self.recorded_sec += duration_sec;
        self.open_part += 1;
        closed
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Deliverable 6: segment boundaries. Every frame comes back exactly once, and only a chunk that
    /// fills the segment to the frame closes it.
    #[test]
    fn a_buffer_is_cut_at_the_frame_the_segment_fills() {
        let mut splitter = SegmentSplitter::new(100);
        assert_eq!(
            vec![Chunk { offset: 0, count: 60, closes_segment: false }],
            splitter.split(60),
        );
        assert_eq!(
            vec![
                Chunk { offset: 0, count: 40, closes_segment: true },
                Chunk { offset: 40, count: 50, closes_segment: false },
            ],
            splitter.split(90),
        );
        assert_eq!(50, splitter.frames_in_segment());
    }

    /// A buffer longer than a whole segment crosses as many boundaries as it has to.
    #[test]
    fn a_long_buffer_crosses_several_boundaries() {
        let mut splitter = SegmentSplitter::new(10);
        let chunks = splitter.split(25);
        assert_eq!(3, chunks.len());
        assert_eq!(vec![true, true, false], chunks.iter().map(|c| c.closes_segment).collect::<Vec<_>>());
        assert_eq!(25, chunks.iter().map(|c| c.count).sum::<usize>());
        assert_eq!(5, splitter.frames_in_segment());
    }

    /// Deliverable 6: part numbers and names. docs/03 "이름 규칙" — `{base}_p{NNN}_{track}.m4a`,
    /// numbered from 1, and offsets that accumulate what was actually written.
    #[test]
    fn parts_are_named_and_offset_as_the_spec_says() {
        let mut ledger = SegmentLedger::new("20260826T010000Z_desktop_01J9ZZ12", Track::Sys);
        assert_eq!("20260826T010000Z_desktop_01J9ZZ12_p001_sys.m4a", ledger.open_file_name());
        let first = ledger.close(900.0);
        assert_eq!(1, first.part);
        assert_eq!(0.0, first.start_offset_sec);
        // A short boundary does not shift the parts after it onto a nominal grid.
        let second = ledger.close(880.0);
        assert_eq!(900.0, second.start_offset_sec);
        assert_eq!("20260826T010000Z_desktop_01J9ZZ12_p003_sys.m4a", ledger.open_file_name());
        assert_eq!(3, ledger.open_part());
    }
}
