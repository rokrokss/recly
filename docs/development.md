# Developing Recly

## Prerequisites

- **JDK 21** for everything Gradle builds: the shared core, the Android apps and the Windows shell.
  The Gradle wrapper downloads Gradle itself.
- **The Android SDK** with platform 36 and build-tools 36.0.0 (`compileSdk` and `buildToolsVersion`
  in the Gradle files), for the Android apps and the core's Android target. `make test` needs it
  too.
- **Xcode 26 on macOS** for the Apple apps, which call iOS 26 and macOS 26 APIs. Their builds start
  with `make core`, which runs Gradle, so they need the two items above as well.
- **Go** for recly-events and for the copy of it the Mac app carries (`make mac-helper`).
  `events/go.mod` asks for Go 1.27; a `go` from 1.21 on downloads that toolchain itself. Without
  Go, `make mac` builds the Mac app without recly-events.
- **Rust** 1.82 or later for the Windows capture helper (`rust-version` in
  `windows/capture-helper/Cargo.toml`). Packaging the MSI also needs WiX 3, on Windows.
- **Node.js with npm** for spec validation (`make spec`).

The Makefile defaults `JAVA_HOME` and `ANDROID_HOME` to where Homebrew puts the JDK and the Android
SDK on a Mac, and keeps them when they are already set. Set both where yours differ (below).

### What builds where

| | macOS | Windows | Linux |
|---|---|---|---|
| JVM unit tests (`make test`) | Yes | `make windows-test` runs in CI; the rest is untested | Untested |
| Android APKs (`make apk`, `make wear-apk`) | Yes | Untested | Untested |
| Apple apps | Yes | No | No |
| Windows MSI and real WASAPI capture | No: the capture helper builds and tests with its Windows code left out (`make helper-test`) | Yes | No |
| recly-events | Yes, and its release archives (`make events-release`) | Yes | Yes |

"Untested" means the Makefile does not tie the target to a host, but neither the development Mac
nor CI runs it there. CI runs the Windows shell tests, the capture helper and the MSI build on
Windows for each `v*` tag, and the recly-events tests on all three systems for each change to
`events/`.

## Build · test

The `Makefile` wraps every command below with the flags that matter (JDK 21 and the Android SDK
path for Gradle, `ARCHS=arm64` for simulators, `-collect-test-diagnostics never` for xctest):

```bash
make test        # core · android · windows unit tests (JVM)
make core        # build the XCFramework and stage it into apple/RecKit (do this first on a Mac)
make core-mac    # after a core change, refresh only the macOS slice before make mac / mac-test
make mac         # build Recly Mac          make mac-test   # RecKit tests on macOS
make ios         # Recly on the iOS simulator        make watch      # Recly Watch on the watch simulator
make apk         # phone debug APK          make spec       # validate spec/examples
make help        # the full list — IOS_SIM / WATCH_SIM override the simulator names
```

What the targets run, if you need the commands themselves. Gradle needs JDK 21 and the Android
SDK path. The lines below are the Makefile's defaults, Homebrew's paths on a Mac; the Makefile
keeps `JAVA_HOME` and `ANDROID_HOME` when they are already set, so set your own where they differ,
in the environment or on the command line (`make test JAVA_HOME=…`):

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

`make ios-kit-test IOS_SIM="Recly UX Review" IOS_KIT_TESTS=RecKitTests/PlaybackAudioSessionTests`
runs the playback audio-session regression on an iOS simulator without opening a microphone.

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

**Spec validation** (Node):

```bash
cd spec && npm ci && npm run validate            # validate the examples against the JSON Schemas
```

### App releases

An app release is one GitHub release for every platform, tagged `v<version>-build.<n>` with the
Apple build number as `<n>`, for example `v0.2.0-build.34`. The packages come from these targets:

- macOS: `make mac-release`.
- iPhone with the Apple Watch app: `make ios-archive` exports the App Store package to
  `apple/build/dist/ios/`; `make ios-upload` uploads it to App Store Connect instead. Both need the
  team: `RECLY_DEVELOPMENT_TEAM` in `apple/Config/Local.xcconfig`, or `RECLY_TEAM_ID`.
- Android phone and Wear OS: `make android-release-apk` and `make aab`.
- Windows: pushing the tag runs `.github/workflows/windows-release.yml`, which runs the capture
  helper, recly-events and Windows shell tests, builds the MSI and the two skill ZIPs, and attaches
  them to the release for the tag. If that release does not exist yet, the workflow creates it as a
  pre-release with generated notes.

The release carries `Recly-macOS-<version>-<build>.dmg`, `Recly-<installerVersion>.msi`,
`Recly-Android-<version>-<build>.apk` and `.aab`, `Recly-WearOS-<version>-<build>.apk` and `.aab`
(the `.aab` files are the Play upload bundles), `Recly-iOS-Watch-<version>-<build>.ipa` (the App
Store upload package), `recly-notes.zip`, `recly-notion.zip`, `BUILDINFO.md`, `manifest.json` and
`SHA256SUMS`. The build number differs per platform (below). The release body follows
[Release notes](#release-notes).

**Release signing (Android)**: Play App Signing holds the app signing key; this tree only ever
sees the *upload* key. Create it once, outside the repository (`*.jks` is gitignored anyway):

```bash
keytool -genkeypair -v -keystore ~/.recly/upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
```

Then point the build at it, in `local.properties` or the environment (`REC_UPLOAD_STORE_FILE`,
`REC_UPLOAD_STORE_PASSWORD`, `REC_UPLOAD_KEY_ALIAS`, `REC_UPLOAD_KEY_PASSWORD`):

```properties
upload.storeFile=/Users/you/.recly/upload.jks
upload.storePassword=…
upload.keyAlias=upload
upload.keyPassword=…
```

`make aab` builds the phone and watch bundles (`android/*/build/outputs/bundle/release/`), both
signed with that key — Play pairs the two only when their signatures match. Without the key the
release bundles are unsigned and Play refuses them. After the first upload, Play Console → Setup →
App signing shows the *app signing key's* SHA-1: register an Android OAuth client with it in the
GCP project, next to the debug one, or sign-in fails in every Play-installed build.

`make android-release-apk` builds installable phone and Wear OS APKs signed with the same upload
key into `android/*/build/outputs/apk/release/`. They may differ from the app signing key of
Play-installed builds.

For macOS, `make mac-release` builds the distribution file
`apple/build/dist/Recly-<version>-<build>.dmg`, signed with Developer ID and notarized. It and `make mac`
first build the recly-events the app bundles (`make mac-helper`, which needs Go); without Go the app is
built without it, and its Agent connection switch says so (docs/recly.md §12 "Agent connection"). Existing
iOS archives and DMGs are kept. The Windows MSI is built with `make windows-msi` on a Windows host
or with `.github/workflows/windows-release.yml`. A manual run by default only produces an Actions
artifact that keeps the MSI and the two skill ZIPs for 30 days. Only a `v*` tag push or a manual
run with `publish_release=true` set explicitly publishes to a GitHub release. The Windows OAuth
settings come from the repository's Actions secrets `REC_GOOGLE_DESKTOP_CLIENT_ID` and
`REC_GOOGLE_DESKTOP_CLIENT_SECRET`; if they are missing, packaging stops. The same job builds the
`recly-events.exe` the MSI bundles, with the same client. See
[`windows/README.md`](https://github.com/rokrokss/recly/blob/main/windows/README.md) for details.

The display version of the current release is `0.3.0` on every platform. The build is `35` for the
Apple apps, the embedded Watch app, the widgets and the share extension, `40` for Android and
`1,000,040` for Wear OS. The Windows MSI install version is set apart from the display version
(`installerVersion`): it is `0.3.0`. During `0.1.x` its third field had to keep rising — it reached
`0.1.32`, above the lower `0.1.3` — and every `0.2.0` and later is above all of those, so it upgrades
every earlier MSI.

#### Release notes

Every app release body follows this template. Fill in the versions and build numbers, which differ
per platform, and replace any notes the workflow generated.

~~~markdown
## What's new

- …

## Which file do I need

| Platform | File |
|---|---|
| Mac (Apple silicon, macOS 14.4 or later) | `Recly-macOS-<version>-<build>.dmg` |
| Windows 11 (x64) | `Recly-<installerVersion>.msi` (beta, unsigned: see the [install guide](https://recly.dev/install.html#windows)) |
| Android phone (Android 14 or later) | [Google Play](https://play.google.com/store/apps/details?id=app.recly), or `Recly-Android-<version>-<build>.apk` |
| Galaxy Watch (Wear OS 5 or later) | [Google Play](https://play.google.com/store/apps/details?id=app.recly), or `Recly-WearOS-<version>-<build>.apk` |
| iPhone (iOS 17 or later) and Apple Watch (watchOS 10 or later) | [App Store](https://apps.apple.com/app/recly-record-for-your-ai/id6809930443) |
| Example skills for the Claude or ChatGPT app | `recly-notes.zip` and `recly-notion.zip` |

The `.aab` files and the `.ipa` are the packages uploaded to Google Play and the App Store; you do
not need them.

[Install guide](https://recly.dev/install.html) · [Set-up guide](https://recly.dev/setup.html) ·
[ChatGPT agent guide](https://recly.dev/agent.html)

## Check a download

On macOS or Linux, in the folder with your downloads and `SHA256SUMS` (it checks only the files
you downloaded):

```sh
shasum -a 256 --ignore-missing -c SHA256SUMS
```

On Windows, run this in PowerShell and compare the hash with that file's line in `SHA256SUMS`:

```powershell
Get-FileHash .\Recly-<installerVersion>.msi -Algorithm SHA256
```
~~~

### recly-events releases

`recly-events` (`events/`) is released apart from the apps, under its own `events-vX.Y.Z` tags.
`TAG=events-vX.Y.Z make events-release` on a Mac builds the archives into `events/dist/X.Y.Z/`:
macOS as an installer package (`.pkg`) that puts one universal binary in `/usr/local/bin`, the binary
signed with a Developer ID Application certificate and the package with a Developer ID Installer
certificate, both in the keychain, then notarized (`NOTARY_PROFILE`, default `recly`) and stapled;
Linux amd64 and arm64, Windows amd64 without a code signature, and `SHA256SUMS`. A package, not a
bare binary: a downloaded binary double-clicked in the Finder fails Gatekeeper however it is signed
and notarized, and what an installer puts in place is not quarantined. Each archive carries `LICENSE`,
`LICENSE-EXCEPTIONS.md` and `THIRD-PARTY-NOTICES.txt`, the licence and NOTICE files of every linked
Go module (`events/scripts/notices.sh`). Recly's
desktop OAuth client is compiled in from `local.properties`, as for the Windows app; the script
stops without it, and when `events/` has uncommitted changes. The build flags live in
`events/scripts/build.sh`, which `make events`, `make mac-helper` and the Windows release job use too. `UPLOAD=1` also creates a draft
pre-release that does not become "Latest"; publishing it creates the tag. CI
(`.github/workflows/events.yml`) tests `events/` on Linux, macOS and Windows.

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
`drive.file` ([recly.md §6](https://github.com/rokrokss/recly/blob/main/docs/recly.md#6-authentication-formerly-docs06)).

### App Group

The iPhone app, its widgets and its share extension share the App Group `group.app.recly`. Automatic
signing registers the App IDs, but not their App Groups capability, so an archive fails with
"Provisioning profile … doesn't match the entitlements file's value for the
com.apple.security.application-groups entitlement" until it is set once in Apple Developer,
Certificates, Identifiers & Profiles → Identifiers (2026-10-09):

| Identifier | Set |
|---|---|
| `group.app.recly` (App Groups) | registered |
| `app.recly` | App Groups → `group.app.recly` |
| `app.recly.widgets` | App Groups → `group.app.recly` |
| `app.recly.share` | App Groups → `group.app.recly` |

### Turning on iCloud

iCloud is off in a checkout. While `RECLY_ICLOUD_CONTAINER` in `apple/Config/Recly.xcconfig` is empty, the app does not offer iCloud as a storage location ([recly.md §3 "Storage location"](https://github.com/rokrokss/recly/blob/main/docs/recly.md#storage-location-adr-024)). To turn it on:

1. In Apple Developer, Certificates, Identifiers & Profiles → Identifiers → iCloud Containers, register `iCloud.app.recly` for the team.
2. Turn on the iCloud capability for the App IDs `app.recly` and `app.recly.mac`, choose **Include CloudKit support** for compatibility, then assign that container under Edit. Turning it on with "Compatible with Xcode 5" puts only the old `TeamID.*`-style container in the profile and leaves out `iCloud.app.recly` (confirmed with an actual profile on 2026-10-02). The Mac app has so far only been signed with Developer ID, so the `app.recly.mac` App ID may not exist. If it does not, register it first under Identifiers → App IDs.
3. Under Profiles → Distribution → **Developer ID**, create a profile for `app.recly.mac` and install it. The profile's Entitlements must contain `com.apple.developer.icloud-container-identifiers` set to `iCloud.app.recly` (check with `security cms -D -i <file>`).
4. Put the values below in `apple/Config/Local.xcconfig`. They are the same as the comment lines at the end of `Local.xcconfig.example`.

```
RECLY_ICLOUD_CONTAINER = iCloud.app.recly
RECLY_PHONE_ENTITLEMENTS = RecPhone/RecPhone-iCloud.entitlements
RECLY_MAC_ENTITLEMENTS[config=Release] = RecMac/RecMac-iCloud.entitlements
RECLY_MAC_PROFILE[config=Release] = Recly Mac Developer ID
```

The iPhone archive (`make ios-archive`, automatic signing) takes its entitlements from `RECLY_PHONE_ENTITLEMENTS`. A Mac Release build needs a **Developer ID provisioning profile** for `app.recly.mac` that includes that container. `Recly Mac Developer ID` on the last line is that profile's name; if your name differs, change that line. iCloud is a restricted entitlement, so a Mac app that has it without a profile quits as soon as it launches. That is why `apple/scripts/release-mac.sh` (`make mac-release`) refuses an iCloud build that does not contain the profile, and also requires a team for an iCloud build (`RECLY_DEVELOPMENT_TEAM` or `RECLY_TEAM_ID` in `Local.xcconfig`). A Debug Mac build (the local "Recly Local Development" certificate) does not include iCloud, and iCloud does not appear in its settings either (`RECLY_MAC_ICLOUD_CONTAINER`).

When you change `NSUbiquitousContainers` in `Info.plist`, raise `CFBundleVersion`. The system rereads that value only for a new build. Checking sync needs two physical devices signed in with the same Apple ID. As of 2026-10-02 it has not been checked on physical devices.

### Google sign-in check for iOS review builds

`make ios-archive` rebuilds the current core, then checks the `GIDClientID` and the Google callback URL scheme in the compiled archive. If either is unset or a placeholder, or the scheme does not match, it stops before export/upload. `make ios-release-test` verifies this check against an archive fixture, without a real account. This static check does not guarantee the OAuth console's publishing status, the bundle ID registration or a successful sign-in on a physical device.

### Apple static core packaging and dSYMs

`ReclyCore` is a static XCFramework and contains no resources of its own.
`PACKAGE_SKIP_AUTO_EMBEDDING_STATIC_BINARY_FRAMEWORKS = YES` in the iPhone, Watch and Mac projects
keeps the Swift Package from copying it into the app as a separate framework. Without this setting,
Xcode creates an empty dynamic binary, and the upload warns that the dSYM for its UUID is missing.
The actual core symbols go into each app's dSYM. The setting behaves as in
[Apple's Swift Build implementation](https://github.com/swiftlang/swift-build/blob/main/Sources/SWBTaskConstruction/TaskProducers/BuildPhaseTaskProducers/SwiftPackageCopyFilesTaskProducer.swift).

`make ios-archive`, `make ios-upload` and `make mac-release` use `validate-apple-package.py` to check
that there is no unneeded `ReclyCore.framework`, that each app's privacy manifest is kept, and that
there are dSYMs matching every architecture UUID of the executable along with the shared core
function symbols. On failure they stop before export/upload or DMG creation. This check does not
guarantee exact source lines for crashes or the behavior on a physical device. `make ios-release-test`
also runs the packaging check fixtures. If resources are added to a static binary dependency in the
future, they must be kept in a separate bundle or the embedding policy must be revisited.
