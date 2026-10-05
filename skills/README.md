# Recly skills for your agent

Recly's pipeline ends at the transcript on purpose. Turning it into notes is your own AI agent's
job, so this folder is a plugin of two example skills your agent can read. They are a starting
point: use them as they are, change them, or [write your own](#write-your-own). Google Drive stays
the archive the app writes and the agent only reads; in these examples the notes, and every later
edit, live in your Notion.

| Example skill | What it does |
|---|---|
| [`recly-notes`](recly-notes/SKILL.md) | Finds a recording (the latest, or the one you name), reads its transcript and writes minutes, a decision log, interview or lecture notes, or a memo |
| [`recly-notion`](recly-notion/SKILL.md) | Keeps those notes in a "Recly Recordings" database in your Notion, one page per recording, and finds them again later |

Five files make up the plugin: the two `SKILL.md` files and the three files under `references/`.
The same files serve every client below.

## Coding agents (Claude Code, Codex, Cursor)

```bash
npx skills add rokrokss/recly            # any agent that supports Agent Skills
# or, inside Claude Code:
/plugin marketplace add rokrokss/recly
/plugin install recly@recly
```

The plugin registers Notion's hosted MCP server; run `/mcp` once to sign in. Google Drive comes
from the connector you enable at claude.ai (Settings → Connectors), which Claude Code picks up
automatically. On the machine that transcribed the recording, the skill can also read the Recly
app's own local copy with no setup at all.

## Claude app (web, desktop, phone)

1. Connect Google Drive and Notion under Settings → Connectors.
2. Turn on "Code execution and file creation" under Capabilities.
3. Upload `recly-notes.zip` and `recly-notion.zip` from the latest
   [release](https://github.com/rokrokss/recly/releases/latest) under Customize → Skills. (Building
   them yourself: `make skills`; each ZIP's root is the skill folder itself.)

Do the setup once on the web; it follows your account to the phone.

## ChatGPT app (web, desktop, phone)

1. Connect Google Drive and Notion under Settings → Apps.
2. Create a project and upload the five files.
3. Put one line in the project instructions: *"Follow the attached recly-notes and recly-notion
   SKILL.md files."*

Chat inside that project.

## Then ask

*"Make minutes from the latest recording and put them in Notion."*
*"What did we decide about pricing last week?"*

## Start on its own

To have a ChatGPT dot or Work chat start as soon as a transcript lands, run
[recly-events](../events/README.md): it tells your agent about each new transcript, and its
subscription prompt can ask the agent to follow these skills for the recording it names.

## Write your own

The examples are a starting point. Want another format, another language, or notes in an app
other than Notion? Edit them, or write your own. That is the point.

- **What your skill reads.** Each recording is a folder in your Drive, like
  `recly/2026/2026-09/{base}/`. It holds the audio parts (`{base}_p001_mono.m4a`, ...),
  `{base}.meta.json` ([schema](../spec/recording.meta.schema.json)) and, once transcribed,
  `{base}.transcript.txt` (plain text) and `{base}.transcript.json` (segments with start, end and
  speaker; [schema](../spec/transcript.schema.json)).
- **What a skill is.** One Markdown file, `SKILL.md`, of instructions your agent follows. Copy
  `recly-notes/SKILL.md` into a folder of your own, keep its "Find the recording" part, and rewrite
  the rest.
- **Where the notes go.** Your agent needs a connector or MCP server for that app, the way the
  examples use Notion's.
