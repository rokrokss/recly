//! Linear-interpolation resampling, streaming and mono.
//!
//! Every stream in this helper is put on one timeline before anything else happens to it: the
//! microphone and the loopback endpoint each arrive at whatever rate WASAPI's shared-mode mix
//! format says, and ADR-006's files are 16 kHz mono. This is the only thing in the pipeline that
//! changes a frame count, so it is also where the drift correction is applied — the caller rebuilds
//! the resampler with the source rate multiplied by the drift ratio (see [`super::drift`]).
//!
//! Linear rather than windowed-sinc on purpose: the target is 16 kHz speech at 32 kbps, where the
//! AAC encoder's own low-pass sits well below the images linear interpolation leaves behind, and a
//! polyphase filter would be a dependency and a block delay for something nobody can hear.

/// A resampler that keeps its phase across buffers, so a callback boundary costs no frames.
pub struct Resampler {
    /// Frames of input per frame of output.
    step: f64,
    /// Where the next output frame sits in the *virtual* buffer — index 0 is [`prev`], index `n` is
    /// input frame `n - 1`. Kept in `[0, step)` between calls, which is what makes the phase
    /// continuous.
    pos: f64,
    /// The last frame of the previous buffer, so interpolation across the seam has both ends.
    prev: f32,
}

impl Resampler {
    pub fn new(source_rate: f64, target_rate: f64) -> Self {
        debug_assert!(source_rate > 0.0 && target_rate > 0.0);
        Self {
            step: source_rate / target_rate,
            // Not zero: the first output frame is input frame 0 rather than the zero that sits in
            // front of it, so a resampler adds no silence of its own.
            pos: 1.0,
            prev: 0.0,
        }
    }

    /// Appends the resampled form of [input] to [out]. Nothing is buffered but one frame, so the
    /// output length is the input's, scaled, to within a frame.
    pub fn push(&mut self, input: &[f32], out: &mut Vec<f32>) {
        if input.is_empty() {
            return;
        }
        let virtual_len = (input.len() + 1) as f64;
        while self.pos + 1.0 < virtual_len {
            let index = self.pos.floor();
            let fraction = (self.pos - index) as f32;
            let index = index as usize;
            let left = self.at(input, index);
            let right = self.at(input, index + 1);
            out.push(left + (right - left) * fraction);
            self.pos += self.step;
        }
        // The next call's virtual index 0 is this buffer's last frame, which is where the shift
        // leaves `pos`.
        self.pos -= input.len() as f64;
        self.prev = input[input.len() - 1];
    }

    fn at(&self, input: &[f32], index: usize) -> f32 {
        if index == 0 {
            self.prev
        } else {
            input[index - 1]
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Deliverable 6: frame counts. A second of 48 kHz is a second of 16 kHz, and the phase carried
    /// across buffers is what keeps the second second the same length as the first.
    #[test]
    fn a_second_of_input_is_a_second_of_output() {
        for (source, target) in [(48_000.0, 16_000.0), (44_100.0, 16_000.0), (16_000.0, 16_000.0)] {
            let mut resampler = Resampler::new(source, target);
            let input = vec![0.0f32; source as usize / 100];
            let mut out = Vec::new();
            for _ in 0..100 {
                resampler.push(&input, &mut out);
            }
            let expected = target as usize;
            assert!(
                out.len().abs_diff(expected) <= 1,
                "{source} -> {target}: {} frames, expected {expected}",
                out.len(),
            );
        }
    }

    /// An hour of 100 ms buffers, which is what the frame counts the drift estimate reads are made
    /// of: no accumulating error, one frame of slack for the open phase.
    #[test]
    fn an_hour_does_not_accumulate_frames() {
        let mut resampler = Resampler::new(48_000.0, 16_000.0);
        let input = vec![0.0f32; 4_800];
        let mut out = Vec::new();
        for _ in 0..36_000 {
            resampler.push(&input, &mut out);
        }
        assert!(out.len().abs_diff(16_000 * 3_600) <= 1, "{} frames", out.len());
    }

    /// A ramp resampled 2:1 is the same ramp at half the frames — the interpolation is checked
    /// against arithmetic rather than against itself.
    #[test]
    fn interpolation_lands_between_the_frames_it_came_from() {
        let mut resampler = Resampler::new(2.0, 1.0);
        let input: Vec<f32> = (0..8).map(|n| n as f32).collect();
        let mut out = Vec::new();
        resampler.push(&input, &mut out);
        assert_eq!(vec![0.0, 2.0, 4.0, 6.0], out);
    }
}
