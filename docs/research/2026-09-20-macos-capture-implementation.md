# macOS 회의 오디오 캡처 보완 — 2026-09-20

## 적용 범위

이번 변경은 macOS 회의 녹음의 입력 선택, 시스템 캡처, 스트림 정렬과 복구를 다룬다.
기존 작업 트리에 있던 Google Drive/OAuth·동기화 변경은 유지했다.

- 자동 마이크 선택: 저장한 직접 선택 → 확인된 회의 앱의 유일한 활성 입력 → macOS 기본 입력.
  브라우저는 그 브라우저 소유의 회의 창 제목을 확인할 수 있어야 우선 선택한다.
- 음소거 때 선택 유지, 장치 변경 1초 안정화, 연결 해제 2초 유예, 재연결 시 저장한 선택 복원.
  선택 장치의 전체 PCM 포맷과 연결 상태를 주기적으로 확인한다.
- Recly의 AudioUnit만 선택 장치에 바인딩한다. OS 기본 입력·출력, 회의 앱의 장치/음소거를 바꾸지 않는다.
  음성 처리는 기존처럼 기본 꺼짐이며, 켤 경우 처리 노드를 구성한 뒤 마이크를 바인딩한다.
- 시스템 aggregate에서 실제 출력 subdevice를 제거했다. 샘플레이트 설정은 private 가상 장치에만 한다.
  tap 및 aggregate 포맷 변경을 감시하고, 실제 수집 timestamp와 프레임 수의 비율도 검사한다.
- 콜백에서 복사한 오디오는 별도 큐에서 변환·저장한다. 큐는 2초로 제한하며, 마이크 처리에 0.6초 지연을
  두어 시스템 리샘플러의 출력 지연을 흡수한다. 정지 시 대기 버퍼와 변환기 꼬리를 모두 저장한다.
- 수집 timestamp가 있는 스트림은 host clock으로 정렬한다. 큰 시스템 버퍼를 마이크 콜백 크기로 잘라 버리지
  않으며, 작은 클록 오차가 전체 녹음 길이에 걸쳐 누적되지 않도록 각 입력의 수집 시각을 반영한다.
- 잘못된 전달 속도·timestamp 누락·버퍼 부족은 복구 대상으로 처리한다. 시스템 복구는 마이크와 독립적이며,
  마이크 재시작은 일시적인 Bluetooth 연결 전환을 재시도한다. 미복구 손실도 메타에 남긴다.
- 현재 마이크, 시스템 출력 및 복구 상태를 기존 테마와 글자 크기로 표시한다. 설정은 자동/마이크 선택 한 줄이다.
- `capture-diagnostics.json`에 장치, 레이트, 측정 레이트, OS, 복구·손실 이벤트를 최대 512개 기록한다.
  파일은 녹음 폴더에만 저장하고 업로드·웹훅 대상으로 등록하지 않는다.

## 검증

실행한 명령과 실제 결과:

| 명령 | 결과 |
| --- | --- |
| `make core` | `BUILD SUCCESSFUL in 1m 53s`; XCFramework 스테이징 완료 |
| `make test` | `BUILD SUCCESSFUL in 9s`; 123 actionable tasks, 1 executed / 122 up-to-date |
| `make mac-test` | `Executed 494 tests, with 1 test skipped and 0 failures`; `TEST SUCCEEDED` |
| `make mac ios watch` | `BUILD SUCCEEDED` 3회; Mac·iPhone 시뮬레이터·Apple Watch 시뮬레이터 빌드 완료 |
| `git diff --check` | 출력 없음, 종료 코드 0 |

회귀 테스트는 8/16/24/44.1/48 kHz 입력에서 440 Hz 주파수와 연속 에너지를 확인한다.
실제 24 kHz를 48 kHz로 표시한 경우의 감지, 큰 입력 배치, timestamp 누락, 느린 파일 처리 중 큐 상한,
정지 시 지연된 마이크·시스템 버퍼가 세 트랙에 모두 남는 경로를 검증한다.
1000 ppm 빠른 입력 클록에서도 80초 시점의 오디오 사건이 3 ms 이내에 정렬되는 테스트를 포함한다.

주요 테스트 위치:

- `apple/RecKit/Tests/RecKitTests/CapturedAudioTests.swift:105` — 장시간 클록 오차 정렬
- `apple/RecKit/Tests/RecKitTests/CapturedAudioTests.swift:135` — 다섯 입력 레이트·큰 버퍼의 주파수/에너지
- `apple/RecKit/Tests/RecKitTests/MeetingRecorderTests.swift:23` — 종료 시 세 트랙의 지연된 꼬리 저장
- `apple/RecKit/Tests/RecKitTests/MicrophoneSelectionTests.swift:6` — 자동/직접 선택·음소거·연결 전환
- `apple/RecKit/Tests/RecKitTests/ProcessTapCaptureTests.swift:23` — 물리 출력 subdevice 미포함

상세 명령 출력은 `build/verification/audio-core-build.log`, `audio-jvm-test.log`,
`audio-mac-test.log`, `audio-apple-builds.log`에 있다.

## 실기기 검증의 한계

자동 테스트는 합성 오디오와 실제 AVAudioConverter/AAC 파일 경로를 사용한다.
실제 마이크 smoke test 1개는 opt-in 환경 변수가 없어 건너뛰었다.
AirPods가 연결된 장치 목록은 확인했으나, 이 변경으로 실제 Teams/Meet/Zoom 통화를 녹음하거나
상대방이 듣는 마이크 품질을 비교하지는 않았다. Bluetooth 프로필 전환, tap-only aggregate의 실기기 동작,
실제 상대방 음질은 별도 통화 검증이 남아 있다. 합성 오디오 테스트 통과를 실기기 통화 검증으로 간주하지 않는다.
