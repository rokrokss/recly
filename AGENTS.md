# AGENTS.md

Instructions for coding agents working in this repository. Read this before touching anything.

## Overview

Recly is a multi-platform audio recorder with **no server**. Six clients — Galaxy Watch, Android
phone, Apple Watch, iPhone, macOS, Windows — record, upload to the *user's own* Google Drive, and
run a user-defined workflow (Drive upload · transcription · webhook). Configuration, keys and
workflow definitions stay on the device; only recordings go to Drive. There is no backend to
change, and no telemetry.

The design source of truth is [`docs/recly.md`](docs/recly.md) (Korean). The machine-readable
contract is [`spec/*.json`](spec/). Conventions are in [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Repository map

```
core/        Kotlin Multiplatform shared core (:core) — workflow engine, Drive resumable upload,
             webhooks, transcription providers, job queue, SQLDelight DB
android/     :app (phone) · :wear (Galaxy Watch) · :recording (shared recorder) · :datalayer
             (phone↔watch contract)
apple/       Rec.xcworkspace — RecKit (Swift package) + RecPhone / RecWatch / RecMac
windows/     app/ (Compose Desktop) + capture-helper/ (Rust, WASAPI) + bundled ffmpeg
spec/        JSON Schema + examples — the contract every client honors
skills/      the `recly` agent plugin — recly-notes (transcript → notes) · recly-notion (notes ↔ Notion)
scripts/     icon rendering, local webhook receiver
docs/        recly.md (design source of truth) · development.md · install.md · policy/privacy-policy.md (+ .ko.md) · design/icon.svg
```

## Build & test commands

The `Makefile` wraps every command with the flags that matter. Use it; do not hand-roll the
`xcodebuild` and `gradlew` invocations. Long form: [`docs/development.md`](docs/development.md).

Gradle needs JDK 21 and the Android SDK path — the Makefile exports both, override if yours differ:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
```

| Target | What it does |
|---|---|
| `make test` | core · android · windows unit tests (JVM) — the default check |
| `make android-test` | android unit tests only |
| `make windows-test` | windows shell unit tests only |
| `make apk` | phone debug APK |
| `make windows-run` | run the Windows shell on this host |
| `make windows-msi` | Windows MSI — **Windows hosts only** |
| `make helper-test` | Rust capture helper tests (`cargo test`) |
| `make core` | build the XCFramework and stage it into `apple/RecKit` — do this first on a Mac |
| `make mac` / `make mac-test` | build Recly Mac / run RecKit tests on macOS |
| `make ios` / `make watch` | simulator builds (`IOS_SIM=` / `WATCH_SIM=` override the device) |
| `make spec` | validate `spec/examples` against the JSON Schemas |
| `make help` | the full list |

Apple work requires macOS. `make core` before any Apple build or test — the XCFramework is a
Gradle output, not a checked-in artifact.

## Rules that are contracts

Breaking one of these breaks something outside the file you are editing.

- **The code base is English.** Identifiers, comments, doc comments, commit messages, test names,
  log event names and messages, module READMEs. `docs/` design documents are Korean.
- **User-facing text is localized through resources** (§7). Never hardcode a UI string in code.
  English is the base language, Korean the translation.
- **`docs/recly.md` section numbers are a contract.** Code comments cite rules as
  `docs/NN "subsection"` → §NN of `docs/recly.md`. Do not renumber a section or rename a cited
  subsection heading without updating every citation.
- **Log event names are stable identifiers** — `rec.*`, `shell.*`, `detect.*`, `xfer.*`,
  `job.step.start/ok/fail`, `sync.*`. Do not rename or translate them; four shells and the
  acceptance scenarios depend on the exact strings. **`CoreMessage` codes** are the same: they are
  written into `step_run.last_error` and rendered by the shells.
- **No new network endpoint without updating `docs/recly.md` §15.** §15 enumerates every path by
  which data leaves the device; a change that adds a network call must amend it in the same commit.
- **Never commit OAuth client files or keys** — `google-services.json`, `GoogleService-Info.plist`,
  `client_secret*.json`, `apple/Config/Local.xcconfig`, keystores. They are gitignored; keep it
  that way.
- **`spec/*.json` is the machine-readable contract.** After touching a schema or an example, run
  `make spec`, and update the clients that read it.

## Verification before finishing

Never report done on code that was not exercised.

1. `make test` — always.
2. `make spec` — if you touched `spec/`.
3. `make mac-test` (after `make core`) — if you touched `core/` or anything under `apple/`.
4. `make helper-test` — if you touched `windows/capture-helper/`.

## Gotchas

- **Simulator builds must go through `apple/scripts/build-sim.sh`** (`make ios` / `make watch`).
  It pins `ARCHS=arm64` on the command line: the core ships arm64-only simulator slices, and a
  command-line build setting is the only thing that reaches SwiftPM package targets. Calling
  `xcodebuild` on a simulator scheme without it fails inside RecKit with "cannot find type … in
  scope" for ReclyCore types — that is the x86_64 half of the build failing.
- **`-collect-test-diagnostics never`** is on every `xcodebuild` invocation. Keep it.
- **Windows MSI packaging and the real WASAPI capture path only work on Windows.** On macOS the
  Rust helper builds and tests with the Windows code behind `cfg(windows)`; `make windows-msi`
  will not run.
- **ffmpeg is bundled on Windows under LGPL v2.1+** — a shared build, run as a separate process,
  replaceable via `--ffmpeg <path>`. Do not statically link it and do not swap in a GPL build; see
  `THIRD-PARTY-NOTICES.md` and `windows/app/resources/common/THIRD-PARTY-ffmpeg.md`.
- The repository is AGPL-3.0-or-later with the additional permissions in `LICENSE-EXCEPTIONS.md`.
  New source files need no SPDX header — `REUSE.toml` covers the tree.
