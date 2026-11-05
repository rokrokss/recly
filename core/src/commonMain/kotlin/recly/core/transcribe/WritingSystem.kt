package recly.core.transcribe

/**
 * docs/08 "Result files" · "Exports": where a transcript's writing system changes how its text is put together —
 * what goes between two segments that run on in one line, and how long a subtitle cue may be. Characters are told
 * apart by Unicode block, one code point at a time.
 */
internal object WritingSystem {
    /**
     * What goes between [before] and [after] when a line runs on from one segment to the next: nothing where both
     * sides are Han, Kana or full-width punctuation — Japanese and Chinese are written without spaces — and a space
     * everywhere else: Hangul, Thai and Latin, and a mixed boundary such as Han next to Latin or digits, where
     * Chinese and Japanese typesetting leave a gap between the scripts too.
     */
    fun separator(before: CharSequence, after: CharSequence): String {
        val last = lastCodePoint(before) ?: return " "
        val first = firstCodePoint(after) ?: return " "
        return if (unspaced(last) && unspaced(first)) "" else " "
    }

    /**
     * A cue's length in half-width units: a full-width character — Hangul, Han, Kana, full-width punctuation —
     * counts 2 and anything else 1, as the Netflix Timed Text Style Guides count a half-width character as half a
     * character. For Latin text it is the number of characters.
     */
    fun cueWidth(text: CharSequence): Int {
        var width = 0
        forEachCodePoint(text) { width += if (wide(it)) 2 else 1 }
        return width
    }

    /**
     * The longest cue for [text]'s writing system, in [cueWidth] units (docs/08 "Exports"): two lines of the Netflix
     * Timed Text Style Guides on partnerhelp.netflixstudios.com, read 2026-11-05 — Japanese 13 full-width characters
     * a line (article 215767517, change log 2025-12-19), Korean 16 (216001127, 2025-07-07), Simplified Chinese 16
     * (215986007, 2025-07-04), Traditional Chinese 16 (215994807, 2025-12-19), and everything else the English (USA)
     * guide's 42 characters (217350977, 2025-12-19). Text is East Asian when its Hangul, Han and Kana letters are at
     * least as many as its other letters, and Japanese when Kana is among them. The Chinese guides do not say how a
     * half-width character counts; it counts half, as in the Korean and Japanese guides.
     */
    fun cueLimit(text: CharSequence): Int {
        var eastAsian = 0
        var other = 0
        var japanese = false
        forEachCodePoint(text) {
            when {
                kana(it) -> { eastAsian++; japanese = true }
                hangul(it) || han(it) -> eastAsian++
                it < 0x10000 && Char(it).isLetter() -> other++
            }
        }
        return when {
            eastAsian == 0 || eastAsian < other -> LATIN_CUE
            japanese -> JAPANESE_CUE
            else -> KOREAN_CHINESE_CUE
        }
    }

    /** Latin and every other script: 2 × 42 characters. */
    const val LATIN_CUE = 84

    /** Japanese: 2 × 13 full-width characters, in half-width units. */
    const val JAPANESE_CUE = 2 * 13 * 2

    /** Korean, Simplified and Traditional Chinese: 2 × 16 full-width characters, in half-width units. */
    const val KOREAN_CHINESE_CUE = 2 * 16 * 2

    fun han(cp: Int): Boolean =
        cp in 0x2E80..0x2FDF || cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0xF900..0xFAFF || cp in 0x20000..0x3FFFF

    /** Hiragana, Katakana and its extensions, and half-width Katakana. */
    fun kana(cp: Int): Boolean = cp in 0x3040..0x30FF || cp in 0x31F0..0x31FF || cp in HALF_WIDTH_KATAKANA

    /** Syllables, Jamo and their compatibility forms. */
    fun hangul(cp: Int): Boolean =
        cp in 0xAC00..0xD7A3 || cp in 0x1100..0x11FF || cp in 0x3130..0x318F || cp in 0xA960..0xA97F || cp in 0xD7B0..0xD7FF

    fun thai(cp: Int): Boolean = cp in 0x0E00..0x0E7F

    private val HALF_WIDTH_KATAKANA = 0xFF66..0xFF9F

    /** CJK symbols and punctuation, the full-width forms with the half-width CJK punctuation, and full-width signs. */
    private fun fullWidthPunctuation(cp: Int): Boolean = cp in 0x3000..0x303F || cp in 0xFF01..0xFF65 || cp in 0xFFE0..0xFFE6

    private fun unspaced(cp: Int): Boolean = han(cp) || kana(cp) || fullWidthPunctuation(cp)

    private fun wide(cp: Int): Boolean =
        hangul(cp) || han(cp) || (kana(cp) && cp !in HALF_WIDTH_KATAKANA) ||
            cp in 0x3000..0x303F || cp in 0xFF01..0xFF60 || cp in 0xFFE0..0xFFE6

    inline fun forEachCodePoint(text: CharSequence, action: (Int) -> Unit) = forEachCodePointAt(text) { cp, _, _ -> action(cp) }

    /** Each code point of [text] with where it starts and ends in UTF-16 units; a lone surrogate is passed on as it is. */
    inline fun forEachCodePointAt(text: CharSequence, action: (cp: Int, start: Int, end: Int) -> Unit) {
        var i = 0
        while (i < text.length) {
            val high = text[i]
            if (high.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                action(codePoint(high, text[i + 1]), i, i + 2)
                i += 2
            } else {
                action(high.code, i, i + 1)
                i++
            }
        }
    }

    fun codePoint(high: Char, low: Char): Int = ((high.code - 0xD800) shl 10) + (low.code - 0xDC00) + 0x10000

    private fun lastCodePoint(text: CharSequence): Int? {
        if (text.isEmpty()) return null
        val low = text[text.length - 1]
        if (low.isLowSurrogate() && text.length >= 2 && text[text.length - 2].isHighSurrogate()) {
            return codePoint(text[text.length - 2], low)
        }
        return low.code
    }

    private fun firstCodePoint(text: CharSequence): Int? {
        if (text.isEmpty()) return null
        val high = text[0]
        if (high.isHighSurrogate() && text.length >= 2 && text[1].isLowSurrogate()) return codePoint(high, text[1])
        return high.code
    }
}
