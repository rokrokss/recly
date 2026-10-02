# Mobile and watch microphone recording improvements — 2026-09-20

## Scope and behavior

Phones and watches record only the microphone. System audio exists only in macOS and Windows meeting mode.
Without adding a settings screen or a guidance step, input selection and switching and the recording save path were improved.

- iPhone: selects automatically in the order wired/USB, Bluetooth HFP/BLE, built-in microphone. If there are several devices of the same kind,
  the current input is kept. If a request for an external input is refused, the OS default input is used, and it is tried again on reconnection or a new recording.
- Apple Watch: allows HFP input on watchOS 11+. watchOS does not support the app's `setPreferredInput`, so
  the OS makes the actual choice among the connected inputs. watchOS 10 keeps the default recording session.
- Apple shared: when the input device or the actual format changes, the same recording continues with a new engine and converter. The hardware callback
  copies only the buffer and timing information, and file handling runs on a separate queue. On stop or switch, the audio left in the queue and the converter is saved.
  During a phone call or Siri, reconnection is postponed, and the silent gap is closed only after the actual recording restart succeeds. Earlier notifications arriving after stop are ignored.
- If the OS audio service is lost or reset, the app hands off to the existing save and stop path and reinitializes on the user's next recording action.
  It follows [Apple QA1749](https://developer.apple.com/library/archive/qa/qa1749/_index.html), which says not to activate the audio session after a reset without a user action.
- Android and Galaxy Watch: requests wired/USB, Bluetooth, then the OS default input with `MediaRecorder.setPreferredDevice`, in that order,
  and checks the actual `routedDevice`. If refused or not applied within 5 seconds, it falls back to the default input. Vendor-specific inputs follow the OS choice.
  A device switch does not re-create the recorder or the segment file. Device notifications are coalesced in 250 ms units and the route is checked every second.
  Call mode, SCO and other apps' communication device settings are not changed. Missing route information alone does not end the recording.
- On Android, input and recorder callbacks are applied only when they match the current recorder. Silent-gap callbacks are also ignored after stop.

## Verification

| Command run | Actual result |
| --- | --- |
| `make core` | `BUILD SUCCESSFUL in 8s` |
| `make test apk` | `BUILD SUCCESSFUL in 6s`, `BUILD SUCCESSFUL in 9s` |
| `make wear-apk` | `BUILD SUCCESSFUL in 10s` |
| `make ios watch mac` | `BUILD SUCCEEDED` 3 times |
| `make ios-kit-test` | `Executed 500 tests, with 1 test skipped and 0 failures`; `TEST SUCCEEDED` |
| `make mac-test` | `Executed 501 tests, with 1 test skipped and 0 failures`; `TEST SUCCEEDED` |

The Android shared recording module has 65 tests with 0 failures, errors and skips. This includes 9 new routing tests.
On Apple, 3 selection policy tests were added, plus 4 tests for switching to another microphone with the same format, ignoring duplicate notifications, retrying after a temporary start failure, and stopping during a retry.
The real AAC file path confirms that switching to a microphone with the same format saves the audio before and after into a single recording.

Main implementation and test locations:

- `apple/RecKit/Sources/RecKit/Recorder/IOSAudioInput.swift:67` — input selection, initialization with the actual format
- `apple/RecKit/Sources/RecKit/Recorder/IOSAudioInput.swift:150` — saving the queue on stop, blocking earlier notifications
- `apple/RecKit/Sources/RecKit/Recorder/IOSAudioInput.swift:224` — handling input changes
- `android/recording/src/main/kotlin/app/recly/recording/MicrophoneRouting.kt:35` — request and actual route verification, fallback to the default input
- `android/recording/src/main/kotlin/app/recly/recording/MicrophoneRouting.kt:142` — cleanup of stop and delayed notifications
- `apple/RecKit/Tests/RecKitTests/SegmentedRecorderTests.swift:248` — audio preserved after a switch to a device with the same format
- `apple/RecKit/Tests/RecKitTests/SegmentedRecorderTests.swift:303` — stop while waiting to reconnect
- `android/recording/src/test/kotlin/app/recly/recording/MicrophoneRouteStateTest.kt:8` — input selection, refusal, reconnection and silent-gap tests

Logs: `build/verification/mobile-core.log`, `mobile-android-final.log`, `mobile-wear-build.log`,
`mobile-apple-final.log`. Wear debug packaging can be reproduced with the new `make wear-apk` command.

## Scope of real-device checks

The real-microphone smoke test in the Apple tests was skipped once per platform because the opt-in setting was not set.
This verification consists of synthetic audio, the real converter/AAC file path, simulator builds and JVM state tests.
No verification reproduced Bluetooth connect/disconnect, call interruptions,
long background recording or OS audio service resets on a real iPhone, Apple Watch, Android phone or Galaxy Watch.
There is no guarantee that connecting a particular headset always switches to that microphone on every OS and device.
