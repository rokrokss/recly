package recly.core.transcribe

/** A stable, bounded paragraph for reading, searching and seeking in a long transcript. */
data class TranscriptBlock(val index: Int, val start: Double, val speaker: String, val text: String)

/** [length] characters of block [block]'s text from [offset]. */
data class BlockRange(val block: Int, val offset: Int, val length: Int)

/**
 * Presentation shared by the four readers. Build once per result, not on every playback tick.
 * StringBuilder avoids repeatedly copying a whole monologue; bounded blocks keep lazy readers
 * useful even when one speaker talks for hours. Original segment timestamps remain seek targets.
 */
class TranscriptDocument(transcript: Transcript) {
    /** Where each segment's words are in [blocks], by segment index; a segment that says nothing has none. */
    private val placed = mutableMapOf<Int, BlockRange>()

    val blocks: List<TranscriptBlock> = buildList {
        var speaker: String? = null
        var start = 0.0
        val words = StringBuilder()
        fun flush() {
            if (words.isNotEmpty()) add(TranscriptBlock(size, start, speaker.orEmpty(), words.toString()))
            words.clear()
        }
        transcript.segments.forEachIndexed { index, segment ->
            val text = segment.text.trim()
            if (text.isEmpty()) return@forEachIndexed
            if (speaker != segment.speaker || segment.end - start > 60 || words.length + text.length > 1200) {
                flush()
            }
            if (words.isEmpty()) {
                speaker = segment.speaker
                start = segment.start.coerceAtLeast(0.0)
            } else words.append(' ')
            placed[index] = BlockRange(size, words.length, text.length)
            words.append(text)
        }
        flush()
    }

    /**
     * docs/09 "Transcript reader": where the words of [segments] — indices into the transcript's segments, such as a
     * correction's (docs/08 "Correcting") — are in [blocks], in reading order, for a reader to tint.
     */
    fun segmentRanges(segments: List<Int>): List<BlockRange> = segments.distinct().mapNotNull { placed[it] }
        .sortedWith(compareBy({ it.block }, { it.offset }))

    /** The complete text, including timestamps, even while the view shows search matches only. */
    val plainText: String = TranscriptNormalizer.text(transcript)

    fun search(query: String): List<TranscriptBlock> {
        val term = query.trim()
        return if (term.isEmpty()) blocks else blocks.filter {
            it.text.contains(term, ignoreCase = true) || it.speaker.contains(term, ignoreCase = true)
        }
    }
}
