//! Deliverable 4: the drift correction, as a calculation.
//!
//! docs/12's target is "1시간 후 오프셋 < 20 ms" and docs/14 N3 repeats it for Windows. On macOS that
//! number is checked with a clap test on real hardware; here it is checked the way the lane asks —
//! an hour of a *synthetic* rate difference, run through the same [`DriftCompensator`] the helper
//! uses, on a machine with no audio device at all.
//!
//! What is measured is the audio that went missing or was invented: every frame the compensator
//! dropped because the system stream was running ahead, plus every frame of silence it had to
//! substitute because it was running behind. That is exactly the offset between the two tracks at
//! the end of the hour.

use recly_capture_helper::pipeline::drift::{DriftCompensator, DriftEstimator, INTERVAL_SEC};
use recly_capture_helper::pipeline::SAMPLE_RATE_HZ;

/// The microphone's buffer, and the simulation's time step.
const STEP_MS: f64 = 10.0;
const HOUR_SEC: f64 = 3_600.0;
/// What the render endpoint claims to run at, and what its packets are labelled with.
const NOMINAL_SYS_RATE: f64 = 48_000.0;

/// The offset after [seconds], in milliseconds, of a system endpoint whose true rate is
/// `NOMINAL_SYS_RATE × rate_error`.
fn run(seconds: f64, rate_error: f64, interval_sec: f64) -> f64 {
    let mut compensator = DriftCompensator::new(interval_sec);
    let mic_frames_per_step = (f64::from(SAMPLE_RATE_HZ) * STEP_MS / 1000.0) as usize;
    let steps = (seconds * 1000.0 / STEP_MS) as usize;

    let mut mic_frames: u64 = 0;
    // The endpoint's own frames accumulate fractionally: 200 ppm is not a whole frame a step.
    let mut sys_pending = 0.0f64;
    let mut taken = Vec::new();

    for step in 0..steps {
        let at_sec = step as f64 * STEP_MS / 1000.0;
        sys_pending += NOMINAL_SYS_RATE * rate_error * STEP_MS / 1000.0;
        let sys_frames = sys_pending.floor() as usize;
        sys_pending -= sys_frames as f64;
        // Labelled with the nominal rate, because that is all WASAPI ever says.
        compensator.append(&vec![0.0f32; sys_frames], NOMINAL_SYS_RATE);

        mic_frames += mic_frames_per_step as u64;
        compensator.observe_mic(mic_frames as f64, at_sec);
        taken.clear();
        compensator.take(mic_frames_per_step, &mut taken);
    }

    let lost = compensator.dropped_frames + compensator.underrun_frames;
    lost as f64 * 1000.0 / f64::from(SAMPLE_RATE_HZ)
}

/// The lane's number, over the rate differences a real pair of clocks produces: docs/12 puts two
/// crystals within "a hundred parts per million of each other (36 ms an hour)", and this doubles
/// that for margin.
#[test]
fn an_hour_of_a_drifting_endpoint_ends_under_twenty_milliseconds_apart() {
    for ppm in [-200.0, -100.0, 0.0, 100.0, 200.0] {
        let offset = run(HOUR_SEC, 1.0 + ppm / 1e6, INTERVAL_SEC);
        println!("drift harness: {ppm:+} ppm over 1 h -> {offset:.1} ms offset");
        assert!(offset < 20.0, "{ppm} ppm drifted {offset:.1} ms in an hour");
    }
}

/// The control: the same hour with the estimate switched off (an interval no hour reaches) is where
/// the number would be without deliverable 4. It is what says the harness measures something.
#[test]
fn without_the_correction_the_same_hour_is_hundreds_of_milliseconds_out() {
    let offset = run(HOUR_SEC, 1.0002, 1e9);
    println!("drift harness: +200 ppm over 1 h, uncorrected -> {offset:.1} ms offset");
    assert!(offset > 600.0, "uncorrected drift was only {offset:.1} ms");
}

/// Why 20 ms is a safe claim rather than a lucky one: what is left over is the *first* interval, the
/// minute that ends before there is any estimate to apply (the same thing macOS's `DriftCompensator`
/// says about itself). It does not grow with the recording — two hours of the same endpoint end no
/// further apart than one — so the bound is `interval × rate error`, whatever the length.
#[test]
fn the_residual_is_one_interval_and_does_not_accumulate() {
    let error = 1.0005;
    let one = run(HOUR_SEC, error, INTERVAL_SEC);
    let two = run(HOUR_SEC * 2.0, error, INTERVAL_SEC);
    println!("drift harness: +500 ppm -> {one:.1} ms after 1 h, {two:.1} ms after 2 h");
    assert_eq!(one, two, "the residual is the first interval, not the recording");
    // 500 ppm across a 60-second interval is 30 ms, and that is the whole of it.
    assert!((one - INTERVAL_SEC * 0.0005 * 1000.0).abs() < 1.0, "{one:.1} ms");
}

/// docs/12's rejection band: an interval that is mostly a hole reads as a stream running a sixth
/// slow, and is refused rather than folded in.
#[test]
fn an_interval_with_a_hole_in_it_is_refused() {
    let mut estimator = DriftEstimator::new(INTERVAL_SEC, 0.0);
    let full = f64::from(SAMPLE_RATE_HZ) * INTERVAL_SEC;
    // Ten seconds of the minute never arrived.
    assert!(!estimator.observe(full, full * 50.0 / 60.0, INTERVAL_SEC));
    assert_eq!(1.0, estimator.ratio());
    // A minute both streams ran through is believed.
    assert!(estimator.observe(full * 2.0, full * 50.0 / 60.0 + full * 1.0002, INTERVAL_SEC * 2.0));
    assert!((estimator.ratio() - 1.0002).abs() < 1e-9, "{}", estimator.ratio());
}

/// docs/12 "tap 재생성": an outage that straddles an observation has already moved the ratio by the
/// time anyone knows about it, so the re-anchor puts it back.
#[test]
fn a_reanchor_reverts_a_correction_the_outage_caused() {
    let mut estimator = DriftEstimator::new(INTERVAL_SEC, 0.0);
    let full = f64::from(SAMPLE_RATE_HZ) * INTERVAL_SEC;
    // Half a percent slow — inside the band, so it is accepted, and wrong: it was a 300 ms hole.
    assert!(estimator.observe(full, full * 0.995, INTERVAL_SEC));
    assert!((estimator.ratio() - 0.995).abs() < 1e-9, "{}", estimator.ratio());

    estimator.reanchor(full, full * 0.995, INTERVAL_SEC + 0.1, 0.4);

    assert_eq!(1.0, estimator.ratio(), "the ratio the outage displaced");
}
