# UI/UX improvements applied and verified — 2026-09-10

## Results applied

A1 · A2 · B1–B4 of the [audit against official standards](ux-standards-audit.md) and the improvements to be handled in code have been applied.
While keeping bottom playback on mobile and top playback · split windows on desktop, the reading · search · copy · navigation behaviors were aligned.
The passes below are results for the stated automated check · build scope, and do not mean usability verification on every real device is complete.

| Item | Behavior applied | Main code evidence |
|---|---|---|
| A1 Header at large text | At narrow widths, the title and the actions are placed on separate rows. On wide, short screens, a short horizontal header is used | `android/app/src/main/kotlin/app/recly/android/ui/component/Table.kt:38` |
| A2 Tabs · state nodes | Tab names may take two lines, and the text measuring width matches the actual tab width. At large text, state nodes are laid out vertically | `android/app/src/main/kotlin/app/recly/android/ui/component/NavBar.kt:38`, `component/Nodes.kt:158` |
| B1 Input field borders | `inputBorder` separated from the decorative grid. Default and focus borders are distinct, and light · dark · high-contrast combinations are checked | Android `ui/theme/Colors.kt:34`, Apple `Design/SectionTable.swift:298`, Windows `ui/component/Fields.kt:91` |
| B2 Keyboard waveform | Android focus entry · 5-second left/right moves · focus exit; a highlight border on Android · Windows | Android `ui/RecordingDetailScreen.kt:267`, Windows `ui/RecordingsWindow.kt:424` |
| B3 iPhone landscape | Supports landscape left and right. Recording actions are reachable by scrolling even at short heights | `apple/RecPhone/RecPhone.xcodeproj/project.pbxproj`, `apple/RecPhone/RecPhone/RecordingView.swift:47` |
| B4 Using the transcript | Adds text selection, copy all, text/speaker search, and utterance time seek on four platforms | Android · Windows `ui/TranscriptReader.kt:32`, `apple/RecKit/Sources/RecKit/Jobs/TranscriptReader.swift:11` |
| Long transcripts | Per-result utterance-block cache and LazyColumn/LazyVStack. Repeated string appending replaced with StringBuilder | `core/src/commonMain/kotlin/recly/core/transcribe/TranscriptDocument.kt:11` |
| Android high contrast | Observes changes to system color contrast and high-contrast text. Reflects line weight · secondary colors · background shapes | `android/app/src/main/kotlin/app/recly/android/ui/theme/Theme.kt:245` |
| Watch | Apple Watch main screen scrolling · state line wrapping; Wear long names · transfer state line wrapping | `apple/RecWatch/RecWatch/RecordingView.swift:17`, `android/wear/src/main/kotlin/app/recly/wear/ui/MainScreen.kt` |
| First transcription setup | Optionally expandable guidance in the workflow list on Drive · the transcription step · keys · workflow selection | Each shell's `TranscriptionSetupHelp`, Apple shared `Workflow/TranscriptionSetupHelp.swift` |
| Long Apple action labels | FlowLayout proposes a constrained width to controls wider than the screen, and buttons show up to three lines | `apple/RecKit/Sources/RecKit/Design/FlowLayout.swift:51`, `Design/Buttons.swift:48` |

Copying a transcript copies the whole original text including timestamps, not the subset filtered by search. Search · copy work even without audio.
Time seek is disabled while recording and while audio is being checked/downloaded, and also for utterances outside the range of the downloaded audio.
When the title · job state refresh, the same transcript document is not rebuilt and the reading position is not reset.
Utterance blocks are split at 60 seconds or 1,200 characters, starting from the next segment. A single segment returned by the provider is not itself
cut to make up arbitrary timestamps. There is no separate performance guarantee for the case where one segment is very large.

The Android landscape 640×360dp · font scale 200% check reproduced an additional problem where the save · playback areas get pushed out, and it was fixed.
While typing on the keyboard, the tab bar and the duplicate header collapse, and the editing area shares the space left after the save row.
On short screens, the separate row for the tab icons is omitted and the waveform and the playback clock sit side by side. While typing in the transcript search,
the detail header · playback area collapse, and they are restored when the keyboard closes. The search tools and the text are also in one scroll area, so
the tools do not take up the whole height left for the text. No new forced onboarding or additional consent step was added.
In the final iPhone landscape screenshot, we also found that the last line of the transcription setup guidance was cut off. The expanded content was
changed to a plain vertical layout and the text's line limit was removed so that the full guidance shows.
The expanded/collapsed state is also provided as Korean · English accessibility values.

## Commands run and actual results

| Command / condition | Result |
|---|---|
| `make core` | `BUILD SUCCESSFUL in 7m 21s`; XCFramework built and staged into RecKit |
| `make test` | Final `BUILD SUCCESSFUL in 21s`, `123 actionable tasks: 6 executed, 117 up-to-date`; core 540 + Android 470 + Windows 365 = **1,375, 0 failures** |
| `make mac-test mac watch WATCH_SIM='Apple Watch SE 3 (40mm)'` | `TEST SUCCEEDED`, **of 450, the existing 1 skipped, 0 failures**; `BUILD SUCCEEDED` for both macOS and the 40mm Watch |
| `make ios-ui-test IOS_SIM='Recly UX Review'` | After building the current iOS / bundled Watch, **3 pass**, `TEST SUCCEEDED` |
| `ANDROID_SERIAL=emulator-5554 make android-ui-test` — 320dp · 200% · dark theme | **13 pass**, `BUILD SUCCESSFUL in 42s` |
| The same UI checks — landscape 640×360dp · 200% | The **12 configured at the time pass**, `BUILD SUCCESSFUL in 31s`; the high-contrast system callback check added afterwards is included in the 13-test run above |
| `ANDROID_SERIAL=emulator-5554 make android-ui-test ANDROID_TEST_CLASS=app.recly.android.ui.UiStandardsTest` | 320 · 360 · 412dp × 100 · 160 · 200%: **3 each in the 9 combinations, 27 passes in total**. Each combination checks the English · Korean titles and all tab names |
| `git diff --check` | No output, exit code 0 |

Android used the dedicated Android 16 AVD `recly_ux_review`, and iPhone used a dedicated iPhone 17 Pro / iOS 26.5 simulator.
No new recordings were made and no paid transcription was requested on real user devices or accounts.

- The Android text check confirms the actual right edge of the glyphs · the last visible character · ellipsis. Center-aligned text uses the tab's actual width,
  and dashboard nodes and tabs with the same name are told apart. It does not judge by `hasVisualOverflow` alone.
- The Android transcript check confirms, on a 2-hour · 120-utterance sample, the display of search results near the end, time seek, and copy all during search.
  It also checks that seek is disabled without audio, no search results, the waveform's 5-second keyboard moves, and Tab moving on to the next button.
- The high-contrast check keeps the already-mounted theme while turning high-contrast text and color contrast on and off separately, to confirm the callbacks take effect.
  It runs only on the emulator, and the changed settings are restored in `finally`. The product uses public APIs, and the system setting names in the test
  were checked against [Android 16 AOSP Settings](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/provider/Settings.java).
- The iPhone UI check confirms save above the keyboard · draft retention, reaching the recording actions at the maximum Korean accessibility text size, and, in landscape, the recording · workflow
  guidance · new workflow actions. Actual screen captures were also kept.
- The Apple unit checks gained a real SwiftUI layout check in which a long button grows in height and wraps inside a constrained-width NSHostingView.
- The core's 7,200-segment block check confirms original text preservation · stable search targets · the time of the next paragraph. This JVM test recorded
  0.004 seconds, but that number is not read as the app's time to first display · scroll frame rate · real-device memory usage.

Logs · XML · screen captures are in `build/reports/ux-remediation-2026-09-10/`.
`matrix.json`, `android-320-dark-200-final.xml`, `android-landscape-200.xml`, `jvm-counts.json`,
`apple.log`, `ios-ui.log`, `ios-complete/manifest.json` are the final evidence. Earlier failed · intermediate logs were not added to the final pass counts.

## Limits of the verification

- Windows code compilation and the 365 JVM checks passed on a macOS host. Narrator · scaling · keyboard input on real Windows,
  WASAPI capture, and running MSI packaging were not performed.
- On macOS, the app build and the shared model/layout tests were run. Transcript selection · copy · VoiceOver were not exhaustively verified by hand in the actual full window.
- Apple Watch passed the build for the 40mm target. Crown · bezel · screen reader on a physical Watch/Wear and user operation on the smallest screen remain to be checked separately.
- Exhaustive mobile screens / combinations of all languages · themes, real Bluetooth · audio routes, 30-minute · 2-hour transcript performance in release builds,
  and target user observation remain. The matrix above is the scope of the title · tab checks and does not mean every screen in the app passed.

The code changes and the stated automated verification are complete; commit · push · a new release build for the stores were not part of this work.

The dedicated Android AVD's screen size · font scale (initial value 1.6) · night mode were restored and it was shut down. The dedicated iPhone simulator was also confirmed to be shut down.
