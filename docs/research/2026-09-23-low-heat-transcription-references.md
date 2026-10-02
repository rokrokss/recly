# Local transcription implementation references for reducing perceived heat

Checked: 2026-09-23. The user wants automatic transcription to stay on while the phone or computer
does not feel hot in use. This research only read web documents and source code. Model inference,
builds and load tests were not resumed. That an implementation below exists is a separate matter
from verifying perceived heat on real devices.

**Scope correction:** what Recly is currently looking for is automatic transcription of files after
recording ends. Argmax's battery mode, murmur's caption control and Edge-Veda's streaming session
below are mainly real-time processing cases. They must not be presented as finished low-heat file
transcription solutions. For file transcription, parts of Tapeback and bestASR are more direct, but
within the scope of this research no finished implementation was found that proves continuous heat
monitoring, pausing and resuming for long file transcription, let alone perceived temperature.

## 1. A product implementation of real-time transcription: Argmax Pro SDK

[Official real-time transcription documentation](https://app.argmaxinc.com/docs/examples/real-time-transcription)

- `voiceTriggered`: runs inference based on input energy and sets a minimum processing interval.
- `batteryOptimized`: adds adaptive delay to voice-triggered execution to account for battery and
  long-running heat.
- The documentation recommends a minimum processing interval of 2 seconds for sustained use on iOS.
  It gives battery-optimized mode as applying to iOS sessions of 10 minutes or more or 2 hours or
  more per day, and macOS sessions of 1 hour or more or 4 hours or more per day. These are the
  vendor's recommended conditions, not a measured guarantee of no heat.
- The Swift examples checked use `WhisperKitPro` and `DecodingOptionsPro`. Do not assume that
  installing the public WhisperKit also provides this commercial SDK's modes.
- This is a policy for real-time input. Whether the same controls apply automatically to batch jobs
  that process existing recording files quickly needs separate confirmation.

**Principle for Recly:** the user turning on automatic transcription does not mean inference must
run as often as possible. Use speech segments and the allowed latency to cut unnecessary repeated
computation.

## 2. Android production cases: LiteRT NPU + Argmax + Heidi

[Google, 2026-04-23](https://developers.googleblog.com/building-real-world-on-device-ai-with-litert-and-npu/)
· [Argmax Android launch, 2026-03-18](https://www.argmaxinc.com/blog/argmax-pro-sdk-for-android)
· [Heidi adoption case, 2025-11-10](https://www.argmaxinc.com/blog/heidi-health-ai-scribe-built-with-argmax-enterprise)

- Google describes Argmax's NPU performance gains over GPU on Tensor, MediaTek and Qualcomm, and a
  case of reducing the battery burden of long transcription. It also covers precompiled models and
  per-device delivery.
- Heidi is a real on-device transcription customer case. It describes the requirement to keep
  transcribing after the Apple screen locks. It is not evidence of behavior on every device, model
  and language.
- The representative model in the Android launch description is Parakeet v2. Do not use it as
  evidence of Korean performance.
- The [2026-09-23 SDK 3 announcement](https://www.argmaxinc.com/blog/argmax-sdk-3) introduces
  Qwen3-ASR, mixed languages, timestamps and real-time API support. The announcement alone does not
  establish that every Android NPU path and the battery-optimized behavior have been confirmed for
  this model.

**Principle for Recly:** sustained transcription using accelerators on Android also has real product
cases. Do not judge the heat of mobile local transcription as a whole only from results of running a
large model on the CPU.

## 3. An app implementation you can read directly: murmur

A macOS meeting recording app. The repository LICENSE is AGPL-3.0.

Revision checked: `12601a323064d4a19e5894fa67a5102e4ef07b1c`.

- [thermal.rs:29](https://github.com/murmur-io/murmur/blob/12601a323064d4a19e5894fa67a5102e4ef07b1c/src-tauri/src/thermal.rs#L29):
  widens the real-time processing interval to 3 seconds when normal, 6 seconds at `fair` and 9
  seconds at `serious`.
- [thermal.rs:67](https://github.com/murmur-io/murmur/blob/12601a323064d4a19e5894fa67a5102e4ef07b1c/src-tauri/src/thermal.rs#L67):
  a worsening thermal state applies immediately; an improvement applies only after being confirmed
  twice in a row.
- [thermal.rs:95](https://github.com/murmur-io/murmur/blob/12601a323064d4a19e5894fa67a5102e4ef07b1c/src-tauri/src/thermal.rs#L95):
  stops auxiliary processing at `serious` and regular real-time caption inference at `critical`.
- [live.rs:513](https://github.com/murmur-io/murmur/blob/12601a323064d4a19e5894fa67a5102e4ef07b1c/src-tauri/src/transcribe/live.rs#L513):
  the controller above is wired to the wait interval of the actual caption loop.

**Limits:** recording and batch transcription after recording ends are not covered by this
controller. If the thermal state cannot be read, it is treated as normal, and some user-started
tasks bypass the caption stop condition. So it cannot be adopted as is as a finished product that
controls the heat of all transcription.

**Principle for Recly:** keep the recording and only delay the transcription. Set a resume condition
so stopping and resuming do not alternate rapidly. Recly must apply this policy to batch
transcription of saved recordings as well.

## 4. A resource budget controller reference: Edge-Veda

A Flutter on-device AI runtime. The repository LICENSE is Apache-2.0.

Revision checked: `57f0edf38a6652f0f8fb549a11edeef1d0e95cd1`.

- [scheduler.dart:361](https://github.com/ramanujammv1988/edge-veda/blob/57f0edf38a6652f0f8fb549a11edeef1d0e95cd1/flutter/lib/src/scheduler.dart#L361):
  periodically checks budgets such as thermal state, battery drain and latency.
- [scheduler.dart:504](https://github.com/ramanujammv1988/edge-veda/blob/57f0edf38a6652f0f8fb549a11edeef1d0e95cd1/flutter/lib/src/scheduler.dart#L504):
  lowers the execution level starting from low-priority work.
- [whisper_session.dart:312](https://github.com/ramanujammv1988/edge-veda/blob/57f0edf38a6652f0f8fb549a11edeef1d0e95cd1/flutter/lib/src/whisper_session.dart#L312):
  when the STT work is paused, it does not run inference and leaves the input audio in a buffer.

**Limits:** the STT path checked only tests whether it is paused. It is not an implementation where
intermediate load reduction is wired to both the interval and the thread count of speech inference.
Memory buffer growth while paused also has to be handled separately. The README gives the Android
status as initial scaffolding, awaiting verification.

**Principle for Recly:** control transcription, speaker diarization and summarization with a shared
resource budget, but confirm that the actual engine honors the control values. For long recordings,
keep the pending audio on disk instead of letting it pile up in memory.

## 5. A batch implementation to reference with limits: Tapeback

Revision checked: `20cc7c406ea4a4482ec2036fc7df1002223de5ce`. The LICENSE is Apache-2.0.

[transcriber.py:506](https://github.com/yastcher/tapeback/blob/20cc7c406ea4a4482ec2036fc7df1002223de5ce/src/tapeback/transcriber.py#L506)
has an implementation that inserts rest time between CUDA processing stages. The default is 0, and
that code applies only to the CUDA path. There is also a path that checks GPU thermal throttling and
switches to the CPU.

**Principle for Recly:** reusing completed segments and waiting between stages are worth
referencing. Whether continuing inference on the CPU because the GPU was throttled for heat fits the
goal of keeping perceived heat down has to be judged separately.

### Model selection at the start of file transcription: bestASR

Revision checked: `83297fcbd773290b5cffa270f48cfbc79734a523`.

It is a [file-input CLI](https://github.com/PsychQuant/bestASR), and
[DynamicHostState.swift:19](https://github.com/PsychQuant/bestASR/blob/83297fcbd773290b5cffa270f48cfbc79734a523/Sources/BestASRKit/Detect/DynamicHostState.swift#L19)
treats `serious`, `critical` or Low Power Mode as a pressure state.
[CommandCore.swift:323](https://github.com/PsychQuant/bestASR/blob/83297fcbd773290b5cffa270f48cfbc79734a523/Sources/BestASRKit/CommandCore.swift#L323)
then changes the auto-selected profile from `medium` to `low`.

This is a **model and backend policy at selection time**. The code checked does not prove a
controller that monitors heat throughout file processing and pauses and resumes. Since it does not
treat `fair` as pressure, there is also no guarantee that it suppresses perceived heat in advance.

## 6. Cases where product descriptions must be kept apart from heat implementations

[Talk Memo](https://www.kkirukstudio.com/talkmemo/) describes a local transcription experience that
automatically turns Watch recordings into text on the iPhone. The public page checked has no thermal
control algorithm, power measurements or perceived temperature results for long meetings. Use it as
a reference case for automatic transcription UX.

§2.2.1 of the [WhisperKit paper](https://arxiv.org/html/2507.10860v1) describes power improvements
using the ANE and a stateful cache. Do not cite a specific decoder measurement as if it were the
power consumption of the whole app. The scope of features offered by the paper's implementation,
the commercial SDK and the public SDK must each be checked as well.

## 7. Judgment for Recly

1. Evaluate with file transcription at the center. Do not add a real-time caption feature as a basic
   requirement.
2. Reference Tapeback's waiting between stages and bestASR's selection policy at start directly.
   Classify Argmax, murmur and Edge-Veda as partial design references taken from other processing
   approaches.
3. Keep saving the recording, and run both real-time transcription and saved-file transcription
   within the resource budget.
4. Since perceived heat is the goal, do not settle for a policy that keeps running until a severe
   thermal state arrives.
5. Do not rely on a fixed rest time or small chunks alone to guarantee efficiency. Evaluate repeated
   computation due to model architecture, model loading, the actual accelerator and sustained power
   together.

## Research verification record

- Confirmed the revisions and source paths of the three repositories above with the GitHub tree
  API. All `truncated: false`.
- Downloaded the original code at the pinned revisions and checked thermal control, schedulers and
  call sites with `rg -n`.
- Read lines 29–105 of `murmur/thermal.rs` and the actual call site, the Edge-Veda scheduler and STT
  consumer, and Tapeback's wait-between-stages implementation. No external code was run.
- During the scope correction, additionally read bestASR's thermal state check and profile selection
  call site at the pinned revision.
- The research source material is in `/tmp/recly-thermal-reference-20260923/`.
- This document is neither a product implementation change nor the result of a real-device heat
  test.
