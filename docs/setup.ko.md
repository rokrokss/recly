# Recly 설정 안내

[English](setup.md)

Recly는 [설치](install.ko.md)하면 바로 녹음할 수 있습니다. 설정에서 정하는 것은 두 가지입니다. 녹음을 어디에
둘지, 그리고 어떻게 전사할지. 처음에는 Google Drive의 `recly/memo/<연>-<월>` 폴더에 저장하고, 기기가 지원하면
기기 안에서 전사하며 지원하지 않으면 전사하지 않습니다. 워치에는 설정이 없습니다. 워치는 녹음을 폰에 넘기고,
폰이 자기 설정대로 처리합니다.

## 1. 설정 열기

- **Android 폰, iPhone**: **설정** 탭.
- **Mac**: 메뉴 막대 아이콘 → 팝오버 맨 아래의 **설정**. Recly는 창도 Dock 아이콘도 없습니다.
- **Windows**: 트레이 아이콘 → **설정**을 누르면 **Recly 설정** 창이 열립니다.

## 2. 녹음을 둘 곳 고르기

맨 위 **저장 위치**에서 고릅니다.

| 선택 | 쓸 수 있는 곳 | 필요한 것 |
|---|---|---|
| **Google Drive**(기본값) | 모든 앱 | **Drive 연결**을 누르고 Google로 로그인 |
| **iCloud** | iPhone, Mac | 시스템 설정에서 Recly의 iCloud Drive 켜기 |
| **로컬 폴더** | iPhone, Mac, Windows, Android 폰 | **폴더 선택** |

- **Google Drive.** **Drive 연결**을 누르고 로그인합니다. Google은 Recly가 직접 만든 파일만 보게 할지 묻습니다
  (`drive.file`). Recly는 Drive의 다른 파일을 볼 수 없습니다. 연결 전에도 녹음할 수 있고, 연결하면 그때 업로드합니다.
  연결되면 그 행에 Google 계정이 보이고, 계정 이름이 없는 앱에서는(Windows는 항상) **Drive 연결됨**이 보입니다.
  옆에는 **연결 해제**가 있습니다.
- **iCloud.** 앱 안에는 버튼이 없습니다. iPhone에서는 설정 → [이름] → iCloud → Drive에서 **이 iPhone 동기화**를
  켜고, **iCloud에 저장됨** → **모두 보기**에서 Recly를 켭니다. Mac에서는 **이 Mac 동기화**를 켜고 **iCloud Drive에
  동기화되는 앱**에서 Recly를 켭니다. Recly의 **iCloud 설정 열기**를 누르면 그 화면으로 갑니다. 녹음은 파일 앱이나
  Finder의 **Recly** 폴더에 보입니다.
- **로컬 폴더.** **폴더 선택**을 눌러 기기의 아무 폴더나 고릅니다. Obsidian 볼트나 다른 도구가 동기화하는 폴더도
  됩니다. Google 계정이 필요 없습니다. 녹취록은 `.transcript.md`로도 저장됩니다. 이 폴더는 이 기기만 쓰므로, 다른
  기기의 목록에는 나오지 않습니다.

바꾼 저장 위치는 그다음에 시작한 녹음부터 적용되고, 이미 한 녹음은 옮겨지지 않습니다. Google Drive에서 다른 곳으로
바꿔도 Drive 연결은 끊기지 않습니다. [ChatGPT 에이전트](agent.ko.md)로 자동 회의록을 받으려면 Google Drive여야
합니다.

## 3. 전사 방식 고르기

**녹음 처리** → **전사**에서 고릅니다.

- **로컬.** 오디오가 기기 밖으로 나가지 않고, 분당 요금도 없습니다. 음성 인식 모델을 한 번 받아야 합니다. 설정의
  **모델 다운로드**를 누르거나, 녹음 탭·Mac 팝오버·Windows 트레이 창에 나오는 **이 기기에서 전사하기** 카드에서
  받습니다. iPhone과 Mac은 Apple 음성 인식 모델, Android와 Windows는 Qwen3-ASR 0.6B입니다. 기기 안 전사는
  화자를 구분하지 않습니다. iPhone과 Mac은 iOS·macOS 26 이상, 지원 하드웨어와 언어 자산이 필요합니다. Android
  폰과 Windows PC는 기기가 보고하는 메모리가 6 GiB 이상인 64비트 기기여야 하고(실제로는 8 GB 폰이나 PC), 약
  1 GB를 한 번 내려받습니다.
- **외부 API.** 내가 고른 업체로 오디오를 보내 내 키로 전사합니다. 요금은 그 업체 가격입니다. **제공자**를 고르고
  **API 키**를 넣은 뒤 **키 저장**을 누르면 **이 기기에 저장됨**으로 바뀝니다. CLOVA Speech와 Azure AI Speech는
  **Invoke URL**도 넣어야 하고, RTZR는 키를 `client ID:client secret` 형식으로 넣습니다. 업체가 지원하면 화자를 자동으로
  구분합니다.
  - Android, Mac, Windows: ElevenLabs, CLOVA Speech, AssemblyAI, RTZR, OpenAI, Groq, Together AI, Mistral AI,
    Deepgram, Azure AI Speech, Daglo, Speechmatics, Rev AI, Gladia.
  - iPhone: ElevenLabs, CLOVA Speech, AssemblyAI, RTZR, OpenAI, Groq, Deepgram, Azure AI Speech. 키를 저장할 때 그
    업체로 녹음을 보내도 되는지 한 번 묻습니다.
- **전사 안 함.** 업로드만 하고 전사하지 않습니다.

### 키 받는 곳

업체마다 아래 페이지에서 키를 발급합니다(2026-10-06 확인). 페이지마다 먼저 로그인하거나 계정을 만들어야 하고,
요금은 업체가 그 계정에 자기 가격으로 청구합니다.

| 업체 | 키 받는 곳 |
|---|---|
| AssemblyAI | [Dashboard → API Keys](https://www.assemblyai.com/dashboard/api-keys) |
| Azure AI Speech | Azure Portal에서 Speech 또는 Foundry 리소스 → **Keys and Endpoint**: **KEY 1**이 키, **Endpoint**가 Invoke URL ([안내](https://learn.microsoft.com/ko-kr/azure/ai-services/speech-service/fast-transcription-create)) |
| CLOVA Speech | 네이버 클라우드 콘솔 → CLOVA Speech → Domain → **빌더 실행** → **설정** → **연동 정보**: Secret Key가 키이고 바로 옆에 Invoke URL이 있습니다 ([안내](https://guide.ncloud-docs.com/docs/clovaspeech-builder-long#api-호출-정보-확인)) |
| Daglo | [개발자 콘솔](https://developers.daglo.ai/console) → 토큰 메뉴 |
| Deepgram | [Console](https://console.deepgram.com/) → 프로젝트 **Settings** → **API Keys** |
| ElevenLabs | [API keys](https://elevenlabs.io/app/developers/api-keys) |
| Gladia | [API keys](https://app.gladia.io/apikeys) |
| Groq | [API keys](https://console.groq.com/keys) |
| Mistral AI | [API keys](https://console.mistral.ai/api-keys) |
| OpenAI | [API keys](https://platform.openai.com/api-keys) |
| Rev AI | [Access token](https://www.rev.ai/access-token) |
| RTZR | [콘솔](https://developers.rtzr.ai/console/): client ID와 secret을 발급받아 `client ID:client secret` 형식으로 넣습니다 |
| Speechmatics | [API keys](https://portal.speechmatics.com/settings/api-keys/) |
| Together AI | [API keys](https://api.together.ai/settings/projects/~current/api-keys) |

ElevenLabs와 Rev AI의 별도 EU 데이터 보관 계정에서 받은 키는 쓸 수 없습니다. Recly는 두 업체의 기본
호스트를 부릅니다.

**녹음 언어**는 녹음에서 쓰는 언어입니다. **자동**, 섞어 말할 때 쓰는 **한국어·영어 혼용**, 또는 언어 하나를
고릅니다. 기기 안 전사는 언어를 직접 골라야 합니다.

키는 저장한 기기에만 있고 동기화되지 않습니다. 기기마다 따로 넣으세요.

## 4. 앱이 묻는 권한 허용하기

| 앱 | 언제 | 무엇을 |
|---|---|---|
| Android 폰 | 첫 녹음 | 알림, 그다음 마이크 |
| Galaxy Watch | 처음 실행할 때 | 마이크와 알림 |
| iPhone | 첫 녹음 | 마이크 |
| Apple Watch | 첫 녹음 | 마이크 |
| Mac | 첫 녹음 | 마이크와 시스템 오디오(통화 상대 소리를 녹음). 알림은 처음 회의를 알아챘을 때 |
| Windows | 묻지 않음 | Windows가 마이크를 막고 있으면 Recly가 알려 주고 그 설정을 엽니다 |

거부했다면 Recly가 어디서 다시 켜는지 알려 줍니다.

## 5. 녹음하기

- **Galaxy Watch**: 앱이나 타일, 또는 **Recly 녹음**을 연결한 홈 키 두 번 누르기([설치 안내](install.ko.md#galaxy-watch)).
  폰에서 Galaxy Wearable의 배터리 최적화를 꺼 두세요. 켜져 있으면 전송이 멈춥니다.
- **Apple Watch**: 앱이나 컴플리케이션. Ultra는 액션 버튼도 됩니다.
- **Android 폰**: 녹음 탭, 빠른 설정 타일, 홈 화면 위젯, 앱 아이콘 바로가기.
- **iPhone**: 녹음 탭, Siri나 단축어, 동작 버튼, 제어 센터 컨트롤.
- **Mac**: 메뉴 막대 아이콘 → **녹음 시작**. 회의 앱이 마이크를 쓰기 시작하면 Recly가 **회의 중인가요?**라고 묻고,
  녹음하겠다고 할 때만 녹음합니다.
- **Windows**: 트레이 아이콘 → **녹음 시작**. 회의를 알아채면 **감지된 회의 녹음 시작**도 나옵니다.

Recly는 참가자에게 녹음을 알렸는지 묻습니다. 폰은 첫 녹음 전에 한 번, 데스크톱은 녹음할 때마다 묻습니다(설정의
**녹음 전 동의 확인**으로 끌 수 있습니다). 녹음을 멈추면 폰과 데스크톱이 **녹음 제목**과 **참석 인원**을 묻습니다.
참석 인원은 업체가 화자를 나누는 데 씁니다. 제목을 비워 두더라도 **저장**을 누르세요. **취소를 누르면 녹음이
지워집니다.**

## 6. 녹음 찾기

- **앱에서**: 폰의 **목록** 탭, Mac 팝오버, Windows 트레이 창. 녹음을 열면 **상세**에서 재생, 녹취록, 검색,
  **전체 복사**를 할 수 있습니다. **Drive 열기**, **Finder에서 보기**(Mac), **폴더 열기**(Windows)로 파일에 갑니다. 같은
  Google 계정을 쓰는 기기들은 서로의 녹음도 목록에 보여 줍니다.
- **저장소에서**: 녹음마다 폴더가 하나씩 생깁니다. 예: `recly/memo/2026-10/20261006T010000Z_phone_01J9ABCD/`. 안에는
  15분쯤씩 나뉜 오디오 파일, `….meta.json`, 전사했다면 `….transcript.txt`와 `….transcript.json`이 있습니다. 폴더
  형식은 **녹음 처리** → **저장 폴더**에서 바꿉니다.

## 7. 노트는 AI에게 맡기기

Recly는 녹취록에서 멈추고, 이미 쓰는 AI에게 넘깁니다.

- **ChatGPT로 자동으로**: [ChatGPT 에이전트로 자동 회의록](agent.ko.md)(Mac·Windows 앱, Google Drive).
- **어떤 에이전트든 부탁할 때**: Claude, ChatGPT, Codex 등에서 쓰는
  [예시 스킬](https://github.com/rokrokss/recly/blob/main/skills/README.md)(영어).

## 다른 기기로 옮기기

**설정 파일** → **설정 내보내기**로 `recly-settings.json`을 만듭니다. 폴더, 전사 방식, 언어, 제공자가 들어가고 키는
들어가지 않습니다. 다른 기기에서 **설정 가져오기**로 불러와 값을 확인하고 저장한 뒤, 그 기기에서 키를 넣으세요.
저장 위치는 기기마다 따로 정합니다.

## 문제가 있을 때

- **워치 녹음이 오지 않음**: 워치는 폰 앱이 있어야 합니다. Galaxy는 Galaxy Wearable의 배터리 최적화를 끄세요. 워치에
  "전송 대기"가 보이면 폰이 아직 받지 않은 것입니다.
- **모바일 데이터에서 업로드가 멈춤**: 폰 설정의 **Wi-Fi에서만 업로드**가 켜져 있습니다.
- **로컬 선택지가 없음**: iPhone과 Mac은 iOS·macOS 26 이상과 지원 하드웨어가, Android 폰과 Windows PC는
  64비트, 보고 메모리 6 GiB 이상(실제로는 8 GB 기기)이 필요합니다.
- **Mac, "내장 스피커로 듣고 있습니다"**: 헤드폰을 쓰면 통화 상대 소리가 내 마이크 트랙에 섞이지 않습니다.
