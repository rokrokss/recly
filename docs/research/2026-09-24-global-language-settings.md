# 녹음 설정 단순화와 글로벌 언어 지원

2026-09-24 요청: 데스크톱 회의 모드 고정, 마이크 자동 선택과 선택 UI 제거, ElevenLabs·CLOVA 순서,
앱 화면과 전사 언어 확대, 이전 설정 내보내기 제거.

## 적용 범위

- Mac·Windows에서 시작하는 녹음은 회의 모드다. 과거 수동 모드나 Mac 마이크 UID를 새 녹음에 적용하지 않는다.
  현재 입력 장치 상태와 권한 복구 안내는 유지한다.
- 외부 전사 목록의 첫 두 항목은 ElevenLabs, CLOVA다. 외부 제공자를 아직 설정하지 않은 초안은 ElevenLabs를
  선택한다. 기존 제공자·모델·키 설정은 보존한다.
- 이전 설정 내보내기의 버튼과 호출 경로는 Mac·iPhone·Android·Windows에서 제거한다. 현재 설정 내보내기는 유지한다.
  과거 문서·큐 스냅샷·내부 복구 백업은 기존대로 읽을 수 있다.
- 화면은 en·ko·ja·zh-Hans·zh-Hant·es·fr·de·pt·ar·hi·ru 12개 언어를 제공한다. 워치·위젯·알림·오류 안내도 포함한다.
  언어 이름은 자기 이름으로 표시하고 기존의 한 행 선택 UI를 유지한다. 중국어 지역/표기체와 아랍어 RTL을 처리한다.
- 전사 언어는 20개 명시 선택값(중국어 간체/번체 포함)에 기존 자동·한영 혼용을 더한다. 화면 언어와 독립적이며
  새 설치에서만 기기 언어로 초기화한다. 저장된 전사 언어는 앱 언어를 바꿔도 유지한다.
- 외부 제공자·모델별 언어 범위를 적용하고, Apple 로컬은 실제 OS의 `supportedLocale(equivalentTo:)`로 확인한다.
  선택한 제공자에서 지원하지 않는 언어가 남아 있으면 안내하고 저장을 막는다. 외부 API의 파일 분할 방식은 변경하지 않는다.

RTL은 플랫폼의 방향 환경값을 적용한다. SwiftUI 커스텀 Layout도 시스템에서 수평 위치를 반전하므로
직접 이중 반전하지 않는다([Apple LayoutDirection](https://developer.apple.com/documentation/swiftui/layoutdirection)).
Compose 팝업의 자체 위치 계산은 RTL에서 오른쪽 앵커에 맞추고 화면 밖으로 나가지 않도록 한다.

## 리소스와 호환성

`localization/translations/`의 공통 사전에서 `scripts/localize.py`가 플랫폼 네이티브 리소스를 생성한다.
529개 정규화된 영어 원문을 검사하고, 번역 누락·형식 인자 불일치·생성 파일 불일치는 실패로 처리한다.
브랜드와 언어 자기 이름 등만 명시적으로 번역 제외 목록에 둔다. 별도 번역 서버나 앱 네트워크 경로는 추가하지 않는다.

언어 코드가 늘어나지만 기존 설정과 workflow의 버전·기존 네 가지 언어 값은 바꾸지 않는다. 실제 API 요청에는 업체가 받는
ISO 코드나 로케일로 변환한다. 중국어의 `zh-cn`/`zh-tw` 선택은 ISO 코드만 받는 API에서 `zh`, Speechmatics·Rev에서는
`cmn`이므로, 모든 제공자가 출력 표기체까지 강제한다는 의미는 아니다.

Android·Windows의 로컬 엔진은 기존 구현대로 아직 사용 불가 상태다. 이번 변경으로 해당 플랫폼의 로컬 ASR 모델이
새로 탑재되는 것은 아니며, 외부 API 선택·설정은 계속 가능하다.

## 제공자 근거

2026-09-24 확인한 공식 문서:

- [ElevenLabs Speech to Text](https://elevenlabs.io/docs/overview/capabilities/speech-to-text),
  [API 언어 코드](https://elevenlabs.io/docs/api-reference/speech-to-text/convert): 다국어 Scribe와 ISO 언어 코드.
- [CLOVA 장문 업로드](https://api.ncloud-docs.com/docs/ja/ai-application-service-clovaspeech-longsentence-local):
  ko-KR·en-US·enko·ja·zh-cn·zh-tw. 선택 UI도 이 범위로 제한한다.
- [Apple SpeechTranscriber](https://developer.apple.com/documentation/speech/speechtranscriber): 기기 가용성·지원 로케일은
  런타임 API로 확인하고, 언어 자산 설치 여부와 구분한다.
- [Deepgram 모델/언어](https://developers.deepgram.com/docs/models-languages-overview): Nova 모델별 범위;
  Nova-2의 아랍어와 영어 전용 특수 모델을 일반 다국어 모델과 구분한다.
- [Mistral Speech to Text](https://docs.mistral.ai/studio/audio/speech_to_text): 13개 언어 범위.
- [RTZR 파일 전사](https://developers.rtzr.ai/docs/stt-file/): sommers의 한·일과 whisper 다국어를 구분한다.
- [Speechmatics 언어](https://docs.speechmatics.com/speech-to-text/languages): Mandarin `cmn` 코드.
- [Rev 언어 코드](https://www.rev.ai/languages?a5d3b468_page=4): Mandarin은 `cmn`. 별도 언어 감지 요청 없이
  자동 전사를 한다고 표시하지 않도록 Rev의 자동 언어 선택은 제외한다. 과거 `auto` 값의 읽기 동작은 유지한다.
- [Azure 언어](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/language-support): 언어별 로케일.
- [Daglo 비동기](https://developers.daglo.ai/guide/en/STT-Async.html): 새 언어 코드 계약을 확인하지 못한 언어를
  임의로 노출하지 않고 기존 어댑터의 검증된 한·영·혼용 범위를 유지한다.

## 검증

실제 외부 API 호출이나 장시간 모델 추론은 이 변경의 검사에 포함하지 않는다.
빌드는 백그라운드 우선순위에서 순차 실행했고, Gradle에는 `--max-workers=1`, Xcode에는 `-jobs 1`을 적용했다.

- `/usr/sbin/taskpolicy -b make test GRADLE='./gradlew --offline --max-workers=1 --no-parallel -Pkotlin.compiler.execution.strategy=in-process'`
  - 출력: `BUILD SUCCESSFUL in 13m 59s`, `123 actionable tasks: 44 executed, 79 up-to-date`.
  - JUnit XML: 코어 606, Android 휴대폰 336, 워치 58, 녹음 65, 데이터 계층 23, Windows 370.
    합계 **1,458개, 실패 0, 오류 0, 건너뜀 0**.
  - 초기 실행의 두 실패는 예전 제공자 순서와 암묵적인 한국어 테스트 데이터였다. 새 순서를 반영하고 테스트 언어를
    명시한 뒤 전체 명령을 다시 통과했다.
- `make spec`: `OK` 64개, 종료 코드 0. 22개 언어 값의 저장과 미지원 코드 거부 포함.
- `python3 scripts/localize.py --check`:
  `Localization: 12 languages, 529 source messages, 51 native files verified`.
- `git diff --check`: 출력 없음, 종료 코드 0.

- `/usr/sbin/taskpolicy -b make core CORE_GRADLE_ARGS='--offline --max-workers=1 --no-parallel -Pkotlin.compiler.execution.strategy=in-process'`
  - 최종 소스의 출력: `BUILD SUCCESSFUL in 55m 59s`, `47 actionable tasks: 27 executed, 20 up-to-date`.
  - `build-core: /Users/rokrokss/rec/apple/RecKit/Frameworks/ReclyCore.xcframework`.
  - iPhone 실기기/시뮬레이터, Mac, Watch 실기기/시뮬레이터 다섯 slice 검증과 staging 완료.

- `make mac-test XCODEBUILD='xcodebuild -workspace apple/Rec.xcworkspace -collect-test-diagnostics never -jobs 1 -parallel-testing-enabled NO'`
  - 출력: `Executed 507 tests, with 2 tests skipped and 0 failures (0 unexpected)`, `** TEST SUCCEEDED **`.
  - Swift 호출부의 `explicit_` 두 곳을 실제 Swift 이름인 `explicit`으로 수정한 뒤 통과했다.
  - 12개 언어의 실제 번들 조회와 전체 카탈로그, 새 전사 언어 보존 검사 포함.

- `make ios-ui-test IOS_TESTS=ReclyUITests/LanguageSettingUITests SIM_BUILD_ARGS='-jobs 1 -parallel-testing-enabled NO'`
  - 출력: `Executed 1 test, with 0 failures (0 unexpected)`, `** TEST SUCCEEDED **` (63.793초).
  - iPhone 17 Pro 시뮬레이터에서 영어 → 일본어 → 중국어 번체 → 아랍어, 재실행, 한국어 → 영어를 검증했다.
    아랍어 탭 방향과 선택 언어 유지가 통과했다. 일본어·번체·아랍어 첨부 스크린샷의 배치도 확인했다.
  - iPhone·워치·위젯이 함께 빌드됐다. 테스트 결과:
    `/Users/rokrokss/Library/Developer/Xcode/DerivedData/Rec-hceoraeiiygpllftfieamwnnkyff/Logs/Test/Test-Recly-2026.09.24_13-39-07-+0400.xcresult`.
- `/usr/sbin/taskpolicy -b make mac XCODEBUILD='xcodebuild -workspace apple/Rec.xcworkspace -collect-test-diagnostics never -jobs 1 -parallel-testing-enabled NO CODE_SIGN_IDENTITY=F28FEB1373DC6357F762A1750B40711E4DA8A09A DEVELOPMENT_TEAM=87G5R48C73 CODE_SIGN_STYLE=Manual'`
  - 출력: `** BUILD SUCCEEDED **`.
  - 기존 앱·새 앱 모두 `codesign --verify --deep --strict` 통과, designated requirement 동일.
  - `/Applications/Recly.app`을 교체하고 cua-driver로 실행했다. PID 29792의 실제 실행 경로도 이 설치 경로다.
  - 2026-09-24 13:47:52 +0400 로그: `shell.ready device=<private> dataDir=<private> workflows=0 recovered=0`.
  - 재시작 후 녹음 `finalized|4`, 작업 0, 전사 설정 원문의 SHA-256 동일, 앱 언어 `ko` 유지.
  - 백업: `/Users/rokrokss/Library/Caches/recly-before-global-zyd92nya` (이전 앱·온라인 SQLite 백업·검증 상태).

최종 리소스 검사와 `git diff --check`도 통과했다. Mac에서 생략한 두 테스트는 실제 마이크 녹음과 로컬 전사다.
Android·Windows는 macOS 호스트에서 단위 테스트 및 컴파일을 검증했으며, 실기기 화면·WASAPI 실행은 이번 검사에 포함하지 않았다.

## 주요 구현 위치

- `apple/RecMac/RecMac/MenuModel.swift:222`, `:445`: 자동 마이크, 회의 모드 고정.
- `windows/app/src/main/kotlin/app/recly/windows/ui/ShellModel.kt:606`: 회의 모드 고정.
- `core/src/commonMain/kotlin/recly/core/workflow/WorkflowParser.kt:97`: 외부 제공자 목록 순서.
- `core/src/commonMain/kotlin/recly/core/transcribe/TranscriptionLanguages.kt:11`, `:62`:
  제공자·모델별 언어 범위, 초기 언어 선택.
- `apple/RecKit/Sources/RecKit/Transcription/AppleSpeechTranscriber.swift:5`: OS의 실제 로컬 언어 지원 조회.
- `apple/RecKit/Sources/RecKit/Transcription/ProcessingSettingsView.swift:130`, `:152`:
  현재 설정만 내보내기, 지원 언어의 저장 검증.
- `apple/RecKit/Sources/RecKit/Localization/AppLanguage.swift:34`: 앱 언어 12개와 지역/표기체 대응.
- `apple/RecPhone/RecPhoneUITests/LanguageSettingUITests.swift:9`: 실화면 언어 전환·아랍어 RTL·재실행 유지 검사.
