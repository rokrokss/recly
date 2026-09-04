//! The capture source a machine with no WASAPI has: two tones, delivered in real time at two
//! different rates.
//!
//! It is what `--fake-source sine` selects and the only source a non-Windows build has. Two things
//! about it are load-bearing rather than decorative:
//! * **real time**. The app's `HelperClientTest` drives this binary and asserts the durations and
//!   offsets in `part_done`; a generator that ran as fast as it could would close every segment at
//!   once and prove nothing about the segment clock.
//! * **two rates** (48 kHz and 44.1 kHz). The microphone and the render endpoint on a real machine
//!   rarely agree, so the resampler and the drift compensator are exercised rather than bypassed.

use std::io;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{channel, Sender};
use std::sync::Arc;
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use super::{Buffer, Capture, Delivery};

const MIC_RATE_HZ: f64 = 48_000.0;
const SYS_RATE_HZ: f64 = 44_100.0;
/// Long enough not to be a busy loop, short enough that a one-second segment is not one buffer.
const CHUNK_MS: f64 = 100.0;

pub fn start(system: bool) -> io::Result<Capture> {
    let stopping = Arc::new(AtomicBool::new(false));
    let mut threads = Vec::new();

    let (mic_tx, mic_rx) = channel();
    threads.push(spawn(mic_tx, Arc::clone(&stopping), MIC_RATE_HZ, 440.0, 0.30));

    let sys_rx = if system {
        let (sys_tx, sys_rx) = channel();
        threads.push(spawn(sys_tx, Arc::clone(&stopping), SYS_RATE_HZ, 220.0, 0.20));
        Some(sys_rx)
    } else {
        None
    };

    Ok(Capture::new(mic_rx, sys_rx, stopping, threads))
}

fn spawn(
    sender: Sender<Delivery>,
    stopping: Arc<AtomicBool>,
    rate: f64,
    hz: f64,
    amplitude: f32,
) -> JoinHandle<()> {
    std::thread::spawn(move || {
        let frames_per_chunk = (rate * CHUNK_MS / 1000.0) as u64;
        let started = Instant::now();
        let mut emitted: u64 = 0;
        while !stopping.load(Ordering::Relaxed) {
            let due = Duration::from_secs_f64(emitted as f64 / rate);
            let elapsed = started.elapsed();
            if due > elapsed {
                std::thread::sleep(due - elapsed);
            }
            let samples = (0..frames_per_chunk)
                .map(|frame| {
                    let seconds = (emitted + frame) as f64 / rate;
                    (amplitude as f64 * (std::f64::consts::TAU * hz * seconds).sin()) as f32
                })
                .collect();
            if sender.send(Delivery::Frames(Buffer { samples, rate })).is_err() {
                return;
            }
            emitted += frames_per_chunk;
        }
    })
}
