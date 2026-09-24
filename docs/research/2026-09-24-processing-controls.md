# 완료 후 연동 제거와 자동 화자 분리

사용자 요청: 완료 후 연동 기능을 없애고, 화자 분리 옵션은 노출하지 않으며 지원하는 경우 항상 켠다.

## 변경 범위

- iPhone·Mac·Android·Windows의 고정 처리 화면에서 완료 웹훅, 화자 분리 토글, 최소/최대 화자 수 입력을 제거했다.
- 예전 실패 작업의 복구 입력도 외부 전사에만 표시한다. 웹훅 복구용 URL·키 입력은 현재 화면에서 노출하지 않는다.
- 현재 설정 조회·저장·가져오기·내보내기와 새 계획에서 완료 웹훅을 제거한다. 예전 파일의 필드는 읽기 호환을 위해
  스키마와 파서에 남기며, 기존 workflow 문서·이미 큐에 저장된 단계·키 값은 수정하지 않는다.
- 화자 분리는 지원하는 외부 어댑터에서 자동 요청한다. Groq와 명시적으로 선택한 비화자 OpenAI 모델은 제외한다.
  OpenAI 모델 미지정 시 기존 어댑터의 화자 모델 기본값을 사용한다. 명시된 모델을 자동 변경하지 않는다.
- 로컬 실행은 엔진의 `supportsDiarization`을 확인하고 실제 요청에 반영한다. 미지원이면 화자 분리 없이 전사를 계속한다.
  따라서 Apple SpeechTranscriber도 전사 실패로 바뀌지 않는다. 화자 수 입력은 기본 범위로 대체해 엔진/업체가 추론한다.
- 기존 대기 작업의 저장된 선택은 유지한다. 과거 문서의 재해석 때문에 새 녹음에 제거한 옵션이 되살아나지 않도록
  UI 초안뿐 아니라 코어의 설정과 계획 생성에서 동일한 정책을 적용한다.
- 예전에 만든 Siri 단축어도 새 녹음을 시작할 때는 현재 고정 설정을 사용한다. 저장된 workflow 파라미터의 해독은
  유지하지만 녹음 시작에는 넘기지 않는다. 이전 녹음 복구 및 큐의 workflow 선택과는 분리한다.

## 검증

회귀 테스트는 기존 설정·가져오기·내보내기에서 웹훅 제거, 공급자와 명시 모델별 화자 지원,
지원/미지원 로컬 엔진 요청과 결과, 이미 큐에 저장된 단계 보존을 다룬다.
새 실계정 API 요청이나 장시간 전사 부하 시험은 실행하지 않는다.

스키마 확인:

```text
/usr/sbin/taskpolicy -b make spec
OK   recording-settings.schema.json <- examples/recording-settings.json
OK   recording-settings.schema.json <- examples/recording-settings-external.json
OK   settings: legacy speaker choices remain readable -> valid=true
OK   settings: legacy webhook remains readable -> valid=true
```

JVM 검증:

```text
/usr/sbin/taskpolicy -b make test GRADLE='./gradlew --offline --max-workers=1 --no-parallel -Pkotlin.compiler.execution.strategy=in-process'
BUILD SUCCESSFUL in 16m 34s
123 actionable tasks: 34 executed, 89 up-to-date
XML: tests=1452, failures=0, errors=0, skipped=0
core 601 / Android app 335 / wear 58 / recording 65 / datalayer 23 / Windows 370
```

로그는 `/tmp/recly-controls-jvm.log`, 스키마 검증은 `/tmp/recly-controls-spec.log`다.

최종 검토에서 세 UI의 옛 웹훅 복구 입력을 숨긴 뒤 같은 `make test` 명령을 다시 실행했다.

```text
BUILD SUCCESSFUL in 7m 44s
123 actionable tasks: 10 executed, 113 up-to-date
XML: tests=1452, failures=0, errors=0, skipped=0
```

최종 JVM 로그는 `/tmp/recly-controls-jvm-final.log`다. `git diff --check`도 출력 없이 종료 코드 0으로 통과했다.

Apple 공통 라이브러리:

```text
/usr/sbin/taskpolicy -b make core CORE_GRADLE_ARGS='--offline --max-workers=1 --no-parallel -Pkotlin.compiler.execution.strategy=in-process'
BUILD SUCCESSFUL in 51m 14s
47 actionable tasks: 27 executed, 20 up-to-date
build-core: /Users/rokrokss/rec/apple/RecKit/Frameworks/ReclyCore.xcframework
```

Apple 검사(직렬 실행):

```text
/usr/sbin/taskpolicy -b make -j1 mac-test ios-ui-test XCODEBUILD='xcodebuild -workspace apple/Rec.xcworkspace -collect-test-diagnostics never -jobs 1 -parallel-testing-enabled NO' IOS_TESTS=ReclyTests/RecordingIntentTests SIM_BUILD_ARGS='-jobs 1 -parallel-testing-enabled NO'
RecKit: Executed 506 tests, with 2 tests skipped and 0 failures (0 unexpected)
** TEST SUCCEEDED **
ReclyTests/RecordingIntentTests: Executed 6 tests, with 0 failures (0 unexpected)
** TEST SUCCEEDED **
```

제외한 두 검사는 마이크 실녹음과 실제 로컬 모델 추론이다. 로그는 `/tmp/recly-controls-apple.log`다.
iPhone 앱 및 포함된 Watch 앱도 위 시뮬레이터 검사 과정에서 빌드했다.

Mac 설치용 빌드:

```text
/usr/sbin/taskpolicy -b make mac XCODEBUILD='xcodebuild -workspace apple/Rec.xcworkspace -collect-test-diagnostics never -jobs 1 -parallel-testing-enabled NO CODE_SIGN_IDENTITY=F28FEB1373DC6357F762A1750B40711E4DA8A09A DEVELOPMENT_TEAM=87G5R48C73 CODE_SIGN_STYLE=Manual'
** BUILD SUCCEEDED **
/Applications/Recly.app signature verified
/Users/rokrokss/Library/Developer/Xcode/DerivedData/Rec-hceoraeiiygpllftfieamwnnkyff/Build/Products/Debug/Recly.app signature verified
Designated requirements match
```

기존 앱과 같은 Developer ID 서명을 사용했다. 녹음 상태 `finalized=4`, 대기 작업 0건을 확인하고 DB를 온라인 백업했다.
`cua-driver`의 Cmd-Q로 정상 종료한 뒤 새 앱을 `/Applications/Recly.app`에 설치하고 같은 도구로 실행했다.
이전 앱과 DB는 `/Users/rokrokss/Library/Caches/recly-before-controls-ccpaq4bb`에 보존했다.

```text
ps -p 49845 -o pid=,comm=
49845 /Applications/Recly.app/Contents/MacOS/Recly
/usr/bin/log show --last 3m --info --style compact --predicate 'process == "Recly" AND (eventMessage BEGINSWITH "shell.ready" OR eventMessage BEGINSWITH "shell.failed")'
2026-09-24 10:57:54.134 I  Recly[49845:57317e7] [app.recly.mac:shell] shell.ready device=<private> dataDir=<private> workflows=0 recovered=0
```

시작 후 SecurityAgent 창은 없었다. 메뉴 설정 화면의 픽셀 단위 수동 검증이나 실계정 API 호출은 하지 않았다.
로그는 `/tmp/recly-controls-signed-mac.log`, `/tmp/recly-controls-launch.log`다.

## 구현 위치

- `core/src/commonMain/kotlin/recly/core/processing/ProcessingSettings.kt:27`: 새 녹음의 고정 정책.
- `core/src/commonMain/kotlin/recly/core/processing/ProcessingPlan.kt:11`: 계획 생성 시 동일한 정책 적용.
- `core/src/commonMain/kotlin/recly/core/processing/ProcessingSettingsRepository.kt:183`: 저장과 조회의 정규화.
- `core/src/commonMain/kotlin/recly/core/transcribe/SttProvider.kt:144`: 외부 어댑터·선택 모델의 화자 지원 판정.
- `core/src/commonMain/kotlin/recly/core/transcribe/LocalTranscriptionService.kt:93`: 로컬 런타임 지원 여부 반영.
- `apple/RecKit/Sources/RecKit/Transcription/ProcessingSettingsView.swift:218`: Apple 처리 UI.
- `android/app/src/main/kotlin/app/recly/android/ui/ProcessingPanel.kt:86`: Android 처리 UI.
- `windows/app/src/main/kotlin/app/recly/windows/ui/ProcessingPanel.kt:49`: Windows 처리 UI.
- `apple/RecPhone/RecPhoneShared/RecordingIntents.swift:78`: 예전 단축어의 새 녹음도 고정 설정으로 시작.
- `apple/RecPhone/RecPhone/RecordingModel.swift:646`: 모델 진입점에서도 옛 workflow ID를 새 캡처에 적용하지 않음.
- `apple/RecPhone/RecPhoneTests/RecordingIntentTests.swift:21`: 예전 단축어와 현재 설정, 옛 ID 해독 검증.
- `Makefile:147`: 시뮬레이터 검사에도 기존 `SIM_BUILD_ARGS`를 전달해 직렬 빌드 옵션을 적용.
