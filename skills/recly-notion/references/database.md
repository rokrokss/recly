# The "Recly Recordings" database

Contents: properties · creating the database · queries · page body layout.

## Properties

| Property | Type | Filled from |
|---|---|---|
| `Name` | title | `meta.title`; when absent, `{date} {source}` e.g. `2026-08-26 desktop` |
| `Date` | date (with time) | `startedAt` rendered in `timezone` |
| `Duration (min)` | number | `durationSec / 60`, rounded |
| `Participants` | rich_text | mapped names if known, else `context.participants` as a count, else the speaker labels seen |
| `Type` | select: `minutes`, `decision-log`, `interview`, `lecture`, `memo` | the template used |
| `Source` | select: `watch`, `phone`, `desktop` | `meta.source` |
| `Recording` | url | `meta.drive.folderUrl`; if the meta predates that field, the folder link from your Drive tool; otherwise leave empty |
| `Status` | select: `ready`, `transcribing` | `ready` once notes exist |
| `recordingId` | rich_text | `meta.recordingId`, the full ULID — the key; never omit it |

Keep property values short: Notion text properties are capped at 2,000 characters. The notes go
in the page body, not in a property.

## Creating the database

Ask where (a parent page) once, then create it with these property definitions — this is the
shape Notion's API and MCP take:

```json
{
  "Name":           { "title": {} },
  "Date":           { "date": {} },
  "Duration (min)": { "number": { "format": "number" } },
  "Participants":   { "rich_text": {} },
  "Type":           { "select": { "options": [
                      { "name": "minutes" }, { "name": "decision-log" }, { "name": "interview" },
                      { "name": "lecture" }, { "name": "memo" } ] } },
  "Source":         { "select": { "options": [
                      { "name": "watch" }, { "name": "phone" }, { "name": "desktop" } ] } },
  "Recording":      { "url": {} },
  "Status":         { "select": { "options": [ { "name": "ready" }, { "name": "transcribing" } ] } },
  "recordingId":    { "rich_text": {} }
}
```

Title the database **Recly Recordings**. Tell the user it was created and where.

## Queries

Find the page for a recording (before every write):

```json
{ "filter": { "property": "recordingId", "rich_text": { "equals": "01J9ABCDEF0123456789ABCDEF" } } }
```

Recent notes, newest first:

```json
{
  "filter": { "property": "Date", "date": { "on_or_after": "2026-08-25" } },
  "sorts": [ { "property": "Date", "direction": "descending" } ]
}
```

By participant: `{ "property": "Participants", "rich_text": { "contains": "Kim" } }`.

With Notion's hosted MCP these go through `notion-query-data-sources` in rows mode (`filter` and
`sort`), which works on every plan but shares a per-workspace allowance on Free and Plus. If a
filtered query is refused, fall back to `notion-search` on the id string.

## Page body layout

```
<header line: date · duration · participants · source>

<the notes, sections per template>

---
<anything the user adds by hand lives below this divider; never touch it>
```

When the user asks for the transcript, append a toggle block titled **Transcript** after the
divider region, at the very end of the page.
