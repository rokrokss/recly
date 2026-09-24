<div align="center">

<img src="docs/design/icon.svg" width="120" alt="Recly icon">

# Recly

**A Plaud-style AI notetaker, running on the watch and phone you already own.<br>Your Drive keeps the audio, your own key transcribes it, your own AI writes the notes.**

[Download](#get-recly) · [Install guide](docs/install.md) · [Privacy](https://recly.dev/policy/privacy-policy) · [Issues](https://github.com/rokrokss/recly/issues) · [한국어](README.ko.md)

[![License: AGPL-3.0-or-later](https://img.shields.io/badge/license-AGPL--3.0--or--later-0F62FE)](LICENSE)
[![Latest release](https://img.shields.io/github/v/release/rokrokss/recly?include_prereleases&label=release)](https://github.com/rokrokss/recly/releases)
[![Downloads](https://img.shields.io/github/downloads/rokrokss/recly/total)](https://github.com/rokrokss/recly/releases)
[![Stars](https://img.shields.io/github/stars/rokrokss/recly?style=flat)](https://github.com/rokrokss/recly/stargazers)

</div>

<p align="center"><img src="docs/design/flow.svg" width="100%" alt="Press record on your watch, phone, Mac or Windows PC → the phone or PC uploads to your Google Drive and transcribes with your own key → anything, with your own AI"></p>

A Plaud or a NotePin sells you three things: a recorder, transcription, and AI notes. You already
wear the recorder, and you already pay for the AI. Recly supplies the one piece you are missing,
transcription on a supported device or with **your own** API key, and keeps the original audio in
**your** Google Drive. There is no Recly server, no bot joining your call, and no monthly fee.

## Why Recly

- **The recorder you already wear.** A double press of the Galaxy Watch's home key starts
  recording; the watch hands the audio to your phone, and the phone does the rest. Six clients
  (Galaxy Watch, Android, Apple Watch, iPhone, macOS, Windows) record the same way and run the same
  recording flow; desktops capture your mic and the other side of a Zoom, Teams or Meet call as
  separate tracks.
- **Your Drive is the only storage.** Recordings go to a folder like `recly/2026/2026-09/` in
  your own Google Drive, using the narrowest permission Google offers (`drive.file`), and are never
  deleted before the upload is confirmed. Recly cannot see your files. It has no server to see them with.
- **Files are the interface.** Your agent reads transcription results from your Drive. Settings offer
  on-device transcription, an external API using your key (AssemblyAI, Clova, Deepgram, OpenAI, Azure
  and more), or upload only. Speaker separation is automatic when supported. Notes are your agent's
  job, not a paid tier.
- **Nothing covert.** Recording is always visible. Desktops detect a meeting, ask, and only then
  record. No analytics, no crash reporting, no update pings.

## What runs where

| Step | Where it happens | What leaves your device |
|---|---|---|
| Recording | Your watch, phone or desktop | Nothing. A watch hands the audio to your paired phone, and only there. |
| Storage | Your Google Drive | The audio parts and a small metadata file, to your own account. |
| Transcription | On device, or a provider you chose with your own key | Local transcription sends no audio to an ASR service. External mode sends the joined audio to the selected provider. Results are written next to the recording in Drive. |
| Notes | Your own AI agent (Claude, ChatGPT, Codex, ...) | The agent reads the transcript from your Drive and writes the notes to your Notion. Recly is not involved. |
| Processing settings and API keys | Your device | Nothing is synced. Settings → Export/Import moves configuration only; enter keys separately on each device. |

The full list of every network path, with nothing left out, is in the
[privacy policy](https://recly.dev/policy/privacy-policy).

## Get Recly

Store releases are coming soon. Until then, pre-release builds are on the
[Releases](https://github.com/rokrokss/recly/releases) page.

| Platform | Requires | Today | Soon |
|---|---|---|---|
| Android phone | Android 14+ | APK from [Releases](https://github.com/rokrokss/recly/releases) | Google Play |
| Galaxy Watch | Wear OS 5+ | APK from Releases, installed over ADB ([how](docs/install.md#galaxy-watch)) | Google Play |
| iPhone | iOS 17+ | Build from source | App Store · TestFlight |
| Apple Watch | watchOS 10+ | Build from source, with the iPhone app | App Store |
| macOS | macOS 14.4+ | Build from source | Notarized DMG · Homebrew |
| Windows | Windows 11 | MSI from [Releases](https://github.com/rokrokss/recly/releases) (unsigned, see [guide](docs/install.md#windows)) | Signed MSI · winget |

The [install guide](docs/install.md) has the step-by-step for each platform, including sideloading
a watch and getting past the Windows SmartScreen warning. Building from source is in
[docs/development.md](docs/development.md).

## How it works

1. **Record.** Tap Record on the watch (a Galaxy Watch can bind it to a double press of the home
   key), the menu-bar icon on a Mac, or the tray icon on Windows. Desktops notice when a meeting
   app opens your mic and ask whether to record.
2. **Upload.** When you stop, the recording goes to your Drive as it is. Watches hand off to the
   phone first. If the network is down, it waits and retries. The original is never deleted before
   Drive has acknowledged it.
3. **Transcribe and finish.** After the original upload, the phone or desktop transcribes and
   uploads the result. Choose the transcription method and storage folder in Settings. Turning
   transcription off keeps the original upload.

New installations default to on-device transcription where the app ships an on-device engine. The
Apple adapter requires iOS/macOS 26, supported hardware and language assets. Android, Windows and
Apple devices below OS 26 have no engine yet, so new installations there start with transcription
off and do not offer on-device; select an external API to transcribe. Local transcription has no automatic
cloud fallback.

The interface supports English, Korean, Japanese, Simplified and Traditional Chinese, Spanish,
French, German, Portuguese, Arabic, Hindi and Russian. Transcription offers 20 language choices,
filtered by the provider or the device's speech engine. Desktop recordings always use meeting mode
(microphone plus system audio), with automatic microphone selection.

Settings and transcript formats are documented in [`spec/`](spec/).

## Notes: bring your own agent

Recly's pipeline ends at the transcript on purpose. Turning it into notes is something your
existing AI subscription already does well, so Recly ships **skills** for your agent instead of a
metered feature. Drive stays the archive the app writes and the agent only reads it. The notes,
and every edit you make to them later, live in your Notion.

| Skill | What it does |
|---|---|
| [`recly-notes`](skills/recly-notes/SKILL.md) | Finds a recording (the latest, or the one you name), reads its transcript and writes minutes, a decision log, interview or lecture notes, or a memo |
| [`recly-notion`](skills/recly-notion/SKILL.md) | Keeps those notes in a "Recly Recordings" database in your Notion, one page per recording, and finds them again later |

```bash
npx skills add rokrokss/recly            # any agent that supports Agent Skills
# or, inside Claude Code:
/plugin marketplace add rokrokss/recly
/plugin install recly@recly
```

The plugin registers Notion's hosted MCP server; run `/mcp` once to sign in. Using the Claude or
ChatGPT apps instead of a coding agent? The same five files work there too. Setup for each is in
[skills/README.md](skills/README.md).

Then ask: *"Make minutes from the latest recording and put them in Notion"* or *"What did we
decide about pricing last week?"*. Don't like the format? Edit the skill file. That is the point.

## Clients

| Client | Built with | What it does |
|---|---|---|
| Galaxy Watch (Wear OS) | Kotlin · Wear Compose | Record, hand off to the Android phone |
| Android phone | Kotlin · Compose | Record, configure processing, Google sign-in |
| Apple Watch | SwiftUI | Record, hand off to the iPhone |
| iPhone | SwiftUI | Record, configure processing, Google sign-in |
| macOS | SwiftUI menu-bar app | Meeting capture (mic + system audio), process recordings |
| Windows | Compose Desktop + Rust capture helper | Meeting capture, process recordings |

All six share one Kotlin Multiplatform core: the fixed recording flow, resumable Drive uploads,
transcription adapters and the job queue.

<p align="center">
  <img src="docs/design/screenshots/galaxy-watch.png" height="150" alt="Galaxy Watch">&nbsp;&nbsp;
  <img src="docs/design/screenshots/android-phone.png" height="300" alt="Android phone">&nbsp;&nbsp;
  <img src="docs/design/screenshots/mac.png" height="250" alt="macOS menu bar">&nbsp;&nbsp;
  <img src="docs/design/screenshots/iphone.png" height="300" alt="iPhone">&nbsp;&nbsp;
  <img src="docs/design/screenshots/apple-watch.png" height="150" alt="Apple Watch">
</p>

## Privacy

Recly has no server. The only places data can go are your Google Drive, the transcription
provider you chose, and your own paired watch or phone. The
[privacy policy](https://recly.dev/policy/privacy-policy) lists every one of those paths, and
[docs/recly.md §15](docs/recly.md#15-프라이버시데이터-흐름-구-docs15) is the engineering contract
behind it: any change that adds a network call must update that section first.

## Contributing, security, license

- **Contributing**: bugs, questions and ideas all go to
  [Issues](https://github.com/rokrokss/recly/issues/new/choose). Conventions are in
  [CONTRIBUTING.md](CONTRIBUTING.md). There is no CLA.
- **Security**: report vulnerabilities privately via [SECURITY.md](SECURITY.md).
- **License**: [AGPL-3.0-or-later](LICENSE), with two additional permissions in
  [LICENSE-EXCEPTIONS.md](LICENSE-EXCEPTIONS.md) so the apps can be distributed through app stores.
  "Recly" and its icon are trademarks; see [TRADEMARK.md](TRADEMARK.md). Third-party components are
  listed in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

## For developers

```
core/        Kotlin Multiplatform shared core (:core)
android/     :app (phone), :wear (Galaxy Watch), :recording (shared recorder), :datalayer (phone↔watch contract)
apple/       Rec.xcworkspace — RecKit (Swift package) + RecPhone / RecWatch / RecMac
windows/     app/ (Compose Desktop) + capture-helper/ (Rust, WASAPI)
spec/        JSON Schema + examples — the contract every client honors
skills/      the `recly` agent plugin — recly-notes (transcript → notes) · recly-notion (notes ↔ Notion)
scripts/     icon rendering
docs/        recly.md (the design source of truth) + install.md + development.md + policy/
```

| Document | Contents |
|---|---|
| [docs/development.md](docs/development.md) | Build and test every client, values filled in locally (OAuth client IDs), cutting a release |
| [docs/recly.md](docs/recly.md) | **The design source of truth** (Korean). Architecture, the internal step model, recording and retention, processing settings and secrets, auth, transcription, per-platform notes, privacy, open decisions. Its section numbers are a contract: code comments cite them as `docs/NN "…"` |
| [spec/](spec/) | Machine-readable contract: `recording-settings.schema.json`, `recording.meta.schema.json`, `transcript.schema.json`, `examples/` |
| [skills/README.md](skills/README.md) | The `recly` plugin: what the two skills do and how to set them up in Claude Code, the Claude app and ChatGPT |
| [AGENTS.md](AGENTS.md) | Orientation for coding agents working in this repository |
