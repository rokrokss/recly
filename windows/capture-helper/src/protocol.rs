//! The docs/14 helper protocol: one JSON object per line each way, the app on stdin and the helper
//! on stdout.
//!
//! `windows/app/src/main/kotlin/app/recly/windows/helper/HelperProtocol.kt` is the other end and the
//! contract — the wire names here are its `@SerialName`s, and its `ignoreUnknownKeys` is what lets
//! this side say more than that version reads (`mic_in_use.inUse`).
//!
//! Two deliberate differences from the lane's sketch, both forced by that contract:
//! * no `recordingId` on `part_done` — `start` does not carry one. One helper process serves one
//!   recording, so the app already knows which recording an event belongs to; inventing an id here
//!   would be a second source for something only the app has.
//! * `part_done` carries `startOffsetSec`, which the app's `parts[]` entry needs (docs/03).

use serde::{Deserialize, Serialize};

/// docs/03 "트랙". `mono` is the mobile shape; a desktop recording is `mic`/`sys`/`mix`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Track {
    Mono,
    Mic,
    Sys,
    Mix,
}

impl Track {
    pub fn name(self) -> &'static str {
        match self {
            Track::Mono => "mono",
            Track::Mic => "mic",
            Track::Sys => "sys",
            Track::Mix => "mix",
        }
    }

    /// Which captured stream this track is written from. `mono` is the microphone alone, as on the
    /// phone.
    pub fn needs_system_audio(self) -> bool {
        matches!(self, Track::Sys | Track::Mix)
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(tag = "command", rename_all = "snake_case", rename_all_fields = "camelCase")]
pub enum Command {
    Start {
        dir: String,
        /// docs/03 "이름 규칙". The app owns the names; the helper writes what it was told.
        base: String,
        segment_sec: u32,
        tracks: Vec<Track>,
    },
    Stop,
    /// docs/14 "감지": mic-in-use monitoring, on or off.
    Detect { on: bool },
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(tag = "event", rename_all = "snake_case", rename_all_fields = "camelCase")]
pub enum Event {
    /// A segment is closed and on disk. The helper hashes the file it just closed, because it is
    /// the only side that knows when the last frame landed.
    PartDone {
        part: u32,
        track: Track,
        file: String,
        bytes: u64,
        sha256: String,
        start_offset_sec: f64,
        duration_sec: f64,
    },
    MicInUse {
        app: String,
        in_use: bool,
    },
    /// docs/09 화면 원칙 6: the peak of every tenth of a second the write path finished since the
    /// last one, oldest first — what the app's strip draws while a recording runs. A line ten times
    /// a second, so it carries nothing but the numbers and they are rounded ([`Event::level`]).
    Level {
        peaks: Vec<f32>,
    },
    #[serde(rename = "error")]
    Failed { message: String, fatal: bool },
}

impl Event {
    pub fn failed(message: impl std::fmt::Display, fatal: bool) -> Self {
        Event::Failed {
            message: message.to_string(),
            fatal,
        }
    }

    /// The levels, to three decimals. A bar is 2dp tall on a 44dp row, so the digits past the third
    /// are not a pixel of anything — and this is the one line that goes out ten times a second.
    pub fn level(peaks: &[f32]) -> Self {
        Event::Level {
            peaks: peaks.iter().map(|peak| (peak * 1_000.0).round() / 1_000.0).collect(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Deliverable 6: the protocol round trip, against the exact lines
    /// `HelperProtocol.kt`/`FakeHelper.java` produce and read.
    #[test]
    fn a_start_from_the_app_parses() {
        let line = r#"{"command":"start","dir":"/tmp/rec","base":"20260827T100000Z_desktop_01H","segmentSec":900,"tracks":["mic","sys","mix"]}"#;
        match serde_json::from_str::<Command>(line).expect("start") {
            Command::Start { dir, base, segment_sec, tracks } => {
                assert_eq!("/tmp/rec", dir);
                assert_eq!("20260827T100000Z_desktop_01H", base);
                assert_eq!(900, segment_sec);
                assert_eq!(vec![Track::Mic, Track::Sys, Track::Mix], tracks);
            }
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn stop_and_detect_parse() {
        assert!(matches!(
            serde_json::from_str::<Command>(r#"{"command":"stop"}"#).expect("stop"),
            Command::Stop
        ));
        assert!(matches!(
            serde_json::from_str::<Command>(r#"{"command":"detect","on":true}"#).expect("detect"),
            Command::Detect { on: true }
        ));
    }

    /// The field names the Kotlin `@Serializable` classes declare. A rename here is a recording the
    /// app files nothing for.
    #[test]
    fn a_part_done_is_the_line_the_app_reads() {
        let event = Event::PartDone {
            part: 2,
            track: Track::Mix,
            file: "base_p002_mix.m4a".into(),
            bytes: 3_601_234,
            sha256: "abc".into(),
            start_offset_sec: 900.0,
            duration_sec: 880.5,
        };
        assert_eq!(
            r#"{"event":"part_done","part":2,"track":"mix","file":"base_p002_mix.m4a","bytes":3601234,"sha256":"abc","startOffsetSec":900.0,"durationSec":880.5}"#,
            serde_json::to_string(&event).expect("part_done"),
        );
    }

    #[test]
    fn detection_and_errors_are_the_lines_the_app_reads() {
        assert_eq!(
            r#"{"event":"mic_in_use","app":"Zoom.exe","inUse":true}"#,
            serde_json::to_string(&Event::MicInUse { app: "Zoom.exe".into(), in_use: true }).unwrap(),
        );
        assert_eq!(
            r#"{"event":"error","message":"no microphone","fatal":true}"#,
            serde_json::to_string(&Event::failed("no microphone", true)).unwrap(),
        );
    }

    /// The strip's line, as `HelperEvent.Level` reads it — and short: three decimals is what a bar
    /// on a 44dp row can be drawn from, and this one goes out ten times a second.
    #[test]
    fn a_level_is_the_line_the_app_reads() {
        assert_eq!(
            r#"{"event":"level","peaks":[0.1,0.5]}"#,
            serde_json::to_string(&Event::level(&[0.1, 0.5])).unwrap(),
        );
        assert_eq!(
            r#"{"event":"level","peaks":[0.123,0.007]}"#,
            serde_json::to_string(&Event::level(&[0.123_456, 0.006_7])).unwrap(),
        );
    }
}
