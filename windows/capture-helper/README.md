# `windows/capture-helper` — the Recly capture helper (Rust)

docs/14 "캡처"·"감지" · the docs/03 audio/segment rules · ADR-005 · ADR-006 · ADR-019.
`windows/app` (Compose Desktop) spawns this binary and talks to it over the docs/14 JSON line
protocol. All of the audio is captured, resampled, mixed, split into segments and encoded here, and
the app takes `part_done` and writes it into the meta.

```
Mic (WASAPI shared mode, event driven)      ─┐
                                             ├─► DriftCompensator ─► mic / sys / mix segments → .m4a
System (default render endpoint loopback,   ─┘                       (900-second boundaries, the same
        a timer inserts silence frames                               part number on all three tracks)
        through silent stretches)
```

## Protocol

`windows/app/src/main/kotlin/app/recly/windows/helper/HelperProtocol.kt` is the contract. One JSON
per line.

| Direction | Line |
|---|---|
| App → helper | `{"command":"start","dir":…,"base":…,"segmentSec":900,"tracks":["mic","sys","mix"]}` |
| App → helper | `{"command":"stop"}` · `{"command":"detect","on":true}` |
| Helper → app | `{"event":"part_done","part":1,"track":"mic","file":…,"bytes":…,"sha256":…,"startOffsetSec":0.0,"durationSec":900.0}` |
| Helper → app | `{"event":"mic_in_use","app":"Zoom.exe","inUse":true}` · `{"event":"error","message":…,"fatal":true}` |

- File names are built from the `base` the app gave (docs/03 "이름 규칙"). The helper does not name
  anything.
- There is no `recordingId` in `part_done` — because `start` does not give one. One helper process
  handles one recording, so the app already knows which recording it is.
- When `stop` arrives (or the app closes stdin), it closes the last segment, sends `part_done` and
  **exits**. stdout closing is the signal the app waits for (docs/14 — "if the helper dies the app
  finalizes through the last part").
- stdout is the protocol, stderr is the log.

## Encoding (ADR-019)

The default is the **bundled ffmpeg** (`-c:a aac -b:a 32k`, 16 kHz mono = ADR-006). One process is
brought up per segment and PCM is fed into its stdin — because the container has to be **complete**
at the boundary before a `sha256` can be produced. A Media Foundation path (`--encoder mf`) is
written as well but is not the default. The reason is in ADR-019.

The MSI carries an LGPL dynamically linked ffmpeg build (`--disable-gpl`, `--disable-nonfree`)
alongside the helper. To use another path, `--ffmpeg <path>`.

## Running on the dev host (macOS)

All of the Windows-only code sits behind `cfg(windows)`, and everything else (resampling, drift,
mixing, segments, sha256, protocol) builds and tests anywhere.

```bash
cd windows/capture-helper
cargo test                                     # the OS-independent part (32 of them)
cargo check --target x86_64-pc-windows-msvc    # WASAPI/MF compile check (linking is CI)
cargo test --test drift -- --nocapture         # the 1-hour drift harness numbers
```

There is no capture device, so `--fake-source sine` generates mic (48 kHz) and system (44.1 kHz)
sine waves **in real time**. That runs the whole path (resample → drift → mix → segment → encoder →
`part_done`).

```bash
cargo build
{ echo '{"command":"start","dir":"/tmp/rec","base":"b","segmentSec":900,"tracks":["mic","sys","mix"]}'
  sleep 4; echo '{"command":"stop"}'; } \
| ./target/debug/recly-capture-helper --fake-source sine --segment-sec 1.5 --encoder ffmpeg
```

### Running the app's protocol tests against the real binary

`HelperClientTest` brings up `windows/app/dev/FakeHelper.java` by default. Point a system property
at the real helper and the same test runs against this binary (`FakeHelperCommand` translates the
fake's arguments into helper flags).

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :windows:app:test --tests '*HelperClientTest*' \
  -Drecly.captureHelper="$PWD/windows/capture-helper/target/debug/recly-capture-helper --encoder ffmpeg"
```

The value is not a path but **a command line** (the same rule as the app's
`RECLY_CAPTURE_HELPER`). `WindowsRecorderTest` and `ShellFlowTest` are not run this way — the real
helper leaves real audio files behind, while those tests expect the directory to hold nothing but
`meta.json`.

### Development-only flags

| Flag | Meaning |
|---|---|
| `--fake-source sine` | A synthetic sine wave instead of WASAPI. The default on non-Windows builds |
| `--segment-sec 1.5` | Overrides the `segmentSec` of `start` (fractions allowed) |
| `--parts N` | End capture after N parts. A segment left open is not reported |
| `--die` | After `--parts`, exit 9 without waiting for `stop` — the app's helper-death path |
| `--noise` / `--hang` | Print non-protocol lines / do not answer `stop` |
| `--mic-in-use Zoom.exe` | The capture session band. Reports this name on `detect on` |
| `--encoder pcm\|ffmpeg\|mf` | `pcm` is container-less s16le (tests and development only) |
| `--version` | Prints one line with the name and version and exits — the app uses it at startup to check the path and version of the bundled helper (M6-L3) |

## Windows run verification

The user has no Windows PC (lead decision 2026-08-28), so the `windows-latest` job in
`.github/workflows/windows.yml` stands in on every `v*` tag (or a manual run): `cargo test` +
`cargo build --release` (the real WASAPI/MF link) + `--self-test` + the binary artifact, and
`:windows:app:test`.

```
recly-capture-helper.exe --self-test
```

reports, without capturing, ① the **negotiated format** on the default capture/render endpoints
(whether it is engine-converted float32 mono, or an endpoint mix format that has to be decoded
directly), ② the MF AAC sink writer, tried both in the **ADR-006 format (16 kHz mono 32 kbps)** and
in a **documented allowed format (48 kHz mono 128 kbps)**, and ③ `ffmpeg -version`. It does not
`Start` the stream, so there is neither actual capture nor a recording indicator. The exit code is
always 0 — it is a report, not a gate.

Format negotiation: in shared mode it asks for **32-bit float mono** at the endpoint rate with
`AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY` and leaves the
conversion to the engine; if `Initialize` refuses, it takes the mix format as it comes and works out
the layout (float32 · int16 · int24 packed · int32 · 24-in-32) from
`wFormatTag`/`SubFormat`/`wValidBitsPerSample` to decode it directly (`src/capture/format.rs` —
pure functions, so it is tested on macOS).

What is left once there is real hardware (docs/20 "Windows 보류 항목"): the real loopback track
separation for Teams and browser Meet, one hour in four parts, mic-in-use detection on real
hardware.
