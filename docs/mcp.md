# Local MCP server for Claude and Codex

[한국어](mcp.ko.md)

For recordings in a **local folder**, or in **iCloud** on a Mac: Claude Desktop, Claude Code or
Codex on the same computer lists, searches and reads them through `recly-events mcp`. It reads the
folders and nothing else: no sign-in, no network, no changes. Recordings in Google Drive go to
ChatGPT with [recly-events](agent.md), or to any agent through its own Google Drive connector.

## Before you start

**recly-events on this computer:**

| Computer | Path |
|---|---|
| Mac with the Recly app | `/Applications/Recly.app/Contents/MacOS/recly-events` |
| Mac, from the `.pkg` | `/usr/local/bin/recly-events` |
| Linux, Windows | Where you put it: [Install](recly-events.md#1-install) |

**The folder Recly stores recordings in:**

| Storage | Folder |
|---|---|
| Local folder | The folder chosen in Recly's Settings → Storage → Local folder |
| iCloud, on a Mac | `~/Library/Mobile Documents/iCloud~app~recly/Documents` |

Any folder above the recordings works; the folder template does not matter.

## 1. Print the configuration

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

**iCloud on a Mac:**

```sh
recly-events mcp --print-config --folder "$HOME/Library/Mobile Documents/iCloud~app~recly/Documents"
```

**More than one folder:** repeat `--folder`.

## 2. Add it to your agent

### Claude Desktop

Claude menu → **Settings…** → **Developer** → **Edit Config**. Put the `recly` entry from step 1
under `mcpServers` in `claude_desktop_config.json`, keeping any other servers there. Save, quit
Claude Desktop and open it again.

| Computer | File |
|---|---|
| Mac | `~/Library/Application Support/Claude/claude_desktop_config.json` |
| Windows | `%APPDATA%\Claude\claude_desktop_config.json` |

### Claude Code

```sh
claude mcp add --transport stdio --scope user recly -- /usr/local/bin/recly-events mcp --folder ~/Notes/Recly
```

`--scope user` makes it available in every project.

### Codex

```sh
codex mcp add recly -- /usr/local/bin/recly-events mcp --folder ~/Notes/Recly
```

Or in `~/.codex/config.toml`:

```toml
[mcp_servers.recly]
command = "/usr/local/bin/recly-events"
args = ["mcp", "--folder", "/Users/you/Notes/Recly"]
```

The ChatGPT desktop app, the Codex CLI and the Codex IDE extension share this configuration.

Each client's syntax was checked against its documentation on 2026-10-07:
[Claude Desktop](https://modelcontextprotocol.io/docs/develop/connect-local-servers),
[Claude Code](https://code.claude.com/docs/en/mcp),
[Codex](https://learn.chatgpt.com/docs/extend/mcp?surface=cli).

## 3. Ask

```text
List my latest Recly recordings, then write minutes for the newest one: a short summary,
the decisions, and the action items with owners.
```

## Tools

| Tool | Arguments | Returns |
|---|---|---|
| `list_recordings` | `limit` (1–50, default 10), `cursor` | Recordings, newest first: `recordingId`, `title`, `startedAt`, `durationSec`, `source`, `hasTranscript`, `highlightCount`; `nextCursor` |
| `get_transcript` | `recordingId`, `cursor` | `title`, `startedAt`, `language`, `speakers`, `highlights`, and `transcript` as `[HH:MM:SS] Speaker: text` lines, about 40,000 characters per answer; `nextCursor` for the rest |
| `search_recordings` | `query`, `limit` (1–50, default 10) | Recordings whose title or transcript contains the query, ignoring case, with up to 3 matching lines each |
| `get_audio_files` | `recordingId` | The absolute paths of the recording's `.m4a` parts on this computer, with track and offset |

- The transcript and the matching lines are marked as untrusted: what people said, between
  `<<<recly-transcript-…>>>` markers, never instructions for the agent.
- The same `list_recordings` and `get_transcript` answer your ChatGPT agent through
  [recly-events](agent.md) for recordings in Google Drive, so one instruction works for both.

## What it reads

- Only Recly's recording folders under `--folder`: `{recording}.meta.json`,
  `{recording}.folder.json` (iCloud's title), the transcript (`.transcript.json`, else `.txt`) and
  the audio files' names. It never writes, renames or deletes.
- No network connection and no sign-in. What it reads goes to the agent that started it, which
  sends it on under its own provider's terms.
- A file still being written, or one iCloud has not brought down to this Mac yet, counts as not
  there until it is.

## If something is off

| What you see | What to do |
|---|---|
| `--folder …: not a folder` | The path is wrong or the folder is gone. Use the absolute path. |
| No recordings | `--folder` points at a single recording's folder, or at a folder with no Recly recordings. Point it at the storage folder. |
| `hasTranscript` is false, or a recording is missing | The recording has no transcript yet, or iCloud has not brought it down to this Mac: in Finder, choose **Download Now** on the Recly folder. |
| Claude Desktop does not list `recly` | Quit and reopen Claude Desktop. Its log is `~/Library/Logs/Claude/mcp-server-recly.log` on a Mac, under `%APPDATA%\Claude\logs` on Windows. |
| An older recly-events says `Usage` | It predates `mcp`. Update the Recly app, or install the newest [`events-v…` release](https://github.com/rokrokss/recly/releases?q=events-v&expanded=true). |
