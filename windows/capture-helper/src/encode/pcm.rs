//! The stand-in encoder: 16-bit little-endian PCM, no container.
//!
//! It exists so the segment rules — boundaries, part numbers, `bytes`, `sha256`, the last part on a
//! stop — can be tested on a host with no AAC encoder, and so `--fake-source` can drive the whole
//! path in front of `HelperClientTest`. It is never what a Windows install runs.

use std::fs::File;
use std::io::{self, BufWriter, Write};
use std::path::Path;

use super::{to_i16_le, Encoder, EncoderFactory};

pub struct PcmEncoderFactory;

impl EncoderFactory for PcmEncoderFactory {
    fn open(&self, path: &Path) -> io::Result<Box<dyn Encoder>> {
        Ok(Box::new(PcmEncoder {
            file: Some(BufWriter::new(File::create(path)?)),
            scratch: Vec::new(),
        }))
    }

    fn name(&self) -> &'static str {
        "pcm"
    }
}

pub struct PcmEncoder {
    file: Option<BufWriter<File>>,
    scratch: Vec<u8>,
}

impl Encoder for PcmEncoder {
    fn write(&mut self, samples: &[f32]) -> io::Result<()> {
        let Some(file) = self.file.as_mut() else {
            return Ok(());
        };
        to_i16_le(samples, &mut self.scratch);
        file.write_all(&self.scratch)
    }

    fn finish(&mut self) -> io::Result<()> {
        let Some(mut file) = self.file.take() else {
            return Ok(());
        };
        file.flush()
    }
}
