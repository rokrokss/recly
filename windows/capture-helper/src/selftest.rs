//! `--self-test`: what a Windows machine can answer about this helper without recording anything.
//!
//! It is CI's half of docs/20 S8 (docs/lanes/M6-L2 "Windows 검증은 CI로"). A `windows-latest` runner
//! has no audio endpoint at all, so the endpoint lines are expected to fail there and are printed
//! rather than asserted — what CI is really being asked is the **encoder** question docs/14 left
//! open: can Media Foundation's AAC encoder produce ADR-006's format?
//!
//! Everything goes to stdout, and the exit code is always 0: this is a report, not a gate.

use std::fmt::Write as _;
#[cfg(windows)]
use std::io;

pub fn run() -> String {
    let mut report = String::new();
    let _ = writeln!(report, "recly-capture-helper --self-test");
    let _ = writeln!(
        report,
        "  build: {} {}",
        std::env::consts::OS,
        std::env::consts::ARCH
    );
    let _ = writeln!(report, "  ffmpeg (ADR-019 encoder): {}", ffmpeg_version());
    body(&mut report);
    report
}

/// ADR-019's encoder has to be *there*, and on a user's machine it is next to the helper in the MSI.
fn ffmpeg_version() -> String {
    match std::process::Command::new("ffmpeg").arg("-version").output() {
        Ok(output) => String::from_utf8_lossy(&output.stdout)
            .lines()
            .next()
            .unwrap_or("(no output)")
            .to_string(),
        Err(error) => format!("not runnable ({error})"),
    }
}

#[cfg(not(windows))]
fn body(report: &mut String) {
    let _ = writeln!(
        report,
        "  wasapi/media foundation: skipped — this is not a Windows build"
    );
}

#[cfg(windows)]
fn body(report: &mut String) {
    use crate::capture::wasapi::{capture_flow, render_flow, Stream, HNS_PER_SEC};
    use crate::encode::mf;
    use windows::Win32::Media::Audio::EDataFlow;
    use windows::Win32::System::Com::{CoInitializeEx, COINIT_MULTITHREADED};

    /// The format the capture thread would *negotiate* on this endpoint — the engine-converted
    /// float32 mono if `Initialize` takes the conversion flags, otherwise the mix format this helper
    /// would have to decode itself. Opened and dropped without ever starting, so nothing is
    /// captured and no recording indicator appears.
    fn endpoint(flow: EDataFlow, loopback: bool) -> io::Result<String> {
        Stream::open(flow, loopback, if loopback { HNS_PER_SEC / 4 } else { 0 })
            .map(|stream| stream.describe())
    }

    fn describe<T: std::fmt::Display, E: std::fmt::Display>(result: Result<T, E>) -> String {
        match result {
            Ok(value) => format!("ok — {value}"),
            Err(error) => format!("unavailable — {error}"),
        }
    }

    let _ = unsafe { CoInitializeEx(None, COINIT_MULTITHREADED) };
    let _ = writeln!(
        report,
        "  capture endpoint (negotiated): {}",
        describe(endpoint(capture_flow(), false))
    );
    let _ = writeln!(
        report,
        "  render endpoint  (negotiated): {}",
        describe(endpoint(render_flow(), true))
    );

    // The docs/14 open item, asked of the machine rather than of the documentation. ADR-019 says the
    // first line fails and the second succeeds; if the first one ever succeeds, `--encoder mf` is
    // already written and ADR-019 is one revision away from changing its mind.
    let adr006 = mf::probe(crate::pipeline::SAMPLE_RATE_HZ, mf::ADR006_BYTES_PER_SECOND);
    let documented = mf::probe(mf::DOCUMENTED_RATE_HZ, mf::DOCUMENTED_BYTES_PER_SECOND);
    let _ = writeln!(
        report,
        "  MF AAC @ 16000 Hz mono 32 kbps (ADR-006): {}",
        describe(adr006.map(|()| "sink writer accepted the format"))
    );
    let _ = writeln!(
        report,
        "  MF AAC @ 48000 Hz mono 128 kbps (documented): {}",
        describe(documented.map(|()| "sink writer accepted the format"))
    );
}
