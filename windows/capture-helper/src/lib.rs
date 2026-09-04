//! The Recly Windows capture helper (docs/14, ADR-005): WASAPI microphone + render-endpoint
//! loopback, resampled onto one timeline, drift-corrected, mixed, cut into ADR-006 segments and
//! announced to the app over the docs/14 JSON-line protocol.
//!
//! It is a library as well as a binary so the arithmetic — resampling, drift, mix, segment
//! boundaries, the protocol, sha256 — can be tested on the macOS development host, where none of the
//! Windows half exists (docs/lanes/M6-L2 "환경 제약").

pub mod capture;
pub mod detect;
pub mod encode;
pub mod pipeline;
pub mod protocol;
pub mod recorder;
pub mod selftest;
