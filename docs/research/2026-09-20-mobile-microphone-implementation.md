# 모바일·워치 마이크 녹음 보완 — 2026-09-20

## 범위와 동작

폰·워치는 마이크만 녹음한다. 시스템 오디오는 macOS·Windows 회의 모드에만 있다.
설정 화면이나 안내 단계를 추가하지 않고 입력 선택·전환과 녹음 저장 경로를 보완했다.

- iPhone: 유선·USB, Bluetooth HFP/BLE, 내장 마이크 순으로 자동 선택한다. 같은 종류의 장치가 여럿이면
  현재 입력을 유지한다. 외부 입력 요청이 거절되면 OS 기본 입력을 사용하고 재연결·새 녹음 때 다시 시도한다.
- Apple Watch: watchOS 11+에서 HFP 입력을 허용한다. watchOS는 앱의 `setPreferredInput`을 지원하지 않아
  연결된 입력 중 실제 선택은 OS가 담당한다. watchOS 10은 기본 녹음 세션을 유지한다.
- Apple 공통: 입력 장치·실제 포맷이 바뀌면 새 엔진·변환기로 같은 녹음을 이어간다. 하드웨어 콜백에서는
  버퍼와 시간 정보만 복사하고 파일 처리는 별도 큐에서 수행한다. 정지·전환 시 큐와 변환기의 남은 오디오를 저장한다.
  전화·Siri 중에는 재연결을 미루고 실제 녹음 재시작 성공 뒤 무음 구간을 닫는다. 종료 이후의 이전 알림은 무시한다.
- OS 오디오 서비스가 유실·리셋되면 기존 저장·종료 경로로 넘기고 다음 사용자 녹음 동작에서 다시 초기화한다.
  리셋 뒤 사용자 동작 없이 오디오 세션을 활성화하지 않는 [Apple QA1749](https://developer.apple.com/library/archive/qa/qa1749/_index.html)를 따른다.
- Android·Galaxy Watch: `MediaRecorder.setPreferredDevice`로 유선·USB, Bluetooth, OS 기본 입력 순으로
  요청하고 실제 `routedDevice`를 확인한다. 거절·5초 미반영 시 기본 입력으로 돌아간다. 벤더 전용 입력은 OS 선택을 따른다.
  장치 전환 때문에 녹음기나 세그먼트 파일을 재생성하지 않는다. 장치 알림은 250 ms 단위로 합치고 1초마다 경로를 확인한다.
  통화 모드·SCO·다른 앱의 통신 장치 설정은 바꾸지 않는다. 라우트 정보 누락만으로 녹음을 종료하지 않는다.
- Android의 입력·녹음기 콜백은 현재 녹음기와 일치할 때만 반영한다. 무음 구간 콜백도 종료 뒤에는 무시한다.

## 검증

| 실행 명령 | 실제 결과 |
| --- | --- |
| `make core` | `BUILD SUCCESSFUL in 8s` |
| `make test apk` | `BUILD SUCCESSFUL in 6s`, `BUILD SUCCESSFUL in 9s` |
| `make wear-apk` | `BUILD SUCCESSFUL in 10s` |
| `make ios watch mac` | `BUILD SUCCEEDED` 3회 |
| `make ios-kit-test` | `Executed 500 tests, with 1 test skipped and 0 failures`; `TEST SUCCEEDED` |
| `make mac-test` | `Executed 501 tests, with 1 test skipped and 0 failures`; `TEST SUCCEEDED` |

Android 공용 녹음 모듈은 65개 테스트, 실패·오류·건너뜀 0개다. 새 라우팅 테스트 9개가 포함된다.
Apple에는 선택 정책 3개와 같은 포맷의 다른 마이크 전환·중복 알림 무시·일시 시작 실패 재시도·재시도 중 정지
4개 테스트를 추가했다. 포맷이 같은 마이크로 전환해도 앞뒤 오디오가 하나의 녹음에 저장되는 것을 실제 AAC 파일 경로로 확인한다.

주요 구현·테스트 위치:

- `apple/RecKit/Sources/RecKit/Recorder/IOSAudioInput.swift:67` — 입력 선택·실제 포맷 초기화
- `apple/RecKit/Sources/RecKit/Recorder/IOSAudioInput.swift:150` — 종료 시 큐 저장·이전 알림 차단
- `apple/RecKit/Sources/RecKit/Recorder/IOSAudioInput.swift:224` — 입력 변경 처리
- `android/recording/src/main/kotlin/app/recly/recording/MicrophoneRouting.kt:35` — 요청·실제 경로 검증과 기본 입력 복귀
- `android/recording/src/main/kotlin/app/recly/recording/MicrophoneRouting.kt:142` — 종료·지연 알림 정리
- `apple/RecKit/Tests/RecKitTests/SegmentedRecorderTests.swift:248` — 동일 포맷 장치 전환 후 오디오 보존
- `apple/RecKit/Tests/RecKitTests/SegmentedRecorderTests.swift:303` — 재연결 대기 중 정지
- `android/recording/src/test/kotlin/app/recly/recording/MicrophoneRouteStateTest.kt:8` — 입력 선택·거절·재연결·무음 구간 테스트

로그: `build/verification/mobile-core.log`, `mobile-android-final.log`, `mobile-wear-build.log`,
`mobile-apple-final.log`. Wear 디버그 패키징은 새 `make wear-apk` 명령으로 재현할 수 있다.

## 실기기 확인 범위

Apple 테스트의 실제 마이크 smoke test는 opt-in 설정이 없어 플랫폼별 1개씩 건너뛰었다.
이번 검증은 합성 오디오·실제 변환기/AAC 파일·시뮬레이터 빌드·JVM 상태 테스트다.
실제 iPhone·Apple Watch·Android·Galaxy Watch에서 Bluetooth 연결/해제, 통화 인터럽션,
장시간 배경 녹음, OS 오디오 서비스 리셋을 재현한 검증은 하지 않았다.
특정 헤드셋을 연결하면 모든 OS·기기에서 반드시 그 마이크로 전환된다는 보장은 할 수 없다.
