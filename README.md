# Recly

**Whichever device you record on, the workflow you defined runs when the recording ends.**

An honest recorder that records and uploads, nothing more. The original audio stays in **your own
Google Drive**, and everything after that is your workflow's business. Six clients — watches,
phones, desktops — run the same kind of workflows, and **Recly has no server.**

Repository: <https://github.com/rokrokss/recly> · code identifier `recly` (Android `app.recly`,
Kotlin `recly.core`).

## Principles

1. **The device that recorded runs the workflow.** Watches hand off to phones. There is no server.
2. **Configuration belongs to the device.** Workflow definitions, transcription keys and the
   default pick all live locally and are never synced. Moving them between devices is Settings'
   **export/import** (a workflow JSON file). Only recordings go to Drive.
3. **Originals are never deleted before the upload is acknowledged.**
4. **Files and webhooks are the interface.** Transcription is an optional step you run with your
   own key; everything from summarization on belongs to your agent (see "Summaries: bring your
   own agent"). The default is "my Drive + my automation".
5. **No covert mode.** Recording is always visible, and desktops go detect → confirm → record.

## Clients

| Client | Shell | Role |
|---|---|---|
| Galaxy Watch (Wear OS) | Kotlin · Wear Compose | Record → hand off to the Android phone |
| Android phone | Kotlin · Compose | Record · edit workflows · run · Google auth |
| Apple Watch (watchOS) | SwiftUI | Record → hand off to the iPhone |
| iPhone | SwiftUI | Record · edit workflows · run · Google auth |
| macOS | SwiftUI menu-bar app | Meeting capture (mic + system audio) · run |
| Windows | Compose Desktop + Rust capture helper | Meeting capture · run |

The shared core is one Kotlin Multiplatform module (`core/`): the workflow engine, Drive
resumable uploads, webhooks, transcription provider adapters, and the job queue.

## Summaries: bring your own agent

Recly's pipeline ends at **Drive upload + transcription (`transcribe`)**. Transcription is in the
pipeline only because it is the one thing a subscription agent (Claude, Codex, …) cannot do.
Summarization is text→text — something your agent already does well — and there is no reason to
meter it through a BYO API key when your existing subscription covers it.

So summarization is a **skill**, not a step: install
[skills/recly-notes/](skills/recly-notes/SKILL.md) into your agent (for Claude Code, copy it to
`~/.claude/skills/recly-notes/`) and "summarize yesterday's meeting" reads the transcript from
your Drive `recly/` folder and writes the minutes (`{base}.summary.md`) next to the original.
Any Drive access your agent has works — a Drive connector/MCP, a locally synced folder, a file
you downloaded yourself. Don't like the minutes format? Edit the skill file — that is the point
of the arrangement.

## Repository layout

```
core/        Kotlin Multiplatform shared core (:core)
android/     :app (phone), :wear (Galaxy Watch), :recording (shared recorder), :datalayer (phone↔watch contract)
apple/       Rec.xcworkspace — RecKit (Swift package) + RecPhone / RecWatch / RecMac
windows/     app/ (Compose Desktop) + capture-helper/ (Rust, WASAPI)
spec/        JSON Schema + examples — the contract every client honors
skills/      skills for the user's agent — recly-notes (transcript → minutes)
scripts/     icon rendering, local webhook receiver
docs/        recly.md (the design source of truth) + policy/privacy-policy.md + design/icon.svg
```

## Documents

| Document | Contents |
|---|---|
| [docs/recly.md](docs/recly.md) | **The design source of truth** (Korean). Architecture · workflow contract · recording/retention/deletion · webhooks · storage & secrets · auth · i18n · transcription · design system · core · per-platform implementation · privacy · verification status · open decisions. Section numbers are a contract: code comments cite them as `docs/NN "…"` |
| [spec/](spec/) | The machine-readable source of truth — `workflow.schema.json`, `recording.meta.schema.json`, `webhook.payload.schema.json`, `transcript.schema.json`, `examples/` |
| [docs/policy/privacy-policy.md](docs/policy/privacy-policy.md) | Privacy policy (ko/en) — the public document for OAuth production review and store submissions |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Code and documentation conventions |

## Build · test

The `Makefile` wraps every command below with the flags that matter (JDK 21 and the Android SDK
path for Gradle, `ARCHS=arm64` for simulators, `-collect-test-diagnostics never` for xctest):

```bash
make test        # core · android · windows unit tests (JVM)
make core        # build the XCFramework and stage it into apple/RecKit (do this first on a Mac)
make mac         # build Recly Mac          make mac-test   # RecKit tests on macOS
make ios         # Recly on the iOS simulator        make watch      # Recly Watch on the watch simulator
make apk         # phone debug APK          make spec       # validate spec/examples
make help        # the full list — IOS_SIM / WATCH_SIM override the simulator names
```

What the targets run, if you need the commands themselves. Gradle needs JDK 21 and the Android
SDK path:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
```

**Core · Android · Windows (JVM)** — the unit tests, in one go:

```bash
./gradlew :core:jvmTest :android:app:testDebugUnitTest :android:wear:testDebugUnitTest \
          :android:recording:testDebugUnitTest :android:datalayer:testDebugUnitTest :windows:app:test
./gradlew :android:app:assembleDebug          # phone APK
./gradlew :windows:app:run                    # run the Windows shell on the dev host
```

**Apple** (requires macOS) — build the XCFramework and stage it into RecKit first:

```bash
./apple/scripts/build-core.sh                 # :core:assembleXCFramework → apple/RecKit/Frameworks/
./apple/scripts/setup-local-signing.sh        # once per Mac; keeps Keychain grants across rebuilds
xcodebuild -workspace apple/Rec.xcworkspace -scheme RecKit -destination 'platform=macOS' -collect-test-diagnostics never test
xcodebuild -workspace apple/Rec.xcworkspace -scheme 'Recly Mac' -destination 'platform=macOS' build
./apple/scripts/build-sim.sh Recly "iOS Simulator" "iPhone 17 Pro" build
./apple/scripts/build-sim.sh "Recly Watch" "watchOS Simulator" "Apple Watch Series 11 (46mm)" build
```

Simulator builds go through `build-sim.sh`, which pins `ARCHS=arm64` on the command line: the
core ships arm64-only simulator slices, and a command-line build setting is the only thing that
reaches SwiftPM package targets (project-level `ARCHS`/`EXCLUDED_ARCHS` and arch-qualified
destinations do not). Calling `xcodebuild` on a simulator scheme without it fails inside RecKit
with "cannot find type … in scope" for ReclyCore types — the x86_64 half of the build.

**Windows capture helper** (Rust):

```bash
cd windows/capture-helper && cargo test          # rules, boundaries, sha256, drift harness
cargo build --release                            # the real capture binary, on Windows
```

**Spec validation · local webhook receiver** (Node):

```bash
cd spec && npm ci && npm run validate            # validate the examples against the JSON Schemas
node scripts/webhook-receiver.mjs --port 8787 --secret whsec_…   # a receiver that checks signature & schema
```

**Android phone · Galaxy Watch, from a GitHub release.** Debug APKs go up as pre-releases
(`v0.0.1-dev`, [releases](https://github.com/rokrokss/recly/releases)) — one for the phone,
one for the watch. Both need Android 14 / Wear OS 5 or later; while the repository is private,
downloading needs a GitHub login on the device.

- *Phone*: open the release in the phone's browser, download the phone APK, open it, and allow
  installs from that source. Play Protect warns about the debug signature — choose "Install
  anyway". Google sign-in works: the debug keystore's SHA-1 is the one registered on the OAuth
  client.
- *Watch*: Wear OS has no browser and no APK installer, so the watch APK can only arrive over
  ADB. On the watch, Settings → About watch → Software → tap the version seven times, then
  Developer options → turn on **ADB debugging** and **Wireless debugging**, and pair from a
  machine on the same Wi-Fi:

  ```bash
  adb pair <pairing IP:port> <code>       # Wireless debugging → "Pair new device"
  adb connect <IP:port>                   # the address on the Wireless debugging screen
  adb -s <IP:port> install -r Recly-Watch-*.apk
  ```

  Without a computer: download the watch APK on the phone and push it with an ADB-based
  installer app from Play (for instance "Wear Installer 2"); the watch settings above are the
  same. The watch cannot upload on its own — it hands recordings to the phone app, so install
  both, and take Galaxy Wearable off the phone's background-battery limits or the Bluetooth
  link drops.
- *Double press home key → record*: on the watch, Settings → Advanced features → Customise keys →
  Double press home key → Open app, and choose **"Recly Record"** (the second launcher entry;
  "Recly" only opens the app).

To cut a release: `make apk` and `./gradlew :android:wear:assembleDebug`, then
`gh release create v0.0.1-dev <phone.apk> <watch.apk> --target main --prerelease`.

**Releases**: macOS via `apple/scripts/release-mac.sh` (Developer ID + notarization + DMG);
the Windows MSI via `./gradlew :windows:app:packageMsi` (Windows hosts only — see
`windows/README.md`).

**Icons**, when regenerating (macOS only): `swift scripts/render-icons.swift`, then
`python3 scripts/make-ico.py --check windows/app/src/main/icons/recly.ico`.

## Values filled in locally

Client files (`google-services.json`, `GoogleService-Info.plist`, `client_secret*.json`) and
OAuth client IDs are never committed. While either Apple app's `Info.plist` `GIDClientID` is a
placeholder, its sign-in button is disabled and stopped recordings park their jobs as
`NEEDS_AUTH`.

| App | Info.plist | Client type | Bundle ID |
|---|---|---|---|
| RecMac | `apple/RecMac/RecMac/Info.plist` | iOS | `app.recly.mac` |
| RecPhone | `apple/RecPhone/RecPhone/Info.plist` | iOS | `app.recly` |

Create a client of that type and bundle ID in the GCP console, then copy
`apple/Config/Local.xcconfig.example` to `apple/Config/Local.xcconfig` (gitignored) and fill in
the four values — each app's issued ID and its **reversed client ID**
(`com.googleusercontent.apps.{number}-{hash}`). Both `Info.plist` files read them as build
settings, so nothing you fill in shows up in the tracked tree. The consent screen must carry
exactly one scope:
`drive.file` ([docs/recly.md §6](docs/recly.md#6-인증-구-docs06)).

## Status

Every code milestone is on main — the core (KMP), Android phone & Galaxy Watch, macOS,
iPhone & Apple Watch, Windows, transcription (`transcribe`, `schema: 3`), en/ko localization,
the "Blueprint" UI, app icons, and workflow export/import. Summarization is an agent skill, not
a pipeline step (see "Summaries: bring your own agent" above).

What has been verified on real hardware and what still waits on devices or accounts is in
[docs/recly.md §20](docs/recly.md#20-검증-상태-구-docs20); the remaining user decisions
(trademark, license, pricing, legal review) are in
[§열린 결정](docs/recly.md#열린-결정).
