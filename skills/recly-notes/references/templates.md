# Note templates

Contents: shared rules · minutes · decision-log · interview · lecture · memo.

## Shared rules

- **Header** on every template: title (from `meta.json`, or "Untitled recording" + device), date
  and time in the recording's timezone, duration, participants (head count or speaker labels).
- Write in the transcript's language unless the user asks otherwise.
- Speakers are `S1`, `S2`, … unless the user has mapped them to names. Offer to map them once.
- Cite timestamps as `[HH:MM:SS]` wherever a reader might want to jump to the audio: every
  decision, every action item, and any disputed point.
- Do not retell the transcript. A reader who missed the recording should get what matters in one
  screen. Long recordings may need two; never more.
- Misheard names and terms stay as transcribed; flag them (`"Q3 리포트"(?)`) rather than guess.
- Gaps and silenced intervals from `meta.json` become one line ("audio missing 00:30:00–00:30:20")
  only if something was clearly lost.

## minutes (default)

For a meeting: several speakers, things settled, things owed.

1. Header
2. **Decisions** — one line each, what was settled and, if said, why. `[timestamp]`
3. **Action items** — `owner → task (due if said)`. Owners are speaker labels or mapped names.
   Unassigned tasks go in a separate line marked "unassigned".
4. **Discussion** — by topic, in the order they came up, two to four lines each. Include the
   argument, not just the conclusion, where people disagreed.
5. **Open questions** — anything raised and not resolved.

## decision-log

When the user wants only what was decided.

1. Header
2. One entry per decision:
   - **Decision** — one sentence.
   - **Context** — the problem in one or two lines.
   - **Options considered** — if alternatives were discussed.
   - **Why** — the reason given, in the speakers' terms.
   - **Owner / next step**, `[timestamp]`.

No discussion section. If nothing was decided, say so and list the open questions instead.

## interview

One person asks, one (or more) answers: user research, hiring, a podcast.

1. Header, plus who is the interviewer (usually the speaker who asks most questions).
2. **Summary** — three to five lines: who the interviewee is and the main takeaway.
3. **Themes** — grouped by topic, not by question order. Each theme: the point, then one or two
   short **verbatim quotes** with `[timestamp]`. Quotes are the value here; keep the speaker's
   words exactly.
4. **Notable moments** — surprises, strong emotions, contradictions.
5. **Follow-ups** — questions to ask next time, things promised.

## lecture

One speaker, long, explanatory: a class, a talk, a briefing.

1. Header
2. **One-paragraph summary** — the thesis.
3. **Outline** — the structure of the talk as headings with `[timestamp]`, each followed by the
   key points in the speaker's order. Preserve definitions and numbers exactly.
4. **Key terms** — term → definition as given.
5. **Questions asked** (if any audience questions) — question and answer, brief.

## memo

A short single-speaker note to self, a phone call, a voice memo.

1. Header (one line is enough).
2. **The point** — what the person wanted to remember, in two to five lines.
3. **To do** — checklist if any tasks were mentioned.
4. **Details** — names, numbers, addresses, dates mentioned, exactly as said.

If the memo is under a minute, skip the sections and write it as one short paragraph.
