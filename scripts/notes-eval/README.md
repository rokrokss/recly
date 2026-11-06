# Notes extraction bench

Scores the summary items the apps ask ChatGPT for (docs/recly.md §8 "Organizing") — decisions, to-dos with owner
and due date, open questions — on four synthetic meetings, one with a memo, so a change to the prompt or the model can be measured.
A developer tool: nothing here ships in the apps.

```bash
python3 -I scripts/notes-eval/test_notes_eval.py
python3 -I scripts/notes-eval/notes_eval.py render
python3 -I scripts/notes-eval/notes_eval.py run
python3 -I scripts/notes-eval/notes_eval.py run --send --model MODEL --run-id first
python3 -I scripts/notes-eval/notes_eval.py score --answers build/notes-eval/runs/first
```

| Command | What it does | Sends anything |
|---|---|---|
| `test_notes_eval.py` | scorer unit tests on the canned answers | no |
| `render [--answers DIR]` | builds each case's request with the app's code (`./gradlew :core:notesEvalRender`) into `build/notes-eval/requests/<case>/` | no |
| `run` | dry run: how many calls a real run makes, the codex command, where answers go | no |
| `run --send --model M [--effort E] [--run-id ID] [--case C]` | one `codex exec` call per case — **4 calls**, or 1 with `--case` — on the signed-in ChatGPT plan; answers to `build/notes-eval/runs/<id>/<case>.txt` | **yes**: the synthetic transcripts go to OpenAI |
| `score --answers DIR [--tolerance S] [--verbose]` | reads `DIR/<case>.txt` as the app does and grades it; JSON to `build/notes-eval/scores/` | no |

`render` runs Gradle; where builds are serialized, run it under the build lock like any other Gradle command. After
a change to `NotesPrompts.kt`, `Notes.kt` or a case, render again: `run` and `score` refuse requests rendered from
an older transcript. Score the canned answers to see the report without a real run:

```bash
python3 -I scripts/notes-eval/notes_eval.py render --answers scripts/notes-eval/canned/bad
python3 -I scripts/notes-eval/notes_eval.py score --answers scripts/notes-eval/canned/bad
```

## Requests from the app's code

`core/src/jvmTest/kotlin/recly/core/notes/NotesEval.kt` is a `main()` in the core's JVM test sources, run by the
`notesEvalRender` task in `core/build.gradle.kts`. `make test` compiles it and never runs it. For each case it does
what `Notes.finalPass` and `Notes.conversation` do for a recording of one conversation: one block
(`ConversationSplit`), so no boundaries or overview request; at least `Notes.MIN_WORDS`; the lines from
`NoteLines`; the input from `NotesPrompts.transcriptInput` with numbers and addresses hidden by `Placeholders`; the
instructions from `NotesPrompts.conversation` in the language `SummaryLanguage.of(transcript language, appLocale)`
names; Settings → Notes as they come, and the case's `memo.txt` as the memo where it has one. It writes
`instructions.txt`, `input.txt` and `request.json` (language, hidden values, the memo, transcript hash). No prompt text is copied anywhere else.

With `--answers DIR` it also reads `DIR/<case>.txt` with `NotesAnswer` and `QuoteCheck`, as the final pass does,
into `app-items-<answer hash>.json`. `score` reads the same answer with its Python port (`scoring.py`) and stops
with the differences when the two readings disagree, so the port cannot drift from the app unnoticed.

## The runner

`codex exec` with the app's instructions in place of codex's own (`-c model_instructions_file=…`), the input on
stdin, from an empty folder outside the repository, without the user's config, rules, project docs, tools or web
search. No `--output-schema`: the app asks for JSON in words and reads the answer leniently, and the bench measures
that. The app sends no reasoning effort for notes; `--effort` passes one, otherwise codex uses its default for the
model. `--model` is required with `--send`: the app uses the model Settings shows.

Codex still adds messages of its own that the app's request does not have — its skills list and agent role
(`codex debug prompt-input` with the same `-c` flags shows them) — so absolute numbers are codex's, and the bench is
for comparing one prompt or model with another under the same runner.

## Cases

`cases/<case>/transcript.json` is a transcript in Recly's format (`spec/transcript.schema.json`);
`cases/<case>/truth.json` its ground truth. Each case is one conversation of about three minutes: one call.

| Case | Language | Planted | Traps |
|---|---|---|---|
| `ko-launch` | `ko`, app `ko-KR` | 2 decisions, 5 to-dos, 1 question | launch date 18th → 25th; dates 19th/21st/25th; ad budget 12M asked vs 9M approved; dark mode floated; "who sends the mail?" answered; weekend and lunch talk; a phone number |
| `en-trade-fair` | `en`, app `en-US` | 3 decisions, 2 to-dos, 1 question | Hall 3 → Hall 5; 14,500/11,800/4,350 euros; 12th/14th/21st; 5,000 and 800 vs 500 brochures; reception floated; "who staffs day one?" answered; sports and coffee talk; an e-mail address |
| `mixed-migration` | `ko-en`, app `ko-KR` | 2 decisions, 4 to-dos, 1 question | cutover 8th → 15th; 12th/14th/15th; offer cap 1억 2천 vs 1억 5천; referral bonus floated; "who is on call?" answered; a photo promise and lunch talk |
| `ko-memo-pilot` | `ko`, app `ko-KR` | 2 decisions, 3 to-dos, 1 question | 50 → 20 seats; three months vs two; a video floated; "who takes questions?" answered; lunch talk; **a memo** with one fact also said and three that were not (a budget approved after the meeting, a contact's leave, a competitor's bid) |

Ground truth fields: `items[]` with `id`, `kind` (`decision`, `todo`, `question`), `kinds` (other kinds that are
also right), `topic` (keyword groups: every group must have one of its keywords in the item's words), `value`
(what a right item says), `valueRequired` (default true), `old` (the value before a reversal), `wrong` (a value said
nearby that is not this one), `owner`, `due` (`null`: none was said), `oldDue`, `wrongDue`, `at` and `oldAt`
(evidence lines, `HH:MM:SS`). `traps[]` with `type` `hedge`, `answered` or `smalltalk`, a `topic` and `at`.
`memoOnly[]` with `id` and `topic`: a fact in `memo.txt` that no line says, checked when the case loads.
Keywords compare without case, spaces or punctuation, and a number never matches inside a longer one (`4일` is not
in `14일`).

## Scoring

The scorer keeps what the app keeps: the JSON object in the answer, well-formed items of the kinds asked, a quote
found near its time, an owner only when it is a speaker. Key points are counted, not scored. Each kept item is tied
to the entry whose evidence line it quotes or whose topic its words name (an entry of the item's kind first, then
the ground truth over a trap, then the nearest evidence), and counted as:

| Category | When |
|---|---|
| correct | a planted item, of an accepted kind, with its value, owner and due date |
| wrong | a planted item with the wrong kind, value, owner or due date, or a due date nobody said |
| old | the value from before a reversal, or a question that was answered later in the meeting |
| hedge | something only floated ("maybe we could…"), given as a decision or to-do |
| fabricated | small talk, or anything not in the ground truth |
| duplicate | a planted item already found once |
| missing | a planted item no kept item is about |

`time ok` counts the planted items whose evidence time is within `--tolerance` seconds (default 5) of one of the
entry's lines; every planted item was said at a moment, so one written without a time counts against it. `dropped`
counts what the app would not keep. `no time` counts the items kept with neither a time nor a quote: no check guards
them, so they are always listed, and in a case with no memo only something that runs through the whole meeting
should be one. `memo only` says where each `memoOnly` fact landed: `row`, an item with no time, where it belongs;
`timed`, an item held to a moment that does not say it; `dropped`, an item whose quote failed; `overview`; or `left
out`. An item about such a fact is listed as `memo` and is not fabricated. The matching is by keywords: read the listed items
(`--verbose` lists the correct ones too) before trusting a number.

Ideas from the unmerged long-recording harness (commit `a48479ca`, `scripts/long-recording-eval`: the codex flags,
the dry run that refuses to send) and from OpenGlasses' timeline-recall bench (scoring as correct, wrong, old value
or hedge). No code from either.
