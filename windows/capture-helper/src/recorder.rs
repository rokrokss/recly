//! One recording as a chain of `.m4a` segments on one, two or three tracks — the rules docs/03 and
//! docs/12 give, with the audio arriving as plain slices so a host with no capture device can check
//! them.
//!
//! The three tracks are deliberately dumb about each other: the boundary lives in a single
//! [`SegmentSplitter`], which is what makes the part numbers, the offsets and the durations
//! identical across them (docs/03 "같은 시간 구간이면 같은 번호"). A track that decided for itself
//! when its segment was full would drift out of step with its siblings within the hour.

use std::fs;
use std::io;
use std::path::{Path, PathBuf};

use sha2::{Digest, Sha256};

use crate::encode::{Encoder, EncoderFactory};
use crate::pipeline::level::{LiveLevel, WINDOW_FRAMES};
use crate::pipeline::mix::mix;
use crate::pipeline::segment::{SegmentLedger, SegmentSplitter};
use crate::pipeline::SAMPLE_RATE_HZ;
use crate::protocol::{Event, Track};

/// What one call produced: the parts that closed, and the failure that followed if there was one.
///
/// They are separate, and always both, because **a part that reached disk and was hashed is a part
/// the app must be told about**. Dropping it because the *next* segment could not be opened would
/// leave a complete, playable file that nothing registers — and docs/03's recovery, finding an
/// unregistered file in the directory, quarantines it as `.corrupt`. So every path out of here
/// reports what closed first and fails afterwards.
#[derive(Default)]
pub struct Written {
    pub parts: Vec<Event>,
    /// docs/09 화면 원칙 6: the tenths of a second that finished during this call, oldest first —
    /// the levels of the track the user hears, for the app's live strip. Not a part and not a
    /// failure: they are a picture of what was written, and losing one costs a bar.
    pub levels: Vec<f32>,
    pub failure: Option<io::Error>,
}

pub struct Recorder {
    dir: PathBuf,
    factory: Box<dyn EncoderFactory>,
    splitter: SegmentSplitter,
    writers: Vec<TrackWriter>,
    mixed: Vec<f32>,
    level: LiveLevel,
}

struct TrackWriter {
    track: Track,
    ledger: SegmentLedger,
    encoder: Box<dyn Encoder>,
}

impl Recorder {
    /// Opens part 1 of every track. Fails if the very first segment cannot be created, which is a
    /// recording that never started rather than one that lost a part.
    pub fn open(
        dir: &Path,
        base: &str,
        segment_sec: f64,
        tracks: &[Track],
        factory: Box<dyn EncoderFactory>,
    ) -> io::Result<Self> {
        fs::create_dir_all(dir)?;
        let frames_per_segment = (segment_sec * f64::from(SAMPLE_RATE_HZ)).round() as u64;
        let mut writers = Vec::with_capacity(tracks.len());
        for &track in tracks {
            let ledger = SegmentLedger::new(base, track);
            let encoder = factory.open(&dir.join(ledger.open_file_name()))?;
            writers.push(TrackWriter {
                track,
                ledger,
                encoder,
            });
        }
        Ok(Self {
            dir: dir.to_path_buf(),
            factory,
            splitter: SegmentSplitter::new(frames_per_segment),
            writers,
            mixed: Vec::new(),
            level: LiveLevel::new(WINDOW_FRAMES),
        })
    }

    /// Writes one microphone buffer and the system frames that belong under it, crossing as many
    /// segment boundaries as it has to. The parts that closed come back in track order within each
    /// part, alongside the failure that stopped it if there was one — see [`Written`].
    pub fn push(&mut self, mic: &[f32], sys: &[f32]) -> Written {
        debug_assert_eq!(mic.len(), sys.len());
        // Which track the strip is of: the mix in a meeting, and the microphone alone when that is
        // all there is to hear (`mono` on the phone, `mic` on a desktop recording without system
        // audio). It is the track a person hears, so it is the one that answers "am I being heard".
        let mixing = self.writers.iter().any(|writer| writer.track == Track::Mix);
        self.mixed.clear();
        if mixing {
            mix(mic, sys, &mut self.mixed);
        }
        let mut written = Written::default();
        for chunk in self.splitter.split(mic.len()) {
            let range = chunk.offset..chunk.offset + chunk.count;
            for writer in self.writers.iter_mut() {
                let source = match writer.track {
                    Track::Mono | Track::Mic => mic,
                    Track::Sys => sys,
                    Track::Mix => &self.mixed,
                };
                if let Err(error) = writer.encoder.write(&source[range.clone()]) {
                    written.failure = Some(error);
                    return written;
                }
            }
            // After the writers, and only for what they took: the strip is the app's answer to "is
            // this being captured", so it may never run ahead of the file (docs/09 화면 원칙 6).
            let heard: &[f32] = if mixing { &self.mixed } else { mic };
            self.level.push(&heard[range.clone()], &mut written.levels);
            if chunk.closes_segment {
                // A chunk only closes a segment by filling it exactly, so this is the length, to the
                // frame.
                let duration = self.frames_per_segment_sec();
                self.close_open_segments(duration, &mut written);
                if written.failure.is_some() {
                    return written;
                }
                if let Err(error) = self.open_next() {
                    // The part just closed is already in `written.parts` and goes out with the
                    // error. It is a whole segment on disk; the app files it and finalizes through
                    // it (docs/14 "헬퍼가 죽으면 앱이 마지막 파트까지를 finalize한다").
                    written.failure = Some(error);
                    return written;
                }
            }
        }
        written
    }

    /// The stop: closes the tail of every track. An empty tail is no part at all — a boundary that
    /// happened to land on the stop already filed everything.
    pub fn finish(mut self) -> Written {
        let frames = self.splitter.frames_in_segment();
        let mut written = Written::default();
        if frames == 0 {
            self.discard_open_segments();
            return written;
        }
        let duration = frames as f64 / f64::from(SAMPLE_RATE_HZ);
        self.close_open_segments(duration, &mut written);
        written
    }

    /// Leaves the open segment unfiled and unwritten. Only `--parts` in development reaches this:
    /// the app's rule for a helper that stops mid-segment is its own (docs/14 "헬퍼가 죽으면 앱이
    /// 마지막 파트까지를 finalize한다"), and it does not want a tail nobody announced.
    pub fn discard(mut self) {
        self.discard_open_segments();
    }

    fn frames_per_segment_sec(&self) -> f64 {
        self.splitter.frames_per_segment() as f64 / f64::from(SAMPLE_RATE_HZ)
    }

    /// Closes this segment on **every** track, appending a `part_done` for each one that made it and
    /// keeping the first failure.
    ///
    /// Every track, not up to the first failure: the writers are independent files, and one whose
    /// `finish` failed says nothing about the two after it — an ffmpeg that is dropped rather than
    /// finished still closes its stdin and still leaves a complete container behind, which would
    /// then be a playable file nobody hashed or announced (the [`Written`] rule again).
    ///
    /// The ledger advances for every track whatever happened, so a failed track does not leave its
    /// part numbers one behind its siblings' for the rest of the recording (docs/03 "같은 시간
    /// 구간이면 같은 번호").
    fn close_open_segments(&mut self, duration_sec: f64, into: &mut Written) {
        for writer in self.writers.iter_mut() {
            // Finishing is what writes the container's trailer; until it returns the file is not one
            // a reader can open, and the hash below would be of something nobody can play.
            let finished = writer.encoder.finish();
            let closed = writer.ledger.close(duration_sec);
            let result = finished.and_then(|()| digest(&self.dir.join(&closed.file)));
            match result {
                Ok((bytes, sha256)) => into.parts.push(Event::PartDone {
                    part: closed.part,
                    track: closed.track,
                    file: closed.file,
                    bytes,
                    sha256,
                    start_offset_sec: closed.start_offset_sec,
                    duration_sec: closed.duration_sec,
                }),
                Err(error) => {
                    if into.failure.is_none() {
                        into.failure = Some(error);
                    }
                }
            }
        }
    }

    fn open_next(&mut self) -> io::Result<()> {
        for writer in self.writers.iter_mut() {
            writer.encoder = self.factory.open(&self.dir.join(writer.ledger.open_file_name()))?;
        }
        Ok(())
    }

    fn discard_open_segments(&mut self) {
        for writer in self.writers.iter_mut() {
            let _ = writer.encoder.finish();
            let _ = fs::remove_file(self.dir.join(writer.ledger.open_file_name()));
        }
    }
}

/// docs/03 `parts[].sha256`, taken the moment the segment is closed.
fn digest(path: &Path) -> io::Result<(u64, String)> {
    let mut file = fs::File::open(path)?;
    let mut hasher = Sha256::new();
    let bytes = io::copy(&mut file, &mut hasher)?;
    Ok((bytes, format!("{:x}", hasher.finalize())))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::encode::pcm::PcmEncoderFactory;
    use crate::encode::{Encoder, EncoderFactory};

    fn temp_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("recly-recorder-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        dir
    }

    fn tone(frames: usize) -> Vec<f32> {
        (0..frames).map(|n| (n as f32 * 0.01).sin()).collect()
    }

    /// Deliverable 6: segment boundaries and part numbers, on three tracks, with the last part
    /// arriving on the stop.
    #[test]
    fn three_tracks_share_the_boundary_and_the_part_number() {
        let dir = temp_dir("boundary");
        let tracks = [Track::Mic, Track::Sys, Track::Mix];
        let mut recorder = Recorder::open(&dir, "base", 1.0, &tracks, Box::new(PcmEncoderFactory))
            .expect("open");

        // Two and a half seconds in quarter-second buffers: two boundaries, then a tail.
        let mut events = Vec::new();
        for _ in 0..10 {
            let written = recorder.push(&tone(4_000), &tone(4_000));
            assert!(written.failure.is_none());
            events.extend(written.parts);
        }
        assert_eq!(6, events.len(), "two parts on three tracks");
        events.extend(recorder.finish().parts);
        assert_eq!(9, events.len(), "and the tail the stop closed");

        let parts: Vec<(u32, &str, f64, f64)> = events
            .iter()
            .map(|event| match event {
                Event::PartDone { part, track, start_offset_sec, duration_sec, .. } => {
                    (*part, track.name(), *start_offset_sec, *duration_sec)
                }
                other => panic!("{other:?}"),
            })
            .collect();
        assert_eq!(
            vec![
                (1, "mic", 0.0, 1.0), (1, "sys", 0.0, 1.0), (1, "mix", 0.0, 1.0),
                (2, "mic", 1.0, 1.0), (2, "sys", 1.0, 1.0), (2, "mix", 1.0, 1.0),
                (3, "mic", 2.0, 0.5), (3, "sys", 2.0, 0.5), (3, "mix", 2.0, 0.5),
            ],
            parts,
        );
        let _ = fs::remove_dir_all(&dir);
    }

    /// Deliverable 6: `sha256` and `bytes` are the file's, taken after it was closed — the app
    /// verifies the upload against them.
    #[test]
    fn a_part_carries_the_hash_of_the_file_it_closed() {
        let dir = temp_dir("digest");
        let mut recorder = Recorder::open(&dir, "base", 10.0, &[Track::Mono], Box::new(PcmEncoderFactory))
            .expect("open");
        recorder.push(&tone(16_000), &vec![0.0; 16_000]);
        let events = recorder.finish().parts;

        let Event::PartDone { file, bytes, sha256, .. } = &events[0] else {
            panic!("{:?}", events[0]);
        };
        assert_eq!("base_p001_mono.m4a", file);
        // One second of 16 kHz, 16-bit mono under the PCM stand-in encoder.
        assert_eq!(32_000, *bytes);
        let (on_disk_bytes, on_disk) = digest(&dir.join(file)).expect("digest");
        assert_eq!(on_disk, *sha256);
        assert_eq!(on_disk_bytes, *bytes);
        assert_eq!(64, sha256.len());
        let _ = fs::remove_dir_all(&dir);
    }

    /// docs/09 화면 원칙 6: the strip is of the track the user hears, which in a meeting is the mix.
    /// One bar per tenth of a second of what was written, and nothing for the tenth still open.
    #[test]
    fn a_push_reports_the_level_of_the_track_the_user_hears() {
        let dir = temp_dir("levels");
        let tracks = [Track::Mic, Track::Sys, Track::Mix];
        let mut recorder = Recorder::open(&dir, "base", 10.0, &tracks, Box::new(PcmEncoderFactory))
            .expect("open");

        // A second of a full-scale microphone over a silent render endpoint: the mix is half of it
        // (docs/12 "합산 −6 dB 헤드룸"), and half is what the strip has to draw.
        let written = recorder.push(&vec![1.0; 16_000], &vec![0.0; 16_000]);

        assert_eq!(vec![0.5; 10], written.levels, "ten tenths of a second");
        assert!(written.parts.is_empty(), "and no boundary inside a ten-second segment");
        recorder.discard();
        let _ = fs::remove_dir_all(&dir);
    }

    /// With no mix to hear, the microphone is the track — the phone's `mono` recording, and a
    /// desktop one whose render endpoint was never opened.
    #[test]
    fn a_mono_recording_reports_the_microphone_it_wrote() {
        let dir = temp_dir("levels-mono");
        let mut recorder = Recorder::open(&dir, "base", 10.0, &[Track::Mono], Box::new(PcmEncoderFactory))
            .expect("open");

        // Half a window at a time: the strip's window is not a buffer, and it is carried across.
        let first = recorder.push(&vec![-0.4; 800], &vec![0.0; 800]);
        let second = recorder.push(&vec![0.1; 800], &vec![0.0; 800]);

        assert!(first.levels.is_empty(), "the window is not finished yet");
        assert_eq!(vec![0.4], second.levels, "the loudest of the two halves, unsigned");
        recorder.discard();
        let _ = fs::remove_dir_all(&dir);
    }

    /// A PCM encoder that fails the Nth `open`, or the Nth `finish`, and is otherwise real.
    struct Brittle {
        opens: std::cell::Cell<usize>,
        fail_open_from: usize,
        fail_finish_on: usize,
    }

    impl Brittle {
        fn open_fails_from(opened: usize) -> Self {
            Self { opens: std::cell::Cell::new(0), fail_open_from: opened, fail_finish_on: usize::MAX }
        }

        fn finish_fails_on(opened: usize) -> Self {
            Self { opens: std::cell::Cell::new(0), fail_open_from: usize::MAX, fail_finish_on: opened }
        }
    }

    impl EncoderFactory for Brittle {
        fn open(&self, path: &Path) -> io::Result<Box<dyn Encoder>> {
            let opened = self.opens.get();
            self.opens.set(opened + 1);
            if opened >= self.fail_open_from {
                return Err(io::Error::other("the disk filled up"));
            }
            let inner = PcmEncoderFactory.open(path)?;
            Ok(Box::new(BrittleEncoder {
                inner,
                fail_finish: opened == self.fail_finish_on,
            }))
        }

        fn name(&self) -> &'static str {
            "brittle"
        }
    }

    struct BrittleEncoder {
        inner: Box<dyn Encoder>,
        fail_finish: bool,
    }

    impl Encoder for BrittleEncoder {
        fn write(&mut self, samples: &[f32]) -> io::Result<()> {
            self.inner.write(samples)
        }

        fn finish(&mut self) -> io::Result<()> {
            self.inner.finish()?;
            if self.fail_finish {
                return Err(io::Error::other("the trailer could not be written"));
            }
            Ok(())
        }
    }

    /// The parts a boundary closed are already whole files on disk, so they are reported even though
    /// opening the *next* segment then failed. Losing them would leave three playable files that the
    /// app never files and recovery quarantines (docs/03).
    #[test]
    fn parts_that_closed_are_reported_even_when_the_next_segment_cannot_open() {
        let dir = temp_dir("open-fails");
        let tracks = [Track::Mic, Track::Sys, Track::Mix];
        let factory = Box::new(Brittle::open_fails_from(3));
        let mut recorder = Recorder::open(&dir, "base", 1.0, &tracks, factory).expect("open");

        let written = recorder.push(&tone(16_000), &tone(16_000));

        assert_eq!(3, written.parts.len(), "one per track, all of part 1");
        assert!(written.failure.is_some(), "and the failure that followed");
        for (event, track) in written.parts.iter().zip(tracks) {
            let Event::PartDone { part, file, bytes, .. } = event else {
                panic!("{event:?}");
            };
            assert_eq!(1, *part);
            assert_eq!(format!("base_p001_{}.m4a", track.name()), *file);
            // A whole second of audio, not a stub: this is a file the app can upload.
            assert_eq!(32_000, *bytes);
        }
        let _ = fs::remove_dir_all(&dir);
    }

    /// One track failing to close says nothing about the two after it — their files are finished and
    /// on disk either way, so they are hashed and announced, and only then does the failure go out.
    #[test]
    fn a_track_that_cannot_close_does_not_take_its_siblings_with_it() {
        let dir = temp_dir("finish-fails");
        let tracks = [Track::Mic, Track::Sys, Track::Mix];
        // The second track's first segment: `sys`.
        let factory = Box::new(Brittle::finish_fails_on(1));
        let mut recorder = Recorder::open(&dir, "base", 1.0, &tracks, factory).expect("open");

        let written = recorder.push(&tone(16_000), &tone(16_000));

        let files: Vec<&str> = written
            .parts
            .iter()
            .map(|event| match event {
                Event::PartDone { file, .. } => file.as_str(),
                other => panic!("{other:?}"),
            })
            .collect();
        assert_eq!(vec!["base_p001_mic.m4a", "base_p001_mix.m4a"], files);
        assert!(written.failure.is_some(), "and `sys` said so");

        // All three files are on disk and complete — which is the point: the two that could be
        // hashed were, rather than being abandoned because a third track failed between them.
        for track in tracks {
            let path = dir.join(format!("base_p001_{}.m4a", track.name()));
            assert_eq!(32_000, fs::metadata(&path).expect("on disk").len(), "{path:?}");
        }
        let _ = fs::remove_dir_all(&dir);
    }

    /// A stop that lands exactly on a boundary files nothing extra: an empty part would be a file
    /// the app uploads and nobody can play.
    #[test]
    fn a_stop_on_the_boundary_files_no_empty_part() {
        let dir = temp_dir("empty");
        let mut recorder = Recorder::open(&dir, "base", 1.0, &[Track::Mic], Box::new(PcmEncoderFactory))
            .expect("open");
        let closed = recorder.push(&tone(16_000), &vec![0.0; 16_000]);
        assert_eq!(1, closed.parts.len());
        assert!(recorder.finish().parts.is_empty());
        assert!(!dir.join("base_p002_mic.m4a").exists(), "the segment it opened and never used");
        let _ = fs::remove_dir_all(&dir);
    }
}
