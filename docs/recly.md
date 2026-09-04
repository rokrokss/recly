# Recly

**어느 기기에서 녹음하든, 녹음이 끝나면 사용자가 정한 워크플로우가 돈다.**

이 문서 하나가 Recly의 현재 모습이다. 역사도 레인 기록도 아니고, 지금 코드가 지키는 계약과 규칙을 있는 그대로 적는다.
기계가 읽는 정본은 `spec/*.json`(워크플로우·메타·웹훅 payload·transcript 스키마)이고, 이 문서는 그 스키마의 의미와
스키마에 담기지 않는 규칙을 정한다.

**절 번호는 계약이다.** 코드 주석의 `docs/NN "소제목"` 인용은 이 문서의 §NN과 그 소제목을 가리킨다. 절 번호와
소제목은 이유 없이 바꾸지 않는다.

| 절 | 내용 |
|---|---|
| [0](#0-제품-정의--규칙-구-docs00) | 제품 정의 · 원칙 · 규칙(구 ADR) |
| [1](#1-아키텍처-구-docs01) | 아키텍처 |
| [2](#2-워크플로우-계약-구-docs02) | 워크플로우 계약 |
| [3](#3-녹음저장-구-docs03) | 녹음 · 저장 · 보관 · 삭제 |
| [4](#4-웹훅-구-docs04) | 웹훅 |
| [5](#5-워크플로우-보관시크릿-구-docs05) | 워크플로우 보관 · 내보내기/가져오기 · 시크릿 |
| [6](#6-인증-구-docs06) | Google 인증 |
| [7](#7-i18n-구-docs07) | 다국어 |
| [8](#8-전사-구-docs08) | `transcribe` |
| [9](#9-디자인-시스템-blueprint-구-docs09) | 디자인 시스템 "Blueprint" |
| [10](#10-코어-kmp-구-docs10) | 공유 코어(KMP) |
| [11](#11-androidwear-구-docs11) | Android 폰 · Galaxy Watch |
| [12](#12-macos-구-docs12) | macOS |
| [13](#13-ioswatchos-구-docs13) | iPhone · Apple Watch |
| [14](#14-windows-구-docs14) | Windows |
| [15](#15-프라이버시데이터-흐름-구-docs15) | 프라이버시 · 데이터 흐름 |
| [16](#16-페르소나가격-구-docs16--제안-미결정) | 페르소나 · 가격(제안, 미결정) |
| [20](#20-검증-상태-구-docs20) | 검증 상태 |
| [21](#21-컨벤션-구-docs21) | 컨벤션 — 일부러 남긴 것 |
| [열린 결정](#열린-결정) | 남은 사용자 결정 |

---

## 0. 제품 정의 · 규칙 (구 docs/00)

### 한 줄 정의

녹음과 업로드만 하는 정직한 레코더. 원본 오디오는 **사용자 자신의 Google Drive**에 그대로 남고, 그 다음은 사용자의
워크플로우가 한다. Plaud 류 노트테이커에서 "녹음 + 업로드"만 떼어낸 제품이며, 워치·폰·데스크톱 여섯 클라이언트가
같은 워크플로우를 실행한다.

### 원칙

1. **녹음한 기기가 실행한다.** 워치는 폰에 넘긴다. **Recly의 서버는 존재하지 않는다.**
2. **설정은 기기의 것이다.** 워크플로우 정의·전사 키·기본 선택은 전부 기기 로컬에 있고 동기화하지 않는다.
   기기 사이의 이동은 설정의 내보내기/가져오기(§5)다. Drive에는 녹음과 결과 파일만 올라간다 — 그리고 그것이
   곧 기기들이 공유하는 **녹음 목록**이다(ADR-023, §3 "다른 기기의 녹음"). 재시도·업로드
   세션 같은 런타임
   상태는 각 기기 로컬에만 있다.
3. **ack 전까지 원본을 지우지 않는다.**
4. **파일과 웹훅이 인터페이스다.** 전사는 선택 단계이고, 요약부터는 사용자의 에이전트 몫이다(§16,
   `skills/recly-notes/`). 기본은 "내 Drive + 내 자동화".
5. **은밀 모드는 없다.** 녹음 중에는 항상 표시하고, 데스크톱은 감지 → 확인 → 녹음이다.

### 규칙 (구 ADR)

코드 주석이 `ADR-0NN`으로 인용하는 결정들이다. 번호는 안정 계약이므로 유지하고, 내용은 현재 규칙만 적는다.

| 번호 | 규칙 |
|---|---|
| ADR-001 | 기기는 녹음하고 원본은 사용자의 Drive로 간다. 전사·전달은 사용자가 **자기 워크플로우에 직접 넣는 선택 단계**이지 고정된 후처리가 아니다. 요약은 파이프라인에 없다 — 구독형 에이전트가 못 하는 일(STT)만 파이프라인이 대신하고, 할 수 있는 일(텍스트 요약)은 에이전트 스킬(`skills/recly-notes/`)로 한다 |
| ADR-002 | 워크플로우를 실행하는 것은 Android 폰·iPhone·macOS·Windows다. Galaxy Watch는 Data Layer로, Apple Watch는 WatchConnectivity로 파일을 폰에 넘긴다. **워치에는 인증·네트워크 코드가 없다.** 셀룰러 워치 단독 업로드는 범위 밖 |
| ADR-003 | 폰과 워치가 동시에 녹음하면 파일도 둘이고 서로 연결하지 않는다. `recordingId`는 기기별 독립 ULID이고 세션 연결(session id)은 없다 |
| ADR-004 | 워크플로우 엔진·Drive 클라이언트·웹훅·동기화·잡 큐는 Kotlin Multiplatform `core/` 하나에 있다. 워크플로우 로직을 두 번 짜지 않는다 |
| ADR-005 | 셸: Android/Wear는 Kotlin + Compose, iOS·watchOS·macOS는 SwiftUI + KMP XCFramework, Windows는 Compose Desktop(JVM) + Rust 캡처 헬퍼 |
| ADR-006 | 오디오는 AAC-LC `.m4a`, 16 kHz 모노 32 kbps, 명목 900초 세그먼트. 모바일·워치는 `mono` 한 트랙, 데스크톱은 `mic`·`sys`·`mix` 세 트랙 |
| ADR-007 | 워크플로우 정의는 **기기 로컬 문서** 하나이고 백엔드도 동기화도 없다. 기기 간 이동은 설정의 내보내기/가져오기(§5) — 파일 포맷은 문서 직렬화 그대로다 |
| ADR-008 | 워크플로우 JSON에는 시크릿 **이름**(`secretRef`)만 들어가고 값은 기기별 보안 저장소에 있다. 값이 없는 기기에서 그 단계는 `MISSING_SECRET`으로 실패한다. 값은 동기화되지 않으며 내보내기 파일에도 절대 들어가지 않는다 — 키는 기기마다 입력한다 |
| ADR-009 | OAuth 스코프는 `drive.file` 하나이고 동의 화면은 Production이다. 전체 `drive` 스코프를 요청하지 않는다 |
| ADR-010 | 웹훅 서명은 Standard Webhooks 그대로다 |
| ADR-011 | **회의에 봇을 넣지 않는다.** 마이크 사용·회의 앱으로 감지하고 사용자가 한 번 눌러 녹음한다. 자동 녹음은 없다. 참가자별 스트림은 없고 "나 vs 상대" 두 트랙이 상한이다 |
| ADR-012 | 단계 타입은 `drive.upload` · `webhook` · `transcribe` 셋이다(`schema: 3`). `deliver.*`(Notion·Telegram)는 없다 |
| ADR-013 | 단계별 조건식(`if`)은 없다. 워크플로우 수준의 `minDurationSec` 하나뿐이다 |
| ADR-014 | Drive 배치는 녹음당 폴더 하나 — `{folder}/{base}/` 아래 파트 파일들과 `meta.json` |
| ADR-015 | resumable 업로드는 **프로토콜(코어의 순수 함수)과 전송(플랫폼)이 분리**되어 있다. Apple은 배경 `URLSession`으로 전송을 교체한다 |
| ADR-016 | 기기마다 **사용 중인 워크플로우 하나**를 고른다 — 로컬에만 있고 동기화되지 않는 포인터이고, 이 기기의 모든 녹음(수동·회의 감지·워치)이 그것을 실행한다. 피커에서 고르는 행위가 곧 이 포인터를 바꾸는 것이며 녹음 단위의 임시 선택은 없다(2026-09-02: 이전의 "기본 (이름)" 항목과 "기본" 어휘를 폐기 — 같은 워크플로우가 두 항목으로 보였다). 공유 문서에는 `enabled`·`isDefault`·`trigger.sources`가 없고, 소스 필터도 `updatedAt` 최신 폴백도 없다. 선택이 없거나 가리키는 워크플로우가 사라졌으면 아무것도 실행하지 않고 셸이 "워크플로우를 선택하세요"라고 묻는다. 사용 중인 워크플로우도 삭제할 수 있다 |
| ADR-017 | 로컬 원본은 **업로드가 성공했을 때만** 지운다. 웹훅만 있는 워크플로우나 `continue`로 지나간 실패 업로드는 원본을 보관한다. **2026-09-03 개정**: 업로드 성공 후에도 **7일**은 남긴다(고정값, 설정 UI 없음) — 로컬 파트는 기간이 있는 캐시다. 매 잡 패스의 보관 스윕이 "모든 잡 DONE + 업로드 전부 성공 + 파일 mtime과 마지막 DONE 시각 둘 다 7일 경과"인 녹음의 파트를 지운다. 상세 화면이 재생할 때 로컬에 없는 파트는 업로드 출력의 `fileId`로 Drive에서 다시 받아 같은 이름으로 두고(sha256 검증), 그 파트는 다시 7일을 받는다. 업로드된 적 없는 파트는 영원히 보관 |
| ADR-018 | 제품명은 **Recly**(식별자 `recly`). Android `app.recly`·Kotlin 패키지 `recly.core`, Apple 번들 `app.recly`/`app.recly.watch`/`app.recly.mac`, XCFramework `ReclyCore`. 사용자에게 보이지 않는 계약(파일명 `{base}`, 웹훅 `user-agent: rec/…`, 로그 이벤트 `rec.*`, 기기 저장 경로 `files/rec`·`rec.db`, Data Layer 경로 `/rec/…`)은 `rec` 그대로다 |
| ADR-019 | Windows 인코딩은 **번들 ffmpeg**(LGPL 동적 링크, 무수정, 별도 프로세스)다. Media Foundation AAC MFT는 입력 44.1/48 kHz·출력 96 kbps 이상만 받아 ADR-006의 16 kHz·32 kbps를 낼 수 없다. MF 경로는 `--encoder mf`로 남아 있고 기본값이 아니다 |
| ADR-020 | Drive 기본 폴더 최상단은 **`recly/`**다 — `drive.upload`의 `folder` 기본값 `recly/{{yyyy}}/{{yyyy}}-{{MM}}`, "메모" 기본 워크플로우 `recly/memo/{{yyyy}}-{{MM}}` |
| ADR-021 | `transcribe`(STT + 화자분리)는 **잡을 실행하는 기기가 사용자의 키로 provider API를 직접 호출**한다. 중간 서버·릴레이·콜백 URL은 없다 |
| ADR-022 | **텔레메트리가 없다.** 분석·사용 통계·크래시 리포팅·원격 로그 수집·원격 설정·A/B·광고 식별자를 넣지 않는다. 어떤 셸에도 Firebase/Crashlytics/Sentry/AppCenter 계열 의존성이 없다. 로그는 기기 로컬 플랫폼 로그에만 남고 사용자가 직접 내보낼 때만 기기를 떠난다 |
| ADR-023 | **녹음 목록은 Drive가 정본이다**(2026-09-04). 같은 계정의 다른 기기가 올린 녹음은 이 기기의 목록에도 나타난다 — 별도 색인 파일이나 서버 없이, 앱이 `recordingId`를 찍어 둔 `{base}/` 폴더(ADR-014)를 Drive에서 나열해 `meta.json`을 읽어 온다(§3 "다른 기기의 녹음"). 그 행은 이 기기에 Job도 원본도 없고 재생 시 Drive에서 받아 캐시한다. `meta.json`이 아직 없는 폴더도 목록에 나온다 — "다른 기기가 업로드 중"인 잠정 행으로. Drive에서 사라지면 행도 사라진다. 이 기기가 직접 만든 행은 Drive가 뭐라 하든 건드리지 않는다 |

이 규칙들을 뒤집으려면 이 문서를 고치는 것으로 끝나지 않는다. 특히 ADR-022를 뒤집으면
`docs/policy/privacy-policy.md`와 Play "데이터 안전" 양식·App Store 개인정보 라벨을 함께 고쳐야 한다.

---

## 1. 아키텍처 (구 docs/01)

### 시스템 그림

```
 ┌─ Capture ─────────────────────────────────────────────────────────────────┐
 │ Galaxy Watch   Android 폰   Apple Watch   iPhone   macOS         Windows   │
 │ MediaRecorder  MediaRecorder AVAudioEngine AVAudioEngine  CoreAudio tap  WASAPI │
 └──────┬─────────────┬───────────┬────────────┬──────────┬────────────┬─────┘
        │ Data Layer  │           │ WCSession  │          │            │
        │ ChannelClient           │ transferFile          │            │
        ▼             │           ▼            │          │            │
   (폰이 수신·ack)    │      (폰이 수신·ack)    │          │            │
                      ▼                        ▼          ▼            ▼
 ┌─ Executor (기기 로컬) ─────────────────────────────────────────────────────┐
 │  Job(recording × workflow) → step 1 → step 2 → … ; 상태는 로컬 SQLite     │
 │  KMP core: workflow · job · drive · webhook · transcribe · sync · storage  │
 └───────────────┬───────────────────────────────────────────┬────────────────┘
                 │                                           │
                 ▼                                           ▼
   Google Drive  {folder}/{base}/ parts + meta.json      웹훅 (서명, 재시도)
```

### 구성 요소

| 구성 요소 | 위치 | 책임 |
|---|---|---|
| Recorder | 플랫폼별 | 마이크(및 시스템 오디오) 캡처, 세그먼트 파일 작성, 무음화 감지, `meta.json` 작성 |
| Transfer | Wear/watchOS ↔ 폰 | 파트·메타 전송, sha256 검증, ack, ack 후 워치 측 삭제 |
| Core | `core/` KMP | 워크플로우 파싱·검증·선택, 잡 큐, 단계 실행, Drive resumable, 웹훅, 전사 provider 어댑터, 내보내기/가져오기 |
| Scheduler 어댑터 | 플랫폼별 | 코어의 `runDueJobs()`를 WorkManager / 배경 URLSession + BGTask / 트레이·메뉴바 타이머에서 호출 |
| Auth 어댑터 | 폰·데스크톱 | Google OAuth, access token 공급(`TokenProvider`) |
| UI | 플랫폼별 | 녹음 시작·정지, 워크플로우 편집(폰·데스크톱), 녹음 목록·상태 |

### 녹음 생명주기

```
 start ──► Recording{id, source, workflowId, startedAt}   (로컬 DB, status=recording)
   │        parts 작성: p001, p002 … (세그먼트마다 sha256, bytes 기록)
   │        meta.json 갱신 (세그먼트 경계마다)
 stop ───► finalize: endedAt, durationSec, status=finalized
   │
   ├─ 폰·데스크톱 ─► Job 생성 {recordingId, workflowId, steps[] 상태=PENDING}
   │                 Scheduler 어댑터가 runDueJobs() 호출
   │                 step 순서대로 실행 · 각 step 상태 영속 · 실패 시 backoff 후 재시도
   │                 모든 step 종료 → Job DONE
   │                 → 보관 규칙(ADR-017): 이 녹음의 모든 Job이 DONE이고 그중 하나가
   │                   drive.upload를 전부 SUCCEEDED시킨 뒤 **7일이 지나면** 매 패스의 보관 스윕이
   │                   로컬 파트를 지운다(아니면 보관 + 목록에 "업로드 안 됨"; meta.json·DB 행은 항상
   │                   보관). 지워진 파트는 상세의 재생이 Drive에서 다시 받아 7일간 캐시한다
   │
   └─ 워치 ───────► Transfer 큐 {recordingId, parts[]}
                    폰 연결 시 파트별 전송 → 폰이 sha256 검증 후 ack
                    전부 ack → 워치 삭제, 폰이 Job 생성(위와 동일)
```

불변 조건:

- 파트 파일은 같은 녹음의 모든 Job이 DONE이고 그중 하나가 `drive.upload` 단계를 전부 성공시키기 전까지 삭제되지
  않는다(ADR-017, §3 보관 · 삭제).
- 워치 파트는 폰 ack 전까지 삭제되지 않는다.
- `Job`은 `(recordingId, workflowId)`로 유일하다. 같은 녹음에 워크플로우를 다시 돌리면 새 Job이 아니라 기존 Job의
  실패 단계부터 재개한다.

### 식별자·시간

- `recordingId` · `workflowId` · `jobId` · `stepRunId`: ULID(26자, Crockford base32). 코어에서 생성.
- `deviceId`: 설치 시 생성한 UUID v4, 보안 저장소(macOS만 `{dataDir}/device.id`)에 보관. 재설치 시 새 값.
- 모든 시각은 UTC ISO-8601(`2026-08-26T01:00:00.000Z`). 표시용 타임존은 메타의 `timezone`.
- 파일명의 시각은 UTC 컴팩트(`20260826T010000Z`).

### 저장소 레이아웃

```
rec/
  core/                       KMP 모듈 (:core) — §10
  android/
    app/                      폰 앱
    wear/                     Galaxy Watch 앱
    recording/                MediaRecorder 기반 SegmentedRecorder + RecorderService (폰·워치 공용)
    datalayer/                폰↔워치 Data Layer 경로·JSON 계약 (양쪽이 함께 쓴다)
  apple/
    Rec.xcworkspace
    RecKit/                   Swift 패키지: Recorder, MacCapture, Detect, Transfer, Auth, Transport, Workflow, CoreBridge
    RecMac/ RecPhone/ RecWatch/
  windows/
    app/                      Compose Desktop
    capture-helper/           Rust (wasapi) — 캡처·감지, JSON lines로 상태 보고
  spec/                       JSON Schema + 예제 (계약)
  scripts/                    아이콘 렌더링, 웹훅 로컬 수신기
  docs/                       이 문서 + 개인정보처리방침 + 아이콘 마스터
```

빌드 도구: Gradle 래퍼(`core`·`android:*`·`windows/app`), Xcode(`apple`), Cargo(`windows/capture-helper`),
Node(`spec` 검증·웹훅 수신기). 루트 `settings.gradle.kts`가 `:core`, `:android:*`, `:windows:app`를 포함하고,
`apple`은 `:core:assembleXCFramework` 산출물을 SwiftPM binary target으로 참조한다.

### 코어 ↔ 셸 경계 (the core ↔ shell boundary)

**셸이 코어에 주는 것**(`CoreDeps`):

- `SecureStore` — 토큰·시크릿 읽기/쓰기(네임스페이스별)
- `TokenProvider` — 유효한 access token 반환(만료 시 갱신은 셸 책임)
- `FileSystem` — okio `FileSystem` + 앱 데이터 디렉터리
- `Transport` — 기본 Ktor. Apple은 배경 URLSession 구현으로 교체 가능(ADR-015)
- `AudioTools` — `concat`(파트 무손실 remux, §8)
- `Clock`, `Logger`, `DeviceInfo{deviceId, platform, name}`, `io` 디스패처

**코어가 셸에 주는 것**(`ReclyCore(deps, driverFactory)` — 셸이 SQLDelight 드라이버를 열어 넘긴다):

- `recordings` — 녹음 등록, 파트 추가, finalize, 목록, `delete`
- `workflows` — 로컬 캐시 읽기/쓰기, `sync()`, 선택 규칙
- `jobs` — `enqueue(recordingId, chosenWorkflowId?)`, `runDueJobs(now)`, `retry(jobId)`, 상태 관찰
- `secrets` — `SecretsRepository.put/delete/get/names`(값 쓰기의 **유일한** 입구, §5)
- `secretSync` — `setup`/`disable`/`status`
- `transfer` — 워치→폰 수신 측 검증·ack 도우미(송신은 플랫폼 API라 셸)
- `disconnect(alsoDeleteRecordings)` — 연결 해제의 로컬 정리 절반

셸은 오디오 캡처·전송 API·스케줄러·UI만 갖는다. **워크플로우 의미론은 절대 셸에 두지 않는다.**

셸로 나가는 모든 `suspend` 진입점에는 `@Throws(Throwable::class)`가 붙는다 — Kotlin/Native는 선언되지 않은
예외가 export된 suspend 함수를 벗어나면 프로세스를 죽인다.

---

## 2. 워크플로우 계약 (구 docs/02)

스키마: [`spec/workflow.schema.json`](../spec/workflow.schema.json) · 예제:
[`spec/examples/workflows.json`](../spec/examples/workflows.json). 스키마가 기계 정본이고 이 절은 그 의미다.

각 기기는 자기 문서 하나를 읽고 쓴다. 저장은 로컬 DB(§5), 내보내기 파일명은 `recly-workflows.json`이다.

### 문서 구조

```json
{
  "schema": 2,
  "revision": 12,
  "updatedAt": "2026-08-26T01:00:00.000Z",
  "updatedBy": "3f1c…-deviceId",
  "workflows": [ …workflow ]
}
```

| 필드 | 의미 |
|---|---|
| `schema` | 현재 **3**. 앱이 지원하는 값보다 **큰** 문서를 만나면 파서가 `Invalid(UnsupportedSchema)`를 돌려주고 가져오기는 아무것도 쓰지 않는다. UI는 "앱을 업데이트하세요"(update the app)를 보인다. `MIN_SCHEMA`(=1)..2의 옛 문서(로컬 저장분·가져온 파일)는 현재 규칙으로 읽어 현재 schema로 저장한다(§5 스키마) |
| `revision` | 쓸 때마다 +1. 병합 판단은 워크플로우별 `updatedAt`으로 하고 `revision`은 진단용 |
| `updatedBy` | 마지막으로 쓴 기기의 `deviceId` |

### 워크플로우

```json
{
  "id": "01J9ABCDEF0123456789ABCDEF",
  "name": "회의",
  "updatedAt": "2026-08-26T01:00:00.000Z",
  "minDurationSec": 30,
  "steps": [ …step ]
}
```

| 필드 | 의미 |
|---|---|
| `name` | 1~40자. UI에서 프로필 이름으로 쓰이고 워치 버튼에 그대로 표시된다 |
| `minDurationSec` | 이보다 짧은 녹음은 Job을 만들지 않고 `SKIPPED_SHORT`로 끝낸다(파일은 로컬 보관. 2026-09-02: 목록의 "지금 올리기"가 사라져 수동 업로드 경로는 없다 — 올리고 싶은 길이면 이 값을 낮춘다). 기본 0 |
| `steps` | 1~10개, 순서대로 실행 |

문서에는 **정의만** 있다. 어떤 워크플로우를 이 기기가 기본으로 쓰는지는 기기 로컬 포인터이고 동기화되지 않는다
(ADR-016, 원칙 2 "정의는 하나, 상태는 기기별"). `enabled`·`isDefault`·`trigger`(=`sources`)는 schema 3에서
사라졌다 — 옛 문서의 그 필드들은 읽을 때 폐기되고 `trigger.minDurationSec`만 위로 올라온다(§5 스키마).

### 단계 (step)

공통 필드:

| 필드 | 기본 | 의미 |
|---|---|---|
| `id` | 필수 | `^[a-z][a-z0-9_]{0,31}$`, 워크플로우 안에서 유일 |
| `type` | 필수 | `drive.upload` \| `webhook` \| `transcribe` |
| `onError` | `abort` | `abort`: 이후 단계 실행 안 함, Job FAILED. `continue`: 이 단계만 FAILED로 두고 다음 단계 진행 |
| `retry.maxAttempts` | 8 | 1~20 |
| `retry.initialDelaySec` | 30 | 지수 백오프 시작값 |
| `retry.maxDelaySec` | 3600 | 백오프 상한. 지터 ±20% |

#### `drive.upload`

```json
{ "id": "up", "type": "drive.upload",
  "folder": "recly/{{yyyy}}/{{yyyy}}-{{MM}}",
  "includeMeta": true }
```

- `folder` — My Drive 루트 기준 경로 템플릿. 없는 폴더는 만든다. 기본 `recly/{{yyyy}}/{{yyyy}}-{{MM}}`(ADR-020).
- 실제 배치는 `{folder}/{base}/` 아래(ADR-014, §3 Drive 배치).
- 녹음에 있는 트랙은 전부 올린다. 고르는 옵션은 없다.
- `includeMeta` — `meta.json` 업로드 여부. 기본 true.
- 성공 조건: 모든 파트 + 메타가 Drive에 있고 `md5Checksum`이 로컬과 일치.
- 멱등: 같은 `{base}` 폴더가 이미 있으면 재사용하고, 같은 이름·같은 md5 파일은 건너뛴다.

#### `webhook`

```json
{ "id": "hook", "type": "webhook",
  "url": "https://example.com/rec",
  "secretRef": "hook_main" }
```

- `url` — `https://` 필수. 예외로 `http://127.0.0.1`, `http://localhost`는 허용(로컬 n8n 등).
- `secretRef` — 서명 키 이름. 없으면 서명 헤더 없이 보낸다.
- payload·서명·재시도 규칙은 §4.
- 이 단계 앞에 `drive.upload`가 있으면 payload의 `files[].drive`가 채워진다. 없으면 `drive`는 null. 앞선
  `transcribe`가 성공했으면 결과 파일도 `files[]`에 들어간다.

#### `transcribe`

```json
{ "id": "stt", "type": "transcribe", "provider": "rtzr", "secretRef": "rtzr_key",
  "language": "ko", "diarize": true, "speakers": { "min": 2, "max": 6 } }
```

필드·provider·결과 파일·폴링 규칙은 §8. 순서 제약: `transcribe`는 앞에 `drive.upload`가 있어야 한다(검증 오류
`TranscribeNeedsUpload`).

### 템플릿 변수

`{{ }}` 안에 아래 이름만 허용한다. 알 수 없는 변수는 검증 오류다.

| 변수 | 값 |
|---|---|
| `yyyy` `MM` `dd` `HH` `mm` | 녹음 `startedAt`을 메타의 `timezone`으로 변환한 값 |
| `title` | 메타 `title`, 없으면 워크플로우 `name` |
| `source` | `watch` / `phone` / `desktop` |
| `recordingId` | 전체 ULID |
| `workflowName` | 워크플로우 `name` |
| `device` | 메타 `deviceName` |

경로에 쓰일 때 `/`, `\`, 제어문자는 `_`로 치환하고 앞뒤 공백을 제거한다.

### 선택 규칙 (ADR-016)

1. 녹음 시작(또는 정지) 시 호출자가 넘긴 `workflowId`가 문서에서 **해석되면** 그것. 셸은 이 자리를 **비워
   보낸다**(2026-09-02: 녹음 단위 임시 선택은 UI에서 사라졌다) — 코어 규칙으로는 남겨 두어 테스트·하네스가 특정
   워크플로우를 지정해 돌릴 수 있다.
2. 없거나 해석되지 않으면 **이 기기에서 사용 중인 워크플로우**(기기 포인터)가 해석될 때 그것.
3. 둘 다 아니면 Job을 만들지 않고 녹음은 `NO_WORKFLOW` 상태로 목록에 남는다.

소스 필터도, `updatedAt` 최신 폴백도 없다. 기기 포인터는 로컬이고 정의는 공유되므로, 다른 기기가 그
워크플로우를 지우면 포인터는 **stale**이 되어 아무것도 선택하지 않는다(= 규칙 3). 이때 셸은 목록·녹음 화면에
"워크플로우를 선택하세요"를 띄운다. 이 기기에서 직접 지운 경우에는 삭제와 함께 포인터도 비워진다.

시딩 기기의 초기 포인터는 **되돌릴 수 있는 추측**이다: 셸이 `seed(선호 스타터)`를 부르면 즉시 적용되지만(첫
기기는 오프라인에서도 동작해야 한다) 추측으로 기록되고, 첫 결정적 동기화가 판정한다 — 원격 문서를 **입양**하면
추측을 회수하고(사용자가 이미 바꾼 포인터는 건드리지 않는다), 이 기기의 push/게시가 확정한다. 입양은
`seededHere`도 지우므로 입양 뒤의 `seed()`는 아예 추측하지 않는다. 기본이 정해지기 **전에** 큐에 들어간
녹음(복구·입양 직후 등)은 `NO_WORKFLOW`로 파킹되고 기존 수동 실행 경로를 따른다 — 재큐잉은 없다.

### 검증 규칙 (validation rules)

- 스키마 통과 + 워크플로우 `id`가 ULID + `name` 1~40자 + `minDurationSec >= 0` + 단계 `id` 유일 +
  템플릿 변수 유효 + `webhook.url` 스킴 규칙 + `transcribe` 순서 제약 +
  `transcribe.provider`가 아는 값(`UnknownProvider`) + `clova`에만 `invokeUrl`.
- 검증 실패한 문서는 **로컬 캐시를 덮어쓰지 않고** UI에 오류를 보인다(동기화로 깨진 정의가 퍼지는 것을 막는다).
- 알 수 없는 필드는 무시하되 다시 쓸 때 보존하지 않는다(forward-compat은 `schema` 버전으로만). 그래서 옛 문서에
  타입 모델이 버릴 필드가 있으면 마이그레이션하지 않고 거부한다(§5 스키마의 `MigrationBlocked`). 예외는
  **폐기된 것을 아는** 필드다 — schema 1..2의 `enabled`·`isDefault`·`trigger`는 이름을 아는 채로 버리므로
  거부 사유가 아니다(§5 스키마).

### 예제

`spec/examples/workflows.json` — "회의"(Drive + 웹훅), "메모"(Drive만), "회의록"(Drive + `transcribe`) 세 개.

---

## 3. 녹음·저장 (구 docs/03)

스키마: [`spec/recording.meta.schema.json`](../spec/recording.meta.schema.json).

### 오디오 설정

| 항목 | 값 |
|---|---|
| 코덱 / 컨테이너 | AAC-LC / `.m4a` (MPEG-4) |
| 샘플레이트 | 16,000 Hz (기기가 미지원이면 44,100 Hz로 폴백하고 메타에 기록) |
| 채널 | 모노 |
| 비트레이트 | 32 kbps |
| 세그먼트 | 명목 900초. 경계는 무손실 — Android는 `setMaxFileSize(900 s × 비트레이트 × 1.07)` + `setNextOutputFile`(`setMaxDuration`은 파일을 넘기지 않고 녹음을 **멈추므로** 쓰지 않는다; 따라서 실제 길이는 바이트 기준으로 ±수 %), Apple은 `AVAudioFile` 교체, Windows는 헬퍼가 PCM을 잘라 ffmpeg에 넘긴다. 파트별 `durationSec`이 정본이고 `audio.segmentSec`은 명목값. 불가피한 공백은 메타 `gaps`에 기록 |
| **트랙** | 모바일·워치: `mono`. 데스크톱: `mic`, `sys`, `mix` — 각각 위 설정, **같은 시작 시각·세그먼트 경계**를 공유한다 |

용량: 트랙당 시간당 14.4 MB, 세그먼트당 3.6 MB. 데스크톱 3트랙 시간당 43 MB. 세그먼트 하나가 3.6 MB이므로
OpenAI 계열의 25 MB 업로드 제한도 통과한다.

### 이름 규칙

```
base  = {yyyyMMdd}T{HHmmss}Z_{source}_{recordingId 앞 8자}
part  = {base}_p{NNN}_{track}.m4a
meta  = {base}.meta.json
```

예:

```
20260826T010000Z_watch_01J9ABCD_p001_mono.m4a
20260826T010000Z_desktop_01J9ZZ12_p003_sys.m4a
20260826T010000Z_desktop_01J9ZZ12.meta.json
```

- 시각은 `startedAt` UTC. `NNN`은 1부터이고, 트랙마다 독립 번호가 아니라 **같은 시간 구간이면 같은 번호**다.
- 파일명에는 제목·기기명 같은 사용자 문자열을 넣지 않는다(경로 안전·기계 파싱용). 제목은 메타와 Drive 폴더
  `description`에 들어간다.
- 워치 녹음은 `_watch_`이고 메타의 `"source": "watch"`와 일치한다.

### 메타데이터

녹음당 `meta.json` 하나. 녹음 중에는 세그먼트 경계마다 다시 쓴다 — **크래시 시 마지막 경계까지는 복구 가능**하고,
정지 전에 죽은 마지막 세그먼트는 컨테이너가 닫히지 않아 읽을 수 없으므로 복구가 `{file}.corrupt`로 격리하고
등록하지 않는다. 읽을 수 있는 파트가 하나도 없는 녹음(격리 파일만 남았거나 아무것도 없는 경우)은 앱에서 할 수 있는
일이 없으므로 행과 디렉터리를 격리 파일째 삭제한다(2026-09-04 사용자 결정; 그 전까지는 수동 복구 여지로 보존했으나
사용자가 손댈 수 없는 `recording` 행만 남았다).
`stop` 후 `status: finalized`.

```json
{
  "schema": 1,
  "recordingId": "01J9ABCDEF0123456789ABCDEF",
  "source": "desktop",
  "platform": "macos",
  "deviceId": "7c1e4b2a-0d3f-4a7e-9b1c-2f5e8d6a4c10",
  "deviceName": "MacBook Pro",
  "workflowId": "01J9ABCDEF0123456789ABCDEF",
  "title": "주간 회의",
  "startedAt": "2026-08-26T01:00:00.000Z",
  "endedAt": "2026-08-26T02:00:12.400Z",
  "durationSec": 3612.4,
  "timezone": "Asia/Seoul",
  "audio": { "codec": "aac-lc", "container": "m4a", "sampleRateHz": 16000, "channels": 1, "bitrateKbps": 32, "segmentSec": 900 },
  "tracks": ["mic", "sys", "mix"],
  "parts": [
    { "part": 1, "track": "mic", "file": "20260826T010000Z_desktop_01J9ABCD_p001_mic.m4a",
      "bytes": 3601234, "sha256": "…", "startOffsetSec": 0, "durationSec": 900 }
  ],
  "gaps": [ { "startSec": 1800.0, "endSec": 1800.3, "reason": "segment_restart" } ],
  "silenced": [ { "startSec": 120.0, "endSec": 125.5, "reason": "mic_taken" } ],
  "context": {
    "app": "us.zoom.xos",
    "participants": 3
  },
  "status": "finalized"
}
```

| 필드 | 비고 |
|---|---|
| `source` | `watch` · `phone` · `desktop` — 워크플로우 트리거와 일치 |
| `platform` | `wearos` · `android` · `watchos` · `ios` · `macos` · `windows` |
| `title` | 선택. 폰·데스크톱 셸에서 사용자가 정지 후 입력하거나 없음(워치는 입력 UI가 없어 항상 없다 — 폰에서 나중에 붙일 수 있다). 상세 화면에서 언제든 바꿀 수 있고 Drive 폴더 `description`을 거쳐 모든 기기에 퍼진다(§3 "다른 기기의 녹음" — 제목) |
| `parts` | 파트 목록. `parts[].sha256`은 세그먼트 확정 직후 계산하고 전송·업로드 검증에 쓴다. `startOffsetSec`이 녹음 시간축의 기준이다 |
| `gaps` | 세그먼트 재시작·인터럽션·tap 재생성 등으로 오디오가 빠진 구간 |
| `silenced` | Android `isClientSilenced`, Apple 인터럽션 등 마이크를 뺏긴 구간. **알려진 한계**: Android에서 정지가 지연되면(등록 못 한 파트가 남아 복구에 맡길 때) 이 구간은 로그에만 남고 메타에는 들어가지 않는다 |
| `context` | 선택. `app`은 감지된 회의 앱의 번들 id로 데스크톱 전용. `participants`(정수, 본인 포함 인원)는 정지 후 다이얼로그 선택으로 채운다 — `transcribe`의 화자 수 힌트(§8). **`context.calendar`는 없다** — 캘린더 읽기는 제품 전체에서 제거됐다 |
| `status` | `recording` → `finalized` → (워치) `transferred` |

인원 선택지는 `2 · 3 · 4 · 5 · 6+ · 모름`이고 기본은 "모름"(unknown)이다 — 고르지 않으면 필드를 생략한다.
값이 없는 것(nil)이 곧 "unknown"이며 워크플로우의 `speakers` 기본값이 적용된다. `6+`는 §8이 화자 힌트를 10명으로
묶는 것과 짝이다.

### 로컬 저장

| 플랫폼 | 경로 |
|---|---|
| Android / Wear | `context.filesDir/recordings/{base}/` |
| iOS / watchOS / macOS | `Application Support/rec/recordings/{base}/` (iOS는 `isExcludedFromBackup = true`) |
| Windows | `%LOCALAPPDATA%\rec\recordings\{base}\` |

워치에서 **수신**한 녹음은 메타가 마지막에 도착해 `{base}`를 미리 알 수 없으므로 폰은 `recordings/{recordingId}/`에
둔다(디렉터리명은 DB 행에 저장; Drive 배치는 여전히 `{base}`). 수신의 `upsertRecording`은 행을 통째로 바꾸되
`drive_folder_id`는 남긴다 — 폰이 이미 올린 뒤 워치가 재전송해도 폴더를 잊지 않는다.

### 보관 · 삭제

#### 자동 보관 (ADR-017)

파트 파일은 다음이 **모두** 참일 때만 삭제한다 — ① Job이 DONE, ② 그 워크플로우에 `drive.upload` 단계가 1개 이상
있고 전부 SUCCEEDED(`continue`로 건너뛴 실패 업로드는 보관), ③ 같은 녹음의 **모든** Job이
DONE(FAILED·SKIPPED_SHORT·NEEDS_AUTH·NEEDS_SPACE도 삭제를 막는다 — `retry()`에 파트가 필요하므로), ④ 파일
mtime과 마지막 DONE 시각 둘 다 **7일** 경과(매 잡 패스의 `Retention.sweep`이 재평가한다). 그 외에는 보관하고 목록에 "업로드 안 됨"을 표시한다. 웹훅만 있는 워크플로우는 원본을
지우지 않는다. **워치는 폰 ack 즉시 삭제**한다.

**지우지 않는 것**: `meta.json`, DB 행(`recording`·`part`(`deleted=1` 표시)·`job`·`step_run`), 그리고
`transcribe`가 만든 로컬 사본(`{base}.transcript.json/.txt` — 상세 화면의 입력이라 파트 보관 규칙과 무관하게
남긴다, §8). 그래서 파트가 지워진 뒤에도 목록·상세·웹훅 재전송에 필요한 것은
전부 로컬에 있다. 이 자동 삭제에는 **업로드 성공 뒤 7일**의 창이 있다(2026-09-03, 고정값·설정 없음): 시간만
지났다고 지우는 규칙은 여전히 없고, "업로드가 끝났고 7일이 지났다"일 때만 매 잡 패스의 보관 스윕이 지운다. 그
사이에는 상세의 재생이 로컬 파트를 그대로 쓰고, 지워진 뒤에는 Drive에서 받아 다시 7일을 둔다. 사용자가 지우는
것은 언제나 즉시다.

#### 앱에서 지우기 — "녹음 삭제"

목록 행의 삭제. **한 번에 하나의 녹음**이고, 누를 때마다 Drive를 어떻게 할지 묻는다.

| 대상 | 동작 |
|---|---|
| 로컬 파트·`meta.json`·결과 파일(transcript)·녹음 디렉터리 | 항상 삭제 |
| DB 행 (`recording`, `part`, `job`, `step_run`) | 항상 삭제 |
| Drive의 `{base}/` 폴더와 그 안의 파일 | **사용자가 고른다. 기본값은 "Drive에 남기기"** |

- 확인 다이얼로그는 두 갈래를 한 화면에 보여준다: `로컬만 삭제`(기본값) / `Drive 폴더도 삭제`. 기본값이 "남기기"인
  이유는 Drive의 파일이 이미 **사용자의 것**이고 다운스트림 자동화가 그 폴더를 이미 소비했을 수 있기 때문이다.
  **되돌릴 수 없는 쪽을 기본값으로 두지 않는다.**
- 아직 업로드되지 않은 파트가 있으면(보관 규칙이 보관 중) 다이얼로그가 그 사실을 먼저 말한다 — "아직 Drive에
  올라가지 않은 파트 3개가 함께 지워집니다".
- 실행 중(`RUNNING`)인 Job이 있으면 삭제하지 않고 "실행이 끝난 뒤 다시 시도하세요"로 거절한다.
  `WAITING`/`NEEDS_AUTH`/`NEEDS_SPACE`/`FAILED`는 Job을 함께 지우므로 삭제해도 된다.
- **"Drive에서도 삭제"**는 `files.delete`다. **폴더 id는 그 녹음의 `drive.upload` 단계가 남긴 것에서만
  읽는다** — `output_json.folderId`(끝났거나 파킹된 경우), 없으면 재개 상태 `state_json.folderId`.
  **`drive_folder_cache`는 쓰지 않는다**: 그 캐시의 키는 렌더된 *경로*(`recly/2026/2026-08`)라 그 달의 다른 녹음이
  전부 공유하는 상위 폴더이고, 한 녹음을 지우면서 그것을 지우면 안 된다. 실패하면 로컬 삭제는 그대로 진행하고
  "Drive에서 지우지 못했습니다"를 남긴다 — 로컬을 지우고 나면 다시 시도할 근거가 없으므로 사용자에게 Drive 링크를
  함께 보여준다.
- **삭제가 다른 기기에 닿는 길은 Drive뿐이다.** "Drive 폴더도 삭제"로 지운 녹음은 그 폴더를 **입양**했던 다른
  기기(아래 "다른 기기의 녹음")의 목록에서 다음 조회 때 사라진다. 그 녹음을 **직접 만든** 기기의 행은 남는다 —
  그 기기의 원본·Job 기록이고 Drive가 지울 권한이 없다. "로컬만 삭제"는 어느 기기에도 닿지 않는다.
- **입양한 행의 삭제**는 Drive 삭제뿐이다. 이 기기에는 캐시밖에 없어 "로컬만 삭제"는 다음 조회에서 되살아나는
  일이라 다이얼로그가 그 선택지를 주지 않고, "다른 기기에서 녹음한 것입니다. 지우면 Drive와 모든 기기에서
  사라집니다"를 말한 뒤 `delete(id, deleteDrive = true)`를 부른다. 폴더 id는 `recording.drive_folder_id`다.

**코어 규칙** — `RecordingRepository.delete(recordingId, deleteDrive): DeleteResult`:

- 결과는 셋뿐이다. `Deleted(driveDeleted, driveError)` / `Busy`(`RUNNING` Job이 있어 **아무것도 건드리지 않음**) /
  `NotFound`.
- **행 찾기·`RUNNING` 검사·폴더 id 읽기·네 테이블(`step_run`·`job`·`part`·`recording`) 삭제가 한 트랜잭션**이다.
  스키마에 FK도 CASCADE도 없으므로 테이블을 하나씩 이름으로 지운다(`step_run`이 먼저인 것은 그 쿼리가 아직 남아
  있는 `job` 행을 타고 들어가기 때문이다). 디렉터리 삭제는 같은 잠금 구간 안에서 커밋 직후에 한다 — 커밋과 파일
  삭제 사이에 취소가 끼어 행이 가리키지 않는 디렉터리가 남는 일을 막는다.
- 이 트랜잭션과 `JobStore.claimRunning`은 SQLite 단일 라이터가 갈라 준다: 둘 중 하나가 먼저 커밋하므로 **"Job이
  `RUNNING`이어서 `Busy`" 아니면 "행이 이미 없어서 클레임할 것이 없음"** 둘 중 하나이고, 둘 다인 경우는 없다.
- Drive `files.delete`는 커밋 뒤에 부른다. 실패는 `Deleted.driveError`로 보고할 뿐 로컬 삭제를 되돌리지 않는다.

#### 계정에서 떼기 — "로그아웃" vs "연결 해제"

두 가지는 다른 동작이고 UI에서 다른 문구를 쓴다.

| | 로그아웃(이 기기) | 연결 해제 |
|---|---|---|
| 목적 | 이 기기에서 그만 쓰기 | Recly가 내 Drive에 접근하지 못하게 하기 |
| 토큰 | 이 기기의 access/refresh token 삭제 | 같음 + Google grant 취소(revoke) |
| 다른 기기 | 영향 없음 | **함께 끊긴다** — revoke는 클라이언트가 아니라 **Cloud 프로젝트 단위**라서 폰에서 눌러도 Mac·PC의 grant가 사라진다(§6) |
| Drive의 녹음 파일 | 그대로 | **그대로** — 사용자의 파일이고 앱이 지울 이유가 없다 |
| 워크플로우 문서·기기 기본값 | 그대로 | **그대로.** 계정에서 파생된 것이 아니라 이 기기의 설정이고(§5), 지우면 어디에서도 되찾을 수 없다 |
| 로컬 시크릿(웹훅·STT 키) | 남는다 | **남는다** — 같은 이유다. 계정을 떼는 결정이 사용자가 입력한 키를 지울 이유가 되지 않는다 |
| 로컬 Job·step_run | 남는다(재로그인하면 이어서 실행) | **삭제**(+ `drive_folder_cache`와 "로컬만 삭제" 기록 `remote/ignored/*`도 비움). 예외: "녹음도 함께 삭제"에서 `RUNNING` 때문에 남은 녹음의 Job은 **함께 남긴다** — 진행 중인 실행이 쓸 행이 사라지면 안 된다 |
| 로컬 녹음 파일·`meta.json` | 남는다 | **남는다.** 아직 올라가지 않은 원본을 이 동작으로 지우지 않는다(원칙 3). 다이얼로그가 "Drive에 올라가지 않은 녹음 N건이 이 기기에 남습니다"를 알리고, 사용자가 원하면 **"녹음도 함께 삭제"**를 따로 체크한다 |

- **연결 해제 다이얼로그는 "다른 기기도 함께 끊긴다"를 반드시 보여준다.** 한 기기만 떼려는 사용자는 로그아웃이
  맞고, **안내에 Google 계정 설정(<https://myaccount.google.com/permissions>)에서 직접 해제하는 방법도 함께
  적는다.**
- 기본 로그아웃 경로에서는 revoke를 **부르지 않는다** — 그 이유와 인용은 §6(iOS·macOS `signOut` vs `disconnect`,
  Windows revoke 절)에 있다.

**연결 해제는 두 반쪽이다**: grant revoke(셸의 플랫폼 SDK 몫)와 로컬 정리(`ReclyCore.disconnect(alsoDeleteRecordings):
DisconnectResult`, 코어 몫). 코어는 revoke를 부를 수단이 없으므로 셸이 둘을 **같이** 부른다.

- **정지 상태에서 돈다.** 로컬 정리는 전부 `Executor.quiesced` 안이다 — 이미 실행 중인 Job은 지금 단계를 마치고
  멈추고, 그 뒤에야 무언가가 지워진다. 캐시된 access token을 먼저 무효화한 다음(`tokenProvider.invalidate()`)
  `tokens` 네임스페이스를 비운다 — 반대 순서면 셸이 메모리에 들고 있던 토큰이 다음 실행에 넘어간다.
- **지우는 것은 넷뿐이다**: `tokens` 네임스페이스, 큐(`job`·`step_run`), Drive 폴더 캐시, "로컬만 삭제" 기록(`kv` `remote/ignored/*`, §3 "다른 기기의 녹음"). 워크플로우 문서·기기 기본
  워크플로우·`secrets` 네임스페이스는 그대로 둔다(위 표).
- **`DisconnectResult(deletedRecordings, busyRecordings)`.** "녹음도 함께 삭제"를 골랐을 때 `RUNNING` Job 때문에
  지우지 못한 녹음의 id가 `busyRecordings`에 담긴다. 그 녹음과 **그 Job 행은 남기고** 화면은 그 사실을 말한다 —
  Job이 끝난 뒤 다시 누르면 그때 지워진다.
- **`DisconnectPhase` — `NONE` → `REVOKE_PENDING` → `REVOKED_CLEANUP_OWED` → `NONE`.** 셸의 설정 저장소에
  남긴다(재시도가 다음 실행일 수 있고, 그때는 토큰이 이미 없다). 순서가 요점이다: `REVOKE_PENDING`은 revoke를
  **부르기 전에** 쓴다(revoke가 이 기기의 자격 증명을 지우므로, 뒤에 쓰면 그 자격 증명과 함께 잃는다).
  `REVOKED_CLEANUP_OWED`는 revoke가 돌아온 뒤·**로컬 정리를 부르기 전에** 쓰고, 정리가 끝나고 `busyRecordings`가
  비었을 때에만 `NONE`으로 지운다. 예외가 던져지면 단계는 그대로 남는다 — 그것이 정직한 상태다. 단계·빚이 저장소에
  쓰이지 않으면 자격 증명을 건드리지 않는다.
- **재시도가 남의 grant를 지우지 않는다.** `REVOKED_CLEANUP_OWED`에서의 재시도는 revoke를 **건너뛰고** 밀린 로컬
  정리만 한다(그 grant는 이미 없다). `REVOKE_PENDING`에서의 재시도는 토큰 저장소에 물어본다 — refresh token이 남아
  있으면 revoke가 일어나지 않은 것이므로 다시 부르고, 없으면 정리로 넘어간다. 단계가 밀려 있는 동안 **로그인·로그아웃을
  보류한다**(빈 자리에 다른 계정이 들어오면 그 계정의 grant를 지우게 된다).
- **revoke debt.** revoke가 실패하면 그 사실을 **토큰을 지우기 전에** 저장소에 기록한다(단계만으로는 못 잡는다:
  실패 분기도 refresh token을 지우므로, 그 사이에 죽으면 단계는 `REVOKE_PENDING`인데 토큰이 없어 "revoke가 됐다"로
  잘못 읽힌다). 빚이 서 있는 동안 화면은 "Google 계정에 아직 남아 있습니다"(Google still lists Recly) + 권한 페이지
  링크를 말하고, **빚을 지우는 것은 사용자의 확인뿐이다** — 나중에 성공한 revoke도 그것을 지우지 않는다. 앱은 계정
  신원을 갖고 있지 않아, 방금 지운 grant가 빚이 가리키는 그 계정의 것인지 알 수 없기 때문이다.
- **`DisconnectGate` — 연결 해제 중에는 녹음 시작을 거절한다.** 다이얼로그를 열 때 본 "지금 녹음 중" 값은
  revoke(네트워크 왕복)를 기다리는 사이에 낡는다. 그동안 타일·위젯·단축키·트레이 메뉴·회의 감지가 새 녹음을 시작할
  수 있고, 그 녹음에는 아직 Job이 없어 코어의 `Busy` 검사에 걸리지 않는다 — 정리가 녹음기가 쓰고 있는 파일을 지우게
  된다. 그래서 앱 전역 게이트를 두고, 연결 해제는 revoke 전부터 정리 후까지 그것을 잡는다. 시작은 **큐에 서지 않고
  거절**되고("연결 해제 중입니다") 이유를 말한다(§12: 대신 멈춰 주지 않는다). 확인 버튼도 녹음 중이면 비활성이고,
  확인 시점에 한 번 더 읽는다.
- 게이트 판정과 실제 캡처 시작은 **같은 임계 구역**이어야 한다 — 판정 후 캡처가 열리기 전에 (워크플로우 목록 조회
  등으로) 중단되면 그 사이에 연결 해제가 게이트를 가져갈 수 있다. Windows는 `DisconnectGate.ifOpen { start() }`,
  Android는 양쪽 플래그 쓰기가 상대의 읽기보다 앞서고 사이에 중단 지점이 없는 구조로, Apple은 MainActor의
  `tryLock`으로 성립시킨다. Apple에는 규칙 둘이 더 있다 — 복원 결과가 불확실하면(`GoogleRestoration.failed`) 단계를
  유지하고 다시 시도하게 하며, `UserDefaults` flush가 실패하면 자격 증명을 건드리지 않는다.

### Drive 배치 (ADR-014)

```
My Drive/
  {folder 템플릿 결과}/               예: recly/2026/2026-08/
    {base}/                           예: 20260826T010000Z_desktop_01J9ABCD/
      {base}_p001_mic.m4a
      {base}_p001_sys.m4a
      {base}_p001_mix.m4a
      …
      {base}.meta.json
      {base}.transcript.json / .txt   (transcribe 단계를 넣었을 때)
```

- 폴더는 `drive.file` 스코프로 앱이 만든 것이라 앱이 다시 찾을 수 있다. 폴더 ID는 로컬 DB에 캐시한다.
- `{base}` 폴더의 `description`에 `title`을, `appProperties`에 `recordingId`·`workflowId`를 기록한다. `description`은
  이후 이름 바꾸기가 갱신하는 **제목의 정본**이다(아래 "다른 기기의 녹음" — 제목).
- 업로드 완료 판정은 Drive 파일의 `md5Checksum`과 로컬 md5 비교(sha256은 전송 검증용, md5는 Drive가 주는 값).
- 파트 업로드 순서: 트랙별 1번부터. `meta.json`은 마지막에 올려서 "meta가 있으면 완료"를 다운스트림 트리거 조건으로
  쓸 수 있게 한다.

### 다른 기기의 녹음 (ADR-023)

같은 계정으로 로그인한 폰·데스크톱은 **같은 녹음 목록**을 본다. 서버도 색인 파일도 없다 — Drive가 이미 그
인덱스다: 위 배치가 녹음마다 `recordingId`를 `appProperties`에 찍은 `{base}/` 폴더 하나를 남기므로, 폴더 조회 한
번(`mimeType = folder and trashed = false` — `drive.file`이라 앱이 만든 폴더만 나오고, 그중 `appProperties.recordingId`가
있는 것이 녹음 폴더다; `appProperties has { key=… }`는 value 없이는 Drive가 400으로 거부한다, 2026-09-04 실계정 확인)이
이 계정의 모든 녹음이다.
워크플로우마다 폴더 템플릿이 달라도 경로를 걷지 않으니 상관없다. 코어 `RemoteRecordings.pull()`:

1. 폴더를 나열해 `recordingId`로 묶는다. 로컬 DB에 그 id의 행이 있으면 건너뛴다(이 기기가 만든 것이든 이미 입양한
   것이든) — **다만 아래 3의 잠정 행이면 건너뛰지 않고 그 행을 완성한다**. `remote = 0`인 행은 무슨 일이 있어도
   건드리지 않는다.
2. 모르는 id는 폴더의 자식을 한 번 나열해 `{base}.meta.json`을 받고(같은 id의 폴더가 둘이면 — 다른 경로로 재실행 —
   최신 것 중 완료된 것), `recording` 행을 **입양**한다(`RecordingRepository.adopt`): `remote = 1`,
   `drive_folder_id`에 폴더, `part` 행은 처음부터 `deleted = 1`에 `drive_file_id`. 디렉터리는 워치 수신과 같은
   `recordings/{recordingId}/`이고 `meta.json`을 써 둔다. **메타는 경로를 만든다**(디렉터리명·파트 파일명)**므로
   스키마대로만 받는다**: `recordingId`가 폴더의 것과 같고 ULID 형식이며, 모든 `parts[].file`이 이름 규칙이 주는
   그 이름일 때만 — 아니면 `remote.meta.mismatch`로 거절.
3. **`meta.json`이 없는 폴더는 "다른 기기가 업로드 중"이다**(2026-09-04). 메타는 마지막에 올라가므로(위 Drive 배치)
   그 폴더는 지금 올라가는 중인 녹음이고, 다음 조회로 미루면 20분짜리 업로드가 끝날 때까지 사용자는 빈 목록을 본다.
   그래서 나열이 준 것만으로 **잠정 행**을 연다: `remote = 1`, `drive_folder_id`에 그 폴더, `meta`는 폴더 이름
   `{yyyyMMddTHHmmssZ}_{source}_{ulid8}`에서 읽은 `source`(모르면 `phone`)와 `recordingId`의 ULID 타임스탬프에서
   읽은 `startedAt`, 제목은 폴더 `description`, `tracks`·`parts`는 비고 `status = recording`. `meta.json`은 입양과
   같은 자리에 써 둔다. 셸이 보는 것은 `RecordingRecord.remoteUploading`이다. 폴더의 `recordingId`가 ULID가 아니면
   열지 않는다 — 그 값이 곧 디렉터리 이름이다. **버려진 업로드**: `createdTime`이 24시간을 넘긴 메타 없는 폴더는
   입양하지 않고, 이미 연 잠정 행도 그 나이가 되면 폴더가 사라졌을 때와 같은 길로 지운다(`drop`). 나중 조회가 그
   폴더에서 `meta.json`을 찾으면 잠정 행을 **진짜 메타로 갈아 끼운다**(`finalized`·파트·`drive_file_id`) — 1의 예외가
   이것이다.
4. **폴더의 `pending` 표식**: 업로드 뒤에 아직 할 일이 남은 기기가 그것을 폴더에 적는다 — `appProperties.pending`은
   업로드 다음 단계들의 `type`을 쉼표로 이은 것(`transcribe`, `transcribe,webhook`, 없으면 빈 문자열),
   `appProperties.pendingAt`은 그렇게 적은 시각. 쓰는 쪽은 `drive.upload`가 폴더를 만들거나 찾은 직후(첫 바이트보다
   먼저)와, 실행기가 단계 하나를 마칠 때마다(남은 것)와 잡이 `DONE`·터미널 `FAILED`가 될 때(빈 값)다. `files.update`의
   `appProperties`는 병합이라 폴더의 `recordingId`는 그대로 남는다. 표식은 **참고용**이라 실패하면
   `drive.marker.failed`로 남기고 넘어간다. 읽는 쪽은 이 조회다: 모든 **remote** 행의 `recording.remote_pending`을
   그 폴더의 표식으로 채우고(`RecordingRecord.remotePending`), 표식이 없거나 비었거나 `pendingAt`이 **8시간**(§8의
   가장 긴 provider 결과 타임아웃)보다 오래됐으면 NULL이다. `remote = 0`인 행은 절대 받지 않는다 — 자기 잡이 정본이다.
5. 이전에 입양했는데 그 **폴더**가 이제 나열되지 않으면(다른 기기가 지웠거나, 사용자가 Drive에서 지웠거나, 재실행이
   다른 폴더로 대체) 행과 캐시를 지운다(`drop` — 트랜잭션 안에서 `drive_folder_id`가 그 폴더인지 확인하고 지우므로
   그 사이 워치 전송이 같은 id를 이 기기 것으로 만들었다면 건드리지 않는다). 지우는 것이 입양보다 먼저라 같은 id가
   다른 폴더에 남아 있으면 그 폴더에서 다시 입양된다. 입양한 행의 폴더보다 **새 폴더가 나중에 완료되면**(재실행이
   끝남) 그쪽으로 옮긴다 — 그 id의 폴더가 둘 이상일 때만 새 폴더의 자식을 한 번 더 본다. 나열이 끝까지 성공했을
   때만 — 실패한 조회는 아무것도 지우지 않는다.
6. **"로컬만 삭제"는 기억한다.** 이 기기가 올린 녹음을 Drive 폴더는 남기고 지우면 그 폴더는 계속 나열되므로, 아니면
   다음 조회가 방금 지운 녹음을 "다른 기기"로 되살린다. 그래서 `delete(deleteDrive = false)`가 삭제 트랜잭션 안에서
   `kv`에 `remote/ignored/{recordingId}` → 폴더 id를 남기고, 조회는 그 폴더가 나열되는 동안 그 id를 입양하지 않는다
   (`adopt` 자체도 트랜잭션 안에서 이 기록을 확인하므로 조회 도중에 끼어든 삭제도 되살아나지 않는다). 폴더가
   Drive에서 사라지면 기록도 지운다. "연결 해제"는 이 기록을 전부 비운다 — 계정을 다시 붙이면 새 기기처럼 Drive에
   있는 것을 다 본다. 이 기기가 올린 녹음의 폴더 id는 `drive.upload`가 폴더를 만들거나 찾는 순간 **행의
   `drive_folder_id`에도** 적는다 — 연결 해제가 큐(step 출력)를 비운 뒤에도 "로컬만 삭제"가 어느 폴더를 남겼는지
   알아야 하기 때문이다(그 전에 올린 녹음은 step 출력에서 읽고, 없으면 기록 없이 지워진다 — 다음 조회에 다시 보인다).

입양한 행의 성질:

- **Job이 없고 생기지 않는다.** `enqueue`는 `PartsPurged`를 돌려준다(Drive가 이미 갖고 있고 이 기기엔 보낼 원본이
  없다). 복구 스캔이 "Job 없는 finalized 행"으로 보고 enqueue를 불러도 같은 답이다. 목록의 상태는 `NO_JOB`이 아니라
  **`DONE`**이다 — 끝난 녹음과 똑같이 보이고(2026-09-04 사용자 결정: 영구 상태 "다른 기기"는 무의미), 세부·삭제
  외의 동작(재시도·올리기·Drive 링크)은 없다. 다른 기기의 것임은 삭제 다이얼로그만 안다(`remote` 플래그).
- **`uploaded()`는 참.** Drive에서 온 것이니 정의상 올라가 있다. 삭제 경고의 "안 올라간 파트"는 0이다.
- **재생·전사는 Drive에서.** `AudioParts`는 업로드 출력 대신 `part.drive_file_id`로 받고, `RecordingResults`는
  폴더에서 `{base}.transcript.json`을 이름으로 찾는다(다른 기기가 나중에 전사해도 다음 열 때 보인다). 받은 파트는
  여느 캐시처럼 7일 뒤 보관 스윕이 지운다 — Job이 없으므로 파일 mtime만으로 익힌다(`claimPurge`의 입양 분기).
- **제목은 Drive 폴더의 `description`이 정본이다**(아래 "제목").
- **잠정 행도 같은 성질을 그대로 쓴다.** `remote = 1`이라 Job이 없고 `uploaded()`는 참이며 삭제·`enqueue`도 입양한
  행과 같다. 다른 것은 아직 파트가 없다는 것뿐이고(재생할 것이 없다), 제목 바꾸기는 `status = recording`이라
  거절된다 — 메타가 도착하면 그때부터 된다.

셸이 "지금 무슨 일이 벌어지고 있나"를 그리는 데 쓰는 것은 `RecordingRecord`의 세 값이다. 셋 다 이 기기의 Job이
아니므로 큐에서는 읽을 수 없다.

| 값 | 뜻 |
|---|---|
| `receiving` | 워치 전송이 오는 중(`remote = 0` · `source = watch` · `status = recording`) — 폰은 `source = watch`로 녹음하지 않으므로 이 모양은 전송뿐이다. 행의 `startedAt`은 **워치가 만든 `recordingId`의 ULID 타임스탬프**다(§1 "식별자·시간"): 20분짜리를 끝나고 넘겨받아도 목록에서 제자리에 앉는다 |
| `remoteUploading` | 다른 기기가 업로드 중(`remote = 1` · `status = recording`) — 위 3의 잠정 행 |
| `remotePending` | 다른 기기가 업로드 뒤에 아직 할 일(`transcribe` 등) — 위 4의 표식 |

#### 제목

정지 직후의 제목 입력(`updateTitle`, Job이 돌기 전까지만)과 별개로, **상세 화면에서 언제든 이름을 바꿀 수 있다**
(`ReclyCore.rename(recordingId, title)`; 이 기기의 녹음이든 입양한 녹음이든, 빈 문자열은 "제목 없음" = 타임스탬프
이름). 한 번 올라간 제목이 기기마다 달라지지 않게 하는 규칙:

- **로컬은 즉시.** 행·`meta.json`을 바로 쓰고 `kv`에 `title/pending/{recordingId}` → 제목을 남긴다. 목록은
  `recordings.observe()`로 곧 갱신된다.
- **Drive로 밀기**(`RemoteRecordings.pushTitles`): 폴더 `description`을 `files.update`로 바꾸고, 폴더 안
  `{base}.meta.json`을 로컬 메타로 덮어쓴다(`updateMedia`). **둘 다** 됐을 때만 pending을 지운다 — 그 사이 또 바꿨으면
  값이 달라 남는다(`kvDeleteIfValue`). push는 한 번에 하나만 돈다(뮤텍스; 연속 두 번의 이름 바꾸기가 뒤바뀐 순서로
  Drive에 닿지 않도록). `rename` 직후 한 번, 그리고 매 조회(잡 패스) 끝에 다시 시도한다. 폴더를 아직 모르는
  녹음(업로드 전)은 기다리고, 폴더는 알지만 `meta.json`이 아직 없는 녹음(업로드 진행 중)도 기다린다 — 진행 중인
  업로드가 옛 제목의 meta를 올릴 수 있어 그 뒤의 패스가 고쳐야 한다. 폴더가 404면 다른 기기가 지운 것이라 pending을
  접는다. 녹음을 지우면(어느 삭제든) 그 pending도 같이 지운다 — 나중에 같은 폴더를 다시 입양했을 때 낡은 제목을
  밀어 넣지 않도록.
- **Drive에서 받기.** 조회가 받는 폴더 목록의 `description`이 곧 제목이다(추가 요청 없음; Drive는 `fields`에
  적은 것만 주므로 `RECORDING_FOLDER_FIELDS`에 `description`이 들어 있다). 행의 제목과 다르면 바꾼다
  (`applyTitle`; 입양 행이든 이 기기 것이든). 단 그 녹음에 pending push가 있으면 **이 기기의 변경이 이긴다**. 행이
  자기 폴더를 알면 그 폴더의 것을, 모르면(이 버전 전 업로드) 최신 폴더의 것을 읽고 **그 폴더를 행에 기억해 둔다**
  (`rememberFolder`; 알던 폴더는 덮지 않는다) — 그래야 그 녹음의 이름 바꾸기가 갈 곳이 생긴다. `description`이 비어
  있으면 아무것도 하지 않는다 — 제목을 지우는 것은 기기 간에 전파되지 않는다(이름 바꾸기만 전파된다).
- 두 기기가 동시에 바꾸면 나중에 Drive에 쓴 쪽이 남는다. 이상은 없다.

언제 조회하나: **모든 잡 패스**(`ReclyCore.runDueJobs` 끝, 2분 스로틀)와 **목록이 화면에 올 때**(셸이
`pullRemoteRecordings(force = true)`, 화면을 막지 않고 뒤에서). 스로틀은 **다른 기기가 뭔가 하고 있는 동안에는
30초**다 — `remoteUploading`이거나 `remotePending`이 있는 행이 하나라도 있으면(조회 시점에 DB에 묻는다; 새 상태를
두지 않는다) 진행 중이라고 말한 목록이 곧 말을 바꿀 수 있어야 한다. 셸의 목록은 `jobs.observe()` 외에
`recordings.observe()`(recording 테이블 변경)도 구독해 입양·잠정 행·완성·표식 변경·삭제가 곧바로 반영된다.
로그인이 없으면 조회는 조용히 건너뛴다(`skipped = "auth"`).

**전제**: `drive.file` 스코프에서 다른 클라이언트 ID(폰의 Android 클라이언트, Mac의 iOS 클라이언트…)가 만든 파일이
같은 Cloud 프로젝트 안에서 서로 보여야 한다. 코드는 그 전에도 이것을 전제했다 — 월 폴더 `recly/2026/2026-09`를
`findChild`로 찾아 재사용하므로, 아니라면 기기마다 같은 이름의 폴더가 중복 생성됐을 것이다. 공식 문서는 단위를
명시하지 않으니 **실계정에서 확인한다**: Drive에 같은 달 폴더가 하나뿐이면 성립. 보이지 않는다면 이 조회는 빈
목록을 돌려줄 뿐 아무것도 깨뜨리지 않고, 대안은 전체 `drive` 스코프뿐이라 ADR-009와 충돌한다.

### 워치 → 폰 전송 계약

- 단위: 파트 파일 하나 + 마지막에 `meta.json`. Android Data Layer 경로:
  `/rec/part/{recordingId}/{part}/{track}/{sha256}/{file}`, `/rec/meta/{recordingId}` — 파일명을 경로에 싣는 이유는
  메타(=`{base}`)가 마지막에 도착하기 때문이다. Apple은 같은 값들을 `WCSession.transferFile`의 `metadata`
  딕셔너리에 싣는다.
- **채널은 `onOutputClosed` 뒤에 닫는다**(2026-09-04, Watch7 실기에서 발견). `ChannelClient.sendFile`의 Task는
  전송 완료가 아니라 **요청 수락** 시점에 끝나고, 공식 문서는 "그 직후 채널을 닫지 말고 `onOutputClosed`로 완료를
  알라"고 한다. 바로 닫으면 CLOSE가 데이터 뒤에 줄을 서고 폰은 `onInputClosed(CLOSE_REASON_REMOTE_CLOSE)`를 받아
  파일을 버리고 ack하지 않았다(3.8 MB 파트가 오후 내 6번 전송, ack 0). 워치는 콜백을 첫 바이트 전에 등록하고
  `CLOSE_REASON_NORMAL`을 기다린 뒤 닫는다(다른 사유는 링크 실패 = `STALLED`). 폰은 `NORMAL`·`REMOTE_CLOSE` 둘 다
  "전부 도착"으로 받아들이고 sha256이 최종 판정이다 — 끊김·타임아웃·로컬 close는 잘린 파일이라 버린다.
- **폰의 리스너 서비스는 이벤트마다 새 인스턴스다**(2026-09-04, Z Fold7 실기에서 발견). Play Services는
  `onChannelOpened`·`onInputClosed`·`onChannelClosed`를 각각 새로 만든 `WearableListenerService` 인스턴스에 전달하고
  1.5초 뒤 파괴한다. 그래서 `onChannelOpened`에서 인스턴스 필드에 적어 둔 "받는 중인 채널" 목록은 `onInputClosed`
  때 비어 있었고, 캐시에 다 받아 둔 파일을 아무 로그 없이 무시했다(폰 ack 0건의 두 번째 원인). 스테이징 파일 경로는
  채널 경로만의 함수(`cache/rec-transfer/{recordingId}/{file}`)이고 `onInputClosed`는 그것을 다시 계산한다 — 두
  콜백 사이에 메모리 상태를 두지 않는다.
- 각 파트마다 폰이 sha256을 검증하고 `{recordingId, part, track, ok}` ack를 보낸다.
- 워치는 파트 ack를 **기록만** 하고 파일은 유지한다; `ack-meta ok:true`를 받은 뒤에야 파트·메타·디렉터리·로컬 행을
  삭제한다(뒤 파트나 메타가 치명 nack면 앞서 ack된 파트까지 남아 있어야 재전송·복구가 가능하다).
  `SHA256_MISMATCH` nack는 그 파트를 1회 재전송하고, 두 번째면 치명이다.
- 폰은 메타를 받은 시점에 `recordings`에 등록하고 Job을 만든다. 메타 본문의 `recordingId`는 경로의 것과 일치해야
  하며(불일치 → `RECORDING_ID_MISMATCH` nack, 코어 호출 없음), **`ack-meta ok:true`는 enqueue와 실행기 깨우기까지
  끝난 뒤에만** 보낸다 — 그 전에 실패하면 ack하지 않고 워치의 재전송에 맡긴다(`acceptMeta`·`enqueue`는 멱등).
  메타 없이 파트만 온 상태로 24시간이 지나면 고아 파트를 삭제한다.
- **알려진 한계**: `ack-meta ok:true`가 유실되면 워치는 메타를 재전송하고, 폰이 이미 업로드·정리해
  `Incomplete(전 파트)`를 돌려주더라도 워치는 파트를 아직 갖고 있으므로(ok 전 삭제 금지) 파트·메타를 다시 보내
  수렴한다(폰의 `acceptPart`는 덮어쓰기, `enqueue`는 `AlreadyDone`). `ack-meta ok:false`에서 워치가 완료 처리하는
  경우는 없다; 요구된 파트가 로컬에 없으면(외부 삭제) `PART_MISSING_LOCALLY`로 실패 표시하고 나머지는 보존한다.
- 전송 중 녹음 시작 시각·`recordingId`는 워치가 만든 값을 그대로 쓴다(폰이 재생성하지 않는다). 메타가 오기 전의
  임시 행(`TransferReceiver.placeholder`)도 마찬가지로 시작 시각을 **`recordingId`의 ULID 타임스탬프**에서 읽는다
  — 도착 시각을 쓰면 20분짜리를 끝나고 받은 폰의 목록에서 그 녹음만 맨 위로 튀어 오른다. 셸이 이 행을 알아보는
  것은 `RecordingRecord.receiving`이다(§3 "다른 기기의 녹음" 끝의 표).

---

## 4. 웹훅 (구 docs/04)

스키마: [`spec/webhook.payload.schema.json`](../spec/webhook.payload.schema.json).

### 요청

```
POST {url}
content-type: application/json
user-agent: rec/{version} ({platform})
webhook-id: {stepRunId ULID}
webhook-timestamp: {unix seconds}
webhook-signature: v1,{base64(HMAC-SHA256(secret, "{webhook-id}.{webhook-timestamp}.{body}"))}
```

- 서명은 [Standard Webhooks](https://www.standardwebhooks.com/) 그대로(ADR-010). `secretRef`가 없으면
  `webhook-signature` 헤더를 생략한다.
- 시크릿은 보안 저장소의 원문 바이트. UI에서 생성 버튼으로 32바이트 랜덤을 만들어 `whsec_` + base64로 보여준다
  (Standard Webhooks 관례). 서명에는 `whsec_` 접두를 뗀 뒤 base64 디코드한 바이트를 쓴다. 생성된 값은 **그때 한
  번만 보인다**.
- 재시도할 때 `webhook-id`는 같고 `webhook-timestamp`와 서명은 새로 만든다. 수신 측은 `webhook-id`로 dedupe한다.
- 타임아웃 30초. 리다이렉트는 따르지 않는다.

### payload

```json
{
  "type": "recording.completed",
  "id": "01J9STEPR0N0123456789ABCDE",
  "timestamp": "2026-08-26T02:05:00.000Z",
  "data": {
    "recording": {
      "recordingId": "01J9ABCDEF0123456789ABCDEF",
      "source": "desktop", "platform": "macos",
      "title": "주간 회의",
      "startedAt": "2026-08-26T01:00:00.000Z", "endedAt": "2026-08-26T02:00:12.400Z",
      "durationSec": 3612.4, "timezone": "Asia/Seoul",
      "tracks": ["mic", "sys", "mix"],
      "context": { "app": "us.zoom.xos", "participants": 3 }
    },
    "files": [
      { "part": 1, "track": "mix", "name": "20260826T010000Z_desktop_01J9ABCD_p001_mix.m4a",
        "bytes": 3601234, "sha256": "…",
        "drive": { "fileId": "1AbC…", "webViewLink": "https://drive.google.com/file/d/1AbC…/view" } },
      { "part": 1, "track": "meta", "name": "20260826T010000Z_desktop_01J9ABCD.meta.json",
        "bytes": 2210, "sha256": "…", "drive": { "fileId": "…", "webViewLink": "…" } }
    ],
    "folder": { "path": "recly/2026/2026-08/20260826T010000Z_desktop_01J9ABCD",
                "drive": { "folderId": "1XyZ…", "webViewLink": "https://drive.google.com/drive/folders/1XyZ…" } },
    "workflow": { "id": "01J9ABCDEF0123456789ABCDEF", "name": "회의" },
    "device": { "id": "7c1e4b2a-…", "platform": "macos", "name": "MacBook Pro" }
  }
}
```

- `files[].drive`와 `folder.drive`는 이 단계 **앞에** 성공한 `drive.upload`가 있을 때만 채워진다. **없으면 null.**
  `files`는 그 업로드 단계가 올린 파일만 담는다.
- `files[]`에 메타 파일은 `track: "meta"`로 포함한다.
- 앞선 `transcribe` 단계가 성공했으면 결과 파일을 `track: "transcript"`(`{base}.transcript.json`,
  `{base}.transcript.txt` 두 항목)로 추가한다(§8). 실패했거나 단계가 없으면 항목이 없다. `data.transcript` 같은
  별도 객체는 두지 않는다 — 수신자는 `files[]`와 Drive만 보면 된다.
- `type`은 `recording.completed` 하나. 이후 `recording.failed` 등을 추가할 때 수신 측이 `type`으로 분기하도록
  처음부터 넣었다.

### 응답 처리

| 응답 | 처리 |
|---|---|
| 2xx | 성공. 본문 무시 |
| 408 · 425 · 429 · 5xx · 네트워크 오류 · 타임아웃 | 재시도(`retry` 규칙). **429의 `Retry-After`가 있으면 그 값을 우선하되 `maxDelaySec` 상한** |
| 그 외 4xx | 즉시 단계 FAILED(터미널), `onError` 적용. 코어 메시지는 `WEBHOOK_HTTP:{status}` |

백오프: `min(initialDelaySec × 2^(attempt-1), maxDelaySec)` ± 20% 지터. 기본값이면 30s, 60s, 120s, … 3600s 상한,
8회.

### 수신 측 검증

```js
import { Webhook } from "standardwebhooks";
const wh = new Webhook(process.env.REC_SECRET); // "whsec_…"
const payload = wh.verify(rawBody, {
  "webhook-id": req.headers["webhook-id"],
  "webhook-timestamp": req.headers["webhook-timestamp"],
  "webhook-signature": req.headers["webhook-signature"],
});
```

n8n Webhook 노드는 서명 검증이 없으므로 Code 노드에서 위 라이브러리로 검증하거나, 로컬 n8n이면
`http://127.0.0.1` 예외를 쓰고 서명 없이 둔다.

저장소의 로컬 수신기는 `scripts/webhook-receiver.mjs`다(§20 검증 상태).

---

## 5. 워크플로우 보관·시크릿 (구 docs/05)

### 동기화하지 않는다

**워크플로우 정의도 시크릿 값도 기기별이다.** 두 기기가 같은 계정을 쓰더라도 서로의 워크플로우를 보지 않고,
한쪽에서 고친 것이 다른 쪽에 저절로 나타나지 않는다. Drive `appDataFolder`에는 아무것도 두지 않는다 —
`workflows.json`도 `secrets.enc`도 없고, 따라서 pull/push도 병합도 동결도 `dirty` 표시도 없다(ADR-007 대체).
Recly가 Drive에 쓰는 것은 녹음 파일뿐이고, 그것은 `drive.file` 스코프의 사용자 폴더다(§3).

기기 사이로 정의를 옮기는 방법은 **내보내기/가져오기** 하나다(아래). 시크릿 값은 어느 파일로도 나가지 않는다 —
새 기기에서는 `secretRef`가 비어 있고 UI가 "이 기기에 키 없음"으로 표시한다(§2).

동기화하지 않는 것은 그 밖에도 같다: Job 상태, 토큰, 앱 설정(언어·Wi-Fi 전용)(Wi-Fi 전용은 폰
셸에만 있다: Android WorkManager UNMETERED, iOS allowsCellularAccess; 2026-09-03)(ADR-008). **녹음 목록은
예외다** — 동기화가 아니라 Drive의 녹음 폴더가 곧 목록이라서 그렇다(ADR-023, §3 "다른 기기의 녹음").

### 로컬 상태 (`sync_state`)

키/값 테이블 하나에 두 줄뿐이고, 둘 다 이 기기의 것이다.

| 키 | 값 |
|---|---|
| `localDoc` | 이 기기의 워크플로우 문서. 실행도 편집도 표시도 전부 이것 하나를 읽는다(`WorkflowStore`) |
| `deviceDefaultWorkflowId` | 이 기기의 기본 워크플로우 id(ADR-016). 문서 안에 들어가지 않으므로 내보낸 파일이 남의 기기 선택을 옮기지 않는다 |

문서를 읽을 수 없을 때(뒤 버전이 쓴 `schema`, 이 빌드가 모르는 단계 타입)에도 **행은 건드리지 않는다**: 읽지
못하는 것과 없는 것은 다르고, 그 위에 기본 워크플로우를 심으면 사용자의 바이트가 사라진다. 그런 기기는 기본
워크플로우 2개로 동작하고 행은 그대로 남는다.

### 스키마

지원 schema는 **3**이다(§2). 파서가 내리는 판정은 로컬 문서에도 가져온 파일에도 똑같이 적용된다.

| `schema` | 이름 | 동작 |
|---|---|---|
| `> 3` | **Newer** | `ParseResult.UnsupportedSchema` → 읽지 않는다. 가져오기라면 "앱을 업데이트하세요"에 해당하는 오류이고, 로컬 문서라면 위처럼 행을 남긴 채 기본값으로 동작한다 |
| `1..2` | **Outdated** | 현재 규칙으로 읽어 현재 schema로 올린다(`ParseResult.Ok.migratedFrom`). 다음 저장이 현재 schema로 되돌려 쓴다 |
| `1..2` + 폐기된 필드 | **필드 폐기 마이그레이션** | ADR-016으로 사라진 `workflows[i].enabled`·`.isDefault`·`.trigger`는 **이름을 아는 채로 버린다**. `trigger.minDurationSec`은 워크플로우 최상단으로 올라가고 `trigger.sources`는 버려진다 |
| `1..2` + 미지 필드 | **MigrationBlocked** | 타입 모델이 버릴 필드(`ParseResult.MigrationBlocked.fields`)가 있으면 마이그레이션하지 않고 거부한다 — 되돌려 쓰는 순간 남의 데이터가 사라지기 때문이다 |
| `< 1` | — | `Invalid` |

읽을 수 있는 가장 낮은 값은 `WorkflowParser.MIN_SCHEMA`(=1). schema 1은 `transcribe`가 없는 schema 2, schema 2는
폐기된 세 필드가 더 있는 schema 3이므로 현재 파서로 읽는 것이 곧 제 규칙으로 읽는 것이다.

### 내보내기 · 가져오기

설정 화면의 두 항목이고, 코어 쪽은 `WorkflowRepository.exportJson()` / `importJson(json)` 둘뿐이다. 파일을
고르고 쓰는 것은 셸의 몫이다(Android SAF, iPhone 공유 시트, Mac 저장 패널, Windows 파일 선택).

- **내보내기**: 저장된 문서를 **직렬화한 그대로** 쓴다. 형식이 따로 있는 것이 아니라 문서 자체가 형식이다
  (`spec/workflow.schema.json`). 기본 파일명은 `recly-workflows.json`. **기기 기본 워크플로우 포인터는 들어가지
  않고, 시크릿 값도 들어가지 않는다** — 파일에 있는 것은 `secretRef` 이름뿐이다(ADR-008).
- **가져오기**: 파일을 파싱·검증(§2)한 뒤 **문서 전체를 교체한다.** 병합은 없다 — 두 기기의 문서는 서로의 사본이
  아니므로 병합할 기준이 없다. 그래서 확인 대화상자가 "N개 워크플로우로 교체합니다"라고 먼저 묻는다.
  옛 schema로 쓰인 파일은 로컬 문서와 **똑같이** 마이그레이션되고, 파싱·검증에 실패한 파일은 아무것도 쓰지 않은 채
  파서의 오류 목록을 그대로 돌려준다(편집기 검증과 같은 표시).
- 가져오기가 이 기기의 기본 워크플로우를 없애 버려도 포인터는 그대로 둔다. 가리킬 것이 없는 포인터는 오류가 아니라
  `WorkflowSelector`가 아무것도 고르지 않는 상태이고, 셸이 새로 고르라고 안내한다(ADR-016).

### 저장 · 편집

- 저장은 `WorkflowRepository.save`가 검증(§2 파서 왕복) → `localDoc` 교체 순서로 한 번에 한다. 실패는 저장 실패이고,
  실패한 저장은 아무것도 쓰지 않는다.
- 저장이 문서 봉투(`revision += 1`, `updatedAt = now`, `updatedBy = deviceId`)를 찍는다. 내보낸 파일이 언제 어느
  기기에서 나왔는지 말할 수 있어야 하기 때문이다.
- 한 기기에서도 편집기 두 개(데스크톱 두 창, 가져오기와 편집기)가 같은 워크플로우를 만질 수 있다. `WorkflowMutator`가
  뮤텍스 안에서 문서를 다시 읽고, 편집기가 열었을 때의 `updatedAt`이 그대로가 아니면 `MutationResult.Stale`로
  거절한다 — 3-way 병합이 없으므로 다시 열게 하는 것이 정직하다.

### 첫 실행

- 기본 워크플로우는 "메모"(Drive, `recly/memo/{{yyyy}}-{{MM}}`, mono) 하나뿐이다(2026-09-04 사용자 결정; "회의"
  스타터는 제거). 고정 ULID(`00000000000000000000RECMEM`)와 `updatedAt = 1970-01-01T00:00:00.000Z`로 심는다 — 아직
  아무도 편집하지 않았다는 뜻이고, 첫 편집이 실제 시각을 찍는다. 이름은 최초 시드 시점의 앱 언어로 만든다(en: "Memo",
  §7 규칙 6).
- `WorkflowRepository.seed(preferredDefaultId)`는 문서가 없으면 심고, **포인터가 비어 있을 때에만** 셸이 고른
  기본값(모든 기기에서 "메모")을 찍는다. 사용자가 이미 고른 선택은 절대 옮기지 않고, 한 번 찍힌 것은
  되돌리지 않는다 — 되돌릴 근거였던 원격 문서가 이제 없다.
- 어느 경로가 먼저 문서를 심었는지는 상관없다(백그라운드 enqueue의 `current()`가 셸의 `seed()`보다 먼저 돌 수 있다).
  조건은 "포인터가 비어 있는가" 하나다.

### 시크릿

- 이름: `^[a-z][a-z0-9_]{0,31}$`. 워크플로우 JSON에는 이름만 들어간다(ADR-008).
- 저장:

| 플랫폼 | 구현 | 세부 |
|---|---|---|
| Android 폰 · 갤럭시 워치 | `androidx.security:security-crypto` 1.1.0 `EncryptedSharedPreferences` | 마스터 키는 `MasterKey`(`AES256_GCM`, Android Keystore가 감쌈), 키 이름 `AES256_SIV`·값 `AES256_GCM`, 파일 `rec_secure`, 항목 이름은 `{ns}/{key}`. 워치가 담는 것은 설치의 device UUID뿐(ADR-002) |
| iPhone · Apple Watch · macOS | Keychain generic password | `(ns, key)`당 항목 하나, service는 앱 이름 접두 + ns, 접근성 `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` |
| Windows | Credential Manager | JNA로 `CredReadW`/`CredWriteW`/`CredDeleteW`/`CredEnumerateW`, `CRED_TYPE_GENERIC`, `CRED_PERSIST_LOCAL_MACHINE`(로밍 프로필에 올리지 않음) |

`EncryptedSharedPreferences`·`MasterKey`는 상위 라이브러리에서 deprecated지만 **그대로 쓴다**(코드는
`@Suppress("DEPRECATION")`으로 그 사실을 남긴다). DataStore + Keystore 직접 구현은 (a) 봉인·해제 코드와 키
로테이션을 우리가 지게 되고, (b) 마이그레이션 대상 사용자가 0이며, (c) `EncryptedSharedPreferences`가 키 이름까지
복호화해 주기 때문에 별도 이름 색인 없이 `names(ns)`가 성립한다(색인을 따로 두면 복원·크래시로 실제 저장소와
어긋난다). 라이브러리가 실제로 제거되면 그때 옮긴다.

Windows 개발 호스트(macOS)에서는 `DevFileSecureStore`(평문 base64 JSON)가 대신 쓰인다 — **개발 전용**이고
Windows에서는 절대 선택되지 않는다.

- 값 입구는 **`ReclyCore.secrets`**(`SecretsRepository.put/delete/get/names`)다. 코어가 소유한 네임스페이스는
  하나이고 입구도 하나여야 하므로, 셸은 `SecureStore`에 직접 쓰지 않는다.
- **값은 기기 밖으로 나가지 않는다.** 파일로도, 내보내기로도, 워치로도 가지 않는다. 새 기기는 사용자가 다시
  입력한다 — 그 대신 어느 기기의 키가 새어도 다른 기기가 함께 새지 않는다.
- 실행 시 값이 없으면 그 단계는 즉시 `MISSING_SECRET`으로 FAILED(재시도 없음), `onError` 적용.
- UI: 워크플로우 편집 화면이 이 기기에 없는 `secretRef`를 나열하고 바로 입력받는다.
- **읽히지 않는 보안 저장소는 실패로 닫는다(fail closed).** 셸의 Keychain/Keystore/Credential Manager가 목록 조회
  자체를 거부하면(`errSecMissingEntitlement`, 잠긴 기기의 `errSecInteractionNotAllowed`, ACL 거부) 그 예외는 코어를
  그대로 통과한다 — "없음"으로 읽으면 `secretRef`가 있는 단계에 "이 기기에 키 없음"이 잘못 붙고,
  `ReclyCore.disconnect`의 `tokens` 정리가 토큰이 그대로 남은 네임스페이스를 비웠다고 보고한다. 그래서 `disconnect`는
  던져서 셸이 정리를 계속 빚진 상태(`REVOKED_CLEANUP_OWED`)로 두고 재시도를 띄우게 한다.
- **"연결 해제"는 시크릿을 지우지 않는다**(§3). 값은 계정에서 파생된 것이 아니라 이 기기의 설정이고, 지우면 어디에서도
  되찾을 수 없다.

### 토큰

- Google access/refresh token은 시크릿과 같은 저장소, 다른 네임스페이스(`tokens`)에 있다. 동기화하지 않는다.
- 코어는 `TokenProvider.accessToken()`만 호출한다. 갱신·재로그인 유도는 셸의 몫이다(§6).

---

## 6. 인증 (구 docs/06)

### GCP 프로젝트

1. 프로젝트 생성, Drive API 활성화.
2. OAuth 동의 화면: 외부, **Production으로 게시**(Testing이면 refresh token이 7일 만에 만료된다). 앱 이름·로고·개인정보
   URL이 필요하고, 게시 후 non-sensitive 스코프만이면 검증 없이 통과한다.
3. 스코프: `https://www.googleapis.com/auth/drive.file` **하나뿐이다.** non-sensitive이고, 앱이 만든 파일만
   보인다. appdata에는 이제 아무것도 두지 않으므로 `drive.appdata`는 요청하지 않는다(§5). **다른 스코프를
   추가하지 않는다**(캘린더 등은 sensitive → 검증 절차 발생).
4. 클라이언트 ID(플랫폼마다 하나, Google 정책):

| 클라이언트 | 유형 | 식별자 |
|---|---|---|
| Android | Android | 패키지명 + 서명 SHA-1 (debug·release 각각) |
| iOS | iOS | 번들 ID `app.recly` |
| macOS | iOS 유형(GoogleSignIn macOS가 사용) | 번들 ID `app.recly.mac` |
| Windows / JVM | Desktop app | loopback 리다이렉트 `http://127.0.0.1:{port}` |
| Wear OS · watchOS | 없음 | 폰이 대행 |

클라이언트 파일(`google-services.json`, `GoogleService-Info.plist`, `client_secret*.json`)은 `.gitignore`에 있고
빌드 시 로컬에서 주입한다. Apple 두 앱의 `Info.plist` `GIDClientID`가 자리표시자면 로그인 버튼이 비활성이고 정지한
녹음의 Job은 `NEEDS_AUTH`로 파킹된다.

### Android

- 로그인: Credential Manager. 구글이 문서화한 순서를 그대로 사다리로 내려간다 — 각 단계는 앞 단계가
  `NoCredentialException`(자격 증명 없음)일 때만 시도한다.
  1. `GetGoogleIdOption(filterByAuthorizedAccounts=true, autoSelectEnabled=true)` — 재방문 사용자는 화면 없이 바로.
  2. `GetGoogleIdOption(filterByAuthorizedAccounts=false)` — 첫 로그인. 기기의 모든 구글 계정을 바텀시트로.
  3. `GetSignInWithGoogleOption(serverClientId)` — "Sign in with Google" 버튼 흐름. 문서상 *계정이 하나도 없을 때
     계정 추가를 제공하는 유일한 흐름*이다.
  4. 여기까지 전부 `NoCredentialException`이면 기기에 구글 계정이 아예 없는 것 → `SignInResult.NoAccount`. UI가
     `Intent(Settings.ACTION_ADD_ACCOUNT, EXTRA_ACCOUNT_TYPES=["com.google"])`로 시스템 계정 추가 화면을 열고,
     돌아오면 로그인을 **한 번만** 재시도한다(루프 방지).
- 사다리를 내려가는 조건은 `NoCredentialException`뿐이다. 취소(`GetCredentialCancellationException`)나 Play
  Services 실패는 그 단계가 성립했다는 뜻이므로 다음 단계로 내려가지 않는다.
- nonce는 쓰지 않는다. ID 토큰은 계정 식별용으로만 쓰고 서버로 보내지 않으므로 재생 공격을 묶을 대상이 없다.
- 분기는 `auth.signIn.fallback=allAccounts|button|addAccount`로 로그에 남는다.
- 인가: `Identity.getAuthorizationClient(activity).authorize(AuthorizationRequest{scopes: drive.file})` →
  `accessToken`(1시간). 이미 허용된 계정이면 무음.
- 갱신: refresh token을 직접 갖지 않는다. `TokenProvider`가 만료 60초 전이면 `authorize()`를 다시 부른다.
  **액티비티가 없는 WorkManager 컨텍스트**에서 `hasResolution() == true`(재동의 필요)가 나오면 Job을 `NEEDS_AUTH`로
  두고 알림으로 앱을 열게 한다. **앱이 포그라운드로 올 때**(`MainActivity.onStart`) 로그인돼 있고 `NEEDS_AUTH` Job이
  있으면 사용자가 아무것도 누르지 않아도 액티비티에서 `authorize()`를 다시 부른다(2026-09-04) — 이미 허용된 계정이면
  무음으로 통과해 Job이 `PENDING`으로 돌아가고, 정말 동의가 필요하면 동의 화면이 그 자리에서 뜬다. 취소하면 배너가
  남고 다음 시작이 다시 묻는다. 배너·행의 버튼도 같은 호출이다(로그아웃 상태면 설정의 로그인으로). 설정에 별도
  "다시 허용" 행은 없다.
- 저장: access token + 만료 시각, 그리고 다시 같은 계정을 고르기 위한 계정 이메일(`account/email`)만 보안 저장소에.
  로그아웃이 둘 다 지운다.

### iOS · macOS

- GoogleSignIn-iOS 9.x(`GIDSignIn.sharedInstance.signIn(withPresenting:hint:additionalScopes:)`). refresh token은
  SDK가 Keychain에 보관하고 `refreshTokensIfNeeded`로 갱신한다 → `TokenProvider`가 이걸 감싼다. **SDK의 Keychain
  항목이 "who is signed in"의 정본**이다.
- 실행할 때마다 `hasPreviousSignIn()` → `restorePreviousSignIn()`으로 조용히 복원한다. 실패는
  `GIDSignInError.hasNoAuthInKeychain`(-4)이라 알릴 것이 없다.
- **Drive 스코프는 처음부터 `additionalScopes`로 받는다.** Recly가 구글 계정을 요구하는 이유는 Drive 업로드
  하나뿐이라 스코프 없는 로그인은 쓸 데가 없다. 대신 **동의 결과는 반드시 확인한다**: 사용자가 두 체크박스를 따로
  끌 수 있으므로 `grantedScopes`에 하나라도 빠지면 `signOut()` 후 "Drive 권한을 허용해야 업로드할 수 있습니다"로
  되돌린다(스코프가 빠진 계정을 남겨 두면 업로드가 401이 아니라 403으로 실패해 Job이 재시도를 태운다).
- `hint:`는 OAuth `login_hint`로, **로그아웃으로 끝나지 않은** 마지막 계정만 넣는다(`UserDefaults`
  `app.recly.auth.lastAccount`). 로그아웃한 사용자는 계정을 바꾸러 온 것일 수 있는데, hint는 다중 로그인 세션에서
  계정을 대신 골라 버린다.
- `GIDSignInError.canceled`(-5)는 실패가 아니다 — 시트를 닫은 것뿐이므로 Mac은 경고창을, 폰은 빨간 문구를 띄우지
  않는다.
- 로그아웃은 `signOut()`이고 기본 경로에서 `disconnect()`는 쓰지 않는다. `signOut`은 "clears the sign-in state…
  removes the user's credentials for your app from the Keychain"이고 "Signing out only applies to your app… it
  does not revoke the permissions the user granted". `disconnect`는 취소(revoke)까지 하는데 그 취소는 **Cloud
  프로젝트 단위**라 폰에서 누르면 PC·안드로이드의 grant까지 사라진다. 앱의 "연결 해제"(§3)만이 이것을 부른다.
- 리다이렉트 URL은 **두 셸 모두** SDK에 넘겨야 한다: 폰은 `.onOpenURL`, Mac은
  `NSApplicationDelegate.application(_:open:)`.
- SDK는 `ASWebAuthenticationSession`을 **비-ephemeral**로 연다. 그래서 Safari에 이미 로그인한 사용자는 비밀번호를
  다시 치지 않고 계정 선택만 한다 — 원하는 동작이라 바꿀 것이 없다.
- macOS에서 SDK는 기본으로 **데이터 보호 키체인**(`kSecUseDataProtectionKeychain`)에 자격증명을 쓰는데, 그
  키체인은 팀 서명이 붙은 Keychain 접근 그룹(`$(AppIdentifierPrefix)$(CFBundleIdentifier)`)이 없는 프로세스를
  거부한다 — ad-hoc 서명 빌드 전부다(2026-09-02 실기: 동의 후 "Google sign-in failed", `GIDSignInError.keychain`).
  그래서 RecKit은 macOS에서 SDK의 저장소를 **로그인 키체인(파일 기반)** 저장소로 바꿔 끼운다
  (`GoogleAuth.useLoginKeychain`, GTMAppAuth `useFileBasedKeychain`). SDK가 저장소를 공개 API로 열어 두지 않아
  KVC로 사설 ivar에 넣는 것이며, SDK를 올릴 때 이름이 바뀌면 로그인이 예전과 같은 방식으로 실패하니 그때 다시
  본다. 부작용이었던 것: ad-hoc 빌드는 설치할 때마다 서명이 달라져 macOS가 "Recly이(가) 키체인의 'auth'에
  접근하려 합니다" 확인을 다시 띄웠다. 그래서 Mac 빌드는 ad-hoc을 쓰지 않는다 — `apple/scripts/setup-local-signing.sh`가
  로그인 키체인에 만드는 자체 서명 인증서 "Recly Local Development"가 Xcode 프로젝트의 서명 ID이고,
  `release-mac.sh`는 Developer ID → `RECLY_SIGNING_IDENTITY` → 그 로컬 인증서 순으로 고르며 어느 것도 없으면
  멈춘다(2026-09-03). 서명이 빌드마다 같으니 키체인·개인정보 허용이 유지된다. 팀 서명이 붙는 날(§12 M9) 접근
  그룹을 넣고 이 우회를 걷어낼 수 있다.
- **배경 URLSession**에서 청크를 보낼 때 토큰이 만료될 수 있다 → 청크 태스크 생성 직전에 갱신하고, **401이면 해당
  청크를 재계획**한다(ADR-015).
- App Review 4.8: Drive 클라이언트로서 Google 계정이 *필수*이므로 Sign in with Apple 의무의 예외("특정 서드파티
  서비스의 클라이언트")에 해당한다고 심사 노트에 적는다. 거절되면 로컬 전용 모드(업로드 없이 녹음·웹훅)를 추가하는
  것이 대안이다.

### Windows (JVM)

- 코어에 넣지 않고 `windows/app`에 Kotlin으로: 127.0.0.1 임시 포트 Ktor 서버 → 시스템 브라우저로
  `https://accounts.google.com/o/oauth2/v2/auth`(PKCE S256, `access_type=offline`) → code 수신 →
  `https://oauth2.googleapis.com/token` 교환. refresh token은 Windows Credential Manager, 갱신은
  `JvmTokenProvider`(만료 60초 전).
- 리다이렉트는 `http://127.0.0.1:{OS가 고른 포트}`다. `localhost`가 아니다 — RFC 8252 §8.3 "the use of `localhost`
  is NOT RECOMMENDED … avoids inadvertently listening on network interfaces other than the loopback interface".
  포트는 등록하지 않는다(§7.3 "MUST allow any port to be specified at the time of the request"; Desktop 유형
  클라이언트는 리다이렉트 URI를 아예 받지 않는다). 서버는 로그인이 시작될 때만 열고 응답이 오면 닫는다.
- `state`는 128비트 난수, 불일치는 거절(§8.9). 리다이렉트는 **한 번만** 받는다 — 그 포트는 이 기기의 아무
  프로그램이나 두드릴 수 있다. 두 번째 요청은 "이미 처리되었습니다" 페이지만 받는다.
- `prompt`는 **첫 로그인에 붙이지 않는다**("the user will be prompted only the first time your project requests
  access", "include `prompt=consent` only when necessary"). 이미 refresh token을 가진 기기에서 다시 로그인하는 것은
  계정을 바꾸겠다는 뜻이므로 그때만 `prompt=select_account`.
- `include_granted_scopes`는 쓰지 않는다("Incremental authorization is not supported for installed apps or
  devices"). `login_hint`도 없다 — 프로필 스코프를 요청하지 않으므로(ADR-009) 채워 넣을 주소를 애초에 모른다.
  **Windows는 계정 이메일을 저장하지 않는다.**
- 로그아웃은 **로컬만**이고 `https://oauth2.googleapis.com/revoke`를 부르지 않는다: "Revocation removes all OAuth
  2.0 scopes previously granted to a **project**, invalidating any issued access or refresh tokens for all clients
  registered under that project." 남는 grant는 6개월 미사용으로 만료된다. 앱의 "연결 해제"만이 `/revoke`를 부른다.
- 갱신 중의 `invalid_grant`는 grant가 죽은 것 → 저장분을 버리고 재로그인(`AuthRequiredException`). 같은 코드가 교환
  단계에서는 PKCE 불일치를 뜻하므로 어느 호출이 실패했는지로 구분한다.
- Desktop 유형의 `client_secret`은 비밀이 아니다("Installed apps … cannot keep secrets"). 그래도 개발자마다
  다르므로 커밋하지 않는다.
- 브라우저 열기 30초, 동의 대기 5분. `Desktop.browse`는 블로킹이라 별도 스코프에서 열고 **기다리는 쪽**만
  포기한다. 성공 페이지는 인라인 HTML 하나뿐이다 — 외부 리소스를 하나라도 받으면 code가 들어 있는 이 URL이
  `Referer`로 새어 나간다.

### Wear OS · watchOS

인증 없음. 워치는 Drive에 접근하지 않는다(ADR-002). 워크플로우 요약은 폰이 밀어준다.

### 코어 인터페이스 (the core interface)

```kotlin
interface TokenProvider {
    /** 유효한 access token. 갱신 실패·재동의 필요 시 AuthRequired 예외. */
    suspend fun accessToken(): String
    /** 401을 받은 뒤 셸이 강제 갱신하도록 */
    suspend fun invalidate()
}
```

코어는 401을 받으면 `invalidate()` 후 한 번 재시도하고, 다시 401이면 단계를 `NEEDS_AUTH`로 둔다(재시도 카운트 소모
없음, 사용자가 로그인하면 재개).

### refresh token 한도

클라이언트 ID당 계정별 100개. 기기 6대 × 재설치 몇 번이면 여유가 있다. 6개월 미사용 시 만료 — 폰·데스크톱은
문제없고, 오래 안 쓴 기기는 재로그인한다.

---

## 7. i18n (구 docs/07)

지원 언어는 **영어(en)와 한국어(ko)**다. 기본 언어는 사용자의 시스템 언어를 따르고, 앱 안의 언어 설정으로 바꿀 수
있다.

### 규칙

1. **기준 언어는 영어**(리소스 키의 기본값), 한국어는 번역 리소스. 시스템 언어가 `ko`(지역 무관)면 한국어, 그 외
   전부 영어.
2. **언어 설정값**: `system`(기본) · `ko` · `en`. **기기별 설정이고 동기화하지 않는다**(워크플로우 문서에 넣지
   않는다). 저장 위치는 플랫폼 관례(Android `LocaleManager.applicationLocales` + DataStore, Apple `UserDefaults`,
   Windows `java.util.prefs`). **설정 UI는 지금 언어를 값으로 보여주는 한 행**이고, 목록은 폰(Android·iPhone)에서
   다이얼로그로, 데스크톱(macOS·Windows)에서 드롭다운으로 연다 — 언어는 늘어나도 행은 한 줄이다. 목록에
   `시스템 기본` 항목은 **없다**: 각 언어의 자기 이름(`English`, `한국어`)뿐이고, 아직 아무것도 고르지 않았으면
   시스템 언어를 따르며 **그 언어가 선택된 것으로 보인다**(행에도 그 이름이 뜬다) — 대신 명시적으로 고른 뒤 다시
   OS 추종으로 되돌리는 항목은 없다는 것을 받아들인다. 이름은 어느 언어에서도 번역하지 않는다. 고른 즉시
   적용되고(규칙 3) 국기는 쓰지 않는다.
3. **런타임 전환**: 설정을 바꾸면 재시작 없이 화면이 바뀐다(Android `setApplicationLocales`가 액티비티를 재생성,
   SwiftUI는 `\.locale` 환경값, Compose는 로케일 상태로 리컴포지션). AppKit/UIKit 알림(NSAlert,
   UNNotification)·트레이 메뉴·Live Activity·컴플리케이션 텍스트는 현재 앱 언어로 명시
   조회(`String(localized:bundle:locale:)`, Android `createConfigurationContext`)한다.
4. **사용자에게 보이는 문자열은 전부 리소스**: UI 텍스트, 알림, 다이얼로그, 오류 안내, 접근성 라벨,
   위젯/컴플리케이션, 트레이. **리소스로 만들지 않는 것**: 로그 이벤트·필드(`rec.*`, `shell.*`), 파일명·폴더 규칙,
   웹훅 페이로드, 워크플로우 JSON, 코드 식별자. 숫자는 숫자다 — 인원 수 같은 값은 문장이 아니라 수로 쓰고, 문장이
   되는 것은 `모름`(unknown)과 `6+`뿐이다.
5. **코어가 만드는 사용자 문자열**: 코어는 자연어 대신 **메시지 키**(`CoreMessage` enum: `NEEDS_AUTH`,
   `DRIVE_REAUTH`, `MISSING_SECRET`, `FROZEN`, `STALE`, `DRIVE_STORAGE_FULL`, `AUTH_REJECTED`, …)를
   `StepRun.lastError`·예외에 담고, 각 플랫폼이 키를 번역한다. 인자는 `NAME:{arg}`, provider가 한 말은
   `NAME|{detail}`로 붙이고 `|` 뒤는 번역하지 않고 문장 아래에 고정폭으로 그대로 보여준다. 셸은
   `CoreMessageRef.parse`로 읽는다. DB에 이미 저장된 옛 문장은 그대로 표시한다(호환).
6. **기본 워크플로우 이름**("메모")은 최초 시드 시점의 앱 언어로 생성한다(en: "Memo"). 이후 언어를
   바꿔도 이름은 사용자 데이터이므로 바뀌지 않는다. 시드 ID는 고정이다(§5).
7. **날짜·시간·숫자는 플랫폼 로케일 포맷터**를 쓴다 — 패턴 자체가 리소스라서 한국어 기기는 "8월 28일 15:04",
   영어 기기는 "Aug 28, 3:04 PM"으로 읽힌다. 파일명 타임스탬프(ISO)는 불변이다.
8. **관할별 동의 안내문**(§12)은 언어별 리소스로 두되 링크·법역 목록은 공통이다.
9. **완전성 테스트**: 각 플랫폼에 "모든 키가 en·ko 양쪽에 존재"하는 테스트와 "UI 소스에 한글 리터럴이 남아 있지
   않다"는 검사(허용 목록: 로그·테스트·주석)를 둔다. RecKit은 여기에 더해 "뷰가 그리는 키가 전부 카탈로그에
   있다"를 스캔으로 확인한다(곱슬 아포스트로피처럼 철자가 어긋난 키를 쓰는 날 바로 실패한다).
10. **기기 이름 치환 규칙**: 한 문장이 이 기기를 가리킬 때 쓰는 말은 셸마다 정해져 있다 — Android·iPhone은
    **폰**(en: `phone`), macOS는 **Mac**, Windows는 **PC**, 그리고 어느 기기인지 특정하지 않는 문장(코어의
    `CoreMessage`, 여러 셸이 공유하는 RecKit 문장)은 **이 기기**(en: `this device`)다. 같은 뜻의 문장이 셸마다
    이 한 단어만 다르고 나머지는 글자 그대로 같아야 한다 — "이 폰의 시크릿" / "이 Mac의 시크릿" / "이 PC의
    시크릿", "Delete on this phone only" / "…this Mac only" / "…this PC only". 워치는 자기 문장을 갖지 않고
    폰의 것을 따른다.
11. **교차 셸 사전**: 두 개 이상의 셸에 나오는 문장은 그 셸들에서 **글자 그대로 같다**(§9 화면 원칙 1의 노드
    표기 정책, 상태 배지의 단어, 편집기 필드 라벨, 시크릿 화면의 안내문 등). `CrossShellDictionaryTest`(Android
    jvm 테스트)가 Windows `.properties`와 RecKit·RecMac·RecPhone 카탈로그를 직접 읽어 en·ko를 대조한다. 형식
    인자는 표기가 플랫폼마다 다르므로(`%1$s` ↔ `%@`) 비교 전에 정규화한다. **아직 한 셸만 말하는 사전 문장**은 그
    셸의 자기 테스트가 잠그고(한 셸짜리 줄은 이 테스트가 거절한다), 나머지 셸이 그 키를 가지면 여기로 옮긴다 —
    2026-09-04의 `워치에서 받는 중`·`다른 기기에서 업로드 중`·`다른 기기에서 전사 중`(§9 화면 원칙 2)은 세 셸이
    같은 날 갖췄으므로 바로 이 사전에 있다.

### 플랫폼 매핑

| 플랫폼 | 리소스 | 언어 설정 UI | 전환 |
|---|---|---|---|
| Android 폰 | `values/strings.xml`(en) + `values-ko/`, `android:localeConfig` | 설정 → 언어 행 → 선택 다이얼로그 | 플랫폼 `LocaleManager.applicationLocales`(API 33+; 앱은 순수 ComponentActivity라 AppCompat 경로는 no-op) |
| 갤럭시 워치 | 같은 방식, 시스템 언어만(설정 UI 없음) | — | 시스템 |
| iPhone·Apple Watch·macOS | String Catalog(`Localizable.xcstrings`) — RecKit이 공용 카탈로그를 갖고 각 앱이 자기 것을 갖는다 | 설정 → 언어 행(폰은 다이얼로그, Mac은 드롭다운) | SwiftUI `\.locale`; AppKit/UIKit 문자열은 명시 로케일 조회; `AppleLanguages`는 건드리지 않음 |
| Windows 데스크톱 | `strings_en.properties`/`strings_ko.properties` + `Str` enum 키 | 설정 창 → 언어 행 → 드롭다운 | 로케일 `StateFlow` → 리컴포지션, 트레이 메뉴 재구성 |
| 코어 | `CoreMessage` 키 | — | — |

### Windows 데스크톱 설계 메모

- **리소스 형식은 `.properties` + `Str` enum이다.** 트레이 앱이 말하는 문장의 상당수가 컴포지션 밖에서 만들어진다 —
  `TrayIcon.displayMessage` 풍선, Ktor가 서빙하는 로그인 리디렉트 페이지, `Recents.stateLabel`, 헬퍼
  `--self-test` 보고. 어느 스레드에서든 즉시 읽히는 평범한 표가 유일하게 맞는 모양이다. UTF-8로 직접
  읽는다(`Properties.load(InputStream)`는 ISO-8859-1이다).
- **키 타입 세이프:** `Str` enum이 곧 키 목록이고, 프로퍼티 키는 enum 이름을 소문자·점으로 바꾼
  것(`STATUS_WAITING` → `status.waiting`)이다. 표에 없는 문자열은 애초에 컴파일되지 않고, `StringTableTest`가 en·ko
  두 표와 enum을 서로 대조한다.
- **인자 포맷:** `String.format(locale, …)`의 위치 인자(`%1$s`, `%1$d`) — 안드로이드 쪽과 같은 표기라 번역문을
  그대로 옮길 수 있고 번역이 순서를 바꿀 수 있다.
- **중첩·verbatim:** `UiMessage`는 `Res(키, 인자)` / `Text(그대로)` 두 가지고, 인자 하나가 다시 `UiMessage`일 수
  있다. 그리는 순간에 함께 풀리므로 언어를 바꾸면 통째로 다시 그려진다.
- **전환:** `Localization`이 `StateFlow<Strings>` 하나를 들고, 컴포지션은 `collectAsState()`로 읽어 파라미터로
  내려보낸다. 컴포지션 밖은 `localization.current`를 그 자리에서 읽는다. 트레이 메뉴는 `trayMenu()`가 만드는 순수한
  목록이고 `Main.kt`가 AWT 항목으로 옮기기만 한다.
- **저장:** `java.util.prefs`의 `app/recly/windows` 노드에 `language` 키 하나.

---

## 8. 전사 (구 docs/08)

`transcribe`(STT + 화자분리)는 워크플로우 `schema: 3`의 선택 단계다. 실행 위치는 다른 단계와 같은 **잡을 실행하는
기기**(폰·Mac·Windows)이고 서버는 없다. 기기가 사용자의 키로 STT API를 직접 부르고 결과를 Drive 녹음 폴더에 쓴다.

### 원칙

- **서버리스** — 모든 호출은 기기 → 사용자 계정 API(Drive, STT). 중간 릴레이·콜백 URL 없음. 따라서 STT
  provider는 **폴링 가능한 비동기 API 또는 동기 API**만 쓴다(콜백 전용 모드는 쓰지 않는다).
- **BYO 키** — 키는 기존 시크릿 저장소(§5)의 `secretRef`다. 없으면 `MISSING_SECRET`.
- **Drive가 버스** — 결과 파일은 녹음 폴더 `{folder}/{base}/`에 놓인다. 다른 기기·웹훅 수신자·사용자는 그 파일만
  보면 된다. 기기 간 별도 통신은 없다.
- **한 트랙, 한 파일** — 트랙 하나(`mono` 또는 `mix`)를 파트 remux로 이어 붙인 파일 하나를 올린다. 파트별 전사는
  파트마다 화자 라벨이 달라져 쓰지 않는다.

### 단계 정의

#### `transcribe`

```json
{ "id": "stt", "type": "transcribe",
  "provider": "clova",
  "secretRef": "clova_key",
  "invokeUrl": "https://clovaspeech-gw.ncloud.com/external/v1/1234/abcd…",
  "language": "ko",
  "diarize": true,
  "speakers": { "min": 2, "max": 6 } }
```

| 필드 | 기본 | 의미 |
|---|---|---|
| `provider` | 필수 | `assemblyai` \| `clova` \| `rtzr` \| `openai` \| `groq` \| `together` \| `mistral` \| `elevenlabs` \| `deepgram` \| `azure` \| `daglo` \| `speechmatics` \| `rev` \| `gladia`. 클라이언트가 모르는 값이면 검증 오류(`UnknownProvider`) |
| `secretRef` | 필수 | provider 키. 값 형식은 provider 표 참조 |
| `invokeUrl` | — | provider마다 뜻이 다르다(`WorkflowParser.invokeUrlUse`). **필수**: `clova`(앱별 호출 URL)·`azure`(리소스 엔드포인트). **선택**: `openai`·`groq`·`together`·`mistral`·`speechmatics` — 비우면 provider 기본 엔드포인트, 넣으면 그 자리를 대신한다(자체 호스팅·리전). **나머지**: 넣으면 검증 오류. 끝의 `/`는 잘라낸다. 필수 provider는 템플릿(`WorkflowParser.invokeUrlTemplate`: `clova` → `https://clovaspeech-gw.ncloud.com/external/v1/{appId}/{invokeKey}`, `azure` → `https://{resourceName}.cognitiveservices.azure.com`)이 있어 편집기가 빈 필드에 채워 넣고, `{…}`가 남은 URL은 검증 오류(`InvokeUrlPlaceholder`) |
| `language` | `ko` | `ko` \| `en` \| `ko-en` \| `auto`. provider가 못 받는 값은 어댑터가 가장 가까운 값으로 매핑 |
| `diarize` | `true` | 화자분리 요청 여부 |
| `speakers.min` / `speakers.max` | 1 / 10 | 화자 수 힌트. 메타 `context.participants`가 있으면 `min = max = participants`로 **덮어쓴다**(녹음 시점 정보가 워크플로우 기본값보다 정확하다). 상한은 10명이라 `6+`는 그 위쪽 전부를 뜻한다 |
| `model` | provider 기본 | provider별 모델 이름. 자유 문자열, 검증은 provider가 한다 |

입력 트랙: 녹음 `tracks`에 `mono`가 있으면 `mono`, 아니면 `mix`. 둘 다 없으면 단계
`FAILED(NO_INPUT_TRACK)`(비재시도).

전제 조건: 이 단계 **앞에** `drive.upload`가 있어야 한다(`TranscribeNeedsUpload`) — 결과 파일을 놓을 폴더가 그
단계의 output이기 때문이다.

출력: `{ transcript: { jsonFileId, txtFileId, language, speakerCount, durationSec, provider, model }, files: [ … ] }`
— `files[]`는 결과 파일(json·txt)의 `name/bytes/sha256/drive{fileId,webViewLink}`로, 웹훅 payload 빌더가
`drive.upload` output과 같은 코드로 읽는다.

### Provider

첫 행이 편집기의 기본 provider다(영어 기준 우선).

| provider | 인증(`secretRef` 값 형식) | 제출 | 폴링 | 화자분리·힌트 | 언어 매핑 | 기본 모델 |
|---|---|---|---|---|---|---|
| `assemblyai` | 헤더 `authorization`; 값 = 키 | `POST /v2/upload`(바이트) → `upload_url`; `POST /v2/transcript` `{audio_url, speech_models:["universal-2"], language_code, speaker_labels, speakers_expected?}` | `GET /v2/transcript/{id}` → `queued`/`processing`/`completed`/`error`. `Waiting(30s)` | `speaker_labels`, `speakers_expected`(min=max일 때) | `ko`→`ko`, `en`→`en`, `ko-en`→`ko`, `auto`→`language_detection: true` | `universal-2` (**고정** — U-3.x는 한국어 없음) |
| `clova` (Naver CLOVA Speech 장문) | 헤더 `X-CLOVASPEECH-API-KEY`; 값 = 키 문자열. `invokeUrl`은 단계 필드 | `POST {invokeUrl}/recognizer/upload` multipart(`media`, `params` JSON), `completion: "sync"`(≤2 h; 결과가 응답 본문) | 없음(동기). HTTP 타임아웃 15분 | `diarization.enable`, `speakerCountMin/Max`(≤10) | `ko`→`ko-KR`, `en`→`en-US`, `ko-en`→`enko`, `auto`→`ko-KR` | — |
| `rtzr` (리턴제로) | 값 = `{clientId}:{clientSecret}`. `POST /v1/authenticate` → JWT(6 h) 캐시(단계 state) | `POST /v1/transcribe` multipart(`file`, `config` JSON) → `id` | `GET /v1/transcribe/{id}` → `transcribing`/`completed`/`failed`. `Waiting(30s)` | `use_diarization`, `diarization.spk_count`(min=max일 때만 전달) | `sommers`는 `ko`/`ja`만 → `ko`→`sommers`; `en`·`ko-en`·`auto`는 `model_name: "whisper"` + `language: "en"`/`"multi"`/`"detect"` | `sommers` |
| `openai` | 헤더 `Authorization: Bearer {키}` | `POST {base}/audio/transcriptions` multipart(`file`, `model`, `language`), 동기(응답 본문이 결과). base 기본 `https://api.openai.com/v1`, `invokeUrl`로 대체 가능 | 없음(동기) | 모델 이름이 정한다 — 이름에 `diarize`가 있으면 `response_format=diarized_json` + `chunking_strategy=auto`, `whisper`로 시작하면 `verbose_json` + `timestamp_granularities[]=segment`, 그 밖은 `json`. 화자 수 힌트 없음 | `ko`→`ko`, `en`→`en`, `ko-en`→`ko`, `auto`→생략(자동 감지) | `diarize`면 `gpt-4o-transcribe-diarize`, 아니면 `whisper-1` |
| `groq` | 헤더 `Authorization: Bearer {키}` | `POST {base}/audio/transcriptions` multipart, 동기. base 기본 `https://api.groq.com/openai/v1`, `invokeUrl`로 대체 가능 | 없음(동기) | **없다** — Groq에 화자분리가 없으므로 `diarize`는 무시하고 화자는 `null`. `verbose_json` 고정 | 위와 같음 | `whisper-large-v3-turbo` |
| `together` | 헤더 `Authorization: Bearer {키}` | `POST {base}/audio/transcriptions` multipart, 동기. base 기본 `https://api.together.ai/v1`, `invokeUrl`로 대체 가능 | 없음(동기) | `diarize=true` + `response_format=verbose_json` + `timestamp_granularities=segment`, 그리고 `min_speakers`/`max_speakers`(범위) | 위와 같음 | `openai/whisper-large-v3` |
| `mistral` (Voxtral) | 헤더 `Authorization: Bearer {키}` | `POST {base}/audio/transcriptions` multipart, 동기. base 기본 `https://api.mistral.ai/v1`, `invokeUrl`로 대체 가능 | 없음(동기) | `diarize=true` + `timestamp_granularities=segment`. 화자 수 힌트 없음 | 위와 같음 | `voxtral-mini-latest` |
| `elevenlabs` (Scribe) | 헤더 `xi-api-key`; 값 = 키 | `POST https://api.elevenlabs.io/v1/speech-to-text` multipart(`file`, `model_id`, `language_code`, `diarize`, `num_speakers`, `timestamps_granularity=word`, `tag_audio_events=false`), 동기 | 없음(동기) | `diarize`, `num_speakers`(단일 값을 알 때만) | `ko`→`ko`, `en`→`en`, `ko-en`→`ko`, `auto`→생략 | `scribe_v2` |
| `deepgram` | 헤더 `Authorization: Token {키}` | `POST https://api.deepgram.com/v1/listen?…` 쿼리(`model`, `smart_format=true`, `punctuate=true`, `utterances=true`), 본문은 파일 바이트 그대로, 동기 | 없음(동기) | `diarize_model=latest`만 보낸다(옛 `diarize` 플래그를 같이 보내면 거부된다). 화자 수 힌트 없음 | `ko`→`language=ko`, `en`→`language=en`, `ko-en`→`language=ko`, `auto`→`detect_language=true` | `nova-3` |
| `azure` (Fast transcription) | 헤더 `Ocp-Apim-Subscription-Key`; 값 = 키. `invokeUrl`은 리소스 엔드포인트(단계 필드, **필수**) | `POST {invokeUrl}/speechtotext/transcriptions:transcribe?api-version=2025-10-15` multipart(`audio`, `definition` JSON), 동기 | 없음(동기) | `diarization.enabled` + `maxSpeakers`(API가 받는 2..35로 클램프). `diarize: false`면 `diarization`을 아예 빼고 보낸다 | `ko`→`["ko-KR"]`, `en`→`["en-US"]`, `ko-en`→`["ko-KR","en-US"]`, `auto`→`[]` | — |
| `daglo` (다글로) | 헤더 `Authorization: Bearer {키}` | `POST https://apis.daglo.ai/stt/v1/async/transcripts` multipart(`file`, `sttConfig` JSON) → `rid` | `GET https://apis.daglo.ai/stt/v1/async/transcripts/{rid}` → `transcribed`(완료) / `input_error`·`transcript_error`·`file_error`(종료 실패) / 그 밖은 진행 중 | `speakerDiarization.enable`, `speakerCountHint`(단일 값을 알고 2 이상일 때) | `ko`→`ko-KR`, `en`→`en-US`, `ko-en`→`mixed`, `auto`→`ko-KR` | `general` |
| `speechmatics` | 헤더 `Authorization: Bearer {키}`. base 기본 `https://eu1.asr.api.speechmatics.com/v2`, `invokeUrl`로 대체 가능(예: `us1` 호스트) | `POST {base}/jobs` multipart(`data_file`, `config` JSON) → `id` | `GET {base}/jobs/{id}` → `running`(진행) / `rejected`·`deleted`·`expired`(종료 실패, 재제출) / `done`. `done`이면 같은 폴링에서 `GET {base}/jobs/{id}/transcript?format=json-v2` | `diarization: "speaker"`(아니면 `"none"`). 화자 수 힌트 없음 | `ko`→`ko`, `en`→`en`, `ko-en`→`ko`, `auto`→`auto`(언어 식별; 감지된 언어는 토큰의 `alternatives[].language`에서 읽는다) | `enhanced`(`operating_point`) |
| `rev` (Rev AI) | 헤더 `Authorization: Bearer {키}` | `POST https://api.rev.ai/speechtotext/v1/jobs` multipart(`media`, `options` JSON) → `id` | `GET …/jobs/{id}` → `in_progress`/`failed`/`transcribed`. `transcribed`이면 같은 폴링에서 `GET …/jobs/{id}/transcript`(`Accept: application/vnd.rev.transcript.v1.0+json`). **결과 대기 상한 8시간**(비영어 작업 처리 시간이 최대 6시간이라 기본 2시간으로는 모자란다) | `skip_diarization`(= `diarize`의 반대). 화자 수 힌트 없음 | `ko`→`ko`, `en`→`en`, `ko-en`→`ko`, `auto`→`ko`(필드를 빼면 감지가 아니라 영어 기본이라 항상 보낸다) | — (`transcriber: "machine"`) |
| `gladia` | 헤더 `x-gladia-key`; 값 = 키 | `POST https://api.gladia.io/v2/upload` multipart(`audio`) → `audio_url`; 이어서 `POST https://api.gladia.io/v2/pre-recorded` JSON → `id` | `GET https://api.gladia.io/v2/pre-recorded/{id}` → `queued`·`processing`/`error`/`done` | `diarization`, `diarization_config.number_of_speakers`(단일 값을 알 때) 또는 `min_speakers`/`max_speakers` | `ko`→`["ko"]`, `en`→`["en"]`, `ko-en`→`["ko","en"]` + `code_switching: true`, `auto`→`language_config` 생략 | — (`model`은 지정했을 때만 보낸다) |

어댑터는 `core/commonMain`의 `transcribe/` 패키지에 `interface SttProvider { submit(ctx, file): Submitted;
poll(ctx, ref): PollResult }`로 있고, `TranscribeRunner`는 provider와 무관하게 remux → submit → poll → 정규화 →
Drive 쓰기만 안다.

기본 모델 이름은 각 provider의 현행 GA 모델로 갱신하고 `spec/examples/workflows.json`에 반영한다. 사용자가
`model`을 바꿀 수 있으므로 **코어는 이름을 검증하지 않는다.**

#### 길이·크기 한도

한 시간짜리 회의는 32 kbps AAC로 대략 14 MB다. 그래도 provider마다 상한이 있고, 넘으면 4xx로 거부당한다
(`UNSUPPORTED_AUDIO`, 재시도하지 않음).

**32 kbps 녹음이 현실적으로 닿을 수 있는 한도만** provider가 `SttProvider.limits`(`SttLimits(maxBytes,
maxDurationSec)`)로 선언한다. 러너는 파트를 이어 붙인 직후·업로드 직전에 그 한도를 확인하고, 넘으면 바이트를
보내기 전에 `UNSUPPORTED_AUDIO`로 실패한다(재시도하지 않음). detail은 `30 MB exceeds openai's 25 MB` /
`2h 15m exceeds clova's 2h`처럼 실제 값과 한도를 함께 적는다 — 셸은 `UNSUPPORTED_AUDIO` 문장 아래에 그대로
보여준다. **등급·플랜마다 달라지는 한도는 선언하지 않는다** — 상위 플랜이 받아 줄 녹음을 우리가 먼저 거절하게
되기 때문이다. 그런 한도와 32 kbps로는 닿지 않는 한도는 예전대로 provider의 4xx로만 알게 된다.

| provider | 한도 | 업로드 전 확인 |
|---|---|---|
| `openai` | 파일 25 MB(32 kbps 기준 대략 1시간 44분). `gpt-4o-transcribe-diarize`는 그와 별개로 약 25분까지만 받는다고 알려져 있다(비공식) | 25 MB는 확인한다. diarize 모델의 25분은 비공식이라 확인하지 않는다 |
| `groq` | 무료 등급 파일 25 MB | 아니오 — 등급마다 다르다 |
| `together` | 오디오 4시간(파일 80 MB) | 4시간만 확인한다. 80 MB는 32 kbps로 닿지 않는다 |
| `gladia` | 오디오 135분(표준 플랜 기준. Enterprise는 4시간 15분) | 아니오 — 플랜마다 다르다 |
| `clova` | 동기 호출 2시간 | 예 |
| `azure` | Fast transcription 오디오 5시간 | 예 |
| `daglo` | 오디오 4시간 | 예 |

### 오디오 준비 (remux)

`CoreDeps.audio.concat(parts: List<File>, out: File)` — 셸이 구현한다. 같은 트랙의 파트를 파트 번호 순으로
**무손실로** 이어 붙인다(세그먼트 경계가 무손실이므로 AAC 프레임을 그대로 복사한다. 재인코딩 금지).

| 플랫폼 | 구현 |
|---|---|
| Android | `MediaExtractor` + `MediaMuxer`(AAC 트랙 복사, 파트 간 pts 이어 붙임) |
| Apple | `AVAssetReader` → `AVAssetWriter`(`outputSettings: nil`, 패스스루). `AVMutableComposition` + `AVAssetExportSession(presetPassthrough)`는 파트마다 포맷 디스크립션이 달라 `AVErrorOperationNotSupportedForAsset`으로 실패한다 |
| Windows / JVM 데스크톱 | 번들 ffmpeg(ADR-019) `-f concat -safe 0 -c copy`. ffmpeg는 파트가 없어도 exit 0으로 잘린 출력을 내므로 파트 존재는 Kotlin에서 먼저 확인한다 |

인코더 패딩(각 파트가 표시 길이보다 ~0.18초 긴 프레임을 가짐)은 절반 이상이 표시 길이 안에 드는 프레임만 남겨
잘라낸다 — 안 그러면 뒤 파트의 타임스탬프가 파트마다 밀린다. `gaps`(메타)는 무시한다 — 이어 붙인 파일의 시간축은
"오디오가 있는 시간"이고, 결과 타임스탬프는 `parts[].startOffsetSec`으로 녹음 시간축에 되돌려 놓는다
(`transcript.json`의 `start/end`는 녹음 기준이다). 산출물은 임시 파일이며 단계 종료 시 삭제한다(파트 보관 규칙과
무관하다).

### 폴링 · 상태

`StepRunner.run`의 결과에 **`StepOutcome.Waiting(retryAfterSec, state)`**가 있다(§10).

- `Waiting`은 `attempts`를 **소모하지 않는다.** Job은 `WAITING(next_run_at = now + retryAfterSec)`, 단계는
  `PENDING` 유지, `state`(제출 ref·토큰·제출 시각)는 저장. 그래서 **전사가 진행 중인 동안에는 "언제" 다시
  시도한다는 예산 개념이 없다** — 다음 폴링 시각만 있다.
- 제출 후 경과가 **provider가 선언한 결과 대기 상한**(`SttProvider.resultTimeout`, 기본 2시간; `rev`만 8시간 —
  비영어 작업 처리 시간이 최대 6시간이라 2시간에 포기하면 살아 있는 작업을 버리고 같은 오디오를 다시
  결제한다)을 넘거나 provider가 **종료 상태로 실패**(`failed`/`error`)를
  돌려주면 러너는 **state를 비운 뒤** `FAILED(RESULT_TIMEOUT | PROVIDER_ERROR)`(재시도 가능)를 던진다 — Executor는
  실패 시 state를 지우지 않으므로 비워야 **재시도는 새로 제출**한다. 폴링 중 일시 오류(네트워크·5xx·429)는 state를
  유지해 같은 ref를 계속 폴링한다.
- 셸의 스케줄러 어댑터는 `next_run_at`에 맞춰 다시 깨운다(Android WorkManager `OneTimeWorkRequest` 지연, iOS
  `BGProcessingTaskRequest.earliestBeginDate`, 데스크톱 타이머). iOS는 정확한 시각을 보장하지 않으므로 앱 포그라운드
  진입 시에도 `runDueJobs`를 부른다.
- 동기 provider(`clova sync`)는 HTTP 응답을 기다리는 동안 협력적 취소를 받으면 요청을 끊고 `Waiting`이 아니라
  **재제출**한다(결과 ref가 없다). 동기 호출은 데스크톱에서 무해하지만 모바일 백그라운드에선 실행 시간 예산을 넘길
  수 있다 — 모바일 기본 provider 권장은 `assemblyai`/`rtzr`(비동기)다.

### 오류

| reason | 재시도 | 조건 |
|---|---|---|
| `MISSING_SECRET` | 아니오 | 이 기기에 그 이름의 키가 없음 |
| `AUTH_REJECTED` | 아니오 | 401/403. UI는 **"키를 확인하세요"**(check the key) + 편집기 진입 |
| `QUOTA` | 예 | 429(`Retry-After` 우선), 402 |
| `PROVIDER_ERROR` | 예 | 5xx, 네트워크, 타임아웃, provider `failed`/`error` 상태(메시지 보존) |
| `UNSUPPORTED_AUDIO` | 아니오 | 4xx로 파일 거부, 또는 제출 전 한도 초과(provider가 선언, detail에 한도) |
| `NO_INPUT_TRACK` | 아니오 | 입력 트랙 없음 |
| `RESULT_TIMEOUT` | 예 | 제출 후 provider의 결과 대기 상한 초과(기본 2시간, `rev` 8시간) |

reason은 `step_run.last_error`에 **`CoreMessage` 코드**로 적힌다(§7 규칙 5). 여섯 개는 `NAME|provider가 한 말` —
셸이 `NAME`을 자기 언어 문장으로 옮기고 `|` 뒤는 번역 없이 문장 아래에 그대로 보여준다. `MISSING_SECRET`만 인자를
쓴다(`MISSING_SECRET:{secretRef}`). `MISSING_SECRET`·`AUTH_REJECTED`일 때 `StepReport.needsKey`가 "키를 확인하세요"
동작을 켠다. **키는 워크플로우에 정의되어 있으므로 그 화면이 곧 편집기다.**

### 결과 파일

녹음 폴더 `{folder}/{base}/`에 다음 이름으로 쓴다. 로컬 녹음 디렉터리에도 같은 이름으로 사본을 둔다(앱 상세
화면용; 파트 삭제 규칙과 무관하게 보관).

| 파일 | 내용 |
|---|---|
| `{base}.transcript.json` | 아래 스키마. 기계용 정본 |
| `{base}.transcript.txt` | 사람·에이전트용. 한 줄 = `[HH:MM:SS] S1: 텍스트`. 화자가 바뀌거나 세그먼트가 60초를 넘을 때 줄바꿈 |

Drive 쓰기는 `drive.upload`와 같은 멱등 규칙(같은 이름 + 같은 md5면 건너뜀, 다르면 **덮어씀** — 다시 돌리면 최신
결과가 정본).

`transcript.json` 스키마: `spec/transcript.schema.json`.

```json
{
  "schema": 1,
  "recordingId": "01J9ABCDEF0123456789ABCDEF",
  "track": "mono",
  "language": "ko",
  "provider": { "name": "rtzr", "model": "sommers", "jobRef": "…" },
  "createdAt": "2026-08-29T03:10:00.000Z",
  "durationSec": 3612.4,
  "speakers": [ { "id": "S1", "name": null }, { "id": "S2", "name": null } ],
  "segments": [
    { "start": 0.0, "end": 3.2, "speaker": "S1", "text": "시작하겠습니다.",
      "words": [ { "start": 0.0, "end": 0.6, "text": "시작하겠습니다." } ] }
  ]
}
```

- 화자 id는 provider 라벨을 등장 순으로 `S1, S2, …`로 정규화한다. `name`은 항상 `null`(사용자 라벨링은 후속).
- `words`는 provider가 주면 넣고 아니면 생략한다. `start/end`는 초(소수), **녹음 시간축**이다.
- `diarize: false`면 `speakers`는 `[{"id":"S1"}]` 하나, 모든 세그먼트가 `S1`이다.

### 메타 힌트 `context.participants`

§3. 정수, 선택. 채우는 곳:

- 데스크톱·폰: 정지 후 제목 다이얼로그에서 인원 선택(2·3·4·5·6+·모름). 기본 "모름" = 생략.
- 워치: 없다(폰이 전송받아 바로 enqueue). 워크플로우 `speakers` 기본값이 적용된다.

### 웹훅

§4의 `files[]`에 결과 파일을 추가한다: `track: "transcript"`(json·txt 두 항목). 앞선 `transcribe` 단계가
성공했을 때만이다.

### 없는 것

로컬 provider(Whisper + pyannote, 데스크톱 전용), mic/sys 분리 전사로 "나" 식별, 화자 이름 편집·저장, 워치에서
인원 수 입력. Gemini 어댑터도 없다 — 화자분리가 프롬프트로만 되고 타임스탬프를 믿을 수 없어 `transcript.json`의
`start/end` 계약을 못 지킨다. 어댑터 인터페이스는 같으므로 provider를 더하는 것은 어댑터 하나를 더하는 일이다.

---

## 9. 디자인 시스템 "Blueprint" (구 docs/09)

원칙 한 줄: **장식이 아니라 의도**.

### 트렌드 → 적용

| # | 트렌드 | Recly 적용 |
|---|---|---|
| 1 | AI 협업(보조 레이어, 강요 없음) | AI는 **사용자가 자기 워크플로우에 직접 넣은 선택 단계**일 때만 존재한다 — `transcribe`는 사용자의 키로 돌고, 넣지 않으면 앱 어디에도 AI가 없다. UI 규칙은 "보조 레이어": 원본(파트·타이머·상태)이 언제나 본문이고 녹취는 그 옆·아래의 별도 레이어이며, 결과가 원본 표시를 덮거나 대신하지 않는다. 결과가 있는 녹음에만 목록 행에 진입점이 생기고 상세 화면의 녹취 탭에서 펼친다. 자동 제안·자동 실행·"AI로 개선" 같은 권유 UI는 없다 |
| 2 | **목적 있는 모션**(상태 신호, 의도적 마이크로 딜레이) | 녹음 시작/정지·업로드·로그인 같은 **드문 고위험 동작**에 0.3–0.8초의 가시적 처리 상태(버튼이 "저장 중…"으로 변하고 끝나면 "✓"). 장식 애니메이션 금지. 모든 모션은 `reduce motion`을 존중한다 |
| 3 | **Raw 미학**(모노스페이스·그리드·와이어프레임) | **Recly의 시각 언어의 중심.** 타이머·파트 번호·파일명·크기·sha·상태 코드는 모노스페이스. 화면은 보이는 그리드 위의 사각 노드; 워크플로우 편집기는 단계를 **직선 커넥터로 잇는 노드 그래프**다. 필러 일러스트·그라디언트 오버레이 없음 |
| 4 | 포용적 시각(제어권) | **모션 줄이기·고대비는 시스템 설정을 그대로 따른다** — 앱 안에 같은 토글을 다시 두지 않는다(중복 제어는 제어권이 아니다). WCAG AA(텍스트 4.5:1, 그래픽 3:1). 스와이프 힌트엔 정적 대안 |
| 5 | **유동 타이포** | 브레이크포인트 대신 연속 스케일: Compose `sp` + 창 크기 기반 보간, SwiftUI Dynamic Type + `ScaledMetric`, Windows 창 너비에 따른 clamp 보간. 사용자 글꼴 크기 설정을 존중한다 |
| 6 | 저작(Crafted, not prompted) | **아이콘 마스코트 없음.** 대신 **정직한 시스템 표시**: 버전·빌드·기기 ID·오픈소스 고지를 설정 하단에 모노스페이스로. "Handmade" 마케팅 문구는 넣지 않는다(제품 정직성) |
| 7 | Anti-Liquid Glass | 글래스는 **크롬(탭 바·툴바·메뉴바 팝오버)에만**, 콘텐츠 위엔 금지. 블러 + 그라디언트 조명만, 굴절 왜곡 없음. 시스템 탭 바는 시스템 기본을 따르되 텍스트 대비를 확인하고 필요하면 배경을 채운다 |

### 토큰

- **팔레트**(중립 + 단일 액센트):
  - Light: 배경 `#F7F7F5`(종이), 표면 `#FFFFFF`, 그리드선 `#E6E6E2`, 본문 `#111111`, 보조 `#5E5E5A`,
    액센트 **`#0F62FE`**(블루프린트 블루), 녹음/위험 `#DA1E28`, 성공 `#198038`, 경고 `#B28600`.
  - Dark: 배경 `#0E0F12`, 표면 `#16181D`, 그리드선 `#23262D`, 본문 `#F2F2F0`, 보조 `#9A9CA3`, 액센트 `#4589FF`,
    녹음 `#FA4D56`, 성공 `#42BE65`, 경고 `#F1C21B`.
  - **고대비 모드**(시스템이 그 설정을 알려주는 셸에서만 — Apple `colorSchemeContrast`, Windows `win.highContrast.on`):
    그리드선·보조 텍스트를 본문 색으로 승격, 액센트 채도 유지, 테두리 2dp.
- **타이포**: UI 본문 = 플랫폼 시스템 산세리프(Roboto / SF Pro / Segoe UI — 로컬 폰트 번들 금지, 한국어 글리프
  보장). **데이터 = 모노스페이스**(Android `FontFamily.Monospace`/Roboto Mono, Apple SF Mono, Windows
  Cascadia/Consolas): 타이머 `00:12:34`, 파트 `p001`, 파일명, 바이트, 상태 코드 `NEEDS_AUTH`, 기기 ID. 스케일:
  12 / 14 / 16(본문) / 20 / 28 / 44(타이머) — 유동 보간.
- **형태**: 모서리 반경 4(노드) / 8(카드) / 0(테이블 행) / **2(배지)** — 상태 배지·체크박스·라디오·스위치 손잡이처럼
  노드보다 작은 사각형은 노드의 절반을 쓴다(`Radius.badge`). 원형 녹음 버튼은 **사각 노드 + 두꺼운 테두리**다
  (72×72, 녹음 중엔 채움 + 모노 타이머).
- **간격**: 4의 배수, 기본 리듬 8/16/24. 보이는 그리드: 8dp 점선 배경(불투명도 6%, 고대비에선 끔).
- **선**: 커넥터·구분선 1dp(고대비 2dp), 직선·직각만.
- **모션**: 표준 이징 `ease-in-out 200ms`, 상태 전환 배지 `fade 150ms`, 처리 상태 최소 표시 400ms(실제가 더 빨라도
  유지, 최대 800ms). **`reduce motion` 시 "즉시 전환 + 텍스트 상태만"** — 끄는 것은 *전환*이고 "…"과 "✓" 같은 텍스트
  상태 자체는 남는다.
- **아이콘**: 기하학적 얇은 선(1.5dp), 플랫폼 시스템 아이콘 우선(Material Symbols Outlined / SF Symbols light).

### 앱 아이콘

마스터는 **`docs/design/icon.svg`** 하나("A · Record node", 1024×1024 아트보드): 점 격자 바탕(64 피치, r=5,
`#888884` 28%) 위에 노드 사각형(560, 반경 40, 테두리 28)과 그 안의 녹음 사각형(240, 반경 24). 색은 토큰 그대로다.

1. **마스터는 하나** — 플랫폼별로 다시 그리지 않고 **익스포트만** 한다.
2. **모노크롬 템플릿**(22×22 그리드: 바깥 사각형 x3 y3 w16 h16 반경2 테두리1.5, 안쪽 x8 y8 w6 h6 반경1)은
   메뉴바·트레이·Android `monochrome` 레이어가 쓴다. 64px 미만 래스터도 마스터 대신 이 비율로 그린다 — 마스터의
   테두리는 1픽셀 아래로 내려가 사라진다.
3. **녹음 변형**은 메뉴바·트레이에만 있다. 안쪽 사각형만 빨강(Light `#DA1E28` / Dark `#FA4D56`)이고 바깥은 본문 색
   그대로다.

플랫폼별 산출물: Android 폰·워치는 벡터 어댑티브 아이콘(`res/mipmap-anydpi-v26/ic_launcher*.xml` +
`res/drawable/ic_launcher_{background,foreground,monochrome}.xml`, 108dp 캔버스의 66dp 안전 영역 안), Apple은 각
앱의 `Assets.xcassets/AppIcon.appiconset`(iOS는 light/dark/tinted, macOS는 16~512 @1x/@2x에 10% 여백 + 스퀘어클),
macOS 메뉴바는 `MenuBarIcon`/`MenuBarIconRecording` 이미지셋, Windows/jpackage는
`windows/app/src/main/icons/recly.{ico,icns,png}`.

래스터는 전부 커밋되어 있어 빌드는 스크립트를 돌리지 않는다. 다시 뽑을 때(macOS 전용):

```
swift scripts/render-icons.swift
python3 scripts/make-ico.py --check windows/app/src/main/icons/recly.ico
```

### 화면 원칙

1. **녹음 화면 = 계기판**: 상단에 기기·워크플로우·상태 노드 3개(직선으로 연결), 중앙 모노 타이머, 하단 사각 녹음
   노드. 녹음 중엔 경과 시간만 모노 타이머로 (2026-09-03: 파트 번호·세그먼트 경계·트랙 메트릭은 제거 —
   사용자에게 보이지 않는 데이터다). 이 셋이 화면의 경계(boundary)를 만든다.
   - **기기 노드 값**은 소스 코드 그대로(`phone` / `desktop`) — 번역하지 않는다. 트랙(`mono`)과 같은 취급이고,
     헤더 meta도 4셸 모두 `<source> · <기기 ID 앞 8자>`다.
   - **사용 중인 워크플로우 표시**(ADR-016): 워크플로우 노드는 4셸 모두 **그 이름만** 적는다. 피커는 워크플로우
     이름들만 나열하고 선택된 하나가 채워진 칩(✓)이다 — "선택됨"을 말로 쓰지 않는다. 단일 선택 컨트롤은 선택
     상태를 스스로 보여주고, 말로 붙이면 길어질 뿐이다. 피커에서 고르면 그 자리에서 이 기기의 포인터가 바뀐다.
     워크플로우 목록에서는 사용 중인 행에 배지 `사용 중`(en `In use`)을 붙이고, 다른 행의 동작은 `사용`(en
     `Use`)이다. **선택이 없거나 포인터가 가리키던 워크플로우가 사라졌으면** 노드와 피커 모두 4셸에서
     `워크플로우를 선택하세요`(en `Choose a workflow`) 한 줄이다 — 그냥 시작하면 아무것도 실행되지 않는다는 사실과
     그것을 고치는 방법을 같은 자리에서 말한다.
   - **상태 노드**는 녹음기 상태 코드(`IDLE`·`STARTING`·`REC`·`STOPPING`)다. 유휴 상태에서 잡이 돌고
     있으면(원장이 `UPLOADING`인 행이 있으면) `UPLOADING`(액센트)과 그 왼쪽에 회전하는 8pt 사각 로더 — `reduce
     motion`이면 코드만. 녹음 중이면 `REC`가 우선한다. 원장은 코어의 잡 행 관찰(`jobs.observe()`)로 패스 도중에도
     갱신된다(Apple·Android·Windows 2026-09-03; Windows는 reduce motion 신호가 없어 로더가 항상 돈다).
     Windows는 셸 상태 `OPENING`(헬퍼 기동 전)·`NO_HELPER`·`NAMING`(제목 입력 중)을 더 쓴다.
     **폰(Android·iPhone)은 하나 더 빌린다**: 유휴이고 워치에서 받는 중인 행이 있으면 `RECEIVING`(액센트, 같은
     로더)이다(2026-09-04). 이 기기의 잡이 돌고 있으면 `UPLOADING`이 이기고, 녹음 중이면 `REC`가 둘 다 이긴다.
     데스크톱은 받을 것이 없으므로 그대로다.
2. **목록 = 원장(ledger)**: 행 = `시각(모노) · 제목 · 길이 · 상태 코드 · 진행`. 시각 열은 4셸 모두 언어와 무관하게
   **`MM-dd` 위에 `HH:mm`**(월이 먼저; 고정 폭 패턴이지 로케일 서식이 아니다 — 읽어주는 문장만 로케일 서식).
   상태는 텍스트 배지(색 + 문자 둘 다), 실패 사유는 메시지 키 번역.
   **다른 곳에서 벌어지는 일 셋**(2026-09-04, §3 "다른 기기의 녹음")은 잡 상태 매핑보다 **먼저** 읽는다 — 셋 다 이
   기기의 잡이 아니라 큐에서는 읽을 수 없다: `RECEIVING`(워치에서 받는 중 = `receiving`; `status = recording`이라
   그냥 두면 `REC`로 보인다) · `UPLOADING`(다른 기기가 업로드 중 = `remoteUploading`; 이 기기의 업로드와 **같은
   단어**다 — 배지는 어디서가 아니라 무엇을 말한다) · `TRANSCRIBING`(다른 기기가 전사 중 = `remotePending`에
   `transcribe`; `webhook`만 남았으면 `DONE`이다). 셋 다 액센트다. 앞의 둘은 **녹음 중인 행과 같이 동작이 없다**
   (삭제·다시 시도·Drive 링크 없음 — 삭제는 남의 업로드 밑에서 폴더를 빼는 일이다), `TRANSCRIBING`은 입양한 `DONE`
   행과 같다(상세·삭제). 길이 열은 `durationSec`이 없을 때의 자리표시자 그대로이고(`0:00`을 지어내지 않는다),
   배지 열 너비를 재는 셸은 이 코드들도 함께 잰다. 헤더 수에서 앞의 둘은 `대기`로 세고 `TRANSCRIBING`은 세지 않는다.
   말로 하는 상태는 `워치에서 받는 중`(en `Receiving from the watch`) · `다른 기기에서 업로드 중`(en `Uploading on
   another device`) · `다른 기기에서 전사 중`(en `Transcribing on another device`)이다(§7).
   행 확장에 **동작 — 가로로 한 줄, 넘치면 다음 줄로 접는다(세로 나열 금지)**: `Drive 열기`(링크가 있을 때) ·
   `다시 시도`(**실패 상태 `FAILED`·`NEEDS_AUTH`·`NEEDS_SPACE`, 그리고 실패 뒤 백오프를 기다리는 `RETRY`**(4셸 모두, 2026-09-04; provider가 전사 중인 `TRANSCRIBING`은 제외 — 남의 시계다) **에서만**) · `키를 확인하세요`(`AUTH_REJECTED`) ·
   `상세`(en `Details`; 상세 화면 — 데스크톱은 같은 이름의 창 — 을 연다) · `삭제`(녹음·업로드 중 제외). 상세는 원본이 본문이다: **로컬 파트 재생**(재생/일시정지 + `경과 / 총 길이` 모노 시계; 파형(파트를 디코드해 0.25초 창의 피크; 재생된 구간은 액센트) 위에 플레이헤드가 움직이고, 파형을 드래그·탭하면 그 시각으로 이동한다 — Apple·Android·Windows 2026-09-03; 트랙은 `mix`가 있으면 `mix`, 아니면 `mono`; 파일이 이 기기에 없으면 `이 기기에 오디오가 없습니다`(en `No audio on this device`)) 위에 녹취가 놓인다. 4셸 모두(2026-09-03; Apple은 RecKit 공용 뷰 + AVQueuePlayer, Android는 media3 ExoPlayer, Windows는 번들 ffmpeg가 PCM으로 풀어 `SourceDataLine`으로 냄 — JVM은 AAC를 못 푼다). 로컬에 없는 파트는 업로드된 녹음이면 `core.audio()`로 Drive에서 받아온다(`Drive에서 받는 중…`/`Drive에서 받지 못했습니다`); 받는 판단이 끝나기 전엔 재생 버튼을 보이지 않고, 일부만 받아지면 이어지는 앞부분만 재생한다. 이 기기에서 녹음 중이면 재생을 제공하지 않는다. "지금 올리기"는 없다(2026-09-02:
   잡 없는 녹음은 올릴 필요가 없고, 대기 중인 잡은 제 시각에 돈다). 메뉴바·트레이 팝오버와 데스크톱 상세 창도 같은 원장을
   쓴다 — **20행씩 읽고, 마지막 행이 보이면 다음 20행을 더한다**(무한 스크롤; 2026-09-04, 이전에는 팝오버·상세 창이 최근 5행).
   (2026-09-03: 행의 파트 표는 제거 — 파트 번호·트랙·바이트·sha는 사용자에게 보이지 않는다)
3. **워크플로우 편집기 = 노드 그래프**: 트리거(기기) → 단계 노드들 → 끝. 노드는 사각, 커넥터는 직선, 선택 노드에
   액센트 테두리. 단계 추가는 커넥터 위 `+`. 이미 `drive.upload`가 있으면 추가 메뉴의 `Drive 업로드`는 비활성이다
   (2026-09-04: 두 번째 업로드는 같은 폴더면 아무것도 안 하고, 다른 폴더면 뒤 단계가 모르는 사본이 된다 — 웹훅·전사는
   마지막 업로드만 읽는다; 편집기 규칙이고 파서는 막지 않는다). 웹훅은 엔드포인트마다 하나이므로 여러 개가 자연스럽다.
4. **설정 = 섹션형 표**: 계정 / 언어 / 테마 / 캡처(플랫폼별) / 정보(버전·빌드·기기 ID·오픈소스 고지, 모노). **테마**(en
   `Theme`)는 4셸 공통의 칩 3개 `시스템 기본`·`밝게`·`어둡게`(en `System default`·`Light`·`Dark`)이고, 언어처럼 이
   기기의 로컬 설정이다 — 미설정은 시스템의 `prefers-color-scheme`을 따른다(Windows 2026-09-01, 나머지 셋 2026-09-04).
   설정에 기술 수치(세그먼트 길이 등)는 두지 않는다 — 사용자가 바꿀 수 없는 값은 표시하지 않는다(2026-09-04).
5. **알림·다이얼로그**: **제목 + 한 줄 설명 + 최대 2개 버튼.** 처리 상태는 인라인(버튼이 "저장 중…"으로 변함), 완료
   시 배지. 두 갈래 질문에 셋째 선택지를 만들지 않는다.
6. **macOS 메뉴바**: 팝오버(글래스 허용)에 상태 노드 3개 + 최근 원장(20행씩 무한 스크롤) + 동작. Windows 트레이도 동일 구조(Compose
   팝업 창). 녹음 중에는 동작 줄의 빈 자리에 기록 중인 트랙의 실시간 파형(0.1초 창 피크, 녹음 색)이 흐른다 — iPhone
   녹음 화면은 타이머 아래 같은 띠(Apple 2026-09-03). Windows 트레이 팝업은 타이머 아래 같은 띠 — 헬퍼가
   `level {peaks[]}` 이벤트로 0.1초 창 피크를 보낸다(2026-09-03). Android 녹음 화면은 타이머 아래 같은 띠 —
   `MediaRecorder.getMaxAmplitude()`를 0.1초마다 읽는다(2026-09-03).
7. **워치**: 모노 타이머 + 사각 시작/정지, **상태 한 줄**, 전송 대기 수 — 전송 패스가 폰을 찾아 파일을 넘기는
   중이면 같은 수를 `전송 중 %1$d개`(en `Sending %1$d`)로 말한다(2026-09-04; 타일·컴플리케이션도 같은 규칙).
   대기와 전송 중은 사용자가 같은 수에 대해 묻는 두 질문이고, 어느 쪽인지는 전송 패스만 안다.

### 접근성

시스템 `reduce motion`·`prefers-color-scheme`·대비·글꼴 크기를 자동 반영한다 — **앱 설정에는 접근성 섹션이 없고,
기본 모션·명암이 유일한 동작이다.** 유일한 예외가 §9 원칙 4의 `테마`다: 밝기 구성만은 사용자가 시스템과 다르게 고를 수
있고(접근성 토글이 아니라 취향이다), 고대비·모션은 여전히 시스템만 결정한다. **모든 상태는 색 + 텍스트**다 — 색만으로 무엇을 말하는 표시는 없다. 탭 순서와
스크린리더 라벨은 리소스이고(§7), 행처럼 눌러서 화면을 여는 요소는 그 역할을 라벨에 적는다(예: 목록 행 =
`row, button`).

---

## 10. 코어 (KMP) (구 docs/10)

### 타깃 · 의존성

| 항목 | 값 |
|---|---|
| Kotlin | 2.2.x, K2 |
| 타깃 | `androidTarget`, `jvm`, `iosArm64`, `iosSimulatorArm64`, `macosArm64`, `watchosArm64`, `watchosDeviceArm64`, `watchosSimulatorArm64` |
| HTTP | Ktor 3.x client — Android/JVM `OkHttp`, Apple `Darwin` |
| 직렬화 | kotlinx-serialization-json |
| DB | SQLDelight 2.x — Android/JVM `sqlite-driver`, Apple `native-driver` |
| 파일 | okio (`FileSystem`, `HashingSink` for sha256/md5) |
| 설정 | multiplatform-settings (보안 저장은 셸이 `SecureStore`로 제공) |
| 시간 | kotlinx-datetime |
| 로깅 | 코어 자체 `Logger` 인터페이스 |
| Swift 노출 | SKIE + `assembleXCFramework` |

시뮬레이터 슬라이스는 **arm64만** 있다(Apple Silicon 전용). Apple 앱 타깃은 정적 XCFramework가 링커 옵션을 내보내지
않으므로 **Other Linker Flags에 `-lsqlite3`**가 필요하다.

### 패키지

```
recly.core
  model/        Workflow, Step, Trigger, RecordingMeta, Part, Job, StepRun, DeviceInfo   — spec과 1:1
  ids/          Ulid
  workflow/     WorkflowParser · WorkflowValidator · Template · WorkflowSelector · WorkflowMutator
  recording/    RecordingRepository · MetaWriter · PartHasher (sha256 + md5)
  job/          JobService · Executor · StepRunner · Backoff · JobStore
  drive/        DriveApi · ResumableUploadPlanner · FolderResolver · AppData
  webhook/      Signer · PayloadBuilder · WebhookRunner
  transcribe/   SttProvider 어댑터 · TranscribeRunner
  sync/         WorkflowSync (pull/push/merge)
  secrets/      SecretsRepository · SecretSync · SecretSyncStore
  transfer/     TransferReceiver (수신 측 검증·ack 도우미)
  platform/     SecureStore · TokenProvider · Transport · Crypto · AudioTools · Clock · Logger · DeviceInfo
  ReclyCore     조립 루트
```

### DB 스키마 (SQLDelight)

```sql
CREATE TABLE recording (
  id TEXT PRIMARY KEY, source TEXT NOT NULL, platform TEXT NOT NULL,
  workflow_id TEXT, title TEXT, started_at TEXT NOT NULL, ended_at TEXT, duration_sec REAL,
  timezone TEXT NOT NULL, dir TEXT NOT NULL, meta_json TEXT NOT NULL, status TEXT NOT NULL,
  drive_folder_id TEXT,                   -- 이 녹음의 `{base}/` 폴더 (ADR-014)
  remote INTEGER NOT NULL DEFAULT 0,      -- Drive에서 입양한 행 (§3 "다른 기기의 녹음")
  remote_pending TEXT                     -- 그 기기가 아직 할 일 (같은 절, 폴더의 `pending` 표식)
);
CREATE TABLE part (
  recording_id TEXT NOT NULL, part INTEGER NOT NULL, track TEXT NOT NULL,
  file TEXT NOT NULL, bytes INTEGER NOT NULL, sha256 TEXT NOT NULL, md5 TEXT,
  deleted INTEGER NOT NULL DEFAULT 0,
  drive_file_id TEXT,                     -- 입양한 파트를 받아 올 Drive 파일 (§3)
  PRIMARY KEY (recording_id, part, track)
);
CREATE TABLE job (
  id TEXT PRIMARY KEY, recording_id TEXT NOT NULL, workflow_id TEXT NOT NULL,
  workflow_json TEXT NOT NULL,            -- 실행 시점의 정의 스냅샷
  status TEXT NOT NULL,                   -- PENDING RUNNING WAITING DONE FAILED NEEDS_AUTH NEEDS_SPACE SKIPPED_SHORT
  created_at TEXT NOT NULL, updated_at TEXT NOT NULL, next_run_at TEXT,
  UNIQUE (recording_id, workflow_id)
);
CREATE TABLE step_run (
  id TEXT PRIMARY KEY, job_id TEXT NOT NULL, step_id TEXT NOT NULL, ordinal INTEGER NOT NULL,
  status TEXT NOT NULL,                   -- PENDING RUNNING SUCCEEDED FAILED SKIPPED NEEDS_AUTH NEEDS_SPACE
  attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at TEXT, last_error TEXT,
  state_json TEXT,                        -- 단계별 재개 상태
  output_json TEXT,                       -- 다음 단계가 읽는 출력 (drive: folderId, files[])
  UNIQUE (job_id, step_id)
);
CREATE TABLE drive_folder_cache (path TEXT PRIMARY KEY, folder_id TEXT NOT NULL, checked_at TEXT NOT NULL);
CREATE TABLE sync_state (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE kv (key TEXT PRIMARY KEY, value TEXT NOT NULL);   -- deviceId 등 비밀 아닌 설정
-- secret_sync 테이블은 시크릿 동기화 폐기와 함께 삭제됐다(migrations/2.sqm)
  name TEXT PRIMARY KEY, updated_at TEXT NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0, dirty INTEGER NOT NULL DEFAULT 0
);
```

`step_run.state_json` for `drive.upload`:

```json
{ "folderId": "…", "files": { "p001_mic": { "sessionUri": "…", "offset": 1310720, "fileId": null },
                              "p001_sys": { "fileId": "1AbC…" } } }
```

세션 URI와 오프셋을 저장하므로 프로세스가 죽어도 청크 단위로 이어간다.

#### 스키마 마이그레이션

`Rec.sq`는 **언제나 전체 스키마**이고, 설치된 DB를 따라오게 하는 것은
`core/src/commonMain/sqldelight/migrations/<n>.sqm`이다(`n`은 "이 버전에서 올린다"는 뜻이라 `1.sqm`이 1 → 2).
`Schema.version`은 마이그레이션 파일에서 자동으로 나온다(현재 **5**; `3.sqm`이 `recording.drive_folder_id`·
`recording.remote`·`part.drive_file_id`를, `4.sqm`이 `recording.remote_pending`을 더한다, §3 "다른 기기의 녹음").

- **스키마를 바꿀 때마다** `Rec.sq`를 고치고 **같은 커밋에서** 다음 번호의 `.sqm`을 추가한다. 둘 중 하나만 하면 새
  설치와 기존 설치의 스키마가 갈라진다.
- **이미 나간 `.sqm`은 절대 고치지 않는다.** 그 파일은 남의 기기에서 이미 실행됐다 — 고쳐야 할 것이 있으면 다음
  번호를 추가한다.
- 드라이버별로: Android(`AndroidSqliteDriver`)와 Apple(`NativeSqliteDriver`)은 `RecDatabase.Schema`를 받아
  `user_version`으로 create/migrate를 스스로 처리한다. **JDBC(Windows·테스트)는 아무것도 하지 않으므로**
  `JvmRuntime.openDriver`가 대신 한다: 테이블이 없으면 `create` 후 버전 스탬프, 뒤처졌으면 `migrate`, 최신이면 그대로.
  `user_version`이 0인데 테이블이 있으면 **버전 1**로 본다(데스크톱이 버전을 안 찍던 시절의 파일).
- 검증은 `MigrationTest`다: 버전 1 DDL로 손수 만든 DB를 올려 기존 행이 남아 있는지와 새 테이블이 쓰이는지를 본다.
- **내려가는 길은 없다.** v2로 올라간 DB에 v1 빌드를 설치하면 `Can't downgrade database`로 프로세스가 죽는다(같은
  기기에 여러 브랜치를 설치할 때만 생긴다; 앱 데이터를 지우면 풀린다).

### 잡 상태 머신

```
enqueue ──► PENDING ──runDueJobs──► RUNNING ──모든 step 종료──► DONE
                                       │ step 실패(재시도 남음) → WAITING(next_run_at)
                                       │ step 실패(abort)      → FAILED
                                       │ 401 재현              → NEEDS_AUTH  ──로그인──► PENDING
                                       │ 403 storageQuotaExceeded → NEEDS_SPACE ──"다시 시도"──► PENDING
minDurationSec 미만 ──► SKIPPED_SHORT (수동 실행 시 PENDING)
```

`NEEDS_AUTH`와 `NEEDS_SPACE`는 **사용자가 무언가를 하기 전까지 터미널**인 두 상태다. 스케줄러가 다시 깨워도 고르지
않고(`runDueJobs`의 대상은 `PENDING`·`WAITING`뿐), 재시도 예산도 쓰지 않는다.

- `runDueJobs(now)`: `status IN (PENDING, WAITING) AND (next_run_at IS NULL OR next_run_at <= now)`인 Job을
  `created_at` 순으로 하나씩. 동시에 하나만 실행(모바일 네트워크·배터리). 데스크톱은 셸이 `maxConcurrent`를 2로 올릴
  수 있다.
- 단계 실행은 `ordinal` 순. `SUCCEEDED`/`SKIPPED`인 단계는 건너뛰고 `PENDING`/`FAILED`(재시도 가능)부터.
- 단계가 `FAILED`로 확정(attempts ≥ maxAttempts 또는 비재시도 오류)되면 `onError`: `abort` → Job FAILED;
  `continue` → 다음 단계.
- `runDueJobs`는 협력적 취소를 지원한다(WorkManager 중단, 앱 종료). 청크 단위로 상태를 저장하므로 중단 지점부터
  재개한다.
- 보관 규칙(§3, ADR-017 7일 창)은 잡 DONE 시가 아니라 **매 `runDueJobs` 패스 끝의 `Retention.sweep(now)`**가
  평가한다: `JobStore.claimPurge`가 **한 트랜잭션** 안에서 자격(전부 DONE·업로드 성공)과 나이(파일 mtime과 마지막
  DONE `updated_at` 둘 다 7일 경과)를 보고 `part.deleted=1`로 클레임한 뒤에만 `RecordingRepository.purgeParts`가
  파일을 지운다(메타·DB 유지). `ReclyCore.audio(recordingId)`는 재생 트랙(`mix`>`mono`)의 없는 파트를 Drive에서
  받아(`fileId`는 업로드 출력, sha256 검증) 같은 이름으로 쓰고 `deleted=0`으로 되돌린다 — 그 파트는 새 mtime으로
  다시 7일을 산다. `enqueue`는 같은 트랜잭션
  규율로 파트가 전부 클레임된 녹음을 거부한다(`PartsPurged`). 만족하지 않으면 `rec.retained` 로그.
- 영속된 `FAILED` 단계는 터미널이다: 다음 패스에서 러너를 다시 부르지 않는다(`continue`로 지나간 것이거나,
  `retry()`가 PENDING으로 되돌린 뒤에만 다시 실행).
- `JobService.retry(jobId)`: NEEDS_AUTH/NEEDS_SPACE/FAILED/SKIPPED_SHORT → PENDING(실패 단계 초기화).
  **WAITING → PENDING now** — `next_run_at`과 단계 `next_attempt_at`을 비우되 `attempts`는 유지한다(예산 리셋이
  아니라 "지금"이다; 코어 규칙으로 남아 있지만 2026-09-02부터 UI의 "지금 올리기" 버튼은 없고, 셸의 "다시 시도"는
  실패 상태에서만 이 호출을 한다).
- step_run과 job의 **짝 전이**(WAITING·NEEDS_AUTH·NEEDS_SPACE·FAILED)는 한 트랜잭션에 쓴다. 방어적으로, PENDING
  단계의 `next_attempt_at`이 미래면 실행하지 않고 job을 그 시각의 WAITING으로 둔다.

### 잡 스냅샷의 미지 스텝

**잡 스냅샷**은 enqueue 시점의 워크플로우다(`job.workflow_json`). 더 새로운 스키마를 아는 앱이 만든 잡을 옛 빌드가
읽으면 그 스냅샷에 모르는 스텝 `type`이 들어 있을 수 있다(같은 기기를 두 빌드가 번갈아 쓰거나 백업을 되돌린 경우).
디코드 예외가 `JobStore.toJob`을 타고 `observeJobs`로 올라가면 **목록 전체**가 죽으므로, 격리 단위는 잡 하나다
(동기화의 동결(§5)과 같은 성질의 방어다).

- `toJob`은 스냅샷 디코드 실패에 예외를 내지 않는다. 그 잡만 `Job.workflow = null`, 상태는 행에 무엇이 적혀 있든
  **`FAILED`**, `Job.snapshotError`에 셸이 그릴 코드를 담아 돌려준다. 다른 잡과 녹음은 정상적으로 읽힌다.
- 코드는 `steps[]`를 하나씩 디코드해 **처음 실패한 스텝의 `type`**을 인자로 하는 `CoreMessage.UNSUPPORTED_STEP`이다.
  어떤 스텝도 지목할 수 없으면 `STEP_FAILED`로 내려간다.
- `selectDue`는 그런 잡을 절대 넘기지 않고, `claimPurge`는 "업로드가 다 끝났다"의 증거로 삼지 않는다 — 읽을 수 없는
  스냅샷은 아무것도 증명하지 못하고 파트는 원본 오디오의 유일한 사본이다.
- **`workflow_json`은 다시 쓰지 않는다.** 행은 더 새로운 앱이 넣은 그대로 남고 `retry()`는 행을 `PENDING`으로
  되돌리기만 하므로, 앱을 업데이트하면 같은 행이 그대로 디코드되어 원래 의도대로 실행된다.
- **단계 정의는 실행 시점의 문서가 이긴다**(2026-09-04). 스냅샷은 잡이 *무엇인지*(어느 워크플로우, 어떤 단계들),
  문서는 사용자가 *지금 뜻하는 것*이다. 실패 뒤 URL·키·플랜을 고친 사용자는 다음 시도가 그 수정을 쓰길 기대한다
  (Z Fold7 실기: 대기 중인 전사가 스냅샷의 Free Clova 도메인만 30분간 계속 불렀다). 그래서 `Executor`는 잡을 돌릴 때
  현재 문서에서 같은 `workflowId`의 워크플로우를 찾아, **같은 `id`·같은 `type`**의 단계는 문서의 정의로 바꿔 끼운다.
  워크플로우가 지워졌거나, 단계가 빠졌거나 타입이 바뀌었거나, 문서를 읽을 수 없으면 그 단계는 스냅샷 그대로다.
  단계 순서·존재 여부는 여전히 스냅샷의 것이다(`step_run` 행이 그것이다).
- 녹음 삭제는 스냅샷을 읽지 않으므로 그대로 된다(§3 녹음 삭제).
- 셸은 그 잡의 오류 문구로 단계의 `last_error` 대신 `snapshotError`를 쓴다.

### Drive 용량 초과 — `NEEDS_SPACE`

사용자의 Drive가 꽉 차면 업로드는 **재시도로 해결되지 않는다.** 사람이 자리를 비우거나 요금제를 올릴 때까지 몇 번을
다시 보내도 같은 403이 오고, 백오프 8회를 태우고 나면 Job은 `FAILED`가 되어 "왜 실패했는지"가 재시도 예산 소진
메시지 뒤에 묻힌다. 그래서 `NEEDS_AUTH` 옆에 같은 성격의 상태를 하나 더 둔다.

- **판정**: Drive가 403에 `errors[].reason == "storageQuotaExceeded"`(또는 `message`가 그와 같은 뜻)를 담아 답한
  경우. 다른 403(권한·스코프 문제)은 `DRIVE_REAUTH`/`STEP_FAILED` 경로다. 판정은 `DriveApi.send`가 **`check`보다
  먼저** 한다 — resumable 계열은 308·404·5xx를 스스로 읽으려고 `check`를 건너뛰므로 뒤에 두면 꽉 찬 Drive가 평범한
  비재시도 실패로 내려간다. 그래서 resumable 세션 시작·청크 PUT·multipart·`meta.json` 어느 요청에서 와도 같다.
- **동작**: 단계는 `NEEDS_SPACE`, Job도 `NEEDS_SPACE`. `attempts`를 **올리지 않고** `next_run_at`도 두지 않는다.
  `state_json`의 resumable 세션 URI·오프셋은 **지운다** — 세션은 1주면 만료되고, 사용자가 자리를 비운 뒤 다시
  시작하는 편이 확실하다.
- **재시도**: `JobService.retry(jobId)`만이 `PENDING`으로 되돌린다. 로그인처럼 자동으로 풀리는 신호가 없으므로(코어는
  Drive 용량을 폴링하지 않는다) 사용자가 **"다시 시도"**를 눌러야 한다.
- **알림은 한 번**: 잡 하나가 `NEEDS_SPACE`로 들어갈 때 셸이 알림 1건. 같은 상태의 잡이 이미 있으면 새 알림을 내지
  않고 기존 알림의 개수만 갱신한다. `NEEDS_AUTH`도 같은 규칙을 쓴다.
- **원본은 남는다**: `NEEDS_SPACE`는 DONE이 아니므로 보관 규칙(ADR-017)이 파트를 지우지 않는다. 자리가 생기면 그대로
  이어 올린다. 지워진 것은 재개 상태뿐이고, "Drive에서도 삭제"가 쓰는 `output_json.folderId`는 파킹을 넘겨
  살아남는다.
- 코어 메시지: `CoreMessage.DRIVE_STORAGE_FULL`(인자 없음, detail에 Drive가 한 말). 셸 문구는 "Google Drive에 공간이
  없습니다 — 정리한 뒤 다시 시도하세요"(free some up) + Drive 저장용량 페이지
  링크(<https://drive.google.com/settings/storage>).
- **이 판정은 `transcribe`의 Drive 쓰기에도 같이 적용된다**(결과 파일도 Drive에 쓴다).

### 삭제 · 연결 해제 API

정본 규칙은 §3 보관 · 삭제다. 코어가 여는 것은 둘이다.

```kotlin
suspend fun RecordingRepository.delete(recordingId: String, deleteDrive: Boolean): DeleteResult
sealed interface DeleteResult {
    data class Deleted(val driveDeleted: Boolean, val driveError: String? = null) : DeleteResult
    data object Busy : DeleteResult      // 이 녹음의 Job이 RUNNING — 아무것도 지우지 않았다
    data object NotFound : DeleteResult
}

suspend fun ReclyCore.disconnect(alsoDeleteRecordings: Boolean): DisconnectResult
data class DisconnectResult(val deletedRecordings: Int, val busyRecordings: List<String>)
```

`disconnect`의 순서: 녹음 삭제(옵션, 한 건씩 `delete`로) → `tokenProvider.invalidate()` →
`tokens` 비움 → `job`·`step_run` 삭제(`busyRecordings`의
것은 남김) → `sync_state` 비움 → `drive_folder_cache` 비움. 전부 `Executor.quiesced` 안이다. 녹음 파일과
`recording`/`part` 행은 옵션이 없으면 남고 **Drive는 절대 건드리지 않는다**. **grant revoke는 코어가 하지
않는다** — 플랫폼 SDK 호출이라 셸의 몫이고, 셸이 지켜야 하는 `DisconnectPhase`·revoke debt·`DisconnectGate`는
§3에 있다.

### 사용자가 고칠 수 있는 실패와 그 알림

"기다리면 낫는 실패"(5xx·네트워크·429)는 조용히 재시도한다. 아래는 **사람이 무언가를 해야만 풀리는** 실패이고, 앱은
이 목록에 대해서만 사용자를 부른다. 문구는 전부 리소스이고 코어는 `CoreMessage` 키만 준다(§7 규칙 5).

| 상태 · 코드 | 언제 | 앱이 하는 말 | 사용자가 누르는 것 |
|---|---|---|---|
| Job `NEEDS_AUTH` (`NEEDS_AUTH`·`DRIVE_REAUTH`·`DRIVE_CONSENT_REQUIRED`) | 401 재현, Drive grant 소멸, 동의 화면이 필요한데 띄울 화면이 없음 | "Google 로그인이 필요합니다 — 녹음 N건이 기다리는 중" | 로그인 / Drive 권한 다시 허용 → 성공 시 자동 재개. Android는 앱을 여는 것만으로 다시 허용을 시도한다(§6 Android) |
| Job `NEEDS_SPACE` (`DRIVE_STORAGE_FULL`) | Drive 403 `storageQuotaExceeded` | "Google Drive에 공간이 없습니다 — 녹음 N건이 기다리는 중" | Drive 저장용량 열기 / 다시 시도 |
| 단계 `FAILED` (`MISSING_SECRET:{name}`) | 이 기기에 그 이름의 키가 없음 | "이 기기에 `{name}` 키가 없습니다" | 키 입력(편집기의 시크릿 폼으로 바로 진입) |
| 단계 `FAILED` (`INVALID_SECRET:{name}`) | 저장된 값이 서명 키로 못 씀 | "`{name}` 키 값이 올바르지 않습니다" | 키 다시 입력 |
| 단계 `FAILED` (`AUTH_REJECTED`) | STT provider가 키를 거절(401/403) | "키를 확인하세요" + provider가 한 말 그대로 | 키 다시 입력 |
| 단계 재시도 중 / `RETRY_BUDGET_SPENT:QUOTA` (`QUOTA`) | provider 할당량·요금(429·402). 429는 `Retry-After`로 조용히 기다리지만, 예산을 다 쓰면 사용자 문제다 | "provider 할당량이 찼습니다 — 결제·한도를 확인하세요" | provider 콘솔 열기 / 다시 시도 |
| 단계 `FAILED` (`WEBHOOK_HTTP:{status}`) | 웹훅이 4xx(408·425·429 제외) | "웹훅이 {status}로 거절했습니다 — URL과 서명 시크릿을 확인하세요" | 워크플로우 편집 열기 / 다시 시도 |

규칙:

1. **알림은 상태당 하나로 접는다.** 잡 5건이 같은 이유로 막혀도 알림은 1건이고 본문의 개수만 오른다. 같은 이유의
   알림을 다시 내는 것은 상태가 풀렸다가 다시 막혔을 때뿐이다.
2. **탭하면 고칠 수 있는 화면으로 간다** — 로그인 화면, 시크릿 폼, 워크플로우 편집기. "앱 열기"로 끝내지 않는다.
   모든 사유에는 갈 곳이 있다.
3. **상태가 풀리면 알림을 내린다.** 그 이유가 큐에서 사라지면 알림도 내려간다.
4. **재시도로 낫는 실패는 알리지 않는다.** 목록 행의 상태 배지로만 보인다.

| 플랫폼 | 표면 |
|---|---|
| Android 폰 | 알림 채널 `jobs`(녹음 FGS 채널과 별개, **무음 · 우선순위 기본**). 탭 → 해당 화면. 목록 상단 배너도 같은 문구 |
| 갤럭시 워치 | 없음 — 워치는 Job을 만들지 않는다(ADR-002). 전송 실패만 워치 화면 한 줄 |
| **iPhone** | `UNUserNotificationCenter` 로컬 알림 + 목록 상단 배너. 권한이 없으면 **배너만** |
| Apple Watch | 없음(iPhone과 같은 이유) |
| **macOS** | 메뉴바 아이콘 오류 상태 + 팝오버 상단 배너 + `UNUserNotificationCenter` 알림(미팅 감지와 같은 경로) |
| Windows | 트레이 아이콘 오류 상태 + `TrayIcon.displayMessage` 풍선 + 트레이 팝업 상단 배너 |

### StepRunner

```kotlin
interface StepRunner {
    val type: String
    /** 성공하면 output, 실패하면 StepFailure(retryable, reason). state 저장은 ctx.saveState()로 수시로. */
    suspend fun run(ctx: StepContext): StepOutput
}
class StepContext(val job: Job, val step: Step, val recording: RecordingMeta, val prior: Map<String, StepOutput>,
                  val state: JsonObject?, val saveState: suspend (JsonObject) -> Unit,
                  val secrets: SecureStore, val token: TokenProvider, val transport: Transport, val audio: AudioTools, …)
sealed interface StepOutcome { data class Done(val output: StepOutput); data class Waiting(val retryAfterSec: Int, val state: JsonObject) }
```

실패는 `StepFailure`를 던진다.

- `DriveUploadRunner`: 폴더 해석(캐시 → `files.list` → 생성) → 파트별 resumable 세션(≤5 MB면 multipart 단일 요청) →
  md5 검증 → `meta.json` 마지막 → output `{folderId, webViewLink, files[]}`.
- `WebhookRunner`: `prior`에서 가장 최근 `drive.upload` output을 찾아 payload 구성 → 서명 → POST → 응답 규칙(§4).
- `TranscribeRunner`: `deps.audio.concat`로 입력 트랙 remux → `SttProvider.submit` → `Waiting(30)` 반복 → `poll`
  완료 시 `transcript.json/.txt`로 정규화 → 로컬 사본 + Drive 쓰기(`prior`의 `drive.upload` 폴더).
- **`StepOutcome.Waiting(retryAfterSec, state)`** — `run`이 이것을 돌려주면 Executor는 `attempts`를 올리지 않고
  단계를 `PENDING`으로 둔 채 Job을 `WAITING(next_run_at = now + retryAfterSec)`으로 옮기고 `state`를 저장한다.

"앞 단계의 마지막 X 출력"을 찾는 것은 `priorOutput` 하나이고 네 곳(러너 3 + `PayloadBuilder`)이 그것을 쓴다.

### ResumableUploadPlanner (ADR-015)

순수 함수 집합. 네트워크를 모른다.

```kotlin
object ResumableUploadPlanner {
    fun startRequest(meta: DriveFileMeta, totalBytes: Long, mime: String): HttpPlan          // POST …?uploadType=resumable
    fun chunkRequest(sessionUri: String, offset: Long, chunk: ByteRange, total: Long): HttpPlan
    fun queryRequest(sessionUri: String, total: Long): HttpPlan                              // Content-Range: bytes */total
    fun onResponse(status: Int, headers: Headers, body: String?): Outcome                    // Continue(offset) | Done(file) | Restart | Retry(after) | Fail(reason)
}
```

`Transport`는 `HttpPlan`을 실행해 `(status, headers, body)`를 돌려준다. 기본 구현은 Ktor. Apple 셸은 청크 PUT을
배경 `URLSession` 업로드 태스크로 보내는 구현을 제공한다(청크를 임시 파일로 잘라 `fromFile`).

청크 크기: 모바일 1 MiB(256 KiB 배수), 데스크톱 8 MiB. 5xx·네트워크 오류 → `queryRequest`로 오프셋 재확인 후
이어감. 404 → 세션 재시작(기존 부분 업로드는 Drive가 버린다). 세션은 1주 유효 — `state_json`의 세션이 7일 지났으면
재시작한다.

### 동시성 · 스레딩

- 코어는 `suspend` API만 노출한다. 디스패처는 셸이 주입한다(`CoreDeps.io`).
- DB 접근은 저장소별 `Mutex`로 직렬화한다(셸이 멀티스레드 디스패처를 넘겨도 read-modify-write가 겹치지 않도록;
  SQLDelight 드라이버가 스레드 안전하지 않은 플랫폼 대비).
- `runDueJobs`는 재진입 금지 — `Mutex.tryLock`, 이미 실행 중이면 즉시 반환한다.
- 값과 메타 행처럼 **서로 다른 저장소에 걸친 한 쌍의 전이는 하나의 락 안에서** 일어난다(§5 `SecretSyncStore`).

### 테스트 (전부 JVM, 기기 없이)

| 영역 | 테스트 |
|---|---|
| 파서·검증 | `spec/examples/workflows.json` 통과; 잘못된 문서 픽스처(중복 step id, 미지 변수, http URL, 11개 step…) 각각 지정된 오류 |
| 템플릿 | 변수 치환, 타임존 변환, 경로 안전 치환 |
| 선택 규칙 | 4단계 규칙 표 기반 |
| 백오프 | 8회 시퀀스 상한·지터 범위 |
| ResumableUploadPlanner | 200/308/404/5xx/401 시나리오, 청크 경계, 오프셋 재확인 |
| DriveUploadRunner | Ktor `MockEngine`으로 전체 흐름: 폴더 생성 → 3파트 업로드 중 2번째에서 5xx → 재개 → md5 불일치 시 재업로드 → meta 마지막 |
| WebhookRunner | Standard Webhooks 공식 테스트 벡터로 서명 검증; 429 `Retry-After`; 4xx 즉시 실패; 앞 단계 output 반영 |
| Executor | 프로세스 재시작 시뮬레이션(DB만 남기고 새 Executor) 후 중단 지점 재개; `abort`/`continue`; `NEEDS_AUTH` 전이; quiesce 순서 |
| Sync | pull/push/merge 케이스: **원격만 변경**, 로컬만 변경, **양쪽 변경 → id별 LWW**, 삭제 휴리스틱, 깨진 원격, Outdated schema 마이그레이션 후 push |
| SecretSync | PBKDF2·AES-GCM 실제 연산, AAD 위조, tombstone·삭제 워터마크, 여분 파일, fail-closed |
| **스키마 마이그레이션** (`MigrationTest`) | 버전 1 DDL로 만든 DB → migrate 후 기존 행 보존 + 새 테이블 사용 |
| 잡 스냅샷 (`JobSnapshotTest`) | 미지 스텝 타입이 그 잡만 FAILED로 격리, 목록·`selectDue`·`claimPurge` 영향 없음 |
| TransferReceiver | sha256 불일치 → nack; 메타 없는 고아 파트 24h 삭제 |
| Drive 용량 초과 (`DriveQuotaTest`) | 403 `storageQuotaExceeded` → `NEEDS_SPACE`(attempts 그대로, `next_run_at` null, `state_json` 비움), 이후 `runDueJobs`가 다시 고르지 않음, `retry()` 후 완주; 다른 403은 기존 경로 |
| 삭제 (`RecordingDeleteTest`) | 네 테이블 삭제, `RUNNING` → `Busy`, `deleteDrive` 실패해도 로컬은 지워지고 `driveError`가 담김, 삭제 vs `claimRunning` 경쟁, 커밋 시점 취소 |
| 다른 기기의 녹음 (`RemoteRecordingsTest`) | 폴더 나열 → 입양(파트 `deleted=1`+`drive_file_id`, `meta.json` 기록), 시작 시각순 정렬, 두 번째 조회는 요청 1번, meta 없는 폴더는 보류, 내 행은 덮지 않음, 폴더가 사라진 입양 행만 삭제, 같은 id 폴더 둘·재실행 폴더로 이동(옛 폴더 소멸·새 폴더 완료 둘 다), id 불일치·ULID 아님·파트 파일명 위조 거절, 계정 없음·스로틀·조회 실패, "로컬만 삭제"는 되살아나지 않음(+폴더 소멸 시 기록 정리, `clearIgnored` 후 재입양, 큐가 비워진 뒤에도 `drive_folder_id`로, `adopt`가 트랜잭션에서 거부), `uploaded` 참·`enqueue`=`PartsPurged`, 재생이 file id로 받음, 7일 뒤 스윕, Drive 삭제가 `drive_folder_id` 사용, 제목: rename→description+meta push, 입양 행도, 실패 시 pending 후 다음 조회, description→행 적용(pending이 이김), 빈 description 무시 |
| 연결 해제 (`ReclyCoreTest`) | 지우는 순서, 녹음·`recording` 행 보존, `busyRecordings`, Drive `files.delete` 미호출 |

`spec/` 스키마와 코어 직렬화 모델이 어긋나지 않도록, 테스트에서 예제 JSON을 파싱 → 직렬화 → 원본과 구조 비교한다.

---

## 11. Android·Wear (구 docs/11)

대상: Wear OS 6/7(API 36/37) 갤럭시 워치4 이상, Android 폰 API 34+. `minSdk 34`, `targetSdk 36`. 패키지명·서명키는
폰·워치 동일(Play 요구).

### 모듈

```
android/
  recording/   라이브러리. SegmentedRecorder(MediaRecorder), RecorderService(FGS microphone), SilenceMonitor,
               AndroidSecureStore · deviceId · SystemClock (폰·워치 공용)
  datalayer/   폰↔워치 경로·JSON 계약 한 벌
  app/         폰 앱. Compose. 워크플로우 편집, 녹음, 실행, 인증, Data Layer 수신
  wear/        워치 앱. Wear Compose. 녹음, 전송, 타일, Ongoing Activity
```

### `:android:recording`

- `SegmentedRecorder`
  - `MediaRecorder`: `AudioSource.MIC`, `OutputFormat.MPEG_4`, `AudioEncoder.AAC`, 16 kHz(미지원 시 44.1 kHz 폴백 후
    메타 기록), 모노, 32 kbps.
  - 세그먼트: `setMaxFileSize(segmentSec × bitrate/8 × 1.07)` + 미리 `setNextOutputFile(nextFile)`;
    `MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED`에서 이전 파트 확정(sha256 계산은 IO 스레드) →
    `RecordingRepository.addPart`. `setMaxDuration`은 `MAX_DURATION_REACHED`에서 녹음을 **정지**시키고
    `MAX_DURATION_APPROACHING`은 공개 SDK에 없으므로 쓰지 않는다.
  - `setPrivacySensitive(true)`는 `setOutputFormat` **이전**에 호출해야 한다(이후 호출 시 API 36에서
    `IllegalStateException`).
- `SilenceMonitor`: `AudioManager.registerAudioRecordingCallback` — `isClientSilenced` 전이를 `silenced` 구간으로
  기록.
- `RecorderService`: `foregroundServiceType="microphone"`, 알림 + `OngoingActivity`(워치), 액션: 정지. 시작은 반드시
  보이는 액티비티·타일·알림 액션에서(while-in-use 규칙). 정지 시 **즉시** `finalize`(제목 null) → 폰 UI는 그 뒤에 제목
  다이얼로그(`updateTitle`) → `jobs.enqueue`; 알림의 정지 액션은 바로 enqueue. 워치면 `TransferQueue.add`. 중복
  정지는 무시하고, finalize·enqueue는 서비스 수명과 무관한 스코프에서 끝까지 실행한다.
- `RecordingRecovery.reconcile()`: 앱 시작과 새 녹음 시작 전에 `status = recording`인 행을 찾아 디스크의 미등록
  파트를 등록·finalize하고, finalize됐지만 처리되지 않은 녹음을 `RecorderHost.onRecordingReady(recordingId,
  enqueue=true)`로 넘긴다(프로세스 사망·다이얼로그 중 종료 복구, §3).
- **enqueue 정책은 호스트가 정한다**: `RecorderService`와 `RecordingRecovery`는 `core.enqueue`를 직접 부르지 않고
  finalize 뒤 `RecorderHost.onRecordingReady(recordingId, enqueue)`를 호출한다. 폰 호스트는 `enqueue`면
  `core.enqueue` + `onJobsDue`, 아니면 제목 다이얼로그가 나중에 enqueue. 워치 호스트는 플래그와 무관하게
  `TransferQueue.add` — 워치는 Job을 만들지 않는다(ADR-002).

### 폰 `:android:app`

| # | 범위 |
|---|---|
| A1 | 프로젝트 골격, `:core` 의존, DI, DataStore, `ReclyCore` 조립 |
| A2 | 인증: Credential Manager 로그인 + `AuthorizationClient`(drive.file), `TokenProvider`, 보안 저장 |
| A3 | 녹음 화면 + `RecorderService` 연결, 워크플로우 선택 시트, 정지 후 제목·인원 입력(선택) |
| A4 | 녹음 목록: 상태(녹음 중/대기/업로드 중 n%/완료/실패/인증 필요/공간 없음), 수동 실행·삭제 |
| A5 | `WorkflowWorker`: `OneTimeWorkRequest`(유니크 `rec-jobs`, `NetworkType.CONNECTED`), `runDueJobs()` 호출, 실패 시 `Result.retry`(주기 인스턴스는 상한 뒤 success — 6h 보험이 죽지 않게); **매 패스 후 후속 실행 재계산**: WAITING의 `nextRunAt`과 PENDING(즉시) 중 최소로 `rec-jobs-next`(REPLACE, 과거면 지연 0) 하나를 무장, `alreadyRunning`이면 타이머를 건드리지 않고 60초 뒤 후속; enqueue 시에도 `rec-jobs-next` 0초 무장; 옛 "지금 올리기"가 쓰던 core `retry`(WAITING 포함) + `setExpedited`는 "다시 시도"가 그대로 쓴다; 설정 "Wi-Fi에서만" 변경 시 `rec-jobs-next`·`rec-jobs-periodic`을 새 제약으로 재무장(`rec-jobs`는 KEEP — 실행 중 패스를 취소하면 재시도 예산이 소모된다; 이미 큐에 있던 패스 1회는 옛 제약으로 돌 수 있다) |
| A6 | 워크플로우 편집 UI: 목록(행마다 "사용" + 삭제, 사용 중인 행은 배지), 이름/최소 길이, 단계 순서 편집(드래그), `drive.upload` 폴더 템플릿, `webhook` URL/시크릿, `transcribe` 폼과 provider 고지, 이 기기에 없는 시크릿 경고, 키 관리(값은 기기별, §5). 쓰기 규칙: 모든 문서 변경은 뮤텍스 안에서 `current()`를 다시 읽어 적용·저장(전체 문서 저장 경쟁 방지); 편집기는 연 시점의 `updatedAt`을 기억해 다른 창의 저장으로 바뀌었으면 저장을 거부하고 "다시 열기"; 시크릿 복사는 클립보드 민감 플래그 |
| A7 | 설정의 워크플로우 내보내기/가져오기(§5): SAF `CREATE_DOCUMENT`(기본 이름 `recly-workflows.json`)·`OPEN_DOCUMENT`, 가져오기는 교체 확인 뒤 적용, 키는 파일에 들어가지 않는다는 안내 |
| A8 | Data Layer 수신: `WearableListenerService`(`ChannelClient.onChannelOpened` → `receiveFile`, 경로 `/rec/part/…`·`/rec/meta/…`), sha256 검증, `MessageClient` ack `/rec/ack`·`/rec/ack-meta`, 메타 수신 시 등록 + enqueue + `onJobsDue`; 워크플로우 요약을 `DataClient` `/rec/workflows`(urgent)로 게시; `rec_phone` capability 선언 |
| A9 | 진입점: 빠른 설정 타일, 홈 위젯(시작/정지), 앱 단축. 타일·위젯에서 시작할 때 FGS 백그라운드 시작 예외를 **쓰거나 포기하거나** 둘 중 하나이지, 조용히 실패하지 않는다 |
| A10 | 설정: 계정(로그아웃 / 연결 해제 2행), 언어, Wi-Fi 전용, 동의 리마인더, **로그 내보내기** |
| A11 | Play 등록: Wear OS 폼팩터 포함, 스크린샷, 데이터 안전 양식("수집 없음") |

### 워치 `:android:wear`

| # | 범위 |
|---|---|
| W1 | 골격: Wear Compose M3, `standalone=false`(폰 앱 필수), `RecorderService` 재사용 |
| W2 | 메인 화면: 큰 시작/정지 버튼, 워크플로우 선택(`DataClient`로 받은 요약, 워치에서 고르지 않았으면 **"폰의 워크플로우"**(en `Phone's workflow`) — 두 워치 모두 같은 말이다), 경과 시간, 전송 대기 n개(폰을 찾아 넘기는 중이면 `전송 중 n개`(en `Sending n`) — 패스가 채널을 연 동안만이다, 2026-09-04). 화면은 둘뿐이고 내비게이션 라이브러리가 없다 — 워치에서의 여정은 "녹음"뿐이다 |
| W3 | `OngoingActivity`(Wear OS 6) / Live Updates(7): 워치페이스 칩, 탭하면 앱 |
| W4 | `TransferQueue`: `CapabilityClient`로 폰 노드 탐색, `ChannelClient.openChannel` → `sendFile` 파트별, ack 대기(타임아웃 5분), ack 시 삭제, 실패·미연결 시 큐 유지, 연결 이벤트에서 재시도. 판정 기준은 **"폰 꺼진 채 녹음 → 폰 켜면 자동 전송 완료"**이고, 그때까지 워치 화면은 "폰이 아직 갖고 있지 않다"를 정직하게 말한다 |
| W5 | 진입점: 타일(`launchAction`), 컴플리케이션(상태), **두 번째 런처 항목 "Recly 녹음"**(`QuickStartActivity` — 삼성 "홈 키 두 번 누르기" 설정은 앱만 고를 수 있고 엑스트라를 못 넘기므로, 이 항목이 자동 시작 엑스트라를 붙여 MainActivity로 넘기고 사라진다), **설정 안내 화면**("홈 키 두 번 → 녹음"). **"타일 탭 → 즉시 녹음"**, **"홈 키 두 번 → 즉시 녹음"**이 기준이다 |
| W6 | 햅틱: 시작 CLICK, 정지 DOUBLE_CLICK, 전송 완료 tick |
| W7 | 배터리: 녹음 중 Wi-Fi 요청 없음, 화면 켜두기 없음; 전송은 충전 중 우선(옵션) |

### 주의

- `dataSync` FGS는 쓰지 않는다(6h/24h 캡). 업로드는 WorkManager다.
- 워치 앱에는 인증·네트워크·워크플로우 실행 코드가 없다. `:core`에서 `model`·`recording`·`transfer`(송신 측 큐
  모델)만 쓰고 모듈에 HTTP 클라이언트가 아예 없다.
- Galaxy Wearable 앱의 배터리 최적화가 켜져 있으면 BT 프록시가 끊긴다 — 설정 안내에 포함한다.
- **삼성 sleeping apps는 WorkManager를 지연시킨다.** UI는 그것을 숨기지 않고 "n건 대기 중"으로 정직하게 표시한다
  (2026-09-02: "지금 올리기" 버튼은 사라졌다 — 앱을 열면 포그라운드 패스가 돌고, 실패한 잡은 "다시 시도"가 즉시
  돌린다).

---

## 12. macOS (구 docs/12)

대상: macOS 14.4+ (Core Audio process tap TCC 안정 버전), Apple Silicon 우선. 메뉴바 앱(`LSUIElement`), 샌드박스
없음(직접 배포).

### 구조

```
apple/
  Rec.xcworkspace
  RecKit/                       Swift 패키지 (iOS·watchOS·macOS 공용 + 플랫폼 조건부)
    Sources/RecKit/
      Recorder/                 SegmentedRecorder (AVAudioEngine 탭 → AVAudioFile AAC, 900초 교체) — 모든 Apple 타깃
      MacCapture/               #if os(macOS): ProcessTapCapture, MicCapture, TrackWriter(mic/sys/mix), DriftCompensator
      Detect/                   #if os(macOS): MicInUseMonitor, MeetingAppMonitor, MeetingDetector
      Transfer/                 #if os(iOS)||os(watchOS): WatchTransferQueue (WCSession)
      Auth/                     #if os(iOS)||os(macOS): GoogleAuth (GoogleSignIn) → TokenProvider
      Transport/                #if os(iOS)||os(macOS): BackgroundTransport (URLSession background)
      Workflow/                 CoreWorkflowDocuments, WorkflowInspector (두 셸 공용 편집기)
      CoreBridge/               ReclyCore(XCFramework) 조립, SecureStore(Keychain), FileSystem, Logger, Crypto
  RecMac/                       메뉴바 앱, SwiftUI
```

`ReclyCore.xcframework`는 `./gradlew :core:assembleXCFramework` 산출물이고 `apple/scripts/build-core.sh`가
`apple/RecKit/Frameworks/`로 스테이징한다. 정적 프레임워크라 **앱 타깃의 Other Linker Flags에 `-lsqlite3`**가 필요하다
(빠뜨리면 `_sqlite3_*` 미해결 심볼).

**워크플로우 편집 로직은 Swift로 두 번 짜지 않는다.** `CoreWorkflowDocuments`가 코어 `WorkflowDocuments`를 Swift에서
구현하고(`__current`/`__save`/`__writeFrozen`), 편집 블록은 코어가 요구하는
`suspend (WorkflowsDocument) -> WorkflowsDocument?`를 `DocumentMutation: KotlinSuspendFunction1`로 넘긴다 — 세 셸이
같은 뮤텍스·같은 staleness 규칙을 쓴다.

### 캡처 파이프라인

```
 마이크 (AVAudioEngine inputNode, 16 kHz 모노 변환)  ─┐
                                                    ├─► DriftCompensator ─► TrackWriter(mic) ─┐
 시스템 (CATapDescription 전역 tap, 자기 프로세스 제외 ─┘                     TrackWriter(sys) ─┼─► mix (합산 −6 dB 헤드룸) ─► TrackWriter(mix)
        → AudioHardwareCreateAggregateDevice → IOProc, 출력 장치 레이트)
```

- 세 `TrackWriter`는 같은 시작 시각·같은 세그먼트 경계(900초)를 공유한다. 파트 번호가 트랙 간 일치한다.
- `DriftCompensator`: 두 스트림의 **누적 프레임 수 vs 벽시계**로 레이트 차를 **60초마다 추정**해 시스템 트랙을
  리샘플하고(`AVAudioConverter`), 임계 초과 시 메타 `gaps`에 기록한다. 목표: 1시간 후 두 트랙 오프셋 < 20 ms —
  보정하지 않으면 시간당 수십 ms가 벌어진다.
- **에코**: AEC 없이 두 트랙을 저장한다. 출력 장치가 내장 스피커면 시작 시 한 줄 경고("헤드폰을 쓰면 상대 목소리가
  내 트랙에 섞이지 않습니다").
- 녹음 규칙: 정지·입력 재시작 시 `AVAudioConverter`를 `.endOfStream`으로 드레인한 뒤 세그먼트를 닫는다(48 kHz
  입력의 꼬리 프레임 보존); 닫힌 세그먼트를 읽어 되돌릴 수 없으면 등록하지 않고 `.pending`으로 남겨 finalize를
  보류; 복구는 읽을 수 없는 꼬리를 `.corrupt`로 격리하고 읽을 수 있는 파트가 없는 녹음은 삭제한다(§3);
  `applicationShouldTerminate`는 세션이 살아 있으면 `.terminateLater`로 정지·finalize·enqueue 뒤 종료; 기기 ID는
  Keychain이 아니라 `{dataDir}/device.id`(임시 서명 재빌드 시 키체인 ACL 모달 회피).
- **권한**: `NSMicrophoneUsageDescription`, `NSAudioCaptureUsageDescription`. tap 권한은 사전 확인 API가 없으므로 첫
  IOProc 시작에서 프롬프트하고, 실패하면 시스템 설정 "화면 및 시스템 오디오 녹음" 딥링크를 안내한다.
- **tap 재생성**: 출력 장치 변경(`kAudioHardwarePropertyDefaultOutputDevice`)·포맷 변경 시 tap을 다시 만들고 `gaps`에
  기록한다. 상태 줄은 **캡처 중인 출력 장치명**을 보여 준다.

### 미팅 감지 · 컨텍스트

- `MicInUseMonitor`: 기본 입력 장치의 `kAudioDevicePropertyDeviceIsRunningSomewhere` 리스너 + 기본 입력 변경 리스너.
- `MeetingAppMonitor`: 실행 중 앱 번들 ID 목록(`us.zoom.xos`, `com.microsoft.teams2`,
  `com.tinyspeck.slackmacgap`, `com.hnc.Discord`, 브라우저) — 마이크 사용 중 && 회의 앱 활성이면 "회의 중인가요? 녹음
  시작" 알림(클릭 한 번). 쿨다운 600초, 회의당 알림 1회. **브라우저 Meet은 창 제목까지만** 본다(AX 권한을 요구하지
  않는다).
- **캘린더 컨텍스트는 없다.** EventKit 읽기와 메타 필드 `context.calendar`(제목·시작·종료·참석자 이메일)는 제품
  전체에서 제거됐다 — 캘린더 접근은 참석자 이메일을 웹훅까지 실어 보내는 유일한 경로였고, 그 값을 얻는 대가로
  macOS만 권한 프롬프트를 하나 더 지고 있었다. 제목은 세 셸 모두 정지 후 입력한다.
- **종료 감지**: 마이크 사용 중이 아님이 60초 지속되면 "녹음을 끝낼까요?" 알림을 낸다. **자동 정지가 아니다** —
  앱은 대신 멈춰 주지 않는다.

### 메뉴바 앱

- **상태 아이콘**: 대기 / 녹음 중(빨강) / 업로드 중 / 오류. 오류 상태는 접근성 설명으로도 말한다.
- **메뉴바** 팝오버: 상태 노드 3개 + 최근 원장(20행씩 무한 스크롤; 상태, 펼치면 §9 원칙 2의 동작 — Drive 열기·다시 시도·상세·삭제 —
  가로 한 줄) + 동작, 상단에 알림 배너.
- **팝오버가 이 항목들을 담는다**: 시작·정지, 최근 원장, **워크플로우 편집 창**, 설정, 로그인. Windows의 AWT 메뉴는
  축약 폴백이다(macOS는 `.window` MenuBarExtra라 NSMenu가 없다).
- **팝오버는 이 앱의 창이 포커스를 가져가도 열려 있다** — 상세·워크플로우·설정 창이나 삭제 다이얼로그를 팝오버에서
  열고 그 안을 눌러도 닫히지 않는다. 닫히는 것은 다른 앱 클릭·상태 아이콘 클릭·Esc뿐이다(Mac은 `MenuBarPanel`의
  AppKit `NSPanel` + 전역 마우스 모니터, 2026-09-03; Windows 트레이 팝업은 포커스가 이 JVM의 다른 창으로 간 경우를
  구분한다, 2026-09-04).
- **실행기**: 앱 프로세스가 `runDueJobs()`를 (a) Job 생성 직후 (b) 5분 타이머 (c) 네트워크 복귀(`NWPathMonitor`)
  (d) `nextRunAt` 후속에 호출한다. `SMAppService`로 로그인 시 자동 실행.
- **워크플로우 편집 창**: 폰과 같은 기능. SwiftUI 폼 + `WorkflowInspector`(RecKit 공용). 데스크톱이 편집의 주
  무대다.

### 태스크

| # | 범위 |
|---|---|
| M1 | 워크스페이스, RecKit, XCFramework 연결, 메뉴바 골격 |
| M2 | `MicCapture` + `TrackWriter` → mic 단일 트랙 세그먼트 녹음 |
| M3 | `ProcessTapCapture`(전역 tap, 자기 제외) → sys 트랙; 권한 플로우·거부 UX |
| M4 | `DriftCompensator` + mix 트랙 |
| M5 | 인증(GoogleSignIn macOS) + `BackgroundTransport` + 실행기 연결 |
| M6 | 미팅 감지 + 알림 |
| M7 | **워크플로우 편집 창** + 설정의 워크플로우 내보내기/가져오기(§5): `NSSavePanel`(기본 이름 `recly-workflows.json`)·`NSOpenPanel`, 가져오기는 교체 확인 뒤 적용, 키는 파일에 들어가지 않는다는 안내 |
| M8 | 동의 리마인더(첫 녹음 시 1회, 설정에서 끔) + 관할별 안내문 + 스피커 경고. 리마인더는 *첫* 녹음 전에 묻고, "다시 묻지 않기"를 고르면 설정에서 다시 켤 수 있다 |
| M9 | 배포: Developer ID 서명, hardened runtime, notarytool, DMG(`apple/scripts/release-mac.sh`). Sparkle 자동 업데이트는 없다 |

### 동의 리마인더 · 관할별 안내문

로컬 캡처는 상대에게 **아무 표시도 남기지 않는다**(Zoom 자체 녹화와 달리 참가자 쪽에 다이얼로그도 배지도 없다).
그래서 고지 책임은 전적으로 사용자에게 있고, 앱이 할 수 있는 일은 그 사실을 한 번 분명히 말해 주는 것뿐이다(ADR-011).

**앱이 보여주는 것**

1. **리마인더** — "참가자에게 녹음을 알렸습니까?" + [알렸습니다] [취소]. 확인하지 않으면 녹음을 시작하지 않는다.
   설정에서 끌 수 있다(끄는 것도 사용자의 선택이고, 끈다고 책임이 옮겨 가지 않는다). 띄우는 시점은 기기마다 다르다 —
   Mac·Windows는 회의 모드 녹음마다(감지 여부 무관; 마이크만 녹음하는 메모에는 상대가 없다), 폰(iPhone·Android)은 회의를 구분할 수단이 없어
   **첫 녹음 전에 한 번만** 묻고 설정 문구에 그 차이를 적는다. **워치 둘에는 없다**(화면이 좁고 폰이 정본이며, 워치로
   녹음할 때의 책임은 같다).
2. **관할별 안내문** — "내 관할은?"을 누르면 아래 표를 요약한 화면. 언어별 리소스이고 법역 목록·링크는 공통이다.
   문구는 네 셸이 글자 그대로 같고 대조 테스트로 묶여 있다.
3. **상시 녹음 표시** — 은밀 모드는 만들지 않는다(App Store 2.5.14, Play 스토커웨어 정책). 메뉴바 빨간
   아이콘·트레이 아이콘·Live Activity·Ongoing Activity·시스템 마이크 표시.
4. Google 계정 연결 시의 OAuth 동의 화면(§6)은 **다른 동의**다 — Drive 접근에 대한 사용자 본인의 동의이지 회의
   참가자의 녹음 동의가 아니다. 안내문에서 이 둘을 섞지 않는다.

**관할 요약** (**법률 자문이 아니다** — 각자 확인해야 한다)

| 관할 | 요지 | 안내문이 말하는 것 |
|---|---|---|
| 한국 | 통신비밀보호법상 **당사자 녹음은 합법** — 본인이 참여한 대화는 상대의 동의 없이 녹음해도 처벌 대상이 아니고 별도 고지 의무도 없다. 반대로 **자신이 참여하지 않은 타인 간 대화의 녹음·청취는 범죄**다. 합법적으로 녹음한 내용이라도 **공개·유포**는 별도 책임이 될 수 있다 | "내가 참여한 대화만 녹음하세요. 내가 없는 자리의 대화를 녹음하는 것은 형사처벌 대상입니다." |
| 미국 (연방) | 연방 도청법은 one-party consent. 다만 **주법이 더 엄격하면 주법이 적용**된다 | "주마다 다릅니다. 아래 주가 관련되면 전원 동의를 받으세요." |
| 미국 (전원 동의 주) | 흔히 꼽히는 곳: California, Delaware, Florida, Illinois, Maryland, Massachusetts, Montana, New Hampshire, Oregon, Pennsylvania, Washington. **목록은 출처마다 다르고**(Connecticut·Hawaii를 넣고 Delaware를 빼는 정리도 있다) 주별로 "대화"와 "전화 통화"의 범위가 다르다. 원격 회의는 참가자가 여러 주에 흩어져 있어 **가장 엄격한 주를 기준으로 잡는 것이 실무 기준**이다 | "이 주가 하나라도 걸리면 시작 전에 전원 동의를 받고, 동의를 기록으로 남기세요." |
| EU / EEA (GDPR) | 회의 녹음은 개인정보 처리다. **적법 근거**가 필요하고, 참가자에게 **누가·왜·얼마나 보관하는지 고지**해야 하며, 열람·삭제 요청에 응할 수 있어야 한다. "순수 개인·가정 활동" 예외는 업무·상업적 녹음에 적용되지 않는다 | "업무 회의라면 시작 전에 고지하고 근거를 남기세요. 참가자가 삭제를 요구하면 Drive의 원본과 결과 파일을 지워야 합니다." |
| 그 외 | 확인되지 않음 | "여기에 없는 나라는 직접 확인하세요." |

**앱이 보장할 수 없는 것**(안내문에 같은 무게로 적는다)

- 참가자에게 **자동으로 알리지 않는다.** 회의 앱 채팅에 메시지를 넣는 기능은 미팅 앱 연동 없이는 불가능하므로,
  대신 붙여넣을 수 있는 **문구 복사 버튼**만 둔다.
- 관할을 **판별하지 않는다.** 기기 로케일로 나라를 추측해 안내문을 고르는 것까지가 전부이고, 참가자가 어디에 있는지는
  앱이 알 수 없다.
- 동의를 **기록하지 않는다.** "알렸습니다"를 누른 사실은 앱의 로컬 로그일 뿐 법적 증거가 아니다.
- 표의 내용은 **법률 자문이 아니고** 시점 요약이다(§열린 결정 "법률 검토").

이 절은 macOS 전용이 아니다 — Windows·Android·iPhone의 리마인더도 같은 문구 리소스를 쓴다.

### 열린 문제

- App Store 샌드박스에서 tap이 되는지 미확인 → 직접 배포. 이후 검증되면 App Store 병행.
- 내장 스피커 사용 시 에코. `setVoiceProcessingEnabled` 실험 결과에 따라 옵션화.

---

## 13. iOS·watchOS (구 docs/13)

대상: iOS 17+, watchOS 10+. Apple Watch는 iPhone 전용(Android 페어링 없음). `apple/` 워크스페이스의 `RecPhone`·
`RecWatch` 타깃이고 RecKit 공용 모듈(`Recorder`, `Transfer`, `Auth`, `Transport`, `Workflow`, `CoreBridge`)을 macOS와
함께 쓴다.

### iPhone

- **녹음**: RecKit `SegmentedRecorder`(AVAudioEngine → AAC `AVAudioFile`, 900초 교체). `AVAudioSession`
  `.playAndRecord`/`.default`, 옵션 `.allowBluetooth`. `UIBackgroundModes: audio`로 잠금 중 계속. 인터럽션(전화·Siri)
  → `silenced`/`gaps` 기록 후 재개.
- **표시**: Live Activity(경과 시간, 정지 버튼) — 잠금화면·Dynamic Island·워치 Smart Stack. **8시간 상한이면
  갱신**한다.
- **진입점**: App Intents `StartRecordingIntent(workflow)`, `StopRecordingIntent` → Siri·Shortcuts·액션 버튼.
  iOS 18 Control은 `OpenIntent`로 앱을 열어 시작한다(위젯 확장에서 장시간 오디오 세션 시작은 불안정하다).
- **인증**: GoogleSignIn-iOS 9.x, `additionalScopes: [drive.file]`. `TokenProvider`는
  `refreshTokensIfNeeded`. RecKit `GoogleAuth`·`AppleTokenProvider`를 macOS와 공유하고, iPhone은
  `signIn(presenting: UIViewController)`.
- **실행기**(the executor): 포그라운드는 RecKit `JobRunner`(잡 생성 직후 · 5분 타이머 · 네트워크 복귀 · `nextRunAt`
  후속 + 폰만의 다섯째 방아쇠인 앱 활성화). 앱이 화면에 없을 때는 아래 표대로 나뉜다.
- **UI**: 녹음, 목록, 워크플로우 편집(폰과 macOS 동일 기능, RecKit `WorkflowInspector`), 설정. SwiftUI.
- **App Review**: 4.8 예외 근거 심사 노트, 2.5.14 녹음 표시(Live Activity + 시스템 마이크 표시).

#### 배경에서 실제로 되는 일

| 단계 | 앱이 정지(suspended)된 동안 | 앱이 종료된 뒤 |
|---|---|---|
| Drive 청크 PUT | **된다** — `BackgroundTransport`가 청크를 임시 파일로 잘라 배경 `URLSession`(`app.recly.upload`) 업로드 태스크로 보낸다. 완료 이벤트가 플래너에 응답으로 들어가 다음 청크가 예약된다 | 전송은 끝나지만 결과를 기다리던 코루틴은 없다. iOS가 `handleEventsForBackgroundURLSession`으로 앱을 깨우고, 이벤트는 임시 파일만 정리한다. 오프셋은 `DriveApi`가 재개 시 Drive에 다시 물어보므로 유실이 아니다 |
| resumable 세션 시작 · `meta.json` · 웹훅 · Job 상태 기록 | 코어가 돌아야 하는 일이라 **안 된다** | 안 된다 |

즉 **배경에서 저절로 되는 것은 업로드 바이트뿐**이고, 나머지는 `BGProcessingTask`(`app.recly.jobs`)가 얻어 주는
실행 시간에 `runDueJobs()`가 처리한다. 스케줄 시점은 (a) 녹음 정지 직후, (b) 업로드 이벤트로 깨어난 직후, (c)
처리된 태스크마다 자기 후속 1개다. `Info.plist`에 `UIBackgroundModes: processing`과
`BGTaskSchedulerPermittedIdentifiers: [app.recly.jobs, app.recly.uploadNow]`가 있어야 등록이 된다(청크 PUT 자체는
배경 모드가 필요 없다). iOS 26이면 사용자가 행에서 시작한 패스(2026-09-02부터는 **"다시 시도"**; 식별자 이름은
옛 "지금 올리기"의 `uploadNow`를 그대로 쓴다)가 `BGContinuedProcessingTask`로 진행 UI를 띄우며
계속 돈다 — 등록은 와일드카드 `app.recly.uploadNow.*`, 제출은 `app.recly.uploadNow.{recordingId}`이고
`Info.plist`에는 **접두사만**(`.*` 없이) 올린다. `#available`로 가려져 있어 iOS 26 아래에서는 포그라운드 패스 +
`BGProcessingTask`가 전부다.

#### 워치 수신

`WCSessionDelegate.session(_:didReceive:)`에서 **동기적으로** 파일 이동 → sha256 검증 →
`transferUserInfo(["ack": …])`(도달 보장) → 메타 수신 시 등록·enqueue. `transferFile` 수신 측은 콜백 안에서 파일을
옮기지 않으면 삭제된다. 워크플로우 요약은 `updateApplicationContext`로 워치에 보낸다.

| # | 범위 |
|---|---|
| I1 | 타깃·RecKit·XCFramework |
| I2 | 녹음 + 배경 오디오 + Live Activity |
| I3 | 인증 + **목록** + 포그라운드 **실행기** |
| I4 | `BackgroundTransport` + `BGProcessingTask` |
| I5 | **워크플로우 편집** + 설정의 워크플로우 내보내기/가져오기(§5): SwiftUI `fileExporter`(기본 이름 `recly-workflows.json`)·`fileImporter`, 가져오기는 교체 확인 뒤 적용, 키는 파일에 들어가지 않는다는 안내 |
| I6 | WC 수신·ack(§3 워치 → 폰 전송 계약의 받는 쪽) |
| I7 | App Intents · 액션 버튼 · Control |
| I8 | TestFlight |

### Apple Watch

- **녹음**: RecKit 공용 세그먼트 레코더. `UIBackgroundModes: audio`(WatchKit의 `WKBackgroundModes`가 아니다).
  포그라운드에서 세션 시작 후 손목을 내려도 유지된다. 길이 제한 없음. 16 kHz AAC 32 kbps.
- **전송**: `WCSession.transferFile(url, metadata: [recordingId, part, track, sha256, file])` 파트별 + 메타 마지막.
  `didFinish` 콜백은 누락될 수 있으므로 **폰의 ack(`didReceiveUserInfo`)**를 완료 기준으로 삼는다. ack 받은 파트 삭제.
  재시도: 앱 활성화 시 미ack 파트 재전송(중복은 폰이 sha256으로 무시).
- **진입점**: 컴플리케이션(상태 + 탭 시작), App Shortcut → Watch Ultra 액션 버튼, Double
  Tap(`handGestureShortcut(.primaryAction)`)은 정지에.
- 인증·네트워크 없음. 워크플로우 요약은 `applicationContext`.

| # | 범위 |
|---|---|
| WA1 | 타깃·RecKit(watchOS 슬라이스) |
| WA2 | 녹음 + 배경 오디오 |
| WA3 | `transferFile` 큐 + ack |
| WA4 | 컴플리케이션·App Shortcut·Double Tap |
| WA5 | 햅틱(`WKInterfaceDevice.play(.start/.stop/.success)`) — 셋째 햅틱(전송 완료)은 "이제 내 손을 떠났다"를 뜻한다 |
| WA6 | 인터럽션(전화·Siri) 처리 |

### 주의

- watchOS 앱 75 MB 상한 — 링크된 `RecWatch.app`은 13 MB로 여유가 있다.
- 모든 Apple 타깃에 **`-lsqlite3` 링크가 필요**하다(정적 XCFramework는 링커 옵션을 내보내지 않는다).
- 시뮬레이터 빌드는 **`apple/scripts/build-sim.sh`**로 한다(`ARCHS=arm64`를 명령줄에 고정하는 래퍼):
  `ReclyCore`에 x86_64 시뮬레이터 슬라이스가 없고, SwiftPM 패키지(RecKit)는 프로젝트 레벨
  `ARCHS`/`EXCLUDED_ARCHS` 조건부도, arch를 명시한 `-destination`도 무시한다 — 셋 다 시도해 확인했고,
  커맨드라인 빌드 설정만 도달한다. x86_64 코어 타깃 추가는 하지 않는다.

---

## 14. Windows (구 docs/14)

대상: Windows 11(프로세스별 loopback은 build 20348+; 그 이하는 엔드포인트 loopback 폴백).

### 구조

```
windows/
  app/               Compose Desktop (JVM). 트레이, 워크플로우 편집, 실행기, 인증(loopback PKCE), 헬퍼 관리
  capture-helper/    Rust 바이너리. WASAPI 마이크 + loopback 캡처, 리샘플, 드리프트 보정, 세그먼트 파일 작성,
                     마이크 사용 감지
```

JVM ↔ 헬퍼: 앱이 헬퍼를 spawn하고 stdin으로 명령(JSON line: `start {dir, base, segmentSec, tracks}`, `stop`,
`detect on/off`), stdout으로 이벤트(`part_done {…sha256}`, `level {peaks[]}`, `mic_in_use {app}`, `error`)를
받는다. 파일 이름은 앱이 준 `base`로 만든다 — 헬퍼가 이름을 짓지 않는다. **헬퍼가 죽으면 앱이 마지막 파트까지를
finalize한다.** stdout이 닫히는 것이 앱이 기다리는 신호다. stdout은 프로토콜이고 stderr는 로그다.

### 캡처

- 마이크: WASAPI 캡처(공유 모드, 이벤트 구동). `wasapi` crate.
- 시스템: 기본 렌더 엔드포인트 loopback(`AUDCLNT_STREAMFLAGS_LOOPBACK`). 무음 시 콜백이 없으므로 타이머로 무음
  프레임을 삽입한다. 프로세스별 loopback은 아직 없다 — 전역이다.
- 인코딩: **번들 ffmpeg**(ADR-019)로 파이프. MF 경로는 `--encoder mf`로 남아 있고, CI의 `--self-test`가 두 포맷을
  실제로 시도해 근거를 찍는다.
- 트랙·세그먼트·메타 규칙은 macOS와 동일하다. 드리프트 보정도 같은 방식이고 목표도 같다(1시간 < 20 ms).
  녹음 모드(마이크만 / 회의)는 macOS와 같은 설정이고 마이크만 모드는 `mono` 한 트랙이다(2026-09-03).
  기본값은 Windows가 `회의`, macOS가 `마이크만`이다 — Mac은 시스템 오디오 캡처에 권한 프롬프트가 있어 첫 실행을
  마이크로 시작하고, Windows의 루프백은 프롬프트가 없다(2026-09-03).
- **권한**: 프롬프트가 없다. 설정 → 개인정보 → 마이크 → "데스크톱 앱 허용"이 꺼져 있으면 레지스트리
  (`HKCU\…\ConsentStore\microphone`)로 감지해서 안내한다.

### 감지

- 마이크 사용: `IAudioSessionManager2::GetSessionEnumerator`(캡처 엔드포인트)의 활성 세션 + 프로세스
  이름(`Zoom.exe`, `ms-teams.exe`, `slack.exe`, `Discord.exe`, 브라우저) + 창 제목(`EnumWindows`).
- 규칙은 순수 Kotlin이고 macOS와 **같은 상태 기계**다: 마이크 사용 × 회의 앱 × 쿨다운 600초, 회의당 알림 1회,
  **60초 유휴 → "녹음을 끝낼까요?"**(End the recording?) — 자동 정지가 아니다. 자동 녹음도 없다(ADR-011).
- 마이크 신호는 그때그때 헬퍼가 다르다. 녹음 중이 아니면 감지 전용 헬퍼가 `detect on`으로 보고하고, 녹음이 시작되면
  **녹음 중인 헬퍼**의 `mic_in_use`를 쓴다 — 헬퍼는 자기 프로세스를 세션 목록에서 빼므로 그래야 조용한 회의와 Recly
  자신이 연 마이크를 구별할 수 있다. 교대는 한 방향씩 기다리고, 소유권은 **녹음 세션마다 토큰**이다. 토큰이 맞지 않는
  `resume`·`mic_in_use`·알림 액션은 버린다. 두 헬퍼가 동시에 살아 있는 구간은 없다.
- 알림은 AWT 트레이 풍선이라 **버튼이 없다**(액션 버튼이 있는 토스트 라이브러리는 Windows 전용 런타임 의존성이다).
  풍선 클릭이 곧 수락이고, 확실한 경로는 트레이 메뉴 항목이다.
- **캘린더 컨텍스트는 없다**(§12와 같이 제품 전체에서 제거됐다). 제목은 정지 후 입력한다.

### 앱

- 트레이: 상태 아이콘, 시작/정지, 최근(원장, 20행씩 무한 스크롤), 편집 창, 설정, 알림 배너. 시작 시 자동 실행(launch at login)은
  `HKCU\…\Run`이다(Mac의 `SMAppService`에 해당한다).
- 인증: §6 Windows 절(Ktor CIO 127.0.0.1 서버 + 시스템 브라우저 + PKCE). refresh token은 Credential Manager(JNA).
- **실행기**: Job 생성 직후 + 5분 타이머 + 네트워크 복귀 + `nextRunAt` 후속.
- 패키징: `jpackage` MSI. 헬퍼와 ffmpeg(LGPL 공유 빌드)를 `app/resources/windows-x64/`에 넣고, 설치된 앱이
  `compose.application.resources.dir`로 받아 헬퍼에 `--ffmpeg <경로>`로 넘긴다. ffmpeg LGPL 고지는
  `THIRD-PARTY-ffmpeg.md`로 설치본에 함께 들어간다. MSI는 Windows에서만 만들 수 있어 CI(`windows-latest`)가 만든다.

### 개발 호스트(macOS) 대체

개발 머신이 macOS이므로 Windows 전용 부분은 인터페이스 뒤에 있고 macOS에서는 스텁이 선택된다.

| 기능 | Windows | macOS(개발 호스트) |
|---|---|---|
| 시크릿·토큰 저장 | Credential Manager (`WindowsCredentialStore`, JNA) | `{dataDir}/dev-secure-store.json` — **암호화 없음, 개발 전용** |
| 자동 실행 | `HKCU\…\Run` | no-op, 설정에서 비활성 표시 |
| 데이터 디렉터리 | `%LOCALAPPDATA%\Recly` | `~/Library/Application Support/app.recly.windows` |
| 캡처 헬퍼 | MSI 리소스의 `recly-capture-helper.exe` | 없음 → 트레이 "캡처 헬퍼 없음"(페이크 헬퍼로 대체) |
| 실행 중인 앱·창 제목 | 프로세스 테이블 + `EnumWindows` | 항상 비어 있음 → `RECLY_DETECT_PROCESSES`로 대체 |
| 마이크 "데스크톱 앱 허용" | 레지스트리 | 항상 `UNKNOWN`(안내 문구 안 뜸) |
| 회의 알림 | 트레이 풍선 | 같은 API — macOS 알림 센터로 뜬다 |

### 태스크

| # | 범위 |
|---|---|
| N1 | Compose Desktop 골격, `:core` JVM 의존, 트레이 |
| N2 | 헬퍼: 마이크 캡처 → PCM 세그먼트 → ffmpeg → m4a |
| N3 | 헬퍼: loopback + 드리프트 보정 + mix |
| N4 | 인증 + 실행기 |
| N5 | **감지** + 알림 |
| N6 | 편집 창 + 설정의 워크플로우 내보내기/가져오기(§5) |
| N7 | MSI + 서명(SmartScreen 경고 없음) |

---

## 15. 프라이버시·데이터 흐름 (구 docs/15)

이 절은 **Recly가 설치된 기기에서 데이터가 밖으로 나가는 모든 경로**를 하나도 빼지 않고 적는 것이 목적이다.
사용자에게 보여줄 문장은 [`docs/policy/privacy-policy.md`](policy/privacy-policy.md)이고, 이 절은 그 문장의
근거이자 구현 계약이다. **새 네트워크 호출을 추가하는 변경은 이 절을 함께 고쳐야 한다.**

### 한 줄 요약

Recly는 **서버가 없다.** 데이터가 나가는 곳은 (1) 사용자의 Google Drive, (2) 사용자가 워크플로우에 적어 넣은 웹훅
주소, (3) 사용자가 **선택 단계로 직접 넣었을 때만** STT provider, (4) **사용자가 짝 지은 자신의 다른
기기**(워치 ↔ 폰) — 앞의 셋은 사용자의 계정·사용자의 키로 가고, 넷째는 사용자 자신의 기기 두 대 사이에 머문다. 그
외에는 아무 데도 가지 않는다.

### §0 기기 안에만 있는 것

| 데이터 | 어디에 | 나가는가 |
|---|---|---|
| 녹음 원본(`.m4a` 파트) | §3 로컬 저장 경로 | Drive 업로드 단계가 있을 때만. 없으면 네트워크로는 나가지 않는다 — 다만 **워치에서 녹음한 것은 업로드 단계가 없어도 짝 지은 폰으로 넘어간다**(§4) |
| `meta.json` | 같은 폴더 | 업로드 단계가 함께 올린다. 워치의 것은 파트와 함께 폰으로 넘어간다 |
| 녹취 결과 파일 | 같은 폴더(로컬 사본) | Drive 녹음 폴더에 함께 쓴다 |
| Job·단계 상태, 재시도 예산, 업로드 세션 오프셋 | 로컬 SQLite(`rec.db`) | **절대 나가지 않는다**(원칙 2) |
| `deviceId`(설치마다 새 UUID v4) | 보안 저장소 / macOS는 `{dataDir}/device.id` | 웹훅 payload `data.device.id`에만 실린다(사용자가 웹훅을 넣었을 때) |
| 로그(`rec.*`, `shell.*`, `detect.*`) | 플랫폼 로그(Android `Log`, Apple `os.Logger`, JVM stdout) | 사용자가 "로그 내보내기"로 직접 꺼낼 때만 |
| 언어·Wi-Fi 전용 같은 앱 설정 | 플랫폼 설정 저장소 | 나가지 않는다(동기화 대상이 아니다) |

### §1 Google Drive — 사용자 자신의 Drive

| 항목 | 내용 |
|---|---|
| 스코프 | `drive.file` 하나뿐(ADR-009). non-sensitive. **전체 `drive` 스코프를 요청하지 않는다** — 앱이 만들지 않은 사용자의 다른 파일은 읽을 수 없다 |
| 올라가는 것 | 녹음 폴더 `{folder 템플릿}/{base}/`(기본 `recly/{yyyy}/{yyyy}-{MM}/`) 안의 파트 `.m4a`, `{base}.meta.json`, 그리고 전사 단계를 넣었다면 `{base}.transcript.json/.txt` |
| 폴더에 붙는 메타 | 폴더 `description`에 제목, `appProperties`에 `recordingId`·`workflowId` |
| appDataFolder | **쓰지 않는다.** 워크플로우 정의도 시크릿 값도 기기에만 있고(§5), 기기 사이로 옮기는 것은 사용자가 직접 하는 내보내기/가져오기뿐이다 |
| 받는 것 | 업로드 검증용 `md5Checksum`·파일 메타. 사용자의 다른 파일 목록은 요청하지 않는다 |
| 누가 보는가 | 사용자와, 사용자가 그 폴더를 공유한 사람. **Recly는 이 파일들에 접근할 수 있는 서버가 없다** — OAuth 토큰은 기기 보안 저장소에만 있고 Google API 호출에만 쓰이며 Recly에는 전송되지 않는다 |
| 통제 | Google 계정 설정(<https://myaccount.google.com/permissions>)에서 언제든 연결 해제. 앱 안의 "연결 해제"도 네 셸 모두에 있다(§3) — 로그아웃과 별개 항목이고, grant revoke(Android `AuthorizationClient.revokeAccess`, Apple GoogleSignIn `disconnect()`, Windows `oauth2.googleapis.com/revoke`) + `ReclyCore.disconnect`의 로컬 정리를 함께 한다 |

Recly가 부르는 Google 엔드포인트는 Drive API(`www.googleapis.com`)와
OAuth(`accounts.google.com`·`oauth2.googleapis.com`)뿐이다.

다만 **로그인 자체가 계정 이메일을 알려 주고, 그 값이 기기에 남는다**:

| 셸 | 어디서 오나 | 어디에 남나 | 지워지는 때 |
|---|---|---|---|
| Android 폰 | Credential Manager의 Google ID token(`GoogleIdTokenCredential.id`가 이메일 주소) | 보안 저장소 `account/email` | 로그아웃 |
| iPhone · Mac | GoogleSignIn SDK의 `profile.email` — 앱이 `additionalScopes`로 더하는 것은 Drive 두 개뿐이고 이메일은 SDK 로그인에 딸려 온다 | SDK의 키체인 항목 + 다음 로그인 힌트로 `UserDefaults`의 `app.recly.auth.lastAccount` | 로그아웃 |
| Windows | 받지 않는다(프로필 스코프 없음) | 저장하지 않는다 | — |

어느 쪽도 Recly로 전송되지 않는다(받을 서버가 없다). 계정을 다시 고르기 위한 로컬 값이다.

### §2 웹훅 — 사용자가 적어 넣은 주소

`webhook` 단계가 있을 때만, 사용자가 그 단계에 적은 URL로 **POST 한 번**. 받는 쪽은 사용자의 n8n·Cloudflare
Worker·자기 스크립트다(Recly가 운영하는 수신기는 없다).

- **본문에 들어가는 것**(§4, `spec/webhook.payload.schema.json`): 녹음 메타(`recordingId`, `source`, `platform`,
  제목, 시작·종료 시각, 길이, 타임존, 트랙 목록, `context`), 파일 목록(이름·바이트·sha256과 **Drive 파일
  id·`webViewLink`**), 폴더 경로와 Drive 폴더 id, 워크플로우 id·이름, 기기 id·플랫폼·기기 이름.
- **`context`에는 다른 사람의 식별자가 없다.** `meta.context`는 payload 빌더가 손대지 않고 그대로 싣지만, 그 안에
  있는 것은 `app`(감지된 회의 앱의 번들 id, 데스크톱 전용)과 `participants`(인원 수 정수)뿐이다.
  **`context.calendar`는 제거됐다** — 그전에는 macOS가 EventKit로 읽은 회의 제목·시각·**참석자 이메일 주소 목록**이
  여기 실려 웹훅 수신기까지 갔다. 지금은 어떤 셸도 캘린더를 읽지 않으므로 그 경로 자체가 없다.
- **본문에 들어가지 않는 것**: 오디오 바이트 자체, 녹취 **본문**(파일 항목만 실린다), 액세스 토큰, 시크릿 값.
- `webViewLink`는 링크일 뿐 공개 URL이 아니다 — Drive 권한이 있는 사람만 연다. 그래도 **웹훅 수신자는 그 파일이
  존재한다는 사실과 위치를 알게 된다.**
- 서명: Standard Webhooks. 시크릿(`whsec_…`)은 기기 보안 저장소에 있고 본문에 실리지 않는다. `secretRef`를 비우면
  서명 없이 나간다(그 선택도 사용자의 것이다).
- 전송은 HTTPS만. 예외는 `127.0.0.1`·`localhost`(로컬 수신기). 리다이렉트를 따르지 않고, 타임아웃 30초, 실패 시
  재시도한다.

### §3 STT provider — 사용자가 그 단계를 넣었을 때만

전사는 **고정된 후처리 단계가 아니다.** 사용자가 자기 워크플로우에 `transcribe` 단계를 직접 추가하고 자기 API
키를 넣었을 때만 존재한다(§8). 넣지 않으면 이 절 전체가 일어나지 않는다.

| 무엇이 | 어디로 | 언제 |
|---|---|---|
| **오디오 전체**(파트를 이어 붙인 `mono` 또는 `mix` 트랙 한 파일) | 사용자가 `provider`로 고른 STT 서비스 | `transcribe` 단계 실행 시 |
| 화자 수 힌트, 언어 설정 | 같은 요청 | 같음 |

- 호출은 **기기에서 provider로 직접** 간다. 중간 서버·릴레이·콜백 URL이 없다. Recly는 이 요청의 내용을 볼 수 없다.
- 인증은 **사용자의 키**다. 청구도 사용자의 계정으로 간다.
- 그 다음 provider가 데이터를 얼마나 오래 갖고 있는지, 학습에 쓰는지는 **그 provider의 정책**이고 Recly가 통제하지
  못한다.
- **이 고지는 세 셸의 편집기에 있다.** `transcribe` 단계 폼의 **provider 선택 바로 아래**에 세 줄이 뜬다.
  문구는 세 셸이 글자 그대로 같고(대조 테스트) en·ko 양쪽에 있다.

  `transcribe`(ko / en):

  > 이 단계를 실행하면 녹음 오디오 전체가 고른 업체로 전송됩니다.
  > 얼마나 보관하는지, 학습에 쓰는지는 그 업체의 정책이고 Recly가 통제하지 못합니다.
  > 쓰기 전에 업체의 정책을 확인하세요.

  > Running this step sends the whole recording to the provider you pick.
  > How long they keep it, and whether they train on it, is that provider's own policy — Recly does not control it.
  > Read the provider's policy before you use it.

- **링크는 아직 없다.** 아래 표의 provider별 정책 URL이 확정되지 않았으므로 문장만 넣고 링크는 걸지 않았다(없는
  URL을 만들지 않는다).

#### provider 보관 정책 — 확인 대상 (**전부 미확인, 방침 게시 전 확인할 것**)

받는 것은 열넷 모두 같다 — **이어 붙인 오디오 트랙 한 파일**과 그 요청에 실린 **언어·화자분리 옵션**(§3 위 표).
다른 것은 그 다음 그 업체가 무엇을 하느냐이고, 그것이 아래에서 확인할 것이다.

| provider | 확인할 것 | 링크(경로 미확인) |
|---|---|---|
| AssemblyAI (STT) | 오디오·전사 보관 기간, 삭제 API, 학습 사용 여부, 데이터 리전 | <https://www.assemblyai.com/> → Legal · Privacy Policy |
| CLOVA Speech (네이버 클라우드, STT) | 업로드 파일 보관, 국내 리전, 학습 사용 여부 | <https://www.ncloud.com/> → 이용약관 · 개인정보처리방침 |
| RTZR 리턴제로 (STT) | job 결과 보관 기간, 삭제 방법, 학습 사용 여부 | <https://www.rtzr.ai/> → 개인정보처리방침 |
| OpenAI (STT) | 오디오 보관 기간, API 데이터의 학습 사용 여부, 데이터 리전 | <https://openai.com/> → Privacy Policy · API data usage |
| Groq (STT) | 오디오 보관 기간, 학습 사용 여부, 무료 등급과 유료 등급의 차이 | <https://groq.com/> → Privacy Policy |
| Together AI (STT) | 오디오 보관 기간, 학습 사용 여부, 데이터 리전 | <https://www.together.ai/> → Privacy Policy |
| Mistral AI (Voxtral, STT) | 오디오 보관 기간, 학습 사용 여부, EU 리전 | <https://mistral.ai/> → Privacy Policy |
| ElevenLabs (Scribe, STT) | 오디오 보관 기간, 학습 사용 여부, zero-retention 옵션 유무 | <https://elevenlabs.io/> → Privacy Policy |
| Deepgram (STT) | 오디오 보관 기간, 학습 사용 여부, 데이터 리전 | <https://deepgram.com/> → Privacy Policy |
| Microsoft Azure AI Speech (STT) | 오디오 보관 기간, 학습 사용 여부, 사용자가 고른 리소스 리전 | <https://azure.microsoft.com/> → Microsoft Privacy Statement · Azure AI 서비스 약관 |
| 다글로 Daglo (STT) | job 결과 보관 기간, 삭제 방법, 국내 리전, 학습 사용 여부 | <https://daglo.ai/> → 개인정보처리방침 |
| Speechmatics (STT) | job·전사 보관 기간, 학습 사용 여부, 리전 선택(`eu1`/`us1`) | <https://www.speechmatics.com/> → Privacy Policy |
| Rev AI (STT) | job 보관 기간, 삭제 API, 사람이 듣는지, 학습 사용 여부 | <https://www.rev.ai/> → Privacy Policy |
| Gladia (STT) | 업로드 파일·결과 보관 기간, 학습 사용 여부, EU 리전 | <https://www.gladia.io/> → Privacy Policy |

**작성 규칙**: 위 표의 내용을 개인정보처리방침이나 앱 문구로 옮길 때 "이 provider는 N일 보관한다"처럼 단정하지
않는다. 확인 전까지는 "provider의 정책을 따르며, 링크에서 확인하라"로만 쓴다. 확인되지 않은 보관 기간을 지어내지
않는다.

### §4 짝 지은 기기 간 전송 — 워치 ↔ 폰

워치는 Drive도 네트워크도 쓰지 않는다(ADR-002). 그래도 **데이터는 워치를 떠난다** — 짝 지은 폰으로. 이 경로는
워크플로우에 업로드 단계가 하나도 없어도 일어나고, Drive·웹훅·provider 어느 것과도 무관하다.

| 방향 | 무엇이 | 어떻게 |
|---|---|---|
| 갤럭시 워치 → Android 폰 | 녹음 파트 `.m4a` 파일, 그리고 전부 ack된 뒤 `meta.json` | Wear Data Layer `ChannelClient.openChannel` → `sendFile`. 파일 메타는 별도 payload가 아니라 채널 경로에 실린다(`/rec/part/{recordingId}/{part}/{track}/{sha256}/{file}`·`/rec/meta/{recordingId}`). 폰은 `WearableListenerService.onChannelOpened` → `receiveFile`로 받고 `MessageClient`로 ack한다 |
| Apple Watch → iPhone | 같음 | WatchConnectivity `WCSession.transferFile(_:metadata:)`. recordingId·part·track·sha256·파일명이 `metadata` 딕셔너리에 실린다. 폰은 `WCSessionDelegate.session(_:didReceive:)`로 받고 `transferUserInfo`로 ack한다 |
| Android 폰 → 갤럭시 워치 | **워크플로우 요약**: 워크플로우당 `id`·`name` 두 필드뿐, **단계 내용은 실리지 않는다** | `DataClient.putDataItem` 경로 `/rec/workflows` |
| iPhone → Apple Watch | 워크플로우 요약: `id`·`name`과 앱 언어뿐, 단계 내용 없음 | `WCSession.updateApplicationContext` |

- **두 기기 모두 사용자 자신의 기기다.** 전송은 OS의 페어링 전송로를 타고 **Recly의 서버는 관여하지 않는다** —
  받을 서버가 없다. 워치 모듈에는 HTTP 클라이언트조차 없다.
- 녹음 파일 전송은 **가까이 있는 노드로만** 나간다 — Android는 `isNearby`가 아닌 노드를 걸러 Google 클라우드 릴레이
  경유를 제외하고, Apple은 `WCSession`뿐이다. 다만 **`/rec/workflows` DataClient 항목에는 그 필터가 없어** Play
  Services가 고르는 전송로를 그대로 탄다(워크플로우 id·이름·on/off·소스뿐).
- 방향은 녹음에 대해 **한 방향**이다. 워치 → 폰만 있고, 녹취 본문이나 워크플로우 단계·시크릿·Job 결과가
  폰 → 워치로 가는 경로는 없다.
- 워치는 ack를 받으면 자기 사본을 지운다 — 워치에 녹음 이력이 남지 않는다.
- 키·토큰은 이 경로로 가지 않는다. 워치의 보안 저장소에 담기는 것은 device UUID뿐이다.

### §5 BYO 키와 토큰 — 기기 보안 저장소에만

키 값은 **워크플로우 정의에 들어가지 않는다.** 정의에는 이름(`secretRef`)만 있고 값은 기기마다 따로 넣는다
(ADR-008). 그래서 Drive의 `workflows.json`에도, 웹훅 payload에도, 로그에도 키가 없다. 저장 기전은 §5의 시크릿 표와
같다.

- 여기 담기는 것: Google access/refresh token(`tokens` 네임스페이스), 웹훅 서명 시크릿과 STT API
  키(`secrets` 네임스페이스), `deviceId`.
- **키가 나가는 유일한 경로는 provider 인증이다.** STT 키는 사용자가 그 단계를 넣었을 때 요청 헤더로 provider에
  그대로 실린다. 즉 "기기 밖으로 나가지 않는다"가 아니라 **"Recly로는 가지 않고, 사용자가 고른 provider에만
  간다"**가 맞는 문장이다. 웹훅 서명 시크릿은 어디에도 실리지 않는다(HMAC 계산에만 쓴다).
- **기기 간 동기화는 없다.** 키는 입력한 기기에만 있고, 키가 없는 기기에서 그 단계는 `MISSING_SECRET`으로 즉시
  실패한다. 내보낸 워크플로우 파일에도 값은 들어가지 않는다 — 파일에 있는 것은 `secretRef` 이름뿐이다(§5).
- 개발 호스트 예외: macOS에서 Windows 앱을 돌릴 때 쓰는 `DevFileSecureStore`는 평문 base64 JSON이다. **개발
  전용**이고 Windows 빌드에서는 선택되지 않는다.

### §6 수집하지 않는 것

- 분석·사용 통계·이벤트 수집 없음.
- 크래시 리포팅 없음(Firebase/Crashlytics/Sentry/AppCenter 계열 의존성이 네 셸 어디에도 없다).
- 원격 설정·A/B·광고 식별자 없음.
- Recly 쪽 **계정이 없다.** 회원가입도, 이메일 수집도, 사용자 식별자도 없다. 로그인은 사용자의 Google 계정에 대고
  하는 것이고 그 결과 토큰은 기기에만 있다.
- 업데이트 확인·라이선스 확인 같은 "우리 서버" 호출도 없다.

### §7 데이터 삭제

정본 규칙은 §3 보관 · 삭제. 요약:

| 사용자가 원하는 것 | 방법 | 남는 것 |
|---|---|---|
| 이 녹음을 없애기 | 목록에서 "삭제" → `로컬만`(기본값) 또는 `Drive 폴더도`. 네 셸 모두에 있다 | 기본값을 쓰면 Drive의 파일은 남는다(사용자의 파일이므로). `job`·`step_run` 행은 함께 지워진다 |
| 자동 삭제 | 업로드가 성공(ack)한 뒤 로컬 파트는 자동으로 지워진다(ADR-017). `meta.json`·DB 행·녹취 사본은 남는다 | 기간 기반 자동 삭제는 없다. Drive가 꽉 차 Job이 `NEEDS_SPACE`로 파킹되면 그 Job은 DONE이 아니므로 **로컬 원본은 지워지지 않고 그대로 기기에 남는다** |
| 이 기기에서 그만 쓰기 | 로그아웃 | 다른 기기·Drive 영향 없음. 녹음·Job·시크릿은 전부 남는다 |
| Recly의 Drive 접근을 끊기 | 앱의 "연결 해제" 또는 Google 계정 설정 | Drive의 녹음 파일은 남는다 — 연결 해제는 `files.delete`를 **한 번도 부르지 않는다**. 이 기기의 토큰·시크릿·Job·`sync_state`·폴더 캐시는 지워지고, **녹음 파일과 `recording`/`part` 행은 사용자가 "녹음도 함께 삭제"를 고르지 않는 한 남는다** |
| 전부 지우기 | 앱 삭제 + 연결 해제 + Drive에서 `recly/` 폴더 삭제 | **앱 삭제로 전부 지워지는 것은 Android/Wear뿐이다.** macOS는 `~/Library/Application Support/app.recly.mac/`와 키체인 항목, Windows는 `%LOCALAPPDATA%\Recly\`와 자격 증명 관리자 항목이 남고, iOS·watchOS는 키체인 항목이 남을 수 있다(Apple이 삭제를 보장하지 않는다). 플랫폼별 정리 방법은 `docs/policy/privacy-policy.md` §7 |
| provider가 가진 사본 | Recly가 대신 지울 수 없다 | 해당 provider의 콘솔·정책을 따라 사용자가 직접 |

**녹음 삭제가 지우는 것.** `RecordingRepository.delete`가 한 트랜잭션 안에서 `step_run` → `job` → `part` →
`recording`을 지우고 같은 잠금 구간에서 디렉터리를 지운다. 그래서 그 녹음의 워크플로우 정의 스냅샷
전문(`job.workflow_json`), 실패 메시지 원문(`step_run.last_error`), 재개 상태(`state_json` — Drive resumable 세션
URI·오프셋·`fileId`, STT provider job 식별자), 단계 출력(`output_json`)이 **UI에 보이지 않은 채 DB에 남는 일이
없다.**

**연결 해제가 남기는 것.** revoke가 실패했을 때 **grant는 Google 쪽에 그대로 서 있다** — 로컬 정리는 이미 끝났으므로
토큰·시크릿·Job은 이 기기에서 사라졌지만 Google 계정의 앱 목록에는 Recly가 남아 있다. 앱은 그것을 revoke debt로
기록하고 권한 페이지 링크와 함께 말한다. 로컬 정리가 실패하면 `DisconnectPhase`가 `REVOKED_CLEANUP_OWED`로 남아 다음
실행에서 이어서 갚고, 그 사이 로그인은 막힌다. `RUNNING` Job 때문에 지우지 못한 녹음은 그 Job 행과 함께 남는다.

### §8 이 절을 고쳐야 하는 변경

새 네트워크 호출, 새 단계 타입, 새 스코프, 새 저장 위치, 텔레메트리 도입(= ADR-022 개정) 중 하나라도 있으면 이 절과
`docs/policy/privacy-policy.md`, 그리고 Play "데이터 안전" 양식·App Store 개인정보 라벨을 함께 고친다.

---

## 16. 페르소나·가격 (구 docs/16) — 제안, 미결정

**이 절은 규칙이 아니라 제안이다.** 가격·과금은 아직 결정되지 않았고(§열린 결정), 결정되면 §0의 규칙 표로 올라간다.

### 이 제품이 서는 자리

**"봇 없이 캡처 → 내 Drive에 원본 저장 → 웹훅/내 자동화"를 하는 제품이 상용·오픈소스 어디에도 없다.**
Granola·ChatGPT Record·Notion은 원본 오디오를 **지우고**, 로컬 보관을 하는 오픈소스들도 웹훅·자동화를 월 $10~15 유료
티어에 가둔다. 그래서 페르소나의 공통 분모는 "AI 요약이 필요한 사람"이 아니라 **"원본과 자동화를 자기가 쥐고 싶은
사람"**이다.

| # | 누구 | JTBD | Recly가 파는 것 | 돈을 낼 이유 |
|---|---|---|---|---|
| 1 | n8n을 돌리는 개인 파워유저 / 개발자. 회의보다 혼잣말 메모·통화·강의를 더 많이 녹음한다 | "녹음이 끝나는 순간, 내가 이미 만들어 둔 파이프라인의 입력이 되게 해 달라" | 서명된 웹훅 한 방과 Drive 폴더 하나. "폴더에 새 항목" 트리거 하나로 세그먼트·트랙·메타가 함께 도착한다. 앱이 파이프라인을 대신 만들어 주겠다고 나서지 않는 것이 셀링 포인트 | **거의 없다.** 이 페르소나는 매출원이 아니라 **신뢰의 근거**(정직한 레코더라는 평판, 버그 리포트, 워크플로우 예제) |
| 2 | 회의가 많은 한국 지식 노동자. 한국어 회의록이 필요하고 회의 오디오를 외부 SaaS에 상주시키는 것이 부담이다 | "한국어 회의를 제대로 받아쓰되, 오디오와 결과가 **내 Drive**에 남게 해 달라" | 어디서 녹음해도 같은 워크플로우, `transcribe(clova\|rtzr)`로 녹취록이 원본 옆에 파일로, 회의록은 이미 쓰는 에이전트 + `skills/recly-notes/`로. 봇이 회의에 안 들어온다 | **있다.** 단 키 발급·과금 설정이 진입장벽이다(네이버 클라우드 콘솔에서 앱을 만들고 `invokeUrl`을 복사해 오는 일) |
| 3 | macOS·Windows에서 하루 대부분을 Zoom·Teams·Meet 통화로 보내는 컨설턴트·리크루터·리서처 | "봇을 들여보내지 않고, 내 목소리와 상대 목소리를 나눠서, 원본을 지우지 않고 내 저장소에 쌓아 달라" | mic/sys/mix 3트랙, 감지 → 확인 → 녹음, 원본이 `recly/2026/2026-08/`에 그대로 | **있다.** 다만 경쟁이 가장 치열한 자리이고 이 사람들은 "요약 품질"로 제품을 비교한다. Recly의 차별점은 품질이 아니라 **소유권**이다 |

### 경쟁 가격

| 제품 | 가격 | 모델 |
|---|---|---|
| Plaud (전용 하드웨어 + 앱) | 기기 $159~189 + 연 $99.99 | 하드웨어 + 구독. 원본은 자기 클라우드에 가둠 |
| Otter / Fireflies | 사용자당 월 $10~25 | 봇/클라우드 SaaS 구독 |
| 클로바노트 | 개인 무료 월 300분 | 무료 티어로 개인 시장 장악 |
| Meetily | 무료 / BYO 키, Pro 월 $15 | 오픈소스 + 유료 편의 티어 |
| **Recly (제안)** | **앱 무료 · 키는 사용자 것** | 서버 없음 |

읽는 법: 이 표에서 Recly가 이길 수 없는 칸은 "요약 품질"과 "설정의 편함"이고, 이길 수 있는 칸은 "원본 소유"와 "월
고정비 0"이다. 클로바노트의 무료 300분이 있는 한 **한국 개인 사용자에게 월 구독을 받는 안은 성립하지 않는다.**

### 제안

**A. 앱은 무료, 키는 사용자 것**(권고) — 전사는 사용자가 자기 키로, 요약은 자기 에이전트 구독으로 돌리므로
사용량 비용이 개발자에게 오지 않는다. 서버가 없으므로 운영비가 사실상 0이고, 유료화는 그 구조적 장점을 스스로 깎는 방향이 되기 쉽다.

**B. 나중에, 선택적 유료 편의 티어**(열어 두되 지금은 없음) — 후보는 관리형 STT 크레딧이다. **다만 이것은 제품
원칙을 건드린다**: 관리형 크레딧은 사용자의 오디오를 개발자 계정으로 보낸다는 뜻이고, 그러려면 **서버가 하나
생긴다.** 그 순간 개인정보처리방침의 "Recly에는 서버가 없습니다"가 거짓이 되고 ADR-021·ADR-022를 함께 고쳐야 한다.
서버를 만들지 않는 대안(부담이 낮은 순): ① 키 발급 가이드 강화(앱 안에서 콘솔 단계를 스크린샷과 함께 — 비용 0,
원칙 훼손 0), ② 일회성 인앱 결제로 특정 기능(영수증 검증은 스토어가), ③ 기부·후원.

**C. 받지 않기로 한 것** — 사용자 수·녹음 시간 기반 구독, 광고, 데이터 활용(ADR-022가 이미 막고 있다).

### 이 결정이 덮어야 하는 비용

| 항목 | 금액 | 주기 |
|---|---|---|
| Apple Developer Program | $99 | 매년 |
| Google Play 개발자 등록 | $25 | 1회 |
| Windows 코드 서명 | 미확인(연 단위 과금) | 매년 — SmartScreen 경고를 없애려면 필요 |
| GitHub Actions(비공개 저장소, `windows-latest` 러너) | 사용량 과금 | 매월 |
| 도메인·개인정보처리방침 호스팅 | 소액 | 매년 — OAuth Production 게시에 공개 URL이 필요 |

합계는 첫 해 $124, 이후 매년 $99 + 서명 + CI이므로 **"유료화하지 않으면 유지가 불가능한" 구조가 아니다.** 이것이
제안 A의 근거다.

---

## 20. 검증 상태 (구 docs/20)

### 계측

모든 클라이언트가 같은 로그 이벤트 이름을 쓴다: `rec.start`, `rec.part`, `rec.stop`, `rec.finalize`, `xfer.part`,
`xfer.ack`, `job.step.start/ok/fail`, `sync.pull/push/merge`, `secrets.*`, `detect.*`. **이 이름들은 안정
계약이다**(§21). 실기에서 무슨 일이 있었는지를 확인하는 경로는 설정의 **"로그 내보내기"**(파일 링 버퍼) 하나뿐이고,
그 밖으로는 아무것도 나가지 않는다(ADR-022).

### 웹훅 로컬 수신기

Drive·웹훅이 걸린 인수는 모두 이 수신기로 판정한다. §4의 Standard Webhooks 서명을 코어 `Signer`와 같은 식으로
재계산해 검증하고, 본문을 `spec/webhook.payload.schema.json`으로 확인한다.

```
cd spec && npm ci          # 처음 한 번 (ajv를 이 수신기가 그대로 쓴다)
node scripts/webhook-receiver.mjs --port 8787 --secret whsec_… [--log ./hooklog] [--fail-first 1]
```

워크플로우의 `webhook` 단계 `url`은 `http://127.0.0.1:8787/hook`, `secretRef`는 그 값을 담은 시크릿 이름이다. 앱과
수신기가 **같은 `whsec_` 문자열**이면 된다(앱의 "웹훅 시크릿 생성" 값을 `--secret`에 주거나, 반대로 정한 값을 앱의
시크릿 폼에 넣는다). Android 실기기·에뮬레이터는 `adb reverse tcp:8787 tcp:8787`, iOS 시뮬레이터와 데스크톱 앱은
맥의 `127.0.0.1`을 그대로 쓴다.

성공하면 전달 1건당 한 줄을 찍는다.

```
ok id=01J9STEPR0N0123456789ABCDE recordingId=01J9ABCDEF0123456789ABCDEF event=recording.completed drive.fileId=1AbC… attempt=1
```

`drive.fileId`가 `-`면 앞에 성공한 `drive.upload`가 없다는 뜻이다(§4). 서명 불일치·타임스탬프 초과는 401, 스키마
위반은 400을 찍는다. `--fail-first 1`은 같은 `webhook-id`의 첫 전달에 500을 답해 재시도 경로를 만든다.
`node --test scripts/webhook-receiver.test.mjs`가 수신기 자체를 검증한다.

### 인수 시나리오

| 대상 | 시나리오 |
|---|---|
| **코어** | `./gradlew :core:jvmTest` 통과; 예제 JSON 왕복(파싱→직렬화) 구조 동일 |
| **Android 폰** | 1 새 설치 → 기본 워크플로우 2개가 로컬에 생기고 이 폰에서 사용 중인 것은 메모. 2 1시간 녹음(화면 끔) → 정지 → 제목 입력 → 30초 안에 Drive `recly/2026/2026-08/{base}/`에 4파트 + meta, 웹훅 1회 수신(서명 검증 통과). 3 비행기 모드에서 녹음 → 해제 → 자동 업로드. 4 업로드 중 앱 강제 종료 → WorkManager가 이어서 완료(Drive에 중복 파일 없음). 5 웹훅 500 → 재시도 → 성공. 6 시크릿 없는 기기에서 `MISSING_SECRET`, `continue`면 다음 단계 진행. 7 30초 미만 → `SKIPPED_SHORT`, 행에 다시 시도·올리기 버튼이 없다. 8 동의 화면 중 회전(액티비티 재생성) → 인가 완료 |
| **Galaxy Watch** | 1 타일 탭 → 즉시 녹음, 워치페이스 칩 표시, 앱 닫아도 지속. 2 폰 없이 20분 녹음 2건 → 폰 연결 → 두 건 모두 전송·ack·워치에서 삭제·폰에서 Drive 업로드. 3 전송 중 BT 끊김 → 재연결 시 미ack 파트부터 재개, 폰에 중복 없음. 4 폰에서 워크플로우 이름 변경 → 워치 선택지에 반영 |
| **macOS** | 1 **"Zoom 입장 시 알림 1회"** → 클릭 → 3트랙 녹음 → 정지 → Drive에 mic/sys/mix + meta, 웹훅. 2 1시간 회의 후 클랩 오프셋 < 20 ms. 3 폰에서 만든 워크플로우가 mac 편집 창에 보이고 양방향 편집 충돌 없이 병합. 4 새 Mac에서 DMG 설치 → Gatekeeper 통과 → 권한 프롬프트 2종(마이크 → 시스템 오디오) |
| **iPhone + Apple Watch** | 1 액션 버튼 → 녹음 → 잠금 3시간 → 정지 → 홈으로 나감 → 잠금 상태에서 업로드 완료(배경 URLSession). 2 워치 20분 녹음 × 2 → iPhone 자동 수신·ack·실행. 3 Double Tap으로 정지 |
| **Windows** | **1 "Teams 입장 → 알림"** → 녹음 → Drive + 웹훅. 2 MSI 설치 시 SmartScreen 경고 없음 |
| **전사** | 1 폰에서 "회의록" 워크플로우로 3분 이상(2파트 이상) 녹음 → 인원 선택 → Drive 폴더에 파트·meta·`*.transcript.json/.txt`가 생기고 상세 화면에 화자별 녹취가 보임. transcript의 `start/end`가 두 번째 파트 구간을 900초 이후로 가리킴(remux + 오프셋 검증). 2 제출 직후 프로세스 종료 → 다음 `runDueJobs`가 새로 제출하지 않고 같은 `jobRef`로 폴링해 완료(`attempts` 안 오름). 3 키 없음 → `MISSING_SECRET`(즉시), 틀린 키 → `AUTH_REJECTED` + "키를 확인하세요". 4 데스크톱 3트랙 → `mix`가 입력으로 선택되고 웹훅 `files[]`에 `transcript` 항목 |

### 지금까지 무엇이 실제로 확인됐나

**자동 검증(기기 없이)** — 아래 스위트가 전부 녹색인 것이 모든 변경의 기본선이다.

| 스위트 | 케이스 수 |
|---|---|
| `:core:jvmTest` | 391 |
| `:android:app:testDebugUnitTest` | 262 |
| `:android:wear:testDebugUnitTest` | 53 |
| `:android:recording:testDebugUnitTest` | 49 |
| `:android:datalayer:testDebugUnitTest` | 23 |
| `:windows:app:test` | 260 |
| `cargo test`(capture-helper) | 32 |
| RecKitTests | 354 |
| ReclyTests(iOS) | 28 |
| ReclyUITests | 7 |

**실기 인수 통과**

| 항목 | 무엇을 확인했나 |
|---|---|
| 데스크톱(JVM 앱, macOS 호스트) | 실제 Google 계정 PKCE 로그인 → 3트랙 녹음(페이크 헬퍼) → Drive `recly/{yyyy}/{yyyy}-{MM}/{base}/`에 3파트 + meta → 로컬 수신기가 서명·스키마 검증 통과한 웹훅 1건 |
| Android 폰(에뮬레이터, 실제 계정) | Credential Manager 사다리 로그인 + Drive 권한 허용 → `NEEDS_AUTH`로 파킹돼 있던 잡 3건이 자동 재개돼 업로드 완료 |
| 스키마 v2 마이그레이션 | `user_version = 1`인 실기 DB에 새 빌드를 덮어써도 크래시 없이 1 → 2로 올라감 |
| Android `concat` 런타임 | 계측 테스트가 AAC 파트 2개를 만들어 이어 붙인 뒤 디코드 — 길이 오차 0, 전 프레임 디코드 통과(§8 무손실 복사·pts 이어 붙임) |
| 교차 셸 문구 통일(§7 규칙 10·11, §9 화면 원칙 1) | 네 셸의 사전을 하나로 맞춘 뒤 실제로 확인: Android 에뮬레이터와 iPhone 시뮬레이터의 녹음 화면이 같은 노드 값(`phone` · 사용 중인 워크플로우 이름만 · `IDLE`)과 같은 헤더 meta(`phone · <id8>`)를 보이고, 폰의 피커가 워크플로우 이름만(`회의` 선택됨·`메모`)을, 정지 뒤 제목 프롬프트가 `Recording title` + `Leave it empty to keep the timestamp name` + `People in the room`(모름·2·3·4·5·6+)을 보인다. RecMac 카탈로그에 없던 `People in the room`·`Unknown`·`6+`(한국어에서 영어로 새던 자리)까지 포함해 `CrossShellDictionaryTest`가 en·ko를 잠근다 |
| 워크플로우 내보내기/가져오기 | 설정에서 내보낸 `recly-workflows.json`을 다른 기기에서 가져오기 → 목록이 교체되고, 이 기기의 기본·시크릿 값은 그대로(파일에 없음). 구 스키마 파일 가져오기 → 마이그레이션되어 저장 |

**보류 — 전부 하드웨어·계정 대기이지 코드 문제가 아니다**

| 항목 | 왜 |
|---|---|
| 갤럭시 워치 3시간 백그라운드 녹음, `sendFile` 30 MB 실측 | 실기기 갤럭시 워치가 없다. 에뮬레이터와 페이크로만 확인 |
| Apple Watch 배경 녹음 2~3시간, `transferFile` 50 MB·ack 신뢰성 | 실기기 Apple Watch가 없다 |
| macOS tap 권한 프롬프트 실물, Zoom/Meet/Teams 3앱 캡처, 클랩 오프셋 | 사람의 클릭이 필요하다(자동 조작이 막힘). 설계·경로와 드리프트 *추정기*는 합성 하네스로 검증됨 |
| Windows 실캡처(loopback, `IAudioSessionManager2` 감지), MSI·SmartScreen, Credential Manager 비우기, `/revoke` 실호출, 시스템 고대비 감지 | **Windows PC가 없다.** 컴파일·단위 테스트·MSI 생성은 CI(`windows-latest`)가, UI는 macOS 호스트의 패키지 실행이 대신한다 |
| `NEEDS_SPACE` 기기 재현 | 실제로 꽉 찬 Google Drive를 만들지 못했다. 코어는 페이크 Transport로(`DriveQuotaTest`), 셸은 단위 테스트와 개발 플래그로 배너·배지만 확인 |
| 연결 해제의 실제 실행 | 누르면 그 계정의 grant가 **모든 기기에서** 사라져(revoke는 Cloud 프로젝트 단위) 남은 실기 로그인 확인이 전부 막힌다. 다이얼로그·경고 문구·"녹음 중에는 확인 비활성"까지는 UI로 봤고, revoke → `core.disconnect` 경로는 코어·셸 단위 테스트로만 |
| M7 실키 인수(전사 시나리오 1·2·4) | STT 실제 키가 필요하다. 가짜 키로 `AUTH_REJECTED` → "키를 확인하세요" → 편집기 진입까지는 확인 |
| iPhone·macOS 실기 로그인, TestFlight, 스토어 등록·서명 | Apple Developer / Play 등록이 필요하다 |

---

## 21. 컨벤션 (구 docs/21)

감사·클린업이 **보고 나서 남기기로 판정한** 것들이다. 다음 감사가 같은 것을 다시 꺼내 논쟁하지 않도록 근거만 짧게
적는다. 판정을 뒤집으려면 여기에 이유를 덧붙이거나 지운다.

### 1. 로그 이벤트 이름은 안정 계약이다 — 개명 금지

`rec.start` · `rec.part` · `rec.stop` · `rec.finalize` · `xfer.*` · `job.step.start/ok/fail` · `sync.*` 같은 이벤트
이름은 네 셸이 **같은 이름**을 쓰기로 한 계약이고(§20), 실측 기록과 인수 시나리오가 이 문자열에 걸려 있다. 코드
안에서만 보인다고 해서 제품명이나 현재 클래스 이름에 맞춰 다듬지 않는다 — 사용자에게 보이지 않으므로 그대로 두는
쪽이 계약이다. `CoreMessage` 코드도 같다: 저장된 `last_error`와 셸의 렌더가 코드 문자열을 그대로 쓴다.

### 2. `WorkflowSync.reconcile`은 밀도가 높지만 분해 대상이 아니다

로컬·원격·삭제 워터마크·`dirty`를 **한 화면에서 같이 봐야** 규칙이 읽힌다. 헬퍼로 쪼개면 각 조각은 짧아져도 동기화
규칙 전체를 따라가려면 파일을 오가야 하고, 이 함수는 규칙 그 자체가 본문이다.

### 3. `ShellModel`·`MenuModel`의 잔여 길이는 그대로 둔다

Windows `ui/ShellModel.kt`와 Mac `MenuModel.swift`는 길지만 **뽑을 로직은 이미 다 나갔다** —
`DisconnectGuard`/`Phase`/`Gate`, `StatusLine`, `runTracked`/`persistFlushed`, `DisconnectFlow`,
`WorkflowInspector`, `RecordingDialogs`, `RecorderStatusLine`. 남은 것은 상태 필드와 공용 구현으로의 얇은 위임이라,
줄 수를 더 줄이는 분해는 계약을 옮기지 않고 churn만 만든다.

### 4. 테스트 더블은 공용 테스트 지원 위치에 1벌만 둔다

같은 페이크를 테스트 파일 안에 두 번째로 적는 순간 드리프트가 시작된다(실패 변형만 다른 사본, 서로 다른 기기
식별자). 두 번째 사용처가 생기면 그 모듈의 공용 테스트 지원 위치로 올리고, 특수한 동작은 사본이 아니라 **공용 더블을
감싸는 델리게이트**로 만든다. 현재 자리: 코어 `core/src/jvmTest/.../testing/`(`TestSupport`, `FakeDrive`),
Windows `windows/app/src/test/.../Fakes.kt`, Android는 모듈별 공용 테스트 파일.

### 5. step→라벨 맵이 셋인 것은 의도한 것이다

Android `WorkflowsViewModel`에 `Step`→라벨과 `StepEdit`→라벨이 따로 있고, `WorkflowEditorScreen`에 `StepKind`→라벨이
하나 더 있다. **문구가 다르기 때문**이다 — 목록은 짧은 이름(`Drive`), 편집기와 단계 추가 다이얼로그는 동작을 말하는
이름(`Drive 업로드`). 하나로 합치면 세 화면 중 두 곳의 문구가 무너진다. 합칠 것은 맵이 아니라, 같은 문구를 두 번
적는 경우뿐이다.

### 6. 코드베이스의 언어는 영어다

식별자·주석·문서 주석·커밋 메시지·로그 이벤트 이름과 메시지·테스트 이름·모듈 README는 영어로 쓴다. **사용자에게
보이는 문자열은 리소스로 지역화**하고(§7) 코드에 하드코딩하지 않는다. 이 문서를 비롯한 `docs/`의 설계 문서는
한국어다.

---

## 열린 결정

코드가 아니라 사람이 정해야 남는 것들이다.

| 항목 | 상태 |
|---|---|
| **상표** | 미국 USPTO에 동일 문자 **RECLY**가 제9류(모바일 앱)로 등록·유효(Reg. 7739986, 2025-03-25)하고 한국에는 동일 표장이 없다. 미국 출시·출원은 위험하다. 선택지: 한국 우선 출시(미국 제외) / 미국 변리사 의견 / 개명(표시 이름만). 공개 범위가 한국이면 유지, 글로벌이면 개명 권고 |
| **라이선스·공개 여부** | **결정(2026-09-04)**: `AGPL-3.0-or-later`(`LICENSE` 원문 그대로) + §7 추가 허가 두 개(앱스토어 배포, 7(e) 상표 불허 — `LICENSE-EXCEPTIONS.md`) + `TRADEMARK.md`. **CLA 없음** — 기여는 같은 조건(추가 허가 포함)으로 받는다(`CONTRIBUTING.md`). 이름은 글로벌 공개에도 **Recly 유지**(미국 RECLY 상표 리스크는 감수). 공개 전환 시점과 스토어 출시는 "곧 예정" |
| **가격·과금** | §16의 제안(앱 무료 + 사용자 키, 유료 편의 티어는 후속)은 **결정 대기**. 결정되면 §0의 규칙으로 올린다. 함께 정할 것: 유료 편의 티어를 가능성으로 열어 둘 것인가, 아니면 "서버는 영원히 없다"를 제품 약속으로 못 박을 것인가 / Windows 코드 서명 비용을 확인하고 감당할 것인가 |
| **관할별 동의 문구의 법률 검토** | §12의 관할 표는 웹 요약이고 법률 자문이 아니다. 스토어 제출 전에 최소한 한국·미국(주별 목록의 현행성)·EU 항목을 확인하거나, 안내문을 "관할을 직접 확인하라"는 수준으로만 유지한다 |
| **개인정보처리방침 게시** | 구현 전제 조건은 전부 충족됐다. 남은 것은 ① §15 §3 표의 provider별 보관 정책 URL 확정(확정 전까지 앱 고지에도 링크를 걸지 않는다), ② 공개 연락처 이메일, ③ 위 법률 검토. 셋 다 사람의 일이다 |
| **워치 슬라이스 크기 기준** | 원래 기준은 "watchOS 슬라이스 < 20 MB"였는데 SKIE 적용 후 스트립 전 정적 슬라이스가 20.1~21.8 MB로 그 선을 넘는다. 링크·스트립 후 실제 앱은 13 MB라 75 MB 예산에는 여유가 있다. 기준을 "링크된 워치 앱 크기"로 다시 쓸지, 워치 소스셋을 줄여 슬라이스를 되돌릴지 결정이 필요하다 |
| **macOS 에코(AEC)** | 내장 스피커를 쓰면 상대 목소리가 mic 트랙에 섞인다. 지금은 시작 시 경고 한 줄뿐이고, `setVoiceProcessingEnabled` 실험 결과에 따라 옵션화할지 결정한다 |





