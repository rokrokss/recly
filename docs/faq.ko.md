# 자주 묻는 질문

[English](faq.md)

짧게 답하고, 자세한 내용은 그 내용이 있는 안내로 연결합니다. 답에 나오는 낱말은 맨 끝의 [용어](#용어)에서
설명합니다.

## Recly는 무료인가요?

네. 앱은 무료 오픈소스이고([AGPL-3.0-or-later](https://github.com/rokrokss/recly/blob/main/LICENSE)), 월 구독료도
없습니다. 기기 안 전사는 비용이 들지 않고 사용 시간 한도도 없습니다. 오디오가 기기 밖으로 나가지 않고, 분당 요금도
없습니다. 외부 업체는 내 키로 전사하고, 요금은 그 업체 가격입니다.
[전사 방식 고르기](setup.ko.md#3-전사-방식-고르기)를 보세요.

## Plaud와 무엇이 다른가요?

Plaud는 녹음기, 녹취, AI 노트를 팝니다. Recly는 이미 가진 워치, 폰, 컴퓨터를 녹음기로 쓰고, 기기 안에서 또는 내 API
키로 전사하고, 파일을 내 저장소에 둡니다. 노트는 이미 쓰는 AI가 씁니다. 알아서 회의록을 쓰는
[ChatGPT 에이전트](agent.ko.md)나, Claude, ChatGPT, Codex 등에서 쓰는
[예시 스킬](https://github.com/rokrokss/recly/blob/main/skills/README.md)(영어)이 그 일을 합니다.

## 워치가 있어야 하나요?

아니요. Android 폰, iPhone, Mac, Windows 앱은 그 자체로 녹음합니다. 워치를 쓰면 손목에 녹음기가 하나 더 생깁니다.
Galaxy Watch는 녹음을 Android 폰에, Apple Watch는 iPhone에 넘깁니다. [녹음하기](setup.ko.md#5-녹음하기)를 보세요.

## 폰 없이 워치만 쓸 수 있나요?

아니요. 워치는 혼자 업로드하지 못하고 녹음을 폰 앱에 넘기므로, 둘 다 설치해야 합니다. 폰이 가져갈 때까지 녹음은
워치에서 기다립니다. 직접 설치한다면 워치 앱과 폰 앱을 같은 곳에서 받아야 합니다. [설치 안내](install.ko.md)를
보세요.

## Google 계정이 있어야 하나요?

아니요. 기본값은 Google Drive지만, iPhone과 Mac에서는 내 iCloud에, iPhone·Mac·Windows·Android 폰에서는 내가 고른
로컬 폴더에 Google 계정 없이 저장할 수 있습니다. 예외는 [ChatGPT 에이전트로 자동 회의록](agent.ko.md)으로, 녹음이
Google Drive에 저장되어야 합니다. [녹음을 둘 곳 고르기](setup.ko.md#2-녹음을-둘-곳-고르기)를 보세요.

## 녹음은 어디로 가고, Recly가 볼 수 있나요?

Recly에는 서버가 없습니다. 데이터는 고른 저장소(내 Google Drive, iPhone·Mac에서는 내 iCloud, 또는 기기 안에 머무는
로컬 폴더), 내 키로 고른 전사 업체, 짝지은 내 워치와 폰 사이, 그리고 자동 회의록을 설정했다면 OpenAI를 거쳐 내 ChatGPT
에이전트에게만 갑니다. 에이전트에게 가는 것은 녹음의 이름·제목·Drive 링크와, 에이전트가 요청할 때의 녹취록이고, 오디오는
가지 않습니다.
Google Drive에서 Recly는 `drive.file` 권한만 요청하므로 자기가 만든 파일만 봅니다. Recly 개발자에게 가는 것은 없으며,
모든 경로는 [개인정보처리방침](https://recly.dev/policy/privacy-policy.ko)에 있습니다.

## 다른 기기에서도 내 녹음이 보이나요?

같은 Google 계정으로 연결한 폰과 데스크톱은 Google Drive에 있는 서로의 녹음을 목록에 보여 줍니다. 같은 Apple ID로
둘 다 iCloud에 저장하는 iPhone과 Mac도 서로의 녹음을 보여 줍니다. 로컬 폴더는 그 기기만 쓰므로, 다른 기기의 목록에는
나오지 않습니다. [녹음 찾기](setup.ko.md#6-녹음-찾기)를 보세요.

## Android와 Windows에는 왜 iCloud가 없나요?

Recly는 Apple 시스템이 앱마다 마련해 두고 직접 올려 주는 iCloud Drive 폴더로 iCloud에 저장하는데, 이 폴더는
iPhone과 Mac에만 있습니다. Android와 Windows에서는 Google Drive나 로컬 폴더를 고르세요. 로컬 폴더는 다른 도구가
동기화하는 폴더여도 됩니다.
[녹음을 둘 곳 고르기](setup.ko.md#2-녹음을-둘-곳-고르기)를 보세요.

## 어떤 기기에서 기기 안 전사를 할 수 있나요?

iPhone과 Mac에서는 Apple 음성 인식 모델을 쓰며, iOS·macOS 26 이상, 지원 하드웨어와 언어 자산이 필요합니다.
Android와 Windows에서는 Qwen3-ASR 0.6B를 씁니다. Android 폰과 Windows PC는 기기가 보고하는 메모리가 6 GiB 이상인
64비트 기기여야 하고(실제로는 8 GB 폰이나 PC), 약 1 GB를 한 번 내려받습니다. 워치는 전사를 폰에 맡기고, 다른
기기에서는 외부 업체를 쓸 수 있습니다. [전사 방식 고르기](setup.ko.md#3-전사-방식-고르기)를 보세요.

## 기기 안에서 화자를 구분하나요?

네. iPhone과 Mac은 앱에 들어 있는 모델로 화자를 구분합니다. Android 폰과 Windows PC는 음성 인식 모델과 함께 받는 약
41 MB의 화자 모델로 문장 단위로 구분하고, 이 모델이 없으면 녹취록은 화자 없이 만들어집니다. 외부 업체를 쓰면 업체가
지원하는 경우 화자를 구분합니다. 녹음을 멈춘 뒤 넣는 **참석 인원**은 어느 쪽이든 도움이 되고, 녹음 **상세**에서 화자
이름을 붙이거나 한 줄을 다른 화자로 옮길 수 있습니다. [전사 방식 고르기](setup.ko.md#3-전사-방식-고르기)를 보세요.

## 하이라이트는 무엇이고 어디에 남나요?

녹음 중에 표시한 순간입니다. 폰, 두 워치(watchOS 11 이상의 Apple Watch는 이중 탭도), Mac 팝오버, Windows 트레이
창의 **하이라이트**로 표시합니다. 나중에 녹음 **상세**에서 더하거나 지울 수 있습니다. 하이라이트는 오디오 옆의
`….meta.json`과, 로컬 폴더라면 `.md` 녹취록에 남고, `.txt` 녹취록에는 들어가지 않습니다. ChatGPT 에이전트와
[로컬 MCP 서버](mcp.ko.md)는 녹취록과 함께 하이라이트를 보므로, 에이전트가 내가 표시한 곳을 감안할 수 있습니다.
[순간 표시하기](setup.ko.md#순간-표시하기)를 보세요.

## 어떤 파일을 가져올 수 있나요?

기기가 디코딩할 수 있는 오디오·동영상 파일입니다. Android와 Apple 기기는 자체 디코더가, Windows는 함께 설치되는
ffmpeg가 여는 파일이면 됩니다. Recly는 소리만 자기 형식으로 바꿔 이 기기의 녹음으로 만들고, 다른 녹음처럼 업로드하고
전사합니다. 동영상의 화면은 남기지 않습니다. 오디오가 없거나 기기가 디코딩하지 못하는 파일(복제 방지된 트랙 등)은
**이 파일을 가져오지 못했습니다**와 함께 거절됩니다. [오디오 가져오기](setup.ko.md#오디오-가져오기)를 보세요.

## AI가 알아서 노트를 쓰게 할 수 있나요?

네, ChatGPT 에이전트로 됩니다. 녹취록이 내 Google Drive에 올라오면 내 컴퓨터에서 직접 실행하는 작은 프로그램
recly-events가 에이전트에게 알리고, 에이전트가 보통 1분 안에 알아서 회의록을 씁니다. 필요한 것: Google Drive에
저장되는 녹음, ChatGPT dot(이 글을 쓰는 시점에 ChatGPT Business Premium, 또는 EEA·스위스·영국 밖의 ChatGPT Pro)
또는 ChatGPT 웹의 Work 채팅, 터널과 키를 만들 OpenAI Platform 계정, 그리고 켜 둔 Mac, Linux, Windows 컴퓨터.
[recly-events를 직접 실행](recly-events.ko.md)하세요. 하는 일은 [ChatGPT 에이전트 안내](agent.ko.md)에 있습니다.
다른 에이전트에서는
[예시 스킬](https://github.com/rokrokss/recly/blob/main/skills/README.md)(영어)이 부탁할 때 노트를 쓰고, 로컬
폴더나 iCloud의 녹음은 Claude와 Codex가 [로컬 MCP 서버](mcp.ko.md)로 읽습니다.

## 녹취록을 고치면 에이전트가 다시 실행되나요?

아니요. 글을 고치거나 화자 이름을 붙이면 저장소의 전사 파일이 바뀌지만, 자동 회의록을 돌리는 recly-events는 고친 것을
새 녹취록으로 알리지 않습니다. **다시 전사**는 에이전트를 다시 실행합니다. 새 전사는 처음과 똑같이 알립니다. 예외:
[내 Google 클라이언트](https://github.com/rokrokss/recly/blob/main/events/README.md#using-a-google-client-of-your-own)(영어)로
실행한 recly-events는 고친 것을 구분하지 못해 그것도 알립니다.

## Recly는 아무에게도 알리지 않고 녹음하나요?

몰래 하는 것은 없습니다. 녹음 중에는 언제나 보이고, Mac과 Windows 앱은 회의를 알아채면 먼저 묻고 녹음합니다.
Recly는 참가자에게 녹음을 알렸는지도 묻습니다. 폰은 첫 녹음 전에, 데스크톱은 녹음할 때마다 묻고, 설정에서 이 질문을
끌 수 있습니다. 다른 사람에게 알리는 일은 Recly가 대신할 수 없고, 규칙은 나라와 지역마다 다릅니다. 개인정보처리방침의
[녹음과 관련한 사용자의 책임](https://recly.dev/policy/privacy-policy.ko#8-녹음과-관련한-사용자의-책임)과
[녹음하기](setup.ko.md#5-녹음하기)를 보세요.

## Windows는 왜 경고하고, Windows 앱은 준비됐나요?

MSI에 코드 서명이 없어서 SmartScreen이 "Windows의 PC 보호" 창을 띄웁니다. **추가 정보** → **실행**을 고르세요
([설치 안내](install.ko.md#windows)).

**베타.** Windows 앱은 CI에서 빌드하고 테스트하지만 아직 실제 Windows PC에서 확인하지 못했습니다.
발견한 문제는 [Issues](https://github.com/rokrokss/recly/issues)에 알려 주세요.

## Mac과 Windows 앱은 어떻게 업데이트하나요?

스스로 업데이트하지 않습니다. [최신 릴리스](https://github.com/rokrokss/recly/releases/latest)의 새 DMG나 MSI를
기존 앱 위에 설치하세요. MSI는 설치된 앱을 업그레이드합니다. 새 릴리스 알림을 받으려면 GitHub의
[rokrokss/recly](https://github.com/rokrokss/recly)에서 **Watch** → **Custom** → **Releases**를 고르세요.
[업데이트하기](install.ko.md#업데이트하기)를 보세요.

## 버그는 어떻게 안전하게 알리나요?

[Issues](https://github.com/rokrokss/recly/issues)에 이슈를 여세요. 녹음, 녹취록, 키는 절대 첨부하지 말고, 문제를
글로 설명하고 붙여 넣는 내용에서 개인적인 부분은 가리세요. 보안 취약점은 공개 이슈로 올리지 말고
[SECURITY.md](https://github.com/rokrokss/recly/blob/main/SECURITY.md)(영어)에 적힌 대로 비공개로 알려 주세요.

## 용어

| 용어 | 뜻 |
|---|---|
| dot | 한 번 만들어 두면 이벤트가 올 때 스스로 일을 시작하는 ChatGPT 에이전트. |
| Work 채팅 | ChatGPT 웹의 Work 모드에서 여는 채팅. dot처럼 이벤트를 구독할 수 있습니다. |
| MCP | Model Context Protocol. ChatGPT가 recly-events 같은 외부 앱과 대화하는 표준. |
| MCP 이벤트 | 앱이 ChatGPT에 보내 에이전트를 깨우는 짧은 메시지. recly-events는 `recording.transcribed`를 보냅니다. |
| Secure MCP Tunnel | 공개 주소 없이 ChatGPT가 내 컴퓨터의 recly-events에 닿게 하는 OpenAI의 중계. |
| 터널 키 | 터널을 읽고 쓰는 권한만 있는 제한된 OpenAI API 키. |
| `drive.file` | Google Drive의 가장 좁은 권한. Recly는 자기가 만든 파일만 봅니다. |
| 사이드로드 | Google Play 대신 내려받은 파일(APK)로 앱을 설치하는 것. |
| ADB | Android Debug Bridge. [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools)에 든 명령줄 도구로, USB나 Wi-Fi로 폰·워치에 앱을 설치합니다. |
| Linger | 로그아웃한 뒤에도 사용자 서비스를 계속 돌게 하는 systemd 설정. |
