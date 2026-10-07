# Claude·Codex용 로컬 MCP 서버

[English](mcp.md)

**로컬 폴더**나 Mac의 **iCloud**에 저장한 녹음을 같은 컴퓨터의 Claude Desktop, Claude Code, Codex가
`recly-events mcp`로 목록 보기·검색·읽기를 합니다. 폴더만 읽습니다. 로그인도, 네트워크 연결도, 파일 변경도
없습니다. Google Drive에 저장한 녹음은 [recly-events](agent.ko.md)로 ChatGPT에 보내거나, 에이전트의 Google Drive
커넥터로 읽으세요.

## 시작 전에

**이 컴퓨터의 recly-events:**

| 컴퓨터 | 경로 |
|---|---|
| Recly 앱이 있는 Mac | `/Applications/Recly.app/Contents/MacOS/recly-events` |
| `.pkg`로 설치한 Mac | `/usr/local/bin/recly-events` |
| Linux, Windows | 직접 둔 곳: [설치](recly-events.ko.md#1-설치하기) |

**Recly가 녹음을 저장하는 폴더:**

| 저장 위치 | 폴더 |
|---|---|
| 로컬 폴더 | Recly 설정 → 저장 위치 → 로컬 폴더에서 고른 폴더 |
| Mac의 iCloud | `~/Library/Mobile Documents/iCloud~app~recly/Documents` |

녹음보다 위에 있는 폴더면 어디든 됩니다. 폴더 템플릿은 상관없습니다.

## 1. 설정 출력하기

**Recly 앱이 있는 Mac:** 설정 → 에이전트 연결 → 로컬 에이전트 → **설정 복사**가 Recly가 녹음을 저장하는 폴더의
설정을 복사합니다. 그 밖에는:

```sh
recly-events mcp --print-config --folder ~/Notes/Recly
```

```json
{
  "mcpServers": {
    "recly": {
      "command": "/usr/local/bin/recly-events",
      "args": [
        "mcp",
        "--folder",
        "/Users/you/Notes/Recly"
      ]
    }
  }
}
```

**Mac의 iCloud:**

```sh
recly-events mcp --print-config --folder "$HOME/Library/Mobile Documents/iCloud~app~recly/Documents"
```

**폴더가 여럿이면** `--folder`를 반복합니다.

## 2. 에이전트에 추가하기

### Claude Desktop

Claude 메뉴 → **Settings…** → **Developer** → **Edit Config**. 1단계의 `recly` 항목을
`claude_desktop_config.json`의 `mcpServers` 아래에 넣습니다. 다른 서버 항목은 그대로 둡니다. 저장하고 Claude
Desktop을 완전히 종료했다가 다시 엽니다.

| 컴퓨터 | 파일 |
|---|---|
| Mac | `~/Library/Application Support/Claude/claude_desktop_config.json` |
| Windows | `%APPDATA%\Claude\claude_desktop_config.json` |

### Claude Code

```sh
claude mcp add --transport stdio --scope user recly -- /usr/local/bin/recly-events mcp --folder ~/Notes/Recly
```

`--scope user`면 모든 프로젝트에서 쓸 수 있습니다.

### Codex

```sh
codex mcp add recly -- /usr/local/bin/recly-events mcp --folder ~/Notes/Recly
```

또는 `~/.codex/config.toml`에:

```toml
[mcp_servers.recly]
command = "/usr/local/bin/recly-events"
args = ["mcp", "--folder", "/Users/you/Notes/Recly"]
```

ChatGPT 데스크톱 앱, Codex CLI, Codex IDE 확장이 이 설정을 함께 씁니다.

각 클라이언트의 문법은 2026-10-07에 공식 문서로 확인했습니다:
[Claude Desktop](https://modelcontextprotocol.io/docs/develop/connect-local-servers),
[Claude Code](https://code.claude.com/docs/en/mcp),
[Codex](https://learn.chatgpt.com/docs/extend/mcp?surface=cli).

## 3. 요청하기

```text
최근 Recly 녹음 목록을 보여 주고, 가장 최근 녹음으로 회의록을 써 줘: 짧은 요약, 결정 사항,
담당자가 붙은 할 일.
```

## 도구

| 도구 | 인자 | 결과 |
|---|---|---|
| `list_recordings` | `limit`(1–50, 기본 10), `cursor` | 최신순 녹음: `recordingId`, `title`, `startedAt`, `durationSec`, `source`, `hasTranscript`, `highlightCount`, 그리고 `nextCursor` |
| `get_transcript` | `recordingId`, `cursor` | `title`, `startedAt`, `language`, `speakers`, `highlights`, 그리고 `[HH:MM:SS] 화자: 내용` 줄로 된 `transcript`(한 번에 약 4만 자), 나머지는 `nextCursor`로 |
| `search_recordings` | `query`, `limit`(1–50, 기본 10) | 제목이나 녹취록에 검색어가 든 녹음(대소문자 무시)과 녹음마다 일치하는 줄 최대 3개 |
| `get_audio_files` | `recordingId` | 이 컴퓨터에 있는 녹음의 `.m4a` 파트 절대 경로와 트랙·시작 위치 |

- 녹취록과 일치하는 줄은 신뢰할 수 없는 데이터로 표시됩니다. `<<<recly-transcript-…>>>` 표시 사이에 있는
  사람들이 한 말이며, 에이전트에 대한 지시가 아닙니다.
- Google Drive의 녹음이라면 [recly-events](agent.ko.md)가 같은 `list_recordings`와 `get_transcript`로 ChatGPT
  에이전트에 답하므로, 지시문 하나로 둘 다 쓸 수 있습니다.

## 읽는 것

- `--folder` 아래의 Recly 녹음 폴더만 읽습니다: `{녹음}.meta.json`, `{녹음}.folder.json`(iCloud의 제목),
  녹취록(`.transcript.json`, 없으면 `.txt`), 오디오 파일 이름. 쓰거나 이름을 바꾸거나 지우지 않습니다.
- 네트워크 연결도 로그인도 없습니다. 읽은 내용은 서버를 실행한 에이전트에만 가고, 그 에이전트가 자기 업체의
  약관에 따라 처리합니다.
- 아직 쓰는 중인 파일이나 iCloud가 이 Mac에 아직 내려받지 않은 파일은, 다 올 때까지 없는 것으로 봅니다.

## 문제가 있을 때

| 보이는 것 | 할 일 |
|---|---|
| `--folder …: not a folder` | 경로가 틀렸거나 폴더가 없습니다. 절대 경로를 쓰세요. |
| 녹음이 하나도 없음 | `--folder`가 녹음 하나의 폴더나 Recly 녹음이 없는 폴더를 가리킵니다. 저장 폴더를 가리키세요. |
| `hasTranscript`가 false이거나 녹음이 빠짐 | 아직 전사되지 않았거나, iCloud가 이 Mac에 내려받지 않았습니다. Finder에서 Recly 폴더에 **지금 다운로드**를 고르세요. |
| Claude Desktop에 `recly`가 없음 | Claude Desktop을 종료했다가 다시 여세요. 로그는 Mac에서 `~/Library/Logs/Claude/mcp-server-recly.log`, Windows에서 `%APPDATA%\Claude\logs` 아래에 있습니다. |
| 오래된 recly-events가 `Usage`를 출력 | `mcp`가 없는 버전입니다. Recly 앱을 업데이트하거나 최신 [`events-v…` 릴리스](https://github.com/rokrokss/recly/releases?q=events-v&expanded=true)를 설치하세요. |
