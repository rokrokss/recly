//! Where 16 kHz mono float frames become the file docs/03 names.
//!
//! Behind a trait, and deliberately: the encoder is the one thing in this helper that cannot be run
//! on the macOS development host in the shape it ships in, so the segment rules are tested against
//! [`pcm::PcmEncoder`] and the real one is chosen at run time. ADR-019 records which real one and
//! why; `--self-test` is how CI checks that answer on a Windows machine.

use std::io;
use std::path::Path;

pub mod ffmpeg;
#[cfg(windows)]
pub mod mf;
pub mod pcm;

/// One open segment file.
pub trait Encoder {
    /// Appends frames. They are mono, 16 kHz, and in `[-1, 1]`.
    fn write(&mut self, samples: &[f32]) -> io::Result<()>;

    /// Closes the file. Returning is what promises the container is complete — the caller hashes it
    /// the moment this comes back, exactly as the macOS recorder does when it releases an
    /// `AVAudioFile`.
    fn finish(&mut self) -> io::Result<()>;
}

pub trait EncoderFactory {
    fn open(&self, path: &Path) -> io::Result<Box<dyn Encoder>>;
    /// What went in the log line at `start`, so a recording says which encoder wrote it.
    fn name(&self) -> &'static str;
}

/// `--encoder`. The default is the ADR-019 one on Windows and the PCM stand-in everywhere else,
/// because everywhere else is a development host with no AAC encoder this helper can reach.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Ffmpeg,
    #[cfg(windows)]
    MediaFoundation,
    /// Raw little-endian 16-bit PCM under the `.m4a` name the app chose. Development and tests only
    /// — the name is the app's (docs/03 "이름 규칙") and the helper does not get to change it.
    Pcm,
}

impl Kind {
    pub fn parse(value: &str) -> Option<Self> {
        match value {
            "ffmpeg" => Some(Kind::Ffmpeg),
            #[cfg(windows)]
            "mf" => Some(Kind::MediaFoundation),
            "pcm" => Some(Kind::Pcm),
            _ => None,
        }
    }
}

impl Default for Kind {
    fn default() -> Self {
        if cfg!(windows) {
            Kind::Ffmpeg
        } else {
            Kind::Pcm
        }
    }
}

pub fn factory(kind: Kind, ffmpeg_program: String) -> Box<dyn EncoderFactory> {
    match kind {
        Kind::Ffmpeg => Box::new(ffmpeg::FfmpegEncoderFactory::new(ffmpeg_program)),
        #[cfg(windows)]
        Kind::MediaFoundation => Box::new(mf::MfEncoderFactory),
        Kind::Pcm => Box::new(pcm::PcmEncoderFactory),
    }
}

/// The one conversion every encoder here wants: both the Media Foundation AAC encoder and ffmpeg's
/// are fed 16-bit PCM, so the clamp and the scale live in one place.
pub fn to_i16_le(samples: &[f32], out: &mut Vec<u8>) {
    out.clear();
    out.reserve(samples.len() * 2);
    for sample in samples {
        let scaled = (sample.clamp(-1.0, 1.0) * i16::MAX as f32) as i16;
        out.extend_from_slice(&scaled.to_le_bytes());
    }
}
