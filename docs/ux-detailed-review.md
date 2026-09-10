# 모바일·데스크톱 UX 상세 재점검 — 2026-09-10

1차 UX 수정 이후 추가 진단 6건으로 확인한 5개 문제를 모두 수정했다. 모바일 하단 재생과 데스크톱 상단 재생·창
배치를 유지하면서 상태 갱신·오류 안내·입력 보존을 보완했다. 최초 점검의 실패 증거는 아래에 이력으로 남긴다.

## 수정 결과

| 항목 | 반영한 동작 | 현재 코드 근거 |
|---|---|---|
| 1. Apple 녹음·재생 충돌 | 새 녹음의 캡처를 열기 전에 기존 플레이어를 중지한다. 준비·녹음·종료 처리 중 재생과 탐색을 막고, 종료 완료 또는 시작 실패 때 해제한다. 전사와 별도로 다른 녹음의 상태도 관찰한다 | `apple/RecKit/Sources/RecKit/Recorder/RecorderSession.swift:79`, `Jobs/RecordingPlaybackGate.swift:5`, `Jobs/RecordingDetail.swift:141` |
| 2. Windows 디코딩 오류 | ffmpeg 자연 종료의 코드와 대기 시간 초과를 검사해 재생 실패를 표시한다. 중간 파트가 실패하면 다음 파트로 건너뛰지 않는다. 사용자의 중지·탐색은 실패로 표시하지 않으며 다음 재생으로 재시도한다 | `windows/app/src/main/kotlin/app/recly/windows/ui/RecordingPlayer.kt:912` |
| 3. 키 폼 교체 시 입력 손실 | 다른 키 입력 폼을 열 때 미저장 값을 확인한다. 계속 편집하면 기존 값을 보존하고 폐기를 선택해야 요청한 폼으로 이동한다. 같은 폼을 다시 열면 입력을 유지한다 | Android `WorkflowsViewModel.kt:339`, Apple `WorkflowsModel.swift:408`, Windows `WorkflowsModel.kt:394` |
| 4. 열린 녹음 상세 갱신 | 녹음 상태·파트·트랙·경로 변경을 전사와 별도로 구독한다. 녹음 종료 후 오디오를 갱신해 상세를 다시 열지 않아도 재생할 수 있다. 제목·전사만 바뀌면 플레이어를 초기화하지 않는다 | `core/src/commonMain/kotlin/recly/core/recording/RecordingRepository.kt:405`, Android `JobsViewModel.kt:351`, Apple `RecordingDetail.swift:148`, Windows `ShellModel.kt:995` |
| 5. 손상 전사 복구 | 명시적인 다시 시도에서 손상된 로컬 JSON 대신 Drive 원격본을 받아 검증 후 저장한다. 정상 로컬 파일은 계속 오프라인으로 읽는다. 다운로드 중 생성된 더 최신의 유효한 로컬 결과를 덮어쓰지 않도록 결과 기록과 교체를 같은 잠금으로 보호한다 | `core/src/commonMain/kotlin/recly/core/ReclyCore.kt:151`, `transcribe/RecordingResults.kt:72`, `transcribe/ResultFiles.kt:16` |

Android·Apple·Windows의 재시도 버튼은 공통 `retryResults`를 호출한다. 기존 Drive 다운로드 경로를 사용하며
새 외부 전송 경로나 추가 동의 단계를 만들지 않았다. Drive 원격본도 없거나 읽을 수 없는 경우는 읽기 실패 안내를 유지한다.

Apple 전체 테스트 도중 개인정보 설정 모델의 구독이 종료 후 남아 임시 DB를 읽는 문제도 확인해 정리했다.
모델이 종료될 때 구독·진행 중 읽기를 취소하고, 테스트는 종료 완료를 기다린 뒤 DB를 제거한다
(`apple/RecKit/Sources/RecKit/Workflow/TransferPrivacy.swift:112`). 이후 전체 테스트가 통과했다.

## 수정 후 검증

| 실행 명령 | 실제 결과 |
|---|---|
| `make test` | `BUILD SUCCESSFUL in 2s`; `123 actionable tasks: 1 executed, 2 from cache, 120 up-to-date` |
| JVM XML 집계 | core 538 + Android 470(app 333·datalayer 23·recording 56·wear 58) + Windows 365 = 1,373개; 실패·오류·건너뜀 0 |
| `make core` | `BUILD SUCCESSFUL in 7m 29s`; `47 actionable tasks: 27 executed, 20 up-to-date`; XCFramework 생성·RecKit 배치 완료 |
| `make mac-test mac ios watch` | `Executed 449 tests, with 1 test skipped and 0 failures`; `TEST SUCCEEDED`; macOS·iOS·watchOS 각각 `BUILD SUCCEEDED` |
| `ANDROID_SERIAL=emulator-5554 make android-ui-test` | 별도 Android 16 AVD `recly_ux_review`에서 한국어·기본 글씨로 6개 모두 통과; `BUILD SUCCESSFUL in 27s` |
| 실제 ffmpeg 진단을 넣은 `make windows-test` | `BUILD SUCCESSFUL in 18s`; `AUDIT decoderExit=183 playing=false failed=true` |
| `git diff --check` | 출력 없음, 종료 코드 0 |

회귀 테스트는 단순 상태 대입 외에 다음 경로를 검사한다.

- Apple: 캡처 시작 직전에 플레이어가 중지됐는지, 시작·종료 대기 중 재생 차단, 시작 실패 후 해제,
  다른 녹음 시작·종료 관찰, 열린 상세의 녹음 종료 후 오디오 갱신. `RecorderSessionTests.swift:11`, `RecordingDetailTests.swift:25`.
- Windows: 비정상 디코더 종료의 실패·재시도, 중간 파트 실패, 실제 셸 모델의 녹음 시작→상세 열기→종료 후 오디오 갱신.
  `RecordingPlaylistTest.kt:499`, `ShellDetailTest.kt:25`.
- 키 입력: 같은 폼 재선택과 다른 폼 교체, 계속 편집·폐기 양쪽 동작. Windows·Apple 모델 테스트와 Android 실제 대화상자를 검사한다.
  `WorkflowsModelTest.kt:50`, `TransferPrivacyTests.swift`, `MobileUxTest.kt:52`.
- 공통 코어: 손상 전사의 원격 복구 후 로컬 재사용, 복구 다운로드 도중 새 로컬 결과 보존, 제목 변경은 오디오 구독에서 제외하면서
  파트 추가·녹음 종료는 전달. `RecordingResultsTest.kt:23`, `RecordingRepositoryTest.kt:45`.
- Android: 실제 상세 UI에서 녹음 종료 후 재생 버튼이 나타나는지 확인한다. `MobileUxTest.kt:85`.

실제 ffmpeg 진단은 macOS의 `/opt/homebrew/bin/ffmpeg`와 가짜 스피커를 사용했다. 최초 점검의
`failed=false`가 수정 후 `failed=true`로 바뀌었다. 해당 임시 진단만 제거하고 테스트 파일의 기존 SHA-256을
확인했으며, 영구 회귀 테스트를 남긴 상태로 마지막 `make test`를 통과했다.

최신 증거: `/tmp/recly-five-fixes-test.log`, `/tmp/recly-five-fixes-core.log`,
`/tmp/recly-five-fixes-apple.log`, `/tmp/recly-five-fixes-android-ui.log`,
`/tmp/recly-five-fixes-ffmpeg.log`, `/tmp/recly-five-fixes-ffmpeg-result.xml`.

이번 수정 검증에서는 실제 iPhone·Android 기기, Windows WASAPI 장치·MSI 설치를 실행하지 않았다.
iOS UI의 큰 글씨 검증은 [1차 기록](mobile-ux-review.md)에 있으며 이번 수정 후 다시 실행한 것은 아니다.
별도 Android 테스트 에뮬레이터는 종료했다. 배포용 빌드 생성·스토어 업로드·커밋·푸시는 수행하지 않았다.

## 최초 점검 기록 — 아래의 실패 상태·행 번호는 수정 전 기준

### 발견 사항

### 1. [P1] Apple 상세 화면이 다른 녹음의 시작을 감지하지 못함

- **영향:** iPhone·macOS 공용 상세 모델. macOS는 상세 창을 연 채 메뉴바에서 새 녹음을 시작할 수 있어 우선 수정이 필요하다.
- **재현:** 완료된 녹음 A의 상세를 열고 결과 구독의 첫 응답을 기다린 뒤, 같은 저장소에 녹음 중인 B를 생성한다.
- **기대:** 상세의 재생을 차단하고 이미 재생 중이면 중지한다.
- **실제:** `activeRecordings=1 detailDeviceRecording=false`. Apple 네이티브 진단 테스트에서 실패했다.
- **원인:** `RecordingDetailModel.followResults()`가 전사 결과를 받을 때만 기기의 녹음 상태를 확인한다.
  `ReclyCore.observeResults(A)`는 A에 관련된 변경만 내보내므로 B의 시작·종료는 전달하지 않는다.
  뷰에도 녹음 시작에 따라 기존 재생을 중지하는 연결이 없다.
- **근거:** `apple/RecKit/Sources/RecKit/Jobs/RecordingDetail.swift:122`, `:531`,
  `core/src/commonMain/kotlin/recly/core/ReclyCore.kt:157`.
- **수정 방향:** 전사 구독과 별도로 기기 전체 녹음 상태를 구독하고, 준비·녹음 중에는 재생 중지와 시작 차단을 함께 적용한다.
  Android의 `RecordingDetailScreen.kt:101`, Windows의 `RecordingsWindow.kt:96`에는 별도의 중지 경로가 있다.
- **검증 한계:** 실제 스피커 소리가 새 녹음에 들어가는 실험은 하지 않았다. 시스템 오디오를 함께 녹음하는 macOS에서
  재생이 계속되면 녹음 내용에 섞일 수 있다는 영향은 코드에 근거한 위험 판단이다.

### 2. [P2] Windows의 실제 디코딩 오류가 정상 재생 종료로 처리됨

- **재현:** 손상된 임시 `.m4a`를 실제 `/opt/homebrew/bin/ffmpeg`에 전달하고, 앱의 `RecordingPlayer`로 읽는다.
  출력 장치만 가짜 스피커로 대체해 실제 소리는 재생하지 않았다.
- **기대:** 재생 실패 문구가 나타나고 재시도할 수 있어야 한다.
- **실제:** `decoderExit=183 playing=false failed=false`. 비정상 종료인데도 실패 상태가 꺼져 있었다.
- **원인:** PCM 스트림의 끝을 정상 종료로 취급하고, 디코더를 정리할 때 종료 코드를 검사하지 않는다.
  새로 추가했던 기존 오류 테스트는 프로세스 생성 함수가 예외를 던지는 경우만 검사했다.
- **근거:** `windows/app/src/main/kotlin/app/recly/windows/ui/RecordingPlayer.kt:884`, `:912`;
  기존 테스트 `windows/app/src/test/kotlin/app/recly/windows/ui/RecordingPlaylistTest.kt:483`.
- **수정 방향:** 자연 종료 시 종료 코드·대기 시간 초과를 검사한다. 사용자의 중지·탐색으로 종료한 경우는 오류에서 제외한다.
  여러 파트 중간의 실패와 재시도도 함께 검증해야 한다.

### 3. [P2] 다른 키 입력을 열 때 미저장 키가 확인 없이 사라짐

- **영향:** Apple 공용 모델과 Windows 모델에서 각각 재현. macOS·Windows는 다른 워크플로우의 누락 키 버튼을
  기존 입력 폼과 동시에 표시하므로 실제 UI에서 도달할 수 있다. iPhone도 목록의 누락 키 버튼이 같은 모델 함수를 호출한다.
- **재현:** `first_key` 입력 폼에 값을 입력한 뒤 `second_key`의 키 추가를 누른다.
- **기대:** 기존 입력을 보존한 채 폐기 여부를 확인한다.
- **실제:** Windows `formName=second_key valueLength=0 confirmation=null`,
  Apple `formName=second_key valueLength=0 confirmation=false`.
- **원인:** 닫기·워크플로우 전환에는 보호가 있지만 `openSecrets()`는 기존 폼을 바로 교체한다.
- **근거:** `windows/app/src/main/kotlin/app/recly/windows/ui/WorkflowsModel.kt:394`,
  `apple/RecKit/Sources/RecKit/Workflow/WorkflowsModel.swift:408`;
  실제 버튼은 Windows `WorkflowEditorWindow.kt:152`, Mac `WorkflowWindow.swift:162`.
- **수정 방향:** 키 폼 교체도 미저장 입력 확인을 거치게 하고, 동일한 키를 다시 선택하면 현재 입력을 유지한다.

### 4. [P2] 상세를 열어 둔 녹음이 종료돼도 재생 영역이 활성화되지 않음

- **영향:** Apple에서 실행 재현. Android·Windows에도 동일하게 `writing`과 오디오 목록을 초기 읽기에만 설정하는 경로가 있다.
- **재현:** 녹음 중인 항목의 상세를 열고 결과 구독을 시작한 뒤, 저장소에서 그 녹음을 종료한다.
- **기대:** 녹음 종료를 반영해 오디오 목록을 갱신하고 재생 영역을 표시한다.
- **실제:** `finalized availability=notRequested writing=true`. 결과 상태는 바뀌었지만 상세는 계속 녹음 중으로 간주했다.
- **원인:** 결과 구독은 전사와 전사 상태만 갱신한다. `writing`과 오디오 목록은 남아 있어 상세를 닫고 다시 열어야 갱신된다.
- **근거:** Apple `Jobs/RecordingDetail.swift:122`, `:335`;
  Android `JobsViewModel.kt:350`, `:358`; Windows `ShellModel.kt:986`.
- **수정 방향:** 녹음 중 → 종료 전환과 오디오 파트 변경을 별도로 관찰해 필요한 메타데이터·오디오만 갱신한다.
  이미 완성된 녹음을 재생하는 동안 전사만 도착하면 재생 위치는 유지해야 한다.

### 5. [P2] 손상된 전사 파일은 ‘다시 시도’를 눌러도 복구되지 않음

- **영향:** 전사 상세가 있는 Android·iPhone·macOS·Windows 공통 코어.
- **재현:** 전사를 정상 완료해 Drive에 유효한 결과가 있는 상태에서 로컬 JSON만 손상시킨다. 읽기 실패 후 다시 읽는다.
- **기대:** 사용자가 선택할 수 있는 복구 경로가 있어야 한다.
- **실제:** `retryAvailability=UNAVAILABLE additionalDownloads=0`. 같은 손상 파일만 다시 읽는다.
- **원인:** 로컬 파일이 존재하면 내용 검증 전에 원격 읽기를 제외한다. 상세의 재시도는 동일한 읽기 함수를 다시 호출한다.
  기존 테스트는 테스트 코드가 파일을 직접 정상 JSON으로 교체한 뒤 재시도해서 이 문제를 잡지 못했다.
- **근거:** `core/src/commonMain/kotlin/recly/core/transcribe/RecordingResults.kt:53`, `:69`;
  기존 테스트 `core/src/jvmTest/kotlin/recly/core/transcribe/RecordingResultsTest.kt:94`.
- **수정 방향:** 읽기 실패를 네트워크 오류와 로컬 파일 손상으로 구분한다. 손상 파일에는 원격본 복구 또는 재전사로
  이어지는 동작을 제공한다. 원격본으로 복구할 때는 더 최신인 유효한 로컬 결과를 덮어쓰지 않는 기존 규칙을 유지한다.

## 최초 점검 당시 플랫폼 간 일관성

| 항목 | 확인 결과 |
|---|---|
| 모바일 하단 재생 / 데스크톱 상단 재생 | 플랫폼별 배치 분기를 코드로 확인. 데스크톱을 모바일 배치로 변경하지 않았음 |
| 전사 완료 시 본문 자동 갱신 | 공통 코어와 Apple 기존 회귀 테스트 통과. 녹음 수명주기 갱신은 1·4번 누락 |
| 편집 취소·워크플로우 전환 보호 | 기존 테스트 통과. 키 폼 교체는 3번 누락 |
| 재생 오류 안내 | Android·Apple의 기존 오류 테스트 통과. Windows의 실제 프로세스 오류는 2번 누락 |
| 로딩·빈 전사·실패·전사 없음 구분 | 상태 분기와 영어·한국어 리소스 확인. 로컬 손상 복구는 5번 누락 |
| 키보드·큰 글씨 | 직전 Android 1.6배 및 iPhone 기본·큰 글씨 UI 테스트의 통과 로그 확인. 이번 점검에서 UI 자동화는 다시 실행하지 않았음 |
| 워치 | 폰의 편집 화면·하단 조작을 추가하지 않았음. 직전 watchOS 빌드 통과 기록 확인 |

## 최초 점검 실행 명령과 실제 결과

| 실행 | 실제 결과 |
|---|---|
| 최초 `make test` | `BUILD SUCCESSFUL in 18s`; 123 actionable tasks: 1 executed, 122 up-to-date |
| Windows 진단이 포함된 `make test` | `363 tests completed, 2 failed`; `BUILD FAILED in 23s` |
| 코어 진단이 포함된 `make test` | `536 tests completed, 1 failed`; `BUILD FAILED in 12s` |
| Apple 진단 3건이 포함된 `make mac-test` | 447 tests, 1 skipped, 3 failures; `TEST FAILED` |
| 진단 제거 후 `make test` | `BUILD SUCCESSFUL in 3s`; 123 actionable tasks: 1 executed, 4 from cache, 118 up-to-date |
| 최종 JVM XML 집계 | core 535 + Android 470 + Windows 361 = 1,366개; 실패·오류·건너뜀 0 |
| 진단 제거 후 `make mac-test` | 444 tests, 1 skipped, 0 failures; `TEST SUCCEEDED` |

기존 테스트 통과와 추가 진단 실패는 서로 다른 검사 결과다. 추가 진단은 기존 테스트가 다루지 않던 경로의
정상 동작을 기대하도록 작성했으며, 위 실제 값 때문에 실패했다. 최초 점검 종료 시에는 제품 코드를 수정하지 않아
5개 문제가 남아 있었다. 임시로 수정한 테스트 파일 5개는 모두 점검 전 SHA-256과 일치하도록 복원했다.
그 점검에서는 코어 제품 소스와 XCFramework를 변경하지 않았다. 이후 수정과 새 XCFramework를 사용한 검증 결과는
이 문서 상단의 ‘수정 결과’·‘수정 후 검증’에 있다.

증거는 `/tmp/recly-ux-detailed-review/`에 보관했다.

- `probes.log`, `core-probe.log`, `apple-probes-expanded.log`: 진단 실행 로그.
- `RecordingPlaylistTest-probe.xml`, `WorkflowsModelTest-probe.xml`, `RecordingResultsTest-probe.xml`: 실제 상태와 실패 이유.
- `*.probe.txt`, `manifest.json`: 실행한 진단 코드와 복원 확인용 해시.
- `restored-tests.log`, `restored-apple-tests.log`: 복원 후 기존 테스트 결과.

실기기 iPhone·Android, 폴더블·가로 모드, VoiceOver·TalkBack, Windows 실제 WASAPI 장치와 MSI 설치는
이번 점검에서 실행하지 않았다. 기존 Clova 녹음의 `segments=0` 원인도 이번 진단으로 규명한 것은 아니다.

최초 점검 후 1번 녹음·재생 충돌 방지, 2·3번 오류 안내와 입력 보존, 4·5번 갱신·복구 경로를 모두 수정했다.
