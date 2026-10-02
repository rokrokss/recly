# UI/UX audit against official standards — 2026-09-10

> Follow-up: what follows is the audit record from before the fixes. How the findings were applied and the latest run results are
> recorded in [UI/UX improvements applied and verified](ux-remediation.md).

## Verdict

The current state **falls short of a verdict that UI/UX optimization is complete.** The 5 functional defects found earlier are fixed,
but this time 2 legibility defects were reproduced on an actual Android screen. The code also has gaps in input field identification and keyboard operation.
Passing functional tests, the presence of accessibility support code, and actual usability are each different evidence. No arbitrary overall score is given.

This work is web research · code comparison · diagnosis · reporting. It did not modify product code or proceed with a store release.
Below, P2 is a problem that hurts usability under specific screen conditions or input methods, and P3 is an improvement candidate that needs further usage or performance measurement.
Confirmed defects, design constraints, and risks not yet measured are kept apart.

## Official standards compared against

- **Touch and text scaling:** Android guides a 48dp interaction area and UI verification at the maximum font scale of 200%.
  Apple's current table distinguishes iOS · watchOS default controls 44pt / minimum 28pt from macOS default 28pt / minimum 20pt.
  So a small visual icon was not immediately judged a ‘below-44pt violation’.
  [Android API defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults),
  [Android 14 font scaling](https://developer.android.com/about/versions/14/features),
  [Apple Accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility).
- **Text · control contrast:** 4.5:1 for normal text and 3:1 for the identifying information of required controls were the measurement criteria.
  Decorative dividers and the borders of buttons that their text already identifies were not failed wholesale. Disabled controls were also looked at separately.
  [W3C text contrast](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html),
  [W3C non-text contrast](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html).
- **Input methods:** Besides touch and drag, the main functions must be reachable by keyboard and assistive technology, and keyboard focus must be visible.
  [Microsoft Keyboard interactions](https://learn.microsoft.com/en-us/windows/apps/develop/input/keyboard-interactions),
  [Android Semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics),
  [W3C Focus Visible](https://www.w3.org/WAI/WCAG22/Understanding/focus-visible.html).
- **Layout:** Respond to changes in size · orientation · text · language while keeping the product consistent. Apple recommends supporting both orientations
  but allows a single orientation where needed, so portrait lock was not immediately declared a review violation.
  [Apple Layout](https://developer.apple.com/design/human-interface-guidelines/layout),
  [Android screen size support](https://developer.android.com/develop/ui/compose/layouts/adaptive/support-different-display-sizes).
- **Long content and first use:** We referred to the direction of composing only the needed part of lists whose length is hard to predict, and of giving short guidance close to the current task.
  [Android Lazy lists](https://developer.android.com/develop/ui/compose/lists),
  [Apple Onboarding](https://developer.apple.com/design/human-interface-guidelines/onboarding).

WCAG is a web standard, so here it served as a measurable design criterion for native apps. This report is not an official accessibility certification or
a legal conformance judgment. The source documents were looked up on the day of the audit, and differences between the implementation's frameworks and platforms were taken into account.

## Defects reproduced by running

### A1. [P2] At large text, the workflow screen title disappears

- **Condition:** separate Android 16 AVD, 945×1680px / 420dpi = 360×640dp, font scale 200%, English.
- **Action:** Open the Workflows tab. The `Manage secrets` · `+ New` buttons on the right take up all of the header's horizontal space.
- **Actual:** The title's layout width is **0px**. Enlarged text should make the title easier to read, but the screen name itself is gone.
- **Code:** The fixed `Row` action area at `android/app/src/main/kotlin/app/recly/android/ui/WorkflowsScreen.kt:89` collides with
  the title column at `component/Table.kt:48`, which only gets the remaining width. The rename · close combination in the detail, which uses the same header, is also to be checked.
- **Fix:** At narrow width · large text, separate the title from the action row or group the secondary actions into a menu. Secure a minimum available width for the title.
  Avoid forcibly shrinking the text.
- **Acceptance criteria:** At 320 · 360 · 412dp, English · Korean, 100 · 160 · 200%, the title and all main actions are fully readable and tappable.

### A2. [P2] At large text, the bottom tab names are clipped

- **Actual under the same condition:** Of the 9 characters of `Workflows`, the visible text ended at **7**. In the allotted width of 236px the next line is not
  shown, so the end disappears. That the original text remains in the accessibility tree does not resolve the visual clipping.
- **Code:** The combination of the quarter widths at `android/app/src/main/kotlin/app/recly/android/ui/component/NavBar.kt:49` and
  `maxLines = 1` at `:56`. The `Workflow` node title on the main screen also shrinks to `Work…` under the same condition.
- **Fix:** Decide on a tab layout · row height · line wrapping that handles large text, or on short, clear localized names.
  Keep the selection state · touch size · assistive technology name. Also compare applying the standard navigation component.
- **Acceptance criteria:** Every tab name is fully identifiable, and the tabs do not cover main actions in landscape layout or with the keyboard showing.

Screenshots and the final probe results are in `build/reports/ux-standards-2026-09-10/`.
Looking at `record-360-200.png`, `workflows-360-200.png`, `final-result.xml`, and `final-values.txt` together confirms the reproduction.

## Gaps confirmed by code and numbers

### B1. [P2] Empty input fields lack border contrast

- **Finding:** In normal mode, `grid` is **1.25:1** on the light surface and **1.17:1** on the dark surface.
  An empty input field has no text inside, so the actual input area has to be recognized by its border, but the difference between the border and the surrounding background is small.
- **Evidence:** Apple's empty `TextField` at `apple/RecKit/Sources/RecKit/Design/SectionTable.swift:308` and its grid border at `:321`,
  Windows' grid border at `windows/app/src/main/kotlin/app/recly/windows/ui/component/Fields.kt:89`,
  Android's `outline = grid` mapping in `android/app/src/main/kotlin/app/recly/android/ui/theme/Theme.kt`.
- **Fix:** Separate the decorative grid from a token for input field borders. Secure 3:1 or more for the parts of an empty field's background · border needed for identification.
  Check the focus state and error state separately too. Darkening every divider is not needed.
- **Limitation:** This is the result of computing and comparing the palette and the paths that use it. Not every composited background on actual macOS · Windows screens was measured by pixel.

### B2. [P2] Keyboard access to the waveform is incomplete on each platform

- **Android:** `RecordingDetailScreen.kt:277` has `setProgress` for TalkBack, but that Canvas has no path for
  keyboard focus or left/right key handling. Value changes by assistive technology and general keyboard navigation are separate things.
- **Windows:** `RecordingsWindow.kt:434` has left/right key handling and `:443` has `focusable()`. But this Canvas has no path
  that reads the focus state to display it or that connects a focus indication, so its position is hard to confirm by eye.
- **Apple:** `RecordingDetail.swift:465` has the adjustable accessibility action, and `:478` has focus and arrow-key handling paths, so it can serve as the reference for comparison.
- **Fix:** Add keyboard access · adjustment to Android, and add a clear focus indicator to the Windows waveform.
  Do not generalize that buttons as a whole lack keyboard support. The state indication of the default clickable also needs to be checked separately.
- **Acceptance criteria:** Tab into the waveform → see the current focus → move with the left/right keys → play/pause → leave to another control.
  Verify end to end with a real keyboard and Narrator on Windows, and with an external keyboard and TalkBack on Android.

### B3. [P2, design constraint] iPhone supports portrait only

- `apple/RecPhone/RecPhone.xcodeproj/project.pbxproj:659`, `:688` specify portrait only, and the device family is also limited to iPhone.
- This is a constraint for reading long transcripts, landscape mounting, and external keyboard use. Decide whether the target users need it, then consider supporting both orientations.
  The current iPhone-only product is not failed just because iPad screens were not inspected.
- The current dashboard scroll branch depends only on the accessibility text size at `RecordingView.swift:49`. This is not a change that ends with unlocking the orientation setting;
  the recording · detail · edit · consent screens at small heights must be verified together.

### B4. [P2, product improvement] Few actions make use of the transcript

- Android `RecordingDetailScreen.kt:479`, Apple `RecordingDetail.swift:637`, and Windows `RecordingsWindow.kt:526`
  show the utterance time · speaker · text as plain text. In those detail screens and their parent screens we found no text selection/copy, no text search,
  and no tapping a time to jump to playback of that span.
- There is a path to open the Drive original externally, but reusing a sentence just read in the app or listening to it again takes extra navigation.
- **Recommended order:** text selection · copy all → timestamp seek → search within long transcripts. On mobile use a secondary menu · share sheet;
  on desktop use selection · right-click · keyboard shortcuts. There is no need to line up every feature as buttons along the bottom on mobile.
- This is a product improvement, kept apart from bugs that violate an existing contract. If the actual target users consume results only through Drive · webhooks, its priority can be lowered.

## Risks that need more run verification

| Item | Evidence and risk in the code | Verification needed |
|---|---|---|
| Long transcript performance | Android `RecordingDetailScreen.kt:150` and Windows `RecordingsWindow.kt:245` compose an entire plain Column; Apple `RecordingDetail.swift:351` composes an entire VStack. Utterance blocks are also built in the view every time. Kotlin joins the same speaker's text by cumulative string copying (`:499`, Windows `:545`) | In a release build, open 30-minute · 2-hour · many-utterance transcripts and measure time to first display · scroll lag · memory. If needed, apply an utterance-block cache and LazyColumn/LazyVStack. No measured judgment that it is slow has been made yet |
| High-contrast setting | Apple · Windows have paths that observe system contrast, but the Android theme reads only dark · font scale · reduce motion (`theme/Theme.kt:109`). Text effects the system applies on its own must be told apart from how the app handles shapes · backgrounds | With Android's high-contrast setting ON/OFF, check the actual text · input fields · selection states · backgrounds. Do not immediately declare ‘Android high contrast doesn't work at all’ |
| Small watches · enlarged text | The Galaxy Watch main screen provides scrolling (`android/wear/.../ui/MainScreen.kt:100`). Apple Watch relies on a fixed VStack and some shrinking (`RecordingView.swift:17`) | At the smallest supported size, check permission denial · transfer pending · long workflow names · large text · VoiceOver/TalkBack · crown/bezel behavior. See [Wear OS accessibility](https://developer.android.com/training/wearables/accessibility) |
| Understanding the setup needed for a first transcript | The main screen shows the device code · workflow · state first. The first-run sample Memo is a Drive job, and using transcription requires knowing about separate setup | Observe target users performing ‘first recording → transcription → using the result’ without explanation. Place optional guidance · a transcription example where help is needed. Do not wholesale remove the graph meant for developers/experienced users |
| Real-device · desktop accessibility | Current verification is concentrated on some emulator · model tests | Verify one-handed operation and VoiceOver/TalkBack on physical iPhone/Android, Windows Narrator · 125/150/200% scaling, macOS keyboard navigation and minimum window size |

## What already has evidence

- Normal text contrast: the body · secondary text · accent · error · warning text in the light and dark themes was computed. The lowest of these combinations is
  accent · error on the light background at **4.66:1**; secondary text is **6.07:1 or more**, and main button text is **5.00:1 or more**.
  `contrast.json` has all the computed values. The Apple · Windows palettes use the same token values.
- Android's shared buttons · chips specify a minimum of 48dp. Apple's shared chips · playback controls also have size · name · state wiring.
  This is not stretched into a result of measuring every individual control.
- The waveform supports tap · drag and value changes by assistive technology. So the remark that it is ‘operable only by dragging’ does not hold.
- Recording/transcription/empty result/failure notices, discard-input confirmation, delete confirmation, and automatic refresh of transcription · recording state have evidence from the earlier improvements and this run's base tests.
- Keeping bottom playback on iPhone · Android and top playback · split windows on macOS · Windows is a sound direction. Consistency means aligning behavior · terms · states;
  it was not read as a requirement to make the screen layouts identical.

## Commands run and actual results

| Command/check | Actual result |
|---|---|
| `make test` | `BUILD SUCCESSFUL in 1s`; `123 actionable tasks: 1 executed, 122 up-to-date`; existing JVM results core 538 + Android 470 + Windows 365 = 1,373, 0 failures |
| `make apk` | Debug APK of the current code built successfully (`apk.log`). Installed only on the dedicated emulator |
| `adb -s emulator-5554 shell wm size 945x1680` and `settings put system font_scale 2.0` | Set up the 360×640dp · 200% condition at 420dpi |
| `ANDROID_SERIAL=emulator-5554 make android-ui-test` | The existing 6 pass under that 200% condition too; `BUILD SUCCESSFUL in 22s` |
| `ANDROID_SERIAL=emulator-5554 make android-ui-test ANDROID_TEST_CLASS=app.recly.android.ui.UiStandardsProbe` | 2 of the 2 final 200% probes fail; `BUILD FAILED in 11s`; A1 · A2 reproduced |
| The same final probes run at 100% | Both pass; `BUILD SUCCESSFUL in 10s`; header · tabs both show 9/9 visible characters. Recorded in `control-final-result.xml` and `control-final-values.txt` |
| Palette sRGB contrast computation | Values saved in `contrast.json` |
| `cua-driver list_apps '{}'` | Recly Mac was not running. Since the installed version could not be confirmed to match the current source, that app's UI was not used as verification of the latest code |

**Probe precision:** At first only `hasVisualOverflow` was checked, but we found it could give false positives because, at the default size, the paragraph width is wider than the text.
The final probe checks the actual right edge of the glyphs (1px tolerance), the position of the last visible character, whether there is an ellipsis,
and a zero title width. The same probe was compared at 100% and 200%. The failure counts of the early intermediate probes were not added to the final result.

The existing 6 UI tests check button presence · clickability · position above the keyboard · model state and so on. They do not check whether the whole text is readable
or whether the header leaves room for the title, so they passed despite A1 · A2. Also, some edit entries use model calls,
so they cannot be regarded as tests that verify every real entry flow (`MobileUxTest.kt:51`).

This UI run is limited to **Android · English**. Switching to Korean was also attempted with the system app-language command, but the actual screen
stayed in English, so Korean verification is not included. iPhone UI · macOS UI · Windows UI · watch UI were not run-verified this time.
The earlier 449 Apple tests and build success are in the [previous fix record](ux-detailed-review.md) and are not results of this rerun.

The temporary probe tests were kept in the evidence directory and then removed from the source tree. Existing uncommitted changes were kept.
After removing the probes, `make test` was `BUILD SUCCESSFUL in 2s`, and the XML tally stayed at 1,373 with 0 failures
(`final-tests.log`, `jvm-counts.json`). `git diff --check` exited with code 0 and no output.
The emulator's screen size was restored to its original value and the font scale to the first observed value of 1.6, and then that dedicated emulator was shut down.

## Next steps toward an optimization-complete verdict

1. **Confirmed defects and input accessibility:** fix A1 · A2, separate the input field border for B1, fill in keyboard · focus support for B2. Turn the screen clipping probe into a permanent regression check.
2. **Per-platform screen verification:** mobile 320 · 360 · 412dp/pt, Korean · English, 100 · 160 · 200% / maximum Dynamic Type, light · dark · high contrast.
   Check desktop minimum window · split · keyboard · scaling, and watch minimum screen · large text, each. Instead of every combination, start with the risky combinations, but record the unverified ones.
3. **Using the result:** fill in copy · time seek first, then add search and first-transcription guidance depending on the target users. Also explicitly decide the scope of iPhone orientation support.
4. **Usage · performance evidence:** on a release build on real devices, perform recording → transcription → finding the desired span → reusing the text end to end.
   Record where target users got stuck and their mis-operations in observation, measure the display · scroll performance of long transcripts, and then update the completion verdict.

Raising the unit test count alone or only moving the bottom buttons cannot substitute for the completion verdict.
