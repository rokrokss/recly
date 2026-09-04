//! The `mix` track: the file the user's own AI is meant to eat, where the separate tracks are for
//! speaker separation (docs/12 "합산 −6 dB 헤드룸", the same arithmetic as
//! `SegmentedRecorder.mix` on macOS).

/// Appends `(mic + sys) × 0.5` to [out]. The two slices are the same length by construction — the
/// system frames were taken to match the microphone buffer that is being written.
pub fn mix(mic: &[f32], sys: &[f32], out: &mut Vec<f32>) {
    debug_assert_eq!(mic.len(), sys.len());
    out.extend(
        mic.iter()
            .zip(sys.iter())
            .map(|(mic, sys)| (mic + sys) * 0.5),
    );
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Deliverable 6: headroom. Two full-scale tracks summed at half scale stay inside the range an
    /// encoder can take — the half is what buys the 6 dB, and clipping is what its absence sounds
    /// like.
    #[test]
    fn two_full_scale_tracks_do_not_clip() {
        let mic = vec![1.0f32, -1.0, 1.0, 0.5];
        let sys = vec![1.0f32, -1.0, -1.0, 0.5];
        let mut out = Vec::new();
        mix(&mic, &sys, &mut out);
        assert_eq!(vec![1.0, -1.0, 0.0, 0.5], out);
        assert!(out.iter().all(|sample| sample.abs() <= 1.0));
    }

    /// Silence on one side leaves the other at half scale, which is the point: the mix is not a
    /// louder copy of whichever track happens to be talking.
    #[test]
    fn one_silent_track_halves_the_other() {
        let mut out = Vec::new();
        mix(&[0.8, -0.4], &[0.0, 0.0], &mut out);
        assert_eq!(vec![0.4, -0.2], out);
    }
}
