//! docs/14 "캡처 · 시스템": the default render endpoint captured with
//! `AUDCLNT_STREAMFLAGS_LOOPBACK`, polled rather than event-driven because a silent endpoint signals
//! no event.
//!
//! The silence a loopback stream does not deliver, and when a quiet moment becomes an outage, is
//! [`super::silence::SilenceFill`] — kept out of here so its arithmetic can be tested on a host with
//! no render endpoint. This thread is the Windows half: poll, read what is there, and hand what is
//! not to the fill.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::Sender;
use std::sync::Arc;
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use super::silence::SilenceFill;
use super::wasapi::{render_flow, report, Apartment, Stream, HNS_PER_SEC};
use super::{Buffer, Delivery};

/// The poll period. Short enough that a gap is filled before the recorder asks for it, long enough
/// not to be a spin.
const POLL_MS: u64 = 10;

/// How long the endpoint may deliver nothing before the silence is filled in. Two poll periods plus
/// slack: a busy endpoint's packets are not evenly spaced, and filling between two real packets
/// would push the track ahead of itself.
const IDLE_MS: u128 = 40;

/// A gap this long is a device change or a stalled endpoint rather than a quiet moment, and the
/// frames across it are missing rather than slow. See [`SilenceFill`].
const OUTAGE_SEC: f64 = 1.0;

/// A quarter-second ring for a polled stream — no event means no engine period to inherit.
const BUFFER_HNS: i64 = HNS_PER_SEC / 4;

pub fn spawn(sender: Sender<Delivery>, stopping: Arc<AtomicBool>) -> JoinHandle<()> {
    std::thread::spawn(move || {
        let _apartment = match Apartment::enter() {
            Ok(apartment) => apartment,
            Err(error) => return report(&sender, error),
        };
        let stream = match Stream::open(render_flow(), true, BUFFER_HNS) {
            Ok(stream) => stream,
            Err(error) => return report(&sender, error),
        };
        if let Err(error) = stream.start() {
            return report(&sender, error);
        }
        eprintln!("recly-capture-helper: render endpoint {}", stream.describe());
        let rate = stream.rate;
        let started = Instant::now();
        let mut fill = SilenceFill::new(rate, OUTAGE_SEC);
        let mut last_packet = Instant::now();
        let mut samples = Vec::new();

        while !stopping.load(Ordering::Relaxed) {
            std::thread::sleep(Duration::from_millis(POLL_MS));
            samples.clear();
            let frames = match stream.drain(&mut samples) {
                Ok(frames) => frames,
                Err(error) => return report(&sender, error),
            };
            let mut outage = None;
            if frames > 0 {
                last_packet = Instant::now();
                fill.delivered(frames);
            } else if last_packet.elapsed().as_millis() >= IDLE_MS {
                let filled = fill.fill(started.elapsed().as_secs_f64());
                samples.resize(filled.frames, 0.0);
                outage = filled.outage_sec;
            }
            if samples.is_empty() {
                continue;
            }
            let buffer = Buffer {
                samples: std::mem::take(&mut samples),
                rate,
            };
            if sender.send(Delivery::Frames(buffer)).is_err() {
                return;
            }
            // After the silence, so the re-anchored interval starts having counted it.
            if let Some(seconds) = outage {
                if sender.send(Delivery::Outage { seconds }).is_err() {
                    return;
                }
            }
        }
    })
}
