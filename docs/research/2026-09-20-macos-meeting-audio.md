# macOS meeting audio capture comparison

Researched: 2026-09-20. Scope: a design that keeps the AirPods microphone, also records the other meeting participants, and adds no new disruption to the call. Official product documentation and public source code were checked. The improvements below are proposals and do not change the rules in `docs/recly.md`. No external project was run and no real call was recorded.

## Criteria

- The meeting app's microphone choice, the macOS default microphone and Recly's microphone choice can differ from one another.
- The quality of Bluetooth call mode itself and the pitch changes and periodic silence that arose in Recly are separate problems.
- An API name or a project's popularity alone cannot guarantee AirPods stability. The comparison covers device changes, format changes, timing information and handling of data shortfalls.
- For closed-source apps, only publicly documented behavior was checked. Their internal capture APIs or sample-rate correction algorithms are not guessed at and written up as fact.

## Meeting apps and commercial meeting-note apps

| Product | Behavior checked | What it means for Recly |
| --- | --- | --- |
| Teams | The speaker and the microphone are chosen separately inside the app. The Computer audio default uses the computer's default devices. | Default-device mode and a specific device choice must be kept apart. |
| Google Meet | The microphone and speaker are chosen in the audio settings before and after joining. | It cannot be assumed that the input the browser picked is the same as the macOS default input. |
| Zoom | Officially supports choosing AirPods directly as the microphone and speaker. It also warns that with Bluetooth the microphone quality other participants hear can drop. | AirPods microphone support is a normal requirement. Bluetooth quality limits must be kept apart from extra distortion added by Recly. |
| Granola | Captures the device microphone and system audio. Its official troubleshooting documentation tells users to match the OS default microphone and the meeting app's microphone. | A capture model close to Recly's, and it does not guarantee that it automatically follows the meeting app's input in every case. |

Sources: [Teams audio settings](https://support.microsoft.com/en-us/teams/meetings/manage-audio-settings-in-microsoft-teams-meetings), [Meet audio connection](https://support.google.com/meet/answer/10409699?hl=en-NZ), [Zoom Bluetooth guidance](https://support.zoom.com/hc/en/article?id=zm_kb&sysparm_article=KB0058146), [Granola audio troubleshooting](https://www.granola.ai/blog/granola-integration-troubleshooting-common-issues-solutions), [Granola's capture description](https://www.granola.ai/security).

Meet's built-in recording saves the meeting content to the organizer's Drive, and Zoom local recording also supports a separate audio file per participant. These are built-in features of the meeting services. They must not be treated as direct precedents for an implementation where Recly, a standalone app, captures the OS microphone and output. [Meet recording](https://support.google.com/meet/answer/9308681?hl=en-GB), [Zoom local recording](https://support.zoom.com/hc/en/article?id=zm_kb&sysparm_article=KB0076922).

## Constraints found in Apple APIs

### Microphone choice and system audio

A Core Audio process tap is an API that captures a process's **output audio**. It is not an API that automatically duplicates the raw input of the microphone a meeting app uses. Apple's official sample also uses the tap as the input of an aggregate device. [Apple Core Audio tap sample](https://developer.apple.com/documentation/coreaudio/capturing-system-audio-with-core-audio-taps).

`kAudioProcessPropertyDevices` provides the list of devices a process uses, split by input/output scope. This was confirmed in the installed SDK `AudioHardware.h:1958–1961`. So an implementation that inspects the input device of the active meeting process can be considered. However, identifying the right meeting among multiple browser processes, tabs, virtual devices and multiple inputs, and keeping track of it, has to be verified separately. It does not mean the three meeting apps expose their own settings to outside apps in a uniform way. [Apple API](https://developer.apple.com/documentation/coreaudio/kaudioprocesspropertydevices).

### The ScreenCaptureKit alternative

From macOS 15, ScreenCaptureKit provides system audio and a separate microphone output. A specific microphone can be chosen with `microphoneCaptureDeviceID`; if none is set, the system default microphone is used. System audio follows the configured sample rate/channel count, but the microphone output follows **the native format of the chosen microphone**. So even with a single API, it must not be assumed that the two inputs have the same format. This was cross-checked against the installed SDK `SCStream.h:25–26,298–300,358–365` and the [WWDC24 session](https://developer.apple.com/videos/play/wwdc2024/10088/).

Recly's minimum macOS version is 14.4, so moving the microphone to ScreenCaptureKit as well would need an older-version path or a minimum-version policy. There is no evidence that switching APIs automatically solves the Bluetooth problem.

### Why AirPods must not be pinned to a specific sample rate

Apple explains that when a Bluetooth microphone is active, the device switches to a call input/output mode and listening quality can drop. However, recent AirPods and OS versions also have high-quality recording and call improvements. So a fix that always classifies AirPods as a 16/24 kHz device, or unconditionally reinterprets them as 24 kHz, is inappropriate. [Bluetooth mode guidance](https://support.apple.com/en-us/102217), [Apple's announcement of high-quality AirPods recording](https://www.apple.com/newsroom/2025/06/airpods-now-more-versatile-with-studio-quality-audio-recording-and-camera-remote/).

The WWDC25 samples around `bluetoothHighQualityRecording` cannot be carried over as a macOS solution either. In the installed macOS SDK `AVCaptureSession.h:590`, `configuresApplicationAudioSessionForBluetoothHighQualityRecording` is declared `API_AVAILABLE(ios(26.0)) API_UNAVAILABLE(macos, ...)`. Product feature support and the availability of an API on a specific platform must be kept apart. [WWDC25 audio recording APIs](https://developer.apple.com/videos/play/wwdc2025/251/).

## Open-source implementation comparison

The links below are pinned to the commits read during the research. The behavior of the source is kept apart from the developer intent written in comments.

### Chromium: tap-only aggregate and Bluetooth transition checks

`f741c8c27fd492b2f3fae0cfe0d698170ffaebec`

Chromium's macOS CATap loopback implementation also puts only the tap in the aggregate and no physical output subdevice. The output device to capture can be set as the tap's target, so this does not mean the design does not know the output path. [aggregate setup:817–841](https://chromium.googlesource.com/chromium/src/+/f741c8c27fd492b2f3fae0cfe0d698170ffaebec/media/audio/mac/catap_audio_input_stream.mm#817)

It keeps the capture time with `inInputTime.mHostTime`, and during a Bluetooth profile switch it does not use a buffer whose channel count or frame count differs from what is expected. It detects aggregate rate and output device changes and requests a restart, and the macOS 14 path reads the actual output's virtual-format rate and applies it to the aggregate configuration. [timing and buffer checks:1071–1142](https://chromium.googlesource.com/chromium/src/+/f741c8c27fd492b2f3fae0cfe0d698170ffaebec/media/audio/mac/catap_audio_input_stream.mm#1071), [change handling:1404–1484](https://chromium.googlesource.com/chromium/src/+/f741c8c27fd492b2f3fae0cfe0d698170ffaebec/media/audio/mac/catap_audio_input_stream.mm#1404), [rate selection:1583–1604](https://chromium.googlesource.com/chromium/src/+/f741c8c27fd492b2f3fae0cfe0d698170ffaebec/media/audio/mac/catap_audio_input_stream.mm#1583)

This code is Chromium's system audio loopback implementation. It is not stretched into code that explains Google Meet's closed server-side recording or its whole microphone processing. It is highly worth referencing directly because it is the same macOS tap family as Recly and handles timing and format transitions.

### OBS Studio: device identifiers, timing information, reinitialization

`b5bfb79093e26ce4b0e71d999f59b6b22f961121`

- The macOS microphone source stores the device UID. `default` is one of the options, and a specific device can also be chosen. [mac-audio.c:79–123](https://github.com/obsproject/obs-studio/blob/b5bfb79093e26ce4b0e71d999f59b6b22f961121/plugins/mac-capture/mac-audio.c#L79-L123)
- It reads the AudioUnit input format and passes the actual frame count and a timestamp converted from `mHostTime` along with the audio. [format](https://github.com/obsproject/obs-studio/blob/b5bfb79093e26ce4b0e71d999f59b6b22f961121/plugins/mac-capture/mac-audio.c#L248-L325), [callback](https://github.com/obsproject/obs-studio/blob/b5bfb79093e26ce4b0e71d999f59b6b22f961121/plugins/mac-capture/mac-audio.c#L391-L416)
- It watches device state, physical format and default input changes, and tears down and retries the capture. [change handling](https://github.com/obsproject/obs-studio/blob/b5bfb79093e26ce4b0e71d999f59b6b22f961121/plugins/mac-capture/mac-audio.c#L452-L508)
- ScreenCaptureKit audio uses the format and presentation timestamp of each `CMSampleBuffer`. [mac-sck-common.m:318–351](https://github.com/obsproject/obs-studio/blob/b5bfb79093e26ce4b0e71d999f59b6b22f961121/plugins/mac-capture/mac-sck-common.m#L318-L351)

The patterns for Recly are to keep the device choice as an identifier, not to throw away the capture time, and to handle a format change as a new capture configuration. This research alone does not claim that OBS detects every wrong format descriptor from frame count/time.

### Screenpipe: excluding the actual output device from the capture aggregate

`288989a4aa69574f50c6978de47ca7f70e4eb51a`

- System audio is captured with a Core Audio process tap and a **private aggregate that contains only the tap**. It does not put the actual output device in `sub_device_list` and does not set `main_sub_device` either. A comment explains the reason: opening and clocking the actual speaker can race with the meeting app's device start. [macos.rs:833–944](https://github.com/screenpipe/screenpipe/blob/288989a4aa69574f50c6978de47ca7f70e4eb51a/crates/screenpipe-audio/src/core/process_tap/macos.rs#L833-L944)
- It sets the virtual aggregate's rate to the tap format, then reads it back to confirm. It is not code that changes the actual speaker's rate. When the tap format or the aggregate rate changes, it marks that capture disconnected and builds a new configuration. [listener](https://github.com/screenpipe/screenpipe/blob/288989a4aa69574f50c6978de47ca7f70e4eb51a/crates/screenpipe-audio/src/core/process_tap/macos.rs#L504-L568)
- Because it is an always-on recording product, it opens a Bluetooth microphone by default only when a meeting is detected. There is also an exception where the user sets it to always record. This is not a policy that forbids the AirPods microphone during meetings. [manager.rs:983–994](https://github.com/screenpipe/screenpipe/blob/288989a4aa69574f50c6978de47ca7f70e4eb51a/crates/screenpipe-audio/src/audio_manager/manager.rs#L983-L994)

This is the most direct structural difference from the current Recly. However, the conflict-avoidance effect is that project's design intent and was not verified with real AirPods in this research. It cannot be established that this difference caused the September 18 recording or the other side's receive-quality problem. Claims in the source comments about other closed-source apps' implementations were not cited without separate confirmation.

### Meetily: tap-centered capture, but format transition handling needs separate review

`a2cb62e827da7ef59f65064c97233efb2313878e`

It uses a process tap for system audio. The physical output `sub_device_list` was removed from the aggregate description, and a comment gives duplicate capture/echo problems in the earlier configuration as the reason. The output UID remains in `main_sub_device`. **This key alone does not establish that the actual output device is additionally opened.** [core_audio.rs:119–145](https://github.com/Zackriya-Solutions/meetily/blob/a2cb62e827da7ef59f65064c97233efb2313878e/frontend/src-tauri/src/audio/capture/core_audio.rs#L119-L145)

When the nominal rate changes in the IOProc, it updates an atomic value, but this callback keeps using the existing `ctx.format` and does not use the input timestamp. The callback observed alone cannot fully verify the whole app's format transitions, and this part is hard to adopt as is as a stability baseline. [callback:158–200](https://github.com/Zackriya-Solutions/meetily/blob/a2cb62e827da7ef59f65064c97233efb2313878e/frontend/src-tauri/src/audio/capture/core_audio.rs#L158-L200)

### tobi/recorder: separate raw tracks and file writing separated from the system IOProc

`f1b5c7455074253605ae6d55b0ef89f34efa3011`

It opens the microphone with a separate AVAudioEngine, uses the current native format and keeps the host time. The system audio IOProc hands samples to a ring buffer, and a separate writer thread writes them to the file. Keeping timing information and moving heavy work out of the system capture callback are worth referencing. [MicCapture.swift:98–144](https://github.com/tobi/recorder/blob/f1b5c7455074253605ae6d55b0ef89f34efa3011/Sources/Recorder/MicCapture.swift#L98-L144), [SystemAudioTap.swift:479–550](https://github.com/tobi/recorder/blob/f1b5c7455074253605ae6d55b0ef89f34efa3011/Sources/Recorder/SystemAudioTap.swift#L479-L550)

On the other hand, the microphone writes the file inside the callback, and there is also logic that keeps the existing file format when the system tap is re-created. This must not be copied as a finished solution for Bluetooth format transitions. [MicCapture.swift:162–198](https://github.com/tobi/recorder/blob/f1b5c7455074253605ae6d55b0ef89f34efa3011/Sources/Recorder/MicCapture.swift#L162-L198), [re-creation:630–639](https://github.com/tobi/recorder/blob/f1b5c7455074253605ae6d55b0ef89f34efa3011/Sources/Recorder/SystemAudioTap.swift#L630-L639)

This project's explanatory comments use the phrase tap-only, but the actual aggregate configuration includes a physical output subdevice. It is the opposite choice from Screenpipe and Chromium, so it cannot be said that open source as a whole has converged on one structure. [actual configuration:330–352](https://github.com/tobi/recorder/blob/f1b5c7455074253605ae6d55b0ef89f34efa3011/Sources/Recorder/SystemAudioTap.swift#L330-L352)

### AudioCap and CoreAudioTapKit: telling apart what the samples are for

- AudioCap `6f609e8ad1b1e11fa0e8edbe91864cb099f00de3` is a reference sample for creating a process tap. Its path uses the format read at creation time and throws away the input timestamp, so it is not used as a baseline for recovering from Bluetooth transitions. [ProcessTap.swift:208–241](https://github.com/insidegui/AudioCap/blob/6f609e8ad1b1e11fa0e8edbe91864cb099f00de3/AudioCap/ProcessTap/ProcessTap.swift#L208-L241)
- CoreAudioTapKit `aab395f1e8e5dd1bd369c3bbfcf5c6bdca85e099` is meant for processing sound and sending it back out to the speaker. It puts the actual output and the tap in one aggregate, mutes the original output and plays back in the same callback. It is not a structure that Recly, a recording-only app that must not touch the call, should adopt as is. [SystemTapCapture.swift:76–119](https://github.com/CJStanfield/CoreAudioTapKit/blob/aab395f1e8e5dd1bd369c3bbfcf5c6bdca85e099/Sources/CoreAudioTapKit/SystemTapCapture.swift#L76-L119)

## Priorities for Recly

1. **Keep the AirPods microphone choice.** Against the current implementation, which only follows the default input, design a path that identifies and keeps the AirPods used in the meeting. If an explicit choice is offered, the current microphone name and a simple change menu are enough; there is no need to add a confirmation step before recording. Do not change the OS's or the meeting app's input settings on their behalf.
2. **Verify that system capture does not open the physical output device unnecessarily.** Compare Screenpipe's tap-only aggregate with the current configuration. Check the virtual device's format together with the frames actually delivered, and do not force a change to the physical output device's sample rate.
3. **Keep audio timestamps all the way through.** Preserve the time, frames and format of the microphone and system inputs to align them, and tell a sustained shortfall or a 2:1 speed difference apart from ordinary small clock drift. Fix the behavior of filling a simple queue shortfall with silence and continuing to treat it as a normal recording.
4. **Handle format transitions and device reconnections as explicit state transitions.** Shrink the stretch where new buffers are interpreted with the old format, and leave reconfiguration, retries and failure diagnostics. Consider doing only minimal work in the callback, such as copying/queue handoff, and handling conversion and file writing separately.
5. **Verify simultaneous use with meeting apps all the way to the receiving side.** Checking only that the recording file is fine cannot prove the call is unaffected.

The direction recommended at this stage is not to replace the Core Audio tap wholesale right away but **to separate physical output from capture + keep the input device choice + strengthen timestamp/format handling**. Switching to ScreenCaptureKit is a separate candidate for comparison and must be evaluated including the current minimum OS and the permission UX.

## Real-device verification items

Compare Teams, Meet and Zoom each on the same AirPods and Mac. The targets are starting/stopping a recording, joining/leaving a meeting, muting/unmuting, AirPods reconnection, output changes and calls of 30 minutes or more. Do not declare it done on automated tests alone that do not actually use the devices.

The results to check at the same time are:

- Whether the microphone Recly chose is the AirPods and whether it matches the meeting app's choice.
- The pitch, continuity and mic/sys time alignment of a fixed-frequency test signal after saving.
- Callback times, frame counts, format changes and queue underrun/drop statistics.
- Changes in dropouts, volume and quality of my voice as heard on a separate receiving device, before and after Recly recording.

These experiments were not performed in this research. No server/bot approach was introduced, no library was replaced and no product code was changed.

## Commands checked and results

Sources were downloaded to a temporary directory and checked with `git rev-parse HEAD`, `rg -n` and `nl -ba ... | sed -n ...`. The project code in the checkouts was not run.

| Repository | HEAD checked |
| --- | --- |
| chromium/src | `f741c8c27fd492b2f3fae0cfe0d698170ffaebec` |
| obsproject/obs-studio | `b5bfb79093e26ce4b0e71d999f59b6b22f961121` |
| screenpipe/screenpipe | `288989a4aa69574f50c6978de47ca7f70e4eb51a` |
| Zackriya-Solutions/meetily | `a2cb62e827da7ef59f65064c97233efb2313878e` |
| tobi/recorder | `f1b5c7455074253605ae6d55b0ef89f34efa3011` |
| insidegui/AudioCap | `6f609e8ad1b1e11fa0e8edbe91864cb099f00de3` |
| CJStanfield/CoreAudioTapKit | `aab395f1e8e5dd1bd369c3bbfcf5c6bdca85e099` |

Representative commands and results:

```sh
git -C /tmp/screenpipe-research rev-parse HEAD
# 288989a4aa69574f50c6978de47ca7f70e4eb51a
nl -ba /tmp/screenpipe-research/crates/screenpipe-audio/src/core/process_tap/macos.rs | sed -n '833,945p'
# tap_only_aggregate_desc; virtual aggregate rate set/readback; physical subdevice exclusion
nl -ba /tmp/meetily-research/frontend/src-tauri/src/audio/capture/core_audio.rs | sed -n '119,200p'
# sub_device_list omitted; nominal rate update; existing ctx.format still used
rg -n 'hostTime|mHostTime|startWriterThread' /tmp/tobi-recorder-research/Sources/Recorder/SystemAudioTap.swift
# input host time retained at line 491; separate writer thread starts at line 522
```

For Chromium, instead of a full clone, the file at that commit was fetched from Gitiles with `?format=TEXT`, base64-decoded and checked with `nl -ba`. In the output, the aggregate's keys were name, UID, tap list, auto-start and private, with no physical subdevice list. `OnCatapSample` used `mHostTime` and checked for channel and frame mismatches.

Locations compared in Recly:

- `apple/RecKit/Sources/RecKit/Recorder/AudioInput.swift:68,114–135`: AVAudioEngine using the OS default input.
- `apple/RecKit/Sources/RecKit/MacCapture/ProcessTapCapture.swift:224–250`: aggregate that includes the actual output UID as main/subdevice.
- `apple/RecKit/Sources/RecKit/MacCapture/ProcessTapCapture.swift:270–275`: throws away the IOProc input timestamp and uses a cached format.
- `apple/RecKit/Sources/RecKit/MacCapture/DriftCompensator.swift:188–226`: progress calculation based on the declared rate, and filling shortfalls with silence.
- `apple/RecKit/Sources/RecKit/Recorder/SegmentedRecorder.swift:356–368`: conversion and writing run in the input callback.

Only document research and source reading were done, with no product code change. No claim is made that builds, unit tests or device tests pass.
