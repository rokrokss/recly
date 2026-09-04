# Third-party notices

Recly is licensed under AGPL-3.0-or-later (see [LICENSE](LICENSE) and
[LICENSE-EXCEPTIONS.md](LICENSE-EXCEPTIONS.md)). It ships with the third-party components below.

**This file is maintained by hand.** The authoritative list of dependencies is the build files:
[`gradle/libs.versions.toml`](gradle/libs.versions.toml), each module's `build.gradle.kts`,
[`apple/RecKit/Package.swift`](apple/RecKit/Package.swift),
[`windows/capture-helper/Cargo.toml`](windows/capture-helper/Cargo.toml) and
[`spec/package.json`](spec/package.json). If they disagree, the build files are right and this file
is stale. Build-only tooling (Gradle, the Android Gradle Plugin, Xcode, cargo) is not listed.

## Components

| Component | Used by | License | Source |
|---|---|---|---|
| Kotlin standard library, compiler and Gradle plugins | core, Android, Windows | Apache-2.0 | <https://github.com/JetBrains/kotlin> |
| kotlinx.coroutines | core, all shells | Apache-2.0 | <https://github.com/Kotlin/kotlinx.coroutines> |
| kotlinx.serialization | core | Apache-2.0 | <https://github.com/Kotlin/kotlinx.serialization> |
| kotlinx-datetime | core | Apache-2.0 | <https://github.com/Kotlin/kotlinx-datetime> |
| Ktor (client, and the CIO server for the Windows loopback OAuth receiver) | core, Windows | Apache-2.0 | <https://github.com/ktorio/ktor> |
| OkHttp (transitive, via `ktor-client-okhttp`) | Android, Windows | Apache-2.0 | <https://github.com/square/okhttp> |
| Okio | core | Apache-2.0 | <https://github.com/square/okio> |
| SQLDelight | core | Apache-2.0 | <https://github.com/cashapp/sqldelight> |
| sqlite-jdbc (transitive, SQLDelight's JVM driver) | Windows | Apache-2.0 | <https://github.com/xerial/sqlite-jdbc> |
| multiplatform-settings | core | Apache-2.0 | <https://github.com/russhwolf/multiplatform-settings> |
| json-schema-validator (networknt) | core | Apache-2.0 | <https://github.com/networknt/json-schema-validator> |
| SKIE (Swift API generation for the core XCFramework) | Apple | Apache-2.0 | <https://github.com/touchlab/SKIE> |
| Compose Multiplatform | Windows | Apache-2.0 | <https://github.com/JetBrains/compose-multiplatform> |
| AndroidX / Jetpack Compose — `androidx.*` (core, activity, lifecycle, navigation, work, datastore, credentials, security-crypto, glance, media3, compose, wear-*) | Android phone, Galaxy Watch | Apache-2.0 | <https://github.com/androidx/androidx> |
| JNA and JNA Platform (Win32 wrappers) | Windows | Dual-licensed LGPL-2.1-or-later **or** Apache-2.0 from JNA 4.0 onward; Recly elects **Apache-2.0** | <https://github.com/java-native-access/jna> |
| Google Play services — `play-services-auth`, `play-services-wearable` | Android phone, Galaxy Watch | Proprietary — Android Software Development Kit License Agreement | <https://developer.android.com/studio/terms> |
| Google Identity Services — `com.google.android.libraries.identity.googleid:googleid` | Android phone | Proprietary — Android Software Development Kit License Agreement | <https://developer.android.com/studio/terms> |
| GoogleSignIn-iOS | iPhone, macOS | Apache-2.0 | <https://github.com/google/GoogleSignIn-iOS> |
| GTMAppAuth | macOS | Apache-2.0 | <https://github.com/google/GTMAppAuth> |
| serde | Windows capture helper | MIT OR Apache-2.0 | <https://github.com/serde-rs/serde> |
| serde_json | Windows capture helper | MIT OR Apache-2.0 | <https://github.com/serde-rs/json> |
| sha2 | Windows capture helper | MIT OR Apache-2.0 | <https://github.com/RustCrypto/hashes> |
| windows (windows-rs) | Windows capture helper | MIT OR Apache-2.0 | <https://github.com/microsoft/windows-rs> |
| FFmpeg (bundled `ffmpeg.exe` and its DLLs) | Windows | LGPL-2.1-or-later — see below | <https://ffmpeg.org/> |
| ajv, ajv-formats | `spec/` schema validation (development only, not shipped) | MIT | <https://github.com/ajv-validator/ajv> |
| JUnit 4 | tests only, not shipped | EPL-1.0 | <https://github.com/junit-team/junit4> |

The two Google Maven artifacts marked proprietary are closed-source AARs. Each ships its own
`third_party_licenses.txt` inside the archive, covering the open-source code Google embeds in them;
those notices are surfaced in the Android app, not reproduced here.

## FFmpeg (LGPL v2.1 or later)

The Windows build bundles FFmpeg to encode the recording format (16 kHz mono 32 kbps AAC), which
Media Foundation's AAC encoder will not produce (ADR-019). The same obligations are stated in
[`windows/app/resources/common/THIRD-PARTY-ffmpeg.md`](windows/app/resources/common/THIRD-PARTY-ffmpeg.md).

- The bundled `ffmpeg.exe` and its DLLs are a **shared-library build configured with
  `--disable-gpl --disable-nonfree`**, distributed under **LGPL v2.1 or later**.
- Recly **runs FFmpeg as a separate process**. It does not link the FFmpeg libraries, statically or
  otherwise.
- **You may replace it.** The capture helper is launched with `--ffmpeg <path>`, so dropping another
  LGPL build of `ffmpeg.exe` into the installation folder (`app/resources/`) under the same name is
  enough — the app will use yours.
- **Source**: the source for the bundled build is available from the
  [BtbN/FFmpeg-Builds](https://github.com/BtbN/FFmpeg-Builds) release it came from and the FFmpeg
  revision that release names. The same source is provided on request.

> This software uses code of [FFmpeg](https://ffmpeg.org) licensed under the
> [LGPLv2.1](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html) and its source can be
> downloaded [here](https://github.com/BtbN/FFmpeg-Builds/releases).

FFmpeg's own licensing terms: <https://www.ffmpeg.org/legal.html>.
