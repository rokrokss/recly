# Mobile and desktop UX improvements — 2026-09-10

All 5 problems confirmed in the follow-up [detailed re-review](ux-detailed-review.md) have been fixed and pass regression tests.
The latest fix · verification results are at the top of that document. Below is the first-round implementation · verification record.

First-round improvements were implemented for the problems found on a real Android device and for the 8 items of the mobile code review. On macOS · Windows, the state notices and
protective behaviors were applied in common, keeping the existing windows · split views · top playback layout. iPhone · Android's bottom controls were
not moved to the desktop. The watch did not get the phone's edit screen or bottom navigation.

## Scope of the fixes

| Review item | Behavior applied | Code evidence |
|---|---|---|
| Back on another tab closes the hidden editor | Back applies only to the current tab. Going back from a secondary tab moves to the Record tab and keeps the draft | `android/app/src/main/kotlin/app/recly/android/ui/MainActivity.kt:209` |
| Unsaved input lost | Confirmation before canceling an edit with changes · selecting another workflow · starting a new edit. Key input is protected too. An edit with no changes closes right away | Android `WorkflowsViewModel.kt:176`, Apple `WorkflowsModel.swift:222`, Windows `WorkflowsModel.kt:219` |
| Detail transcript not refreshed automatically | Observes job · step · recording changes and refreshes only the text. Once the transcription step finishes, the text shows even before the follow-up webhook completes | `core/src/commonMain/kotlin/recly/core/ReclyCore.kt:147`, `:157`; each shell's detail model |
| Keyboard pushes save · cancel away | Save · cancel at the bottom on mobile, IME · safe area respected, graph collapsed while typing. Scrolls to the error area and moves to the input field in question | Android `WorkflowEditorScreen.kt`, `MainActivity.kt`, Manifest; iPhone `WorkflowsView.swift` |
| API key deleted immediately | Deletes after confirming the key name · the workflows that use it. Canceling keeps the stored key. The key value is not shown on the confirmation screen | Each shell's `WorkflowProtection`/`WorkflowProtectionDialogs`, workflow models |
| Playback failure not surfaced | Reflects the actual playback state · errors, and the play button retries. Apple also tracks failed items removed from the queue | Android · Apple · Windows `RecordingPlayer`; Apple `RecordingPlayer.swift:103` |
| Internal ID pushes the title | Title up to 3 lines; ID below it as copyable secondary information | Android · Windows `component/Table.kt`; Apple `Design/SectionTable.swift` |
| iOS detail behaves differently | Keeps bottom playback on Android · iPhone and top playback on Mac · Windows. Common notices for waiting · no transcript · failure · read failure · empty result | Apple `Jobs/RecordingDetail.swift:122`, `:334`; each shell's detail screen |
| List loading · empty state | Shows a first-read message. Keeps the existing list. An actually empty list links to the recording screen · start recording · add workflow | Each shell's list screen and list model |

Android's workflow models · screens are in `android/app/src/main/kotlin/app/recly/android/ui/`, Windows' in
`windows/app/src/main/kotlin/app/recly/windows/ui/`, and the Apple shared models in
`apple/RecKit/Sources/RecKit/Workflow/`.

In addition, Android's shared minimum touch size was set to 48dp, and theme selection · key save/generate · the edit screen's bottom buttons were made to wrap.
The recording screen gets scrolling at small heights · large text. So that the status bar text does not disappear on a light background,
the system bar icons also follow the app's theme. English · Korean strings were added to each platform's resources.

## Verification

- Shared core: with the detail open, write a transcript file and change only the step to success. Verifies that the text arrives while the job is still `RUNNING`
  (`ReclyCoreTest`). Also tells apart empty result · corrupted file · re-fetch, jobs without transcription, authentication failure,
  transcription failure while a follow-up step is in progress, waiting for another device's transcription, and unreadable job snapshots.
- Android: ran on the separate Android 16 emulator `recly_ux_review` at the default font size and at 1.6× font scale. The final 4 tests check
  the save button position above the keyboard · continuing to edit after cancel, draft retention after back on another tab, moving to · focusing the error field,
  and the corrupted-audio error · retry (`MobileUxTest`). The signature differed from the existing install, so a new test device was created.
- Apple: of RecKit's 444 tests, 0 failures, with the existing 1 skipped. Includes draft · key delete confirmation, AVQueuePlayer error handling of a real corrupted file,
  and text refresh when a transcript arrives with the audio state preserved. On the separate iPhone 17 Pro / iOS 26.5 simulator `Recly UX Review`, keyboard · draft retention were run at the default font size and at
  `accessibility-extra-large` (`MobileUxTests`). Simulator UI tests need local signing that includes the keychain entitlement,
  so `make ios-ui-test` applies it.
- Windows: workflow switching · cancel · key input preservation · delete confirmation for keys in use, and decoder failure/retry were verified on the JVM.
  Real WASAPI recording and MSI installation cannot be verified on macOS.

| Final verification command | Actual result |
|---|---|
| `make test` | `BUILD SUCCESSFUL in 19s`; 123 actionable tasks: 9 executed, 114 up-to-date |
| JVM XML result tally | core 535 + Android 470 + Windows 361 = 1,366, 0 failures · errors · skipped |
| `make core` | `BUILD SUCCESSFUL in 7m 20s`; `xcframework successfully written out` |
| `make mac-test mac watch` | RecKit 444, the existing 1 skipped, 0 failures; `TEST SUCCEEDED` and Mac · watchOS `BUILD SUCCEEDED` |
| `make ios` | `BUILD SUCCEEDED`, including the final separation of the transcript subscription |
| `make android-ui-test` | All final 4 pass; `BUILD SUCCESSFUL in 19s` (1.6× font scale) |
| `make ios-ui-test IOS_SIM='Recly UX Review'` | Default font size `TEST SUCCEEDED`, large text also `TEST SUCCEEDED` |
| `git diff --check` | No output, exit code 0 |

The missing tracking of failed items and the data subscription that stayed alive after the edit model's teardown, both found during Apple run verification, were also fixed.
The transcript subscription starts after the initial result read, independently of the audio download · waveform generation (`RecordingDetail.swift:368`).
Screenshots of the screen layouts and the test logs are kept in the temporary evidence directories below. The final logs are
`/tmp/recly-ux-test-final.log`, `/tmp/recly-ux-core-final.log`, `/tmp/recly-ux-apple-final.log`,
`/tmp/recly-ux-android-latest.log`, `/tmp/recly-ux-ios-build-final.log`, `/tmp/recly-ux-ios-final.log`,
`/tmp/recly-ux-ios-large.log`.

## Initial observations and remaining real-device checks

The initial Android observation was made on a Galaxy SM-F966N with the Play install 0.1.0 (6), Korean, 1080×2520, 420dpi, font scale 1.0.
Before and after the keyboard appeared, the save button coordinates changed from `[948,185][1012,235]` to `[0,0][0,0]`, and after going back from the Settings tab and
returning to workflows, the edit screen was gone. The run verification after the fixes was done on a separate virtual device.

That one existing Clova recording completed with `segments=0` and the missing automatic refresh of the detail are separate problems. This change
clearly reports empty results, and does not conclude why that recording had an empty result. Transcription · playback on the latest build with a real account,
a real iPhone device, a foldable's inner screen · landscape mode, TalkBack · VoiceOver, and real Windows audio devices remain for pre-release QA.

Real-device logs · screens are kept in `/tmp/recly-android-transcribe/`, and this round's virtual device screens and test materials in `/tmp/recly-ux-review/`.
The user's real-device app data and keys were not erased, and no test materials were added to the repository. The version number was not changed.
