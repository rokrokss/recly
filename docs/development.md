# Developing Recly

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

**Spec validation · local webhook receiver** (Node):

```bash
cd spec && npm ci && npm run validate            # validate the examples against the JSON Schemas
node scripts/webhook-receiver.mjs --port 8787 --secret whsec_…   # a receiver that checks signature & schema
```

To cut a release: `make apk` and `./gradlew :android:wear:assembleDebug`, then
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

`make android-release-apk`는 같은 업로드 키로 서명한 휴대전화·Wear OS 설치용 APK를
`android/*/build/outputs/apk/release/`에 만든다. Play 설치본의 앱 서명 키와는 다를 수 있다.

배포 파일은 macOS의 경우 `make mac-release`로 Developer ID 서명·공증한
`apple/build/dist/Recly-<version>-<build>.dmg`를 만든다. 기존 iOS 아카이브와 DMG는 보존한다.
Windows MSI는 Windows 호스트의 `make windows-msi` 또는
`.github/workflows/windows-release.yml`로 만든다. 수동 실행은 기본적으로 MSI와 두 skill ZIP을
30일 보관하는 Actions artifact만 생성한다. `v*` 태그 push 또는 `publish_release=true`를
명시한 수동 실행만 GitHub 릴리스에 공개한다. Windows OAuth 설정은 저장소 Actions secrets의
`REC_GOOGLE_DESKTOP_CLIENT_ID`와 `REC_GOOGLE_DESKTOP_CLIENT_SECRET`에서 받으며,
누락되면 패키징을 중단한다. 자세한 내용은 [`windows/README.md`](../windows/README.md)를 참고한다.

현재 배포의 표시 버전은 모든 플랫폼에서 `0.1.0`이다. Apple 앱·내장 Watch·위젯의 빌드는 `11`,
Android는 `15`, Wear OS는 `1,000,015`이다. Windows 앱 표시 버전도 `0.1.0`으로 유지하고,
업그레이드 구분을 위해 MSI의 세 번째 버전 필드만 올려 설치 버전은 `0.1.10`으로 설정한다.

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
`drive.file` ([recly.md §6](recly.md#6-인증-구-docs06)).

### iOS 심사 빌드의 Google 로그인 검사

`make ios-archive`는 현재 코어를 다시 빌드한 뒤, 컴파일된 아카이브의 `GIDClientID`와 Google 콜백 URL 스킴을 검사한다. 미설정·플레이스홀더·스킴 불일치면 export/upload 전에 중단한다. `make ios-release-test`는 실제 계정 없이 아카이브 fixture로 이 검사를 검증한다. 이 정적 검사는 OAuth 콘솔의 게시 상태·번들 ID 등록·실제 기기의 로그인 성공까지 보장하지 않는다.

### Apple 정적 코어 패키징과 dSYM

`ReclyCore`는 정적 XCFramework이며 자체 리소스를 포함하지 않는다. iPhone·Watch·Mac 프로젝트의
`PACKAGE_SKIP_AUTO_EMBEDDING_STATIC_BINARY_FRAMEWORKS = YES`는 Swift Package가 이를 앱에
별도 프레임워크로 복사하지 않도록 한다. 이 설정이 없으면 Xcode가 빈 동적 바이너리를 만들고,
업로드 시 해당 UUID의 dSYM 누락 경고가 발생한다. 실제 코어 심볼은 각 앱의 dSYM에 들어간다.
설정 동작은 [Apple의 Swift Build 구현](https://github.com/swiftlang/swift-build/blob/main/Sources/SWBTaskConstruction/TaskProducers/BuildPhaseTaskProducers/SwiftPackageCopyFilesTaskProducer.swift)을 따른다.

`make ios-archive`·`make ios-upload`·`make mac-release`는 `validate-apple-package.py`로
불필요한 `ReclyCore.framework`가 없는지, 각 앱의 개인정보 매니페스트가 유지되는지,
실행 파일의 모든 아키텍처 UUID에 맞는 dSYM과 공유 코어 함수 심볼이 있는지를 검사한다.
실패하면 export/upload 또는 DMG 생성 전에 중단한다. 이 검사는 크래시의 정확한 소스 행이나
실제 기기 동작까지 보장하지 않는다. `make ios-release-test`는 패키징 검사 fixture도 실행한다.
향후 정적 바이너리 의존성에 리소스를 추가하면 별도 번들로 보존하거나 임베딩 정책을 재검토해야 한다.
