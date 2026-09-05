# What Recly leaves in Google Drive

Contents: folder layout · names · `meta.json` · the transcript files · folder states · local copies.

## Folder layout

```
My Drive/
  recly/2026/2026-08/                       the workflow's folder template; may differ per workflow
    20260826T010000Z_desktop_01J9ABCD/      one folder per recording = {base}
      20260826T010000Z_desktop_01J9ABCD_p001_mic.m4a     audio parts (you do not need them)
      20260826T010000Z_desktop_01J9ABCD_p001_sys.m4a
      20260826T010000Z_desktop_01J9ABCD.meta.json        uploaded last
      20260826T010000Z_desktop_01J9ABCD.transcript.txt   only when the workflow transcribes
      20260826T010000Z_desktop_01J9ABCD.transcript.json
```

The default template is `recly/{yyyy}/{yyyy}-{MM}` (in the recording's own timezone), but a user
may have several workflows with different templates, e.g. `recly/memo/2026-08`. Searching Drive by
file name (`name contains '.transcript.txt'`) finds every transcript without walking the tree.

## Names

```
base = {yyyyMMdd}T{HHmmss}Z_{source}_{first 8 chars of recordingId}
```

- The time is `startedAt` in **UTC**, so sorting names descending sorts recordings newest first.
- `source` is `watch`, `phone`, or `desktop`.
- Names never contain the title or device name. The title lives in `meta.json` and in the Drive
  folder's `description` (the folder description wins if the two differ — it is updated on rename).

## `{base}.meta.json`

| Field | Meaning |
|---|---|
| `recordingId` | full ULID; the key you pass to `recly-notion` |
| `title` | optional; user-typed after stopping. Watch recordings have none unless added later |
| `startedAt`, `endedAt` | ISO-8601 UTC |
| `timezone` | IANA name; render times in it |
| `durationSec` | recording length |
| `source` / `platform` / `deviceName` | where it was recorded |
| `context.participants` | optional head count including the user (2–6, 6 means "6 or more") |
| `context.app` | optional bundle id of the meeting app (desktop only), e.g. `us.zoom.xos` |
| `drive.folderId`, `drive.folderUrl` | the recording's own Drive folder, written once the upload knew it; absent on recordings uploaded before this field existed |
| `gaps`, `silenced` | intervals with no audio (mic taken by another app, segment restart) — explains holes in the transcript |
| `status` | `recording` → `finalized` |

## The transcript files

`{base}.transcript.txt` — for people and agents. One line per turn; a new line when the speaker
changes or a segment passes 60 seconds:

```
[00:00:00] S1: 시작하겠습니다.
[00:00:03] S2: 네, 지난주 액션 아이템부터 볼까요.
```

Speakers are normalized to `S1`, `S2`, … in order of first appearance. Names are never in the
file. If the workflow ran without diarization, everything is `S1`.

`{base}.transcript.json` — the machine copy with per-segment `start`/`end` seconds and, when the
provider gives them, word timings. Use it only if the `.txt` is missing or you need exact seconds.

Timestamps are on the recording's own time axis (seconds since `startedAt`).

## Folder states

| What you see | What it means | What to do |
|---|---|---|
| no `meta.json` | another device is still uploading (meta goes last) | do not use it; tell the user it is uploading |
| `meta.json` but no transcript, folder marker `pending` contains `transcribe` | transcription is running (up to a few hours for long recordings) | tell the user it is still transcribing |
| `meta.json` but no transcript, no `pending` | the workflow has no `transcribe` step | say there is no transcript |
| transcript present | ready | read it |

The `pending` marker is a Drive folder property (`appProperties.pending`). If your Drive tool
cannot show it, treat "meta present, transcript absent" as "not transcribed (yet)" and say so.

## Local copies on this device

The Recly desktop and phone apps keep transcripts beside the recording's local files:

- macOS: `~/Library/Application Support/app.recly.mac/recordings/`
- Windows: `%LOCALAPPDATA%\Recly\recordings\`

Two kinds of folder live there:

| Folder | What it holds |
|---|---|
| `{base}/` | a recording this device made and transcribed: `meta.json`, `{base}.transcript.json` and `.txt` |
| `{recordingId}/` (26-character ULID) | a recording from another device that the app has opened: `meta.json` and `{base}.transcript.json` only, fetched from Drive and swept after 7 days |

A recording from another device the app has not opened yet is in Drive only.
