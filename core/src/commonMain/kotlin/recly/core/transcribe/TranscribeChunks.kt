package recly.core.transcribe

import recly.core.job.StepFailure
import recly.core.message.CoreMessage
import recly.core.model.Part

/**
 * docs/08 "Length · size limits": a recording over what the provider takes goes as overlapping pieces of its
 * track, and the pieces' transcripts are put back together here. Pure: [TranscribeRunner] joins, submits and
 * keeps the pieces; this file only decides where they start and how their results meet.
 */
internal object TranscribeChunks {
    /** Each piece is planned to this share of every declared limit, so the joined file's container never tips it over. */
    private const val BUDGET_PERCENT = 90
    const val BUDGET = BUDGET_PERCENT / 100.0

    /** Words both pieces heard within this many seconds of each other are the same words. */
    private const val ALIGN_SEC = 1.0

    /** The shortest run of shared words a seam is put inside; anything shorter could be chance. */
    private const val MIN_RUN = 3

    /** How long two labels must speak at the same moment before they are taken for the same person. */
    private const val MIN_SHARED_SEC = 3.0

    /**
     * The part numbers of each piece, or null when [parts] fit [limits] as one file — the path every recording
     * within the limits keeps. Every piece after the first starts with the last part of the one before, a whole
     * part of overlap; the plan has the fewest pieces that fit [BUDGET], with the fullest of them as empty as
     * it can be. Fails with `UNSUPPORTED_AUDIO` only when no plan can: a part, or two parts in a row, over it.
     */
    fun plan(parts: List<Part>, limits: SttLimits, provider: String): List<List<Int>>? {
        val refused = exceeds(parts, limits, provider) ?: return null
        val spans = Spans(parts, limits)
        for (i in parts.indices) {
            if (spans.load(i, i) > 1.0) throw tooBig("$refused; part ${parts[i].part} alone is ${spans.describe(i, i)}")
        }
        for (i in 0 until parts.lastIndex) {
            if (spans.load(i, i + 1) > 1.0) {
                throw tooBig("$refused; parts ${parts[i].part} and ${parts[i + 1].part} together are ${spans.describe(i, i + 1)}")
            }
        }
        // fullest[e]: the fullest piece of the evenest plan so far whose last piece ends at part e. Every piece
        // holds at least two parts — its first is the one it shares with the piece before.
        val last = parts.lastIndex
        var fullest = DoubleArray(parts.size) { e -> if (e >= 1 && spans.load(0, e) <= 1.0) spans.load(0, e) else NONE }
        val starts = mutableListOf(IntArray(parts.size))
        while (fullest[last] == NONE) {
            val before = fullest
            val start = IntArray(parts.size)
            fullest = DoubleArray(parts.size) { e ->
                var best = NONE
                for (s in 1 until e) {
                    val piece = spans.load(s, e)
                    if (before[s] == NONE || piece > 1.0) continue
                    val candidate = maxOf(before[s], piece)
                    if (candidate < best) {
                        best = candidate
                        start[e] = s
                    }
                }
                best
            }
            starts += start
        }
        val pieces = ArrayDeque<List<Int>>()
        var end = last
        for (k in starts.indices.reversed()) {
            val start = if (k == 0) 0 else starts[k][end]
            pieces.addFirst((start..end).map { parts[it].part })
            end = start
        }
        return pieces.toList()
    }

    /**
     * The pieces' [results] as the one result a single file would have given: on the joined track's axis, in
     * one set of labels — what [TranscriptNormalizer] expects. [parts] are the track's parts in order and
     * [chunks] the plan's part numbers.
     */
    fun merge(parts: List<Part>, chunks: List<List<Int>>, results: List<SttResult>, speakersExpected: Int?): SttResult {
        // Each part's start on the joined axis: the parts before it, end to end.
        val starts = HashMap<Int, Double>()
        parts.fold(0.0) { at, part -> starts[part.part] = at; at + part.durationSec }
        var merged = shift(results.first().segments, starts.getValue(chunks.first().first()))
        for (c in 1 until chunks.size) {
            val shared = chunks[c].first()
            val from = starts.getValue(shared)
            val to = from + parts.first { it.part == shared }.durationSec
            val next = shift(results[c].segments, from)
            val (earlierSeam, laterSeam) = seam(merged, next, from, to)
            val kept = earlierSide(merged, earlierSeam)
            val joining = laterSide(next, laterSeam)
            val labels = speakers(merged, next, from, to, kept, joining, speakersExpected, c + 1)
            merged = kept + joining.map { it.copy(speaker = it.speaker?.let(labels::getValue)) }
        }
        return SttResult(
            segments = merged,
            language = results.firstNotNullOfOrNull { it.language },
            durationSec = parts.sumOf { it.durationSec },
            model = results.first().model,
        )
    }

    /**
     * Where the earlier piece hands over to the later one inside the part they share, [from] to [to], in each
     * piece's own timing. The words both heard at the same moment — the same text, lowercase and without
     * punctuation, starting within [ALIGN_SEC] — form runs; the seam goes in the middle of the widest pause
     * between consecutive words of the longest run, the quietest moment both agree on, which puts the same
     * words on the same side in both. A run shorter than [MIN_RUN] gives the earlier piece's widest pause in the
     * middle half of the part instead, and no pause there — no words at all — the middle of the part.
     */
    internal fun seam(earlier: List<SttSegment>, later: List<SttSegment>, from: Double, to: Double): Pair<Double, Double> {
        val a = words(earlier, from, to)
        val b = words(later, from, to)
        val run = longestRun(a, b)
        if (run != null && run.length >= MIN_RUN) {
            val t = (0 until run.length - 1).maxBy { minOf(pause(a, run.a + it), pause(b, run.b + it)) }
            return middle(a, run.a + t) to middle(b, run.b + t)
        }
        val quarter = (to - from) / 4
        val widest = (0 until a.lastIndex).filter { middle(a, it) in (from + quarter)..(to - quarter) }.maxByOrNull { pause(a, it) }
        val at = widest?.let { middle(a, it) } ?: ((from + to) / 2)
        return at to at
    }

    /** What the earlier piece keeps: what starts before [seam]. A segment running past it keeps its words before it. */
    internal fun earlierSide(segments: List<SttSegment>, seam: Double): List<SttSegment> =
        segments.mapNotNull { segment -> keep(segment) { it < seam } }

    /** What the later piece adds: what starts at [seam] or after. A segment running across it keeps its words from it on. */
    internal fun laterSide(segments: List<SttSegment>, seam: Double): List<SttSegment> =
        segments.mapNotNull { segment -> keep(segment) { it >= seam } }

    /**
     * The later piece's labels in the merged result's terms. Inside the shared part, [from] to [to], both pieces
     * heard the same people: every pair of labels is scored by how long the two speak at the same moment, and
     * the largest scores of at least [MIN_SHARED_SEC] pair up one to one. A label of [joining] left over is
     * someone new, under a label nobody else has — unless [expected] people are already in the result, when it
     * goes to the one it shares the most with (one not taken yet, among equals). [kept] is what the earlier
     * side keeps, [piece] the later piece's number.
     */
    internal fun speakers(
        earlier: List<SttSegment>,
        later: List<SttSegment>,
        from: Double,
        to: Double,
        kept: List<SttSegment>,
        joining: List<SttSegment>,
        expected: Int?,
        piece: Int,
    ): Map<String, String> {
        val shared = LinkedHashMap<Pair<String, String>, Double>()
        val mine = earlier.filter { it.speaker != null && it.end > from && it.start < to }
        val theirs = later.filter { it.speaker != null && it.end > from && it.start < to }
        for (x in mine) {
            for (y in theirs) {
                val both = minOf(x.end, y.end, to) - maxOf(x.start, y.start, from)
                val pair = x.speaker!! to y.speaker!!
                if (both > 0) shared[pair] = (shared[pair] ?: 0.0) + both
            }
        }
        val labels = LinkedHashMap<String, String>()
        val targets = HashSet<String>()
        shared.entries.filter { it.value >= MIN_SHARED_SEC }.sortedByDescending { it.value }.forEach { (pair, _) ->
            if (pair.second !in labels && pair.first !in targets) {
                labels[pair.second] = pair.first
                targets += pair.first
            }
        }
        val joiningLabels = joining.mapNotNull { it.speaker }.distinct()
        val present = kept.mapNotNullTo(LinkedHashSet()) { it.speaker }
        joiningLabels.forEach { label -> labels[label]?.let { present += it } }
        val taken = earlier.mapNotNullTo(HashSet()) { it.speaker }
        for (label in joiningLabels.filter { it !in labels }) {
            val target = if (expected == null || present.size < expected || present.isEmpty()) {
                var fresh = "$piece:$label"
                while (fresh in taken || fresh in labels.values) fresh += "'"
                fresh
            } else {
                present.maxWith(compareBy<String>({ shared[it to label] ?: 0.0 }, { it !in targets }))
            }
            labels[label] = target
            present += target
            targets += target
        }
        return labels
    }

    /** The headline today's check gives a file over [limits] — "2h 15m exceeds clova's 2h" — or null when it fits. */
    private fun exceeds(parts: List<Part>, limits: SttLimits, provider: String): String? {
        val bytes = parts.sumOf { it.bytes }
        limits.maxBytes?.let {
            if (bytes > it) return "${TranscribeRunner.megabytes(bytes)} exceeds $provider's ${TranscribeRunner.megabytes(it)}"
        }
        val seconds = parts.sumOf { it.durationSec }
        limits.maxDurationSec?.let {
            if (seconds > it) return "${TranscribeRunner.hoursMinutes(seconds)} exceeds $provider's ${TranscribeRunner.hoursMinutes(it)}"
        }
        return null
    }

    private fun tooBig(detail: String): StepFailure =
        StepFailure(retryable = false, reason = CoreMessage.UNSUPPORTED_AUDIO.code(detail = detail))

    /** Runs of consecutive parts against [BUDGET] of each declared limit: a load of 1.0 is a full piece. */
    private class Spans(parts: List<Part>, private val limits: SttLimits) {
        private val bytes = LongArray(parts.size + 1).also { for (i in parts.indices) it[i + 1] = it[i] + parts[i].bytes }
        private val seconds = DoubleArray(parts.size + 1).also { for (i in parts.indices) it[i + 1] = it[i] + parts[i].durationSec }

        private fun bytesLoad(from: Int, to: Int): Double =
            limits.maxBytes?.let { (bytes[to + 1] - bytes[from]) / (it * BUDGET) } ?: 0.0

        private fun secondsLoad(from: Int, to: Int): Double =
            limits.maxDurationSec?.let { (seconds[to + 1] - seconds[from]) / (it * BUDGET) } ?: 0.0

        fun load(from: Int, to: Int): Double = maxOf(bytesLoad(from, to), secondsLoad(from, to))

        /** "24 MB, over 90% of 25 MB": the run's value against the limit it goes over. */
        fun describe(from: Int, to: Int): String {
            val share = "$BUDGET_PERCENT%"
            return if (bytesLoad(from, to) > 1.0) {
                "${TranscribeRunner.megabytes(bytes[to + 1] - bytes[from])}, over $share of ${TranscribeRunner.megabytes(limits.maxBytes!!)}"
            } else {
                "${TranscribeRunner.hoursMinutes(seconds[to + 1] - seconds[from])}, over $share of ${TranscribeRunner.hoursMinutes(limits.maxDurationSec!!)}"
            }
        }
    }

    private const val NONE = Double.POSITIVE_INFINITY

    private fun shift(segments: List<SttSegment>, by: Double): List<SttSegment> = segments.map { segment ->
        segment.copy(
            start = segment.start + by,
            end = segment.end + by,
            words = segment.words?.map { it.copy(start = it.start + by, end = it.end + by) },
        )
    }

    /** [segment] as far as [keeps] says of start times: by its words when it has them, else by its own start. */
    private fun keep(segment: SttSegment, keeps: (Double) -> Boolean): SttSegment? {
        val words = segment.words
        if (words.isNullOrEmpty()) return segment.takeIf { keeps(it.start) }
        val staying = words.filter { keeps(it.start) }
        if (staying.size == words.size) return segment
        if (staying.isEmpty()) return null
        // A segment written without spaces (Japanese, Chinese) is joined back the same way.
        val separator = if (' ' in segment.text.trim()) " " else ""
        return segment.copy(
            start = if (staying.first() === words.first()) segment.start else staying.first().start,
            end = if (staying.last() === words.last()) segment.end else staying.last().end,
            text = staying.map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString(separator),
            words = staying,
        )
    }

    private class Word(val start: Double, val end: Double, val key: String)

    /** The words of [segments] that start inside [from]..[to], in time order, with the text they are compared by. */
    private fun words(segments: List<SttSegment>, from: Double, to: Double): List<Word> =
        segments.flatMap { it.words.orEmpty() }
            .filter { it.start >= from && it.start < to }
            .map { Word(it.start, it.end, it.text.lowercase().filter(Char::isLetterOrDigit)) }
            .filter { it.key.isNotEmpty() }
            .sortedBy { it.start }

    private fun pause(words: List<Word>, after: Int): Double = words[after + 1].start - words[after].end

    private fun middle(words: List<Word>, after: Int): Double = (words[after].end + words[after + 1].start) / 2

    private class Run(val a: Int, val b: Int, val length: Int)

    /** The longest run of words [a] and [b] share in order, each pair at the same moment; null when none is. */
    private fun longestRun(a: List<Word>, b: List<Word>): Run? {
        var previous = IntArray(b.size + 1)
        var current = IntArray(b.size + 1)
        var best: Run? = null
        var low = 0
        for (i in a.indices) {
            current.fill(0)
            while (low < b.size && b[low].start < a[i].start - ALIGN_SEC) low++
            var j = low
            while (j < b.size && b[j].start <= a[i].start + ALIGN_SEC) {
                if (a[i].key == b[j].key) {
                    val length = previous[j] + 1
                    current[j + 1] = length
                    if (length > (best?.length ?: 0)) best = Run(i - length + 1, j - length + 1, length)
                }
                j++
            }
            val swap = previous
            previous = current
            current = swap
        }
        return best
    }
}
