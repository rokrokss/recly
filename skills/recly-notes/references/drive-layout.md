# What Recly leaves in Google Drive, iCloud or a local folder

Contents: folder layout · names · `meta.json` · the transcript files · folder states · local copies
· iCloud · local folder.

## Folder layout

```
My Drive/
  recly/memo/2026-08/                      the device storage folder; may differ by settings
    20260826T010000Z_desktop_01J9ABCD/      one folder per recording = {base}
      20260826T010000Z_desktop_01J9ABCD_p001_mic.m4a     audio parts (you do not need them)
      20260826T010000Z_desktop_01J9ABCD_p001_sys.m4a
      20260826T010000Z_desktop_01J9ABCD.meta.json        uploaded last
      20260826T010000Z_desktop_01J9ABCD.transcript.txt   only when transcription is enabled
      20260826T010000Z_desktop_01J9ABCD.transcript.json
```

The default template is `recly/memo/{{yyyy}}-{{MM}}` (in the recording's own timezone).
Existing recordings may use folders from earlier settings or legacy workflows. Searching Drive by
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
| `drive.folderId`, `drive.folderUrl` | the recording's own Drive folder, written once the upload knew it; absent on recordings uploaded before this field existed and on iCloud recordings |
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
file. Legacy v1 transcripts without diarization may use `S1` for everything; this does not prove
a single participant. Local v2 with `speakerIdentification: unavailable` instead has `speakers: []`
and empty segment speakers. Its text lines are `[HH:MM:SS] text`. Never fabricate labels or owners.
The v2 `timing` field declares segment or word precision.

`{base}.transcript.json` — the machine copy with per-segment `start`/`end` seconds and, when the
provider gives them, word timings. Use it only if the `.txt` is missing or you need exact seconds.

Timestamps are on the recording's own time axis (seconds since `startedAt`).

## Folder states

| What you see | What it means | What to do |
|---|---|---|
| no `meta.json` | another device is still uploading (meta goes last) | do not use it; tell the user it is uploading |
| `meta.json` but no transcript, folder marker `pending` contains `transcribe` | transcription is running (up to a few hours for long recordings) | tell the user it is still transcribing |
| `meta.json` but no transcript, no `pending` | transcription is off, failed, or not yet published | say no transcript is available; do not infer why |
| transcript present | ready | read it |

The `pending` marker is a Drive folder property (`appProperties.pending`); in iCloud it is
`appProperties.pending` in `{base}.folder.json`. If your Drive tool cannot show it, treat "meta
present, transcript absent" as "not transcribed (yet)" and say so.

## Local copies on this device

The Recly desktop and phone apps keep transcripts beside the recording's local files:

- macOS: `~/Library/Application Support/app.recly.mac/recordings/`
- Windows: `%LOCALAPPDATA%\Recly\recordings\`

Two kinds of folder live there:

| Folder | What it holds |
|---|---|
| `{base}/` | a recording this device made and transcribed: `meta.json`, `{base}.transcript.json` and `.txt` |
| `{recordingId}/` (26-character ULID) | a recording from another device that the app has opened: `meta.json` and `{base}.transcript.json` only, fetched from Drive or iCloud and swept after 7 days |

A recording from another device the app has not opened yet is in Drive (or iCloud) only.

## iCloud (iPhone and Mac)

On iPhone and Mac the user can store new recordings in iCloud instead of Drive (Settings →
Storage); Android and Windows cannot use iCloud. An iCloud recording is in the app's iCloud Drive
folder, which Finder and the Files app show as **Recly**, in the same layout as Drive:

```
~/Library/Mobile Documents/iCloud~app~recly/Documents/   on a Mac; Finder: iCloud Drive → Recly
  recly/memo/2026-08/
    20260826T010000Z_desktop_01J9ABCD/
      ...                                                the same files as in Drive
      20260826T010000Z_desktop_01J9ABCD.folder.json      what Drive keeps on the folder itself
```

- `{base}.folder.json` holds what a Drive folder carries as its own properties: `description`
  (the title; as on Drive, it wins over `meta.json` `title` if the two differ), `appProperties`
  (`recordingId`, `workflowId`, the `pending` / `pendingAt` marker) and `createdTime`.
- `meta.json` has no `drive` field, so there is no folder link.
- iCloud syncs file by file and in any order, so a `meta.json` there does not prove the other files
  have arrived. Read the folder states above the same way, and trust a transcript once it is there.
- Find transcripts by searching the whole `recly/` tree for `*.transcript.txt` rather than building
  the month path: a month folder two devices made before they synced may show up twice
  (`2026-10 2`).
- Recordings are never moved, so a user who switched storage has recordings in both. When looking
  for the latest, check both if you can.
- Only a Mac can read the folder directly, from the path above. No AI assistant has an iCloud
  connector (Claude, ChatGPT and Gemini, checked 2026-10-02). On iPhone the user can attach a
  transcript from the Files app.

## Local folder (iPhone, Mac, Windows and Android)

On an iPhone, a Mac, a Windows PC or an Android phone the user can store new recordings in a folder they
picked on that device instead (Settings → Storage → Local folder) — often a notes vault such as
Obsidian's. Only the user knows where it is: ask, or look where they say. The layout is Drive's,
plus the transcript as Markdown:

```
<the folder the user picked>/
  recly/memo/2026-08/
    20260826T010000Z_desktop_01J9ABCD/
      ...                                                the same files as in Drive
      20260826T010000Z_desktop_01J9ABCD.transcript.md    front matter (title, recordingId, startedAt) + the .txt lines
```

- There is no folder-properties file and no `pending` marker: the folder is only ever written by
  the device that recorded, and the title is the one in `meta.json` (a rename rewrites it and the
  `.md`).
- `meta.json` has no `drive` field, so there is no folder link.
- Recordings are never moved, so a user who switched storage has recordings in both places.
