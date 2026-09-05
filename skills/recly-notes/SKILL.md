---
name: recly-notes
description: Turn a Recly recording's transcript into minutes, a decision log, interview or lecture notes, or a memo. Use when the user mentions a Recly recording, the latest recording, or a transcript.
---

# recly-notes — notes from a Recly recording

Recly records on a watch, phone, or desktop and uploads the recording to the **user's own Google
Drive**. When the workflow has a `transcribe` step, a diarized transcript lands next to the audio.
Summarizing is deliberately not part of that pipeline — it is your job. This skill finds one
recording, reads its transcript, and writes the notes the user asked for.

Two rules frame everything below:

- **Drive is read-only for you.** It holds what the app recorded. Never write into the recording
  folder. The notes go in your reply and, when the user wants them kept, into Notion through the
  `recly-notion` skill.
- **"Recording" means the transcript.** Unless the user explicitly asks for audio, "get the
  recording" means read `{base}.transcript.txt`. You cannot listen to audio; if asked for it, give
  the file names or the Drive folder and stop.

## Find the recording

Try these in order and use the first that works. Always say which recording you picked (title,
start time, source device).

1. **The Recly app's local directory on this machine.** It needs no setup. macOS:
   `~/Library/Application Support/app.recly.mac/recordings/`, Windows:
   `%LOCALAPPDATA%\Recly\recordings\`. One folder per recording. Recordings this device
   transcribed have both transcript files; recordings from other devices that the app has opened
   are cached under a folder named by the full `recordingId` with only `{base}.transcript.json`.
2. **A Google Drive tool** (a connector or MCP server in your tool list). Search by name for
   `.transcript.txt` — the Drive query is `name contains '.transcript.txt'` — or browse the
   `recly/` folder tree. Read the transcript and the `{base}.meta.json` beside it.
3. **A Google Drive desktop sync folder**, if this machine has one:
   `~/Library/CloudStorage/GoogleDrive-*/My Drive/recly/` or `G:\My Drive\recly`.
4. **None of the above:** ask the user to attach or paste the transcript, and tell them in one
   line how to connect Google Drive to this assistant so it works next time.

Folder and file names, `meta.json` fields, and what an incomplete folder looks like are in
`references/drive-layout.md`.

## Which recording is "the latest"

- **Latest = greatest start time.** Every recording folder and file name begins with the start
  time in UTC (`20260826T010000Z_desktop_01J9ABCD`), so sorting names in descending order is
  chronological. You do not need to open `meta.json` to rank them.
- **A newer folder without a transcript is not "no recording".** A folder with no `meta.json` is
  still uploading; a folder whose Drive marker `pending` contains `transcribe` is still being
  transcribed. Use the newest folder that *has* a transcript, and tell the user a newer recording
  exists and is still uploading or transcribing. Never fall back silently.
- A recording whose workflow had no `transcribe` step has no transcript at all. Say so; do not
  guess at the audio.
- "Yesterday's 3 pm meeting" resolves through `meta.json` `startedAt` in the recording's own
  `timezone`.

## Read it

- `{base}.transcript.txt` is one turn per line: `[HH:MM:SS] S1: text`. Speakers are `S1`, `S2`, …
  in order of first appearance; there are no names.
- If only `{base}.transcript.json` is there, read its `segments` (`start` seconds, `speaker`,
  `text`) and treat them the same way; render a timestamp as `[HH:MM:SS]` from `start`.
- `{base}.meta.json` gives the title (may be absent), `startedAt`, `timezone`, `durationSec`,
  `source` (`watch`/`phone`/`desktop`), `deviceName`, `context.participants` (head count,
  optional), `context.app` (the meeting app on desktop, optional) and `drive.folderUrl` (the
  recording's Drive folder, optional).
- The transcript is speech-to-text output: names and technical terms may be misheard. Do not
  correct them silently — keep the spelling and, where it matters, flag it.

## Write the notes

Pick a template from `references/templates.md`:

| Template | Use when |
|---|---|
| **minutes** (default) | a meeting: several speakers, decisions, tasks |
| decision-log | the user wants only what was decided and why |
| interview | one person asks, another answers (user research, hiring, podcast) |
| lecture | one speaker, long, explanatory |
| memo | a short single-speaker note to self, a call, a voice memo |

If the user named a kind, use it. Otherwise infer from the transcript (speaker count, length,
question density, `context.app`) and say which template you chose. Write in the transcript's
language unless told otherwise. Use speaker labels as owners unless the user maps them to names;
offer to map them. Cite timestamps like `[00:14:20]` for anything a reader may want to jump to.
Keep it to one screen when the recording allows.

## Deliver

- Put the notes in your reply. Do not save them to a file anywhere — not in the recording folder,
  not in the working directory — unless the user asks for a file. The reply and Notion are the
  destinations.
- If the user wants them kept, or says "save", "upload", "Notion": hand off to the `recly-notion`
  skill with the notes and the recording's identity — `recordingId`, `{base}`, title,
  `startedAt` and `timezone`, `durationSec`, `source`, participants, `drive.folderUrl` when the
  meta has it, and the template name.
  `recordingId` is the key that keeps one page per recording; always carry it.
