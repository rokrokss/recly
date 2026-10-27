//! docs/01 "Long recordings — watchdog", end to end against the binary: the helper tells a dead app from one
//! that let it go. Runs on the development host with the fake source and the PCM encoder; the balloon itself
//! is Windows-only, so what is checked here is the decision — the `rec.watchdog.fired` line on stderr.

use std::io::{BufRead, BufReader, Read, Write};
use std::path::PathBuf;
use std::process::{Child, ChildStdout, Command, Stdio};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

const FIRED: &str = "rec.watchdog.fired";

/// A helper recording into a directory of its own, past its first `level` line — so a recording is running.
fn recording() -> (Child, BufReader<ChildStdout>) {
    let dir = scratch();
    // On Windows the balloon is real, and kept a second rather than twelve hours.
    let mut child = Command::new(env!("CARGO_BIN_EXE_recly-capture-helper"))
        .args(["--fake-source", "sine", "--encoder", "pcm", "--segment-sec", "1", "--linger-sec", "1"])
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("spawn the helper");
    // Serialised, not formatted: a Windows path's backslashes are escapes in JSON, and a `start` that does
    // not parse never starts the recording this waits for.
    let start = serde_json::json!({
        "command": "start",
        "dir": dir,
        "base": "watchdog",
        "segmentSec": 900,
        "tracks": ["mic", "sys", "mix"],
        "watchdog": {"title": "Recording may have stopped", "body": "Open Recly to check."},
    });
    let stdin = child.stdin.as_mut().expect("stdin");
    writeln!(stdin, "{start}").expect("send start");
    stdin.flush().expect("flush");
    let mut stdout = BufReader::new(child.stdout.take().expect("stdout"));
    let mut line = String::new();
    loop {
        line.clear();
        assert!(stdout.read_line(&mut line).expect("read") > 0, "the helper ended before recording");
        if line.contains(r#""event":"level""#) {
            return (child, stdout);
        }
    }
}

fn scratch() -> PathBuf {
    let nanos = SystemTime::now().duration_since(UNIX_EPOCH).expect("clock").as_nanos();
    let dir = std::env::temp_dir().join(format!("recly-watchdog-{}-{nanos}", std::process::id()));
    std::fs::create_dir_all(&dir).expect("scratch dir");
    dir
}

/// Waits for the helper to exit and returns what it logged.
fn log_of(mut child: Child) -> String {
    let deadline = Instant::now() + Duration::from_secs(20);
    loop {
        if child.try_wait().expect("wait").is_some() {
            break;
        }
        if Instant::now() > deadline {
            let _ = child.kill();
            panic!("the helper did not exit");
        }
        std::thread::sleep(Duration::from_millis(20));
    }
    let mut log = String::new();
    child.stderr.take().expect("stderr").read_to_string(&mut log).expect("stderr");
    log
}

/// The app's process is gone: both of its pipe ends close with it. The helper closes its segment and warns.
#[test]
fn an_app_that_died_under_a_recording_is_warned_about() {
    let (mut child, stdout) = recording();
    drop(stdout);
    drop(child.stdin.take());

    let log = log_of(child);

    assert!(log.contains(FIRED), "{log}");
    assert!(log.contains("rec.stop"), "the last segment is closed first: {log}");
}

/// `HelperClient.close` without a `stop`: stdin ends, but the app still reads every line to the end.
#[test]
fn an_app_that_let_the_helper_go_is_not() {
    let (mut child, mut stdout) = recording();
    drop(child.stdin.take());
    let mut rest = String::new();
    stdout.read_to_string(&mut rest).expect("read to the end");

    let log = log_of(child);

    assert!(!log.contains(FIRED), "{log}");
    assert!(rest.contains(r#""event":"part_done""#), "the last segment is still announced: {rest}");
}

#[test]
fn a_stop_is_not() {
    let (mut child, mut stdout) = recording();
    let stdin = child.stdin.as_mut().expect("stdin");
    writeln!(stdin, r#"{{"command":"stop"}}"#).expect("send stop");
    stdin.flush().expect("flush");
    let mut rest = String::new();
    stdout.read_to_string(&mut rest).expect("read to the end");

    let log = log_of(child);

    assert!(!log.contains(FIRED), "{log}");
}
