package recly.core.transcribe

import kotlin.test.Test
import kotlin.test.assertEquals

/** docs/08 "Speaker diarization on the device": the merging rules every shell shares. */
class SpeakerTurnsTest {
    private fun segment(start: Double, end: Double) = SttSegment(start, end, null, "t")

    private fun labels(segments: List<SttSegment>, turns: List<SpeakerTurn>) =
        SpeakerTurns.assign(segments, turns).map { it.speaker }

    @Test
    fun `a segment takes the speaker it overlaps longest`() {
        val turns = listOf(SpeakerTurn(0.0, 4.0, "0"), SpeakerTurn(4.0, 10.0, "1"))

        assertEquals(listOf("0", "1", "1"), labels(listOf(segment(0.0, 3.0), segment(3.0, 9.0), segment(9.5, 10.0)), turns))
    }

    @Test
    fun `with no overlap the nearest turn within a second names it, else the previous segment does`() {
        val turns = listOf(SpeakerTurn(0.0, 2.0, "A"), SpeakerTurn(10.0, 12.0, "B"))

        assertEquals(
            listOf("A", "B", "B"),
            labels(listOf(segment(0.0, 1.0), segment(8.5, 9.5), segment(20.0, 21.0)), turns),
        )
    }

    @Test
    fun `the first segment with nothing near takes the next one's speaker`() {
        val turns = listOf(SpeakerTurn(10.0, 12.0, "B"))

        assertEquals(listOf("B", "B"), labels(listOf(segment(0.0, 1.0), segment(10.0, 11.0)), turns))
        assertEquals(listOf("B"), labels(listOf(segment(0.0, 1.0)), turns), "even when no segment is near any turn")
    }

    @Test
    fun `a tie goes to the previous segment's speaker`() {
        val turns = listOf(SpeakerTurn(0.0, 2.0, "A"), SpeakerTurn(2.0, 4.0, "B"), SpeakerTurn(4.0, 6.0, "A"))

        assertEquals(listOf("B", "B"), labels(listOf(segment(2.0, 3.0), segment(3.0, 5.0)), turns))
    }

    @Test
    fun `no turns leaves the segments alone`() {
        val segments = listOf(segment(0.0, 1.0))

        assertEquals(segments, SpeakerTurns.assign(segments, emptyList()))
    }

    @Test
    fun `a piece of speech is cut where the speaker changes`() {
        val turns = listOf(SpeakerTurn(0.0, 5.0, "0"), SpeakerTurn(5.0, 12.0, "1"), SpeakerTurn(12.0, 20.0, "0"))

        assertEquals(
            listOf(SpeakerTurn(2.0, 5.0, "0"), SpeakerTurn(5.0, 12.0, "1"), SpeakerTurn(12.0, 15.0, "0")),
            SpeakerTurns.split(2.0, 15.0, turns),
        )
    }

    @Test
    fun `a sliver under a second joins its longer neighbour, and a gap stays with the piece before it`() {
        val turns = listOf(SpeakerTurn(0.0, 6.0, "0"), SpeakerTurn(6.0, 6.5, "1"), SpeakerTurn(6.5, 9.0, "2"), SpeakerTurn(10.0, 14.0, "2"))

        assertEquals(
            listOf(SpeakerTurn(0.0, 6.0, "0"), SpeakerTurn(6.0, 14.0, "2")),
            SpeakerTurns.split(0.0, 14.0, turns),
        )
    }

    @Test
    fun `speech before the first turn belongs to the speaker after it, and no turn at all is one unlabelled piece`() {
        assertEquals(listOf(SpeakerTurn(0.0, 8.0, "1")), SpeakerTurns.split(0.0, 8.0, listOf(SpeakerTurn(3.0, 8.0, "1"))))
        assertEquals(listOf(SpeakerTurn(0.0, 8.0, "")), SpeakerTurns.split(0.0, 8.0, emptyList()))
    }

    @Test
    fun `speakers of separate blocks are linked by their embeddings`() {
        val alice = floatArrayOf(1f, 0f, 0f)
        val bob = floatArrayOf(0f, 1f, 0f)
        val carol = floatArrayOf(0f, 0f, 1f)

        val linked = SpeakerTurns.link(
            listOf(
                listOf(alice, bob),
                listOf(floatArrayOf(0.1f, 0.95f, 0f), floatArrayOf(0.95f, 0.05f, 0f), carol),
                listOf(floatArrayOf(0f, 0.1f, 0.9f)),
            ),
        )

        assertEquals(listOf(listOf(0, 1), listOf(1, 0, 2), listOf(2)), linked)
    }

    @Test
    fun `two speakers of one block are never made one, and a weak match is a new speaker`() {
        val alice = floatArrayOf(1f, 0f)

        val linked = SpeakerTurns.link(listOf(listOf(alice), listOf(floatArrayOf(1f, 0.05f), floatArrayOf(0.9f, 0.1f)), listOf(floatArrayOf(0f, 1f))))

        assertEquals(listOf(listOf(0), listOf(0, 1), listOf(2)), linked)
    }
}
