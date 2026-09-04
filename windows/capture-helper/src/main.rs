//! The helper process: docs/14 JSON-line commands on stdin, events on stdout, log lines on stderr.
//!
//! One process serves one recording. `stop` — or the app closing stdin, which is what
//! `HelperClient.close` does — closes the last segment, reports it and exits; the app's rule for a
//! helper that dies before that is its own (docs/14 "헬퍼가 죽으면 앱이 마지막 파트까지를
//! finalize한다"), and this side helps it by never announcing a part it has not finished writing.

use std::io::{BufRead, Write};
use std::path::PathBuf;
use std::sync::mpsc::{channel, Receiver, TryRecvError};
use std::time::{Duration, Instant};

use recly_capture_helper::capture::{self, Buffer, Capture, Delivery, Source};
use recly_capture_helper::detect::{MicWatcher, POLL_SEC};
use recly_capture_helper::encode::{self, Kind};
use recly_capture_helper::pipeline::drift::{DriftCompensator, INTERVAL_SEC};
use recly_capture_helper::pipeline::resample::Resampler;
use recly_capture_helper::pipeline::SAMPLE_RATE_HZ;
use recly_capture_helper::protocol::{Command, Event, Track};
use recly_capture_helper::recorder::{Recorder, Written};
use recly_capture_helper::selftest;

/// How long the loop may block on the microphone before it looks at the commands again.
const PUMP_MS: u64 = 50;

fn main() {
    let options = match Options::parse(std::env::args().skip(1)) {
        Ok(options) => options,
        Err(message) => {
            eprintln!("recly-capture-helper: {message}");
            std::process::exit(2);
        }
    };
    if options.version {
        // M6-L3 deliverable 3: the app runs this once at launch to check that the helper the MSI
        // installed is there, runs, and is the one it expects.
        println!("recly-capture-helper {}", env!("CARGO_PKG_VERSION"));
        let _ = std::io::stdout().flush();
        return;
    }
    if options.self_test {
        print!("{}", selftest::run());
        let _ = std::io::stdout().flush();
        return;
    }
    // The microphone-in-use poll runs on this thread, and `IAudioSessionManager2` is a COM object.
    #[cfg(windows)]
    let _apartment = capture::wasapi::Apartment::enter();

    std::process::exit(run(options));
}

/// Development knobs and the encoder choice. Everything here has a default that is what a user's
/// machine runs; the rest exists so `HelperClientTest` can drive this binary on a host with no
/// audio device (`windows/capture-helper/README.md`).
struct Options {
    source: Source,
    encoder: Kind,
    ffmpeg: String,
    self_test: bool,
    /// Print the version and exit — the app's path/version check (M6-L3 deliverable 3).
    version: bool,
    /// Overrides the `segmentSec` of the `start` command, and takes a fraction of a second — a test
    /// cannot wait fifteen minutes for a boundary.
    segment_sec: Option<f64>,
    /// Stop capturing after this many parts, leaving the segment that was open unannounced.
    parts: Option<u32>,
    /// Exit without answering the `stop`, which is the helper-death path from the app's side.
    die: bool,
    /// Print one line that is not protocol, to prove it does not end a recording.
    noise: bool,
    /// Never answer the `stop` and never exit: the app has to kill this.
    hang: bool,
    /// Stand in for a capture session, so `detect on` has something to report.
    mic_in_use: Option<String>,
}

impl Options {
    fn parse(args: impl Iterator<Item = String>) -> Result<Self, String> {
        let mut options = Self {
            source: if cfg!(windows) { Source::Real } else { Source::Fake },
            encoder: Kind::default(),
            ffmpeg: "ffmpeg".into(),
            self_test: false,
            version: false,
            segment_sec: None,
            parts: None,
            die: false,
            noise: false,
            hang: false,
            mic_in_use: None,
        };
        let mut args = args.peekable();
        while let Some(argument) = args.next() {
            let mut value = || {
                args.next()
                    .ok_or_else(|| format!("{argument} needs a value"))
            };
            match argument.as_str() {
                "--self-test" => options.self_test = true,
                "--version" => options.version = true,
                "--fake-source" => {
                    // The shape `sine` is the only one there is; it is named so the flag reads as a
                    // choice rather than a switch, as the lane writes it.
                    match value()?.as_str() {
                        "sine" => options.source = Source::Fake,
                        other => return Err(format!("unknown fake source {other}")),
                    }
                }
                "--encoder" => {
                    let name = value()?;
                    options.encoder =
                        Kind::parse(&name).ok_or_else(|| format!("unknown encoder {name}"))?;
                }
                "--ffmpeg" => options.ffmpeg = value()?,
                "--segment-sec" => {
                    options.segment_sec = Some(
                        value()?
                            .parse()
                            .map_err(|_| "--segment-sec needs a number".to_string())?,
                    )
                }
                "--parts" => {
                    options.parts = Some(
                        value()?
                            .parse()
                            .map_err(|_| "--parts needs a number".to_string())?,
                    )
                }
                "--die" => options.die = true,
                "--noise" => options.noise = true,
                "--hang" => options.hang = true,
                "--mic-in-use" => options.mic_in_use = Some(value()?),
                other => return Err(format!("unknown argument {other}")),
            }
        }
        Ok(options)
    }
}

fn run(options: Options) -> i32 {
    let commands = spawn_reader();
    let mut watcher = MicWatcher::new(options.mic_in_use.clone());
    let mut detecting: Option<Instant> = None;
    let mut session: Option<Session> = None;

    loop {
        // Commands first: a `stop` that arrived while the last buffer was being written is the end
        // of the recording, not the start of another buffer.
        loop {
            match commands.try_recv() {
                Ok(Some(command)) => match command {
                    Command::Start { dir, base, segment_sec, tracks } => {
                        if options.noise {
                            // Not protocol. `HelperClient` logs and skips it (docs/14).
                            println!("recly-capture-helper: starting {base}");
                            let _ = std::io::stdout().flush();
                        }
                        let seconds = options.segment_sec.unwrap_or(f64::from(segment_sec));
                        match Session::open(&options, PathBuf::from(dir), &base, seconds, &tracks) {
                            Ok(started) => session = Some(started),
                            Err(error) => {
                                emit(&Event::failed(error, true));
                                return 1;
                            }
                        }
                    }
                    Command::Stop => {
                        if options.hang {
                            // stdout stays open, so the app never sees the end of the stream.
                            std::thread::sleep(Duration::from_secs(60));
                        }
                        return finish(session);
                    }
                    Command::Detect { on } => {
                        detecting = on.then(Instant::now);
                        if !on {
                            continue;
                        }
                        if let Some(event) = watcher.poll() {
                            emit(&event);
                        }
                    }
                },
                // A line that is not a command. Logged, never fatal — the app's own reader has the
                // same rule for lines that are not events.
                Ok(None) => {}
                Err(TryRecvError::Empty) => break,
                // The app closed stdin, which is `HelperClient.close`: the same end as a `stop`.
                Err(TryRecvError::Disconnected) => return finish(session),
            }
        }

        if let Some(since) = detecting {
            if since.elapsed() >= Duration::from_secs(POLL_SEC) {
                detecting = Some(Instant::now());
                if let Some(event) = watcher.poll() {
                    emit(&event);
                }
            }
        }

        match session.as_mut() {
            Some(running) => {
                let (more, written) = running.pump(options.parts);
                let failed = written.failure.is_some();
                let code = report(written);
                if failed {
                    return code;
                }
                // A `--parts` limit was reached. `--die` is the app's helper-death path.
                if !more && options.die {
                    let _ = std::io::stdout().flush();
                    return 9;
                }
            }
            // Nothing to record yet: `detect on` alone is a legitimate state (docs/14 "감지").
            None => std::thread::sleep(Duration::from_millis(PUMP_MS)),
        }
    }
}

/// The stop path: the last segment, then out. Exiting is what closes stdout, which is the signal the
/// app is waiting on.
fn finish(session: Option<Session>) -> i32 {
    let Some(session) = session else {
        return 0;
    };
    report(session.finish())
}

/// Everything a write produced, in the order the app has to see it: the parts that closed first —
/// they are whole files on disk and the app files them — and only then the failure that stopped the
/// recording. Returns the exit code.
fn report(written: Written) -> i32 {
    // The strip first, in one line for the whole pump: it is what the app is drawing right now, and
    // a `part_done` behind it is a file the user is not looking at (docs/09 화면 원칙 6).
    if !written.levels.is_empty() {
        emit(&Event::level(&written.levels));
    }
    for event in &written.parts {
        emit(event);
    }
    match written.failure {
        Some(error) => {
            emit(&Event::failed(error, true));
            1
        }
        None => 0,
    }
}

fn emit(event: &Event) {
    let line = serde_json::to_string(event).expect("an event is always serialisable");
    println!("{line}");
    let _ = std::io::stdout().flush();
}

/// stdin on a thread of its own, because the main loop is busy waiting for audio. `None` is a line
/// that did not parse.
fn spawn_reader() -> Receiver<Option<Command>> {
    let (sender, receiver) = channel();
    std::thread::spawn(move || {
        for line in std::io::stdin().lock().lines() {
            let Ok(line) = line else { return };
            if line.trim().is_empty() {
                continue;
            }
            let parsed = serde_json::from_str::<Command>(&line)
                .map_err(|error| eprintln!("recly-capture-helper: unparsed command: {error}"))
                .ok();
            if sender.send(parsed).is_err() {
                return;
            }
        }
    });
    receiver
}

/// One recording in flight: the capture threads, the two streams put on one timeline, and the
/// segment writer they feed.
struct Session {
    recorder: Option<Recorder>,
    capture: Option<Capture>,
    compensator: DriftCompensator,
    /// Built on the first buffer, when the endpoint's rate is known, and rebuilt if it changes.
    mic_resampler: Option<(f64, Resampler)>,
    started: Instant,
    /// Whether any track is written from the render endpoint (docs/03 `sys`/`mix`). A `mono` or
    /// `mic` recording never opens it, and must not be told it underran for want of it.
    system: bool,
    /// Microphone frames on the 16 kHz timeline — the recording's clock (docs/12).
    mic_frames: u64,
    parts_done: u32,
    mic: Vec<f32>,
    sys: Vec<f32>,
}

impl Session {
    fn open(
        options: &Options,
        dir: PathBuf,
        base: &str,
        segment_sec: f64,
        tracks: &[Track],
    ) -> std::io::Result<Self> {
        let system = tracks.iter().any(|track| track.needs_system_audio());
        let factory = encode::factory(options.encoder, options.ffmpeg.clone());
        eprintln!(
            "recly-capture-helper: rec.start base={base} tracks={} segmentSec={segment_sec} encoder={} source={:?}",
            tracks.len(),
            factory.name(),
            options.source,
        );
        let recorder = Recorder::open(&dir, base, segment_sec, tracks, factory)?;
        let capture = capture::start(options.source, system)?;
        Ok(Self {
            recorder: Some(recorder),
            capture: Some(capture),
            compensator: DriftCompensator::new(INTERVAL_SEC),
            mic_resampler: None,
            started: Instant::now(),
            system,
            mic_frames: 0,
            parts_done: 0,
            mic: Vec::new(),
            sys: Vec::new(),
        })
    }

    /// One microphone buffer's worth of work: whether to keep going (false once a `--parts` limit
    /// has ended the capture), and what was written.
    fn pump(&mut self, parts_limit: Option<u32>) -> (bool, Written) {
        if self.capture.is_none() {
            std::thread::sleep(Duration::from_millis(PUMP_MS));
            return (false, Written::default());
        }
        // The system stream first: the frames the microphone buffer is about to take are the ones
        // that have already arrived.
        self.drain_system();

        let capture = self.capture.as_ref().expect("checked above");
        let buffer = match capture.mic.recv_timeout(Duration::from_millis(PUMP_MS)) {
            Ok(Delivery::Frames(buffer)) => buffer,
            Ok(Delivery::Outage { .. }) => return (true, Written::default()),
            Ok(Delivery::Failed(error)) => {
                return (
                    false,
                    Written {
                        failure: Some(error),
                        ..Written::default()
                    },
                )
            }
            Err(_) => return (true, Written::default()),
        };

        let written = self.write_buffer(buffer);
        if written.failure.is_some() {
            return (false, written);
        }
        if parts_limit.is_some_and(|limit| self.parts_done >= limit) {
            self.stop_capture();
            // The segment that was open is not announced, and so is not a part: the app files what
            // it was told about and nothing else.
            if let Some(recorder) = self.recorder.take() {
                recorder.discard();
            }
            return (false, written);
        }
        (true, written)
    }

    /// Everything the render endpoint has delivered since the last look, onto the microphone's
    /// timeline.
    fn drain_system(&mut self) {
        let Some(capture) = self.capture.as_ref() else {
            return;
        };
        let Some(sys) = capture.sys.as_ref() else {
            return;
        };
        loop {
            match sys.try_recv() {
                Ok(Delivery::Frames(buffer)) => {
                    self.compensator.append(&buffer.samples, buffer.rate)
                }
                Ok(Delivery::Outage { seconds }) => self.compensator.reanchor(
                    self.mic_frames as f64,
                    self.started.elapsed().as_secs_f64(),
                    seconds,
                ),
                // docs/03 "트랙": the system track is the one that may have holes in it. A
                // recording does not end because the render endpoint went away.
                Ok(Delivery::Failed(error)) => {
                    eprintln!("recly-capture-helper: system audio stopped: {error}");
                    break;
                }
                Err(_) => break,
            }
        }
    }

    /// One microphone buffer, resampled onto the recording's timeline and written to every track
    /// with the system frames that belong under it.
    fn write_buffer(&mut self, buffer: Buffer) -> Written {
        self.mic.clear();
        let resampler = match self.mic_resampler.as_mut() {
            Some((rate, resampler)) if *rate == buffer.rate => resampler,
            _ => {
                self.mic_resampler = Some((
                    buffer.rate,
                    Resampler::new(buffer.rate, f64::from(SAMPLE_RATE_HZ)),
                ));
                &mut self.mic_resampler.as_mut().expect("just set").1
            }
        };
        resampler.push(&buffer.samples, &mut self.mic);
        if self.mic.is_empty() {
            return Written::default();
        }
        self.mic_frames += self.mic.len() as u64;

        self.sys.clear();
        if self.system {
            let at_sec = self.started.elapsed().as_secs_f64();
            self.compensator.observe_mic(self.mic_frames as f64, at_sec);
            self.compensator.take(self.mic.len(), &mut self.sys);
        } else {
            self.sys.resize(self.mic.len(), 0.0);
        }

        let Some(recorder) = self.recorder.as_mut() else {
            return Written::default();
        };
        let written = recorder.push(&self.mic, &self.sys);
        for event in &written.parts {
            if let Event::PartDone { part, .. } = event {
                self.parts_done = self.parts_done.max(*part);
            }
        }
        written
    }

    /// Everything the endpoints left in the channels once they were told to stop. It is the tail of
    /// the recording: the last WASAPI buffers arrive between one poll and the next, and closing the
    /// segment without them would drop audio on every ordinary stop.
    fn drain_queued(&mut self) -> Written {
        let mut written = Written::default();
        loop {
            self.drain_system();
            let Some(capture) = self.capture.as_ref() else {
                return written;
            };
            let delivery = match capture.mic.try_recv() {
                Ok(delivery) => delivery,
                // Empty, or the thread is gone and took its sender with it. Either way there is
                // nothing left to write.
                Err(_) => return written,
            };
            let Delivery::Frames(buffer) = delivery else {
                continue;
            };
            let more = self.write_buffer(buffer);
            written.parts.extend(more.parts);
            if more.failure.is_some() {
                written.failure = more.failure;
                return written;
            }
        }
    }

    /// Stops the endpoints and lets the channels go — for the `--parts` limit, which wants no tail.
    fn stop_capture(&mut self) {
        if let Some(mut capture) = self.capture.take() {
            capture.quiesce();
        }
    }

    /// docs/03: the last part, closed and hashed like every other one.
    fn finish(mut self) -> Written {
        // Silence the endpoints, then write what they had already delivered, and only then close.
        if let Some(capture) = self.capture.as_mut() {
            capture.quiesce();
        }
        let before = self.mic_frames;
        let mut written = self.drain_queued();
        self.capture = None;
        let frames = self.mic_frames;
        // What the endpoints had already delivered when the stop arrived. It is normally a buffer or
        // two, and it is the audio that used to be lost on every stop.
        let drained = frames - before;
        if self.system {
            let underruns = self.compensator.underrun_frames;
            let dropped = self.compensator.dropped_frames;
            let ratio = self.compensator.ratio();
            eprintln!(
                "recly-capture-helper: rec.stop frames={frames} tailDrainedFrames={drained} driftRatio={ratio:.6} sysUnderrunFrames={underruns} sysDroppedFrames={dropped}"
            );
        } else {
            eprintln!("recly-capture-helper: rec.stop frames={frames} tailDrainedFrames={drained}");
        }
        if written.failure.is_some() {
            return written;
        }
        if let Some(recorder) = self.recorder.take() {
            let tail = recorder.finish();
            written.parts.extend(tail.parts);
            written.failure = tail.failure;
        }
        written
    }
}
