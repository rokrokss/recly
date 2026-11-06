"""Reads a notes answer as the app does, then grades what it keeps against a case's ground truth.

The reading mirrors core/src/commonMain/kotlin/recly/core/notes: NotesAnswer (parse, items, summary, offered),
NoteText (normalize, chars, withoutTimes, ratio, partialRatio — themselves ports of Python's difflib), QuoteCheck,
Placeholders.restore, and Notes.conversation's filters (the quote check; an owner must be a speaker of the
transcript). `notes_eval.py render --answers DIR` makes the app's own code read the same answers, and
`notes_eval.py score` compares the two readings, so a change on either side shows.
"""
import difflib
import json
from pathlib import Path
import re
import unicodedata

KINDS = ("decision", "todo", "question")
TRAP_TYPES = ("hedge", "answered", "smalltalk")
CATEGORIES = ("correct", "wrong", "old", "hedge", "missing", "fabricated", "duplicate")

# NotesAnswer
TEXT_MAX = 500
DUE_MAX = 100
SUMMARY_MAX = 1200
# QuoteCheck
QUOTE_WINDOW_SEC = 10.0
QUOTE_THRESHOLD = 0.8

# Java's \d and \s are ASCII; Python's are not, so the classes are spelled out.
_WS = "[ \t\n\x0b\f\r]"
_OWNER = re.compile(r"S[0-9]{1,3}")
_CLOCK = re.compile(r"([0-9]{1,3}):([0-9]{2})(?::([0-9]{2}))?(?:\.[0-9]+)?")
_TIMES = re.compile(_WS + r"*\[[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?(?:" + _WS + r"*[,–-]" + _WS
                    + r"*[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?)*\]")
_PLACEHOLDER = re.compile(r"\[(?:email|phone|number)#[0-9]+]")
_NEWLINES = re.compile(r"\r\n|\n|\r")
_KIND = {"decision": "decision", "todo": "todo", "to-do": "todo", "commitment": "todo", "action": "todo",
         "question": "question", "point": "point", "key point": "point"}

# RecordingSearch.fold: the accented Latin letters of Latin-1 and Latin Extended-A as their base letter.
_BASE = {c: base for letters, base in (
    ("àáâãäåāăą", "a"), ("çćĉċč", "c"), ("ďđ", "d"), ("èéêëēĕėęě", "e"), ("ĝğġģ", "g"), ("ĥħ", "h"),
    ("ìíîïĩīĭįı", "i"), ("ĵ", "j"), ("ķ", "k"), ("ĺļľŀł", "l"), ("ñńņň", "n"), ("òóôõöøōŏő", "o"),
    ("ŕŗř", "r"), ("śŝşš", "s"), ("ţťŧ", "t"), ("ùúûüũūŭůűų", "u"), ("ŵ", "w"), ("ýÿŷ", "y"), ("źżž", "z"),
) for c in letters}


# ---------------------------------------------------------------- NoteText

def _fold(text):
    out = []
    for c in text:
        lower = c.lower()[:1] or c  # Kotlin's lowercaseChar maps one char to one char
        if "！" <= lower <= "～":
            out.append(chr(ord(lower) - 0xFEE0).lower())
        elif lower == "　":
            out.append(" ")
        else:
            out.append(_BASE.get(lower, lower))
    return "".join(out)


def _letter_or_digit(c):
    return unicodedata.category(c) in ("Lu", "Ll", "Lt", "Lm", "Lo", "Nd")


def normalize(text):
    """NoteText.normalize: folded, everything that is not a letter or a digit a space, spaces collapsed."""
    out, gap = [], False
    for c in _fold(text):
        if _letter_or_digit(c):
            if gap and out:
                out.append(" ")
            gap = False
            out.append(c)
        else:
            gap = True
    return "".join(out)


def chars(text):
    return normalize(text).replace(" ", "")


def without_times(text):
    return _TIMES.sub("", text).strip()


def ratio(a, b):
    return difflib.SequenceMatcher(None, a, b, autojunk=False).ratio()


def partial_ratio(needle, haystack):
    if not needle:
        return 0.0
    if len(haystack) <= len(needle):
        return ratio(needle, haystack)
    blocks = difflib.SequenceMatcher(None, needle, haystack, autojunk=False).get_matching_blocks()
    starts = {max(0, b.b - b.a) for b in blocks if b.size} or {0}
    best = 0.0
    for start in starts:
        at = min(start, len(haystack) - len(needle))
        best = max(best, ratio(needle, haystack[at:at + len(needle)]))
    return best


# ---------------------------------------------------------------- QuoteCheck

def quote_ratio(quote, at_sec, segments):
    low, high = at_sec - QUOTE_WINDOW_SEC, at_sec + QUOTE_WINDOW_SEC
    run_on = sum(1 for piece in re.split("[ \n\t]", quote) if piece.strip())
    pieces = []
    for segment in sorted(segments, key=lambda s: s["start"]):
        timed = segment.get("words") or []
        if timed:
            for word in timed:
                if word["end"] >= low and word["start"] <= high:
                    pieces.append(word["text"])
                elif word["start"] > high and pieces and run_on > 0:
                    pieces.append(word["text"])
                    run_on -= 1
        elif segment["end"] >= low and segment["start"] <= high:
            pieces.append(segment["text"])
    return partial_ratio(chars(quote), chars(" ".join(pieces)))


def quote_passes(quote, at_sec, segments):
    return bool(quote.strip()) and quote_ratio(quote, at_sec, segments) >= QUOTE_THRESHOLD


# ---------------------------------------------------------------- NotesAnswer

class Unreadable(ValueError):
    """An answer with no JSON object in it: the app counts the request as failed."""


def _reject(constant):
    raise ValueError(constant)  # kotlinx.serialization reads no NaN or Infinity


def parse(text):
    start, end = text.find("{"), text.rfind("}")
    if 0 <= start < end:
        try:
            found = json.loads(text[start:end + 1], parse_constant=_reject)
        except ValueError:
            found = None
        if isinstance(found, dict):
            return found
    raise Unreadable("unreadable answer")


def _string(value):
    return value if isinstance(value, str) else None


def restore(text, placeholders):
    return _PLACEHOLDER.sub(lambda m: placeholders.get(m.group(0), m.group(0)), text)


def seconds(value):
    """`HH:MM:SS`, `[HH:MM:SS]` or `MM:SS` — minutes and seconds up to 59 — or a number of seconds."""
    if isinstance(value, bool) or value is None or isinstance(value, (list, dict)):
        return None
    if isinstance(value, (int, float)):
        value = float(value)
        return value if value >= 0 and value != float("inf") else None
    match = _CLOCK.search(value)
    if not match:
        return None
    a, b, c = match.group(1), match.group(2), match.group(3) or ""
    if int(b) > 59 or (c and int(c) > 59):
        return None
    return float(int(a) * 60 + int(b)) if not c else float(int(a) * 3600 + int(b) * 60 + int(c))


def items(found, placeholders, points=True):
    """NotesAnswer.items with Settings → Notes as they come: the four built-in groups, no section of the user's own."""
    out = []
    for element in found.get("items") if isinstance(found.get("items"), list) else []:
        if not isinstance(element, dict):
            continue
        said = (_string(element.get("kind")) or "").strip().lower()
        kind = _KIND.get(said)
        if kind is None or (kind == "point" and not points):
            continue
        text = _string(element.get("text"))
        if text is None:
            continue
        text = without_times(" ".join(_NEWLINES.split(restore(text, placeholders).strip())))[:TEXT_MAX]
        if not text:
            continue
        at_sec = seconds(element["at"] if "at" in element else element.get("atSec"))
        quote = restore(_string(element.get("quote")) or "", placeholders).strip() or None
        # Both or neither, and neither only where key points are asked: the final pass.
        if (at_sec is None or quote is None) and not (points and at_sec is None and quote is None):
            continue
        owner = (_string(element.get("owner")) or "").strip()
        owner = owner if kind == "todo" and _OWNER.fullmatch(owner) else None
        due = restore(_string(element.get("due")) or "", placeholders).strip()
        due = due[:DUE_MAX] if due and kind == "todo" else None
        out.append({"kind": kind, "text": text, "owner": owner, "due": due, "atSec": at_sec, "quote": quote})
    return out


def read_answer(text, placeholders, segments, speakers):
    """What Notes.conversation keeps of one answer, in the shape the app's reading is written in (app-items-*.json)."""
    try:
        found = parse(text)
    except Unreadable:
        return {"readable": False}
    summary = restore(_string(found.get("summary")) or "", placeholders).strip()[:SUMMARY_MAX] or None
    kept = []
    for item in items(found, placeholders):
        item["owner"] = item["owner"] if item["owner"] in speakers else None
        item["kept"] = item["quote"] is None or quote_passes(item["quote"], item["atSec"], segments)
        kept.append(item)
    offered = len(found["items"]) if isinstance(found.get("items"), list) else 0
    return {"readable": True, "summary": summary, "offered": offered, "items": kept}


def same_reading(ours, app):
    """The differences between this reading and the app's, as lines; empty when they agree."""
    differences = []
    for key in ("readable", "summary", "offered"):
        if ours.get(key) != app.get(key):
            differences.append(f"{key}: ours {ours.get(key)!r}, app {app.get(key)!r}")
    mine, theirs = ours.get("items", []), app.get("items", [])
    if len(mine) != len(theirs):
        differences.append(f"items: ours {len(mine)}, app {len(theirs)}")
    for n, (a, b) in enumerate(zip(mine, theirs)):
        for key in ("kind", "text", "owner", "due", "atSec", "quote", "kept"):
            if a.get(key) != b.get(key):
                differences.append(f"item {n + 1} {key}: ours {a.get(key)!r}, app {b.get(key)!r}")
    return differences


# ---------------------------------------------------------------- cases

def clock(text):
    h, m, s = (int(x) for x in text.split(":"))
    return float(h * 3600 + m * 60 + s)


def load_case(folder):
    """A case folder's transcript and ground truth, checked; raises ValueError on a mistake in the case."""
    folder = Path(folder)
    transcript = json.loads((folder / "transcript.json").read_text())
    truth = json.loads((folder / "truth.json").read_text())
    segments = transcript["segments"]
    lines = {}
    for s in segments:
        if s["end"] - s["start"] > 20.0 or s["start"] != int(s["start"]):
            # NoteLines splits a longer one; a whole-second start is the time the line shows.
            raise ValueError(f"{folder.name}: segment at {s['start']} must start on a second and last at most 20 s")
        lines[s["start"]] = s["text"]
    speakers = {s["id"] for s in transcript["speakers"]}
    entries, ids = [], set()
    for raw in truth["items"]:
        kind = raw["kind"]
        entry = dict(raw, truth=True, kinds=raw.get("kinds", [kind]),
                     times=[clock(t) for t in raw["at"]], oldTimes=[clock(t) for t in raw.get("oldAt", [])])
        if kind not in KINDS or any(k not in KINDS for k in entry["kinds"]):
            raise ValueError(f"{folder.name}/{raw['id']}: unknown kind")
        if raw.get("owner") and raw["owner"] not in speakers:
            raise ValueError(f"{folder.name}/{raw['id']}: owner {raw['owner']} is not a speaker")
        entries.append(entry)
    for raw in truth["traps"]:
        if raw["type"] not in TRAP_TYPES:
            raise ValueError(f"{folder.name}/{raw['id']}: unknown trap type {raw['type']}")
        kinds = {"hedge": ["decision", "todo"], "answered": ["question"], "smalltalk": list(KINDS) + ["point"]}
        entries.append(dict(raw, truth=False, kinds=kinds[raw["type"]], times=[clock(t) for t in raw["at"]], oldTimes=[]))
    for entry in entries:
        if entry["id"] in ids:
            raise ValueError(f"{folder.name}: id {entry['id']} twice")
        ids.add(entry["id"])
        for t in entry["times"] + entry["oldTimes"]:
            if t not in lines:
                raise ValueError(f"{folder.name}/{entry['id']}: no line starts at {t:.0f} s")
    memo_file = folder / "memo.txt"
    memo = memo_file.read_text().strip() if memo_file.is_file() else ""
    memo_only = truth.get("memoOnly", [])
    for fact in memo_only:
        if not _topic(memo, fact["topic"]) or _topic(" ".join(lines.values()), fact["topic"]):
            raise ValueError(f"{folder.name}/{fact['id']}: a memo-only fact must be in the memo and not in the transcript")
    return {"name": folder.name, "transcript": transcript, "segments": segments, "lines": lines,
            "speakers": speakers, "entries": entries, "appLocale": truth["appLocale"], "memo": memo, "memoOnly": memo_only}


# ---------------------------------------------------------------- grading

def _keys(text):
    return chars(unicodedata.normalize("NFKC", text))


def has_any(text, keys):
    """Whether one of [keys] is in [text], both compared without case, spaces or punctuation. A key that starts or
    ends with a digit does not match inside a longer number: `4일` is not in `14일`, `500` not in `1,500`."""
    haystack = _keys(text)
    for key in keys:
        needle = _keys(key)
        at = haystack.find(needle) if needle else -1
        while at >= 0:
            before = haystack[at - 1] if at else ""
            after = haystack[at + len(needle)] if at + len(needle) < len(haystack) else ""
            if not (needle[0].isdecimal() and before.isdecimal()) and not (needle[-1].isdecimal() and after.isdecimal()):
                return True
            at = haystack.find(needle, at + 1)
    return False


def _topic(text, topic):
    return all(has_any(text, group) for group in topic)


def _value(text, value, old, wrong, required):
    """ok, old, wrong or missing: what [text] says of a value the ground truth names."""
    if value and has_any(text, value):
        return "ok"
    if old and has_any(text, old):
        return "old"
    if wrong and has_any(text, wrong):
        return "wrong"
    return "missing" if value and required else "ok"


def identify(item, case):
    """The entry of the ground truth [item] is about, or None: one whose evidence line the quote is from, or whose
    topic its words name; ties go to an entry of the item's kind, then to the ground truth over a trap, then to the
    nearest evidence."""
    best = None
    for entry in case["entries"]:
        if item["kind"] == "point" and entry.get("type") != "smalltalk":
            continue
        times = entry["times"] + entry["oldTimes"]
        quoted = item["quote"] is not None and any(
            partial_ratio(chars(item["quote"]), chars(case["lines"][t])) >= QUOTE_THRESHOLD for t in times)
        named = _topic(item["text"], entry["topic"])
        if not (quoted or named):
            continue
        rank = (2 * (item["kind"] in entry["kinds"]) + 2 * quoted + named, entry["truth"],
                0 if item["atSec"] is None else -min(abs(item["atSec"] - t) for t in times))
        if best is None or rank > best[0]:
            best = (rank, entry)
    return best[1] if best else None


def judge(item, entry):
    """(category, reasons) of an item about [entry]."""
    if entry is None:
        return ("fabricated", ["not in the ground truth"]) if item["kind"] != "point" else ("point", [])
    if not entry["truth"]:
        trap = entry["type"]
        if trap == "smalltalk":
            return "fabricated", ["small talk"]
        if trap == "hedge":
            return ("hedge", []) if item["kind"] in ("decision", "todo") else ("fabricated", ["hedge as a question"])
        return ("old", ["answered later"]) if item["kind"] == "question" else ("fabricated", ["answered question"])
    reasons, old = [], False
    if item["kind"] not in entry["kinds"]:
        reasons.append(f"kind {item['kind']}")
    state = _value(item["text"], entry.get("value"), entry.get("old"), entry.get("wrong"), entry.get("valueRequired", True))
    if state == "old":
        old = True
    elif state != "ok":
        reasons.append("value" if state == "wrong" else "no value")
    if item["kind"] == "todo" and "todo" in entry["kinds"]:
        if entry.get("owner") and item["owner"] != entry["owner"]:
            reasons.append(f"owner {item['owner']}")
        if "due" in entry and entry["due"] is None:
            if item["due"]:
                reasons.append("due invented")
        elif entry.get("due"):
            state = _value(item["due"] or "", entry["due"], entry.get("oldDue"), entry.get("wrongDue"), True)
            if state == "old":
                old = True
            elif state != "ok":
                reasons.append("due" if state == "wrong" else "no due")
    if old:
        return "old", reasons
    return ("wrong", reasons) if reasons else ("correct", [])


def memo_places(case, reading):
    """Where each fact only the memo has landed: `row`, an item with no time, which is where it belongs; `timed`, an
    item held to a moment that does not say it; `dropped`, an item whose quote failed, which the app does not keep;
    `overview`. No place at all means the answer left it out."""
    places = {}
    for fact in case["memoOnly"]:
        found = ["dropped" if not item["kept"] else "row" if item["atSec"] is None else "timed"
                 for item in reading["items"] if _topic(item["text"], fact["topic"])]
        if reading["summary"] and _topic(reading["summary"], fact["topic"]):
            found.append("overview")
        places[fact["id"]] = found
    return places


def grade(case, reading, tolerance):
    """The verdict on one answer: each kept item's category, the truths it missed, the evidence times."""
    result = {"case": case["name"], "readable": reading.get("readable", False),
              "planted": sum(1 for e in case["entries"] if e["truth"]),
              "counts": dict.fromkeys(CATEGORIES, 0), "time": {"checked": 0, "within": 0},
              "offered": reading.get("offered", 0), "dropped": 0, "points": 0, "withoutTime": 0, "memo": {}, "items": [], "missing": []}
    if not result["readable"]:
        result["missing"] = [e["id"] for e in case["entries"] if e["truth"]]
        result["counts"]["missing"] = len(result["missing"])
        return result
    kept = [item for item in reading["items"] if item["kept"]]
    result["dropped"] = reading["offered"] - len(kept)
    result["withoutTime"] = sum(item["atSec"] is None for item in kept)
    result["memo"] = memo_places(case, reading)
    answered = set()
    for item in kept:
        entry = identify(item, case)
        if entry is None and any(_topic(item["text"], fact["topic"]) for fact in case["memoOnly"]):
            result["items"].append({"category": "memo", "kind": item["kind"], "atSec": item["atSec"], "text": item["text"],
                                    "owner": item["owner"], "due": item["due"], "entry": None, "reasons": []})
            continue
        category, reasons = judge(item, entry)
        line = {"category": category, "kind": item["kind"], "atSec": item["atSec"], "text": item["text"],
                "owner": item["owner"], "due": item["due"], "entry": entry["id"] if entry else None, "reasons": reasons}
        if entry is not None and entry["truth"]:
            if category == "correct" and entry["id"] in answered:
                line["category"] = category = "duplicate"
            if category == "correct":
                answered.add(entry["id"])
            times = entry["times"] + (entry["oldTimes"] if category == "old" else [])
            # Every planted item was said at a moment, so one written without a time misses it.
            line["timeOff"] = None if item["atSec"] is None else min(abs(item["atSec"] - t) for t in times)
            result["time"]["checked"] += 1
            result["time"]["within"] += line["timeOff"] is not None and line["timeOff"] <= tolerance
        if category == "point":
            result["points"] += 1
        else:
            result["counts"][category] += 1
        result["items"].append(line)
    touched = {line["entry"] for line in result["items"] if line["category"] in ("correct", "wrong", "old", "duplicate")}
    result["missing"] = [e["id"] for e in case["entries"] if e["truth"] and e["id"] not in touched]
    result["counts"]["missing"] = len(result["missing"])
    return result
