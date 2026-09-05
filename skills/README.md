# Recly skills for your agent

Recly's pipeline ends at the transcript on purpose. Turning it into notes is your own AI agent's
job, so this folder is a plugin of two skills your agent can read. Google Drive stays the archive
the app writes and the agent only reads; the notes, and every later edit, live in your Notion.

| Skill | What it does |
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

Don't like the format? Edit the skill file. That is the point.
