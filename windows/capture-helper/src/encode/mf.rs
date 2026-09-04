//! The Media Foundation half of the docs/14 open item: the AAC encoder Windows ships, driven
//! through the MP4 sink writer.
//!
//! It is written, selectable with `--encoder mf`, and *not* the default — see ADR-019. The
//! Microsoft AAC encoder documents its input as 16-bit PCM at 44.1 or 48 kHz and its output at
//! 96–192 kbps, which is neither of ADR-006's numbers (16 kHz, 32 kbps). [`probe`] is what turns
//! that reading of the documentation into a fact on a real Windows machine: `--self-test` asks for
//! ADR-006's format and for a format the documentation allows, and prints both answers. If the
//! first one ever succeeds, this module is one flag away from being the default and ADR-019 is one
//! revision away from saying so.
//!
//! `MFCreateSinkWriterFromURL` rather than the MFT by hand: the sink writer *is* the AAC MFT plus
//! the MP4 muxer, and driving the MFT directly would mean writing the muxer too.

use std::io;
use std::path::Path;

use windows::core::HSTRING;
use windows::Win32::Media::MediaFoundation::{
    IMFMediaType, IMFSinkWriter, MFAudioFormat_AAC, MFAudioFormat_PCM, MFCreateMediaType,
    MFCreateMemoryBuffer, MFCreateSample, MFCreateSinkWriterFromURL, MFMediaType_Audio, MFStartup,
    MF_MT_AAC_AUDIO_PROFILE_LEVEL_INDICATION, MF_MT_AAC_PAYLOAD_TYPE,
    MF_MT_ALL_SAMPLES_INDEPENDENT, MF_MT_AUDIO_AVG_BYTES_PER_SECOND, MF_MT_AUDIO_BITS_PER_SAMPLE,
    MF_MT_AUDIO_BLOCK_ALIGNMENT, MF_MT_AUDIO_NUM_CHANNELS, MF_MT_AUDIO_SAMPLES_PER_SECOND,
    MF_MT_MAJOR_TYPE, MF_MT_SUBTYPE, MF_VERSION, MFSTARTUP_NOSOCKET,
};

use super::{to_i16_le, Encoder, EncoderFactory};
use crate::pipeline::SAMPLE_RATE_HZ;

/// ADR-006's 32 kbps, in the units Media Foundation asks for.
pub const ADR006_BYTES_PER_SECOND: u32 = 4_000;

/// 128 kbps at 48 kHz — the middle of what the Microsoft AAC encoder documents. The self-test asks
/// for it as a control: if this fails too, the machine has no AAC encoder at all, which is a
/// different answer from "it has one that will not do 16 kHz".
pub const DOCUMENTED_BYTES_PER_SECOND: u32 = 16_000;
pub const DOCUMENTED_RATE_HZ: u32 = 48_000;

/// AAC Profile L2, which is what the MP4 sink expects to be told.
const AAC_PROFILE_L2: u32 = 0x29;

/// Once per process. Media Foundation is reference counted and this helper never wants it gone
/// before it exits.
fn startup() -> windows::core::Result<()> {
    static ONCE: std::sync::Once = std::sync::Once::new();
    let mut result = Ok(());
    ONCE.call_once(|| result = unsafe { MFStartup(MF_VERSION, MFSTARTUP_NOSOCKET) });
    result
}

/// A sink writer that will take 16 kHz mono PCM and write AAC into [path], ready for samples.
fn sink_writer(
    path: &Path,
    rate_hz: u32,
    bytes_per_second: u32,
) -> windows::core::Result<(IMFSinkWriter, u32)> {
    startup()?;
    unsafe {
        let output: IMFMediaType = MFCreateMediaType()?;
        output.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Audio)?;
        output.SetGUID(&MF_MT_SUBTYPE, &MFAudioFormat_AAC)?;
        output.SetUINT32(&MF_MT_AUDIO_BITS_PER_SAMPLE, 16)?;
        output.SetUINT32(&MF_MT_AUDIO_SAMPLES_PER_SECOND, rate_hz)?;
        output.SetUINT32(&MF_MT_AUDIO_NUM_CHANNELS, 1)?;
        output.SetUINT32(&MF_MT_AUDIO_AVG_BYTES_PER_SECOND, bytes_per_second)?;
        output.SetUINT32(&MF_MT_AAC_PAYLOAD_TYPE, 0)?;
        output.SetUINT32(&MF_MT_AAC_AUDIO_PROFILE_LEVEL_INDICATION, AAC_PROFILE_L2)?;

        let writer = MFCreateSinkWriterFromURL(&HSTRING::from(path.as_os_str()), None, None)?;
        let stream = writer.AddStream(&output)?;

        let input: IMFMediaType = MFCreateMediaType()?;
        input.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Audio)?;
        input.SetGUID(&MF_MT_SUBTYPE, &MFAudioFormat_PCM)?;
        input.SetUINT32(&MF_MT_AUDIO_BITS_PER_SAMPLE, 16)?;
        input.SetUINT32(&MF_MT_AUDIO_SAMPLES_PER_SECOND, rate_hz)?;
        input.SetUINT32(&MF_MT_AUDIO_NUM_CHANNELS, 1)?;
        input.SetUINT32(&MF_MT_AUDIO_BLOCK_ALIGNMENT, 2)?;
        input.SetUINT32(&MF_MT_AUDIO_AVG_BYTES_PER_SECOND, rate_hz * 2)?;
        input.SetUINT32(&MF_MT_ALL_SAMPLES_INDEPENDENT, 1)?;
        // This is the call that refuses a rate the AAC encoder will not take.
        writer.SetInputMediaType(stream, &input, None)?;
        writer.BeginWriting()?;
        Ok((writer, stream))
    }
}

/// Builds and tears down a writer for one format, writing nothing. `--self-test`'s question, and
/// the only part of S8 a machine with no audio device can answer.
pub fn probe(rate_hz: u32, bytes_per_second: u32) -> windows::core::Result<()> {
    let mut path = std::env::temp_dir();
    path.push(format!("recly-selftest-{rate_hz}-{bytes_per_second}.m4a"));
    let result = sink_writer(&path, rate_hz, bytes_per_second).map(|_| ());
    let _ = std::fs::remove_file(&path);
    result
}

pub struct MfEncoderFactory;

impl EncoderFactory for MfEncoderFactory {
    fn open(&self, path: &Path) -> io::Result<Box<dyn Encoder>> {
        let (writer, stream) = sink_writer(path, SAMPLE_RATE_HZ, ADR006_BYTES_PER_SECOND)
            .map_err(|error| io::Error::other(format!("media foundation: {error}")))?;
        Ok(Box::new(MfEncoder {
            writer: Some(writer),
            stream,
            frames: 0,
            scratch: Vec::new(),
        }))
    }

    fn name(&self) -> &'static str {
        "mf"
    }
}

pub struct MfEncoder {
    writer: Option<IMFSinkWriter>,
    stream: u32,
    /// Frames written so far — the sample timestamps are counted from them rather than from a clock,
    /// so the file's timeline is the recording's.
    frames: u64,
    scratch: Vec<u8>,
}

/// 100-nanosecond units per second, which is what Media Foundation counts time in.
const HNS_PER_SEC: u64 = 10_000_000;

impl Encoder for MfEncoder {
    fn write(&mut self, samples: &[f32]) -> io::Result<()> {
        let Some(writer) = self.writer.as_ref() else {
            return Ok(());
        };
        if samples.is_empty() {
            return Ok(());
        }
        to_i16_le(samples, &mut self.scratch);
        let bytes = self.scratch.len() as u32;
        let result: windows::core::Result<()> = unsafe {
            (|| {
                let buffer = MFCreateMemoryBuffer(bytes)?;
                let mut destination = std::ptr::null_mut();
                buffer.Lock(&mut destination, None, None)?;
                std::ptr::copy_nonoverlapping(self.scratch.as_ptr(), destination, bytes as usize);
                buffer.Unlock()?;
                buffer.SetCurrentLength(bytes)?;

                let sample = MFCreateSample()?;
                sample.AddBuffer(&buffer)?;
                sample.SetSampleTime(
                    (self.frames * HNS_PER_SEC / u64::from(SAMPLE_RATE_HZ)) as i64,
                )?;
                sample.SetSampleDuration(
                    (samples.len() as u64 * HNS_PER_SEC / u64::from(SAMPLE_RATE_HZ)) as i64,
                )?;
                writer.WriteSample(self.stream, &sample)
            })()
        };
        result.map_err(|error| io::Error::other(format!("media foundation: {error}")))?;
        self.frames += samples.len() as u64;
        Ok(())
    }

    fn finish(&mut self) -> io::Result<()> {
        let Some(writer) = self.writer.take() else {
            return Ok(());
        };
        // `Finalize` is what writes the MP4 trailer; until it returns the file is not one a reader
        // can open, and the caller hashes it as soon as this does.
        unsafe { writer.Finalize() }
            .map_err(|error| io::Error::other(format!("media foundation: {error}")))
    }
}
