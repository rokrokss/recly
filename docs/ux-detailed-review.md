# Detailed mobile and desktop UX re-review — 2026-09-10

After the first UX fixes, all 5 problems confirmed by 6 additional probes have been fixed. While keeping bottom playback on mobile and top playback · window
layout on desktop, state refresh · error notices · input preservation were filled in. The failure evidence of the initial audit is kept below as history.

## Fix results

| Item | Behavior applied | Current code evidence |
|---|---|---|
| 1. Apple recording · playback conflict | Stops the existing player before opening the capture for a new recording. Blocks playback and seeking during preparation · recording · finalizing, and lifts the block when finalizing completes or the start fails. Also observes the state of other recordings, separately from the transcript | `apple/RecKit/Sources/RecKit/Recorder/RecorderSession.swift:79`, `Jobs/RecordingPlaybackGate.swift:5`, `Jobs/RecordingDetail.swift:141` |
| 2. Windows decoding errors | Checks the exit code and the wait timeout when ffmpeg exits naturally, and shows a playback failure. If a middle part fails, it does not skip to the next part. The user's stop · seek is not shown as a failure, and the next play retries | `windows/app/src/main/kotlin/app/recly/windows/ui/RecordingPlayer.kt:912` |
| 3. Input lost when switching key forms | When another key input form is opened, the unsaved value is confirmed. Continuing to edit keeps the existing value; only choosing discard moves to the requested form. Reopening the same form keeps the input | Android `WorkflowsViewModel.kt:339`, Apple `WorkflowsModel.swift:408`, Windows `WorkflowsModel.kt:394` |
| 4. Refreshing an open recording detail | Subscribes to recording state · part · track · path changes separately from the transcript. After the recording is finalized the audio is refreshed, so it can be played without reopening the detail. When only the title · transcript change, the player is not reset | `core/src/commonMain/kotlin/recly/core/recording/RecordingRepository.kt:405`, Android `JobsViewModel.kt:351`, Apple `RecordingDetail.swift:148`, Windows `ShellModel.kt:995` |
| 5. Recovering a corrupted transcript | On an explicit retry, downloads the Drive remote copy instead of the corrupted local JSON, validates it, and saves it. A healthy local file is still read offline. Result writing and replacement are protected by the same lock, so that a newer valid local result produced during the download is not overwritten | `core/src/commonMain/kotlin/recly/core/ReclyCore.kt:151`, `transcribe/RecordingResults.kt:72`, `transcribe/ResultFiles.kt:16` |

The retry buttons on Android · Apple · Windows call the shared `retryResults`. It uses the existing Drive download path;
no new external transfer path or additional consent step was created. When the Drive remote copy is also missing or unreadable, the read-failure notice stays.

During the full Apple test run, a problem was also found and cleaned up in which the privacy settings model's subscription stayed alive after teardown and read a temporary DB.
When the model is torn down it cancels its subscriptions · in-flight reads, and the test waits for the teardown to complete before removing the DB
(`apple/RecKit/Sources/RecKit/Workflow/TransferPrivacy.swift:112`). After that, the full test run passed.

## Verification after the fixes

| Command run | Actual result |
|---|---|
| `make test` | `BUILD SUCCESSFUL in 2s`; `123 actionable tasks: 1 executed, 2 from cache, 120 up-to-date` |
| JVM XML tally | core 538 + Android 470 (app 333 · datalayer 23 · recording 56 · wear 58) + Windows 365 = 1,373; 0 failures · errors · skipped |
| `make core` | `BUILD SUCCESSFUL in 7m 29s`; `47 actionable tasks: 27 executed, 20 up-to-date`; XCFramework built and staged into RecKit |
| `make mac-test mac ios watch` | `Executed 449 tests, with 1 test skipped and 0 failures`; `TEST SUCCEEDED`; `BUILD SUCCEEDED` for each of macOS · iOS · watchOS |
| `ANDROID_SERIAL=emulator-5554 make android-ui-test` | All 6 pass on the separate Android 16 AVD `recly_ux_review` in Korean at the default font size; `BUILD SUCCESSFUL in 27s` |
| `make windows-test` with a real ffmpeg probe added | `BUILD SUCCESSFUL in 18s`; `AUDIT decoderExit=183 playing=false failed=true` |
| `git diff --check` | No output, exit code 0 |

Beyond simple state assignment, the regression tests check the following paths.

- Apple: whether the player was stopped right before capture starts, playback blocked while waiting for start · finalize, the block lifted after a start failure,
  observing other recordings start · finalize, and refreshing the audio after the recording in an open detail is finalized. `RecorderSessionTests.swift:11`, `RecordingDetailTests.swift:25`.
- Windows: failure · retry on an abnormal decoder exit, a middle-part failure, and the real shell model's start recording → open detail → audio refresh after finalizing.
  `RecordingPlaylistTest.kt:499`, `ShellDetailTest.kt:25`.
- Key input: reselecting the same form and switching to another form, with both the continue-editing and discard behaviors. Checks the Windows · Apple model tests and the actual Android dialog.
  `WorkflowsModelTest.kt:50`, `TransferPrivacyTests.swift`, `MobileUxTest.kt:52`.
- Shared core: local reuse after remote recovery of a corrupted transcript, preserving a new local result during the recovery download, and excluding title changes from the audio subscription while
  still delivering part additions · recording finalization. `RecordingResultsTest.kt:23`, `RecordingRepositoryTest.kt:45`.
- Android: confirms in the real detail UI that the play button appears after the recording is finalized. `MobileUxTest.kt:85`.

The real ffmpeg probe used macOS's `/opt/homebrew/bin/ffmpeg` and a fake speaker. The initial audit's
`failed=false` became `failed=true` after the fix. Only that temporary probe was removed, the test file's original SHA-256 was
checked, and the last `make test` passed with the permanent regression tests left in place.

Latest evidence: `/tmp/recly-five-fixes-test.log`, `/tmp/recly-five-fixes-core.log`,
`/tmp/recly-five-fixes-apple.log`, `/tmp/recly-five-fixes-android-ui.log`,
`/tmp/recly-five-fixes-ffmpeg.log`, `/tmp/recly-five-fixes-ffmpeg-result.xml`.

This fix verification did not run real iPhone · Android devices, Windows WASAPI devices, or MSI installation.
Large-text verification of the iOS UI is in the [first-round record](mobile-ux-review.md) and was not rerun after these fixes.
The separate Android test emulator was shut down. No release build, store upload, commit, or push was done.

## Initial audit record — the failure states and line numbers below are as of before the fixes

### Findings

### 1. [P1] The Apple detail screen does not detect another recording starting

- **Impact:** the detail model shared by iPhone · macOS. On macOS a new recording can be started from the menu bar with the detail window open, so this needs fixing first.
- **Reproduction:** Open the detail of completed recording A, wait for the first response of the result subscription, then create B, which is recording, in the same repository.
- **Expected:** The detail blocks playback, and stops it if it is already playing.
- **Actual:** `activeRecordings=1 detailDeviceRecording=false`. Failed in an Apple native probe test.
- **Cause:** `RecordingDetailModel.followResults()` checks the device's recording state only when it receives a transcript result.
  `ReclyCore.observeResults(A)` emits only changes related to A, so it does not deliver B's start · finalize.
  The view also has no wiring that stops existing playback when a recording starts.
- **Evidence:** `apple/RecKit/Sources/RecKit/Jobs/RecordingDetail.swift:122`, `:531`,
  `core/src/commonMain/kotlin/recly/core/ReclyCore.kt:157`.
- **Fix direction:** Subscribe to the device-wide recording state separately from the transcript subscription, and during preparation · recording apply both stopping playback and blocking its start.
  Android's `RecordingDetailScreen.kt:101` and Windows' `RecordingsWindow.kt:96` have separate stop paths.
- **Verification limit:** No experiment was done on actual speaker sound getting into a new recording. On macOS, which records system audio too,
  the impact that continued playback can mix into the recording is a risk judgment based on the code.

### 2. [P2] Real decoding errors on Windows are treated as a normal end of playback

- **Reproduction:** Pass a corrupted temporary `.m4a` to the real `/opt/homebrew/bin/ffmpeg` and read it with the app's `RecordingPlayer`.
  Only the output device was replaced with a fake speaker, so no actual sound was played.
- **Expected:** A playback failure message appears and retry is possible.
- **Actual:** `decoderExit=183 playing=false failed=false`. Despite the abnormal exit, the failure state was off.
- **Cause:** The end of the PCM stream is treated as a normal end, and the exit code is not checked when the decoder is cleaned up.
  The existing error test that had been newly added only checked the case where the process-creation function throws.
- **Evidence:** `windows/app/src/main/kotlin/app/recly/windows/ui/RecordingPlayer.kt:884`, `:912`;
  existing test `windows/app/src/test/kotlin/app/recly/windows/ui/RecordingPlaylistTest.kt:483`.
- **Fix direction:** On a natural exit, check the exit code · wait timeout. Exclude exits caused by the user's stop · seek from errors.
  Failure and retry in the middle of multiple parts must be verified as well.

### 3. [P2] Opening another key input discards an unsaved key without confirmation

- **Impact:** Reproduced in both the Apple shared model and the Windows model. macOS · Windows show another workflow's missing-key button
  alongside the existing input form, so it is reachable in the real UI. On iPhone too, the list's missing-key button calls the same model function.
- **Reproduction:** Enter a value in the `first_key` input form, then press add key for `second_key`.
- **Expected:** Ask whether to discard while keeping the existing input.
- **Actual:** Windows `formName=second_key valueLength=0 confirmation=null`,
  Apple `formName=second_key valueLength=0 confirmation=false`.
- **Cause:** Closing · workflow switching are guarded, but `openSecrets()` replaces the existing form right away.
- **Evidence:** `windows/app/src/main/kotlin/app/recly/windows/ui/WorkflowsModel.kt:394`,
  `apple/RecKit/Sources/RecKit/Workflow/WorkflowsModel.swift:408`;
  the actual buttons are Windows `WorkflowEditorWindow.kt:152`, Mac `WorkflowWindow.swift:162`.
- **Fix direction:** Make key form switching go through the unsaved-input confirmation too, and keep the current input when the same key is selected again.

### 4. [P2] When a recording whose detail is open is finalized, the playback area is not enabled

- **Impact:** Reproduced by running on Apple. Android · Windows also have the same kind of path that sets `writing` and the audio list only on the initial read.
- **Reproduction:** Open the detail of an item being recorded, start the result subscription, then finalize that recording in the repository.
- **Expected:** Reflect the finalization, refresh the audio list, and show the playback area.
- **Actual:** `finalized availability=notRequested writing=true`. The result state changed, but the detail still treated it as recording.
- **Cause:** The result subscription refreshes only the transcript and the transcript state. `writing` and the audio list stay as they were, and are refreshed only by closing and reopening the detail.
- **Evidence:** Apple `Jobs/RecordingDetail.swift:122`, `:335`;
  Android `JobsViewModel.kt:350`, `:358`; Windows `ShellModel.kt:986`.
- **Fix direction:** Observe the recording → finalized transition and audio part changes separately, and refresh only the metadata · audio needed.
  If only a transcript arrives while an already complete recording is playing, the playback position must be kept.

### 5. [P2] A corrupted transcript file is not recovered even by pressing ‘Retry’

- **Impact:** the shared core for Android · iPhone · macOS · Windows, which have a transcript detail.
- **Reproduction:** With transcription completed normally and a valid result on Drive, corrupt only the local JSON. After the read fails, read again.
- **Expected:** There should be a recovery path the user can choose.
- **Actual:** `retryAvailability=UNAVAILABLE additionalDownloads=0`. It just rereads the same corrupted file.
- **Cause:** If the local file exists, the remote read is excluded before the content is validated. The detail's retry calls the same read function again.
  The existing test missed this because the test code replaced the file directly with valid JSON before retrying.
- **Evidence:** `core/src/commonMain/kotlin/recly/core/transcribe/RecordingResults.kt:53`, `:69`;
  existing test `core/src/jvmTest/kotlin/recly/core/transcribe/RecordingResultsTest.kt:94`.
- **Fix direction:** Tell read failures apart into network errors and local file corruption. For a corrupted file, offer an action that leads to recovery from the remote copy or to
  re-transcription. When recovering from the remote copy, keep the existing rule of not overwriting a newer valid local result.

## Cross-platform consistency at the time of the initial audit

| Item | Finding |
|---|---|
| Mobile bottom playback / desktop top playback | Per-platform layout branching confirmed in code. Desktop was not changed to the mobile layout |
| Automatic text refresh when transcription completes | Shared core and existing Apple regression tests pass. Recording lifecycle refresh is missing (#1 · #4) |
| Guarding edit cancel · workflow switching | Existing tests pass. Key form switching is missing (#3) |
| Playback error notices | Existing Android · Apple error tests pass. Windows' real process errors are missing (#2) |
| Distinguishing loading · empty transcript · failure · no transcript | State branches and English · Korean resources confirmed. Local corruption recovery is missing (#5) |
| Keyboard · large text | Confirmed the passing logs of the previous Android 1.6× and iPhone default · large text UI tests. UI automation was not rerun in this audit |
| Watch | The phone's edit screen · bottom controls were not added. Confirmed the previous watchOS build pass record |

## Commands run in the initial audit and actual results

| Run | Actual result |
|---|---|
| Initial `make test` | `BUILD SUCCESSFUL in 18s`; 123 actionable tasks: 1 executed, 122 up-to-date |
| `make test` including the Windows probes | `363 tests completed, 2 failed`; `BUILD FAILED in 23s` |
| `make test` including the core probe | `536 tests completed, 1 failed`; `BUILD FAILED in 12s` |
| `make mac-test` including the 3 Apple probes | 447 tests, 1 skipped, 3 failures; `TEST FAILED` |
| `make test` after removing the probes | `BUILD SUCCESSFUL in 3s`; 123 actionable tasks: 1 executed, 4 from cache, 118 up-to-date |
| Final JVM XML tally | core 535 + Android 470 + Windows 361 = 1,366; 0 failures · errors · skipped |
| `make mac-test` after removing the probes | 444 tests, 1 skipped, 0 failures; `TEST SUCCEEDED` |

Existing tests passing and the added probes failing are different check results. The added probes were written to expect normal behavior on paths
the existing tests did not cover, and failed because of the actual values above. At the end of the initial audit no product code had been changed, so
the 5 problems remained. All 5 temporarily modified test files were restored to match their pre-audit SHA-256.
That audit did not change the core product sources or the XCFramework. The verification results of the later fixes, using a new XCFramework, are
in ‘Fix results’ · ‘Verification after the fixes’ at the top of this document.

Evidence is kept in `/tmp/recly-ux-detailed-review/`.

- `probes.log`, `core-probe.log`, `apple-probes-expanded.log`: probe run logs.
- `RecordingPlaylistTest-probe.xml`, `WorkflowsModelTest-probe.xml`, `RecordingResultsTest-probe.xml`: actual states and failure reasons.
- `*.probe.txt`, `manifest.json`: the probe code that was run, and hashes for confirming the restore.
- `restored-tests.log`, `restored-apple-tests.log`: existing test results after the restore.

Real iPhone · Android devices, foldable · landscape mode, VoiceOver · TalkBack, real Windows WASAPI devices and MSI installation were
not run in this audit. The cause of `segments=0` in the existing Clova recording was not identified by these probes either.

After the initial audit, #1 recording · playback conflict prevention, #2 · #3 error notices and input preservation, and #4 · #5 refresh · recovery paths were all fixed.
