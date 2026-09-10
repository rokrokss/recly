# UI/UX 개선 적용·검증 — 2026-09-10

## 적용 결과

[공식 기준 대조 감사](ux-standards-audit.md)의 A1·A2·B1~B4와 코드로 처리할 개선 사항을 반영했다.
모바일 하단 재생과 데스크톱 상단 재생·분할 창을 유지하면서, 읽기·검색·복사·탐색 동작을 맞췄다.
아래의 통과는 명시한 자동 검사·빌드 범위에 대한 결과이며, 모든 실기기의 사용성 검증을 완료했다는 뜻은 아니다.

| 항목 | 적용한 동작 | 주요 코드 근거 |
|---|---|---|
| A1 큰 글씨의 헤더 | 좁은 폭에서 제목과 동작을 별도 행으로 배치. 넓고 낮은 화면에서는 짧은 가로 헤더 사용 | `android/app/src/main/kotlin/app/recly/android/ui/component/Table.kt:38` |
| A2 탭·상태 노드 | 탭 이름 두 줄 허용, 글자 측정 폭을 실제 탭 폭에 맞춤. 큰 글씨의 상태 노드는 세로 배치 | `android/app/src/main/kotlin/app/recly/android/ui/component/NavBar.kt:38`, `component/Nodes.kt:158` |
| B1 입력칸 경계 | `inputBorder`를 장식용 grid와 분리. 기본·포커스 경계를 구분하고 밝음·어두움·고대비 조합 검사 | Android `ui/theme/Colors.kt:34`, Apple `Design/SectionTable.swift:298`, Windows `ui/component/Fields.kt:91` |
| B2 키보드 파형 | Android의 포커스 진입·좌우 5초 이동·포커스 이탈, Android·Windows의 강조 테두리 | Android `ui/RecordingDetailScreen.kt:267`, Windows `ui/RecordingsWindow.kt:424` |
| B3 iPhone 가로 | 좌우 가로 방향 지원. 짧은 높이에서도 녹음 동작에 스크롤로 접근 | `apple/RecPhone/RecPhone.xcodeproj/project.pbxproj`, `apple/RecPhone/RecPhone/RecordingView.swift:47` |
| B4 전사 활용 | 네 플랫폼에 본문 선택, 전체 복사, 본문/화자 검색, 발화 시각 이동 추가 | Android·Windows `ui/TranscriptReader.kt:32`, `apple/RecKit/Sources/RecKit/Jobs/TranscriptReader.swift:11` |
| 긴 전사 | 결과별 발화 묶음 캐시와 LazyColumn/LazyVStack. 반복 문자열 덧붙이기를 StringBuilder로 교체 | `core/src/commonMain/kotlin/recly/core/transcribe/TranscriptDocument.kt:11` |
| Android 고대비 | 시스템 색 대비와 고대비 텍스트 변경을 관찰. 선 굵기·보조색·배경 도형 반영 | `android/app/src/main/kotlin/app/recly/android/ui/theme/Theme.kt:245` |
| 워치 | Apple Watch 주 화면 스크롤·상태 줄바꿈, Wear의 긴 이름·전송 상태 줄바꿈 | `apple/RecWatch/RecWatch/RecordingView.swift:17`, `android/wear/src/main/kotlin/app/recly/wear/ui/MainScreen.kt` |
| 전사 첫 설정 | 워크플로우 목록에서 선택적으로 펼치는 Drive·전사 단계·키·워크플로우 선택 안내 | 각 셸 `TranscriptionSetupHelp`, Apple 공통 `Workflow/TranscriptionSetupHelp.swift` |
| 긴 Apple 동작 문구 | FlowLayout이 화면 폭을 넘는 컨트롤에 제한 폭을 제안하고, 버튼을 최대 세 줄로 표시 | `apple/RecKit/Sources/RecKit/Design/FlowLayout.swift:51`, `Design/Buttons.swift:48` |

전사 복사는 검색으로 걸러진 일부가 아니라 타임스탬프를 포함한 전체 원문이다. 검색·복사는 오디오가 없어도 동작한다.
시각 이동은 녹음 중·오디오 확인/다운로드 중에는 비활성이며, 내려받은 오디오 범위를 벗어난 발화도 비활성이다.
제목·잡 상태가 갱신돼도 같은 전사 문서를 다시 만들거나 읽기 위치를 초기화하지 않는다.
발화 묶음은 60초 또는 1,200자 기준으로 다음 세그먼트부터 나눈다. 제공자가 반환한 단일 세그먼트 자체를
잘라서 임의의 타임스탬프를 만들지는 않는다. 한 세그먼트가 매우 큰 경우의 별도 성능 보장은 없다.

Android 가로 640×360dp·글씨 200% 검사에서는 저장·재생 영역이 밀려나는 추가 문제를 재현해 고쳤다.
키보드 입력 중 탭 바와 중복 헤더를 접고, 편집 영역은 저장 행을 제외한 공간을 나눠 쓴다.
낮은 화면에서는 탭 아이콘의 별도 행을 생략하며 파형과 재생 시계를 나란히 둔다. 전사 검색 입력 중에는
상세 헤더·재생 영역을 접고 키보드를 닫으면 복원한다. 검색 도구와 본문도 한 스크롤 영역에 있어
도구가 본문 높이를 모두 차지하지 않는다. 새로운 강제 온보딩이나 추가 동의 단계는 넣지 않았다.
최종 iPhone 가로 스크린샷에서 전사 설정 안내의 마지막 줄이 잘리는 것도 발견했다. 펼침 내용을
일반 세로 레이아웃으로 바꾸고 본문의 줄 제한을 해제해 전체 안내가 보이도록 수정했다.
펼침/접힘 상태도 한·영 접근성 값으로 제공한다.

## 실행 명령과 실제 결과

| 명령 / 조건 | 결과 |
|---|---|
| `make core` | `BUILD SUCCESSFUL in 7m 21s`; XCFramework 생성·RecKit 배치 완료 |
| `make test` | 최종 `BUILD SUCCESSFUL in 21s`, `123 actionable tasks: 6 executed, 117 up-to-date`; core 540 + Android 470 + Windows 365 = **1,375개, 실패 0** |
| `make mac-test mac watch WATCH_SIM='Apple Watch SE 3 (40mm)'` | `TEST SUCCEEDED`, **450개 중 기존 1개 skipped, 실패 0**; macOS·40mm Watch 모두 `BUILD SUCCEEDED` |
| `make ios-ui-test IOS_SIM='Recly UX Review'` | 현재 iOS/동봉 Watch 빌드 후 **3개 통과**, `TEST SUCCEEDED` |
| `ANDROID_SERIAL=emulator-5554 make android-ui-test` — 320dp·200%·어두운 테마 | **13개 통과**, `BUILD SUCCESSFUL in 42s` |
| 같은 UI 검사 — 가로 640×360dp·200% | 당시 구성된 **12개 통과**, `BUILD SUCCESSFUL in 31s`; 이후 추가한 고대비 시스템 콜백 검사는 위 13개 실행에 포함 |
| `ANDROID_SERIAL=emulator-5554 make android-ui-test ANDROID_TEST_CLASS=app.recly.android.ui.UiStandardsTest` | 320·360·412dp × 100·160·200%의 **9개 조합에서 각 3개, 총 27회 통과**. 각 조합에서 영어·한국어 제목과 모든 탭 이름 검사 |
| `git diff --check` | 출력 없음, 종료 코드 0 |

Android는 전용 Android 16 AVD `recly_ux_review`, iPhone은 전용 iPhone 17 Pro / iOS 26.5 시뮬레이터를 사용했다.
실물 사용자 기기나 계정으로 새 녹음을 만들거나 유료 전사를 요청하지 않았다.

- Android 글자 검사는 실제 우측 글자 경계·마지막 가시 문자·말줄임을 확인한다. 가운데 정렬 텍스트는 탭의 실제 폭을
  사용하며, 같은 이름의 대시보드 노드와 탭을 구분한다. `hasVisualOverflow` 하나만으로 판정하지 않는다.
- Android 전사 검사는 2시간·120개 발화 샘플에서 뒤쪽 검색 결과 표시, 시각 이동, 검색 중 전체 복사를 확인한다.
  오디오가 없을 때의 탐색 비활성, 검색 결과 없음, 파형의 키보드 5초 이동과 다음 버튼으로의 Tab 이동도 검사한다.
- 고대비 검사는 이미 마운트된 테마를 유지하면서 고대비 텍스트와 색 대비를 각각 켜고 꺼 콜백 반영을 확인한다.
  에뮬레이터에서만 실행하며, 변경한 설정은 `finally`에서 복원한다. 제품은 공개 API를 쓰고, 테스트의 시스템 설정명은
  [Android 16 AOSP Settings](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/provider/Settings.java)를 확인했다.
- iPhone UI 검사는 키보드 위 저장·초안 유지, 한국어 최대 접근성 글씨에서 녹음 동작 접근, 가로 화면에서 녹음·워크플로우
  안내·새 워크플로우 동작을 확인한다. 실제 화면 캡처도 보관했다.
- Apple 단위 검사에는 제한 폭의 NSHostingView에서 긴 버튼이 높이를 늘려 줄바꿈하는 실제 SwiftUI 레이아웃 검사를 추가했다.
- 코어의 7,200개 세그먼트 묶음 검사는 원문 보존·안정된 검색 대상·다음 단락의 시각을 확인한다. 이 JVM 테스트의 기록은
  0.004초였지만, 이 수치를 앱의 첫 표시 시간·스크롤 프레임률·실기기 메모리 사용량으로 해석하지 않는다.

로그·XML·화면 캡처는 `build/reports/ux-remediation-2026-09-10/`에 있다.
`matrix.json`, `android-320-dark-200-final.xml`, `android-landscape-200.xml`, `jvm-counts.json`,
`apple.log`, `ios-ui.log`, `ios-complete/manifest.json`이 최종 증거다. 이전 실패·중간 로그를 최종 통과 수에 합산하지 않았다.

## 검증의 경계

- Windows 코드 컴파일·365개 JVM 검사는 macOS 호스트에서 통과했다. 실제 Windows의 Narrator·배율·키보드 입력,
  WASAPI 캡처와 MSI 패키징 실행은 수행하지 않았다.
- macOS는 앱 빌드·공통 모델/레이아웃 테스트를 실행했다. 실제 전체 창에서 전사 선택·복사·VoiceOver를 수동 전수 검증한 것은 아니다.
- Apple Watch는 40mm 대상 빌드가 통과했다. 실물 Watch/Wear의 크라운·베젤·화면 읽기 도구와 최소 화면의 사용자 조작은 별도 확인 대상이다.
- 모바일 전수 화면/모든 언어·테마의 조합, 실제 Bluetooth·오디오 경로, 릴리스 빌드의 30분·2시간 전사 성능,
  목표 사용자 관찰은 남아 있다. 위 행렬은 제목·탭 검사 범위이며 앱 전체 화면을 모두 통과했다는 의미가 아니다.

코드 변경과 명시한 자동 검증을 완료했으며, 커밋·푸시·스토어용 새 배포 빌드는 이번 작업에 포함하지 않았다.

전용 Android AVD의 화면 크기·글씨(최초 값 1.6)·야간 모드를 복원하고 종료했다. 전용 iPhone 시뮬레이터도 종료 상태를 확인했다.
