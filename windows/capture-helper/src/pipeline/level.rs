//! The live strip's arithmetic: the loudest sample of each tenth of a second of the track a person
//! hears (docs/09 화면 원칙 6), so the app can draw that the capture is working.
//!
//! Ported from `apple/RecKit/Sources/RecKit/Recorder/LiveWaveform.swift`, and here for the same
//! reason it is in the recorder there: the only place the recorded audio exists is the write path,
//! and a second tap taken to draw a picture with would be a second answer to "is this being
//! captured". A peak per window and nothing else — the app keeps the ring, this side keeps no
//! history at all.

use super::SAMPLE_RATE_HZ;

/// 0.1 s at the 16 kHz every track is written at (ADR-006) — one bar of the strip.
pub const WINDOW_FRAMES: usize = (SAMPLE_RATE_HZ / 10) as usize;

/// The window still being counted, and nothing else. It is deliberately not a buffer of levels: a
/// pump that produced three windows sends three numbers and forgets them.
pub struct LiveLevel {
    window_frames: usize,
    open_peak: f32,
    open_frames: usize,
}

impl LiveLevel {
    pub fn new(window_frames: usize) -> Self {
        assert!(window_frames > 0, "a window of no frames never finishes");
        Self {
            window_frames,
            open_peak: 0.0,
            open_frames: 0,
        }
    }

    /// Counts [frames] into the windows and pushes every window that *finished* onto [out], oldest
    /// first. The open one is carried across the call: a buffer is 1600 frames only by accident, so
    /// a window ends inside a call far more often than between two.
    ///
    /// The open window is never pushed. A bar drawn while it is still growing rises at the right
    /// edge and then stops, which reads as the level having dropped.
    pub fn push(&mut self, frames: &[f32], out: &mut Vec<f32>) {
        for sample in frames {
            // Clamped: a mix can exceed full scale, and a bar taller than the row is not louder.
            let level = sample.abs().min(1.0);
            if level > self.open_peak {
                self.open_peak = level;
            }
            self.open_frames += 1;
            if self.open_frames == self.window_frames {
                out.push(self.open_peak);
                self.open_peak = 0.0;
                self.open_frames = 0;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The window is carried across the call boundary: two halves of one window are one bar, and it
    /// lands on the call that completed it.
    #[test]
    fn a_window_that_ends_between_two_pushes_is_still_one_window() {
        let mut level = LiveLevel::new(WINDOW_FRAMES);
        let mut out = Vec::new();

        level.push(&vec![0.5; WINDOW_FRAMES - 1], &mut out);
        assert!(out.is_empty(), "the window is not finished yet");
        level.push(&[0.25], &mut out);

        assert_eq!(vec![0.5], out, "one window, peaking at the loudest of both halves");
    }

    /// A buffer of several windows yields them all, oldest first, and the remainder stays open.
    #[test]
    fn a_long_buffer_yields_every_window_it_filled() {
        let mut level = LiveLevel::new(4);
        let mut out = Vec::new();

        level.push(&[0.1, 0.2, 0.3, 0.4, 0.9, 0.0, 0.0, 0.0, 0.7], &mut out);

        assert_eq!(vec![0.4, 0.9], out);
    }

    /// Loudness is not a sign: the trough of a wave is as loud as its crest, and a strip that only
    /// counted the crests would draw silence for a buffer that happened to arrive upside down.
    #[test]
    fn the_peak_is_the_loudest_sample_either_way_up() {
        let mut level = LiveLevel::new(3);
        let mut out = Vec::new();

        level.push(&[0.2, -0.8, 0.3], &mut out);

        assert_eq!(vec![0.8], out);
    }

    /// docs/12 "합산 −6 dB 헤드룸" leaves the mix inside full scale, but an endpoint that hands over
    /// more is not a taller bar — the row is the whole of the level.
    #[test]
    fn a_sample_past_full_scale_is_clamped_to_the_row() {
        let mut level = LiveLevel::new(2);
        let mut out = Vec::new();

        level.push(&[1.4, -2.0], &mut out);

        assert_eq!(vec![1.0], out);
    }

    /// Nothing is reported for a window that has not finished, however loud it already is.
    #[test]
    fn a_partial_window_is_not_a_bar() {
        let mut level = LiveLevel::new(WINDOW_FRAMES);
        let mut out = Vec::new();

        level.push(&vec![1.0; WINDOW_FRAMES - 1], &mut out);

        assert!(out.is_empty());
    }
}
