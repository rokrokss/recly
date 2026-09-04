# `windows/app` — the Recly Windows shell (Compose Desktop)

docs/14 "앱" · the Windows section of docs/06 · ADR-005. Tray, auth (loopback PKCE), runner,
workflow editor window, settings, meeting detection & notification (M6-L3). This module does not
capture audio — it spawns `windows/capture-helper` (Rust, M6-L2) and talks to it over the docs/14
JSON line protocol. MSI packaging and signing are in `windows/README.md`.

## Development environment (M6-L1 "environment constraints")

The development machine is macOS. The Windows-only parts sit behind interfaces, and on macOS a stub
is chosen instead.

| Feature | Windows | macOS (dev host) |
|---|---|---|
| Secret & token storage | Credential Manager (`WindowsCredentialStore`, JNA) | `{dataDir}/dev-secure-store.json` — **no encryption, development only** |
| Launch at login | `HKCU\…\Run` (`WindowsRunKey`) | no-op, shown as disabled in Settings |
| Data directory | `%LOCALAPPDATA%\Recly` | `~/Library/Application Support/app.recly.windows` |
| Capture helper | `recly-capture-helper.exe` from the MSI resources | none → tray "No capture helper" (the fake helper below) |
| Running apps · window titles | process table + `EnumWindows` | always empty → replaced by `RECLY_DETECT_PROCESSES` |
| Microphone "allow desktop apps" | `HKCU\…\ConsentStore\microphone` | always `UNKNOWN` (no guidance text appears) |
| Meeting notification | tray balloon (`TrayIcon.displayMessage`) | the same API — it shows up in the macOS Notification Center |

Credential Manager, the Run key and the MSI install cannot be verified by running them (compile
only). Real-hardware Windows verification is on the user's PC.

## Detection (docs/14 "감지", M6-L3)

The rules are pure Kotlin and the same state machine as macOS (`detect/MeetingDetectionRule.kt` ↔
`apple/RecKit/…/MeetingDetector.swift`): mic in use × a meeting app × a 600-second cooldown, one
notification per meeting, 60 seconds idle → "End the recording?" (not an automatic stop). Automatic
recording defaults to off (ADR-011).

The mic signal **comes from a different helper at different times**. When no recording is running,
the detect-only helper reports it with `detect on`; once a recording starts, the `mic_in_use` of the
**recording helper** is used — the helper leaves its own process out of the session list, and that
is the only way to tell a quiet meeting apart from the mic Recly itself opened.

The handover **waits**, one direction at a time (`detect/Detection.kt`): before bringing up its own
helper the recorder calls `yieldToRecorder()` and waits for the detect-only helper to close and its
reader to finish, and once its own helper's stdout ends it hands ownership back with
`resume(token)`. Ownership is **a token per recording session** — a deferred stop
(`StopResult.Deferred`) leaves a consumer coroutine behind, and by the time that coroutine reaches
EOF later, the next recording may already have taken the helper. A `resume` or `mic_in_use` whose
token does not match is dropped (`detect.resume.stale`, `detect.mic.stale`). There is no interval in
which both helpers are alive at once.

Notifications go out carrying **a token** too. When the meeting ends, or the mic comes back, or the
recording state changes, that suggestion becomes void on the spot (it does not wait for the next
2-second tick — `detect.notify.stale`), and a balloon or tray item pressed too late does nothing
(`detect.act.stale` / `detect.act.moot`).

The notification is an AWT tray balloon. **It has no buttons** (a toast library with action buttons
is a Windows-only runtime dependency, which cannot be built or verified here). So clicking the
balloon is the acceptance, and the certain path is the tray menu item "Record the detected meeting".

To see the detection path with your own eyes on the dev host, use the fake helper's mic knob
together with the process list band:

```bash
export RECLY_DETECT_PROCESSES="Zoom.exe"
export RECLY_CAPTURE_HELPER="$(/usr/libexec/java_home -v 21)/bin/java $PWD/windows/app/dev/FakeHelper.java micInUse=Zoom.exe micIdleAfter=10 parts=1 sec=2.0"
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :windows:app:run
```

## OAuth client (docs/06)

Windows/JVM uses a **Desktop app** type client. The `client_secret` of the desktop type is not a
secret according to Google's documentation, but it differs per developer, so it is not committed.

1. GCP console → APIs & Services → Credentials → Create credentials → **OAuth client ID**.
2. Application type: **Desktop app**. The name is free (e.g. `Recly Windows`). Do not enter a
   redirect URI — the desktop type allows every `http://127.0.0.1:{any port}`.
3. The consent screen scope must be `drive.file`, that one only (ADR-009). Adding another scope
   brings a verification procedure with it.
4. Put the issued ID/secret into the repository root's `local.properties` (a `.gitignore` target):

   ```properties
   google.desktopClientId=1234567890-xxxx.apps.googleusercontent.com
   google.desktopClientSecret=GOCSPX-xxxx
   ```

   In CI and the like, the environment variables `REC_GOOGLE_DESKTOP_CLIENT_ID` /
   `REC_GOOGLE_DESKTOP_CLIENT_SECRET` are read as well.
5. With no values the build still works, `OAuthConfig.isPlaceholder` becomes true and the sign-in
   item is disabled. Recording and job creation still work in that state, and the job stops at the
   first upload step as `NEEDS_AUTH` (tray "Sign-in needed").

## The fake capture helper

`dev/FakeHelper.java` speaks the docs/14 protocol and writes no audio. It runs on the JDK
single-file launcher, so it needs neither a build nor a classpath. `HelperClientTest` and
`WindowsRecorderTest` use it.

```bash
export RECLY_CAPTURE_HELPER="$(/usr/libexec/java_home -v 21)/bin/java $PWD/windows/app/dev/FakeHelper.java parts=1 sec=2.0"
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :windows:app:run
```

`RECLY_CAPTURE_HELPER` is not a path but **a command line** (split on spaces). Without it the app
looks for the helper in the MSI resources directory
(`compose.application.resources.dir`), and if it is not there either, recording is disabled.

From M6-L2 on, the protocol tests can also be run against the **real Rust helper**
(`-Drecly.captureHelper=<command line>`; `FakeHelperCommand` translates the fake's arguments into
helper flags):

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :windows:app:test --tests '*HelperClientTest*' \
  -Drecly.captureHelper="$PWD/windows/capture-helper/target/debug/recly-capture-helper --encoder ffmpeg"
```

`WindowsRecorderTest` and `ShellFlowTest` are not run this way — the real helper leaves real audio
files behind, while those tests expect the recording directory to hold nothing but `meta.json`. The
details are in `windows/capture-helper/README.md`.

## Commands

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :windows:app:test                        # unit tests
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :windows:app:run                         # run
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :windows:app:packageDistributionForCurrentOS  # MSI (Windows) / DMG (dev host)
```

The MSI, signing and SmartScreen procedures are in `windows/README.md`.
