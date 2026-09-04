//! The parts of the helper that are arithmetic rather than Windows: resampling, drift, the mix and
//! the segment boundary. They are what `cargo test` checks on the macOS development host
//! (docs/lanes/M6-L2 "환경 제약").

pub mod drift;
pub mod level;
pub mod mix;
pub mod resample;
pub mod segment;

/// ADR-006. Every track is written at this rate, whatever the endpoints run at.
pub const SAMPLE_RATE_HZ: u32 = 16_000;
