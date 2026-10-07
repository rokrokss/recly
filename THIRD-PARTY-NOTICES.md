# Third-party notices

Recly is licensed under AGPL-3.0-or-later (see [LICENSE](LICENSE) and
[LICENSE-EXCEPTIONS.md](LICENSE-EXCEPTIONS.md)). It ships with the third-party components below.

**This file is maintained by hand.** The authoritative list of dependencies is the build files:
[`gradle/libs.versions.toml`](gradle/libs.versions.toml), each module's `build.gradle.kts`,
[`apple/RecKit/Package.swift`](apple/RecKit/Package.swift),
[`windows/capture-helper/Cargo.toml`](windows/capture-helper/Cargo.toml),
[`events/go.mod`](events/go.mod) and [`spec/package.json`](spec/package.json). If they disagree, the build files are right and this file
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
| AppAuth-iOS | iPhone, macOS | Apache-2.0 | <https://github.com/openid/AppAuth-iOS> |
| GTMAppAuth | iPhone, macOS | Apache-2.0 | <https://github.com/google/GTMAppAuth> |
| serde | Windows capture helper | MIT OR Apache-2.0 | <https://github.com/serde-rs/serde> |
| serde_json | Windows capture helper | MIT OR Apache-2.0 | <https://github.com/serde-rs/json> |
| sha2 | Windows capture helper | MIT OR Apache-2.0 | <https://github.com/RustCrypto/hashes> |
| windows (windows-rs) | Windows capture helper | MIT OR Apache-2.0 | <https://github.com/microsoft/windows-rs> |
| Go standard library and the Go modules linked into recly-events — MCP Go SDK, OpenAI tunnel-client, `golang.org/x/*` (oauth2, net, sys, term, sync, time), OpenTelemetry, Prometheus client, Uber fx/dig/zap/multierr, protobuf and others | recly-events | MIT, BSD-3-Clause, Apache-2.0. Every archive carries `THIRD-PARTY-NOTICES.txt` with each module's licence and NOTICE files, made by [`events/scripts/notices.sh`](events/scripts/notices.sh) | <https://github.com/modelcontextprotocol/go-sdk>, <https://github.com/openai/tunnel-client>, [`events/go.mod`](events/go.mod) |
| FFmpeg (bundled `ffmpeg.exe` and its DLLs) | Windows | LGPL-2.1-or-later — see below | <https://ffmpeg.org/> |
| sherpa-onnx (Android AAR, JVM jar and its native jar) | Android phone, Windows | Apache-2.0 | <https://github.com/k2-fsa/sherpa-onnx> |
| ONNX Runtime (inside the sherpa-onnx AAR and native jar) | Android phone, Windows | MIT | <https://github.com/microsoft/onnxruntime> |
| FluidAudio (on-device speaker diarization) | iPhone, macOS | Apache-2.0 | <https://github.com/FluidInference/FluidAudio> |
| NemoTextProcessing (text-processing-rs, a static library FluidAudio links) | iPhone, macOS | Apache-2.0 | <https://github.com/FluidInference/text-processing-rs> |
| fastcluster (inside FluidAudio) | iPhone, macOS | BSD-2-Clause | <https://github.com/fastcluster/fastcluster> |
| ajv, ajv-formats | `spec/` schema validation (development only, not shipped) | MIT | <https://github.com/ajv-validator/ajv> |
| JUnit 4 | tests only, not shipped | EPL-1.0 | <https://github.com/junit-team/junit4> |

The on-device transcription model is **not shipped**: the Android and Windows apps download it
when the user prepares it in Settings (docs/recly.md §15). It is Qwen3-ASR 0.6B as exported for
sherpa-onnx (Apache-2.0, <https://huggingface.co/Qwen/Qwen3-ASR-0.6B>) with the Silero VAD (MIT,
<https://github.com/snakers4/silero-vad>).

The on-device speaker diarization models on Android and Windows are **not shipped** either: they are
downloaded with the speech model (docs/recly.md §15). They are pyannote segmentation-3.0 (MIT,
Copyright (c) 2022 CNRS, <https://huggingface.co/pyannote/segmentation-3.0>) as exported for sherpa-onnx, and
the 3D-Speaker ERes2Net base speaker embedding model (Apache-2.0,
<https://modelscope.cn/models/iic/speech_eres2net_base_sv_zh-cn_3dspeaker_16k>, <https://github.com/modelscope/3D-Speaker>).

The iPhone and Mac apps **ship** FluidAudio's Core ML conversion of pyannote speaker-diarization-community-1
(<https://huggingface.co/FluidInference/speaker-diarization-coreml>): the segmentation, filter-bank, embedding and
PLDA models and their parameter files, licensed under CC-BY-4.0 (<https://creativecommons.org/licenses/by/4.0/>).
Attribution: pyannote (<https://huggingface.co/pyannote/speaker-diarization-community-1>), WeSpeaker, BUT Speech@FIT
(the PLDA parameters) and Fluid Inference. The files are modified from the originals: converted to Core ML.

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

## Store badges

`docs/design/badges/` holds the App Store and Google Play badges that the README and recly.dev link
to the stores with. They are the stores' own artwork, used as their guidelines allow, and are not
covered by Recly's licence.

- `app-store-en.svg`, `app-store-ko.svg`: Apple's "Download on the App Store" badge, black, as served
  on 2026-10-06 by <https://toolbox.marketingtools.apple.com/api/badges/download-on-the-app-store/black/en-us>
  (and `/ko-kr`). Guidelines: <https://developer.apple.com/app-store/marketing/guidelines/>.
- `google-play-en.png`, `google-play-ko.png`: Google's "Get it on Google Play" badge, as served on
  2026-10-06 by <https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png>
  (and `ko_badge_web_generic.png`), with only the transparent padding cropped. Guidelines:
  <https://partnermarketinghub.withgoogle.com/brands/google-play/google-play/lockups-icons-badges/>.

Apple and the Apple logo are trademarks of Apple Inc., registered in the U.S. and other countries
and regions. App Store is a service mark of Apple Inc. Google Play and the Google Play logo are
trademarks of Google LLC.
