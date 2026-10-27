package recly.core.notes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import recly.core.transcribe.TranscriptSegment
import recly.core.transcribe.TranscriptWord

/** docs/08 "Organizing": the text rules, as the long-recording evaluation measured them. */
class NoteTextTest {
    @Test
    fun `ratios are difflib's`() {
        // Values from Python 3: difflib.SequenceMatcher(None, a, b, autojunk=False).ratio().
        assertEquals(0.75, NoteText.ratio("abcd", "bcde"))
        assertEquals(1.0, NoteText.ratio("", ""))
        assertEquals(0.0, NoteText.ratio("abc", "xyz"))
        assertEquals(0.6, NoteText.ratio("private Thread", "Thread"), 1e-9)
        assertEquals(1.0, NoteText.partialRatio("abc", "xxabcxx"))
        assertEquals(0.0, NoteText.partialRatio("", "abc"))
        assertEquals(0.9, NoteText.partialRatio("helloworld", "xxhelloworlxx"), 1e-9)
    }

    @Test
    fun `text is folded to letters and digits`() {
        assertEquals("we ship on friday", NoteText.normalize("We ship — on FRIDAY!"))
        assertEquals("weshiponfriday", NoteText.chars("We ship — on FRIDAY!"))
        assertEquals("abc 12", NoteText.normalize("ＡＢＣ　１２"))
        assertEquals("금요일에출시합니다", NoteText.chars("금요일에 출시합니다."))
        assertEquals(4, NoteText.words("we ship on friday"))
        assertEquals(4, NoteText.words("金曜日に出荷します。"), "a run without spaces counts one word per two letters")
    }

    private fun words(start: Double, text: String, step: Double = 0.5): List<TranscriptWord> =
        text.split(' ').mapIndexed { index, word -> TranscriptWord(start + index * step, start + index * step + 0.4, word) }

    @Test
    fun `a quote passes only near its time and only as said`() {
        val timed = listOf(
            TranscriptSegment(100.0, 106.0, "S1", "we will ship the beta on friday after the review", words(100.0, "we will ship the beta on friday after the review")),
            TranscriptSegment(130.0, 135.0, "S2", "fine by me", words(130.0, "fine by me")),
        )
        assertTrue(QuoteCheck.passes("ship the beta on Friday", 101.0, timed))
        assertTrue(QuoteCheck.passes("ship the beta on friday", 110.0, timed), "within ten seconds")
        assertFalse(QuoteCheck.passes("ship the beta on friday", 125.0, timed), "too far from where it was said")
        assertFalse(QuoteCheck.passes("we decided to cancel the launch", 101.0, timed), "not what was said")
        assertFalse(QuoteCheck.passes("", 101.0, timed))
        // A quote starting at the window's edge runs on past it by its own length.
        assertTrue(QuoteCheck.passes("on friday after the review", 92.0, timed))

        val untimed = listOf(TranscriptSegment(200.0, 230.0, "", "금요일에 베타를 출시하기로 했습니다"))
        assertTrue(QuoteCheck.passes("베타를 출시하기로 했습니다", 215.0, untimed), "a segment without word times counts whole")
        assertFalse(QuoteCheck.passes("베타를 출시하기로 했습니다", 250.0, untimed))
        assertTrue(QuoteCheck.ratio("베타를 출시하기로 햇습니다", 215.0, untimed) >= QuoteCheck.THRESHOLD, "a slip of one letter is still the quote")
    }

    @Test
    fun `numbers and addresses leave as placeholders and come back`() {
        val placeholders = Placeholders()
        val hidden = placeholders.hide(
            "[00:01:02] S1: call me on 010-1234-5678 or +82 10 9876 5432, mail mina.kim@example.com, account 123456789012\n" +
                "[00:01:20] S2: again 010-1234-5678, room 301, 2026 budget",
        )
        assertFalse("1234-5678" in hidden)
        assertFalse("example.com" in hidden)
        assertFalse("9012" in hidden)
        assertTrue("[phone#1]" in hidden && "[phone#2]" in hidden && "[email#1]" in hidden && "[number#1]" in hidden, hidden)
        assertEquals(2, Regex("""\[phone#1]""").findAll(hidden).count(), "the same number, the same placeholder")
        assertTrue("[00:01:02]" in hidden && "room 301" in hidden && "2026 budget" in hidden, "times and short numbers stay")
        assertEquals(
            "Call Mina on 010-1234-5678 and write to mina.kim@example.com [link#1]",
            placeholders.restore("Call Mina on [phone#1] and write to [email#1] [link#1]"),
        )
        assertEquals(4, placeholders.hidden)
    }

    @Test
    fun `items are the same by kind and words, or kind and moment`() {
        assertTrue(SameItem.matches(NoteKind.TODO, "Send the slides to Mina", 100.0, NoteKind.TODO, "send slides to Mina.", 400.0))
        assertTrue(SameItem.matches(NoteKind.TODO, "Book a room", 100.0, NoteKind.TODO, "Order lunch", 110.0), "the same moment")
        assertFalse(SameItem.matches(NoteKind.TODO, "Book a room", 100.0, NoteKind.DECISION, "Book a room", 100.0))
        assertFalse(SameItem.matches(NoteKind.TODO, "Book a room", 100.0, NoteKind.TODO, "Order lunch", 120.0))
    }

    @Test
    fun `silences of more than ten minutes cut a recording, and a long block is cut where it is told`() {
        fun at(start: Double, end: Double) = TranscriptSegment(start, end, "S1", "words")
        val blocks = ConversationSplit.blocks(listOf(at(0.0, 10.0), at(609.0, 620.0), at(1221.0, 1230.0), at(1230.0, 1240.0), at(5000.0, 5010.0)))
        assertEquals(listOf(NoteConversation(0.0, 620.0), NoteConversation(1221.0, 1240.0), NoteConversation(5000.0, 5010.0)), blocks)
        assertEquals(emptyList(), ConversationSplit.blocks(listOf(TranscriptSegment(0.0, 1.0, "S1", " "))))

        val block = NoteConversation(0.0, 3600.0)
        assertEquals(
            listOf(NoteConversation(0.0, 1500.0), NoteConversation(1500.0, 3600.0)),
            ConversationSplit.cut(block, listOf(1500.0, 1530.0, 30.0, 3590.0, 7200.0)),
            "too near an end or the boundary before is not a boundary",
        )
        val spans = ConversationSplit.cut(block, listOf(1500.0))
        val assigned = ConversationSplit.assign(listOf(at(10.0, 20.0), at(1499.0, 1510.0), at(1500.0, 1501.0), at(3600.0, 3601.0)), spans)
        assertEquals(listOf(listOf(10.0, 1499.0), listOf(1500.0, 3600.0)), assigned.map { conversation -> conversation.map { it.start } })
    }

    @Test
    fun `lines carry the speaker's label and are never much longer than twenty seconds`() {
        val long = TranscriptSegment(60.0, 100.0, "S2", "one two three four five six", listOf(
            TranscriptWord(60.0, 61.0, "one"), TranscriptWord(66.0, 67.0, "two"), TranscriptWord(74.0, 75.0, "three"),
            TranscriptWord(80.0, 81.0, "four"), TranscriptWord(90.0, 91.0, "five"), TranscriptWord(94.0, 95.0, "six"),
        ))
        val lines = NoteLines.of(listOf(TranscriptSegment(1.0, 3.0, "", "hello"), long))
        assertEquals(
            "[00:00:01] hello\n[00:01:00] S2: one two three\n[00:01:20] S2: four five six",
            NoteLines.render(lines),
        )
        assertEquals("[00:01:20] S2: four five six", NoteLines.tail(lines, 40))
    }

    @Test
    fun `answers are read leniently and only for what makes an item`() {
        val json = NotesAnswer.parse(
            """Here you go: ```json {"items":[
              {"kind":"todo","text":"Call [phone#1]","owner":"S2","due":"Friday","at":"00:01:05","quote":"I'll call"},
              {"kind":"decision","text":"Ship","owner":"S1","due":"never","at":"[01:02:03]","quote":"we ship"},
              {"kind":"question","text":"Why?","at":"61:00","quote":"why"},
              {"kind":"todo","text":"Ask Mina","owner":"Mina","at":"00:00:10","quote":"ask her"},
              {"kind":"idea","text":"x","at":"00:00:01","quote":"x"},
              {"kind":"todo","text":"","at":"00:00:01","quote":"x"},
              {"kind":"todo","text":"No time","quote":"x"}
            ]} ``` done""",
        )
        val items = NotesAnswer.items(json) { it.replace("[phone#1]", "010-1234-5678") }
        assertEquals(4, items.size)
        assertEquals(Extracted(NoteKind.TODO, "Call 010-1234-5678", "S2", "Friday", 65.0, "I'll call"), items[0])
        assertEquals(Extracted(NoteKind.DECISION, "Ship", null, null, 3723.0, "we ship"), items[1], "an owner and a due date only for a to-do")
        assertEquals(3660.0, items[2].atSec)
        assertEquals(null, items[3].owner, "a name is not a speaker label")
        assertEquals(7, NotesAnswer.offered(json))
        assertEquals(listOf(90.0, 3600.0), NotesAnswer.boundaries(NotesAnswer.parse("""{"boundaries":["00:01:30","1:00:00","late"]}""")))
    }
}
