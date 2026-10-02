# Removing the post-completion integration and automatic speaker diarization

User request: remove the post-completion integration feature, do not expose the speaker diarization option, and always turn it on where supported.

## Scope of the change

- Removed the completion webhook, the speaker diarization toggle and the minimum/maximum speaker count inputs from the fixed processing screens on iPhone, Mac, Android and Windows.
- The recovery inputs for old failed jobs are also shown only for external transcription. The URL and key inputs for webhook recovery are not shown on the current screens.
- The completion webhook is removed from reading, saving, importing and exporting the current settings, and from new plans. The fields in old files stay in
  the schema and parser for read compatibility, and existing workflow documents, steps already saved in the queue and key values are not modified.
- Speaker diarization is requested automatically from external adapters that support it. Groq and explicitly chosen non-diarizing OpenAI models are excluded.
  When no OpenAI model is specified, the existing adapter's default diarizing model is used. An explicitly specified model is not changed automatically.
- Local runs check the engine's `supportsDiarization` and reflect it in the actual request. If it is not supported, transcription continues without speaker diarization.
  So Apple SpeechTranscriber does not turn into a transcription failure either. The speaker count inputs are replaced by the default range, and the engine/vendor infers the count.
- The saved choices of existing pending jobs are kept. So that reinterpreting old documents does not bring removed options back for new recordings,
  the same policy is applied not only to the UI draft but also to the core's settings and plan creation.
- Siri shortcuts made earlier also use the current fixed settings when they start a new recording. Decoding of saved workflow parameters is
  kept, but they are not passed to recording start. This is kept separate from recovering earlier recordings and from the queue's workflow choice.

## Verification

The regression tests cover webhook removal from existing settings, import and export; speaker support per provider and per explicit model;
requests and results for supported and unsupported local engines; and preservation of steps already saved in the queue.
No new real-account API requests or long transcription load tests are run.

Schema check:

```text
/usr/sbin/taskpolicy -b make spec
OK   recording-settings.schema.json <- examples/recording-settings.json
OK   recording-settings.schema.json <- examples/recording-settings-external.json
OK   settings: legacy speaker choices remain readable -> valid=true
OK   settings: legacy webhook remains readable -> valid=true
```

JVM verification:

```text
/usr/sbin/taskpolicy -b make test GRADLE='./gradlew --offline --max-workers=1 --no-parallel -Pkotlin.compiler.execution.strategy=in-process'
BUILD SUCCESSFUL in 16m 34s
123 actionable tasks: 34 executed, 89 up-to-date
XML: tests=1452, failures=0, errors=0, skipped=0
core 601 / Android app 335 / wear 58 / recording 65 / datalayer 23 / Windows 370
```

The log is `/tmp/recly-controls-jvm.log`, and the schema validation is `/tmp/recly-controls-spec.log`.

In the final review, after hiding the old webhook recovery inputs in the three UIs, the same `make test` command was run again.

```text
BUILD SUCCESSFUL in 7m 44s
123 actionable tasks: 10 executed, 113 up-to-date
XML: tests=1452, failures=0, errors=0, skipped=0
```

The final JVM log is `/tmp/recly-controls-jvm-final.log`. `git diff --check` also passed with no output and exit code 0.

Apple shared library:

```text
/usr/sbin/taskpolicy -b make core CORE_GRADLE_ARGS='--offline --max-workers=1 --no-parallel -Pkotlin.compiler.execution.strategy=in-process'
BUILD SUCCESSFUL in 51m 14s
47 actionable tasks: 27 executed, 20 up-to-date
build-core: /Users/rokrokss/rec/apple/RecKit/Frameworks/ReclyCore.xcframework
```

Apple checks (run serially):

```text
/usr/sbin/taskpolicy -b make -j1 mac-test ios-ui-test XCODEBUILD='xcodebuild -workspace apple/Rec.xcworkspace -collect-test-diagnostics never -jobs 1 -parallel-testing-enabled NO' IOS_TESTS=ReclyTests/RecordingIntentTests SIM_BUILD_ARGS='-jobs 1 -parallel-testing-enabled NO'
RecKit: Executed 506 tests, with 2 tests skipped and 0 failures (0 unexpected)
** TEST SUCCEEDED **
ReclyTests/RecordingIntentTests: Executed 6 tests, with 0 failures (0 unexpected)
** TEST SUCCEEDED **
```

The two skipped checks are real microphone recording and actual local model inference. The log is `/tmp/recly-controls-apple.log`.
The iPhone app and the bundled Watch app were also built during the simulator checks above.

Mac build for installation:

```text
/usr/sbin/taskpolicy -b make mac XCODEBUILD='xcodebuild -workspace apple/Rec.xcworkspace -collect-test-diagnostics never -jobs 1 -parallel-testing-enabled NO CODE_SIGN_IDENTITY=F28FEB1373DC6357F762A1750B40711E4DA8A09A DEVELOPMENT_TEAM=87G5R48C73 CODE_SIGN_STYLE=Manual'
** BUILD SUCCEEDED **
/Applications/Recly.app signature verified
/Users/rokrokss/Library/Developer/Xcode/DerivedData/Rec-hceoraeiiygpllftfieamwnnkyff/Build/Products/Debug/Recly.app signature verified
Designated requirements match
```

The same Developer ID signature as the existing app was used. Recording state `finalized=4` and 0 pending jobs were confirmed, and the DB was backed up online.
After quitting normally with `cua-driver`'s Cmd-Q, the new app was installed to `/Applications/Recly.app` and launched with the same tool.
The previous app and DB are kept in `/Users/rokrokss/Library/Caches/recly-before-controls-ccpaq4bb`.

```text
ps -p 49845 -o pid=,comm=
49845 /Applications/Recly.app/Contents/MacOS/Recly
/usr/bin/log show --last 3m --info --style compact --predicate 'process == "Recly" AND (eventMessage BEGINSWITH "shell.ready" OR eventMessage BEGINSWITH "shell.failed")'
2026-09-24 10:57:54.134 I  Recly[49845:57317e7] [app.recly.mac:shell] shell.ready device=<private> dataDir=<private> workflows=0 recovered=0
```

There was no SecurityAgent window after launch. No pixel-level manual check of the menu settings screen and no real-account API calls were done.
The logs are `/tmp/recly-controls-signed-mac.log` and `/tmp/recly-controls-launch.log`.

## Implementation locations

- `core/src/commonMain/kotlin/recly/core/processing/ProcessingSettings.kt:27`: the fixed policy for new recordings.
- `core/src/commonMain/kotlin/recly/core/processing/ProcessingPlan.kt:11`: the same policy applied at plan creation.
- `core/src/commonMain/kotlin/recly/core/processing/ProcessingSettingsRepository.kt:183`: normalization on save and read.
- `core/src/commonMain/kotlin/recly/core/transcribe/SttProvider.kt:144`: speaker support decision per external adapter and chosen model.
- `core/src/commonMain/kotlin/recly/core/transcribe/LocalTranscriptionService.kt:93`: reflecting whether the local runtime supports it.
- `apple/RecKit/Sources/RecKit/Transcription/ProcessingSettingsView.swift:218`: Apple processing UI.
- `android/app/src/main/kotlin/app/recly/android/ui/ProcessingPanel.kt:86`: Android processing UI.
- `windows/app/src/main/kotlin/app/recly/windows/ui/ProcessingPanel.kt:49`: Windows processing UI.
- `apple/RecPhone/RecPhoneShared/RecordingIntents.swift:78`: new recordings from old shortcuts also start with the fixed settings.
- `apple/RecPhone/RecPhone/RecordingModel.swift:646`: the model entry point also does not apply an old workflow ID to a new capture.
- `apple/RecPhone/RecPhoneTests/RecordingIntentTests.swift:21`: verifies old shortcuts against the current settings, and decoding of old IDs.
- `Makefile:147`: also passes the existing `SIM_BUILD_ARGS` to simulator checks so the serial build options apply.
