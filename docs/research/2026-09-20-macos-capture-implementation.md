# macOS meeting audio capture improvements — 2026-09-20

## Scope

This change covers input selection, system capture, stream alignment and recovery for macOS meeting recording.
The Google Drive/OAuth and sync changes already in the working tree were kept.

- Automatic microphone selection: the saved direct choice → the single active input of a confirmed meeting app → the macOS default input.
  A browser is preferred only when the meeting window title owned by that browser can be confirmed.
- The choice is kept while muted, device changes settle for 1 second, disconnection gets a 2-second grace period, and the saved choice is restored on reconnection.
  The full PCM format and connection state of the chosen device are checked periodically.
- Only Recly's AudioUnit is bound to the chosen device. The OS default input and output and the meeting app's device/mute are not changed.
  Voice processing is off by default as before; when turned on, the processing node is set up before the microphone is bound.
- The actual output subdevice was removed from the system aggregate. The sample rate is set only on the private virtual device.
  Tap and aggregate format changes are watched, and the ratio between actual capture timestamps and frame counts is checked too.
- Audio copied in the callback is converted and saved on a separate queue. The queue is capped at 2 seconds, and microphone processing gets a 0.6-second delay
  to absorb the output latency of the system resampler. On stop, both the pending buffers and the converter tails are saved.
- Streams that have capture timestamps are aligned by the host clock. Large system buffers are not cut down to the microphone callback size and discarded,
  and each input's capture time is applied so small clock errors do not accumulate over the whole recording.
- A wrong delivery rate, missing timestamps and buffer shortfalls are treated as recoverable. System recovery is independent of the microphone,
  and a microphone restart retries temporary Bluetooth connection switches. Unrecovered losses are also recorded in the metadata.
- The current microphone, system output and recovery status are shown with the existing theme and text sizes. The setting is a single row: automatic/microphone choice.
- `capture-diagnostics.json` records devices, rates, measured rates, OS, and recovery and loss events, up to 512 entries.
  The file is saved only in the recording folder and is not registered for upload or webhooks.

## Verification

Commands run and their actual results:

| Command | Result |
| --- | --- |
| `make core` | `BUILD SUCCESSFUL in 1m 53s`; XCFramework staging done |
| `make test` | `BUILD SUCCESSFUL in 9s`; 123 actionable tasks, 1 executed / 122 up-to-date |
| `make mac-test` | `Executed 494 tests, with 1 test skipped and 0 failures`; `TEST SUCCEEDED` |
| `make mac ios watch` | `BUILD SUCCEEDED` 3 times; Mac, iPhone simulator and Apple Watch simulator builds done |
| `git diff --check` | no output, exit code 0 |

The regression tests check the 440 Hz frequency and continuous energy for 8/16/24/44.1/48 kHz input.
They verify detection of actual 24 kHz labeled as 48 kHz, large input batches, missing timestamps, the queue cap during slow file processing,
and the path where delayed microphone and system buffers at stop all end up in the three tracks.
This includes a test where audio events at the 80-second mark are aligned within 3 ms even with an input clock 1000 ppm fast.

Main test locations:

- `apple/RecKit/Tests/RecKitTests/CapturedAudioTests.swift:105` — alignment under long-running clock error
- `apple/RecKit/Tests/RecKitTests/CapturedAudioTests.swift:135` — frequency/energy for five input rates and large buffers
- `apple/RecKit/Tests/RecKitTests/MeetingRecorderTests.swift:23` — saving the delayed tails of the three tracks on stop
- `apple/RecKit/Tests/RecKitTests/MicrophoneSelectionTests.swift:6` — automatic/direct choice, mute, connection switches
- `apple/RecKit/Tests/RecKitTests/ProcessTapCaptureTests.swift:23` — no physical output subdevice

Detailed command output is in `build/verification/audio-core-build.log`, `audio-jvm-test.log`,
`audio-mac-test.log` and `audio-apple-builds.log`.

## Limits of real-device verification

The automated tests use synthetic audio and the real AVAudioConverter/AAC file path.
The one real-microphone smoke test was skipped because the opt-in environment variable was not set.
The device list with AirPods connected was checked, but this change did not record real Teams/Meet/Zoom calls
or compare the microphone quality the other side hears. Bluetooth profile switching, the real-device behavior of the tap-only aggregate
and the actual quality heard by the other side still need separate call verification. Passing synthetic-audio tests is not treated as real-device call verification.
