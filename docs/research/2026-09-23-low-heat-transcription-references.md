# 체감 발열을 줄이는 로컬 전사 구현 레퍼런스

확인일: 2026-09-23. 사용자의 요구는 자동 전사를 유지하면서 폰·컴퓨터가 뜨거워지지 않는
사용감이다. 이번 조사는 웹 문서와 소스 읽기만 수행했다. 모델 추론, 빌드, 부하 시험은
재개하지 않았다. 아래 구현의 존재와 실제 기기의 체감 발열 검증은 별개다.

**범위 정정:** Recly에서 현재 찾는 것은 녹음이 끝난 파일의 자동 전사다. 아래 Argmax의
배터리 모드, murmur의 자막 제어, Edge-Veda의 스트리밍 세션은 주로 실시간 처리 사례다.
이를 완성된 저발열 파일 전사 솔루션으로 소개하면 안 된다. 파일 전사에서는 Tapeback과
bestASR의 일부 구현이 더 직접적이지만, 조사 범위에서 장시간 파일 전사의 지속적인 발열
감시·중단·재개와 체감 온도까지 입증한 완성형은 확인하지 못했다.

## 1. 실시간 전사의 제품 구현: Argmax Pro SDK

[실시간 전사 공식 문서](https://app.argmaxinc.com/docs/examples/real-time-transcription)

- `voiceTriggered`: 입력 에너지를 기준으로 추론을 실행하고 최소 처리 간격을 설정한다.
- `batteryOptimized`: 음성 기반 실행에 적응형 지연을 추가해 배터리와 장시간 발열을 고려한다.
- 문서는 iOS에서 지속 사용을 위해 최소 처리 간격 2초를 권한다. 배터리 최적화 모드는
  iOS 세션 10분 이상 또는 하루 2시간 이상, macOS 세션 1시간 이상 또는 하루 4시간 이상을
  적용 사례로 든다. 이는 벤더의 권장 조건이며 발열이 없다는 측정 보증이 아니다.
- 확인한 Swift 예제는 `WhisperKitPro`와 `DecodingOptionsPro`를 사용한다. 공개 WhisperKit을
  설치하면 이 상용 SDK의 모드까지 제공된다고 가정하면 안 된다.
- 실시간 입력에 대한 정책이다. 기존 녹음 파일을 빠르게 처리하는 배치 작업에 같은 제어가
  자동 적용되는지는 별도 확인이 필요하다.

**Recly에 가져올 원칙:** 사용자가 자동 전사를 켰다고 해서 추론을 가능한 한 자주 실행할
필요는 없다. 음성 구간과 허용 지연을 이용해 불필요한 반복 연산을 줄인다.

## 2. Android 실서비스 사례: LiteRT NPU + Argmax + Heidi

[Google, 2026-04-23](https://developers.googleblog.com/building-real-world-on-device-ai-with-litert-and-npu/)
· [Argmax Android 출시, 2026-03-18](https://www.argmaxinc.com/blog/argmax-pro-sdk-for-android)
· [Heidi 도입 사례, 2025-11-10](https://www.argmaxinc.com/blog/heidi-health-ai-scribe-built-with-argmax-enterprise)

- Google은 Tensor·MediaTek·Qualcomm에서 Argmax의 GPU 대비 NPU 성능 개선과 장시간
  전사 시 배터리 부담을 줄이는 사례를 설명한다. 미리 컴파일한 모델과 기기별 배포도 포함한다.
- Heidi는 실제 온디바이스 전사 고객 사례다. Apple 화면 잠금 후에도 전사를 이어가는
  요구가 설명되어 있다. 모든 기기·모델·언어의 동작을 입증하는 자료는 아니다.
- Android 출시 당시 설명의 대표 모델은 Parakeet v2다. 이를 한국어 성능의 근거로 쓰지 않는다.
- [2026-09-23 SDK 3 발표](https://www.argmaxinc.com/blog/argmax-sdk-3)는 Qwen3-ASR,
  언어 혼용, 타임스탬프, 실시간 API 지원을 소개한다. 발표만으로 이 모델의 모든 Android
  NPU 경로와 배터리 최적화 동작까지 확인됐다고 판단하지 않는다.

**Recly에 가져올 원칙:** Android에서도 가속기를 활용한 지속 전사는 실제 제품 사례가
있다. CPU에서 큰 모델을 실행한 결과만으로 모바일 로컬 전사 전체의 발열을 판단하지 않는다.

## 3. 직접 읽을 수 있는 앱 구현: murmur

macOS 회의 녹음 앱. 저장소 LICENSE는 AGPL-3.0이다.

확인 리비전: `12601a323064d4a19e5894fa67a5102e4ef07b1c`.

- [thermal.rs:29](https://github.com/murmur-io/murmur/blob/12601a323064d4a19e5894fa67a5102e4ef07b1c/src-tauri/src/thermal.rs#L29):
  정상 3초, `fair` 6초, `serious` 9초로 실시간 처리 간격을 늘린다.
- [thermal.rs:67](https://github.com/murmur-io/murmur/blob/12601a323064d4a19e5894fa67a5102e4ef07b1c/src-tauri/src/thermal.rs#L67):
  열 상태 악화는 즉시 반영하고, 개선은 연속 두 번 확인한 뒤 반영한다.
- [thermal.rs:95](https://github.com/murmur-io/murmur/blob/12601a323064d4a19e5894fa67a5102e4ef07b1c/src-tauri/src/thermal.rs#L95):
  `serious`에서 보조 처리, `critical`에서 일반 실시간 자막 추론을 중지한다.
- [live.rs:513](https://github.com/murmur-io/murmur/blob/12601a323064d4a19e5894fa67a5102e4ef07b1c/src-tauri/src/transcribe/live.rs#L513):
  위 제어기가 실제 자막 루프의 대기 간격에 연결되어 있다.

**한계:** 녹음과 녹음 종료 후 배치 전사는 이 제어기의 대상이 아니다. 열 상태를 읽지
못하면 정상으로 취급하며, 일부 사용자가 시작한 작업은 자막 중지 조건을 우회한다.
따라서 전체 전사 발열을 제어하는 완성품으로 그대로 채택할 수는 없다.

**Recly에 가져올 원칙:** 녹음을 보존하며 전사만 늦춘다. 재개 조건을 둬서 중단과 재개가
빠르게 반복되지 않게 한다. Recly는 이 정책을 저장된 녹음의 배치 전사에도 적용해야 한다.

## 4. 자원 예산 제어기 참고: Edge-Veda

Flutter 온디바이스 AI 런타임. 저장소 LICENSE는 Apache-2.0이다.

확인 리비전: `57f0edf38a6652f0f8fb549a11edeef1d0e95cd1`.

- [scheduler.dart:361](https://github.com/ramanujammv1988/edge-veda/blob/57f0edf38a6652f0f8fb549a11edeef1d0e95cd1/flutter/lib/src/scheduler.dart#L361):
  주기적으로 열 상태·배터리 소모·지연 등의 예산을 검사한다.
- [scheduler.dart:504](https://github.com/ramanujammv1988/edge-veda/blob/57f0edf38a6652f0f8fb549a11edeef1d0e95cd1/flutter/lib/src/scheduler.dart#L504):
  낮은 우선순위 작업부터 실행 수준을 낮춘다.
- [whisper_session.dart:312](https://github.com/ramanujammv1988/edge-veda/blob/57f0edf38a6652f0f8fb549a11edeef1d0e95cd1/flutter/lib/src/whisper_session.dart#L312):
  STT 작업이 정지 상태면 추론하지 않고 입력 오디오를 버퍼에 남긴다.

**한계:** 확인한 STT 경로는 정지 여부만 검사한다. 중간 단계의 부하 감소가 음성 추론의
간격·스레드 수에 모두 연결되어 있는 구현은 아니다. 정지 중 메모리 버퍼 축적도 별도로
다뤄야 한다. README의 Android 상태는 초기 구조 마련 및 검증 대기다.

**Recly에 가져올 원칙:** 전사·화자 분리·요약을 공통 자원 예산으로 제어하되, 실제 엔진이
제어 값을 반영하는지 확인한다. 긴 녹음의 대기분은 메모리에 계속 쌓지 않고 디스크를 사용한다.

## 5. 제한적으로 참고할 배치 구현: Tapeback

확인 리비전: `20cc7c406ea4a4482ec2036fc7df1002223de5ce`. LICENSE는 Apache-2.0이다.

[transcriber.py:506](https://github.com/yastcher/tapeback/blob/20cc7c406ea4a4482ec2036fc7df1002223de5ce/src/tapeback/transcriber.py#L506)
에는 CUDA 처리 단계 사이에 쉬는 시간을 넣는 구현이 있다. 기본값은 0이며 해당 코드는
CUDA 경로에만 적용된다. GPU 열 제한을 확인하고 CPU로 전환하는 경로도 있다.

**Recly에 가져올 원칙:** 완료 구간 재사용과 단계 사이 대기는 참고할 만하다. 열 때문에
GPU가 제한됐다고 CPU 추론을 계속하는 동작은 체감 발열 억제 목표와 맞는지 따로 판단한다.

### 파일 전사 시작 시 모델 선택: bestASR

확인 리비전: `83297fcbd773290b5cffa270f48cfbc79734a523`.

[파일 입력 CLI](https://github.com/PsychQuant/bestASR)이며,
[DynamicHostState.swift:19](https://github.com/PsychQuant/bestASR/blob/83297fcbd773290b5cffa270f48cfbc79734a523/Sources/BestASRKit/Detect/DynamicHostState.swift#L19)
에서 `serious`·`critical` 또는 저전력 모드를 압박 상태로 판단한다.
[CommandCore.swift:323](https://github.com/PsychQuant/bestASR/blob/83297fcbd773290b5cffa270f48cfbc79734a523/Sources/BestASRKit/CommandCore.swift#L323)
는 자동 선택 프로필을 이때 `medium`에서 `low`로 바꾼다.

이는 **선택 시점의 모델·백엔드 정책**이다. 확인한 코드로는 파일 처리 내내 발열을
감시하며 중단·재개하는 제어기를 입증할 수 없다. `fair`를 압박으로 보지 않으므로
체감 발열을 사전에 억제한다는 보장도 없다.

## 6. 제품 설명과 발열 구현을 구분해야 하는 사례

[Talk Memo](https://www.kkirukstudio.com/talkmemo/)는 Watch 녹음을 iPhone에서 자동으로
텍스트화하는 로컬 전사 경험을 설명한다. 확인한 공개 페이지에는 열 제어 알고리즘,
전력 측정값, 긴 회의에서의 체감 온도 결과가 없다. 자동 전사 UX의 참고 사례로 사용한다.

[WhisperKit 논문](https://arxiv.org/html/2507.10860v1)의 §2.2.1은 ANE와 상태 유지 캐시를
이용한 전력 개선을 설명한다. 특정 디코더 측정값을 앱 전체 소비전력으로 바꾸어 인용하지
않는다. 논문 구현·상용 SDK·공개 SDK의 기능 제공 범위도 각각 확인해야 한다.

## 7. Recly 적용 판단

1. 파일 전사를 중심으로 평가한다. 실시간 자막 기능을 기본 요구로 추가하지 않는다.
2. Tapeback의 단계 간 대기와 bestASR의 시작 시 선택 정책을 직접 참고한다. Argmax,
   murmur, Edge-Veda는 다른 처리 방식에서 가져오는 부분적인 설계 참고로 분류한다.
3. 녹음은 계속 저장하고, 실시간 전사와 저장된 파일 전사 모두 자원 예산 안에서 실행한다.
4. 체감 발열이 목표이므로 심한 열 상태가 올 때까지 계속 달리는 정책으로 끝내지 않는다.
5. 고정 휴식 시간이나 작은 청크만으로 효율을 보장하지 않는다. 모델 구조에 따른 반복
   연산, 모델 로딩, 실제 가속기, 지속 전력을 함께 평가한다.

## 조사 검증 기록

- GitHub tree API로 위 세 저장소의 리비전과 소스 경로를 확인했다. 모두 `truncated: false`.
- 고정 리비전의 원본 코드를 받아 `rg -n`으로 열 제어·스케줄러·호출부를 확인했다.
- `murmur/thermal.rs` 29–105행과 실제 호출부, Edge-Veda 스케줄러와 STT 소비부,
  Tapeback의 단계 간 대기 구현을 읽었다. 외부 코드는 실행하지 않았다.
- 범위 정정 때 bestASR의 고정 리비전에서 열 상태 판정과 프로필 선택 호출부를 추가로 읽었다.
- 조사 원본은 `/tmp/recly-thermal-reference-20260923/`에 있다.
- 이 문서는 제품 구현 변경이나 실기기 발열 시험 결과가 아니다.
