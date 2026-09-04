//! ADR-019's encoder: a bundled `ffmpeg` given the frames on its stdin and asked for the `.m4a` the
//! app named.
//!
//! One process per open segment, not one per recording. A segment file has to be *complete* the
//! moment the boundary closes — that is what the `sha256` in `part_done` is taken over, and what the
//! app uploads — and the only way to make ffmpeg finish an MP4 container is to close its stdin and
//! let it exit. Three tracks × a boundary every 900 seconds is a process every five minutes, which
//! is nothing next to keeping one alive and never being able to say a part is done.
//!
//! The output is a path rather than a pipe on purpose: the MP4 muxer seeks back to write the `moov`
//! atom, so `-f mp4 pipe:1` would need `+faststart` and a fragmented file. ffmpeg writing the file
//! itself is both simpler and what leaves a readable container behind if the helper is killed
//! mid-segment.

use std::io::{self, Write};
use std::path::Path;
use std::process::{Child, Command, Stdio};

use super::{to_i16_le, Encoder, EncoderFactory};
use crate::pipeline::SAMPLE_RATE_HZ;

/// ADR-006: AAC-LC, 32 kbps.
const BITRATE: &str = "32k";

pub struct FfmpegEncoderFactory {
    program: String,
}

impl FfmpegEncoderFactory {
    pub fn new(program: String) -> Self {
        Self { program }
    }
}

impl EncoderFactory for FfmpegEncoderFactory {
    fn open(&self, path: &Path) -> io::Result<Box<dyn Encoder>> {
        let mut child = Command::new(&self.program)
            .args([
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-f",
                "s16le",
                "-ar",
                &SAMPLE_RATE_HZ.to_string(),
                "-ac",
                "1",
                "-i",
                "pipe:0",
                "-c:a",
                "aac",
                "-b:a",
                BITRATE,
            ])
            .arg(path)
            .stdin(Stdio::piped())
            .stdout(Stdio::null())
            // Its complaints belong in the helper's log, which is this process's stderr (docs/20).
            .stderr(Stdio::inherit())
            .spawn()?;
        let stdin = child.stdin.take().ok_or_else(|| {
            io::Error::new(io::ErrorKind::BrokenPipe, "ffmpeg gave no stdin")
        })?;
        Ok(Box::new(FfmpegEncoder {
            child: Some(child),
            stdin: Some(stdin),
            scratch: Vec::new(),
        }))
    }

    fn name(&self) -> &'static str {
        "ffmpeg"
    }
}

pub struct FfmpegEncoder {
    child: Option<Child>,
    stdin: Option<std::process::ChildStdin>,
    scratch: Vec<u8>,
}

impl Encoder for FfmpegEncoder {
    fn write(&mut self, samples: &[f32]) -> io::Result<()> {
        let Some(stdin) = self.stdin.as_mut() else {
            return Ok(());
        };
        to_i16_le(samples, &mut self.scratch);
        stdin.write_all(&self.scratch)
    }

    fn finish(&mut self) -> io::Result<()> {
        // The close is the cue: ffmpeg sees end of input, writes the trailing atoms and exits.
        drop(self.stdin.take());
        let Some(mut child) = self.child.take() else {
            return Ok(());
        };
        let status = child.wait()?;
        if status.success() {
            Ok(())
        } else {
            Err(io::Error::other(format!("ffmpeg exited with {status}")))
        }
    }
}

impl Drop for FfmpegEncoder {
    /// A segment abandoned rather than closed (a `--parts` limit in development, a start that
    /// failed) must not leave an ffmpeg behind waiting on a pipe nobody writes to.
    fn drop(&mut self) {
        drop(self.stdin.take());
        if let Some(mut child) = self.child.take() {
            let _ = child.wait();
        }
    }
}
