//! The system stream put on the microphone's timeline — the Windows half of docs/12 "캡처
//! 파이프라인", ported frame for frame from `apple/RecKit/Sources/RecKit/MacCapture/DriftCompensator.swift`.
//!
//! The microphone is the clock. It has to be: the recording's length, its segment boundaries and
//! its part offsets are all counted in microphone frames, and a second stream that decided any of
//! those for itself would answer differently within the hour. So every microphone buffer that
//! reaches a file takes exactly as many system frames with it, and this is what makes sure those
//! frames are the right ones.

use super::resample::Resampler;
use super::SAMPLE_RATE_HZ;

/// The rate estimate, as arithmetic: no audio, no resampler, no device. Both streams say how many
/// frames they have produced *on the 16 kHz timeline*, the monotonic clock says how long that took,
/// and the ratio between the two rates is the drift (docs/12 "누적 프레임 수 vs 벽시계").
///
/// It is a separate type from [`DriftCompensator`] so the lane's claim — an hour of a synthetic
/// rate difference ends under 20 ms apart — can be checked as a calculation rather than as a
/// recording.
pub struct DriftEstimator {
    interval_sec: f64,
    since_sec: f64,
    mic_at_interval: f64,
    sys_at_interval: f64,
    ratio: f64,
    /// The corrections lately accepted, each with the ratio that was in force before it. An outage
    /// is only ever known about once it has *ended*, and one that straddles an observation — three
    /// hundred milliseconds out of a minute is half a percent, well inside the band — has already
    /// moved the ratio by then. Two is enough: an outage long enough to reach a third observation
    /// is one whose intervals the band refuses on their own.
    accepted: Vec<(f64, f64)>,
}

/// docs/12: re-estimated every 60 seconds. Shorter and the estimate is jitter; longer and an hour
/// is only a handful of corrections.
pub const INTERVAL_SEC: f64 = 60.0;

/// A real clock pair is within a hundred parts per million of each other (36 ms an hour). A whole
/// percent is not a clock difference, it is a stream that stalled — and a ratio built from it would
/// resample the system track into noise for the rest of the recording.
///
/// Two jobs, and the first is the one that matters: it is the band a new estimate has to fall in to
/// be believed at all, and only then the ceiling the compounded ratio is held to.
pub const MAX_DEVIATION: f64 = 0.01;

const ACCEPTED_KEPT: usize = 2;

impl DriftEstimator {
    pub fn new(interval_sec: f64, start_sec: f64) -> Self {
        Self {
            interval_sec,
            since_sec: start_sec,
            mic_at_interval: 0.0,
            sys_at_interval: 0.0,
            ratio: 1.0,
            accepted: Vec::new(),
        }
    }

    /// What the system stream's frame count has to be multiplied by to land on the microphone's
    /// timeline. Applied to the resampler as a source rate, so 1 is "no correction yet".
    pub fn ratio(&self) -> f64 {
        self.ratio
    }

    /// Cumulative output frames of both streams at [at_sec] on a monotonic clock: the microphone's
    /// own count, and what the system stream's resampler has been fed. True when an interval closed
    /// and the ratio moved, which is the caller's cue to rebuild that resampler.
    ///
    /// Rates, and only rates. Folding the queue's current depth in — so the correction fixed where
    /// the two streams *are* and not only how fast they are going — was tried on macOS and taken
    /// out: it chases the resampler's phase instead of the drift. Frames per interval average that
    /// away.
    pub fn observe(&mut self, mic_frames: f64, sys_frames: f64, at_sec: f64) -> bool {
        if at_sec - self.since_sec < self.interval_sec {
            return false;
        }
        let elapsed = at_sec - self.since_sec;
        let mic = mic_frames - self.mic_at_interval;
        let sys = sys_frames - self.sys_at_interval;
        self.since_sec = at_sec;
        self.mic_at_interval = mic_frames;
        self.sys_at_interval = sys_frames;

        // Half an interval's worth of frames from each, or one of them was not really running — a
        // paused microphone during a device change, a loopback stream being re-created. There is no
        // rate to measure across that, and the last good ratio is a better guess than a made-up one.
        if mic <= 0.0 || sys <= 0.0 || mic.min(sys) <= elapsed * f64::from(SAMPLE_RATE_HZ) / 2.0 {
            return false;
        }
        // The system frames were produced with the *current* ratio already applied, so the
        // correction compounds onto it rather than replacing it.
        let raw = self.ratio * sys / mic;
        // A minute of two clocks is parts per million apart. A whole percent away from the ratio in
        // force is not a clock, it is an interval part of whose system audio never arrived — a
        // stream that was away for ten of the sixty seconds reads as running a sixth slow. Folded
        // in, even clamped, that would resample the *next* minute by a percent (600 ms of system
        // audio dropped or filled with silence) to correct frames that were never late, only
        // missing. So it is refused; the interval has already been re-anchored above, and the next
        // one measures two streams that were both running.
        if (raw - self.ratio).abs() > MAX_DEVIATION {
            return false;
        }
        let corrected = raw.clamp(1.0 - MAX_DEVIATION, 1.0 + MAX_DEVIATION);
        if corrected == self.ratio {
            return false;
        }
        self.accepted.push((at_sec, self.ratio));
        if self.accepted.len() > ACCEPTED_KEPT {
            self.accepted.remove(0);
        }
        self.ratio = corrected;
        true
    }

    /// Starts the interval again from here, with no estimate taken: the frames of an interval the
    /// loopback stream was re-created inside are missing rather than slow, and the rate across them
    /// is a fiction (see the refusal in [`Self::observe`]).
    ///
    /// [outage_sec] is how long the stream was away for, which is what says whether an interval that
    /// already closed measured part of the hole. Anything accepted since the audio stopped is put
    /// back — re-anchoring alone would leave the wrong ratio running for the whole next minute, and
    /// a ratio half a percent out drops or invents about as much audio again as the outage did.
    pub fn reanchor(&mut self, mic_frames: f64, sys_frames: f64, at_sec: f64, outage_sec: f64) {
        if let Some(&(_, before)) = self.accepted.iter().find(|(at, _)| *at >= at_sec - outage_sec) {
            self.ratio = before;
        }
        self.accepted.clear();
        self.since_sec = at_sec;
        self.mic_at_interval = mic_frames;
        self.sys_at_interval = sys_frames;
    }
}

/// One resampler block's worth of lead, in milliseconds of the target timeline.
///
/// macOS primes its queue with `AVAudioConverter`'s 4096-frame block because that converter emits
/// nothing at all until it has one. A linear resampler has no such block, so what this covers is
/// the other reason the queue would run dry: WASAPI hands loopback audio over in packets whose
/// arrival is scheduled against the render device's period, and a microphone buffer that asks a
/// moment before a packet lands would otherwise punch a silence gap into the system track. One
/// tenth of a second turns that into a *constant* lag instead of a repeating hole.
///
/// What it does not correct is that constant offset itself, plus the two devices' hardware
/// latencies. Removing it needs both streams' device positions and is not in this lane; the drift is.
const LEAD_MS: f64 = 100.0;

/// The queue's own ceiling, past the lead, for when nothing is drawing it down. [`DriftCompensator::take`]
/// trims to a microphone buffer's worth — but only when there *is* a take, and a microphone that
/// never comes back would otherwise leave this growing for the rest of the recording.
const MAX_QUEUED_SEC: f64 = 1.0;

/// The system stream, resampled onto the microphone's timeline with the drift correction folded
/// into the resample ratio, and held in a queue the recorder draws from a microphone buffer at a
/// time.
pub struct DriftCompensator {
    estimator: DriftEstimator,
    resampler: Option<Resampler>,
    /// The rate the resampler was built for: the endpoint's own rate, and the ratio that was applied
    /// to it. A re-created stream can arrive on a different one.
    source_rate: f64,
    applied_ratio: f64,
    queue: std::collections::VecDeque<f32>,
    /// What the endpoint has delivered, on the target's timeline — counted from the input, see
    /// [`Self::append`].
    produced_frames: f64,
    lead_frames: usize,
    pub underrun_frames: u64,
    pub dropped_frames: u64,
}

impl DriftCompensator {
    pub fn new(interval_sec: f64) -> Self {
        // Primed here rather than on the first buffer: the lead does not depend on the endpoint's
        // rate, and the microphone is running before the render endpoint has delivered anything.
        // Waiting would spend the lead covering that first gap and leave the system track a second
        // lead behind for the rest of the recording.
        let lead_frames = (LEAD_MS * f64::from(SAMPLE_RATE_HZ) / 1000.0) as usize;
        Self {
            estimator: DriftEstimator::new(interval_sec, 0.0),
            resampler: None,
            source_rate: 0.0,
            applied_ratio: 1.0,
            queue: std::iter::repeat_n(0.0, lead_frames).collect(),
            produced_frames: 0.0,
            lead_frames,
            underrun_frames: 0,
            dropped_frames: 0,
        }
    }

    pub fn ratio(&self) -> f64 {
        self.estimator.ratio()
    }

    /// One endpoint buffer at [source_rate], resampled onto the microphone's timeline and queued.
    pub fn append(&mut self, samples: &[f32], source_rate: f64) {
        if samples.is_empty() {
            return;
        }
        // A ratio that moved, or a stream that came back on a different device, is a new resampler.
        if self.resampler.is_none()
            || source_rate != self.source_rate
            || self.estimator.ratio() != self.applied_ratio
        {
            self.rebuild(source_rate, self.estimator.ratio());
        }
        // Counted here, from what the endpoint delivered, and not from what the resampler emitted:
        // the input frames carry no resampler phase, and this is exactly what the device produced,
        // on the microphone's timeline, at the rate in force.
        //
        // "At the rate in force" is the whole correction: the divisor is the resampler's *input*
        // rate, which already has the ratio folded into it. That is what makes [`DriftEstimator`]'s
        // compounding converge — an endpoint running `k` times fast reads as `k` however many
        // corrections have already been applied, instead of multiplying by `k` again every minute.
        self.produced_frames +=
            samples.len() as f64 * f64::from(SAMPLE_RATE_HZ) / (self.source_rate * self.applied_ratio);
        let mut out = Vec::with_capacity(samples.len());
        if let Some(resampler) = self.resampler.as_mut() {
            resampler.push(samples, &mut out);
        }
        self.queue.extend(out);
        self.drop_down_to(self.lead_frames + (f64::from(SAMPLE_RATE_HZ) * MAX_QUEUED_SEC) as usize);
    }

    /// [frames] of system audio to sit under the microphone frames the recorder is about to write.
    /// Short of them, the rest is silence.
    pub fn take(&mut self, frames: usize, out: &mut Vec<f32>) {
        // The lead, plus one microphone buffer for the two streams' latencies and their scheduling
        // jitter. Anything past that is audio that would be written that much late for the rest of
        // the recording, so it goes.
        self.drop_down_to(self.lead_frames + frames);
        let taken = frames.min(self.queue.len());
        out.extend(self.queue.drain(..taken));
        out.resize(out.len() + frames - taken, 0.0);
        self.underrun_frames += (frames - taken) as u64;
    }

    /// The microphone's own progress — the other half of the estimate, and the only thing here that
    /// knows what time it is on the recording's clock.
    pub fn observe_mic(&mut self, mic_frames: f64, at_sec: f64) {
        self.estimator.observe(mic_frames, self.produced_frames, at_sec);
    }

    /// The loopback stream was away for [outage_sec] and is back (docs/12 "tap 재생성"). The frames
    /// it did not deliver are missing, not slow.
    pub fn reanchor(&mut self, mic_frames: f64, at_sec: f64, outage_sec: f64) {
        self.estimator
            .reanchor(mic_frames, self.produced_frames, at_sec, outage_sec);
    }

    /// The correction, applied where a resampler can take it: the resampler is told the stream
    /// arrives at `rate × ratio`, so a system clock running fast produces proportionally fewer
    /// frames and lands back on the microphone's count.
    fn rebuild(&mut self, source_rate: f64, ratio: f64) {
        self.source_rate = source_rate;
        self.applied_ratio = ratio;
        self.resampler = Some(Resampler::new(
            source_rate * ratio,
            f64::from(SAMPLE_RATE_HZ),
        ));
    }

    /// The oldest frames past [cap], counted as dropped. [`Self::produced_frames`] is deliberately
    /// left alone: it is what the endpoint delivered, which is the rate the estimate is about, and a
    /// queue nobody emptied says nothing about how fast the two clocks are running.
    fn drop_down_to(&mut self, cap: usize) {
        if self.queue.len() <= cap {
            return;
        }
        let excess = self.queue.len() - cap;
        self.queue.drain(..excess);
        self.dropped_frames += excess as u64;
    }
}
