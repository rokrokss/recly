# Recly 개인정보처리방침 / Privacy Policy

> **상태: 초안 — 시행 전.** 아직 어디에도 게시되지 않았고, 아래 시행일은 게시하는 날로 다시 적는다.
>
> **구현 전제 조건은 충족됐다.** §7의 녹음 삭제(작업 기록 포함)·앱 안의 연결 해제와 §8의 네 셸 동의 리마인더가 실제로 앱에 있다. 게시 전에 남은 것은 **코드가 아니라 사람의 일** 셋이다 — ① `docs/recly.md` §15 §3 표의 provider별 보관 정책 URL 확정(확정 전까지 앱의 고지에도 링크를 걸지 않는다), ② 아래 `<TODO>` 두 곳의 공개 연락처 이메일, ③ 법률 검토(§8의 관할 요약은 웹 요약이고 법률 자문이 아니다 — `docs/recly.md` §열린 결정).
>
> **Status: DRAFT — not in force.** It is not live anywhere yet and the effective date below is rewritten on the day it is published.
>
> **The implementation prerequisites are met.** §7's recording deletion (job records included) and in-app disconnect, and §8's consent reminder on all four shells, are really in the app. What is left before publication is not code but three human items — (1) confirming the per-provider retention policy URLs in `docs/recly.md` §15 §3 (until then the in-app disclosure carries no link either), (2) the public contact address in the two `<TODO>` places below, and (3) a legal review (§8's jurisdiction summary is a web summary, not legal advice — `docs/recly.md` §열린 결정).

**시행일 / Effective date: 2026-08-29**
**문의 / Contact: `<TODO: 공개 연락처 이메일>`** (스토어 제출·OAuth Production 게시 전에 실제 주소로 바꿀 것)

이 문서는 Google OAuth 동의 화면과 앱 스토어가 요구하는 공개 URL로 게시된다. 기술적 근거는 `docs/recly.md` §15(프라이버시·데이터 흐름).

---

# 한국어

## 1. 요약

Recly는 **녹음 앱**입니다. 녹음한 파일은 **사용자 자신의 Google Drive**로 올라가고, 그 다음 무엇을 할지는 사용자가 만든 워크플로우가 정합니다.

**Recly에는 서버가 없습니다.** 개발자가 운영하는 서버, 데이터베이스, 계정 시스템이 존재하지 않습니다. 따라서 개발자는 사용자의 녹음, 녹취, 워크플로우, Google 계정 정보 중 **어느 것도 수집하거나 보관하거나 볼 수 없습니다.**

## 2. 앱이 다루는 정보와 그것이 있는 곳

| 정보 | 어디에 저장되나 |
|---|---|
| 녹음 오디오 파일, 메타데이터(제목·시각·길이·기기 이름) | 사용자의 기기, 그리고 사용자가 업로드 단계를 넣었다면 사용자의 Google Drive |
| 워크플로우 정의 | 기기 안에만 (Drive로 보내지 않음) |
| Google 액세스·리프레시 토큰 | 사용자 기기의 보안 저장소(Android Keystore 기반 암호화 저장소 / Apple 키체인 / Windows 자격 증명 관리자) |
| 웹훅 서명 키, 사용자가 입력한 STT API 키 | 같은 보안 저장소. **기기 간 동기화되지 않고, Recly로는 전송되지 않습니다**(받을 서버가 없습니다). 사용자가 전사 단계를 직접 넣었을 때에 한해, 그 API 키는 **사용자가 고른 업체로 곧바로** 인증 용도로만 전송됩니다(§3-(3)). 웹훅 서명 키는 전송되지 않고 서명 계산에만 쓰입니다 |
| 로그인한 Google 계정의 이메일 주소 | **기기에만.** Android 폰은 보안 저장소에 넣어 두고 다시 켰을 때 같은 계정을 고르는 데 씁니다. iPhone·Mac은 Google 로그인 SDK가 들고 있는 값을 다음 로그인의 입력칸을 채울 힌트로 기기에 남깁니다. Windows는 저장하지 않습니다. 로그아웃하면 지워집니다 |
| 실행 상태(작업 큐·재시도·업로드 진행) | 사용자 기기의 로컬 데이터베이스 |
| 진단 로그 | 사용자 기기의 시스템 로그. 사용자가 직접 내보낼 때만 기기를 떠납니다 |

## 3. 데이터가 기기 밖으로 나가는 경우 (전부)

**(1) 사용자의 Google Drive**
녹음 폴더에 오디오 파트 파일과 `meta.json`을 씁니다. 사용하는 권한은 `drive.file`(앱이 만든 파일) 하나뿐이며, **사용자의 다른 Drive 파일은 볼 수 없습니다.** 이 파일들은 사용자 소유이고, 사용자가 공유하지 않는 한 사용자만 볼 수 있습니다.

**(2) 사용자가 지정한 웹훅 주소**
사용자가 워크플로우에 `webhook` 단계를 넣고 URL을 적었을 때만, 그 주소로 알림 한 건을 보냅니다. 본문에는 녹음 메타데이터와 Drive 파일 링크가 들어가고 **오디오 자체나 녹취 본문은 들어가지 않습니다.**

- **서명은 사용자가 시크릿을 설정했을 때만 붙습니다.** 서명 시크릿을 지정하면 요청에 HMAC-SHA256(Standard Webhooks) 서명 헤더가 붙고, 지정하지 않으면 **서명 없이** 나갑니다.

그 주소를 운영하는 것은 사용자이며, 그 이후의 처리는 사용자 책임입니다.

**(3) 사용자가 선택한 전사 서비스 — 그 단계를 직접 넣었을 때만**
전사(STT)는 **고정된 기능이 아니라 사용자가 워크플로우에 넣을 수 있는 선택 단계**입니다. 사용자가 그 단계를 추가하고 자기 API 키를 입력했을 때에 한해, 기기가 **사용자가 고른 업체에 사용자의 키로 직접** 요청을 보냅니다.

- 전사 단계: **오디오 파일 전체**가 사용자가 고른 STT 업체로 전송됩니다.
- 중간 서버는 없습니다. 요청은 기기에서 업체로 곧바로 갑니다.
- 그 업체가 데이터를 얼마나 보관하고 어떻게 쓰는지는 **해당 업체의 방침**을 따르며 Recly가 통제하지 않습니다. 단계를 추가하기 전에 업체의 개인정보처리방침을 확인하십시오.
- 이 단계를 넣지 않으면 오디오와 텍스트는 이 업체로 전혀 전송되지 않습니다.

고를 수 있는 업체는 아래 열넷입니다. **어느 것을 고르든 전송되는 내용은 같습니다** — 오디오 트랙 한 파일과 그 요청에 실리는 언어·화자분리 옵션(화자 수 힌트)입니다. 그 뒤의 보관·학습은 업체마다 다르므로, 고르기 전에 그 업체의 방침을 직접 읽으십시오.

| 워크플로우의 `provider` | 업체 | 방침 |
|---|---|---|
| `assemblyai` | AssemblyAI | <https://www.assemblyai.com/> |
| `clova` | 네이버 클라우드 CLOVA Speech | <https://www.ncloud.com/> |
| `rtzr` | 리턴제로 | <https://www.rtzr.ai/> |
| `openai` | OpenAI | <https://openai.com/> |
| `groq` | Groq | <https://groq.com/> |
| `together` | Together AI | <https://www.together.ai/> |
| `mistral` | Mistral AI | <https://mistral.ai/> |
| `elevenlabs` | ElevenLabs | <https://elevenlabs.io/> |
| `deepgram` | Deepgram | <https://deepgram.com/> |
| `azure` | Microsoft Azure AI Speech | <https://azure.microsoft.com/> |
| `daglo` | 다글로 | <https://daglo.ai/> |
| `speechmatics` | Speechmatics | <https://www.speechmatics.com/> |
| `rev` | Rev AI | <https://www.rev.ai/> |
| `gladia` | Gladia | <https://www.gladia.io/> |

**(4) 사용자가 짝 지은 사용자 자신의 기기 — 워치와 폰 사이**
갤럭시 워치나 Apple Watch로 녹음하면, 그 **오디오 파일과 메타데이터(제목·시각·길이·체크섬)** 는 짝 지은 폰으로 넘어갑니다. 워치에는 업로드도 워크플로우 실행도 없기 때문이며, **워크플로우에 Drive 업로드 단계가 없어도 이 전송은 일어납니다.** 반대 방향으로는 폰이 워치의 목록에 띄울 **워크플로우 요약**(워크플로우의 id와 이름 — 단계 내용은 보내지 않습니다)을 보냅니다.

- 전송에 쓰는 것은 운영체제의 기기 페어링 통로입니다(Wear OS의 Data Layer, Apple의 WatchConnectivity). Recly의 서버는 관여하지 않고, 애초에 존재하지 않습니다.
- **두 기기 모두 사용자 자신의 기기입니다.** Recly는 이 전송을 중계하는 서버를 두지 않습니다. 다만 통로 자체는 운영체제의 것이라, 녹음 파일은 두 기기가 가까이 있을 때만 보내지만(Wear OS·Apple 모두 근거리 전송) 워크플로우 이름 같은 작은 항목은 두 기기가 떨어져 있으면 Google Play 서비스가 자기 인프라를 거쳐 전달할 수 있습니다 — 그 처리는 Google의 개인정보처리방침을 따릅니다.
- 폰이 수신을 확인하면 워치는 자기 사본을 지웁니다 — 워치에 녹음 이력이 쌓이지 않습니다.
- API 키·토큰은 이 경로로 전송되지 않습니다.

**그 외에는 없습니다.** 위 네 가지 외에 데이터가 기기를 떠나는 경로는 존재하지 않습니다.

## 4. 수집하지 않는 것

- 분석·사용 통계·행동 로그를 수집하지 않습니다.
- 크래시 리포트를 자동으로 전송하지 않습니다.
- 광고 식별자를 쓰지 않고 광고를 넣지 않습니다.
- Recly 계정이 없습니다. 회원가입이 없고, 개발자에게 전달되는 이메일·프로필 수집도 없습니다. 앱이 Google에 요청하는 권한은 `drive.file` 하나뿐이고 프로필·연락처 권한은 따로 요청하지 않습니다. 다만 **Google 로그인은 그 자체로 어느 계정인지를 앱에 알려 주므로, 그 계정의 이메일 주소가 기기에 남습니다** — 다음에 같은 계정을 고르기 위한 용도이고, §2 표에 적은 대로 Recly를 비롯한 어디로도 전송되지 않습니다.
- **개발자(Recly)는 사용자의 데이터를 수집하지 않고, 제3자에게 판매·제공·공유하지 않습니다.** 애초에 개발자가 가진 데이터가 없습니다. 다만 **사용자의 지시에 따라 데이터가 다른 곳으로 가는 일은 있습니다** — §3에 적은 대로 사용자 자신의 Google Drive, 사용자가 직접 적어 넣은 웹훅 수신기, 사용자가 직접 고른 전사 업체, 그리고 사용자가 짝 지은 사용자 자신의 기기(워치 ↔ 폰)입니다. 이것은 사용자가 그렇게 하라고 지시했기 때문에 일어나는 전송이지, 개발자가 제3자에게 데이터를 넘기는 것이 아닙니다.

## 5. Google 사용자 데이터의 사용 제한

Recly가 Google API로 받은 정보의 사용과 다른 앱으로의 이전은 **Google API 서비스 사용자 데이터 정책**(제한적 사용 요건 포함)을 따릅니다. Recly는 Drive 데이터를 사용자가 요청한 기능(녹음 파일 업로드, 워크플로우 정의 동기화)에만 사용하고, 광고에 사용하지 않으며, 사람이 읽지 않습니다(읽을 수 있는 서버가 없습니다).

## 6. 보안

- API 키·토큰은 각 운영체제의 보안 저장소(Android Keystore로 보호되는 암호화 저장소, Apple 키체인, Windows 자격 증명 관리자)에 저장됩니다.
- 모든 외부 통신은 HTTPS입니다(예외: 사용자가 직접 지정한 `127.0.0.1`·`localhost` 로컬 수신기).
- **Android·iPhone·Apple Watch**에서는 녹음 파일이 운영체제가 앱마다 격리하는 전용 영역(앱 컨테이너)에 저장되어 다른 앱이 접근할 수 없습니다.
- **macOS와 Windows에는 그런 격리가 없습니다.** Recly의 Mac 앱은 직접 배포이므로 샌드박스 안에서 돌지 않고, 파일은 `~/Library/Application Support/app.recly.mac/`에, Windows는 `%LOCALAPPDATA%\Recly\`에 있습니다 — **같은 사용자 계정으로 실행되는 다른 프로그램은 이 파일을 읽을 수 있습니다.** 여기에서의 보호는 운영체제의 사용자 계정 권한(파일 접근 제어)과, 사용자가 켜 두었다면 디스크 암호화(macOS FileVault, Windows BitLocker)입니다. API 키와 토큰은 이 파일들과 함께 있지 않고 키체인·자격 증명 관리자에 따로 있습니다.
- 다만 기기 자체의 잠금·암호화가 없으면 어떤 보안 저장소도 완전하지 않습니다. 기기 잠금과 디스크 암호화를 사용하십시오.

## 7. 보관과 삭제

- **자동 삭제**: 업로드가 성공적으로 확인된 뒤에만 기기의 오디오 원본이 삭제됩니다. 업로드가 실패했거나 업로드 단계가 없으면 원본은 기기에 남습니다. **Google Drive가 가득 차서 업로드가 멈춘 경우에도 원본은 기기에 남습니다** — 앱이 "공간이 없습니다"를 알리고, 공간을 만든 뒤 "다시 시도"를 누르면 이어서 올립니다. 기간 기반 자동 삭제는 없습니다.
- **녹음 삭제**: 네 앱(Android 폰·iPhone·Mac·Windows) 모두 목록에서 녹음을 지울 수 있습니다. 누를 때마다 확인 창이 **Drive를 어떻게 할지 묻고, 기본값은 "Drive에 남기기"** 입니다 — 되돌릴 수 없는 쪽을 기본값으로 두지 않습니다. 아직 Drive에 올라가지 않은 파트가 있으면 그 개수를 먼저 알립니다.
- **녹음을 지우면 함께 지워지는 것**: 이 기기의 녹음 폴더 전체(오디오 파일, `meta.json`, 녹취 로컬 사본)와 **그 녹음의 모든 기록** — 녹음·파트 기록뿐 아니라 **작업 기록**(`job`·`step_run`: 그 녹음에 걸었던 워크플로우 정의 사본, 단계별 실행 상태, 실패 메시지, 업로드·전사 진행 정보, 단계 출력)까지 한 번에 지워집니다. 이전 빌드에서 목록에 보이지 않은 채 데이터베이스에 남던 기록이 이것이고, 이제는 남지 않습니다. "Drive 폴더도 삭제"를 고르면 그 녹음의 Drive 폴더도 지웁니다.
- **실행 중인 녹음은 지워지지 않습니다**: 그 녹음의 작업이 실행 중이면 삭제를 거절하고 "실행이 끝난 뒤 다시 시도하세요"라고 알립니다. Drive에서 지우지 못한 경우에도 기기의 파일은 지워지고, 그 사실을 알려 드립니다.
- **로그아웃**: 네 앱 모두 "로그아웃"이 있고, 이것은 이 기기의 Google 토큰을 지웁니다(Android는 저장해 둔 계정 이메일도 함께). **녹음 파일, 작업 기록, 입력해 둔 API 키는 지우지 않습니다.**
- **연결 해제**: 네 앱 모두 설정에 **로그아웃과 별개의 "연결 해제"** 가 있습니다. Google 권한을 회수하고, 이 기기의 토큰·입력해 둔 API 키·웹훅 서명 키·작업 기록·동기화 상태를 모두 지웁니다. 원하면 "이 기기의 녹음도 함께 삭제"를 따로 체크할 수 있고, **체크하지 않으면 녹음 파일은 남습니다**(아직 올라가지 않은 원본을 계정에 관한 결정으로 지우지 않습니다). 예외 하나: 연결 해제 순간에 **작업이 실행 중이던 녹음**은 그 작업 기록과 함께 남고, 몇 건이 남았는지 알려 드립니다 — 실행이 끝난 뒤 다시 연결 해제하면 지워집니다. **Drive의 파일은 어떤 경우에도 지우지 않습니다** — 사용자의 파일입니다.
  - 확인 창은 **같은 계정으로 로그인한 다른 기기도 함께 끊긴다**는 것, 앱 데이터 영역의 워크플로우 정의가 사라질 수 있다는 것, 아직 Drive에 올라가지 않은 녹음이 몇 건 남는지, 이 기기의 키와 대기열이 지워진다는 것을 먼저 말하고, Google 계정 설정(<https://myaccount.google.com/permissions>) 링크를 함께 보여줍니다.
  - **권한 회수가 실패하면** 그 사실을 알려 드립니다 — 이 기기의 데이터는 지워졌지만 Google 계정 목록에는 Recly가 남아 있으므로, 위 링크에서 직접 지우셔야 합니다.
  - **전사 업체가 가진 사본**: Recly가 대신 삭제할 수 없습니다. 해당 업체에 직접 요청하십시오.

### 앱을 지웠을 때 남는 것

| 플랫폼 | 앱을 지우면 | 남는 것과 지우는 법 |
|---|---|---|
| Android 폰 · 갤럭시 워치 | **앱 데이터가 함께 지워집니다** — 녹음 파일, 로컬 DB, 토큰·API 키를 담은 암호화 저장소가 모두 앱 전용 영역에 있고 OS가 앱과 함께 지웁니다 | 없습니다. Drive의 파일은 사용자 것이므로 남습니다 |
| iPhone · Apple Watch | 앱 컨테이너(녹음 파일·DB·설정)가 함께 지워집니다 | **키체인 항목(서비스 `app.recly.secrets`, 워치는 `app.recly.watch.*` — 입력해 둔 API 키·웹훅 서명 키, 그리고 Google 로그인 SDK의 토큰)은 Apple이 삭제를 보장하지 않습니다.** 남아 있으면 같은 앱을 다시 설치했을 때 다시 읽힐 수 있습니다. 다른 기기나 iCloud로 동기화되지는 않습니다(항목이 `ThisDeviceOnly`). iOS·watchOS는 사용자가 키체인 항목을 **직접** 지울 방법이 없으므로, **앱을 지우기 전에 앱 안에서 "연결 해제"를 실행하십시오** — 그것이 이 기기의 키체인 항목(API 키·서명 키·토큰)을 지웁니다. 워치에는 로그인도 키 입력도 없어 워치의 항목(`app.recly.watch.*`)에 들어 있는 것은 설치 식별용 UUID뿐이고, 그것을 지울 앱 내 경로는 없습니다. 이미 앱을 지운 뒤라면 남은 항목에 대해서는 기기 초기화 외에 확실한 방법이 없습니다 |
| macOS | **앱 번들(`.app`)만 지워집니다** | ① `~/Library/Application Support/app.recly.mac/`(녹음, `rec.db`, `device.id`)를 직접 지우십시오. ② 키체인 접근에서 서비스 이름이 `app.recly.mac.secrets`인 항목(입력해 둔 API 키·웹훅 서명 키)과 Google 로그인 SDK가 만든 항목(Google 토큰)을 지우십시오. ③ **앱 설정과 마지막 로그인 계정의 이메일 힌트가 `UserDefaults`에 남습니다** — 녹음 모드·동의 리마인더·언어·접근성 설정과, 다음 로그인 칸을 채우는 데 쓰는 이메일 주소(`app.recly.auth.lastAccount`)입니다. 터미널에서 `defaults delete app.recly.mac`으로 함께 지웁니다 |
| Windows | **설치된 파일만 지워집니다** | ① `%LOCALAPPDATA%\Recly\`(녹음, `rec.db`, `device.id`)를 직접 지우십시오. ② 자격 증명 관리자의 Windows 자격 증명에서 `app.recly.windows/tokens/…`·`app.recly.windows/secrets/…` 항목을 지우십시오. ③ **앱 설정이 레지스트리에 남습니다** — 동의 리마인더·언어·테마·접근성 설정이 `HKCU\Software\JavaSoft\Prefs\app\recly\windows`에 있고, 레지스트리 편집기에서 그 키를 지우면 됩니다. Windows 앱은 계정 이메일을 저장하지 않으므로 지울 것이 없습니다 |

### 앱 안에서 지우는 것이 먼저입니다

위 표의 수동 정리는 대부분 **앱을 지우기 전에 앱 안에서 "연결 해제"를 한 번 실행하면** 필요 없어집니다 — 그것이 키체인·자격 증명 관리자의 항목(토큰, 입력해 둔 API 키, 웹훅 서명 키)과 이 기기의 작업 기록을 지웁니다. 녹음 파일까지 지우려면 확인 창의 "이 기기의 녹음도 함께 삭제"를 체크하거나, 목록에서 녹음을 하나씩 지우십시오. 앱 설정(macOS `UserDefaults`, Windows 레지스트리)과 macOS·Windows의 데이터 폴더는 그래도 남으므로, 표의 나머지 항목은 그대로 따르십시오.

## 8. 녹음과 관련한 사용자의 책임

Recly는 녹음 사실을 상대방에게 자동으로 알리지 않으며, 그럴 수단이 없습니다. 대화 녹음에 관한 법은 나라와 지역마다 다릅니다(예: 한국은 본인이 참여한 대화의 녹음이 허용되지만 타인 간 대화의 녹음은 범죄이고, 미국의 일부 주와 EU는 전원 동의 또는 사전 고지를 요구합니다). **적용되는 법을 확인하고 필요한 동의를 받는 것은 사용자의 책임입니다.** 이 안내는 법률 자문이 아닙니다.

그래서 앱은 녹음을 시작하기 전에 **참석자에게 알렸는지 한 번 묻습니다.** 이 안내는 **Mac·Windows·iPhone·Android 폰 네 앱 모두**에 있고, 질문·본문·관할별 규정 링크가 네 앱에서 같습니다. 확인하지 않으면 녹음을 시작하지 않고, "다시 묻지 않기"를 고르거나 설정의 스위치를 끄면 더 이상 묻지 않으며, 설정에서 다시 켤 수 있습니다.

- **띄우는 시점은 기기에 따라 다릅니다.** Mac은 회의를 감지해 시작하는 녹음마다, Windows는 녹음을 시작할 때마다 묻습니다. 폰(iPhone·Android)은 회의를 따로 구분할 수단이 없으므로 **첫 녹음 전에 한 번만** 묻고, 설정 문구에 그 차이를 적어 두었습니다.
- **갤럭시 워치와 Apple Watch에는 이 안내가 없습니다.** 화면이 좁고 짝 지은 폰이 정본이기 때문입니다 — 워치로 녹음할 때에도 고지 책임은 같습니다.
- 이 안내는 관할을 판별하지 않고, 참석자에게 자동으로 알리지도 않으며, "알렸습니다"를 누른 사실은 법적 증거가 아닙니다. Google 계정 연결 시의 동의 화면은 이것과 **다른 동의**입니다(Drive 접근에 대한 사용자 본인의 동의).

## 9. 아동

Recly는 아동을 대상으로 하지 않으며 아동으로부터 개인정보를 의도적으로 수집하지 않습니다(수집 자체를 하지 않습니다).

## 10. 방침 변경

방침이 바뀌면 이 문서의 시행일을 갱신하고 저장소 이력에 남깁니다. 데이터가 나가는 경로가 새로 생기는 변경은 앱 안에서도 알립니다.

## 11. 문의

`<TODO: 공개 연락처 이메일>`

---

# English

## 1. Summary

Recly is a **recording app**. Recordings are uploaded to **your own Google Drive**, and what happens next is decided by the workflow you build.

**Recly has no servers.** There is no backend, no database, and no account system operated by the developer. As a result the developer **cannot collect, store, or see** your recordings, transcripts, workflows, or Google account data.

## 2. What the app handles, and where it lives

| Data | Where it is stored |
|---|---|
| Audio files and metadata (title, timestamps, duration, device name) | Your device, and your own Google Drive if you added an upload step |
| Workflow definitions | On your device only (never sent to Drive) |
| Google access and refresh tokens | Your device's secure storage (Android Keystore-backed encrypted storage / Apple Keychain / Windows Credential Manager) |
| Webhook signing keys and any STT API keys you enter | The same secure storage. **They are not synced between devices and are never sent to Recly** (there is no server to receive them). Only if you added a transcription step, that API key is sent **straight to the provider you chose**, for authentication only (§3(3)). A webhook signing key is never sent at all — it is only used to compute the signature |
| The email address of the Google account you signed in with | **On the device only.** The Android phone keeps it in secure storage to pick the same account again on the next launch; iPhone and Mac keep the value held by the Google Sign-In SDK as a hint that prefills the next sign-in. Windows stores none. Signing out removes it |
| Execution state (job queue, retries, upload progress) | A local database on your device |
| Diagnostic logs | Your device's system log. They leave the device only when you export them yourself |

## 3. Every case where data leaves your device

**(1) Your Google Drive.**
The app writes audio parts and `meta.json` into a recording folder. It uses only one permission — `drive.file` (files this app created) — and therefore **cannot see your other Drive files**. These files are yours and are visible only to you unless you share them.

**(2) A webhook address you configured.**
Only when you add a `webhook` step and enter a URL, the app sends one notification to that address. The body contains recording metadata and Drive file links; it does **not** contain the audio itself or the transcript text.

- **Requests are signed only if you configured a signing secret.** With a secret set, the request carries an HMAC-SHA256 (Standard Webhooks) signature header; without one it is sent **unsigned**.

That endpoint is operated by you, and what happens there is your responsibility.

**(3) A transcription provider you chose — only if you added that step.**
Transcription (STT) is **an optional step you may put into your workflow, not a fixed processing stage**. Only when you add such a step and enter your own API key does the device call **the provider you selected, directly, with your key**.

- Transcription step: **the full audio file** is sent to the STT provider you chose.
- There is no intermediary server. The request goes from your device to the provider.
- How long that provider keeps the data and what it does with it is governed by **that provider's policy**, which Recly does not control. Review the provider's privacy policy before adding the step.
- If you do not add this step, no audio or text is ever sent to that provider.

These are the fourteen providers you can choose from. **What is sent is the same whichever one you pick** — one audio track file, and the language and diarization options (the speaker-count hint) that ride on the same request. What happens to it afterwards — retention, training — differs by provider, so read that provider's own policy before you pick it.

| `provider` in the workflow | Company | Policy |
|---|---|---|
| `assemblyai` | AssemblyAI | <https://www.assemblyai.com/> |
| `clova` | NAVER Cloud CLOVA Speech | <https://www.ncloud.com/> |
| `rtzr` | Return Zero (RTZR) | <https://www.rtzr.ai/> |
| `openai` | OpenAI | <https://openai.com/> |
| `groq` | Groq | <https://groq.com/> |
| `together` | Together AI | <https://www.together.ai/> |
| `mistral` | Mistral AI | <https://mistral.ai/> |
| `elevenlabs` | ElevenLabs | <https://elevenlabs.io/> |
| `deepgram` | Deepgram | <https://deepgram.com/> |
| `azure` | Microsoft Azure AI Speech | <https://azure.microsoft.com/> |
| `daglo` | Daglo | <https://daglo.ai/> |
| `speechmatics` | Speechmatics | <https://www.speechmatics.com/> |
| `rev` | Rev AI | <https://www.rev.ai/> |
| `gladia` | Gladia | <https://www.gladia.io/> |

**(4) Your own paired devices — between watch and phone.**
When you record on a Galaxy Watch or an Apple Watch, the **audio files and their metadata** (title, timestamps, duration, checksums) move to the paired phone, because the watch neither uploads nor runs workflows. **This transfer happens even when your workflow has no Drive upload step at all.** In the other direction, the phone sends the watch a **workflow summary** — the id and name of each workflow, so the watch can offer a list. The steps inside a workflow are never sent.

- The transport is the operating system's device-pairing channel (the Wear OS Data Layer, Apple's WatchConnectivity). No Recly server is involved; none exists.
- **Both devices are yours.** Recly runs no server that relays this transfer. The channel itself belongs to the operating system, though: recordings are only sent while the two devices are near each other (on both Wear OS and Apple), but small items such as workflow names may be relayed through Google Play services' infrastructure when the devices are apart — that handling is governed by Google's privacy policy.
- Once the phone confirms receipt, the watch deletes its own copy — no recording history accumulates on the watch.
- API keys and tokens are never sent over this path.

**There is nothing else.** No path other than these four exists by which data leaves your device.

## 4. What is not collected

- No analytics, usage statistics, or behavioral logging.
- No automatic crash reporting.
- No advertising identifiers and no ads.
- No Recly account: no sign-up, and no email or profile data reaching the developer. The only permission the app asks Google for is `drive.file`; it does not request profile or contacts scopes. But **signing in with Google necessarily tells the app which account it is, and that account's email address stays on the device** — to pick the same account next time, and as described in the §2 table it is not transmitted to Recly or anywhere else.
- **The developer (Recly) collects nothing about you and sells, shares, or transfers nothing to third parties** — there is no data in the developer's hands to begin with. What does happen is **the transfers you direct**: as set out in §3, to your own Google Drive, to a webhook receiver you configured, to the STT provider you chose, and between your own paired devices (watch ↔ phone). Those happen because you asked for them; they are not the developer handing your data to a third party.

## 5. Limited Use of Google user data

Recly's use and transfer of information received from Google APIs adheres to the **Google API Services User Data Policy**, including the Limited Use requirements. Drive data is used only to provide the features you requested (uploading your recordings and syncing your workflow definitions), is never used for advertising, and is not read by humans — there is no server that could read it.

## 6. Security

- API keys and tokens are stored in the operating system's secure storage (Android Keystore-backed encrypted storage, Apple Keychain, Windows Credential Manager).
- All outbound communication uses HTTPS, except a `127.0.0.1`/`localhost` receiver you configure yourself.
- On **Android, iPhone and Apple Watch**, recordings live in the per-app area the operating system isolates (the app container) and other apps cannot reach them.
- **macOS and Windows have no such isolation.** The Mac app is distributed directly and therefore does not run inside a sandbox; its files are in `~/Library/Application Support/app.recly.mac/`, and on Windows in `%LOCALAPPDATA%\Recly\` — **other programs running under the same user account can read them.** What protects them there is the operating system's user-account permissions (file access control) and, if you have turned it on, disk encryption (FileVault on macOS, BitLocker on Windows). API keys and tokens are not kept with those files; they are in the Keychain and Credential Manager instead.
- No secure storage is complete without device-level security. Please use a device lock and disk encryption.

## 7. Retention and deletion

- **Automatic deletion**: local audio is deleted only after an upload has been confirmed. If the upload failed, or if your workflow has no upload step, the originals stay on the device. **They also stay when the upload stopped because your Google Drive is full** — the app tells you there is no space, and picks the upload back up when you have made room and pressed "Retry". There is no time-based automatic deletion.
- **Deleting a recording**: all four apps (Android phone, iPhone, Mac, Windows) can delete a recording from the list. Each time, a confirmation dialog **asks what to do about Drive, and the default is to keep it there** — the irreversible choice is never the default. If some parts have not reached Drive yet, the dialog says how many first.
- **What a deletion removes**: that device's whole recording folder (audio files, `meta.json`, local transcript copies) and **every record of that recording** — not only the recording and part records but the **job records** too (`job` and `step_run`: the copy of the workflow definition that recording ran, the per-step execution state, failure messages, upload and transcription progress, and step outputs). Those are what an earlier build left sitting in the database, invisible in the list; they no longer stay behind. Choosing "also delete the Drive folder" removes that recording's Drive folder as well.
- **A recording that is being processed is not deleted**: if one of its jobs is running, the deletion is refused with "try again once it has finished". If Drive refuses the folder deletion, the files on your device are still removed and the app tells you so.
- **Signing out**: all four apps have "Sign out", which removes this device's Google token (on Android, the stored account email as well). It does **not** remove recordings, job state, or the API keys you entered.
- **Disconnecting**: all four apps now have a **"Disconnect" in Settings, separate from signing out.** It revokes the Google grant and clears this device's tokens, the API keys and webhook signing keys you entered, the job records and the sync state. You can additionally tick "also delete the recordings on this device"; **if you leave it unticked the recordings stay** (a decision about an account does not delete an original that has not been uploaded). One exception: a recording whose job was **running at that moment** is kept together with its job records, and the app tells you how many — disconnect again once it has finished and they go too. **It never deletes anything in Drive** — those files are yours.
  - The confirmation dialog says first that **every device signed in with the same account loses access, not just this one**, that the workflow definitions in the application data folder may go, how many recordings have not reached Drive yet, and that this device's keys and queue are wiped; it also links to your Google account settings (<https://myaccount.google.com/permissions>).
  - **If the revocation fails, the app says so** — this device's data is cleared, but Recly is still listed on your Google account, so you have to remove it yourself at the link above.
  - Google documents the workflow definition in the application data folder as deleted **when a user uninstalls the app from their Drive**; what happens on a plain revocation is not stated by Google.
- **Copies held by an STT provider**: Recly cannot delete these for you. Contact that provider directly.

### What survives uninstalling the app

| Platform | Uninstalling | What is left, and how to remove it |
|---|---|---|
| Android phone · Galaxy Watch | **App data goes with it** — recordings, the local database and the encrypted store holding tokens and API keys all live in the app's private area and the OS removes them with the app | Nothing. Files in Drive stay, because they are yours |
| iPhone · Apple Watch | The app container (recordings, database, settings) is removed | **Apple does not guarantee that Keychain items are deleted** (service `app.recly.secrets`, or `app.recly.watch.*` on the watch — the API keys and webhook signing keys you entered, plus the Google Sign-In SDK's token item). If they persist, reinstalling the same app can read them again. They are not synced to another device or to iCloud (the items are `ThisDeviceOnly`). iOS and watchOS give you no way to delete Keychain items **by hand**, so **run "Disconnect" inside the app before you delete it** — that removes this device's Keychain items (tokens, the API keys and webhook signing keys you entered). The watch has no sign-in and no key entry, so its item (`app.recly.watch.*`) holds only an install identifier and there is no in-app path that clears it; once the app is gone, erasing the device is the only certain removal for whatever is left |
| macOS | **Only the `.app` bundle is removed** | (1) Delete `~/Library/Application Support/app.recly.mac/` (recordings, `rec.db`, `device.id`) yourself. (2) In Keychain Access delete the items whose service is `app.recly.mac.secrets` (the API keys and webhook signing keys you entered) plus the item the Google Sign-In SDK created (the Google token). (3) **App settings and the last account's email hint stay in `UserDefaults`** — recording mode, the consent reminder, language and accessibility settings, plus the email address used to prefill your next sign-in (`app.recly.auth.lastAccount`). Clear them with `defaults delete app.recly.mac` in Terminal |
| Windows | **Only the installed files are removed** | (1) Delete `%LOCALAPPDATA%\Recly\` (recordings, `rec.db`, `device.id`) yourself. (2) In Credential Manager → Windows Credentials delete the `app.recly.windows/tokens/…` and `app.recly.windows/secrets/…` entries. (3) **App settings stay in the registry** — the consent reminder, language, theme and accessibility settings live under `HKCU\Software\JavaSoft\Prefs\app\recly\windows`; delete that key in Registry Editor. The Windows app stores no account email, so there is none to remove |

### Clearing it from inside the app first

Most of the manual cleanup above becomes unnecessary if you **run "Disconnect" inside the app once before you delete it** — that clears the Keychain and Credential Manager items (tokens, the API keys and webhook signing keys you entered) and this device's job records. To remove the recordings as well, tick "also delete the recordings on this device" in that dialog, or delete them one by one from the list. App settings (macOS `UserDefaults`, the Windows registry) and the macOS and Windows data folders still remain, so follow the rest of the table for those.

## 8. Your responsibility when recording

Recly does not, and cannot, automatically notify other participants that a recording is taking place. Laws about recording conversations differ by country and region — for example, Korea permits recording a conversation you are part of but criminalizes recording conversations between others, while several U.S. states and the EU require all-party consent or prior notice. **It is your responsibility to know the law that applies to you and to obtain any consent required.** Nothing here is legal advice.

So the app asks you once, before a recording starts, **whether you told the participants.** That reminder is in **all four apps — Mac, Windows, iPhone and the Android phone** — with the same question, the same body text and the same link to a summary of the rules by jurisdiction. Without a confirmation the recording does not start; choosing "Do not ask again", or switching the setting off, stops it asking, and you can switch it back on in Settings.

- **When it appears differs by device.** The Mac asks for each recording it starts from a detected meeting; Windows asks for every recording. The phones (iPhone and Android) have no way to tell a meeting from anything else, so they ask **once, before the first recording**, and the setting text says so.
- **The Galaxy Watch and Apple Watch do not show it** — their screens are small and the paired phone is the reference. Your responsibility when recording from a watch is the same.
- The reminder does not determine your jurisdiction, does not notify participants for you, and pressing "I told them" is not legal evidence. The Google consent screen shown when you connect your account is **a different consent** — your own consent to Drive access.

## 9. Children

Recly is not directed at children and does not knowingly collect personal information from them — it does not collect personal information at all.

## 10. Changes to this policy

If this policy changes, the effective date above is updated and the change is kept in the repository history. Any change that creates a new path for data to leave the device is also announced inside the app.

## 11. Contact

`<TODO: public contact email>`
