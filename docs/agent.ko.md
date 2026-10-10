# ChatGPT 에이전트로 자동 회의록

[English](agent.md)

녹음을 멈추고 녹취록이 내 Google Drive에 올라오면, 내 ChatGPT 에이전트([dot](https://help.openai.com/en/articles/20001530-getting-started-with-your-dot)이나
Work 채팅)가 보통 1분 안에 알아서 회의록을 쓰기 시작합니다. 내 컴퓨터에서 직접 실행하는 작은 프로그램 `recly-events`가
이 일을 합니다. 새 녹취록을 찾아 [OpenAI MCP Events](https://developers.openai.com/plugins/build/mcp-events)와 내
[OpenAI Secure MCP Tunnel](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels)로 에이전트에게
알리고, 에이전트는 같은 터널로 recly-events에서 녹취록을 읽습니다.

로컬 폴더나 iCloud의 녹음을 같은 컴퓨터의 Claude나 Codex가 읽게 하려면 [로컬 MCP 서버](mcp.ko.md)를 보세요.

## 용어

| 용어 | 뜻 |
|---|---|
| dot | 한 번 만들어 두면 이벤트가 올 때 스스로 일을 시작하는 ChatGPT 에이전트. |
| Work 채팅 | ChatGPT 웹의 Work 모드에서 여는 채팅. dot처럼 이벤트를 구독할 수 있습니다. |
| MCP 이벤트 | 앱이 ChatGPT에 보내 에이전트를 깨우는 짧은 메시지. recly-events는 `recording.transcribed`를 보냅니다. |
| Secure MCP Tunnel | 공개 주소 없이 ChatGPT가 내 컴퓨터의 recly-events에 닿게 하는 OpenAI의 중계. |
| 터널 키 | 터널을 읽고 쓰는 권한만 있는 제한된 OpenAI API 키. |

## 필요한 것

- **녹음을 Google Drive에 저장하는 Recly.** iCloud나 로컬 폴더에 저장한 녹음은 보지 못합니다.
- **이벤트를 구독할 수 있는 ChatGPT 에이전트**: dot, 또는 ChatGPT 웹의 Work 채팅. 이 글을 쓰는 시점에 dot은
  ChatGPT Business Premium, 또는 EEA·스위스·영국 밖의 ChatGPT Pro가 필요합니다.
- **OpenAI Platform 계정**([platform.openai.com](https://platform.openai.com)). 터널과 키를 만들 때 씁니다.
- **recly-events를 실행해 켜 둔 Mac, Linux, Windows 컴퓨터.** 잠자기 중에도 잃는 것은 없고, 깨어나면 그사이의
  새 녹취록을 에이전트에게 알립니다.

## 설정하기

[recly-events 직접 실행하기](recly-events.ko.md)를 따르세요.

| 단계 | 명령이나 장소 |
|---|---|
| [1. 설치하기](recly-events.ko.md#1-설치하기) | 최신 [`events-v…` 릴리스](https://github.com/rokrokss/recly/releases?q=events-v&expanded=true) |
| [2. 터널과 키 만들기](recly-events.ko.md#2-터널과-키-만들기) | OpenAI Platform |
| [3. Google Drive와 터널 연결하기](recly-events.ko.md#3-google-drive와-터널-연결하기) | `recly-events init --google --tunnel-id tunnel_…` |
| [4. 실행하기](recly-events.ko.md#4-실행하기) | `recly-events service install`, Windows에서는 작업 스케줄러 |
| [5. ChatGPT에 앱 추가하기](recly-events.ko.md#5-chatgpt에-앱-추가하기) | ChatGPT → 플러그인 |
| [6. 에이전트에게 한 번만 말하기](recly-events.ko.md#6-에이전트에게-한-번만-말하기) | dot이나 Work 채팅 |
| [7. 시험하기](recly-events.ko.md#7-시험하기) | `recly-events test` |

## 컴퓨터 밖으로 나가는 것

recly-events는 Drive에 새로 생긴 녹취록을 이름, ID, 링크로 찾습니다. 이벤트는 에이전트에게 녹음 이름, 제목,
Drive 링크만 알리고 대화 내용은 담지 않습니다. 녹취록은 에이전트가 `get_transcript`로 요청할 때만 내 터널로
갑니다. Recly 개발자에게 가는 것은 없습니다. 전체 목록은
[개인정보처리방침](https://recly.dev/policy/privacy-policy.ko)에 있습니다.
