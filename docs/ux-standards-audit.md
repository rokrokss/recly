# UI/UX 공식 기준 대조 점검 — 2026-09-10

> 후속 작업: 아래는 수정 전 감사 기록이다. 발견 사항의 적용 내용과 최신 실행 결과는
> [UI/UX 개선 적용·검증](ux-remediation.md)에 기록했다.

## 판정

현재 상태를 **UI/UX 최적화 완료로 판정하기에는 부족하다.** 앞서 발견한 기능 결함 5건은 수정됐지만,
이번에는 실제 Android 화면에서 가독성 결함 2건을 재현했다. 입력칸 식별·키보드 조작에서도 코드상 보완점이 있다.
기능 테스트 통과, 접근성 지원 코드의 존재, 실제 사용성은 각각 다른 증거다. 임의의 종합 점수는 부여하지 않는다.

이번 작업은 웹 조사·코드 대조·진단·보고다. 제품 코드를 수정하거나 스토어 배포를 진행하지 않았다.
아래의 P2는 특정 화면 조건·입력 방식에서 사용성을 해치는 문제, P3는 추가 사용·성능 측정이 필요한 개선 후보다.
확정 결함과 설계상 제약, 아직 측정하지 않은 위험을 구분한다.

## 대조한 공식 기준

- **터치와 글씨 확대:** Android는 상호작용 영역 48dp와 최대 글씨 200%에서의 UI 검증을 안내한다.
  Apple의 현재 표는 iOS·watchOS 기본 컨트롤 44pt / 최소 28pt, macOS 기본 28pt / 최소 20pt를 구분한다.
  따라서 작은 시각적 아이콘을 보고 곧바로 ‘44pt 미만 위반’이라고 판정하지 않았다.
  [Android API defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults),
  [Android 14 글씨 확대](https://developer.android.com/about/versions/14/features),
  [Apple Accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility).
- **텍스트·컨트롤 대비:** 일반 텍스트 4.5:1, 필수 컨트롤 식별 정보 3:1을 측정 기준으로 삼았다.
  장식용 구분선이나 텍스트로 충분히 식별되는 버튼의 테두리를 일괄 실패 처리하지 않았다. 비활성 컨트롤도 별도로 봤다.
  [W3C 텍스트 대비](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html),
  [W3C 비텍스트 대비](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html).
- **입력 방식:** 터치·드래그 외에 키보드와 보조기술로 주요 기능에 도달해야 하며, 키보드 포커스는 보여야 한다.
  [Microsoft Keyboard interactions](https://learn.microsoft.com/en-us/windows/apps/develop/input/keyboard-interactions),
  [Android Semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics),
  [W3C Focus Visible](https://www.w3.org/WAI/WCAG22/Understanding/focus-visible.html).
- **배치:** 크기·방향·글씨·언어 변경에 대응하되 제품의 일관성을 유지한다. Apple은 양방향 지원을 권장하면서
  필요한 경우 단일 방향도 허용하므로, 세로 고정을 곧바로 심사 위반으로 단정하지 않았다.
  [Apple Layout](https://developer.apple.com/design/human-interface-guidelines/layout),
  [Android 화면 크기 대응](https://developer.android.com/develop/ui/compose/layouts/adaptive/support-different-display-sizes).
- **긴 내용과 첫 사용:** 길이를 예측하기 어려운 목록은 필요한 부분만 구성하고, 안내는 현재 작업 가까이에서 짧게 제공하는 방향을 참고했다.
  [Android Lazy lists](https://developer.android.com/develop/ui/compose/lists),
  [Apple Onboarding](https://developer.apple.com/design/human-interface-guidelines/onboarding).

WCAG는 웹 표준이므로 여기서는 네이티브 앱의 측정 가능한 설계 기준으로 사용했다. 이 보고서는 공식 접근성 인증이나
법적 적합성 판정이 아니다. 원문은 점검 당일 조회했으며, 구현의 프레임워크와 플랫폼 차이를 함께 고려했다.

## 실행으로 재현한 결함

### A1. [P2] 큰 글씨에서 워크플로우 화면 제목이 사라짐

- **조건:** 별도 Android 16 AVD, 945×1680px / 420dpi = 360×640dp, 글씨 200%, 영어.
- **동작:** Workflows 탭을 연다. 우측 `Manage secrets`·`+ New` 버튼이 헤더 가로 공간을 모두 차지한다.
- **실제:** 제목의 레이아웃 폭이 **0px**다. 글자 확대 시 제목을 더 쉽게 읽어야 하는데 화면 이름 자체가 없어졌다.
- **코드:** `android/app/src/main/kotlin/app/recly/android/ui/WorkflowsScreen.kt:89`의 고정 `Row` 동작 영역과
  `component/Table.kt:48`의 남은 폭만 받는 제목 열이 충돌한다. 동일 헤더를 쓰는 상세의 이름 변경·닫기 조합도 점검 대상이다.
- **수정:** 좁은 폭·큰 글씨에서는 제목과 동작 행을 분리하거나 보조 동작을 메뉴로 묶는다. 제목에 최소 가용 폭을 확보한다.
  글씨를 강제로 작게 줄이는 방식은 피한다.
- **수용 기준:** 320·360·412dp, 영어·한국어, 100·160·200%에서 제목과 주요 동작 전체가 읽히고 눌린다.

### A2. [P2] 큰 글씨에서 하단 탭 이름이 잘림

- **동일 조건의 실제:** `Workflows` 9글자 중 가시 텍스트 끝은 **7**이었다. 할당 폭 236px에서 다음 줄을
  표시하지 않아 끝부분이 사라진다. 접근성 트리에 원문이 남아 있다는 사실은 시각적 잘림을 해결하지 않는다.
- **코드:** `android/app/src/main/kotlin/app/recly/android/ui/component/NavBar.kt:49`의 4등분 폭과
  `:56`의 `maxLines = 1` 조합이다. 메인 화면의 `Workflow` 노드 제목도 같은 조건에서 `Work…`로 줄어든다.
- **수정:** 큰 글씨에 대응하는 탭 배치·행 높이·줄바꿈 또는 짧고 명확한 현지화 명칭을 결정한다.
  선택 상태·터치 크기·보조기술 이름은 유지한다. 표준 내비게이션 컴포넌트 적용도 비교한다.
- **수용 기준:** 모든 탭 이름이 완전히 식별되고, 가로 배치·키보드 표시 상태에서도 주요 동작을 가리지 않는다.

스크린샷과 최종 진단은 `build/reports/ux-standards-2026-09-10/`에 있다.
`record-360-200.png`, `workflows-360-200.png`, `final-result.xml`, `final-values.txt`를 함께 보면 재현을 확인할 수 있다.

## 코드와 수치로 확인한 보완점

### B1. [P2] 빈 입력칸의 경계 대비가 부족함

- **확인:** 일반 모드의 `grid`는 밝은 surface에서 **1.25:1**, 어두운 surface에서 **1.17:1**이다.
  빈 입력칸은 내부 텍스트가 없어 실제 입력 영역을 경계로 알아봐야 하는데, 경계와 주변 배경 차이가 작다.
- **근거:** Apple `apple/RecKit/Sources/RecKit/Design/SectionTable.swift:308`의 빈 `TextField`와 `:321`의 grid 테두리,
  Windows `windows/app/src/main/kotlin/app/recly/windows/ui/component/Fields.kt:89`의 grid 테두리,
  Android `android/app/src/main/kotlin/app/recly/android/ui/theme/Theme.kt`의 `outline = grid` 매핑.
- **수정:** 장식용 grid와 입력칸 경계용 토큰을 분리한다. 빈 칸의 배경·테두리 중 식별에 필요한 부분은 3:1 이상을 확보한다.
  포커스 상태와 오류 상태도 따로 확인한다. 모든 구분선을 진하게 바꾸는 작업은 필요하지 않다.
- **한계:** 팔레트와 그 사용 경로를 계산·대조한 결과다. macOS·Windows 실화면의 모든 합성 배경을 픽셀 측정한 것은 아니다.

### B2. [P2] 파형의 키보드 접근이 플랫폼마다 불완전함

- **Android:** `RecordingDetailScreen.kt:277`에 TalkBack용 `setProgress`는 있지만, 해당 Canvas에
  키보드 포커스·좌우 키 처리 경로가 없다. 보조기술의 값 변경과 일반 키보드 탐색은 별개다.
- **Windows:** `RecordingsWindow.kt:434`에 좌우 키 처리와 `:443`에 `focusable()`은 있다. 그러나 이 Canvas에는
  포커스 상태를 읽어 표시하거나 포커스 indication을 연결하는 경로가 없어 위치를 눈으로 확인하기 어렵다.
- **Apple:** `RecordingDetail.swift:465`에 조절 접근성 동작, `:478`에 포커스 및 인접 키 처리 경로가 있어 비교 기준으로 쓸 수 있다.
- **수정:** Android에 키보드 접근·조절을 추가하고 Windows 파형에 명확한 포커스 표시를 추가한다.
  전체 버튼에 키보드 지원이 없다고 일반화하지 않는다. 기본 clickable의 상태 표시도 별도로 확인해야 한다.
- **수용 기준:** Tab으로 파형 진입 → 현재 포커스 확인 → 좌우 키로 이동 → 재생/일시정지 → 다른 컨트롤로 이탈.
  Windows 실제 키보드·Narrator, Android 외장 키보드·TalkBack으로 각각 끝까지 검증한다.

### B3. [P2, 설계 제약] iPhone은 세로 방향만 지원함

- `apple/RecPhone/RecPhone.xcodeproj/project.pbxproj:659`, `:688`에 세로 방향만 지정돼 있고, 기기군도 iPhone으로 한정돼 있다.
- 긴 전사 읽기, 가로 거치, 외장 키보드 사용에서는 제약이다. 사용자 대상에 필요한지 결정한 뒤 양방향 지원을 검토한다.
  iPad 화면을 검사하지 않았다는 이유로 현재 iPhone 전용 제품을 실패 처리하지는 않는다.
- 현재 대시보드 스크롤 분기는 `RecordingView.swift:49`의 접근성 글씨 크기에만 의존한다. 방향 설정만 풀면 끝나는 변경이 아니며,
  작은 높이의 녹음·상세·편집·동의 화면을 함께 검증해야 한다.

### B4. [P2, 제품 개선] 전사 결과를 활용하는 동작이 부족함

- Android `RecordingDetailScreen.kt:479`, Apple `RecordingDetail.swift:637`, Windows `RecordingsWindow.kt:526`은
  발화 시각·화자·본문을 일반 텍스트로 표시한다. 해당 상세 및 상위 화면에서 본문 선택/복사, 본문 검색,
  시각을 눌러 해당 구간 재생으로 이동하는 동작을 찾지 못했다.
- Drive 원본을 외부에서 여는 경로는 있지만, 앱 안에서 방금 확인한 문장을 재사용하거나 다시 듣는 작업에는 추가 이동이 필요하다.
- **권장 순서:** 본문 선택·전체 복사 → 타임스탬프 이동 → 긴 전사 내 검색. 모바일은 보조 메뉴·공유 시트,
  데스크톱은 선택·우클릭·키보드 단축키를 활용한다. 모든 기능을 모바일 하단에 버튼으로 늘어놓을 필요는 없다.
- 이는 기존 계약 위반 버그와 구분한 제품 개선이다. 실제 목표 사용자가 Drive·웹훅으로만 소비한다면 우선순위를 낮출 수 있다.

## 실행 검증이 더 필요한 위험

| 항목 | 코드상 근거·위험 | 필요한 검증 |
|---|---|---|
| 긴 전사 성능 | Android `RecordingDetailScreen.kt:150`, Windows `RecordingsWindow.kt:245`는 일반 Column 전체 구성, Apple `RecordingDetail.swift:351`은 VStack 전체 구성. 발화 묶음도 뷰에서 매번 만든다. Kotlin은 같은 화자 본문을 누적 문자열 복사로 이어 붙임(`:499`, Windows `:545`) | 릴리스 빌드에서 30분·2시간·다수 발화 전사를 열고 첫 표시 시간·스크롤 지연·메모리를 측정. 필요 시 발화 묶음 캐시와 LazyColumn/LazyVStack 적용. 아직 느리다는 실측 판정은 하지 않음 |
| 고대비 설정 | Apple·Windows는 시스템 대비 관찰 경로가 있으나 Android 테마는 dark·글씨·reduce motion만 읽음(`theme/Theme.kt:109`). 시스템이 자체 적용하는 텍스트 효과와 앱의 도형·배경 대응을 구분해야 함 | Android 고대비 설정 ON/OFF에서 실제 텍스트·입력칸·선택 상태·배경을 확인. 곧바로 ‘Android 고대비 전부 작동 안 함’이라고 단정하지 않음 |
| 작은 워치·확대 글씨 | Galaxy Watch 주 화면은 스크롤을 제공함(`android/wear/.../ui/MainScreen.kt:100`). Apple Watch는 고정 VStack과 일부 축소에 의존(`RecordingView.swift:17`) | 지원 최소 크기에서 권한 거부·전송 대기·긴 워크플로우명·큰 글씨·VoiceOver/TalkBack·크라운/베젤 동작 검사. [Wear OS 접근성](https://developer.android.com/training/wearables/accessibility) 참고 |
| 처음 전사까지의 설정 이해 | 메인 화면은 장치 코드·워크플로우·상태를 먼저 보여준다. 첫 실행 샘플 Memo는 Drive 작업이고, 전사를 쓰려면 별도 설정을 알아야 함 | 대상 사용자에게 설명 없이 ‘첫 녹음 → 전사 → 결과 활용’을 수행하도록 관찰. 도움이 필요한 지점에 선택적 안내·전사 예시를 배치. 개발자/숙련 사용자용 그래프를 일괄 제거하지 않음 |
| 실기기·데스크톱 접근성 | 현재 검증은 일부 에뮬레이터·모델 테스트에 집중됨 | 물리 iPhone/Android의 한 손 조작, VoiceOver/TalkBack, Windows Narrator·125/150/200% 배율, macOS 키보드 탐색과 최소 창 크기 검증 |

## 이미 근거가 있는 부분

- 일반 텍스트 대비: 밝은/어두운 테마의 본문·보조문자·강조·오류·경고 글씨를 계산했다. 이 조합 중 최저는
  밝은 배경의 강조·오류 **4.66:1**, 보조문자는 **6.07:1 이상**, 주요 버튼 글씨는 **5.00:1 이상**이다.
  `contrast.json`에 전체 계산값이 있다. Apple·Windows 팔레트도 같은 토큰 값을 사용한다.
- Android 공통 버튼·칩에 최소 48dp가 지정돼 있다. Apple의 공통 칩·재생 컨트롤에도 크기·이름·상태 연결이 있다.
  개별 컨트롤 전체를 실측한 결과로 확대 해석하지 않는다.
- 파형은 탭·드래그와 보조기술 값 변경을 지원한다. 따라서 ‘드래그로만 조작 가능’이라는 지적은 맞지 않는다.
- 녹음/전사/빈 결과/실패 안내, 입력 폐기 확인, 삭제 확인, 전사·녹음 상태 자동 갱신은 이전 개선과 이번 기본 테스트의 근거가 있다.
- iPhone·Android 하단 재생, macOS·Windows 상단 재생·분할 창을 유지하는 방향은 타당하다. 일관성은 동작·용어·상태를 맞추는 것이며
  화면 배치를 동일하게 만드는 요구로 해석하지 않았다.

## 실행 명령과 실제 결과

| 명령/검사 | 실제 결과 |
|---|---|
| `make test` | `BUILD SUCCESSFUL in 1s`; `123 actionable tasks: 1 executed, 122 up-to-date`; JVM 기존 결과 core 538 + Android 470 + Windows 365 = 1,373개, 실패 0 |
| `make apk` | 현재 코드의 debug APK 생성 성공(`apk.log`). 전용 에뮬레이터에만 설치 |
| `adb -s emulator-5554 shell wm size 945x1680` 및 `settings put system font_scale 2.0` | 420dpi에서 360×640dp·200% 조건 구성 |
| `ANDROID_SERIAL=emulator-5554 make android-ui-test` | 해당 200% 조건에서도 기존 6개 통과; `BUILD SUCCESSFUL in 22s` |
| `ANDROID_SERIAL=emulator-5554 make android-ui-test ANDROID_TEST_CLASS=app.recly.android.ui.UiStandardsProbe` | 최종 200% 진단 2개 중 2개 실패; `BUILD FAILED in 11s`; A1·A2 재현 |
| 같은 최종 진단을 100%에서 실행 | 2개 모두 통과; `BUILD SUCCESSFUL in 10s`; 헤더·탭 모두 가시 문자 9/9. `control-final-result.xml` 및 `control-final-values.txt`에 기록 |
| 팔레트 sRGB 대비 계산 | `contrast.json`에 수치 저장 |
| `cua-driver list_apps '{}'` | Recly Mac은 실행 중이 아니었음. 설치본 버전과 현재 소스의 일치를 확인하지 못해 그 앱의 UI를 최신 코드 검증으로 사용하지 않음 |

**진단 정밀도:** 처음에는 `hasVisualOverflow`만 검사했지만, 기본 크기에서 글자보다 넓은 paragraph 폭 때문에
오탐할 수 있음을 발견했다. 최종 진단은 실제 글자의 우측 경계(1px 허용), 마지막 가시 문자 위치, 말줄임 여부,
제목 폭 0을 검사한다. 같은 진단을 100%와 200%에서 대조했다. 초기 중간 진단의 실패 개수를 최종 결과에 합산하지 않았다.

기존 6개 UI 테스트는 버튼 존재·클릭 가능·키보드 위 위치·모델 상태 등을 검사한다. 텍스트 전체가 읽히는지,
헤더에 제목 공간이 남는지 검사하지 않아 A1·A2가 있어도 통과했다. 또한 일부 편집 진입은 모델 호출을 사용하므로
모든 실제 진입 동선을 검증한 테스트로 볼 수 없다(`MobileUxTest.kt:51`).

이번 UI 실행은 **Android·영어**에 한정한다. 시스템 앱 언어 명령으로 한국어 전환도 시도했지만 실제 화면이 영어로
남아 한국어 검증에 포함하지 않았다. iPhone UI·macOS UI·Windows UI·워치 UI는 이번에 실행 검증하지 않았다.
이전 Apple 449개 테스트와 빌드 성공은 [직전 수정 기록](ux-detailed-review.md)에 있으며 이번 재실행 결과가 아니다.

임시 진단 테스트는 증거 디렉터리에 보관한 뒤 소스 트리에서 제거했다. 기존 미커밋 수정은 유지했다.
진단을 제거한 뒤 `make test`는 `BUILD SUCCESSFUL in 2s`였으며, XML 집계는 1,373개·실패 0으로 유지됐다
(`final-tests.log`, `jvm-counts.json`). `git diff --check`는 출력 없이 종료 코드 0이었다.
에뮬레이터의 화면 크기를 원래 값으로, 글씨를 최초 관찰값 1.6으로 복원한 뒤 해당 전용 에뮬레이터를 종료했다.

## 최적화 완료 판정을 위한 다음 순서

1. **확정 결함과 입력 접근성:** A1·A2 수정, B1 입력칸 경계 분리, B2 키보드·포커스 보완. 화면 잘림 진단을 영구 회귀 검사로 전환.
2. **플랫폼별 화면 검증:** 모바일 320·360·412dp/pt, 한·영, 100·160·200%/Dynamic Type 최대, 밝음·어두움·고대비.
   데스크톱 최소 창·분할·키보드·배율, 워치 최소 화면·큰 글씨를 각각 검사한다. 전수 조합 대신 위험 조합부터 검사하되 미검증 조합을 기록한다.
3. **결과 활용:** 복사·시각 이동을 먼저 보완하고, 대상 사용자에 따라 검색과 첫 전사 안내를 추가한다. iPhone 방향 지원 범위도 명시적으로 결정한다.
4. **사용·성능 증거:** 릴리스 실기기에서 녹음→전사→원하는 구간 찾기→본문 재사용을 끝까지 수행한다.
   목표 사용자 관찰에서 막힌 지점과 오조작을 기록하고, 긴 전사의 표시·스크롤 성능을 측정한 뒤 완료 판정을 갱신한다.

유닛 테스트 개수만 늘리거나 하단 버튼 위치만 바꾸는 것으로 완료 판정을 대체할 수 없다.
