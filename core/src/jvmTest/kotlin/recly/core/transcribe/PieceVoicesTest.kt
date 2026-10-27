package recly.core.transcribe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import recly.core.model.Part
import recly.core.model.Track

/**
 * docs/08 "Length · size limits": a label the shared part paired with no one is matched by voice to a label of
 * the pieces before it — which voices are asked for, from where in the part files, and how the answer is used.
 */
class PieceVoicesTest {
    private val dir = "/rec".toPath()
    private val twoPieces = listOf(listOf(1, 2), listOf(2, 3))

    /**
     * Parts 1–2 and 2–3, the seam in the middle of part 2 (1350 on the joined axis). A and B speak in piece 1; X
     * is A in the shared part; [late] speak only after it, on piece 2's own axis.
     */
    private fun pieces(vararg late: SttSegment, extra: List<SttSegment> = emptyList()): List<SttResult> = listOf(
        result(
            listOf(
                segment("A", 100.0, 110.0, "a one"),
                segment("B", 200.0, 212.0, "b one"),
            ) + extra + segment("A", 1000.0, 1020.0, "shared"),
        ),
        result(listOf(segment("X", 100.0, 120.0, "shared")) + late),
    )

    private fun merge(results: List<SttResult>, voices: Map<String, FloatArray>?, expected: Int? = null, chunks: List<List<Int>> = twoPieces) =
        TranscribeChunks.merge(parts(chunks.last().last()), chunks, results, expected, voices).segments.map { it.speaker }

    @Test
    fun `a label the shared part did not pair joins the earlier label its voice matches`() {
        val results = pieces(segment("Y", 1000.0, 1012.0, "b again"))

        // The voices asked for: Y, and every label of the piece before — not X, which the shared part paired.
        assertEquals(setOf("1:A", "1:B", "2:Y"), voiceSpans(results).keys)
        val voices = mapOf("1:A" to v(1f, 0f, 0f), "1:B" to v(0f, 1f, 0f), "2:Y" to v(0.1f, 0.9f, 0f))

        assertEquals(listOf("A", "B", "A", "B"), merge(results, voices))
        assertEquals(listOf("A", "B", "A", "2:Y"), merge(results, voices = null), "without voices, as before")
    }

    @Test
    fun `a voice below the threshold stays someone new`() {
        val results = pieces(segment("Y", 1000.0, 1012.0, "someone"))
        // Cosine 0.49 with B.
        val voices = mapOf("1:A" to v(1f, 0f, 0f), "1:B" to v(0f, 1f, 0f), "2:Y" to v(0f, 0.5f, 0.9f))

        assertEquals(listOf("A", "B", "A", "2:Y"), merge(results, voices))
    }

    @Test
    fun `a label never joins one its own piece already has`() {
        val results = pieces(segment("Y", 1000.0, 1012.0, "sounds like A"))
        // Y sounds like A, but X is A in this piece: two labels of one piece are two people.
        val voices = mapOf("1:A" to v(1f, 0f, 0f), "1:B" to v(0f, 1f, 0f), "2:Y" to v(1f, 0.05f, 0f))

        assertEquals(listOf("A", "B", "A", "2:Y"), merge(results, voices))
    }

    @Test
    fun `two labels of one piece never join the same earlier label`() {
        val results = pieces(segment("Y", 1000.0, 1012.0, "b"), segment("Z", 1100.0, 1112.0, "also b"))
        // Both sound like B; the closer one gets B and the other stays new.
        val voices = mapOf("1:A" to v(1f, 0f, 0f), "1:B" to v(0f, 1f, 0f), "2:Y" to v(0.3f, 1f, 0f), "2:Z" to v(0.1f, 1f, 0f))

        assertEquals(listOf("A", "B", "A", "2:Y", "B"), merge(results, voices))
    }

    @Test
    fun `the expected count still holds after the voices`() {
        val results = pieces(segment("Y", 1000.0, 1012.0, "c again"), extra = listOf(segment("C", 300.0, 312.0, "c one")))
        val sounds = { y: FloatArray -> mapOf("1:A" to v(1f, 0f, 0f), "1:B" to v(0f, 1f, 0f), "1:C" to v(0f, 0f, 1f), "2:Y" to y) }

        // Three people in the room, all heard: Y's voice says C, where the count alone would have said B.
        assertEquals(listOf("A", "B", "C", "A", "C"), merge(results, sounds(v(0.1f, 0f, 1f)), expected = 3))
        assertEquals(listOf("A", "B", "C", "A", "B"), merge(results, voices = null, expected = 3))
        // A voice like no one's: the count still keeps Y among the three; with room for a fourth, Y is new.
        assertEquals(listOf("A", "B", "C", "A", "B"), merge(results, sounds(v(-1f, 0f, 0f)), expected = 3))
        assertEquals(listOf("A", "B", "C", "A", "2:Y"), merge(results, sounds(v(-1f, 0f, 0f)), expected = 4))
    }

    @Test
    fun `a label or a candidate without a voice pairs as before`() {
        val results = pieces(segment("Y", 1000.0, 1012.0, "b again"))

        assertEquals(listOf("A", "B", "A", "2:Y"), merge(results, mapOf("1:A" to v(1f, 0f, 0f), "1:B" to v(0f, 1f, 0f))))
        assertEquals(listOf("A", "B", "A", "2:Y"), merge(results, mapOf("1:A" to v(1f, 0f, 0f), "2:Y" to v(0f, 1f, 0f))))
        assertEquals(listOf("A", "B", "A", "2:Y"), merge(results, emptyMap()))
    }

    @Test
    fun `someone absent from a whole piece is found again in the next one`() {
        // Parts 1–2, 2–3, 3–4. A speaks in part 1 only and comes back in part 4 as W; B is P, then Q.
        val chunks = listOf(listOf(1, 2), listOf(2, 3), listOf(3, 4))
        val results = listOf(
            result(listOf(segment("A", 100.0, 115.0, "a"), segment("B", 1000.0, 1020.0, "b in part 2"))),
            result(listOf(segment("P", 100.0, 120.0, "b in part 2"), segment("P", 1000.0, 1020.0, "b in part 3"))),
            result(listOf(segment("Q", 100.0, 120.0, "b in part 3"), segment("W", 1000.0, 1015.0, "a again"))),
        )

        // Piece 2 left no one over: the voices of pieces 1 and 2, and W's.
        assertEquals(setOf("1:A", "1:B", "2:P", "3:W"), voiceSpans(results, chunks).keys)
        val voices = mapOf("1:A" to v(1f, 0f), "1:B" to v(0f, 1f), "2:P" to v(0.1f, 1f), "3:W" to v(0.95f, 0.1f))

        assertEquals(listOf("A", "B", "B", "A"), merge(results, voices, chunks = chunks))
        assertEquals(listOf("A", "B", "B", "3:W"), merge(results, voices = null, chunks = chunks))
    }

    @Test
    fun `nothing is asked when every label paired up`() {
        assertEquals(emptyMap(), voiceSpans(pieces(segment("X", 1000.0, 1012.0, "a later"))))
    }

    @Test
    fun `spans are the longer segments trimmed, capped and cut where a part ends`() {
        val spans = TranscribeChunks.spans(
            listOf(
                segment("A", 10.0, 11.4, "too short"),
                segment("A", 20.0, 21.5, "just long enough"),
                segment("A", 100.0, 130.0, "a long one"),
                segment("A", 895.0, 905.0, "across the end of part 1"),
                segment("A", 899.5, 902.0, "barely in part 1"),
            ),
            parts(3),
            dir,
        )

        assertEquals(
            listOf(
                AudioSpan(dir / "part1.m4a", 20.25, 21.25),
                // Ten seconds of it at most.
                AudioSpan(dir / "part1.m4a", 100.25, 110.25),
                // The joined axis has no gap between parts: 900 is part 2's 0.
                AudioSpan(dir / "part1.m4a", 895.25, 900.0),
                AudioSpan(dir / "part2.m4a", 0.0, 4.75),
                // A quarter second left in part 1 is too short to keep.
                AudioSpan(dir / "part2.m4a", 0.0, 1.75),
            ),
            spans,
        )
    }

    @Test
    fun `a voice is at most a minute, taken evenly through its time`() {
        // Twenty ten-second spans, 100 s apart.
        val segments = (0 until 20).map { segment("A", it * 100.0, it * 100.0 + 11.0, "line $it") }

        val spans = TranscribeChunks.spans(segments, parts(3), dir)

        assertEquals(60.0, spans.sumOf { it.endSec - it.startSec }, 1e-9)
        assertEquals(
            listOf(
                AudioSpan(dir / "part1.m4a", 100.25, 110.25),
                AudioSpan(dir / "part1.m4a", 500.25, 510.25),
                AudioSpan(dir / "part1.m4a", 800.25, 810.25),
                AudioSpan(dir / "part2.m4a", 200.25, 210.25),
                AudioSpan(dir / "part2.m4a", 600.25, 610.25),
                AudioSpan(dir / "part3.m4a", 0.25, 10.25),
            ),
            spans,
        )
    }

    @Test
    fun `the spans asked for are where the merge keeps each voice`() {
        val results = pieces(segment("Y", 1000.0, 1012.0, "b again"))

        val spans = voiceSpans(results)

        // A's segment in the shared part is piece 1's, before the seam; Y is at 1900 on the joined axis, part 3's 100.
        assertEquals(listOf(AudioSpan(dir / "part1.m4a", 100.25, 109.75), AudioSpan(dir / "part2.m4a", 100.25, 110.25)), spans["1:A"])
        assertEquals(listOf(AudioSpan(dir / "part3.m4a", 100.25, 110.25)), spans["2:Y"])
        assertTrue(spans.values.flatten().all { it.endSec - it.startSec >= 1.0 })
    }

    @Test
    fun `a centroid is the mean of unit embeddings`() {
        val centroid = SpeakerTurns.centroid(listOf(v(3f, 0f), v(0f, 0.5f)))!!

        assertEquals(listOf(0.5f, 0.5f), centroid.toList())
        assertEquals(null, SpeakerTurns.centroid(emptyList()))
    }

    private fun voiceSpans(results: List<SttResult>, chunks: List<List<Int>> = twoPieces, expected: Int? = null) =
        TranscribeChunks.voiceSpans(parts(chunks.last().last()), chunks, results, expected, dir)

    private fun v(vararg values: Float) = values

    private fun parts(count: Int): List<Part> = (1..count).map { number ->
        Part(
            part = number,
            track = Track.MONO,
            file = "part$number.m4a",
            bytes = 16,
            sha256 = "0".repeat(64),
            startOffsetSec = (number - 1) * 905.0,
            durationSec = 900.0,
        )
    }

    private fun segment(speaker: String?, start: Double, end: Double, text: String) = SttSegment(start, end, speaker, text)

    private fun result(segments: List<SttSegment>) = SttResult(segments, "en", null, null)
}
