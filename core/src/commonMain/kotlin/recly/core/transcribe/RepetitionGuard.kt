package recly.core.transcribe

import recly.core.platform.Logger

/**
 * docs/05 "Fixed processing settings": what the Android and Windows engines do with each piece Qwen3-ASR decodes
 * before they keep it. A decoder caught in a loop writes the same words over and over until it stops or its token
 * budget runs out; the guard cuts such a tail back to its first copy, and drops a piece that is one unit said over
 * and over. Its bars are high, so a person repeating themselves — "네 네 네", "no, no, no", "좋아요 좋아요" — is
 * kept. It never removes a word for being a particular word.
 *
 * It counts in units of the text rather than the model's tokens, which sherpa-onnx also returns: those do not
 * always join up to the text (sherpa-onnx removes U+FFFD from the text but not from the tokens), and one word is a
 * different token with and without a space before it. Every Hangul, Han, Kana and Thai character is a unit — those
 * scripts run words together, Hangul a word's syllables — and so is every other run of letters, digits and marks,
 * compared ignoring case; spaces, punctuation and symbols part units and are not units. A unit is about a token.
 * The bars follow transcribe.cpp's repetition guard (MIT); the share of one unit that makes a piece noise follows
 * LiveSub-Offline-Translator's `NoiseTextFilter` (MIT).
 */
object RepetitionGuard {
    enum class Action { KEPT, TRIMMED, DROPPED }

    /** [text] is what to keep; [units] the piece's units and [removed] how many of them went. */
    class Outcome(val text: String, val action: Action, val units: Int, val removed: Int)

    /** Logged when a piece is trimmed or dropped, with counts and never text: `action` · `units` · `removed` · `budgetHit`. */
    const val EVENT = "local.repetition.removed"

    /**
     * Whether a decode used up its budget of [maxNewTokens]: sherpa-onnx returns a piece's tokens without the
     * "language … <asr_text>" opening Qwen3-ASR writes first, which it looks for among the first 16 tokens.
     */
    fun budgetHit(tokens: Int, maxNewTokens: Int): Boolean = tokens >= maxNewTokens - OPENING_TOKENS

    /** [check], logged as [EVENT] when it changes anything; returns the text to keep. */
    fun apply(text: String, budgetHit: Boolean, logger: Logger): String {
        val outcome = check(text, budgetHit)
        if (outcome.action != Action.KEPT) {
            logger.log(
                Logger.Level.INFO, EVENT,
                mapOf("action" to outcome.action.name.lowercase(), "units" to outcome.units, "removed" to outcome.removed, "budgetHit" to budgetHit),
            )
        }
        return outcome.text
    }

    /**
     * One decoded piece. Dropped when a single unit makes up [DROP_SHARE_PERCENT]% of it or more and is there at
     * least [DROP_MIN] times. Else trimmed when it ends in copies of one block of units — the smallest block that
     * clears its bar ([repeatsNeeded]; when the decode used up its budget, [BUDGET_REPEATS] copies over
     * [BUDGET_SPAN] units are enough) — keeping what came before the copies and the first of them. Else kept.
     */
    fun check(text: String, budgetHit: Boolean): Outcome {
        val units = units(text)
        val n = units.size
        val top = units.groupingBy { it.key }.eachCount().maxOfOrNull { it.value } ?: 0
        if (top >= DROP_MIN && top * 100 >= n * DROP_SHARE_PERCENT) return Outcome("", Action.DROPPED, n, n)
        for (block in 1..minOf(MAX_BLOCK, n / 2)) {
            // The longest tail that repeats every `block` units.
            var matched = 0
            while (matched < n - block && units[n - 1 - matched].key == units[n - 1 - matched - block].key) matched++
            val run = block + matched
            val copies = run / block
            val enough = copies >= repeatsNeeded(block) ||
                (budgetHit && copies >= BUDGET_REPEATS && copies * block >= BUDGET_SPAN)
            if (!enough) continue
            val keep = n - run + phase(units, n - run, block, run, budgetHit) + block
            return Outcome(text.substring(0, units[keep].start).trimEnd(), Action.TRIMMED, n, n - keep)
        }
        return Outcome(text, Action.KEPT, n, 0)
    }

    /**
     * The copies a block of [block] units needs: as many as make [MIN_SPAN] units, so one unit needs 64 copies and
     * eight need 8; 8 copies up to blocks of 27, sliding to 4 by blocks of 48 as the span stops at [MAX_SPAN].
     */
    internal fun repeatsNeeded(block: Int): Int {
        val span = (block * MIN_REPEATS).coerceIn(MIN_SPAN, MAX_SPAN)
        return maxOf(MIN_LONG_REPEATS, (span + block - 1) / block)
    }

    /**
     * Where the copies begin in the repeating tail that starts at [start], 0 until [block]. That tail can reach back
     * into the words before the loop by a few units that happen to match ("…시작하겠습니다 감사합니다 감사합니다"
     * repeats every five syllables from "니다"), and a decode cut off by its budget ends inside a copy. The phase whose
     * copy boundaries most often fall where the text has a space or punctuation wins. With nothing to tell them apart
     * — Latin, where every unit is a word, or Chinese without punctuation — the copies run from the start of the tail
     * when the budget cut the decode off, and end at its end when the decoder stopped by itself.
     */
    private fun phase(units: List<TextUnit>, start: Int, block: Int, run: Int, budgetHit: Boolean): Int {
        val breaks = IntArray(block) { p ->
            var count = 0
            var at = start + p + block
            while (at < units.size) {
                if (units[at].start > units[at - 1].end) count++
                at += block
            }
            count
        }
        val most = breaks.max()
        val fallback = if (budgetHit) 0 else run % block
        return if (breaks[fallback] == most) fallback else breaks.indexOfFirst { it == most }
    }

    /** A unit and where it is in the text, in UTF-16 units. */
    private class TextUnit(val key: String, val start: Int, val end: Int)

    private fun units(text: String): List<TextUnit> {
        val units = mutableListOf<TextUnit>()
        var wordStart = -1
        var wordEnd = -1
        fun endWord() {
            if (wordStart >= 0) units += TextUnit(text.substring(wordStart, wordEnd).lowercase(), wordStart, wordEnd)
            wordStart = -1
        }
        WritingSystem.forEachCodePointAt(text) { cp, start, end ->
            when {
                WritingSystem.hangul(cp) || WritingSystem.han(cp) || WritingSystem.kana(cp) || WritingSystem.thai(cp) -> {
                    endWord()
                    units += TextUnit(text.substring(start, end), start, end)
                }
                cp < 0x10000 && Char(cp).let { it.isLetterOrDigit() || it.category in MARKS } -> {
                    if (wordStart < 0) wordStart = start
                    wordEnd = end
                }
                else -> endWord()
            }
        }
        endWord()
        return units
    }

    private val MARKS = setOf(CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK)

    /** sherpa-onnx strips Qwen3-ASR's opening when "<asr_text>" is among the first 16 tokens. */
    private const val OPENING_TOKENS = 16

    private const val MAX_BLOCK = 128
    private const val MIN_REPEATS = 8
    private const val MIN_LONG_REPEATS = 4

    /** No tail shorter than this is a loop the decoder stopped by itself. */
    private const val MIN_SPAN = 64
    private const val MAX_SPAN = 192

    /** A decode that used up its budget was most likely looping, so less evidence is needed. */
    private const val BUDGET_REPEATS = 3
    private const val BUDGET_SPAN = 32

    private const val DROP_SHARE_PERCENT = 75
    private const val DROP_MIN = 16
}
