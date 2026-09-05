---
name: recly-notion
description: Keep notes made from Recly recordings in the user's Notion database and read them back. Use when the user asks to save or upload notes to Notion, or to find, list, or re-read past Recly notes.
---

# recly-notion — Recly notes in Notion

Google Drive holds what the Recly app recorded; **Notion holds everything you make from it** —
the notes, and every later edit or addition. There is one Notion page per recording, keyed by the
recording's `recordingId`, so re-running never creates a duplicate.

## Tools

Use the Notion tools in your tool list. On Claude (Claude Code, claude.ai, the mobile apps) they
are the Notion connector's `Notion:notion-search`, `Notion:notion-fetch`,
`Notion:notion-query-data-sources`, `Notion:notion-create-pages`, `Notion:notion-update-page` and
`Notion:notion-create-database`. In ChatGPT they are the Notion app's actions.

If no Notion tool is available, say so and tell the user how to connect it, then stop:

- Claude Code: `claude mcp add --transport http notion https://mcp.notion.com/mcp`, then `/mcp` to
  sign in. (The `recly` plugin registers this server already; `/mcp` is all that is left.)
- claude.ai / Claude app: Settings → Connectors → Notion.
- ChatGPT: Settings → Apps → Notion (reconnect if it was connected before March 2026, older
  connections are read-only).

Do not fall back to Notion's REST API with a token.

## The database

- The convention is one database titled **"Recly Recordings"**. Search for it by title. Once
  found, remember its id for the rest of the session (and in project memory if you have one) so
  you do not search every time.
- If it does not exist, ask one question — where to create it — and create it with the schema in
  `references/database.md`.
- Before the first write in a session, fetch the database and read its actual property names.
  Users rename properties; map by meaning (the date property, the text property holding the
  recording id) instead of failing on an exact name.

## Publish notes (create or update)

Input, from the `recly-notes` skill or the user: the notes, and the recording's identity —
`recordingId`, `{base}`, title, `startedAt` + `timezone`, `durationSec`, `source`, participants,
template name.

1. **Look before you write.** Query the database for rows whose `recordingId` equals the id
   (rows-mode filter, see `references/database.md`).
2. **Say what you are about to do**, in one line: create or update, page title, database. Then
   write. Your tool may ask the user to approve each write; that is expected.
3. **Found → update that page.** Set any property that changed and replace the notes in the body.
   The body convention: your generated notes come first; anything the user added by hand sits
   below a `---` divider. Replace only what is above the divider, keep everything below it.
4. **Not found → create a page** in the database with the properties filled from the recording's
   identity and the notes as the body. Title: the recording's title, or `{date} {source}` when the
   recording has none.
5. **The transcript is not uploaded** unless the user asks. If asked, append it at the very end
   inside a toggle named "Transcript".
6. **If your tool cannot set database properties** (some ChatGPT setups), put
   `recordingId: {id}` as the first line of the body and search for that string before creating —
   the one-page-per-recording rule must still hold.
7. Reply with the page link.

Later changes the user asks for — "add the budget numbers", "mark item 2 done" — are edits to
that same page. Do not create a second page for the same recording.

## Read notes back

Reads need no confirmation.

- "Last week's meetings", "what did we record in August": query the database by the date
  property, newest first, and list title · date · type.
- "The meeting with S2 / with Kim": filter on the participants property or search the text.
- "What did we decide about X": search, then fetch the matching pages and answer with the page
  title and date as the citation.
- To go back to the source: the page's `recordingId` names the Drive folder (`{base}` starts
  with the start time and ends with the first 8 characters of the id). The `recly-notes` skill
  can re-read the transcript when the notes are not enough.
