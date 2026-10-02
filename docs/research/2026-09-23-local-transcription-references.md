# Local transcription: survey of official documentation, papers and product development cases

Follow-up proposal: the [performance-first architecture plan](2026-09-23-local-transcription-architecture.md)
collects additional material from September 2026 and the overall structure, validation and implementation order of default-ON local transcription.

Survey date: 2026-09-23. A follow-up to the [survey of open-source candidates](2026-09-23-local-transcription.md).
It assumes Korean memos, mixed Korean-English meetings, and execution on phones and desktops. Official API documentation, researchers' papers,
posts by developers who ran their own experiments, and product support documents were read. Below, what the sources observe is kept apart from
proposals for Recly. No model inference or audio benchmark was run.

## How this survey changed the priorities

On Apple, the first step is **comparing SpeechAnalyzer/SpeechTranscriber with WhisperKit on the same Korean audio**.
The former uses the system's model management; with the latter, the app chooses the model and version.
Placing WhisperKit as the first candidate in the open-source survey was a priority within open source.
The system API is now included as an equal comparison target. No winner on Korean quality is decided yet.

Android's new ML Kit Speech Recognition also provides on-device transcription, but it is currently Alpha,
and Advanced mode has constraints on the range of devices and on how fast a recorded file can be fed. For the new Windows Speech API too,
the overview and the API reference disagree on its maturity label. Rather than choosing either API as the default for general-purpose file transcription,
they are validated separately from the existing whisper.cpp/sherpa-onnx candidates. The grounds are the official documents below.

## Official platform documentation

### 1. Apple WWDC25 — SpeechAnalyzer

[Bring advanced speech-to-text to your app with SpeechAnalyzer](https://developer.apple.com/videos/play/wwdc2025/277/)
— Apple, WWDC 2025.

- **Confirmed:** the new SpeechTranscriber is an on-device model for long-form speech, far-field conversations and meetings.
  It processes files and live audio and provides timing information and volatile/finalized results.
- **Operational difference:** the model is installed with AssetInventory, and the system stores and updates it. The model runs outside the app's
  memory space. This does not mean it uses none of the device's overall RAM or storage.
- **Constraints:** OS, hardware and language support must be checked. `supportedLocales` and `installedLocales`
  must be inspected, and SpeechTranscriber does not target watchOS. This survey did not query Korean support
  on a real device or validate automatic switching between Korean and English.
- **Recly proposal:** use it as the first comparison group on supported devices. Results can change with system model updates,
  so the evaluation also records the OS build. Speaker diarization quality is evaluated separately from transcription quality.

### 2. Apple — long-running background work

[Performing long-running tasks on iOS and iPadOS](https://developer.apple.com/documentation/backgroundtasks/performing-long-running-tasks-on-ios-and-ipados)
— current official documentation, BGContinuedProcessingTask in iOS 26.

- **Confirmed:** a long task started by a user action in the foreground can continue after the app moves to the
  background. The system UI provides progress and cancel, and supported devices also have a path that uses the GPU.
- **Constraints:** it does not guarantee unlimited execution. Progress reporting and handling termination are required. A watch file
  arriving in the background does not by itself satisfy the user-started-task condition.
- **Recly proposal:** distinguish a ‘Transcribe now’ the user taps from waiting for automatic processing. Per-segment results must be
  saved and resumable. Whether the GPU is used, and whether it is supported, is also checked per engine.

Recly already uses this API for manual upload retries at `BackgroundJobs.swift:87`. However, general
scheduling requires the network at line 71 of the same file, and the progress at line 117 completes only when the task ends.
Connecting it to local transcription requires designing both the scheduling conditions for a task with no network and real progress.
Having an existing foundation does not mean local transcription is supported today.

### 3. Google ML Kit — GenAI Speech Recognition

[GenAI Speech Recognition API](https://developers.google.com/ml-kit/genai/speech-recognition/android)
— current official documentation, `1.0.0-alpha1`.

- **Confirmed:** Basic is traditional on-device recognition; Advanced is an on-device generative model.
  Basic supports Android API 31 and later, and Korean `ko-KR` is marked beta.
  Korean is among Advanced's supported languages, but devices are currently limited to Pixel 10/11.
- **Key constraint:** when user audio is fed through a file descriptor, headerless PCM16/mono/16kHz must be fed
  **at real-time speed**. Reading an ordinary file at maximum speed is not supported.
  So it must not be assumed to be a batch API that can transcribe an hour-long file in a few minutes.
- **Recly proposal:** keep it as an experimental candidate for Android live memos. A default engine for long-file transcription for Galaxy users
  is provided separately. The SDK's Alpha status, model preparation and download, and support detection must be handled.

Recly's `android/app/build.gradle.kts:35` is minSdk 34. Being above the OS minimum does not by itself mean
the Advanced model runs on Galaxy devices.

### 4. Microsoft — Windows AI Speech Recognition

[Speech Recognition overview](https://learn.microsoft.com/en-us/windows/ai/apis/speech-recognition),
[SpeechRecognitionModel API reference](https://learn.microsoft.com/en-us/windows/windows-app-sdk/api/winrt/microsoft.windows.ai.speech.speechrecognitionmodel?view=windows-app-sdk-2.0-experimental)
— overview updated 2026-07-07; API reference as of the survey date.

- **Confirmed:** the overview describes offline file batch and streaming recognition, NPU and CPU execution, and model installation when needed.
  It states Windows 11 24H2 or later and WinAppSDK 1.7.1 or later.
- **Uncertainty in the documentation:** the linked API reference opens as `2.0-experimental`, and the class also carries an Experimental
  attribute. The overview's minimum version alone does not lead to the conclusion that it is usable in the stable SDK.
- **Further checks:** Korean support could not be confirmed from the documents read. The overview's MSIX and `systemAIModels`
  guidance is worded for ‘imaging APIs’, so the exact conditions that apply to Speech also need checking.
- **Recly proposal:** keep it as a candidate to track. Packaging with the current MSI distribution (`windows/app/build.gradle.kts:126`),
  WinRT wiring, an actual SDK compile and whether Korean can be recognized are the checks before adoption.

## Material for evaluating Korean and meeting quality

### 5. A 35-minute Korean development meeting, 7 configurations compared

[Real-world STT model performance: a 35-minute Korean meeting recording, 7 models tested](https://blog.dongjun.win/posts/korean-stt-7-model-comparison/)
— Kwon Dongjun, post 2026-06-04 / experiment 2026-02.

- **Observed:** an experiment comparing a 35 min 45 s Korean development meeting between 2 people on Apple Silicon.
  The author rated WhisperKit/Core ML large-v3-turbo FP16 as the most useful.
  It looks at English technical terms, hallucination during silence and timestamps from a practical-use standpoint.
- **Especially useful:** it describes the experience that long leading silence caused problems when mic/system were transcribed separately,
  and the limits of subtracting the microphone transcript from the combined transcript.
- **Limits:** a practical evaluation of a single recording. It is not a standard WER ranking based on a full reference transcript,
  and chip and RAM information is insufficient. Its numbers are not used as estimates of Recly's performance.
- **Recly proposal:** evaluate with `mix` as the baseline, then compare per-channel processing. A separate system track can also hold several remote
  speakers, so a track is not equated with a person. When silence is removed, the original timeline is preserved.

### 6. HiKE — evaluation of mixed Korean-English

[HiKE: Hierarchical Evaluation Framework for Korean-English Code-Switching Speech Recognition](https://aclanthology.org/2026.findings-eacl.33/)
— Paik et al., Findings of EACL, 2026-03.

- **Confirmed:** an evaluation framework that splits natural Korean-English code-switching into word, phrase and sentence levels and also distinguishes
  loanwords. It evaluates on the premise that being a multilingual model does not by itself make code-switching recognition sufficient.
- **Recly proposal:** use it as a test structure that replaces a single ‘supports Korean’ checkbox. Evaluate utterances mixing product names, abbreviations and English
  phrases separately, and compare automatic language detection with specifying Korean on the same audio.
  Do not lump loanword spelling and actual switches into English into one kind of error.
- **Limits:** this paper does not compare every current new model or the Recly runtime.

### 7. KsponSpeech — a baseline for natural Korean conversation

[KsponSpeech: Korean Spontaneous Speech Corpus for Automatic Speech Recognition](https://ksp.etri.re.kr/ksp/article/read?id=62525)
— Bang et al., Applied Sciences, 2020. ETRI material.

- **Confirmed:** a research corpus of about 969 hours of natural Korean conversation from about 2,000 people.
  It centers on two-person conversations in clean environments.
- **Recly proposal:** comparison material that avoids picking candidates from read speech alone. Material for real far-field meetings,
  overlapping speech, watch microphones and ambient noise is added separately. After checking the data's terms of use,
  only the portion for evaluation is used. No audio data was downloaded in this survey.

### 8. Research on Whisper's non-speech hallucinations

[Investigation of Whisper ASR Hallucinations Induced by Non-Speech Audio](https://arxiv.org/abs/2501.11378)
— Barański et al., ICASSP 2025.

- **Confirmed:** experiments on hallucinations induced by non-speech sounds and on expressions that appear repeatedly.
  It also reports the effect of post-processing specific expressions.
- **Recly proposal:** in tests containing only silence, keyboard, music or ambient sound, count ‘sentences nobody said’ separately.
  Evaluate voice activity detection (VAD), but also check whether it drops quiet voices.
  A rule that unconditionally deletes expressions like ‘감사합니다’ ("thank you") could remove real speech, so it is not adopted as is.

### 9. The WhisperX paper — separating transcription from time alignment

[WhisperX: Time-Accurate Speech Transcription of Long-Form Audio](https://arxiv.org/abs/2303.00747)
— Bain et al., Interspeech 2023.

- **Confirmed:** it addresses timing drift and repetition in long audio, combining VAD and forced alignment.
- **Recly proposal:** evaluate separately whether a sentence is right, whether its playback position is right, and whether its speaker is right.
  Do not convert the paper's GPU batch-inference speedup figures into mobile speed. Total RAM and processing time with
  alignment and speaker models included must be measured.

## Conditions to keep when reading benchmark posts

### 10. Argmax — Apple SpeechAnalyzer compared with WhisperKit

[Apple SpeechAnalyzer and Argmax WhisperKit](https://www.argmaxinc.com/blog/apple-and-argmax)
— Argmax, 2025-06-20.

- **Conditions:** a vendor's own comparison using an M4 Mac mini, the first macOS 26 beta, and part of the English Earnings22 set.
  Publishing the data, versions and measurement scripts alongside is worth noting.
- **Interpretation:** it cannot be carried over to a ranking for Korean, iPhone or the current OS. In particular, the absence of WhisperKit
  speaker diarization in that feature table at the time must not be applied to the present. Whether SpeakerKit is currently public was checked separately in the earlier open-source
  survey. Older performance posts are read keeping the methodology apart from the results at the time.

### 11. Lyonesse — Apple, Parakeet and MOSS compared

[Parakeet vs Apple's Speech API vs MOSS: Benchmark Round 2](https://lyonesse.app/blog/parakeet-moss-apple-speech-benchmark.html)
— a product development team, 2026-07-15.

- **Conditions:** M2 Pro 32GB, macOS 26.5.1, 5,559 English LibriSpeech utterances. It publishes each model's raw transcripts and
  the normalization method. Timings were measured on a machine running other work, so no precise speed ranking is given.
- **Observed:** results from quantized Core ML execution differed from the original GPU model figures. It reports that MOSS produced empty results
  on short clips where speech starts immediately, so leading silence and a retry protocol were applied.
- **Limits:** this is English read-speech accuracy, not validation on Korean meetings. The speaker experiment on 62 seconds of synthetically concatenated audio also
  does not replace a DER evaluation on real meetings with overlapping speech. Nor does it experimentally isolate quantization alone as the cause of
  the difference.
- **Recly proposal:** measure with the conversion that will ship, and include failures and empty output in the results. Add tests for a clipped first syllable,
  speech starting right at the file start, and an interrupted last utterance.

## What to learn from products already shipping

### 12. Aiko — per-device models and iOS execution experience

[Aiko official description and FAQ](https://sindresorhus.com/aiko).

- **Confirmed:** a local Whisper app that says it uses different models on Mac and iOS depending on memory
  conditions. For long iOS transcriptions it tells users to keep the app open, and it also describes options for handling repetition and silence.
- **Recly proposal:** use its per-device model sizes and task status guidance as a reference. Do not conclude from Aiko's current FAQ
  that background transcription is impossible across all of iOS. It must be kept apart from the iOS 26 API conditions above.
  This product's description alone does not predict Recly's battery or heat levels.

### 13. MacWhisper — speaker correction and data movement per feature

[Automatic Speaker Recognition](https://docs.macwhisper.com/article/32-automatic-speaker-recognition-in-macwhisper),
[Keeping Transcriptions Private](https://docs.macwhisper.com/article/52-keeping-transcriptions-private).

- **Confirmed:** with supported models, it attaches anonymous speaker labels and provides a flow where the user corrects the names.
  The support documents describe local transcription separately from cloud translation and AI prompts.
- **Recly proposal:** initially provide ‘Speaker 1/2’ and let the user correct it. Distinguish speaker numbers in the transcription result from
  the identity of actual people. Even when transcription is local, data moves if a Drive upload or an external summary is configured,
  so show where processing happens for each workflow step.

### 14. Superwhisper — separating the speech model from the post-processing model

[AI models in Superwhisper](https://superwhisper.com/models).

- **Confirmed:** the model that turns speech into text and an optional text post-processing model are chosen separately.
  It also states combinations that connect a cloud language model after local speech recognition.
- **Recly proposal:** distinguish ‘local transcription’ from ‘the whole workflow offline’. Preserve the original transcript and
  keep summary and correction results as separate outputs. This page's ranking of its own product's performance is not treated as independent validation.

## Evaluation and design proposals for Recly

The following are proposals based on the material above and the current code, not completed implementation.

| Question to decide | Evaluation method | Results to record |
|---|---|---|
| Are Korean memos usable? | Short watch memos and ordinary conversation, comparing Korean specified vs automatic detection | CER·WER, number and name errors, empty output |
| Is mixed Korean-English preserved? | Word, phrase and sentence switches based on the HiKE classification | English term errors and loanword spelling counted separately |
| Is anything dropped in long meetings? | 30–60 minutes, 2 people / many people, far-field and overlap | Dropped segments, repetition, speaker errors, timing position errors |
| Does it make up sentences when it is quiet? | Long silence, ambient sound, a quiet system track | Output in non-speech segments and dropped quiet speech |
| Does the phone finish the job? | Screen lock, app switching, cancel, rerun | Completion rate, amount reprocessed, progress, peak RAM, heat, battery |
| Is it actually offline? | Block the network after the model is ready | Whether transcription completes, upload and summary waiting states |
| Is the comparison reproducible? | Same files, references and normalization, the actually shipped model | OS, chip, RAM, engine, model, quantization, options, first-run/rerun time |

The scope of the first experiment is narrowed to roughly **SpeechAnalyzer vs WhisperKit** on Apple and
**whisper.cpp vs sherpa-onnx/Qwen3-ASR** for the common engine. Devices outside the system API's support range are also
included in the target, and results are kept in a comparable format before the number of models grows.

Adding a local execution path needs the following contracts in addition to wiring the engine.

- A provider with no API key: `TranscribeRunner.kt:62` and `spec/workflow.schema.json:106` currently assume a key.
- Separating Drive-first from committing the local result: `WorkflowParser.kt:316` enforces the upload first.
- Model state: distinguish not installed, downloading, ready, running and interrupted, and validate offline use after the initial download.
- Preserving the timeline and the original text: even after VAD, channel processing and correction, link back to the playback position in the original recording.
- Resuming partial results: after the OS terminates the app, reuse segments already committed, and handle duplicates at boundaries and speaker numbers.

## Survey and verification log

- `git status --short`: one existing open-source survey document was untracked, and there were no code changes.
- `nl -ba apple/RecKit/Sources/RecKit/Jobs/BackgroundJobs.swift`:
  confirmed line 71 `request.requiresNetworkConnectivity = true`, line 87 `uploadNow`,
  line 117 `continued.progress.totalUnitCount = 1`.
- `nl -ba android/app/build.gradle.kts`: confirmed line 35 `minSdk = 34`.
- `nl -ba windows/app/build.gradle.kts`: confirmed line 126 `targetFormats(TargetFormat.Msi, TargetFormat.Dmg)`.
- Rechecked the provider, schema and parser evidence with `rg -n 'secretRef|drive.upload.*step|mono|mix'`.
  The output included `TranscribeRunner.kt:62: val key = apiKey(deps, step.secretRef)` and
  `WorkflowParser.kt:316: "a 'drive.upload' step must come before a 'transcribe' step"`.
- Only official documents and posts were read. The Google documentation text fetched directly and Apple's official Markdown were kept in
  `/tmp/recly-stt-research-20260923`. No model installation, real-device API experiment or audio upload was performed.

The scope of the change is the survey document and the follow-up link in the existing survey document. Verification results are recorded below.

- `make test`: `BUILD SUCCESSFUL in 3s`; `123 actionable tasks: 1 executed, 122 up-to-date`.
  This confirms the existing JVM checks, not ASR quality or performance validation.
- `git diff --check`: no output. A separate check on the two untracked documents confirmed
  `Research document checks: PASS (2 files; links, whitespace, code references)`.
