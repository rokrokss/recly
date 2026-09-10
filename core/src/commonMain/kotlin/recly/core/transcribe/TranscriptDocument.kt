package recly.core.transcribe

/** A stable, bounded paragraph for reading, searching and seeking in a long transcript. */
data class TranscriptBlock(val index: Int, val start: Double, val speaker: String, val text: String)

/**
 * Presentation shared by the four readers. Build once per result, not on every playback tick.
 * StringBuilder avoids repeatedly copying a whole monologue; bounded blocks keep lazy readers
 * useful even when one speaker talks for hours. Original segment timestamps remain seek targets.
 */
class TranscriptDocument(transcript: Transcript) {
    val blocks: List<TranscriptBlock> = buildList {
        var speaker: String? = null
        var start = 0.0
        val words = StringBuilder()
        fun flush() {
            if (words.isNotEmpty()) add(TranscriptBlock(size, start, speaker.orEmpty(), words.toString()))
            words.clear()
        }
        for (segment in transcript.segments) {
            val text = segment.text.trim()
            if (text.isEmpty()) continue
            if (speaker != segment.speaker || segment.end - start > 60 || words.length + text.length > 1200) {
                flush()
            }
            if (words.isEmpty()) {
                speaker = segment.speaker
                start = segment.start.coerceAtLeast(0.0)
            } else words.append(' ')
            words.append(text)
        }
        flush()
    }

    /** The complete text, including timestamps, even while the view shows search matches only. */
    val plainText: String = TranscriptNormalizer.text(transcript)

    fun search(query: String): List<TranscriptBlock> {
        val term = query.trim()
        return if (term.isEmpty()) blocks else blocks.filter {
            it.text.contains(term, ignoreCase = true) || it.speaker.contains(term, ignoreCase = true)
        }
    }
}
