//! Where the audio comes from: WASAPI on Windows, a synthetic pair of tones anywhere else.
//!
//! Both shapes hand the recorder the same thing — mono `f32` buffers at whatever rate the endpoint
//! runs at, over a channel, from a thread of their own — so everything downstream (resample, drift,
//! mix, segment) is the same code on the development host as on a user's machine.

use std::io;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::Receiver;
use std::sync::Arc;
use std::thread::JoinHandle;

pub mod fake;
pub mod format;
pub mod silence;
#[cfg(windows)]
pub mod loopback;
#[cfg(windows)]
pub mod mic;
#[cfg(windows)]
pub mod wasapi;

/// One delivery from an endpoint: mono frames, and the rate they were captured at. The rate rides
/// along rather than being read once at start because a WASAPI stream that is re-created after a
/// device change can come back on a different one.
pub struct Buffer {
    pub samples: Vec<f32>,
    pub rate: f64,
}

/// What a capture thread sends. Everything travels on the one channel so the recorder sees it in
/// the order it happened — a hole and the silence that filled it are the same moment.
///
/// COM objects are opened on the thread that uses them, so a device that cannot be opened at all is
/// discovered inside the thread rather than at `spawn`, which is why the failure is a delivery too.
pub enum Delivery {
    Frames(Buffer),
    /// The endpoint handed over nothing for this long. The frames across it are missing rather than
    /// slow, so the drift estimate re-anchors instead of reading the hole as a rate (docs/12
    /// "tap 재생성"). Sent *after* the silence that filled it, so the re-anchor counts it.
    Outage { seconds: f64 },
    /// The endpoint is gone. docs/14 `error`, and the end of this thread.
    Failed(io::Error),
}

/// `--fake-source`. On a host that is not Windows there is no other option, and `Real` is refused
/// with a message rather than silently faked.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Source {
    Real,
    Fake,
}

/// The running capture threads and the channels they deliver into.
pub struct Capture {
    pub mic: Receiver<Delivery>,
    /// `None` when no track needs system audio — a `mono` or `mic`-only recording does not open the
    /// render endpoint at all.
    pub sys: Option<Receiver<Delivery>>,
    stopping: Arc<AtomicBool>,
    threads: Vec<JoinHandle<()>>,
}

impl Capture {
    fn new(
        mic: Receiver<Delivery>,
        sys: Option<Receiver<Delivery>>,
        stopping: Arc<AtomicBool>,
        threads: Vec<JoinHandle<()>>,
    ) -> Self {
        Self {
            mic,
            sys,
            stopping,
            threads,
        }
    }

    /// Stops the endpoints and waits for their threads, **leaving the channels alone**.
    ///
    /// Quiescing and draining are two steps because the frames already in the channels are the last
    /// frames of the recording. Dropping the receivers here — which is what stopping and consuming
    /// `self` used to do — threw away every packet that had arrived since the previous poll, on
    /// every ordinary stop. So the producers are silenced first and the caller drains what they
    /// left; `try_recv` yields it all and only then reports the disconnection, because the senders
    /// died with the threads.
    pub fn quiesce(&mut self) {
        self.stopping.store(true, Ordering::Relaxed);
        for thread in self.threads.drain(..) {
            let _ = thread.join();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Deliverable 3's tail: what the endpoints delivered before they were stopped is still there to
    /// be written into the last part.
    #[test]
    fn quiesce_stops_the_endpoints_but_keeps_what_they_delivered() {
        let mut capture = fake::start(true).expect("start");
        // The fake source delivers 100 ms at a time and nothing is consuming it.
        std::thread::sleep(std::time::Duration::from_millis(350));

        capture.quiesce();

        let queued = |channel: &Receiver<Delivery>| {
            let mut frames = 0usize;
            while let Ok(Delivery::Frames(buffer)) = channel.try_recv() {
                frames += buffer.samples.len();
            }
            frames
        };
        // At least two chunks of each, and both endpoints, at their own rates.
        assert!(queued(&capture.mic) >= 2 * 4_800, "microphone frames were dropped");
        let sys = capture.sys.as_ref().expect("system audio");
        assert!(queued(sys) >= 2 * 4_410, "system frames were dropped");
        // And nothing arrives after the drain: the producers really did stop.
        std::thread::sleep(std::time::Duration::from_millis(150));
        assert_eq!(0, queued(&capture.mic));
    }
}

/// Starts capture. [system] is whether any track needs the render endpoint (docs/03 `sys`/`mix`).
pub fn start(source: Source, system: bool) -> io::Result<Capture> {
    match source {
        Source::Fake => fake::start(system),
        #[cfg(windows)]
        Source::Real => windows_capture(system),
        #[cfg(not(windows))]
        Source::Real => Err(io::Error::other(
            "this build has no WASAPI capture; run it with --fake-source sine",
        )),
    }
}

#[cfg(windows)]
fn windows_capture(system: bool) -> io::Result<Capture> {
    use std::sync::mpsc::channel;

    let stopping = Arc::new(AtomicBool::new(false));
    let mut threads = Vec::new();

    let (mic_tx, mic_rx) = channel();
    threads.push(mic::spawn(mic_tx, Arc::clone(&stopping)));

    let sys_rx = if system {
        let (sys_tx, sys_rx) = channel();
        threads.push(loopback::spawn(sys_tx, Arc::clone(&stopping)));
        Some(sys_rx)
    } else {
        None
    };

    Ok(Capture::new(mic_rx, sys_rx, stopping, threads))
}
