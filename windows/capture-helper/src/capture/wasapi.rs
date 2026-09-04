//! The WASAPI plumbing both endpoints share: open a shared-mode stream on a default endpoint and
//! read its packets as mono `f32`.
//!
//! Shared mode, not exclusive: the microphone has to be capturable while a meeting app is holding
//! it, and the render endpoint has to be captured without silencing the speakers.
//!
//! **The format is negotiated, not assumed.** The engine is asked for 32-bit float mono at the
//! endpoint's own rate with `AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY`,
//! which is the shared-mode way of saying "convert for me": it collapses the channel down-mix and
//! every integer layout into the one case this helper wants. Only if `Initialize` refuses those
//! flags does the mix format come back — and then it is *decoded*, by
//! [`super::format::SampleFormat`], rather than assumed to be float. A `WAVEFORMATEXTENSIBLE`
//! carrying `KSDATAFORMAT_SUBTYPE_PCM` at 32 bits is an integer stream, and reading it as float is
//! silence and crackle that nothing reports.
//!
//! None of this can be run on the macOS development host — it is compile-checked with
//! `cargo check --target x86_64-pc-windows-msvc` and linked on `windows-latest` in CI. What *is*
//! tested here is the decoding, which is where the layouts live (`capture/format.rs`).

use std::io;
use std::sync::mpsc::Sender;

use windows::core::GUID;
use windows::Win32::Foundation::{CloseHandle, HANDLE};
use windows::Win32::Media::Audio::{
    eCapture, eConsole, eRender, EDataFlow, IAudioCaptureClient, IAudioClient, IMMDeviceEnumerator,
    MMDeviceEnumerator, AUDCLNT_BUFFERFLAGS_SILENT, AUDCLNT_SHAREMODE_SHARED,
    AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM, AUDCLNT_STREAMFLAGS_EVENTCALLBACK,
    AUDCLNT_STREAMFLAGS_LOOPBACK, AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY, WAVEFORMATEX,
    WAVEFORMATEXTENSIBLE,
};
use windows::Win32::Media::KernelStreaming::{KSDATAFORMAT_SUBTYPE_PCM, WAVE_FORMAT_EXTENSIBLE};
use windows::Win32::Media::Multimedia::{KSDATAFORMAT_SUBTYPE_IEEE_FLOAT, WAVE_FORMAT_IEEE_FLOAT};
use windows::Win32::System::Com::{
    CoCreateInstance, CoInitializeEx, CoTaskMemFree, CoUninitialize, CLSCTX_ALL,
    COINIT_MULTITHREADED,
};
use windows::Win32::System::Threading::{CreateEventW, WaitForSingleObject};

use super::format::{append_mono, SampleFormat};
use super::Delivery;

/// 100-nanosecond units, which is what `IAudioClient::Initialize` counts a buffer in.
pub const HNS_PER_SEC: i64 = 10_000_000;

/// Multi-threaded apartment, for the lifetime of a capture thread. COM objects belong to the thread
/// that created them, which is why every stream here is opened inside its own thread.
pub struct Apartment;

impl Apartment {
    pub fn enter() -> io::Result<Self> {
        // S_FALSE means this thread was already initialised, which is not an error.
        unsafe { CoInitializeEx(None, COINIT_MULTITHREADED) }
            .ok()
            .map_err(from_hresult)?;
        Ok(Self)
    }
}

impl Drop for Apartment {
    fn drop(&mut self) {
        unsafe { CoUninitialize() };
    }
}

/// One shared-mode capture stream, with the format facts its packets have to be read through.
pub struct Stream {
    client: IAudioClient,
    capture: IAudioCaptureClient,
    event: Option<HANDLE>,
    pub rate: f64,
    channels: usize,
    /// The packet's stride, `nBlockAlign`.
    frame_bytes: usize,
    format: SampleFormat,
    /// Whether the engine is doing the conversion, which is what the self-test reports.
    converted: bool,
}

impl Stream {
    /// Opens the default endpoint of [flow] and negotiates its format, without starting it — see
    /// [`Self::start`]. [loopback] captures what the endpoint is rendering (docs/14 "시스템"), and
    /// turns the event callback off with it: a render endpoint that is playing nothing signals no
    /// event, so the caller polls and fills the silence itself.
    pub fn open(flow: EDataFlow, loopback: bool, buffer_hns: i64) -> io::Result<Self> {
        unsafe {
            let enumerator: IMMDeviceEnumerator =
                CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL).map_err(from_hresult)?;
            let device = enumerator
                .GetDefaultAudioEndpoint(flow, eConsole)
                .map_err(from_hresult)?;
            let client: IAudioClient = device.Activate(CLSCTX_ALL, None).map_err(from_hresult)?;

            // `WAVEFORMATEX` is `#[repr(packed)]`, so every field is read rather than referenced.
            let mix = client.GetMixFormat().map_err(from_hresult)?;
            let rate = std::ptr::addr_of!((*mix).nSamplesPerSec).read_unaligned();

            let base_flags = if loopback {
                AUDCLNT_STREAMFLAGS_LOOPBACK
            } else {
                AUDCLNT_STREAMFLAGS_EVENTCALLBACK
            };

            // First ask the engine to convert: one channel of float, at the endpoint's own rate so
            // nothing but the layout changes and the drift estimate still sees the device's clock.
            let wanted = float_mono(rate);
            let converted = client.Initialize(
                AUDCLNT_SHAREMODE_SHARED,
                base_flags | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY,
                buffer_hns,
                0,
                &wanted,
                None,
            );

            let (channels, frame_bytes, format, converted) = if converted.is_ok() {
                (1usize, 4usize, SampleFormat::Float32, true)
            } else {
                // The engine will not convert — an older driver, or a loopback stream whose engine
                // refuses the flags. Take the mix format as it is and read it for what it says.
                let described = describe_mix(mix);
                let result = client.Initialize(
                    AUDCLNT_SHAREMODE_SHARED,
                    base_flags,
                    buffer_hns,
                    0,
                    mix,
                    None,
                );
                if let Err(error) = result {
                    CoTaskMemFree(Some(mix as *const _));
                    return Err(from_hresult(error));
                }
                match described {
                    Some(described) => described,
                    None => {
                        CoTaskMemFree(Some(mix as *const _));
                        return Err(io::Error::other(
                            "the endpoint's mix format is one this helper cannot read",
                        ));
                    }
                }
            };
            CoTaskMemFree(Some(mix as *const _));

            let event = if loopback {
                None
            } else {
                let handle = CreateEventW(None, false, false, None).map_err(from_hresult)?;
                client.SetEventHandle(handle).map_err(from_hresult)?;
                Some(handle)
            };

            let capture: IAudioCaptureClient = client.GetService().map_err(from_hresult)?;
            Ok(Self {
                client,
                capture,
                event,
                rate: f64::from(rate),
                channels,
                frame_bytes,
                format,
                converted,
            })
        }
    }

    /// What was negotiated, for `--self-test` and the start log.
    pub fn describe(&self) -> String {
        format!(
            "{} Hz, {} ch, {} ({})",
            self.rate,
            self.channels,
            self.format.name(),
            if self.converted {
                "engine-converted"
            } else {
                "endpoint's own mix format"
            }
        )
    }

    pub fn start(&self) -> io::Result<()> {
        unsafe { self.client.Start() }.map_err(from_hresult)
    }

    /// Blocks until the endpoint says it has audio, or [timeout_ms] passes. A loopback stream has no
    /// event and returns immediately, which is what makes its caller a poller.
    pub fn wait(&self, timeout_ms: u32) {
        if let Some(event) = self.event {
            unsafe { WaitForSingleObject(event, timeout_ms) };
        }
    }

    /// Reads every packet the endpoint has waiting, appending mono frames to [out]. Returns how many
    /// frames that was, which is zero for an endpoint that is idle.
    pub fn drain(&self, out: &mut Vec<f32>) -> io::Result<usize> {
        let mut frames_read = 0usize;
        unsafe {
            loop {
                let available = self.capture.GetNextPacketSize().map_err(from_hresult)?;
                if available == 0 {
                    return Ok(frames_read);
                }
                let mut data = std::ptr::null_mut();
                let mut frames = 0u32;
                let mut flags = 0u32;
                self.capture
                    .GetBuffer(&mut data, &mut frames, &mut flags, None, None)
                    .map_err(from_hresult)?;
                let count = frames as usize;
                if flags & AUDCLNT_BUFFERFLAGS_SILENT.0 as u32 != 0 {
                    // WASAPI is allowed to hand back a buffer it has not filled and say so.
                    out.resize(out.len() + count, 0.0);
                } else {
                    let bytes = std::slice::from_raw_parts(data, count * self.frame_bytes);
                    append_mono(bytes, count, self.channels, self.frame_bytes, self.format, out);
                }
                self.capture.ReleaseBuffer(frames).map_err(from_hresult)?;
                frames_read += count;
            }
        }
    }
}

impl Drop for Stream {
    fn drop(&mut self) {
        unsafe {
            // A stream the self-test opened and never started refuses this, which is fine.
            let _ = self.client.Stop();
            if let Some(event) = self.event {
                let _ = CloseHandle(event);
            }
        }
    }
}

/// What the engine is asked for: one channel of 32-bit float at the endpoint's rate.
fn float_mono(rate: u32) -> WAVEFORMATEX {
    WAVEFORMATEX {
        wFormatTag: WAVE_FORMAT_IEEE_FLOAT as u16,
        nChannels: 1,
        nSamplesPerSec: rate,
        nAvgBytesPerSec: rate * 4,
        nBlockAlign: 4,
        wBitsPerSample: 32,
        cbSize: 0,
    }
}

/// The mix format's channel count, stride and sample layout, or `None` for one this helper has no
/// reading for. `WAVEFORMATEX` is packed, so every field is read rather than referenced.
unsafe fn describe_mix(format: *const WAVEFORMATEX) -> Option<(usize, usize, SampleFormat, bool)> {
    let tag = u32::from(std::ptr::addr_of!((*format).wFormatTag).read_unaligned());
    let bits = std::ptr::addr_of!((*format).wBitsPerSample).read_unaligned();
    let channels = usize::from(std::ptr::addr_of!((*format).nChannels).read_unaligned());
    let frame_bytes = usize::from(std::ptr::addr_of!((*format).nBlockAlign).read_unaligned());

    // A shared-mode mix format is nearly always `WAVEFORMATEXTENSIBLE`, where the sub-format GUID —
    // not the tag — says float or integer, and `wValidBitsPerSample` distinguishes 24-in-32 from a
    // full 32-bit integer.
    let (float, valid_bits) = if tag == WAVE_FORMAT_EXTENSIBLE {
        let extensible = format as *const WAVEFORMATEXTENSIBLE;
        let subformat: GUID = std::ptr::addr_of!((*extensible).SubFormat).read_unaligned();
        if subformat != KSDATAFORMAT_SUBTYPE_IEEE_FLOAT && subformat != KSDATAFORMAT_SUBTYPE_PCM {
            return None;
        }
        (
            subformat == KSDATAFORMAT_SUBTYPE_IEEE_FLOAT,
            std::ptr::addr_of!((*extensible).Samples.wValidBitsPerSample).read_unaligned(),
        )
    } else {
        (tag == WAVE_FORMAT_IEEE_FLOAT, bits)
    };

    let sample = match (float, bits, valid_bits) {
        (true, 32, _) => SampleFormat::Float32,
        (false, 16, _) => SampleFormat::Int16,
        (false, 24, _) => SampleFormat::Int24,
        (false, 32, 24) => SampleFormat::Int24In32,
        (false, 32, _) => SampleFormat::Int32,
        _ => return None,
    };
    Some((channels, frame_bytes, sample, false))
}

pub fn capture_flow() -> EDataFlow {
    eCapture
}

pub fn render_flow() -> EDataFlow {
    eRender
}

fn from_hresult(error: windows::core::Error) -> io::Error {
    io::Error::other(error.to_string())
}

/// Reports a capture thread's failure on the audio channel and ends it (see [`super::Delivery`]).
pub fn report(sender: &Sender<Delivery>, error: io::Error) {
    let _ = sender.send(Delivery::Failed(error));
}
