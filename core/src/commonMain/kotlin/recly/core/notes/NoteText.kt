package recly.core.notes

import recly.core.recording.RecordingSearch
import recly.core.transcribe.TranscriptNormalizer
import recly.core.transcribe.TranscriptSegment

/**
 * docs/08 "Organizing": the text rules the notes are checked and matched by, ported from the long-recording
 * evaluation (`scripts/long-recording-eval`, `textmetrics.py`, `extract.py` `quote_check`, `score_sheet.py`
 * `same_item`), so what was measured there is what the app does.
 */
internal object NoteText {
    /**
     * `normalize`: case and full-width Latin folded ([RecordingSearch.fold]), everything that is not a letter or a
     * digit a space, spaces collapsed. (The evaluation also applies NFKC; transcripts and answers arrive composed.)
     */
    fun normalize(text: String): String {
        val folded = RecordingSearch.fold(text)
        val out = StringBuilder(folded.length)
        var gap = false
        for (c in folded) {
            if (c.isLetterOrDigit()) {
                if (gap && out.isNotEmpty()) out.append(' ')
                gap = false
                out.append(c)
            } else {
                gap = true
            }
        }
        return out.toString()
    }

    /** `chars`: the normalized text without its spaces — what a quote is compared by. */
    fun chars(text: String): String = normalize(text).replace(" ", "")

    /**
     * About how many words [text] holds: its space-separated pieces, a piece written without spaces (Chinese,
     * Japanese, Thai) counting one word per two of its letters.
     */
    fun words(text: String): Int = text.split(' ', '\n', '\t', '　').filter { it.isNotBlank() }.sumOf { piece ->
        val letters = piece.count(Char::isLetterOrDigit)
        if (piece.any(::unspaced)) maxOf(1, letters / 2) else 1
    }

    private fun unspaced(c: Char): Boolean =
        c in '぀'..'ヿ' || c in '㐀'..'鿿' || c in '豈'..'﫿' || c in '฀'..'๿'

    /** Python's `difflib.SequenceMatcher(None, a, b, autojunk=False).ratio()`. */
    fun ratio(a: String, b: String): Double {
        val total = a.length + b.length
        if (total == 0) return 1.0
        return 2.0 * matchingBlocks(a, b).sumOf { it.size } / total
    }

    /** `partial_ratio`: the best [ratio] of [needle] against any stretch of [haystack] as long as it. */
    fun partialRatio(needle: String, haystack: String): Double {
        if (needle.isEmpty()) return 0.0
        if (haystack.length <= needle.length) return ratio(needle, haystack)
        // Candidate windows are anchored on the stretches the two share.
        val starts = matchingBlocks(needle, haystack).map { maxOf(0, it.b - it.a) }.toSet().ifEmpty { setOf(0) }
        return starts.maxOf { start ->
            val from = minOf(start, haystack.length - needle.length)
            ratio(needle, haystack.substring(from, from + needle.length))
        }
    }

    private class Block(val a: Int, val b: Int, val size: Int)

    /** `get_matching_blocks` without a junk heuristic: the longest common stretch, then the same on either side. */
    private fun matchingBlocks(a: String, b: String): List<Block> {
        val positions = HashMap<Char, MutableList<Int>>()
        b.forEachIndexed { j, c -> positions.getOrPut(c) { mutableListOf() }.add(j) }
        val blocks = mutableListOf<Block>()
        val queue = ArrayDeque<IntArray>()
        queue += intArrayOf(0, a.length, 0, b.length)
        while (queue.isNotEmpty()) {
            val (alo, ahi, blo, bhi) = queue.removeLast()
            val found = longest(a, positions, alo, ahi, blo, bhi)
            if (found.size == 0) continue
            blocks += found
            if (alo < found.a && blo < found.b) queue += intArrayOf(alo, found.a, blo, found.b)
            if (found.a + found.size < ahi && found.b + found.size < bhi) {
                queue += intArrayOf(found.a + found.size, ahi, found.b + found.size, bhi)
            }
        }
        return blocks.sortedWith(compareBy({ it.a }, { it.b }))
    }

    /** `find_longest_match`: the earliest of the longest, as difflib picks it. */
    private fun longest(a: String, positions: Map<Char, List<Int>>, alo: Int, ahi: Int, blo: Int, bhi: Int): Block {
        var bestA = alo
        var bestB = blo
        var best = 0
        var lengths = HashMap<Int, Int>()
        for (i in alo until ahi) {
            val next = HashMap<Int, Int>()
            for (j in positions[a[i]].orEmpty()) {
                if (j < blo) continue
                if (j >= bhi) break
                val k = (lengths[j - 1] ?: 0) + 1
                next[j] = k
                if (k > best) {
                    bestA = i - k + 1
                    bestB = j - k + 1
                    best = k
                }
            }
            lengths = next
        }
        return Block(bestA, bestB, best)
    }
}

/**
 * docs/08 "Organizing": an item is kept only when its quote is really in the transcript near its time — the
 * evaluation's `quote_check`. The text compared is that of the words that overlap ±[WINDOW_SEC] of the item's
 * time, running on past the window by as many words as the quote has; a segment without word timings counts
 * whole when it overlaps the window.
 */
internal object QuoteCheck {
    const val WINDOW_SEC = 10.0
    const val THRESHOLD = 0.8

    fun ratio(quote: String, atSec: Double, segments: List<TranscriptSegment>): Double {
        val low = atSec - WINDOW_SEC
        val high = atSec + WINDOW_SEC
        var runOn = quote.split(' ', '\n', '\t').count { it.isNotBlank() }
        val pieces = mutableListOf<String>()
        for (segment in segments.sortedBy { it.start }) {
            val timed = segment.words.orEmpty()
            if (timed.isNotEmpty()) {
                for (word in timed) {
                    if (word.end >= low && word.start <= high) {
                        pieces += word.text
                    } else if (word.start > high && pieces.isNotEmpty() && runOn > 0) {
                        pieces += word.text
                        runOn--
                    }
                }
            } else if (segment.end >= low && segment.start <= high) {
                pieces += segment.text
            }
        }
        return NoteText.partialRatio(NoteText.chars(quote), NoteText.chars(pieces.joinToString(" ")))
    }

    fun passes(quote: String, atSec: Double, segments: List<TranscriptSegment>): Boolean =
        quote.isNotBlank() && ratio(quote, atSec, segments) >= THRESHOLD
}

/**
 * docs/08 "Organizing": two notes are the same item — the evaluation's `same_item` — when they are of one kind and
 * their texts are alike ([TEXT_RATIO] after [NoteText.normalize]) or they cite the same moment (within
 * [SAME_MOMENT_SEC]). What a deletion and the final pass are matched by.
 */
internal object SameItem {
    const val TEXT_RATIO = 0.6
    const val SAME_MOMENT_SEC = 15.0

    fun matches(kind: NoteKind, text: String, atSec: Double, otherKind: NoteKind, otherText: String, otherAtSec: Double): Boolean {
        if (kind != otherKind) return false
        if (sameText(text, otherText)) return true
        return kotlin.math.abs(atSec - otherAtSec) <= SAME_MOMENT_SEC
    }

    fun sameText(text: String, otherText: String): Boolean =
        NoteText.ratio(NoteText.normalize(text), NoteText.normalize(otherText)) >= TEXT_RATIO
}

/**
 * docs/08 "Organizing": phone numbers, e-mail addresses and long runs of digits leave the device as `[phone#1]`,
 * `[email#1]` and `[number#1]` — the same value the same placeholder within one request — and are put back into
 * what comes back, on the device. One instance per request.
 */
internal class Placeholders {
    private val originals = LinkedHashMap<String, String>()
    private val byValue = HashMap<String, String>()
    private val counts = HashMap<String, Int>()

    /** [text] with every value replaced by its placeholder. */
    fun hide(text: String): String {
        var out = replace(text, EMAIL, "email")
        out = replace(out, PHONE, "phone")
        return replace(out, DIGITS, "number")
    }

    /** [text] with every placeholder this instance made back to its value; any other bracket is left alone. */
    fun restore(text: String): String = PLACEHOLDER.replace(text) { originals[it.value] ?: it.value }

    val hidden: Int get() = originals.size

    private fun replace(text: String, pattern: Regex, kind: String): String = pattern.replace(text) { match ->
        val before = text.getOrNull(match.range.first - 1)
        val after = text.getOrNull(match.range.last + 1)
        // A digit or a letter right next to it: part of something longer, not a value on its own.
        if (before?.isLetterOrDigit() == true || before == '+' || after?.isDigit() == true) return@replace match.value
        byValue.getOrPut(match.value) {
            val n = (counts[kind] ?: 0) + 1
            counts[kind] = n
            "[$kind#$n]".also { originals[it] = match.value }
        }
    }

    companion object {
        private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")

        /** Grouped like a phone number — `010-1234-5678`, `+82 10 1234 5678`, `(02) 123-4567`, `555.123.4567`. */
        private val PHONE = Regex("""(?:\+\d{1,3}[ .-]?)?(?:\(\d{1,4}\)[ .-]?|\d{2,4}[ .-])\d{3,4}[ .-]\d{4}""")

        /** Eight digits or more, a single space or hyphen allowed between them — an account, a card, an ID. */
        private val DIGITS = Regex("""\d(?:[ -]?\d){7,}""")

        private val PLACEHOLDER = Regex("""\[(?:email|phone|number)#\d+]""")
    }
}

/**
 * docs/08 "Organizing": the lines a transcript is sent as — `[HH:MM:SS] S1: text`, its speaker's label and never
 * a name — one per segment, a segment longer than [LINE_MAX_SEC] with word timings split where a piece reaches
 * three quarters of it, so an item's time is never far from its words (the evaluation's `lines_of`).
 */
internal object NoteLines {
    const val LINE_MAX_SEC = 20.0

    class Line(val atSec: Double, val speaker: String, val text: String)

    fun of(segments: List<TranscriptSegment>): List<Line> = segments.filter { it.text.isNotBlank() }.sortedBy { it.start }.flatMap { segment ->
        val words = segment.words.orEmpty().filter { it.text.isNotBlank() }
        if (segment.end - segment.start <= LINE_MAX_SEC || words.isEmpty()) {
            listOf(Line(segment.start, segment.speaker, segment.text.trim()))
        } else {
            // A segment written without spaces (Japanese, Chinese) is joined back the same way.
            val separator = if (' ' in segment.text.trim()) " " else ""
            val lines = mutableListOf<Line>()
            var began = words.first().start
            val current = mutableListOf<String>()
            for (word in words) {
                if (current.isNotEmpty() && word.start - began >= LINE_MAX_SEC * 0.75) {
                    lines += Line(began, segment.speaker, current.joinToString(separator))
                    current.clear()
                    began = word.start
                }
                current += word.text.trim()
            }
            lines += Line(began, segment.speaker, current.joinToString(separator))
            lines
        }
    }

    fun render(lines: List<Line>): String = lines.joinToString("\n") { line ->
        "[${TranscriptNormalizer.clock(line.atSec)}] " + (if (line.speaker.isNotEmpty()) "${line.speaker}: " else "") + line.text
    }

    /** The end of [lines] rendered, at most [limit] characters, cut at a line. */
    fun tail(lines: List<Line>, limit: Int): String {
        val kept = ArrayDeque<String>()
        var length = 0
        for (line in lines.asReversed()) {
            val text = render(listOf(line))
            if (length + text.length + 1 > limit) break
            kept.addFirst(text)
            length += text.length + 1
        }
        return kept.joinToString("\n")
    }
}

/**
 * docs/08 "Organizing": a recording's conversations. Every silence of more than [SILENCE_SEC] between speech cuts
 * it ([blocks]); a block longer than [LONG_SEC] is asked once where its conversations change ([cut]).
 */
internal object ConversationSplit {
    const val SILENCE_SEC = 600.0
    const val LONG_SEC = 45 * 60.0

    /** A boundary nearer than this to a block's ends, or to the boundary before it, is not one. */
    const val MIN_SEC = 60.0

    /** The spans of speech no silence of more than ten minutes interrupts, in order. */
    fun blocks(segments: List<TranscriptSegment>): List<NoteConversation> {
        val spoken = segments.filter { it.text.isNotBlank() }.sortedBy { it.start }
        if (spoken.isEmpty()) return emptyList()
        val blocks = mutableListOf<NoteConversation>()
        var start = spoken.first().start
        var end = spoken.first().end
        for (segment in spoken.drop(1)) {
            if (segment.start - end > SILENCE_SEC) {
                blocks += NoteConversation(start, end)
                start = segment.start
            }
            end = maxOf(end, segment.end)
        }
        blocks += NoteConversation(start, end)
        return blocks
    }

    /** [block] cut at the [boundaries] that fall inside it, at least [MIN_SEC] from its ends and from each other. */
    fun cut(block: NoteConversation, boundaries: List<Double>): List<NoteConversation> {
        val kept = mutableListOf<Double>()
        for (at in boundaries.sorted()) {
            if (at < block.startSec + MIN_SEC || at > block.endSec - MIN_SEC) continue
            if (kept.isNotEmpty() && at - kept.last() < MIN_SEC) continue
            kept += at
        }
        val starts = listOf(block.startSec) + kept
        val ends = kept + block.endSec
        return starts.zip(ends) { start, end -> NoteConversation(start, end) }
    }

    /** The spoken segments of each of [spans] (in order): each goes to the last span that starts at or before it. */
    fun assign(segments: List<TranscriptSegment>, spans: List<NoteConversation>): List<List<TranscriptSegment>> {
        val out = spans.map { mutableListOf<TranscriptSegment>() }
        for (segment in segments.filter { it.text.isNotBlank() }.sortedBy { it.start }) {
            val index = spans.indexOfLast { it.startSec <= segment.start }.coerceAtLeast(0)
            if (out.isNotEmpty()) out[index] += segment
        }
        return out
    }
}
