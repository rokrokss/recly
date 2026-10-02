# Survey of open-source local transcription

Follow-up decision: the [performance-first architecture plan](2026-09-23-local-transcription-architecture.md)
revisits the integration-driven priorities below and designs default ON, per-device acceleration and a Korean meeting evaluation.

Survey date: 2026-09-23. Official repositories, releases, model cards, licenses and the Recly code were checked.
The follow-up [survey of official documentation, papers and product cases](2026-09-23-local-transcription-references.md)
adds system transcription APIs and evaluation methods. The priorities below compare open-source candidates only.
This document is a survey for picking candidates, not a product contract or an implementation decision. No model was
installed, no audio was uploaded, no inference was run, and accuracy, speed and power were not measured. The priorities below are a judgment based on integration conditions.

Assumptions: Korean and mixed Korean-English matter, and both short watch memos and long meeting recordings are handled.
The existing structure stays: the phone, Mac and Windows run transcription, and the watch hands its recordings to the phone.
An approach built into the app, where the user does not run a separate Python environment or transcription server, comes first.

## Combinations to validate first

| Order | Combination | Why it was picked | What to check first |
|---|---|---|---|
| 1 | Apple: WhisperKit + SpeakerKit | Local transcription and speaker diarization can be wired together from a Swift package. SpeakerKit is in the current public release too | iPhone memory and heat, speaker diarization of Korean meetings, cancel and resume of long files |
| 2 | Common baseline: whisper.cpp + multilingual Whisper | Has a supported path on all four execution platforms and ships without Python. Suited as the comparison baseline | Mobile model size, mixed languages, hallucination on silence, wiring a separate speaker diarization |
| 3 | Common alternative: sherpa-onnx + Qwen3-ASR-0.6B INT8 | Provides a Korean model, Kotlin·Java·Swift·C APIs and execution paths on all four platforms | Korean quality compared with Whisper, actual memory, timing information and long-form splitting |
| Later | Desktop: NeMo-Speech.cpp + Nemotron 3.5 | A native streaming alternative that supports Korean | Mixed Korean-English, stability of a new execution engine, speaker-count limit |
| Research | MOSS-Transcribe-Diarize 0.9B | Outputs transcript, time and speaker from one model, which fits meetings well | Native port, RAM on long recordings, per-speaker accuracy in Korean |

This is not a proposal to put all of these combinations into the product. It is an order: compare them on the same recordings, then narrow down the engine
for the first release. Transcription based on Apple APIs can be kept as a separate comparison group, but it is not an open-source engine.

## Engines and models must be kept apart

WhisperKit, whisper.cpp and faster-whisper are mainly **different implementations that run the same Whisper-family models**.
Switching the execution engine does not by itself raise Korean accuracy. The model type, quantization,
decoding settings and the way audio is split all have an effect together.

sherpa-onnx is a tool that runs many models; Qwen3-ASR, SenseVoice and Zipformer are models chosen within it.
Speaker diarization is often a task separate from transcription. Detecting speaker changes, assigning consistent numbers to several speakers,
and identifying actual people's names are each different features.

## Comparison by candidate

| Candidate | Korean · platforms | Speaker diarization | Code / model license | Recly judgment |
|---|---|---|---|---|
| **whisper.cpp** | Korean through multilingual Whisper. iOS·Android·macOS·Windows | General multi-speaker diarization is a separate setup. tinydiarize is experimental | MIT / original Whisper is MIT | General-purpose baseline candidate |
| **WhisperKit + SpeakerKit** | Multilingual Whisper, Swift/Core ML for Apple Silicon | SpeakerKit's Pyannote-based diarization, merged with the transcription result | SDK MIT / Whisper MIT, SpeakerKit models CC-BY-4.0 | First Apple candidate |
| **sherpa-onnx + Qwen3-ASR** | 30 languages including Korean. Four platforms and Kotlin·Swift APIs | A separate diarization pipeline is set up | Engine Apache-2.0 / Qwen3-ASR Apache-2.0 | Strong alternative for the common engine |
| **NeMo-Speech.cpp + Nemotron 3.5 ASR 0.6B** | Supports Korean. Official native desktop distribution path confirmed | Can be combined with Sortformer; that model handles at most 4 speakers | Engine Apache-2.0 / ASR model OpenMDW-1.1 | Later candidate for desktop streaming |
| **FluidAudio** | Apple Swift/Core ML. The flagship Parakeet v3 does not support Korean. The SenseVoice path is separate | Options such as Pyannote and Sortformer | SDK Apache-2.0 / differs per model | Review as an Apple speaker diarization alternative |
| **faster-whisper + WhisperX** | Multilingual Whisper. Python-based desktop execution | WhisperX combines alignment with Pyannote diarization | MIT + BSD-2-Clause / differs per model | For development and quality comparison. Low priority for embedding in the mobile app |
| **MOSS-Transcribe-Diarize 0.9B** | Public material reports an evaluation that includes Korean. The official path is Python/Transformers | The model generates time and anonymous speaker labels together | Apache-2.0 | Research candidate for meetings |
| **Moonshine** | A Korean Tiny exists, but there is no Korean streaming checkpoint yet | Library features and per-model performance must be checked separately | Code MIT / the current Korean model is Community | Not prioritized as the default for a Korean product |
| **SenseVoiceSmall** | 5 languages including Korean. Runs with sherpa-onnx and others | Not in the model's own output. Combine separate VAD and speaker models | Code MIT / official weights under the separate FunASR Model License | Candidate whose model distribution terms need a separate review |
| **Voxtral Mini 4B Realtime** | 13 languages including Korean. The official path centers on vLLM | Must be handled separately from the transcription model | Model Apache-2.0 | High-performance desktop comparison group. Low priority among the initial mobile candidates |

Sources for the table: [whisper.cpp](https://github.com/ggml-org/whisper.cpp),
[Whisper multilingual model card](https://huggingface.co/openai/whisper-large-v3-turbo),
[Argmax SDK](https://github.com/argmaxinc/argmax-oss-swift),
[SpeakerKit model license](https://huggingface.co/argmaxinc/speakerkit-coreml),
[sherpa Qwen3 support](https://k2-fsa.github.io/sherpa/onnx/qwen3-asr/index.html),
[Qwen model card](https://huggingface.co/Qwen/Qwen3-ASR-0.6B),
[sherpa speaker diarization](https://k2-fsa.github.io/sherpa/onnx/speaker-diarization/index.html),
[NeMo execution engine](https://github.com/NVIDIA/NeMo-Speech.cpp),
[Nemotron model card](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b),
[FluidAudio](https://github.com/FluidInference/FluidAudio),
[faster-whisper](https://github.com/SYSTRAN/faster-whisper),
[WhisperX](https://github.com/m-bain/whisperX),
[MOSS](https://huggingface.co/OpenMOSS-Team/MOSS-Transcribe-Diarize),
[Moonshine model list](https://moonshine-voice.readthedocs.io/en/latest/models/available-models/),
[SenseVoice](https://github.com/FunAudioLLM/SenseVoice),
[SenseVoice model card](https://huggingface.co/FunAudioLLM/SenseVoiceSmall),
[Voxtral model card](https://huggingface.co/mistralai/Voxtral-Mini-4B-Realtime-2602).

## Specific differences that affect the choice

**WhisperKit's speaker diarization is in the current public SDK too.** At the time of the survey, the `Package.swift` of the
latest release v1.1.0 includes the SpeakerKit product. Pro features must not be confused with public features.
The current public example provides a path that merges speaker information into a file transcription result. That does not mean it provides
all of Pro's real-time features. The package's declared minimum of iOS 16 / macOS 13 is lower than Recly's
iOS 17 / macOS 14.4, but actual model and device compatibility and performance must be checked separately.
[Release package](https://github.com/argmaxinc/argmax-oss-swift/blob/v1.1.0/Package.swift),
[Release usage](https://github.com/argmaxinc/argmax-oss-swift/blob/v1.1.0/README.md).

**Classifying Qwen3-ASR as Python-only misses the current situation.** The official Qwen package
centers on Transformers/vLLM, but sherpa-onnx provides an INT8 ONNX model of the 0.6B and a native API.
However, microphone streaming in the sherpa examples is a VAD-based `simulated-streaming` path, so
it is not assumed to be the same as true streaming that keeps the model's internal state. Attaching Qwen's separate
ForcedAligner also adds that model's cost and integration.
[sherpa models and API](https://k2-fsa.github.io/sherpa/onnx/qwen3-asr/index.html),
[Run examples](https://k2-fsa.github.io/sherpa/onnx/qwen3-asr/pretrained.html),
[Qwen official implementation](https://github.com/QwenLM/Qwen3-ASR).

**Korean support must not be judged by the name Parakeet alone.** The 25 supported languages of the widely used
`parakeet-tdt-0.6b-v3` do not include Korean. By contrast,
`nemotron-3.5-asr-streaming-0.6b` explicitly lists Korean as a language it can transcribe out of the box.
With NeMo-Speech.cpp it can be compared as a Python-free candidate on the desktop.
This survey did not go as far as validating that engine's distribution path in iPhone and Android apps.
[Parakeet v3 language list](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3),
[Nemotron support and local execution](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b).

**A lightweight model's license is not the same as its code's.** Despite Moonshine's general MIT description,
the current Korean Tiny is a Community model, and there is no Korean streaming replacement model yet.
SenseVoiceSmall likewise points to the FunASR Model License in its model card, separately from the MIT code.
So the original weights are not judged MIT/Apache just from the badge of a converted-model repository.
[Moonshine detailed list](https://moonshine-voice.readthedocs.io/en/latest/models/available-models/),
[Moonshine license](https://github.com/moonshine-ai/moonshine/blob/main/LICENSE),
[SenseVoice model terms](https://huggingface.co/FunAudioLLM/SenseVoiceSmall),
[FunASR model license](https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE).

**MOSS is an alternative that does transcription and speaker diarization in one pass.** The public 0.9B model states up to 90 minutes of input and
anonymous speaker labels, and reports an evaluation that includes Korean. But public scores do not prove Recly's
Korean meeting quality or that it can run on an iPhone. Work remains between the Python execution path in the current documentation and
a native execution path that can ship inside the app.
[MOSS official model card](https://huggingface.co/OpenMOSS-Team/MOSS-Transcribe-Diarize).

## Storage and processing cost

These are the base model figures published in the whisper.cpp README. They are not figures for quantized models or Core ML conversions,
and not the peak memory measured in Recly.

| Whisper size | Model file | Memory figure in the README |
|---|---:|---:|
| tiny | 75 MiB | about 273 MB |
| base | 142 MiB | about 388 MB |
| small | 466 MiB | about 852 MB |
| medium | 1.5 GiB | about 2.1 GB |
| large | 2.9 GiB | about 3.9 GB |

Quantization can reduce file and memory use, but the change in quality must also be validated on the same audio.
The first combinations to compare are multilingual `base`/`small` on mobile and `large-v3-turbo` on the desktop.
This is a measurement plan, not a recommended minimum spec or a guarantee of Korean quality.
[Model sizes and quantization](https://github.com/ggml-org/whisper.cpp#memory-usage).

Besides the model file size, decoding buffers, time alignment, speaker diarization and the state of long recordings use memory.
A model download may be needed before running, and offline execution after the download must be validated separately.
Throughput measured on a server GPU is not converted into transcription time or battery use on a phone.

## Connection points Recly needs

Facts confirmed in the current code, and the implementation proposals that follow from them.

| Current evidence | Work needed |
|---|---|
| Provider interface at `core/src/commonMain/kotlin/recly/core/transcribe/SttProvider.kt:31` | There is a place to add a local execution adapter. Revisit the existing network submit and polling conventions |
| Shell dependency injection at `core/src/commonMain/kotlin/recly/core/platform/CoreDeps.kt:10` | Consider injecting Swift / JNI / C library execution as a shell-side implementation |
| Mandatory API key lookup at `core/src/commonMain/kotlin/recly/core/transcribe/TranscribeRunner.kt:62` | Add a contract that a local provider needs no key, and editing UI for it |
| `secretRef` required at `spec/workflow.schema.json:106` | Adding a local provider means changing the schema, every client and import compatibility together |
| Drive-first enforced at `core/src/commonMain/kotlin/recly/core/workflow/WorkflowParser.kt:306` | If immediate offline transcription is wanted, separate out the upload-order constraint |
| Local writes and Drive writes are coupled from `core/src/commonMain/kotlin/recly/core/transcribe/ResultFiles.kt:35` on | Separate committing the local result from the later upload state |
| `core/src/commonMain/kotlin/recly/core/transcribe/TranscribeRunner.kt:236` selects a single mono/mix track | Per-channel speaker handling that uses desktop mic/system is also a separate extension |
| `apple/RecKit/Package.swift:15` is iOS 17 / macOS 14.4 / watchOS 10 | Conditionally exclude the local transcription dependency on watchOS, and check model support per phone and Mac device |

Local transcription and local-only storage are separate things. If the existing Drive step stays, the audio keeps being uploaded
to the user's Drive. Offering fully offline transcription and storage requires changing the workflow, result file and scheduler contracts
together. On implementation, the model download path must also be stated in `docs/recly.md` §15.

If Pyannote's Python library is used for quality comparison or in the product, its optional telemetry is
explicitly disabled and its traffic is checked. This keeps Recly's no-telemetry contract.
It does not mean the Swift/Core ML conversions carry Python's telemetry behavior over as is.
[Pyannote settings](https://github.com/pyannote/pyannote-audio#telemetry).

## Scope of the next experiment

1. With the same public or user-permitted Korean audio, compare short memos, mixed Korean-English, numbers and proper nouns,
   far-field meetings, overlapping speech and long silence. Do not upload to external transcription services.
2. Record Korean CER and WER, including the effect of word spacing, under the same normalization rules.
   Count proper noun and number errors and hallucination on silence separately; for speaker diarization, look at DER and per-speaker text errors.
3. Record processing time relative to file length, time to first result, model file size, peak RAM, and heat and battery.
   Distinguish the first run, which includes model loading, from a rerun with the cache ready.
4. On 30–60 minute recordings, check interruption and resume, the app moving to the background, keeping speaker numbers, and cumulative drift in timing information.
5. Limit the first comparison to multilingual Whisper on WhisperKit/whisper.cpp and Qwen3-ASR-0.6B INT8 on sherpa-onnx.
   Add Nemotron and MOSS after seeing the actual differences.

Public benchmarks are grounds for narrowing candidates. Scores from different datasets, normalizations, quantizations and hardware are not
merged into one ranking. At present, no engine has had its transcription quality or performance validated in Recly.

## Survey log

The following versions were checked from the GitHub API's `releases/latest`. They are the latest releases the API returned,
which does not mean every finding of the survey is included in that tag. For SpeakerKit, the v1.1.0 tag was also checked directly.

| Repository | Latest release checked |
|---|---|
| ggml-org/whisper.cpp | v1.9.4 |
| argmaxinc/argmax-oss-swift | v1.1.0 |
| k2-fsa/sherpa-onnx | v1.13.8 |
| FluidInference/FluidAudio | v0.17.1 |
| SYSTRAN/faster-whisper | v1.2.1 |
| m-bain/whisperX | v3.8.6 |
| moonshine-ai/moonshine | v0.1.5 |
| NVIDIA/NeMo-Speech.cpp | v0.1.0 |

Commands used for local checks, and observations:

- `git status --short` before the survey: no output.
- `rg -n 'SpeechAnalyzer|SpeechTranscriber|SFSpeechRecognizer|whisper.cpp|WhisperKit' apple core android windows`:
  no matching output. The judgment that there is no existing local transcription integration also rests on reading the provider list and the execution code.
- Line 62 of `nl -ba core/src/commonMain/kotlin/recly/core/transcribe/TranscribeRunner.kt`:
  `val key = apiKey(deps, step.secretRef)`.
- Line 316 of `nl -ba core/src/commonMain/kotlin/recly/core/workflow/WorkflowParser.kt`:
  `"a 'drive.upload' step must come before a 'transcribe' step"`.
- Only official READMEs, LICENSEs, package declarations, model cards and GitHub metadata were downloaded with Python `urllib.request`
  and compared in `/tmp/recly-stt-research-20260923`. No model weights or audio files were downloaded.

The final scope of the change is this survey document only. No transcription engine integration or audio benchmark was performed.

Verification results:

- `make test`: `BUILD SUCCESSFUL in 29s`; `123 actionable tasks: 37 executed, 86 from cache`.
  These are the existing JVM checks, not tests that validate local transcription performance.
- `git diff --check`: no output. The new document was separately checked for whitespace and local file and line references, and
  `Research document checks: PASS` was obtained.
