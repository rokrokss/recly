"""Unit tests of the notes-eval scorer, on canned answers: python3 -I scripts/notes-eval/test_notes_eval.py"""
import json
from pathlib import Path
import sys
import unittest

HERE = Path(__file__).resolve().parent
sys.dont_write_bytecode = True  # no __pycache__ beside the scripts
sys.path.insert(0, str(HERE))
import scoring  # noqa: E402

CASES = HERE / "cases"
CANNED = HERE / "canned"
NAMES = ("ko-launch", "en-trade-fair", "mixed-migration")
# What the app's Placeholders hide in each request; `score` uses the rendered request.json instead.
PLACEHOLDERS = {"ko-launch": {"[phone#1]": "010-4821-7395"}, "en-trade-fair": {"[email#1]": "orders@printhaus.de"},
                "mixed-migration": {}}
SEGMENTS = [{"start": 10.0, "end": 14.0, "speaker": "S1", "text": "We ship on the 25th, call me at 010-1234-5678."},
            {"start": 60.0, "end": 64.0, "speaker": "S2", "text": "Something else entirely."}]


def answer(*items, summary="A summary."):
    return json.dumps({"summary": summary, "items": list(items)})


def item(**fields):
    base = {"kind": "decision", "text": "Ship on the 25th.", "owner": None, "due": None, "at": "00:00:10",
            "quote": "We ship on the 25th"}
    base.update(fields)
    return base


def read(text, placeholders=None):
    return scoring.read_answer(text, placeholders or {}, SEGMENTS, {"S1", "S2"})


def grade(name, text, tolerance=5.0):
    case = scoring.load_case(CASES / name)
    return scoring.grade(case, scoring.read_answer(text, PLACEHOLDERS[name], case["segments"], case["speakers"]),
                         tolerance)


def canned(kind, name):
    return (CANNED / kind / f"{name}.txt").read_text()


class Reading(unittest.TestCase):
    """The app's reading of an answer (NotesAnswer, QuoteCheck, Notes.conversation)."""

    def test_the_object_inside_prose_and_a_fence_is_read(self):
        reading = read("Here you go:\n```json\n" + answer(item()) + "\n```\nDone.")
        self.assertTrue(reading["readable"])
        self.assertEqual(1, len(reading["items"]))

    def test_an_answer_without_an_object_is_unreadable(self):
        for text in ("no json here", "[1, 2]", "{not json}", '{"summary": NaN}'):
            self.assertEqual({"readable": False}, read(text), text)

    def test_kinds_are_read_leniently_and_unknown_ones_dropped(self):
        kinds = ["decision", " To-Do ", "action", "commitment", "question", "key point", "section1", "idea", 7]
        reading = read(answer(*[item(kind=k) for k in kinds]))
        self.assertEqual(["decision", "todo", "todo", "todo", "question", "point"], [i["kind"] for i in reading["items"]])
        self.assertEqual(len(kinds), reading["offered"])

    def test_an_item_needs_words_a_time_and_a_quote(self):
        broken = [item(text=""), item(text=None), item(text="[00:00:10]"), item(at="soon"), item(at="00:61:00"),
                  item(quote="  "), item(at=True), item(at=-3)]
        self.assertEqual([], read(answer(*broken))["items"])

    def test_times_are_read_as_the_app_reads_them(self):
        self.assertEqual(3723.0, scoring.seconds("01:02:03"))
        self.assertEqual(3723.0, scoring.seconds("[01:02:03]"))
        self.assertEqual(62.0, scoring.seconds("01:02"))
        self.assertEqual(62.5, scoring.seconds(62.5))
        self.assertEqual(62.0, scoring.seconds("at 01:02 or so"))
        self.assertIsNone(scoring.seconds("1:60"))
        self.assertIsNone(scoring.seconds(None))

    def test_at_null_does_not_fall_back_to_at_sec(self):
        self.assertEqual([], read(answer(item(at=None, atSec=10)))["items"])
        del_at = item(atSec=10)
        del del_at["at"]
        self.assertEqual(10.0, read(answer(del_at))["items"][0]["atSec"])

    def test_times_copied_into_the_words_are_dropped(self):
        reading = read(answer(item(text="[00:00:10] Ship on the 25th [00:00:12–00:00:14].\nFinal.")))
        self.assertEqual("Ship on the 25th. Final.", reading["items"][0]["text"])

    def test_owner_and_due_go_only_with_to_dos_and_owners_are_speakers(self):
        reading = read(answer(item(owner="S1", due="Friday"), item(kind="todo", owner="S2", due=" Friday "),
                              item(kind="todo", owner="Minji"), item(kind="todo", owner="S7", due="")))
        self.assertEqual([(None, None), ("S2", "Friday"), (None, None), (None, None)],
                         [(i["owner"], i["due"]) for i in reading["items"]])

    def test_placeholders_are_put_back(self):
        reading = read(answer(item(text="Call [phone#1] about [phone#2].", quote="call me at [phone#1]")),
                       {"[phone#1]": "010-1234-5678"})
        self.assertEqual("Call 010-1234-5678 about [phone#2].", reading["items"][0]["text"])
        self.assertTrue(reading["items"][0]["kept"])

    def test_the_quote_check_keeps_only_quotes_near_their_time(self):
        reading = read(answer(item(), item(quote="We ship next spring at the earliest"), item(at="00:00:59")))
        self.assertEqual([True, False, False], [i["kept"] for i in reading["items"]])


class Keys(unittest.TestCase):
    def test_a_number_key_does_not_match_inside_a_longer_number(self):
        self.assertTrue(scoring.has_any("11월 14일까지", ["14일"]))
        self.assertFalse(scoring.has_any("11월 14일까지", ["4일"]))
        self.assertFalse(scoring.has_any("print 1,500 copies", ["500"]))
        self.assertFalse(scoring.has_any("print 5,000 copies", ["500"]))
        self.assertTrue(scoring.has_any("print 500 copies", ["500"]))
        self.assertTrue(scoring.has_any("광고비 900만 원", ["900만"]))
        self.assertTrue(scoring.has_any("Booth in HALL 5.", ["hall5"]))


class Cases(unittest.TestCase):
    def test_every_case_loads_and_plants_every_trap(self):
        for name in NAMES:
            case = scoring.load_case(CASES / name)
            truths = [e for e in case["entries"] if e["truth"]]
            traps = {e["type"] for e in case["entries"] if not e["truth"]}
            self.assertTrue(any(e.get("old") for e in truths), f"{name}: no reversed decision")
            self.assertTrue(any(e.get("wrong") or e.get("wrongDue") for e in truths), f"{name}: no near-duplicate value")
            self.assertEqual({"hedge", "answered", "smalltalk"}, traps, name)
            self.assertEqual({"decision", "todo", "question"}, {e["kind"] for e in truths}, name)

    def test_the_placeholders_here_are_values_of_the_transcripts(self):
        for name in NAMES:
            text = " ".join(s["text"] for s in scoring.load_case(CASES / name)["segments"])
            for value in PLACEHOLDERS[name].values():
                self.assertIn(value, text)


class Scoring(unittest.TestCase):
    def counts(self, result):
        return {k: v for k, v in result["counts"].items() if v}

    def test_good_answers_score_every_item_correct(self):
        for name in NAMES:
            result = grade(name, canned("good", name))
            self.assertEqual({"correct": result["planted"]}, self.counts(result), name)
            self.assertEqual(result["time"]["checked"], result["time"]["within"], name)
            self.assertEqual(0, result["dropped"], name)
            self.assertEqual(1, result["points"], name)

    def test_bad_answers_show_every_kind_of_mistake(self):
        expected = {
            "ko-launch": {"correct": 2, "wrong": 3, "old": 2, "hedge": 1, "missing": 2, "fabricated": 2, "duplicate": 1},
            "en-trade-fair": {"correct": 3, "wrong": 2, "old": 1, "hedge": 1, "missing": 1, "fabricated": 1,
                              "duplicate": 1},
            "mixed-migration": {"correct": 3, "wrong": 2, "old": 1, "missing": 1, "fabricated": 1},
        }
        for name in NAMES:
            self.assertEqual(expected[name], self.counts(grade(name, canned("bad", name))), name)

    def test_bad_answers_are_judged_for_the_right_reasons(self):
        lines = {(line["entry"], line["category"]): line for line in grade("ko-launch", canned("bad", "ko-launch"))["items"]}
        self.assertIn(("launch-date", "old"), lines)
        self.assertEqual(["value"], lines[("ad-budget", "wrong")]["reasons"])
        self.assertEqual(["answered later"], lines[("who-sends-mail", "old")]["reasons"])
        self.assertEqual(["small talk"], lines[("weekend", "fabricated")]["reasons"])
        self.assertEqual(["not in the ground truth"], lines[(None, "fabricated")]["reasons"])
        self.assertIn(("dark-mode", "hedge"), lines)
        self.assertIn(("ios-review", "duplicate"), lines)
        reasons = {line["entry"]: line["reasons"] for line in grade("ko-launch", canned("bad", "ko-launch"))["items"]
                   if line["category"] == "wrong"}
        self.assertEqual({"ad-budget": ["value"], "qa-done": ["due"], "press-draft": ["owner S1"]}, reasons)
        result = grade("ko-launch", canned("bad", "ko-launch"))
        self.assertEqual(["channel-split", "call-payment-vendor"], result["missing"])
        self.assertEqual(2, result["dropped"])  # one without a time or quote, one quoting what nobody said
        mixed = {line["entry"]: line["reasons"] for line in grade("mixed-migration", canned("bad", "mixed-migration"))["items"]
                 if line["category"] == "wrong"}
        self.assertEqual({"lag-fix": ["owner None"], "tell-recruiter": ["due invented"]}, mixed)

    def test_evidence_times_are_checked_against_the_tolerance(self):
        strict = grade("ko-launch", canned("bad", "ko-launch"), tolerance=5.0)
        loose = grade("ko-launch", canned("bad", "ko-launch"), tolerance=10.0)
        self.assertEqual({"checked": 7, "within": 5}, strict["time"])  # launch-mail 7 s off, ad-budget 6 s off
        self.assertEqual({"checked": 7, "within": 7}, loose["time"])
        self.assertEqual({"checked": 6, "within": 4}, grade("en-trade-fair", canned("bad", "en-trade-fair"))["time"])

    def test_an_unreadable_answer_misses_everything(self):
        result = grade("en-trade-fair", "I could not find any decisions.")
        self.assertFalse(result["readable"])
        self.assertEqual({"missing": 6}, self.counts(result))

    def test_a_reading_that_differs_from_the_app_is_reported(self):
        ours = read(answer(item()))
        self.assertEqual([], scoring.same_reading(ours, json.loads(json.dumps(ours))))
        app = json.loads(json.dumps(ours))
        app["items"][0]["kept"] = False
        self.assertEqual(["item 1 kept: ours True, app False"], scoring.same_reading(ours, app))


if __name__ == "__main__":
    unittest.main()
