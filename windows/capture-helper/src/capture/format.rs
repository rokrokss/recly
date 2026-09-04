//! Reading a WASAPI packet's bytes as mono `f32`.
//!
//! The engine is asked to hand over 32-bit float mono and usually does (see
//! [`super::wasapi::Stream::open`]), in which case only [`SampleFormat::Float32`] is ever used. This
//! is what happens when it refuses: a shared-mode mix format is whatever the endpoint's driver
//! offers, and "32 bits" alone does not say float — a `WAVEFORMATEXTENSIBLE` carrying
//! `KSDATAFORMAT_SUBTYPE_PCM` at 32 bits is an integer, and reading it as float is silence and
//! crackle rather than an error anyone would notice.
//!
//! Pure and byte-oriented on purpose: the layouts below are the whole risk of this file, and this is
//! the half of the capture path a macOS host can actually test.

/// How one sample sits in a packet.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SampleFormat {
    Float32,
    Int16,
    /// Three bytes per sample, packed — what a `WAVEFORMATEXTENSIBLE` at 24 bits means.
    Int24,
    Int32,
    /// Four bytes per sample with 24 significant bits, left-aligned (`wValidBitsPerSample` 24).
    Int24In32,
}

impl SampleFormat {
    pub fn bytes(self) -> usize {
        match self {
            SampleFormat::Int16 => 2,
            SampleFormat::Int24 => 3,
            SampleFormat::Float32 | SampleFormat::Int32 | SampleFormat::Int24In32 => 4,
        }
    }

    pub fn name(self) -> &'static str {
        match self {
            SampleFormat::Float32 => "float32",
            SampleFormat::Int16 => "int16",
            SampleFormat::Int24 => "int24",
            SampleFormat::Int32 => "int32",
            SampleFormat::Int24In32 => "int24-in-32",
        }
    }

    /// One sample at [offset]. Out of range reads as silence rather than panicking: this runs on
    /// bytes a driver handed over, and a short packet must not end a recording.
    fn sample(self, bytes: &[u8], offset: usize) -> f32 {
        let Some(raw) = bytes.get(offset..offset + self.bytes()) else {
            return 0.0;
        };
        match self {
            SampleFormat::Float32 => f32::from_le_bytes([raw[0], raw[1], raw[2], raw[3]]),
            SampleFormat::Int16 => {
                f32::from(i16::from_le_bytes([raw[0], raw[1]])) / 32_768.0
            }
            SampleFormat::Int24 => {
                // Sign-extended out of the top byte; the value scales against 2^23.
                let value =
                    (i32::from(raw[0])) | (i32::from(raw[1]) << 8) | (i32::from(raw[2] as i8) << 16);
                value as f32 / 8_388_608.0
            }
            // Left-aligned 24-in-32 and a full 32-bit integer scale identically — dividing the whole
            // word by 2^31 is the same number as dividing its top 24 bits by 2^23 — so the two
            // share the arithmetic and stay separate only because the negotiated format says which
            // one the endpoint offered.
            SampleFormat::Int32 | SampleFormat::Int24In32 => {
                i32::from_le_bytes([raw[0], raw[1], raw[2], raw[3]]) as f32 / 2_147_483_648.0
            }
        }
    }
}

/// Appends [frames] of [bytes] to [out] as mono, averaging the channels.
///
/// Averaging rather than taking the first channel: a stereo render endpoint puts a hard-panned voice
/// in one channel, and half of a meeting would be missing from the `sys` track.
///
/// [frame_bytes] is the packet's own stride (`nBlockAlign`) rather than
/// `channels × format.bytes()`, so a format that pads its frames is still read at the right offsets.
pub fn append_mono(
    bytes: &[u8],
    frames: usize,
    channels: usize,
    frame_bytes: usize,
    format: SampleFormat,
    out: &mut Vec<f32>,
) {
    if channels == 0 {
        return;
    }
    out.reserve(frames);
    let width = format.bytes();
    for frame in 0..frames {
        let base = frame * frame_bytes;
        let mut sum = 0.0f32;
        for channel in 0..channels {
            sum += format.sample(bytes, base + channel * width);
        }
        out.push(sum / channels as f32);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The layouts a shared-mode mix format can arrive in, each read back as the value it encodes.
    /// Full scale in every one of them is ±1, which is what the mix's headroom arithmetic assumes.
    #[test]
    fn every_layout_decodes_to_the_same_scale() {
        let cases: Vec<(SampleFormat, Vec<u8>, f32)> = vec![
            (SampleFormat::Float32, 0.5f32.to_le_bytes().to_vec(), 0.5),
            (SampleFormat::Float32, (-1.0f32).to_le_bytes().to_vec(), -1.0),
            (SampleFormat::Int16, 16_384i16.to_le_bytes().to_vec(), 0.5),
            (SampleFormat::Int16, i16::MIN.to_le_bytes().to_vec(), -1.0),
            // 2^22 = half of 2^23, packed little-endian in three bytes.
            (SampleFormat::Int24, vec![0x00, 0x00, 0x40], 0.5),
            (SampleFormat::Int24, vec![0x00, 0x00, 0x80], -1.0),
            (SampleFormat::Int32, 1_073_741_824i32.to_le_bytes().to_vec(), 0.5),
            (SampleFormat::Int32, i32::MIN.to_le_bytes().to_vec(), -1.0),
            // 24-in-32: the value sits in the top three bytes, the low byte is padding.
            (SampleFormat::Int24In32, vec![0x00, 0x00, 0x00, 0x40], 0.5),
            (SampleFormat::Int24In32, vec![0x00, 0x00, 0x00, 0x80], -1.0),
        ];
        for (format, bytes, expected) in cases {
            let mut out = Vec::new();
            append_mono(&bytes, 1, 1, format.bytes(), format, &mut out);
            assert_eq!(vec![expected], out, "{}", format.name());
        }
    }

    /// The bug this file exists to stop: a `WAVEFORMATEXTENSIBLE` carrying `KSDATAFORMAT_SUBTYPE_PCM`
    /// at 32 bits read as float. Nothing errors — the same bytes are simply different audio, and
    /// which kind of wrong depends on the level: loud samples land past full scale (clipping), and
    /// the quiet ones a meeting mostly consists of land in the denormals (silence).
    #[test]
    fn an_integer_packet_read_as_float_is_different_audio() {
        let read = |bytes: [u8; 4], format| {
            let mut out = Vec::new();
            append_mono(&bytes, 1, 1, 4, format, &mut out);
            out[0]
        };
        // Half scale as a 32-bit integer.
        let loud = 1_073_741_824i32.to_le_bytes();
        assert_eq!(0.5, read(loud, SampleFormat::Int32));
        assert_eq!(2.0, read(loud, SampleFormat::Float32), "past full scale");
        // One percent of full scale, which is ordinary speech.
        let quiet = 21_474_836i32.to_le_bytes();
        assert!((read(quiet, SampleFormat::Int32) - 0.01).abs() < 1e-6);
        assert!(read(quiet, SampleFormat::Float32).abs() < 1e-30, "silence");
    }

    /// Stereo is averaged, and the stride is the packet's rather than the format's.
    #[test]
    fn channels_are_averaged_across_the_frame_stride() {
        let mut bytes = Vec::new();
        for (left, right) in [(1.0f32, 0.0f32), (-1.0, 1.0), (0.25, 0.75)] {
            bytes.extend_from_slice(&left.to_le_bytes());
            bytes.extend_from_slice(&right.to_le_bytes());
        }
        let mut out = Vec::new();
        append_mono(&bytes, 3, 2, 8, SampleFormat::Float32, &mut out);
        assert_eq!(vec![0.5, 0.0, 0.5], out);
    }

    /// A packet shorter than it claims is read as far as it goes and silent after — a driver's
    /// mistake must not panic the capture thread.
    #[test]
    fn a_short_packet_reads_as_silence_rather_than_panicking() {
        let mut out = Vec::new();
        append_mono(&0.5f32.to_le_bytes(), 3, 1, 4, SampleFormat::Float32, &mut out);
        assert_eq!(vec![0.5, 0.0, 0.0], out);
    }
}
