# Simplified recording settings and global language support

2026-09-24 request: fix desktop to meeting mode, select the microphone automatically and remove the selection UI, order ElevenLabs and CLOVA first,
expand the app UI and transcription languages, remove the export of previous settings.

## Scope

- Recordings started on Mac and Windows are in meeting mode. The old manual mode and the Mac microphone UID are not applied to new recordings.
  The current input device status and the permission recovery guidance stay.
- The first two entries in the external transcription list are ElevenLabs and CLOVA. A draft with no external provider set yet selects
  ElevenLabs. Existing provider, model and key settings are preserved.
- The button and call path for exporting previous settings are removed on Mac, iPhone, Android and Windows. Exporting the current settings stays.
  Old documents, queue snapshots and internal recovery backups can still be read as before.
- The UI is offered in 12 languages: en, ko, ja, zh-Hans, zh-Hant, es, fr, de, pt, ar, hi, ru. This includes the watch, widgets, notifications and error guidance.
  Language names are shown in their own language, and the existing one-row selection UI stays. Chinese region/script and Arabic RTL are handled.
- Transcription languages are 20 explicit choices (including Simplified/Traditional Chinese) plus the existing automatic and Korean-English mixed options. They are independent of the UI language and
  are initialized to the device language only on a new install. A saved transcription language is kept when the app language changes.
- Language coverage per external provider and model is applied, and Apple local is checked against the actual OS with `supportedLocale(equivalentTo:)`.
  If a language the chosen provider does not support remains selected, the app explains this and blocks saving. The file-splitting behavior of external APIs is not changed.

RTL applies the platform's layout-direction environment value. SwiftUI custom Layouts also have their horizontal positions mirrored by the system,
so they are not mirrored a second time by hand ([Apple LayoutDirection](https://developer.apple.com/documentation/swiftui/layoutdirection)).
The Compose popup's own position calculation anchors to the right in RTL and keeps the popup on screen.

## Resources and compatibility

`scripts/localize.py` generates the platforms' native resources from the shared dictionary in `localization/translations/`.
It checks 529 normalized English source strings, and treats missing translations, format argument mismatches and generated-file mismatches as failures.
Only items such as brand names and languages' own names are put in an explicit do-not-translate list. No separate translation server or app network path is added.

The number of language codes grows, but the version of existing settings and workflows and the existing four language values do not change. Actual API requests convert to the
ISO code or locale the vendor accepts. The Chinese `zh-cn`/`zh-tw` choices become `zh` in APIs that accept only ISO codes and
`cmn` in Speechmatics and Rev, so this does not mean every provider enforces the output script.

The local engines on Android and Windows remain unavailable, as in the existing implementation. This change does not ship a new local ASR model
for those platforms, and choosing and configuring an external API is still possible.

## Provider sources

Official documentation checked on 2026-09-24:

- [ElevenLabs Speech to Text](https://elevenlabs.io/docs/overview/capabilities/speech-to-text),
  [API language codes](https://elevenlabs.io/docs/api-reference/speech-to-text/convert): multilingual Scribe and ISO language codes.
- [CLOVA long-form upload](https://api.ncloud-docs.com/docs/ja/ai-application-service-clovaspeech-longsentence-local):
  ko-KR, en-US, enko, ja, zh-cn, zh-tw. The selection UI is limited to this range too.
- [Apple SpeechTranscriber](https://developer.apple.com/documentation/speech/speechtranscriber): device availability and supported locales
  are checked with the runtime API, kept apart from whether the language assets are installed.
- [Deepgram models/languages](https://developers.deepgram.com/docs/models-languages-overview): coverage per Nova model;
  Nova-2's Arabic and English-only special models are kept apart from the general multilingual models.
- [Mistral Speech to Text](https://docs.mistral.ai/studio/audio/speech_to_text): a range of 13 languages.
- [RTZR file transcription](https://developers.rtzr.ai/docs/stt-file/): sommers' Korean and Japanese are kept apart from whisper's multilingual coverage.
- [Speechmatics languages](https://docs.speechmatics.com/speech-to-text/languages): the Mandarin `cmn` code.
- [Rev language codes](https://www.rev.ai/languages?a5d3b468_page=4): Mandarin is `cmn`. Rev's automatic language choice is excluded so the app does not
  claim automatic transcription without a separate language identification request. Reading of the old `auto` value keeps working.
- [Azure languages](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/language-support): a locale per language.
- [Daglo async](https://developers.daglo.ai/guide/en/STT-Async.html): languages whose new language code contract could not be confirmed are
  not exposed arbitrarily; the existing adapter's verified Korean, English and mixed range stays.

## Verification

Real external API calls and long-running model inference are not part of the checks for this change.
Builds ran sequentially at background priority, with `--max-workers=1` for Gradle and `-jobs 1` for Xcode.

- `/usr/sbin/taskpolicy -b make test GRADLE='./gradlew --offline --max-workers=1 --no-parallel -Pkotlin.compiler.execution.strategy=in-process'`
  - Output: `BUILD SUCCESSFUL in 13m 59s`, `123 actionable tasks: 44 executed, 79 up-to-date`.
  - JUnit XML: core 606, Android phone 336, watch 58, recording 65, data layer 23, Windows 370.
    Total **1,458, 0 failures, 0 errors, 0 skipped**.
  - The two failures in the first run were the old provider order and implicit Korean test data. After reflecting the new order and making the test
    language explicit, the full command passed again.
- `make spec`: 64 `OK`, exit code 0. Includes saving the 22 language values and rejecting unsupported codes.
- `python3 scripts/localize.py --check`:
  `Localization: 12 languages, 529 source messages, 51 native files verified`.
- `git diff --check`: no output, exit code 0.

- `/usr/sbin/taskpolicy -b make core CORE_GRADLE_ARGS='--offline --max-workers=1 --no-parallel -Pkotlin.compiler.execution.strategy=in-process'`
  - Output for the final source: `BUILD SUCCESSFUL in 55m 59s`, `47 actionable tasks: 27 executed, 20 up-to-date`.
  - `build-core: /Users/rokrokss/rec/apple/RecKit/Frameworks/ReclyCore.xcframework`.
  - Verification and staging of the five slices (iPhone device/simulator, Mac, Watch device/simulator) completed.

- `make mac-test XCODEBUILD='xcodebuild -workspace apple/Rec.xcworkspace -collect-test-diagnostics never -jobs 1 -parallel-testing-enabled NO'`
  - Output: `Executed 507 tests, with 2 tests skipped and 0 failures (0 unexpected)`, `** TEST SUCCEEDED **`.
  - Passed after fixing two `explicit_` call sites in Swift to the actual Swift name `explicit`.
  - Includes actual bundle lookups for the 12 languages and the full catalog, and a check that the new transcription language is preserved.

- `make ios-ui-test IOS_TESTS=ReclyUITests/LanguageSettingUITests SIM_BUILD_ARGS='-jobs 1 -parallel-testing-enabled NO'`
  - Output: `Executed 1 test, with 0 failures (0 unexpected)`, `** TEST SUCCEEDED **` (63.793 seconds).
  - Verified English → Japanese → Traditional Chinese → Arabic, relaunch, Korean → English on the iPhone 17 Pro simulator.
    Arabic tab direction and keeping the selected language passed. The layout in the attached Japanese, Traditional Chinese and Arabic screenshots was also checked.
  - iPhone, watch and widgets were built together. Test result:
    `/Users/rokrokss/Library/Developer/Xcode/DerivedData/Rec-hceoraeiiygpllftfieamwnnkyff/Logs/Test/Test-Recly-2026.09.24_13-39-07-+0400.xcresult`.
- `/usr/sbin/taskpolicy -b make mac XCODEBUILD='xcodebuild -workspace apple/Rec.xcworkspace -collect-test-diagnostics never -jobs 1 -parallel-testing-enabled NO CODE_SIGN_IDENTITY=F28FEB1373DC6357F762A1750B40711E4DA8A09A DEVELOPMENT_TEAM=87G5R48C73 CODE_SIGN_STYLE=Manual'`
  - Output: `** BUILD SUCCEEDED **`.
  - Both the old app and the new app pass `codesign --verify --deep --strict`, with the same designated requirement.
  - Replaced `/Applications/Recly.app` and launched it with cua-driver. The actual executable path of PID 29792 is this install path too.
  - Log at 2026-09-24 13:47:52 +0400: `shell.ready device=<private> dataDir=<private> workflows=0 recovered=0`.
  - After the restart: recordings `finalized|4`, 0 jobs, the same SHA-256 of the raw transcription settings, app language `ko` kept.
  - Backup: `/Users/rokrokss/Library/Caches/recly-before-global-zyd92nya` (previous app, online SQLite backup, verification state).

The final resource check and `git diff --check` passed too. The two tests skipped on the Mac are real microphone recording and local transcription.
Android and Windows were verified with unit tests and compilation on the macOS host; real-device UI and WASAPI runs were not part of this check.

## Main implementation locations

- `apple/RecMac/RecMac/MenuModel.swift:222`, `:445`: automatic microphone, meeting mode fixed.
- `windows/app/src/main/kotlin/app/recly/windows/ui/ShellModel.kt:606`: meeting mode fixed.
- `core/src/commonMain/kotlin/recly/core/workflow/WorkflowParser.kt:97`: external provider list order.
- `core/src/commonMain/kotlin/recly/core/transcribe/TranscriptionLanguages.kt:11`, `:62`:
  language coverage per provider and model, initial language choice.
- `apple/RecKit/Sources/RecKit/Transcription/AppleSpeechTranscriber.swift:5`: querying the OS's actual local language support.
- `apple/RecKit/Sources/RecKit/Transcription/ProcessingSettingsView.swift:130`, `:152`:
  exporting only the current settings, save-time validation of supported languages.
- `apple/RecKit/Sources/RecKit/Localization/AppLanguage.swift:34`: the 12 app languages and their region/script mapping.
- `apple/RecPhone/RecPhoneUITests/LanguageSettingUITests.swift:9`: on-screen language switching, Arabic RTL and persistence across relaunch.
