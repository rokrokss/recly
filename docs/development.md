# Developing Recly

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

To cut a release: `make apk wear-apk`, then
`gh release create v0.1.0 <phone.apk> <watch.apk> --target main --prerelease`.

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
[`windows/README.md`](../windows/README.md) for details.

The display version of the current release is `0.1.3` on every platform. The build is `33` for the
Apple apps, the embedded Watch app and the widgets, `38` for Android and `1,000,038` for Wear OS.
The Windows MSI install version is separate from the display version and keeps raising the third
field: it is `0.1.32` — it was raised up to `0.1.29` during `0.1.0`, so the lower `0.1.3` would not
upgrade.

### recly-events releases

`recly-events` (`events/`) is released apart from the apps, under its own `events-vX.Y.Z` tags.
`TAG=events-vX.Y.Z make events-release` on a Mac builds the archives into `events/dist/X.Y.Z/`:
macOS as one universal binary signed with Developer ID and notarized (`NOTARY_PROFILE`, default
`recly`), Linux amd64 and arm64, Windows amd64 without a code signature, and `SHA256SUMS`. Each archive carries `LICENSE`,
`LICENSE-EXCEPTIONS.md` and `THIRD-PARTY-NOTICES.txt`, the licence and NOTICE files of every linked
Go module (`events/scripts/notices.sh`). Recly's
desktop OAuth client is compiled in from `local.properties`, as for the Windows app; the script
stops without it, and when `events/` has uncommitted changes. `UPLOAD=1` also creates a draft
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
`drive.file` ([recly.md §6](recly.md#6-authentication-formerly-docs06)).

### Turning on iCloud

iCloud is off in a checkout. While `RECLY_ICLOUD_CONTAINER` in `apple/Config/Recly.xcconfig` is empty, the app does not offer iCloud as a storage location ([recly.md §3 "Storage location"](recly.md#storage-location-adr-024)). To turn it on:

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
