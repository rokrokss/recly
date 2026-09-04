//! The loopback stream's silence bookkeeping: how much of the `sys` timeline the helper has to
//! invent, and when a quiet moment has become an outage.
//!
//! **A silent render endpoint produces no packets at all** — not silent ones, nothing, and no event
//! either. Left alone that would leave the `sys` track shorter than the recording by however long
//! nobody was talking, and every later frame of it would sit that much early against the microphone.
//! So the poller fills the gap itself, at the endpoint's own rate, from the monotonic clock
//! (docs/14 "무음 시 콜백이 없으므로 타이머로 무음 프레임 삽입").
//!
//! Two clocks meet here and the difference matters. Filled frames are counted as delivered, exactly
//! as a real silent packet would be: the endpoint really did render that silence, WASAPI just
//! declined to say so. But the fill is measured with the *host* clock, not the render clock, so a
//! **long** fill is not a rate — it is a hole, and a hole read as a rate is what
//! [`crate::pipeline::drift`] refuses intervals for. Past [`Self::outage_sec`] the gap is reported
//! once, for its whole length so far, and the drift estimate re-anchors across it (docs/12
//! "tap 재생성").
//!
//! Pure and clock-free so the arithmetic can be tested on a host with no render endpoint — the
//! caller passes the elapsed seconds in.

/// What one poll of an idle endpoint produced.
#[derive(Debug, PartialEq)]
pub struct Fill {
    /// Frames of silence to hand on as if the endpoint had delivered them.
    pub frames: usize,
    /// Set on the single poll where the current gap crosses [`SilenceFill::outage_sec`], carrying
    /// the whole gap so far. `None` on every other poll, including later ones in the same gap: the
    /// estimator re-anchors once per hole, not once per ten milliseconds.
    pub outage_sec: Option<f64>,
}

pub struct SilenceFill {
    rate: f64,
    outage_sec: f64,
    /// Frames handed on so far, real and filled together — this is the `sys` timeline.
    emitted: u64,
    /// Filled frames since the last real packet.
    gap_frames: u64,
    reported: bool,
}

impl SilenceFill {
    pub fn new(rate: f64, outage_sec: f64) -> Self {
        Self {
            rate,
            outage_sec,
            emitted: 0,
            gap_frames: 0,
            reported: false,
        }
    }

    /// The endpoint delivered real audio: the gap, if there was one, is over.
    pub fn delivered(&mut self, frames: usize) {
        self.emitted += frames as u64;
        self.gap_frames = 0;
        self.reported = false;
    }

    /// The endpoint delivered nothing and has been idle long enough to fill. [elapsed_sec] is the
    /// monotonic clock since capture started; the fill never runs past it, which is what keeps the
    /// invented frames at the endpoint's own rate rather than the poll's.
    pub fn fill(&mut self, elapsed_sec: f64) -> Fill {
        let due = (elapsed_sec * self.rate) as u64;
        let frames = due.saturating_sub(self.emitted);
        if frames == 0 {
            return Fill {
                frames: 0,
                outage_sec: None,
            };
        }
        self.emitted = due;
        self.gap_frames += frames;
        let gap_sec = self.gap_frames as f64 / self.rate;
        let outage_sec = if !self.reported && gap_sec >= self.outage_sec {
            self.reported = true;
            Some(gap_sec)
        } else {
            None
        };
        Fill {
            frames: frames as usize,
            outage_sec,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const RATE: f64 = 48_000.0;
    const POLL_SEC: f64 = 0.01;

    /// The bug this replaced: the gap was measured per poll, so it was always ten milliseconds and
    /// `OUTAGE_SEC` was unreachable — a render endpoint that went away for three seconds fed three
    /// seconds of host-clock frames into the drift estimate and never re-anchored.
    ///
    /// One notification, at the first poll past a second, carrying the gap so far.
    #[test]
    fn a_long_gap_is_reported_once_when_it_crosses_the_threshold() {
        let mut fill = SilenceFill::new(RATE, 1.0);
        let mut outages = Vec::new();
        let mut filled = 0usize;
        for poll in 1..=300 {
            let produced = fill.fill(poll as f64 * POLL_SEC);
            filled += produced.frames;
            if let Some(seconds) = produced.outage_sec {
                outages.push((poll, seconds));
            }
        }
        assert_eq!(1, outages.len(), "one per hole, not one per poll: {outages:?}");
        let (poll, seconds) = outages[0];
        assert_eq!(100, poll, "the first poll at or past a second");
        assert!((seconds - 1.0).abs() < 1e-9, "{seconds}");
        // Three seconds of silence, to the frame, and no more: the fill tracks the clock rather
        // than the poll.
        assert_eq!((3.0 * RATE) as usize, filled);
    }

    /// A quiet moment shorter than the threshold is filled and says nothing — most of a meeting is
    /// made of those, and each one re-anchoring would throw the estimate away every few seconds.
    #[test]
    fn a_short_gap_is_filled_but_not_reported() {
        let mut fill = SilenceFill::new(RATE, 1.0);
        for poll in 1..=50 {
            assert_eq!(None, fill.fill(poll as f64 * POLL_SEC).outage_sec);
        }
    }

    /// A real packet ends the gap, so the next one is reported on its own merits.
    #[test]
    fn a_real_packet_resets_the_gap() {
        let mut fill = SilenceFill::new(RATE, 1.0);
        for poll in 1..=150 {
            fill.fill(poll as f64 * POLL_SEC);
        }
        // The endpoint came back, and its packets carry the timeline forward for a while.
        for poll in 151..=250 {
            fill.delivered((POLL_SEC * RATE) as usize);
            assert_eq!(0, fill.fill(poll as f64 * POLL_SEC).frames, "nothing to invent");
        }
        // And went away again.
        let mut outages = 0;
        for poll in 251..=400 {
            if fill.fill(poll as f64 * POLL_SEC).outage_sec.is_some() {
                outages += 1;
            }
        }
        assert_eq!(1, outages, "the second hole is a hole of its own");
    }

    /// An endpoint delivering faster than the wall clock (a packet burst after a scheduling hiccup)
    /// is never "caught up" with invented frames.
    #[test]
    fn the_fill_never_runs_ahead_of_the_endpoint() {
        let mut fill = SilenceFill::new(RATE, 1.0);
        fill.delivered(RATE as usize);
        assert_eq!(
            Fill { frames: 0, outage_sec: None },
            fill.fill(0.5),
            "half a second in, a second already delivered",
        );
    }
}
