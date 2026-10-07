package recly.core.recording

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import recly.core.model.recJson
import recly.core.platform.CoreDeps
import recly.core.platform.Logger
import recly.core.transcribe.TranscribeRunner
import recly.core.transcribe.Transcript

/** Where a match is in a piece of text: [length] characters from [offset]. */
data class SearchRange(val offset: Int, val length: Int)

/** One segment of a transcript that matches: where it starts in the recording, its words, and the matches in them. */
data class SearchSnippet(val atSec: Double, val text: String, val ranges: List<SearchRange>)

/**
 * One recording a search found: by its title ([titleRanges] mark the matches there), its transcript
 * ([snippets], at most [RecordingSearch.SNIPPETS]), or both.
 */
data class SearchHit(
    val recordingId: String,
    val title: String?,
    val startedAt: String,
    val matchesInTitle: Boolean,
    val titleRanges: List<SearchRange>,
    val snippets: List<SearchSnippet>,
)

/**
 * docs/10 "Search": the titles of every row in the list and the transcripts held on this device —
 * this device's own, and other devices' once read (opened, or cached by a pull). Case is ignored, and
 * so are the accents of Latin letters and the width of full-width Latin, so `resume` finds `Résumé` and
 * `ＡＢＣ` finds `abc`; the query is one phrase.
 *
 * A plain scan, off the caller's thread. Each transcript is read and folded once and kept until its file
 * changes (size or time), so a list of a thousand recordings answers from memory.
 */
class RecordingSearch internal constructor(
    private val recordings: RecordingRepository,
    private val deps: CoreDeps,
) {
    private class Cached(val stamp: Pair<Long?, Long?>, val segments: List<Segment>)

    private class Segment(val start: Double, val text: String, val folded: String)

    private val mutex = Mutex()
    private val cache = mutableMapOf<String, Cached>()

    suspend fun search(query: String, limit: Int): List<SearchHit> {
        val needle = fold(query.trim())
        if (needle.isEmpty() || limit <= 0) return emptyList()
        val rows = recordings.list(Int.MAX_VALUE)
        return withContext(Dispatchers.Default) {
            val hits = mutableListOf<SearchHit>()
            for (row in rows) {
                if (hits.size >= limit) break
                val title = row.meta.title
                val titleRanges = title?.let { ranges(fold(it), needle) }.orEmpty()
                val snippets = segments(row).asSequence()
                    .filter { needle in it.folded }
                    .take(SNIPPETS)
                    .map { snippet(it, needle) }
                    .toList()
                if (titleRanges.isEmpty() && snippets.isEmpty()) continue
                hits += SearchHit(row.id, title, row.meta.startedAt, titleRanges.isNotEmpty(), titleRanges, snippets)
            }
            hits
        }
    }

    /** The transcript's segments as they are on disk now: from memory while the file is unchanged. */
    private suspend fun segments(row: RecordingRecord): List<Segment> {
        val path = row.dir / TranscribeRunner.jsonFileName(MetaWriter.baseName(row.meta))
        val stamp = withContext(deps.io) { deps.fileSystem.metadataOrNull(path) }
            ?.let { it.size to it.lastModifiedAtMillis }
        if (stamp == null) {
            mutex.withLock { cache.remove(row.id) }
            return emptyList()
        }
        mutex.withLock { cache[row.id]?.takeIf { it.stamp == stamp }?.let { return it.segments } }
        val segments = try {
            val transcript = withContext(deps.io) {
                recJson.decodeFromString<Transcript>(deps.fileSystem.read(path) { readUtf8() })
            }
            transcript.segments.filter { it.text.isNotBlank() }.map { Segment(it.start, it.text.trim(), fold(it.text.trim())) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            deps.logger.log(Logger.Level.WARN, "search.transcript.unreadable", mapOf("recordingId" to row.id), e)
            emptyList()
        }
        mutex.withLock { cache[row.id] = Cached(stamp, segments) }
        return segments
    }

    /** The segment's words, cut to [SNIPPET_CHARS] around the first match when longer, with every match in it marked. */
    private fun snippet(segment: Segment, needle: String): SearchSnippet {
        val text = segment.text
        if (text.length <= SNIPPET_CHARS) return SearchSnippet(segment.start, text, ranges(segment.folded, needle))
        val first = segment.folded.indexOf(needle)
        val from = (first - LEAD_CHARS).coerceIn(0, text.length - SNIPPET_CHARS)
        val to = from + SNIPPET_CHARS
        val prefix = if (from > 0) ELLIPSIS else ""
        val suffix = if (to < text.length) ELLIPSIS else ""
        val ranges = ranges(segment.folded.substring(from, to), needle).map { it.copy(offset = it.offset + prefix.length) }
        return SearchSnippet(segment.start, prefix + text.substring(from, to) + suffix, ranges)
    }

    private fun ranges(haystack: String, needle: String): List<SearchRange> {
        val found = mutableListOf<SearchRange>()
        var at = haystack.indexOf(needle)
        while (at >= 0) {
            found += SearchRange(at, needle.length)
            at = haystack.indexOf(needle, at + needle.length)
        }
        return found
    }

    companion object {
        /** The most segments one hit shows. */
        const val SNIPPETS: Int = 3

        private const val SNIPPET_CHARS = 120
        private const val LEAD_CHARS = 40
        private const val ELLIPSIS = "…"

        /**
         * One character for one character — so a match in the folded text is at the same place in the
         * original: lower case, full-width Latin as ASCII, the ideographic space as a space, and the
         * accented Latin letters of Latin-1 and Latin Extended-A as their base letter.
         */
        internal fun fold(text: String): String = buildString(text.length) {
            for (c in text) {
                val lower = c.lowercaseChar()
                append(
                    when {
                        lower in '！'..'～' -> (lower.code - 0xFEE0).toChar().lowercaseChar()
                        lower == '　' -> ' '
                        else -> BASE[lower] ?: lower
                    },
                )
            }
        }

        private val BASE: Map<Char, Char> = buildMap {
            fun map(letters: String, base: Char) = letters.forEach { put(it, base) }
            map("àáâãäåāăą", 'a'); map("çćĉċč", 'c'); map("ďđ", 'd'); map("èéêëēĕėęě", 'e')
            map("ĝğġģ", 'g'); map("ĥħ", 'h'); map("ìíîïĩīĭįı", 'i'); map("ĵ", 'j'); map("ķ", 'k')
            map("ĺļľŀł", 'l'); map("ñńņň", 'n'); map("òóôõöøōŏő", 'o'); map("ŕŗř", 'r')
            map("śŝşš", 's'); map("ţťŧ", 't'); map("ùúûüũūŭůűų", 'u'); map("ŵ", 'w'); map("ýÿŷ", 'y'); map("źżž", 'z')
        }
    }
}
