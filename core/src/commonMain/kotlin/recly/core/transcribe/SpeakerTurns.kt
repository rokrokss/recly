package recly.core.transcribe

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One turn an on-device diarizer reported: [label] spoke from [start] to [end], seconds on the input's own axis. */
data class SpeakerTurn(val start: Double, val end: Double, val label: String)

/**
 * docs/10 "Shared rules for the shells": how a diarizer's turns meet the transcription, the same on
 * every shell. The labels stay the diarizer's own (`"0"`, `"S1"`, …); the core renames them `S1, S2, …`
 * in order of appearance when it writes the transcript, and marks it identified only when every segment
 * has one — which is why nothing here leaves a segment without a label while there are turns at all.
 */
object SpeakerTurns {
    /** How far from a segment a turn may be and still name its speaker, when none overlaps it. */
    const val NEAREST_SEC: Double = 1.0

    /** Pieces shorter than this go to their longer neighbour: they decode badly and diarizers are least sure of them. */
    const val MIN_PIECE_SEC: Double = 1.0

    /**
     * The cosine similarity two blocks' speakers must reach to be one person ([link]). Recly's own
     * starting value, to be tuned on real recordings — not a figure any model publishes.
     */
    const val LINK_THRESHOLD: Float = 0.6f

    /**
     * [segments] with a speaker each, from [turns]: the label that overlaps a segment longest; with no
     * overlap, the nearest turn within [nearestSec]; failing that, the previous segment's (the next one's,
     * for the first). A tie goes to the previous segment's speaker, so a short tie does not flicker. With
     * no turns at all, the segments come back as they were.
     */
    fun assign(segments: List<SttSegment>, turns: List<SpeakerTurn>, nearestSec: Double = NEAREST_SEC): List<SttSegment> {
        if (turns.isEmpty() || segments.isEmpty()) return segments
        val labels = arrayOfNulls<String>(segments.size)
        segments.forEachIndexed { index, segment ->
            labels[index] = pick(segment.start, segment.end, turns, nearestSec, previous = labels.getOrNull(index - 1))
        }
        val first = labels.firstOrNull { it != null }
            ?: turns.minBy { gap(segments.first().start, segments.first().end, it) }.label
        var last = first
        return segments.mapIndexed { index, segment ->
            last = labels[index] ?: last
            segment.copy(speaker = last)
        }
    }

    /**
     * The time [start]–[end] of one piece of speech cut where the speaker changes — for an engine that
     * diarizes first and decodes each single-speaker piece on its own. Pieces cover the whole range in
     * order; one shorter than [minSec] joins its longer neighbour, and a stretch no turn covers stays with
     * the piece before it. With no turn near the range the whole range is one piece, labelled "".
     */
    fun split(start: Double, end: Double, turns: List<SpeakerTurn>, minSec: Double = MIN_PIECE_SEC): List<SpeakerTurn> {
        if (end <= start) return listOf(SpeakerTurn(start, end, pick(start, end, turns, NEAREST_SEC, null).orEmpty()))
        val cuts = (turns.flatMap { listOf(it.start, it.end) }.filter { it > start && it < end } + start + end).distinct().sorted()
        val pieces = mutableListOf<SpeakerTurn>()
        for ((from, to) in cuts.zipWithNext()) {
            val covering = dominant(from, to, turns, previous = pieces.lastOrNull()?.label)
            val label = covering ?: pieces.lastOrNull()?.label
            if (label != null && pieces.lastOrNull()?.label == label) {
                pieces[pieces.lastIndex] = pieces.last().copy(end = to)
            } else {
                pieces += SpeakerTurn(from, to, label ?: "")
            }
        }
        // A stretch before the first turn took no label: it belongs to the speaker after it.
        if (pieces.size > 1 && pieces.first().label.isEmpty()) {
            pieces[1] = pieces[1].copy(start = pieces[0].start)
            pieces.removeAt(0)
        }
        if (pieces.size == 1 && pieces[0].label.isEmpty()) {
            pieces[0] = pieces[0].copy(label = pick(start, end, turns, NEAREST_SEC, null).orEmpty())
        }
        while (pieces.size > 1) {
            val shortest = pieces.indices.minBy { pieces[it].end - pieces[it].start }
            if (pieces[shortest].end - pieces[shortest].start >= minSec) break
            val before = pieces.getOrNull(shortest - 1)
            val after = pieces.getOrNull(shortest + 1)
            val into = when {
                before == null -> shortest + 1
                after == null -> shortest - 1
                before.end - before.start >= after.end - after.start -> shortest - 1
                else -> shortest + 1
            }
            val merged = SpeakerTurn(min(pieces[into].start, pieces[shortest].start), max(pieces[into].end, pieces[shortest].end), pieces[into].label)
            pieces[into] = merged
            pieces.removeAt(shortest)
            // Two neighbours that now say the same speaker are one piece.
            var i = 1
            while (i < pieces.size) {
                if (pieces[i].label == pieces[i - 1].label) {
                    pieces[i - 1] = pieces[i - 1].copy(end = pieces[i].end)
                    pieces.removeAt(i)
                } else {
                    i++
                }
            }
        }
        return pieces
    }

    /**
     * Speakers across blocks of a long recording diarized 10–20 minutes at a time: [blocks] holds, for
     * each block in order, the centroid embedding of each of its speakers (index = the block's own
     * speaker number). The answer has the same shape, with one recording-wide speaker number for each.
     *
     * Greedy and in order: within a block the most similar pair of block speaker and recording speaker
     * is matched first, as long as their cosine similarity reaches [threshold]; a block speaker left
     * unmatched is a new recording speaker. A matched recording speaker's centroid takes the block's in.
     * Two speakers of one block are never made one.
     */
    fun link(blocks: List<List<FloatArray>>, threshold: Float = LINK_THRESHOLD): List<List<Int>> {
        val sums = mutableListOf<FloatArray>()
        return blocks.map { block ->
            val ids = IntArray(block.size) { -1 }
            val taken = mutableSetOf<Int>()
            val pairs = block.indices.flatMap { local ->
                sums.indices.map { global -> Triple(local, global, cosine(block[local], sums[global])) }
            }.filter { it.third >= threshold }.sortedByDescending { it.third }
            for ((local, global, _) in pairs) {
                if (ids[local] != -1 || global in taken) continue
                ids[local] = global
                taken += global
            }
            block.indices.forEach { local ->
                if (ids[local] == -1) {
                    ids[local] = sums.size
                    sums += FloatArray(block[local].size)
                }
                val sum = sums[ids[local]]
                val unit = normalized(block[local])
                for (i in sum.indices) sum[i] += unit.getOrElse(i) { 0f }
            }
            ids.toList()
        }
    }

    /** The best label for [start]–[end]: longest overlap, else the nearest turn within [nearestSec]. */
    private fun pick(start: Double, end: Double, turns: List<SpeakerTurn>, nearestSec: Double, previous: String?): String? =
        dominant(start, end, turns, previous)
            ?: turns.filter { gap(start, end, it) <= nearestSec }
                .let { near -> near.minOfOrNull { gap(start, end, it) }?.let { best -> near.filter { gap(start, end, it) == best } } }
                ?.let { closest -> closest.firstOrNull { it.label == previous }?.label ?: closest.first().label }

    /** The label that overlaps [start]–[end] longest, a tie going to [previous]; null when none overlaps. */
    private fun dominant(start: Double, end: Double, turns: List<SpeakerTurn>, previous: String?): String? {
        val overlap = LinkedHashMap<String, Double>()
        for (turn in turns) {
            val shared = min(end, turn.end) - max(start, turn.start)
            if (shared > 0) overlap[turn.label] = (overlap[turn.label] ?: 0.0) + shared
        }
        val best = overlap.values.maxOrNull() ?: return null
        val tied = overlap.filterValues { best - it < EPSILON }.keys
        return if (previous in tied) previous else tied.first()
    }

    /** How far apart a range and a turn are; zero or less when they touch or overlap. */
    private fun gap(start: Double, end: Double, turn: SpeakerTurn): Double = max(turn.start - end, start - turn.end)

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var aa = 0f
        var bb = 0f
        for (i in 0 until min(a.size, b.size)) {
            dot += a[i] * b[i]
            aa += a[i] * a[i]
            bb += b[i] * b[i]
        }
        return if (aa == 0f || bb == 0f) 0f else dot / (sqrt(aa) * sqrt(bb))
    }

    private fun normalized(v: FloatArray): FloatArray {
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return if (norm == 0f) v else FloatArray(v.size) { v[it] / norm }
    }

    private const val EPSILON = 1e-9
}
