---
name: recly-notes
description: Turn a Recly recording's transcript into meeting minutes saved beside the recording. Use when the user asks to summarize a Recly recording, meeting, or transcript — in their Google Drive `recly/` folder or a local copy.
---

# recly-notes — meeting minutes from a Recly transcript

Recly records a meeting, uploads it to the user's own Google Drive, and (when the workflow has a
`transcribe` step) leaves a diarized transcript next to the audio. Summarization is deliberately
not a pipeline step: it is your job, running under the user's existing agent subscription instead
of a metered API key. This skill turns one recording's transcript into minutes and puts them back
where the recording lives.

## Locate the recording

A recording is one folder, by default under `recly/` in the user's Drive (the workflow may name it
differently, e.g. `recly/2026-08-31 Weekly sync/`). It contains:

| File | What it is |
|---|---|
| `{base}_p001_mic.m4a` (and friends) | The audio parts. You do not need them. |
| `{base}.meta.json` | Title, start time, timezone, device, optional participant count. |
| `{base}.transcript.txt` | Human/LLM transcript: one line per turn, `[HH:MM:SS] S1: text`. **Read this one.** |
| `{base}.transcript.json` | Machine transcript with per-segment timing. Fallback only. |

Reach the folder however the user's setup allows: a Google Drive connector/MCP, a locally synced
Drive folder, or files the user hands you. If the user didn't say which recording, take the most
recent folder that has a transcript, and say which one you picked. If there is no transcript file,
stop and tell the user the recording ran without a `transcribe` step — don't guess at the audio.

## Write the minutes

Write in the transcript's own language, unless the user asks otherwise. Structure, in order:

1. **Header** — title, date/time (from `{base}.meta.json`, in its timezone), duration, participants.
2. **Decisions** — what was settled, one line each.
3. **Action items** — `owner → task (due)`. Use speaker labels (S1, S2…) as owners unless the
   user tells you real names; offer to map labels to names.
4. **Discussion** — the substance of what was talked through, by topic, brief. Cite timestamps
   like `[00:14:20]` for anything a reader may want to jump to.

Keep it minutes, not a transcript retelling: a reader who missed the meeting should know what was
decided, who owes what, and what was argued — in one screen if the meeting allows it.

## Save it back

Save as `{base}.summary.md` in the same folder as the transcript (same Drive folder when you read
from Drive, same directory when local). If a summary already exists, overwrite it — the newest run
is canonical. Show the user the minutes in your reply as well.
