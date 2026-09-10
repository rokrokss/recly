# 모바일·데스크톱 UX 개선 — 2026-09-10

후속 [상세 재점검](ux-detailed-review.md)에서 확인한 5개 문제는 모두 수정하고 회귀 테스트를 통과했다.
최신 수정·검증 결과는 해당 문서 상단에 있다. 아래는 1차 구현·검증 기록이다.

Android 실기기에서 확인한 문제와 모바일 코드 점검의 8개 항목에 대한 1차 개선을 구현했다. macOS·Windows에는 상태 안내와
보호 동작을 공통으로 적용하고 기존 창·분할 화면·상단 재생 배치를 유지했다. iPhone·Android의 하단 조작을
데스크톱으로 옮기지는 않았다. 워치에는 폰의 편집 화면이나 하단 내비게이션을 추가하지 않았다.

## 수정 범위

| 점검 항목 | 반영한 동작 | 코드 근거 |
|---|---|---|
| 다른 탭의 뒤로가기가 숨겨진 편집기를 닫음 | 현재 탭에만 뒤로가기 적용. 보조 탭에서 뒤로가면 녹음 탭으로 이동하며 초안 보존 | `android/app/src/main/kotlin/app/recly/android/ui/MainActivity.kt:209` |
| 미저장 입력 손실 | 변경 있는 편집 취소·다른 워크플로우 선택·새 편집 전 확인. 키 입력도 보호. 변경 없는 편집은 바로 닫힘 | Android `WorkflowsViewModel.kt:176`, Apple `WorkflowsModel.swift:222`, Windows `WorkflowsModel.kt:219` |
| 상세의 전사 자동 갱신 누락 | 잡·단계·녹음 변경을 관찰해 본문만 갱신. 전사 단계가 끝나면 후속 웹훅 완료 전에도 표시 | `core/src/commonMain/kotlin/recly/core/ReclyCore.kt:147`, `:157`; 각 셸의 상세 모델 |
| 키보드가 저장·취소를 밀어냄 | 모바일 하단 저장·취소, IME·안전 영역 반영, 입력 중 그래프 접기. 오류 영역 스크롤 및 해당 입력 영역으로 이동 | Android `WorkflowEditorScreen.kt`, `MainActivity.kt`, Manifest; iPhone `WorkflowsView.swift` |
| API 키 즉시 삭제 | 키 이름·사용 워크플로우 확인 후 삭제. 취소하면 저장소 유지. 키 값은 확인 화면에 표시하지 않음 | 각 셸의 `WorkflowProtection`/`WorkflowProtectionDialogs`, 워크플로우 모델 |
| 재생 실패가 드러나지 않음 | 실제 재생 상태·오류 반영, 재생 버튼으로 재시도. Apple은 큐에서 제거된 실패 항목도 추적 | Android·Apple·Windows `RecordingPlayer`; Apple `RecordingPlayer.swift:103` |
| 내부 ID가 제목을 밀어냄 | 제목은 최대 3줄, ID는 아래의 복사 가능한 보조 정보 | Android·Windows `component/Table.kt`; Apple `Design/SectionTable.swift` |
| iOS 상세의 동작 차이 | Android·iPhone 하단 재생, Mac·Windows 상단 재생 유지. 대기·전사 없음·실패·읽기 실패·빈 결과 안내 공통화 | Apple `Jobs/RecordingDetail.swift:122`, `:334`; 각 셸의 상세 화면 |
| 목록 로딩·빈 상태 | 첫 읽기 문구 표시. 기존 목록 유지. 실제 빈 목록에서 녹음 화면·녹음 시작·워크플로우 추가로 연결 | 각 셸의 목록 화면과 목록 모델 |

Android 워크플로우 모델·화면은 `android/app/src/main/kotlin/app/recly/android/ui/`, Windows는
`windows/app/src/main/kotlin/app/recly/windows/ui/`, Apple 공용 모델은
`apple/RecKit/Sources/RecKit/Workflow/`에 있다.

추가로 Android의 공통 터치 최소 크기를 48dp로 맞추고, 테마 선택·키 저장/생성·편집 하단 버튼을 줄바꿈하도록 했다.
작은 높이·큰 글씨의 녹음 화면에는 스크롤을 제공한다. 밝은 배경에서 상태 표시줄 글자가 사라지지 않도록
시스템 표시줄 아이콘도 앱의 테마를 따른다. 영어·한국어 문자열을 각 플랫폼 리소스에 반영했다.

## 검증

- 공통 코어: 상세를 연 상태에서 전사 파일을 기록하고 단계만 성공으로 바꾼다. 잡이 여전히 `RUNNING`인 동안
  본문이 도착하는지 검증한다(`ReclyCoreTest`). 빈 결과·손상 파일·재조회, 전사 없는 작업, 인증 실패,
  후속 단계 진행 중의 전사 실패, 다른 기기의 전사 대기, 읽을 수 없는 작업 스냅샷도 구분한다.
- Android: 별도 Android 16 에뮬레이터 `recly_ux_review`에서 기본 글씨와 1.6배 글씨로 실행했다. 최종 4개 테스트는
  키보드 위 저장 버튼 위치·취소 후 계속 편집, 다른 탭 뒤로가기 후 초안 보존, 오류 필드 이동·포커스,
  손상 오디오 오류·재시도를 검사한다(`MobileUxTest`). 기존 설치본과 서명이 달라 새 테스트 기기를 만들었다.
- Apple: RecKit 444개 테스트 중 실패 0, 기존 1개 건너뜀. 초안·키 삭제 확인과 실제 손상 파일의 AVQueuePlayer
  오류 처리, 전사 도착 시 본문 갱신과 오디오 상태 보존을 포함한다. iPhone 17 Pro / iOS 26.5 별도 시뮬레이터 `Recly UX Review`에서 기본 글씨와
  `accessibility-extra-large`로 키보드·초안 보존을 실행했다(`MobileUxTests`). 시뮬레이터 UI 테스트는 키체인 권한을
  포함하는 로컬 서명이 필요하므로 `make ios-ui-test`가 이를 적용한다.
- Windows: 워크플로우 교체·취소·키 입력 보존·사용 키 삭제 확인과 디코더 실패/재시도를 JVM에서 검증했다.
  실제 WASAPI 녹음과 MSI 설치는 macOS에서 검증할 수 없다.

| 최종 검증 명령 | 실제 결과 |
|---|---|
| `make test` | `BUILD SUCCESSFUL in 19s`; 123 actionable tasks: 9 executed, 114 up-to-date |
| JVM XML 결과 집계 | core 535 + Android 470 + Windows 361 = 1,366개, 실패·오류·건너뜀 0 |
| `make core` | `BUILD SUCCESSFUL in 7m 20s`; `xcframework successfully written out` |
| `make mac-test mac watch` | RecKit 444개, 기존 1개 건너뜀, 실패 0; `TEST SUCCEEDED` 및 Mac·watchOS `BUILD SUCCEEDED` |
| `make ios` | 마지막 전사 구독 분리까지 포함해 `BUILD SUCCEEDED` |
| `make android-ui-test` | 최종 4개 모두 통과; `BUILD SUCCESSFUL in 19s` (1.6배 글씨) |
| `make ios-ui-test IOS_SIM='Recly UX Review'` | 기본 글씨 `TEST SUCCEEDED`, 큰 글씨도 `TEST SUCCEEDED` |
| `git diff --check` | 출력 없음, 종료 코드 0 |

Apple 실행 검증 중 발견한 실패 항목 추적 누락과 편집 모델 종료 후 데이터 구독이 남는 문제도 수정했다.
전사 구독은 초기 결과 읽기 이후 오디오 다운로드·파형 생성과 독립적으로 시작한다(`RecordingDetail.swift:368`).
화면 배치 스크린샷과 테스트 로그는 아래 임시 증거 디렉터리에 보관했다. 최종 로그는
`/tmp/recly-ux-test-final.log`, `/tmp/recly-ux-core-final.log`, `/tmp/recly-ux-apple-final.log`,
`/tmp/recly-ux-android-latest.log`, `/tmp/recly-ux-ios-build-final.log`, `/tmp/recly-ux-ios-final.log`,
`/tmp/recly-ux-ios-large.log`이다.

## 최초 관찰과 남는 실기기 확인

최초 Android 관찰은 Galaxy SM-F966N의 Play 설치본 0.1.0 (6), 한국어, 1080×2520, 420dpi, 글자 배율 1.0에서 했다.
키보드 표시 전후 저장 버튼 좌표가 `[948,185][1012,235]`에서 `[0,0][0,0]`으로 바뀌었고, 설정 탭에서 뒤로간 뒤
워크플로우로 돌아왔을 때 편집 화면이 사라졌다. 수정 후 실행 검증은 별도 가상 기기에서 수행했다.

기존 Clova 녹음 한 건이 `segments=0`으로 완료된 사실과 상세의 자동 갱신 누락은 별개의 문제다. 이번 변경은
빈 결과를 명확히 알리며, 그 녹음이 왜 빈 결과였는지는 단정하지 않는다. 실제 계정의 최신 빌드에서 전사·재생,
iPhone 실기기, 폴더블 내부 화면·가로 모드, TalkBack·VoiceOver, Windows 실제 오디오 장치 확인은 배포 전 QA에 남는다.

실기기 로그·화면은 `/tmp/recly-android-transcribe/`, 이번 가상 기기 화면과 테스트 자료는 `/tmp/recly-ux-review/`에
보관했다. 사용자의 실기기 앱 데이터나 키를 지우지 않았으며, 테스트용 자료는 저장소에 추가하지 않았다. 버전 번호는 변경하지 않았다.
