# 로컬 전사: 공식 문서·논문·제품 개발 사례 조사

후속 제안: [성능 우선 아키텍처 계획](2026-09-23-local-transcription-architecture.md)에
2026년 9월의 추가 자료와 기본 ON 로컬 전사의 전체 구조·검증·구현 순서를 정리했다.

조사일: 2026-09-23. [오픈소스 후보 조사](2026-09-23-local-transcription.md)의 후속 문서다.
한국어 메모와 한영 혼용 회의, 폰·데스크톱 실행을 가정한다. 공식 API 문서, 연구자의 논문,
직접 실험한 개발자의 글, 제품 지원 문서를 읽었다. 아래에서 자료의 관찰과 Recly에 대한
제안을 구분한다. 모델 추론이나 음성 벤치마크는 실행하지 않았다.

## 이번 조사로 달라진 우선순위

Apple에서는 **SpeechAnalyzer/SpeechTranscriber와 WhisperKit을 같은 한국어 음성으로 비교**하는
것이 먼저다. 전자는 시스템의 모델 관리를 활용하고, 후자는 앱이 모델·버전을 선택한다.
오픈소스 조사에서 WhisperKit을 첫 후보로 둔 것은 오픈소스 안에서의 우선순위였다.
이제 시스템 API도 동등한 비교 대상으로 포함한다. 한국어 품질의 승자는 아직 정하지 않는다.

Android의 새 ML Kit Speech Recognition도 기기 내 전사를 제공하지만, 현재 Alpha이며
Advanced 모드의 기기 범위와 녹음 파일 공급 속도에 제약이 있다. Windows 새 Speech API도
소개 문서와 API 명세의 성숙도 표기가 일치하지 않는다. 두 API를 범용 파일 전사의 기본값으로
선택하기보다, 기존 whisper.cpp/sherpa-onnx 후보와 별도로 검증한다. 근거는 아래 공식 문서다.

## 플랫폼 공식 문서

### 1. Apple WWDC25 — SpeechAnalyzer

[Bring advanced speech-to-text to your app with SpeechAnalyzer](https://developer.apple.com/videos/play/wwdc2025/277/)
— Apple, WWDC 2025.

- **확인:** 새 SpeechTranscriber는 장문·원거리 대화·회의를 위한 온디바이스 모델이다.
  파일과 실시간 오디오를 처리하며 시간 정보와 임시/확정 결과를 제공한다.
- **운영 차이:** AssetInventory로 모델을 설치하며 시스템이 보관·갱신한다. 모델은 앱 메모리
  공간 밖에서 실행된다. 이것은 기기 전체 RAM·저장 공간을 사용하지 않는다는 뜻이 아니다.
- **제약:** OS·하드웨어·언어 지원 확인이 필요하다. `supportedLocales`, `installedLocales`를
  검사해야 하며 SpeechTranscriber는 watchOS 대상이 아니다. 이 조사에서 한국어 지원을
  실기기로 조회하거나 한국어·영어 자동 전환을 검증하지는 않았다.
- **Recly 제안:** 지원 기기에서 첫 비교군으로 사용한다. 시스템 모델 업데이트로 결과가
  바뀔 수 있으므로 평가에 OS 빌드도 기록한다. 화자 분리 품질은 전사 품질과 별도 평가한다.

### 2. Apple — 장시간 백그라운드 작업

[Performing long-running tasks on iOS and iPadOS](https://developer.apple.com/documentation/backgroundtasks/performing-long-running-tasks-on-ios-and-ipados)
— 현재 공식 문서, iOS 26의 BGContinuedProcessingTask.

- **확인:** 전경에서 사용자 동작으로 시작한 긴 작업을 앱이 백그라운드로 이동한 뒤에도
  이어갈 수 있다. 시스템 UI로 진행률·취소를 제공하며 지원 기기에서는 GPU 사용 경로도 있다.
- **제약:** 무제한 실행 보장이 아니다. 진행률 보고와 종료 대응이 필요하다. 워치 파일이
  백그라운드에서 도착했다는 사실만으로 사용자 시작 작업 조건이 충족되지는 않는다.
- **Recly 제안:** 사용자가 누르는 ‘지금 전사’와 자동 처리 대기를 구분한다. 구간별 결과를
  저장하고 재개할 수 있어야 한다. GPU 사용 여부와 지원 여부도 엔진별로 검사한다.

Recly는 이미 `BackgroundJobs.swift:87`에서 수동 업로드 재시도에 이 API를 쓴다. 다만 일반
예약은 같은 파일 71행에서 네트워크를 요구하고, 117행의 진행률은 작업 종료 때만 완료된다.
로컬 전사에 연결하려면 네트워크가 없는 작업의 예약 조건과 실제 진행률을 함께 설계해야 한다.
기존 기반이 있다는 사실이 현재 로컬 전사를 지원한다는 뜻은 아니다.

### 3. Google ML Kit — GenAI Speech Recognition

[GenAI Speech Recognition API](https://developers.google.com/ml-kit/genai/speech-recognition/android)
— 현재 공식 문서, `1.0.0-alpha1`.

- **확인:** Basic은 전통적 온디바이스 인식, Advanced는 기기 내 생성형 모델이다.
  Basic의 지원 범위는 Android API 31 이상이며 한국어 `ko-KR`은 beta로 표시된다.
  Advanced의 한국어는 지원 언어에 있지만 기기는 현재 Pixel 10/11로 한정되어 있다.
- **핵심 제약:** 사용자 오디오를 파일 디스크립터로 공급할 때 헤더 없는 PCM16/mono/16kHz를
  **실시간 속도**로 공급해야 한다. 일반 파일을 최대 속도로 읽히는 방식은 지원하지 않는다.
  따라서 한 시간 파일을 몇 분 안에 전사할 수 있는 배치 API라고 가정하면 안 된다.
- **Recly 제안:** Android 실시간 메모의 실험 후보로 남긴다. Galaxy 사용자의 장문 파일 전사
  기본 엔진은 별도로 마련한다. SDK Alpha 상태, 모델 준비·다운로드 및 지원 여부를 처리해야 한다.

Recly의 `android/app/build.gradle.kts:35`는 minSdk 34다. OS 하한을 넘는다는 사실만으로
Advanced 모델이 Galaxy에서 실행되는 것은 아니다.

### 4. Microsoft — Windows AI Speech Recognition

[Speech Recognition 개요](https://learn.microsoft.com/en-us/windows/ai/apis/speech-recognition),
[SpeechRecognitionModel API 명세](https://learn.microsoft.com/en-us/windows/windows-app-sdk/api/winrt/microsoft.windows.ai.speech.speechrecognitionmodel?view=windows-app-sdk-2.0-experimental)
— 개요 갱신 2026-07-07, API 명세는 조사 시점 기준.

- **확인:** 개요는 오프라인 파일 배치·스트리밍 인식, NPU 및 CPU 실행, 필요시 모델 설치를 안내한다.
  Windows 11 24H2 이상과 WinAppSDK 1.7.1 이상을 명시한다.
- **문서상 불확실성:** 연결된 API 명세는 `2.0-experimental`로 열리고 클래스에도 Experimental
  속성이 있다. 개요의 최소 버전만으로 안정 SDK에서 사용 가능하다고 결론 내리지 않는다.
- **추가 확인:** 한국어 지원은 읽은 문서에서 확정하지 못했다. 개요의 MSIX·`systemAIModels`
  안내는 문구가 ‘imaging APIs’여서 Speech에 적용되는 정확한 조건도 확인이 필요하다.
- **Recly 제안:** 추적 후보로 둔다. 현재 MSI 배포(`windows/app/build.gradle.kts:126`)에서의
  패키징, WinRT 연결, 실제 SDK 컴파일과 한국어 인식 가능 여부가 채택 전 확인 사항이다.

## 한국어와 회의 품질을 평가할 자료

### 5. 한국어 개발 회의 35분, 7개 구성 비교

[STT 모델 실제 성능 비교: 한국어 회의 녹음 35분, 7개 모델 테스트](https://blog.dongjun.win/posts/korean-stt-7-model-comparison/)
— 권동준, 글 2026-06-04 / 실험 2026-02.

- **관찰:** Apple Silicon에서 35분 45초의 2인 한국어 개발 회의를 비교한 실험이다.
  작성자는 WhisperKit/Core ML large-v3-turbo FP16을 가장 유용하게 평가했다.
  영문 기술 용어, 무음에서의 환각, 타임스탬프를 실제 사용 관점에서 살핀다.
- **특히 유용한 부분:** mic/system을 따로 전사할 때 긴 선행 무음이 문제를 일으킨 경험,
  합쳐진 전사에서 마이크 전사를 빼는 방식의 한계를 기술한다.
- **한계:** 녹음 한 건의 실용 평가다. 전체 정답 전사 기반의 표준 WER 순위가 아니며
  칩·RAM 정보도 충분하지 않다. 숫자를 Recly 성능 예상치로 쓰지 않는다.
- **Recly 제안:** `mix` 기준 평가 후 채널별 처리를 비교한다. 별도 system 트랙에도 여러 원격
  화자가 있을 수 있으므로 트랙과 사람을 동일시하지 않는다. 무음 제거 시 원본 시간축을 보존한다.

### 6. HiKE — 한국어·영어 혼용 평가

[HiKE: Hierarchical Evaluation Framework for Korean-English Code-Switching Speech Recognition](https://aclanthology.org/2026.findings-eacl.33/)
— Paik 외, Findings of EACL, 2026-03.

- **확인:** 자연스러운 한국어·영어 혼용을 단어·구·문장 수준으로 나누고 외래어도 구분하는
  평가 틀이다. 다국어 모델이라는 사실만으로 혼용 인식이 충분하지 않음을 평가한다.
- **Recly 제안:** ‘한국어 지원’ 체크 한 칸을 대체할 테스트 구조로 활용한다. 제품명·약어·영문
  구절이 섞인 발화를 따로 평가하고, 자동 언어 감지와 한국어 지정도 같은 음성으로 비교한다.
  외래어 표기와 실제 영어 전환을 한 종류의 오류로 몰아넣지 않는다.
- **한계:** 이 논문이 현재 모든 신규 모델이나 Recly 런타임을 비교한 것은 아니다.

### 7. KsponSpeech — 자연스러운 한국어 대화 기준

[KsponSpeech: Korean Spontaneous Speech Corpus for Automatic Speech Recognition](https://ksp.etri.re.kr/ksp/article/read?id=62525)
— Bang 외, Applied Sciences, 2020. ETRI 자료.

- **확인:** 약 969시간, 약 2,000명의 한국어 자연 대화로 구성된 연구용 말뭉치다.
  깨끗한 환경의 두 사람 대화가 중심이다.
- **Recly 제안:** 낭독 음성만으로 후보를 고르는 것을 피할 비교 자료다. 다만 실제 원거리 회의,
  겹친 발화, 워치 마이크, 주변 소음 자료는 별도로 보완한다. 데이터 이용 조건을 확인한 후
  평가용 부분만 사용한다. 이번 조사에서는 음성 데이터를 내려받지 않았다.

### 8. Whisper의 비음성 환각 연구

[Investigation of Whisper ASR Hallucinations Induced by Non-Speech Audio](https://arxiv.org/abs/2501.11378)
— Barański 외, ICASSP 2025.

- **확인:** 비음성 소리로 유발되는 환각과 반복적으로 나타나는 표현을 실험했다.
  특정 표현을 후처리하는 방식의 효과도 보고한다.
- **Recly 제안:** 무음·키보드·음악·환경음만 있는 테스트에서 ‘말하지 않은 문장’을 별도로 센다.
  음성 구간 검출(VAD)을 평가하되 작은 목소리를 누락하는지도 확인한다.
  ‘감사합니다’ 같은 표현을 무조건 삭제하는 규칙은 실제 발화를 없앨 수 있어 그대로 채택하지 않는다.

### 9. WhisperX 논문 — 전사와 시간 정렬의 분리

[WhisperX: Time-Accurate Speech Transcription of Long-Form Audio](https://arxiv.org/abs/2303.00747)
— Bain 외, Interspeech 2023.

- **확인:** 긴 오디오에서의 시간 어긋남·반복 문제를 다루며 VAD와 강제 정렬을 조합한다.
- **Recly 제안:** 문장이 맞는지, 해당 문장의 재생 위치가 맞는지, 화자가 맞는지를 따로 평가한다.
  논문의 GPU 배치 추론 가속 수치를 모바일 속도로 환산하지 않는다. 정렬·화자 모델까지 넣었을 때의
  전체 RAM과 처리 시간을 측정해야 한다.

## 벤치마크 글을 읽을 때 남겨야 할 조건

### 10. Argmax — Apple SpeechAnalyzer와 WhisperKit 비교

[Apple SpeechAnalyzer and Argmax WhisperKit](https://www.argmaxinc.com/blog/apple-and-argmax)
— Argmax, 2025-06-20.

- **조건:** M4 Mac mini, macOS 26 첫 베타, 영어 Earnings22 일부를 사용한 공급사 자체 비교다.
  데이터·버전·측정 스크립트를 함께 공개한 점이 참고할 만하다.
- **해석:** 한국어·iPhone·현재 OS의 순위로 옮길 수 없다. 특히 당시 기능 표의 WhisperKit
  화자 분리 부재를 현재까지 적용하면 안 된다. 현재 SpeakerKit 공개 여부는 앞선 오픈소스
  조사에서 별도 확인했다. 오래된 성능 글은 방법론과 당시 결과를 구분해서 읽는다.

### 11. Lyonesse — Apple·Parakeet·MOSS 비교

[Parakeet vs Apple's Speech API vs MOSS: Benchmark Round 2](https://lyonesse.app/blog/parakeet-moss-apple-speech-benchmark.html)
— 제품 개발팀, 2026-07-15.

- **조건:** M2 Pro 32GB, macOS 26.5.1, 영어 LibriSpeech 5,559개 발화. 모델별 원시 전사와
  정규화 방법을 공개한다. 다른 작업이 돌던 기기의 시간 측정이라 정밀 속도 순위는 제시하지 않는다.
- **관찰:** Core ML 양자화 실행 결과가 원본 GPU 모델 수치와 달랐다. MOSS는 발화가 바로
  시작되는 짧은 클립에서 빈 결과를 내는 사례가 있어 앞쪽 무음과 재시도 규약을 적용했다고 보고한다.
- **한계:** 영어 낭독 정확도이며 한국어 회의 검증이 아니다. 62초 합성 연결 음성의 화자 실험도
  겹친 발화가 있는 실제 회의 DER 평가를 대체하지 않는다. 양자화 하나만이 차이의 원인이라고
  실험적으로 분리한 결과도 아니다.
- **Recly 제안:** 배포할 변환본으로 측정하고 실패·빈 출력도 결과에 포함한다. 잘린 첫 음절,
  파일 시작 즉시 발화, 중단된 마지막 발화도 테스트에 추가한다.

## 이미 배포된 제품에서 배울 부분

### 12. Aiko — 기기별 모델과 iOS 실행 경험

[Aiko 공식 설명·FAQ](https://sindresorhus.com/aiko).

- **확인:** 로컬 Whisper 앱이며 Mac과 iOS에서 메모리 조건에 따라 다른 모델을 사용한다고
  설명한다. 긴 iOS 전사에서는 앱을 열어 두도록 안내하며 반복·무음 처리 옵션도 설명한다.
- **Recly 제안:** 기기별 모델 크기와 작업 상태 안내를 참고한다. Aiko의 현재 FAQ를 근거로
  iOS 전체에서 백그라운드 전사가 불가능하다고 단정하지 않는다. 위의 iOS 26 API 조건과
  구분해야 한다. 이 제품의 설명만으로 Recly의 배터리나 발열 수준을 예측하지 않는다.

### 13. MacWhisper — 화자 수정과 기능별 데이터 이동

[Automatic Speaker Recognition](https://docs.macwhisper.com/article/32-automatic-speaker-recognition-in-macwhisper),
[Keeping Transcriptions Private](https://docs.macwhisper.com/article/52-keeping-transcriptions-private).

- **확인:** 지원 모델에서 익명 화자 라벨을 붙이고 사용자가 이름을 수정하는 흐름을 제공한다.
  지원 문서는 로컬 전사와 클라우드 번역·AI 프롬프트를 구분해 설명한다.
- **Recly 제안:** 초기에는 ‘화자 1/2’를 제공하고 수정할 수 있게 한다. 전사 결과의 화자 번호와
  실제 사람의 신원을 구분한다. 전사가 로컬이어도 Drive 업로드나 외부 요약이 설정되어 있으면
  데이터가 이동하므로, 워크플로우 단계별로 처리 위치를 표시한다.

### 14. Superwhisper — 음성 모델과 후처리 모델 분리

[AI models in Superwhisper](https://superwhisper.com/models).

- **확인:** 음성을 글로 바꾸는 모델과 선택적 문장 후처리 모델을 별도로 고른다.
  로컬 음성 인식 뒤 클라우드 언어 모델을 연결하는 조합도 명시한다.
- **Recly 제안:** ‘로컬 전사’와 ‘전체 워크플로우 오프라인’을 구별한다. 전사 원문을 보존하고
  요약·교정 결과는 별도 산출물로 둔다. 이 페이지의 제품 자체 성능 순위는 독립 검증으로 취급하지 않는다.

## Recly에 적용할 평가·설계 제안

아래는 위 자료와 현재 코드를 바탕으로 한 제안이며 구현 완료 사항이 아니다.

| 판단할 질문 | 평가 방법 | 기록할 결과 |
|---|---|---|
| 한국어 메모가 쓸 만한가? | 짧은 워치 메모·일반 대화, 한국어 지정/자동 감지 비교 | CER·WER, 숫자·이름 오류, 빈 출력 |
| 한영 혼용을 보존하는가? | HiKE 분류를 참고한 단어·구·문장 전환 | 영문 용어 오류와 외래어 표기를 별도 집계 |
| 긴 회의에서 누락되는가? | 30~60분, 2인/다인, 원거리·겹침 | 구간 누락, 반복, 화자 오류, 시간 위치 오차 |
| 조용할 때 문장을 만드는가? | 긴 무음, 환경음, 조용한 system 트랙 | 비음성 구간의 출력과 작은 발화 누락 |
| 폰에서 작업을 끝내는가? | 화면 잠금·앱 전환·취소·재실행 | 완료율, 재처리량, 진행률, 최대 RAM·발열·배터리 |
| 실제로 오프라인인가? | 모델 준비 후 네트워크 차단 | 전사 완료 여부, 업로드·요약 대기 상태 |
| 재현 가능한 비교인가? | 같은 파일·정답·정규화, 실제 배포 모델 | OS·칩·RAM·엔진·모델·양자화·옵션·최초/재실행 시간 |

첫 실험 범위는 Apple에서 **SpeechAnalyzer 대 WhisperKit**, 공통 엔진에서는
**whisper.cpp 대 sherpa-onnx/Qwen3-ASR** 정도로 좁힌다. 시스템 API 지원 범위 밖의 기기도
목표에 포함하며, 모델 수를 늘리기 전에 결과를 비교 가능한 형식으로 남긴다.

로컬 실행 경로를 추가할 때 엔진 연결 외에 다음 계약이 함께 필요하다.

- API 키 없는 provider: 현재 `TranscribeRunner.kt:62` 및 `spec/workflow.schema.json:106`은 키를 전제로 한다.
- Drive 선행과 로컬 결과 확정 분리: `WorkflowParser.kt:316`은 업로드 선행을 강제한다.
- 모델 상태: 미설치·다운로드·준비·실행·중단을 구분하고 초기 다운로드 뒤 오프라인을 검증한다.
- 시간축과 원문 보존: VAD·채널 처리·교정 후에도 원본 녹음의 재생 위치와 연결한다.
- 부분 결과 재개: OS 종료 후 이미 확정한 구간을 재사용하고 경계의 중복 및 화자 번호를 다룬다.

## 조사·검증 기록

- `git status --short`: 기존 오픈소스 조사 문서 한 개가 untracked 상태였으며 코드 수정은 없었다.
- `nl -ba apple/RecKit/Sources/RecKit/Jobs/BackgroundJobs.swift`:
  71행 `request.requiresNetworkConnectivity = true`, 87행 `uploadNow`,
  117행 `continued.progress.totalUnitCount = 1` 확인.
- `nl -ba android/app/build.gradle.kts`: 35행 `minSdk = 34` 확인.
- `nl -ba windows/app/build.gradle.kts`: 126행 `targetFormats(TargetFormat.Msi, TargetFormat.Dmg)` 확인.
- `rg -n 'secretRef|drive.upload.*step|mono|mix'`로 provider·스키마·파서 근거를 재확인했다.
  출력에는 `TranscribeRunner.kt:62: val key = apiKey(deps, step.secretRef)` 및
  `WorkflowParser.kt:316: "a 'drive.upload' step must come before a 'transcribe' step"`가 포함되었다.
- 공식 문서와 글만 읽었다. 직접 가져온 Google 문서 텍스트와 Apple 공식 Markdown은
  `/tmp/recly-stt-research-20260923`에 보관했다. 모델 설치·실기기 API 실험·음성 업로드는 수행하지 않았다.

변경 범위는 조사 문서와 기존 조사 문서의 후속 링크다. 검증 결과는 아래에 기록한다.

- `make test`: `BUILD SUCCESSFUL in 3s`; `123 actionable tasks: 1 executed, 122 up-to-date`.
  기존 JVM 검사를 확인한 결과이며 ASR 품질·성능 검증은 아니다.
- `git diff --check`: 출력 없음. 두 untracked 문서는 별도 검사로
  `Research document checks: PASS (2 files; links, whitespace, code references)`를 확인했다.
