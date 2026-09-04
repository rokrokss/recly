//! docs/14 "캡처 · 마이크": WASAPI shared-mode, event-driven capture of the default capture
//! endpoint.
//!
//! The microphone is the recording's clock (docs/12), so this thread does the least it can: wait for
//! the event, hand every frame the endpoint produced straight to the recorder, and never invent one.
//! Everything that changes a frame count happens downstream.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::Sender;
use std::sync::Arc;
use std::thread::JoinHandle;

use super::wasapi::{capture_flow, report, Apartment, Stream};
use super::{Buffer, Delivery};

/// How long a wait for the endpoint's event may block before the stop flag is looked at again.
const WAIT_MS: u32 = 200;

pub fn spawn(sender: Sender<Delivery>, stopping: Arc<AtomicBool>) -> JoinHandle<()> {
    std::thread::spawn(move || {
        let _apartment = match Apartment::enter() {
            Ok(apartment) => apartment,
            Err(error) => return report(&sender, error),
        };
        // Zero: a shared-mode event-driven stream is given the engine's own period, which is the
        // whole point of asking for the event.
        let stream = match Stream::open(capture_flow(), false, 0) {
            Ok(stream) => stream,
            Err(error) => return report(&sender, error),
        };
        if let Err(error) = stream.start() {
            return report(&sender, error);
        }
        eprintln!("recly-capture-helper: mic endpoint {}", stream.describe());
        let rate = stream.rate;
        let mut samples = Vec::new();
        while !stopping.load(Ordering::Relaxed) {
            stream.wait(WAIT_MS);
            samples.clear();
            match stream.drain(&mut samples) {
                Ok(0) => continue,
                Ok(_) => {}
                Err(error) => return report(&sender, error),
            }
            let buffer = Buffer {
                samples: std::mem::take(&mut samples),
                rate,
            };
            if sender.send(Delivery::Frames(buffer)).is_err() {
                return;
            }
        }
    })
}
