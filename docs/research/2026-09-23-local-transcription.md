# 로컬 전사 오픈소스 조사

후속 결정: [성능 우선 아키텍처 계획](2026-09-23-local-transcription-architecture.md)은
아래의 통합 조건 중심 우선순위를 재검토하고, 기본 ON·기기별 가속·한국어 회의 평가를 설계한다.

조사일: 2026-09-23. 공식 저장소, 릴리스, 모델 카드, 라이선스와 Recly 코드를 확인했다.
후속 [공식 문서·논문·제품 사례 조사](2026-09-23-local-transcription-references.md)에서는
시스템 전사 API와 평가 방법을 추가했다. 아래 우선순위는 오픈소스 후보 안에서의 비교다.
이 문서는 후보 선정용 조사이며 제품 계약이나 구현 결정이 아니다. 모델 설치, 음성 업로드,
추론 실행, 정확도·속도·전력 측정은 하지 않았다. 아래 우선순위는 통합 조건에 따른 판단이다.

가정: 한국어와 한국어·영어 혼용이 중요하며, 짧은 워치 메모와 장시간 회의 녹음을 모두 다룬다.
폰·Mac·Windows가 전사를 실행하고 워치는 폰에 녹음을 전달하는 기존 구조를 유지한다.
사용자가 Python 환경이나 전사 서버를 별도로 운영하지 않는 앱 내장 방식을 우선한다.

## 우선 검증할 조합

| 순서 | 조합 | 선정 이유 | 먼저 확인할 것 |
|---|---|---|---|
| 1 | Apple: WhisperKit + SpeakerKit | Swift 패키지에서 로컬 전사와 화자 분리를 함께 연결할 수 있다. 현재 공개 릴리스에도 SpeakerKit이 있다 | iPhone 메모리·발열, 한국어 회의 화자 분리, 긴 파일의 취소·재개 |
| 2 | 공통 기준: whisper.cpp + 다국어 Whisper | 네 실행 플랫폼의 지원 경로가 있으며 Python 없이 배포 가능하다. 비교 기준으로 적합하다 | 모바일 모델 크기, 언어 혼용, 무음 환각, 별도 화자 분리 연결 |
| 3 | 공통 대안: sherpa-onnx + Qwen3-ASR-0.6B INT8 | 한국어 모델과 Kotlin·Java·Swift·C API, 네 플랫폼 실행 경로를 모두 제공한다 | Whisper 대비 한국어 품질, 실제 메모리, 시간 정보와 장문 분할 |
| 후속 | 데스크톱: NeMo-Speech.cpp + Nemotron 3.5 | 한국어를 지원하는 네이티브 스트리밍 대안 | 한국어·영어 혼용, 새 실행 엔진의 안정성, 화자 수 제한 |
| 연구 | MOSS-Transcribe-Diarize 0.9B | 전사·시간·화자를 한 모델에서 출력하여 회의 용도와 잘 맞는다 | 네이티브 이식, 긴 녹음에서의 RAM, 한국어 화자별 정확도 |

위 조합들을 모두 제품에 넣자는 제안은 아니다. 같은 녹음으로 비교한 다음 첫 배포에 사용할
엔진을 좁히는 순서다. Apple API 기반 전사는 별도 비교군으로 둘 수 있지만 오픈소스 엔진은 아니다.

## 엔진과 모델을 구분해야 한다

WhisperKit, whisper.cpp, faster-whisper는 주로 **같은 Whisper 계열 모델을 실행하는 서로 다른 구현**이다.
실행 엔진을 바꿨다는 사실만으로 한국어 정확도가 높아지지는 않는다. 모델 종류, 양자화,
디코딩 설정, 오디오 분할 방식이 함께 영향을 준다.

sherpa-onnx는 여러 모델을 실행하는 도구이며, Qwen3-ASR·SenseVoice·Zipformer는 그 안에서 고르는 모델이다.
화자 분리는 전사와 별도 작업인 경우가 많다. 화자 변경 감지, 여러 화자에 일관된 번호 부여,
실제 사람 이름 식별도 각각 다른 기능이다.

## 후보별 비교

| 후보 | 한국어·플랫폼 | 화자 분리 | 코드 / 모델 라이선스 | Recly 판단 |
|---|---|---|---|---|
| **whisper.cpp** | 다국어 Whisper로 한국어. iOS·Android·macOS·Windows | 일반 다화자 분리는 별도 구성. tinydiarize는 실험적 기능 | MIT / 원본 Whisper는 MIT | 범용 기준 후보 |
| **WhisperKit + SpeakerKit** | 다국어 Whisper, Apple Silicon용 Swift/Core ML | SpeakerKit의 Pyannote 기반 분리 및 전사 결과 결합 | SDK MIT / Whisper MIT, SpeakerKit 모델 CC-BY-4.0 | Apple 첫 후보 |
| **sherpa-onnx + Qwen3-ASR** | 한국어 포함 30개 언어. 네 플랫폼과 Kotlin·Swift API | 별도 diarization 파이프라인 구성 | 엔진 Apache-2.0 / Qwen3-ASR Apache-2.0 | 공통 엔진의 유력 대안 |
| **NeMo-Speech.cpp + Nemotron 3.5 ASR 0.6B** | 한국어 지원. 공식 데스크톱 네이티브 배포 경로 확인 | Sortformer를 조합할 수 있으며 해당 모델은 최대 4화자 | 엔진 Apache-2.0 / ASR 모델 OpenMDW-1.1 | 데스크톱 스트리밍 후속 후보 |
| **FluidAudio** | Apple Swift/Core ML. 대표 Parakeet v3는 한국어 미지원. SenseVoice 경로는 별도 | Pyannote, Sortformer 등의 선택지 | SDK Apache-2.0 / 각 모델별 상이 | Apple 화자 분리 대안으로 검토 |
| **faster-whisper + WhisperX** | 다국어 Whisper. Python 기반 데스크톱 실행 | WhisperX가 정렬과 Pyannote 분리를 결합 | MIT + BSD-2-Clause / 각 모델별 상이 | 개발·품질 비교용. 모바일 앱 내장 우선순위 낮음 |
| **MOSS-Transcribe-Diarize 0.9B** | 공개 자료에 한국어 포함 평가. 공식 경로는 Python/Transformers | 모델이 시간과 익명 화자 라벨을 함께 생성 | Apache-2.0 | 회의용 연구 후보 |
| **Moonshine** | 한국어 Tiny는 제공되나 한국어 스트리밍 체크포인트는 아직 없음 | 라이브러리 기능과 모델별 성능을 별도 확인해야 함 | 코드 MIT / 현재 한국어 모델은 Community | 한국어 제품 기본값으로 우선하지 않음 |
| **SenseVoiceSmall** | 한국어 포함 5개 언어. sherpa-onnx 등으로 실행 | 모델 자체 출력에는 없음. 별도 VAD·화자 모델 조합 | 코드 MIT / 공식 가중치는 별도 FunASR Model License | 모델 배포 조건을 분리 검토할 후보 |
| **Voxtral Mini 4B Realtime** | 한국어 포함 13개 언어. 공식 경로는 vLLM 중심 | 전사 모델과 별도로 다뤄야 함 | 모델 Apache-2.0 | 데스크톱 고성능 비교군. 초기 모바일 후보에서는 후순위 |

표의 근거: [whisper.cpp](https://github.com/ggml-org/whisper.cpp),
[Whisper 다국어 모델 카드](https://huggingface.co/openai/whisper-large-v3-turbo),
[Argmax SDK](https://github.com/argmaxinc/argmax-oss-swift),
[SpeakerKit 모델 라이선스](https://huggingface.co/argmaxinc/speakerkit-coreml),
[sherpa Qwen3 지원](https://k2-fsa.github.io/sherpa/onnx/qwen3-asr/index.html),
[Qwen 모델 카드](https://huggingface.co/Qwen/Qwen3-ASR-0.6B),
[sherpa 화자 분리](https://k2-fsa.github.io/sherpa/onnx/speaker-diarization/index.html),
[NeMo 실행 엔진](https://github.com/NVIDIA/NeMo-Speech.cpp),
[Nemotron 모델 카드](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b),
[FluidAudio](https://github.com/FluidInference/FluidAudio),
[faster-whisper](https://github.com/SYSTRAN/faster-whisper),
[WhisperX](https://github.com/m-bain/whisperX),
[MOSS](https://huggingface.co/OpenMOSS-Team/MOSS-Transcribe-Diarize),
[Moonshine 모델 목록](https://moonshine-voice.readthedocs.io/en/latest/models/available-models/),
[SenseVoice](https://github.com/FunAudioLLM/SenseVoice),
[SenseVoice 모델 카드](https://huggingface.co/FunAudioLLM/SenseVoiceSmall),
[Voxtral 모델 카드](https://huggingface.co/mistralai/Voxtral-Mini-4B-Realtime-2602).

## 선택에 영향을 주는 구체적인 차이

**WhisperKit의 화자 분리는 현재 공개 SDK에도 있다.** 조사 당시 최신 릴리스 v1.1.0의
`Package.swift`에 SpeakerKit 제품이 포함되어 있다. Pro 기능과 공개 기능을 혼동하면 안 된다.
현재 공개 예제는 파일 전사 결과에 화자 정보를 합치는 경로를 제공한다. 이것이 Pro의 모든
실시간 기능까지 제공한다는 뜻은 아니다. 패키지 선언의 iOS 16 / macOS 13 하한은 Recly의
iOS 17 / macOS 14.4보다 낮지만 실제 모델·기기 호환성과 성능은 따로 확인해야 한다.
[릴리스 패키지](https://github.com/argmaxinc/argmax-oss-swift/blob/v1.1.0/Package.swift),
[릴리스 사용법](https://github.com/argmaxinc/argmax-oss-swift/blob/v1.1.0/README.md).

**Qwen3-ASR를 Python 전용으로 분류하면 현재 상황을 놓친다.** 공식 Qwen 패키지는
Transformers/vLLM 중심이지만 sherpa-onnx가 0.6B의 INT8 ONNX 모델과 네이티브 API를 제공한다.
다만 sherpa 예제의 마이크 스트리밍은 VAD 기반 `simulated-streaming` 경로이므로,
모델 내부 상태를 유지하는 진정한 스트리밍과 동일하다고 가정하지 않는다. Qwen의 별도
ForcedAligner를 붙이는 경우 그 모델의 비용과 통합도 추가된다.
[sherpa 모델·API](https://k2-fsa.github.io/sherpa/onnx/qwen3-asr/index.html),
[실행 예제](https://k2-fsa.github.io/sherpa/onnx/qwen3-asr/pretrained.html),
[Qwen 공식 구현](https://github.com/QwenLM/Qwen3-ASR).

**Parakeet라는 이름만으로 한국어 지원을 판단하면 안 된다.** 널리 쓰이는
`parakeet-tdt-0.6b-v3`의 25개 지원 언어에는 한국어가 없다. 반면
`nemotron-3.5-asr-streaming-0.6b`는 한국어를 즉시 전사 가능한 언어로 명시한다.
NeMo-Speech.cpp를 사용하면 데스크톱에서 Python 없는 후보로 비교할 수 있다.
이번 조사에서 해당 엔진의 iPhone·Android 앱 배포 경로까지 검증하지는 않았다.
[Parakeet v3 언어 목록](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3),
[Nemotron 지원 및 로컬 실행](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b).

**가벼운 모델의 라이선스가 코드와 같지는 않다.** Moonshine의 일반 MIT 설명에도 불구하고
현재 한국어 Tiny는 Community 모델이며 한국어 스트리밍 대체 모델이 아직 없다.
SenseVoiceSmall 역시 코드 MIT와 별개로 모델 카드가 FunASR Model License를 가리킨다.
따라서 변환본 저장소의 배지만 보고 원본 가중치까지 MIT/Apache라고 판단하지 않는다.
[Moonshine 상세 목록](https://moonshine-voice.readthedocs.io/en/latest/models/available-models/),
[Moonshine 라이선스](https://github.com/moonshine-ai/moonshine/blob/main/LICENSE),
[SenseVoice 모델 조건](https://huggingface.co/FunAudioLLM/SenseVoiceSmall),
[FunASR 모델 라이선스](https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE).

**MOSS는 전사와 화자 분리를 한 번에 하는 대안이다.** 공개 0.9B 모델은 최대 90분 입력과
익명 화자 라벨을 안내하며 한국어를 포함한 평가를 보고한다. 하지만 공개 점수가 Recly의
한국어 회의 품질이나 iPhone 실행 가능성을 입증하지는 않는다. 현재 문서의 Python 실행 경로와
앱에 포함할 네이티브 실행 경로 사이의 작업이 남아 있다.
[MOSS 공식 모델 카드](https://huggingface.co/OpenMOSS-Team/MOSS-Transcribe-Diarize).

## 저장 공간과 처리 비용

whisper.cpp README에 공개된 기본 모델 수치다. 양자화 모델이나 Core ML 변환본 수치가 아니며,
Recly에서 측정한 최대 메모리도 아니다.

| Whisper 크기 | 모델 파일 | README의 메모리 수치 |
|---|---:|---:|
| tiny | 75 MiB | 약 273 MB |
| base | 142 MiB | 약 388 MB |
| small | 466 MiB | 약 852 MB |
| medium | 1.5 GiB | 약 2.1 GB |
| large | 2.9 GiB | 약 3.9 GB |

양자화로 파일·메모리 사용량을 줄일 수 있지만 품질 변화까지 같은 음성으로 검증해야 한다.
처음 비교할 조합은 모바일의 다국어 `base`/`small`과 데스크톱의 `large-v3-turbo`다.
이는 측정 계획이며 권장 최소 사양이나 한국어 품질 보장은 아니다.
[모델 크기·양자화](https://github.com/ggml-org/whisper.cpp#memory-usage).

모델 파일 크기 외에도 디코딩 버퍼, 시간 정렬, 화자 분리, 긴 녹음의 상태가 메모리를 쓴다.
실행 전 모델 다운로드가 필요할 수 있으며, 다운로드 이후의 오프라인 실행을 별도로 검증해야 한다.
서버 GPU에서 얻은 처리량을 폰의 전사 시간이나 배터리 사용량으로 환산하지 않는다.

## Recly에 필요한 연결 지점

현재 코드에서 확인한 사실과 그에 따른 구현 제안이다.

| 현재 근거 | 필요한 작업 |
|---|---|
| `core/src/commonMain/kotlin/recly/core/transcribe/SttProvider.kt:31`에 provider 인터페이스 | 로컬 실행 어댑터를 추가할 자리는 있다. 기존 네트워크 제출·폴링 관례는 재검토 |
| `core/src/commonMain/kotlin/recly/core/platform/CoreDeps.kt:10`에 셸 의존성 주입 | Swift / JNI / C 라이브러리 실행을 셸 쪽 구현으로 주입하는 방안 검토 |
| `core/src/commonMain/kotlin/recly/core/transcribe/TranscribeRunner.kt:62`에서 API 키 필수 조회 | 로컬 provider는 키가 필요 없다는 계약과 편집 UI 추가 |
| `spec/workflow.schema.json:106`에서 `secretRef` 필수 | 로컬 provider 추가 시 스키마·모든 클라이언트·가져오기 호환성 함께 수정 |
| `core/src/commonMain/kotlin/recly/core/workflow/WorkflowParser.kt:306`에서 Drive 선행 강제 | 오프라인 즉시 전사를 원하면 업로드 순서 제약 분리 |
| `core/src/commonMain/kotlin/recly/core/transcribe/ResultFiles.kt:35` 이후 로컬 쓰기와 Drive 쓰기가 결합 | 로컬 결과 확정과 후속 업로드 상태를 분리 |
| `core/src/commonMain/kotlin/recly/core/transcribe/TranscribeRunner.kt:236`은 mono/mix 한 트랙 선택 | 데스크톱 mic/system을 활용한 채널별 화자 처리도 별도 확장 사항 |
| `apple/RecKit/Package.swift:15`는 iOS 17 / macOS 14.4 / watchOS 10 | 로컬 전사 의존성의 watchOS 조건부 제외와 폰·Mac 기기별 모델 지원 검사 |

로컬 전사와 로컬 전용 보관은 별개다. 기존 Drive 단계를 유지하면 음성은 계속 사용자의 Drive로
업로드된다. 완전 오프라인 전사 및 보관까지 제공하려면 워크플로우·결과 파일·스케줄러 계약을
함께 변경해야 한다. 구현 시 모델 다운로드 경로도 `docs/recly.md` §15에 명시해야 한다.

Pyannote의 Python 라이브러리를 품질 비교나 제품에 사용하는 경우 선택적 텔레메트리를
명시적으로 비활성화하고 통신을 확인한다. Recly의 텔레메트리 없음 계약을 유지하기 위한 것이다.
Swift/Core ML 변환본에 Python의 텔레메트리 동작이 그대로 있다는 뜻은 아니다.
[Pyannote 설정](https://github.com/pyannote/pyannote-audio#telemetry).

## 다음 실험의 범위

1. 같은 공개 또는 사용자가 허용한 한국어 음성으로 짧은 메모, 한영 혼용, 숫자·고유명사,
   원거리 회의, 겹친 발화, 긴 무음을 비교한다. 외부 전사 서비스에 업로드하지 않는다.
2. 한국어 CER와 띄어쓰기 영향을 포함한 WER를 같은 정규화 규칙으로 기록한다.
   고유명사·숫자 오류와 무음 환각은 따로 세고, 화자 분리는 DER 및 화자별 텍스트 오류를 본다.
3. 파일 길이 대비 처리 시간, 첫 결과 시간, 모델 파일 크기, 최대 RAM, 발열·배터리를 기록한다.
   모델 로딩을 포함한 첫 실행과 캐시가 준비된 재실행을 구분한다.
4. 30~60분 녹음에서 중단·재개, 앱 백그라운드 전환, 화자 번호 유지, 시간 정보 누적 오차를 확인한다.
5. 첫 비교는 WhisperKit/whisper.cpp의 다국어 Whisper와 sherpa-onnx의 Qwen3-ASR-0.6B INT8로
   제한한다. 실제 차이를 본 뒤 Nemotron·MOSS를 추가한다.

공개 벤치마크는 후보를 좁히는 근거다. 서로 다른 데이터셋·정규화·양자화·하드웨어의 점수를
한 순위표로 합치지 않는다. 현재는 어느 엔진도 Recly에서 전사 품질이나 성능을 검증한 상태가 아니다.

## 조사 기록

GitHub API의 `releases/latest`에서 다음 버전을 확인했다. API가 반환한 최신 릴리스이며,
모든 조사 내용이 해당 태그에 포함되었다는 뜻은 아니다. SpeakerKit은 v1.1.0 태그도 직접 확인했다.

| 저장소 | 확인한 최신 릴리스 |
|---|---|
| ggml-org/whisper.cpp | v1.9.4 |
| argmaxinc/argmax-oss-swift | v1.1.0 |
| k2-fsa/sherpa-onnx | v1.13.8 |
| FluidInference/FluidAudio | v0.17.1 |
| SYSTRAN/faster-whisper | v1.2.1 |
| m-bain/whisperX | v3.8.6 |
| moonshine-ai/moonshine | v0.1.5 |
| NVIDIA/NeMo-Speech.cpp | v0.1.0 |

로컬 확인에 사용한 명령과 관찰:

- 조사 전 `git status --short`: 출력 없음.
- `rg -n 'SpeechAnalyzer|SpeechTranscriber|SFSpeechRecognizer|whisper.cpp|WhisperKit' apple core android windows`:
  일치 출력 없음. 기존 로컬 전사 통합이 없다는 판단은 provider 목록과 실행 코드 읽기도 함께 근거로 삼았다.
- `nl -ba core/src/commonMain/kotlin/recly/core/transcribe/TranscribeRunner.kt`의 62행:
  `val key = apiKey(deps, step.secretRef)`.
- `nl -ba core/src/commonMain/kotlin/recly/core/workflow/WorkflowParser.kt`의 316행:
  `"a 'drive.upload' step must come before a 'transcribe' step"`.
- Python `urllib.request`로 공식 README·LICENSE·패키지 선언·모델 카드·GitHub 메타데이터만 내려받아
  `/tmp/recly-stt-research-20260923`에서 비교했다. 모델 가중치 및 음성 파일은 내려받지 않았다.

최종 변경 범위는 이 조사 문서뿐이다. 전사 엔진 통합 및 음성 벤치마크는 수행하지 않았다.

검증 결과:

- `make test`: `BUILD SUCCESSFUL in 29s`; `123 actionable tasks: 37 executed, 86 from cache`.
  기존 JVM 검사이며 로컬 전사 성능을 검증하는 테스트는 아니다.
- `git diff --check`: 출력 없음. 새 문서는 별도로 공백과 로컬 파일·행 참조를 확인했고
  `Research document checks: PASS`를 얻었다.
