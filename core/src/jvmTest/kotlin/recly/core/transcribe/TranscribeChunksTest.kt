package recly.core.transcribe

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import recly.core.job.StepFailure
import recly.core.model.Part
import recly.core.model.Track

/** docs/08 "Length · size limits": where the pieces of a long recording start, and how they meet again. */
class TranscribeChunksTest {
    private val clova = SttLimits(maxDurationSec = 2 * 3600.0)
    private val openai = SttLimits(maxBytes = 26_214_400)

    @Test
    fun `a recording within the limits is not planned at all`() {
        // Eight parts are clova's two hours to the second: the one-file path, as before.
        assertNull(TranscribeChunks.plan(parts(8), clova, "clova"))
        // A provider that declares nothing never has a plan, however long the recording.
        assertNull(TranscribeChunks.plan(parts(40), SttLimits(), "assemblyai"))
    }

    @Test
    fun `three hours on clova go as two pieces that share a whole part`() {
        // 90% of two hours holds seven parts; twelve need two pieces, the second starting with the first's last.
        val plan = TranscribeChunks.plan(parts(12), clova, "clova")

        assertEquals(listOf((1..6).toList(), (6..12).toList()), plan)
    }

    @Test
    fun `a byte limit is planned by bytes`() {
        // 3.6 MB parts against 90% of OpenAI's 25 MB: six fit a piece, so twelve parts take three.
        val plan = TranscribeChunks.plan(parts(12, bytes = 3_600_000), openai, "openai")!!

        assertEquals(listOf((1..4).toList(), (4..8).toList(), (8..12).toList()), plan)
        plan.zipWithNext { a, b -> assertEquals(a.last(), b.first(), "each piece starts with the last part of the one before") }
        plan.forEach { piece -> assertTrue(piece.size * 3_600_000 <= 26_214_400 * 0.9) }
    }

    @Test
    fun `the pieces are as even as the fewest of them can be`() {
        // Nine parts: two pieces, five and five, rather than seven and three.
        assertEquals(listOf((1..5).toList(), (5..9).toList()), TranscribeChunks.plan(parts(9), clova, "clova"))
    }

    @Test
    fun `a limit too small for two parts fails without a plan`() {
        val tiny = SttLimits(maxDurationSec = 25 * 60.0)

        val failure = assertFailsWith<StepFailure> { TranscribeChunks.plan(parts(3), tiny, "tiny") }

        assertContains(failure.reason, "UNSUPPORTED_AUDIO")
        assertContains(failure.reason, "45m exceeds tiny's 25m; parts 1 and 2 together are 30m, over 90% of 25m")
        assertFalse(failure.retryable)
    }

    @Test
    fun `one part over the budget fails without a plan`() {
        val failure = assertFailsWith<StepFailure> {
            TranscribeChunks.plan(parts(3, bytes = 3_600_000), SttLimits(maxBytes = 3_000_000), "small")
        }

        assertContains(failure.reason, "UNSUPPORTED_AUDIO")
        assertContains(failure.reason, "10.3 MB exceeds small's 2.9 MB; part 1 alone is 3.4 MB, over 90% of 2.9 MB")
    }

    @Test
    fun `the seam goes in the widest pause of the longest run both pieces heard`() {
        // Parts 1–2 and 2–3: part 2, 900..1800 on the joined axis, is the shared one.
        val first = result(
            segment("A", 100.0, 110.0, "good morning"),
            segment(
                "A", 1200.0, 1204.5, "we agreed on the budget today",
                word(1200.0, 1200.4, "we"), word(1200.5, 1201.0, "agreed"), word(1201.1, 1201.3, "on"),
                word(1201.4, 1201.5, "the"), word(1201.6, 1202.2, "budget"), word(1204.0, 1204.5, "today"),
            ),
            segment("B", 1205.0, 1212.0, "yes, great"),
        )
        // The second piece heard the same words 0.2 s later, cased and punctuated its own way, and cut its
        // segments elsewhere.
        val second = result(
            segment(
                "X", 300.2, 304.7, "We agreed on the budget today.",
                word(300.2, 300.6, "We"), word(300.7, 301.2, "agreed"), word(301.3, 301.5, "on"),
                word(301.6, 301.7, "the"), word(301.8, 302.4, "budget"), word(304.2, 304.7, "today."),
            ),
            segment("Y", 305.2, 312.2, "yes great"),
            segment("X", 1000.0, 1005.0, "see you"),
        )

        val merged = TranscribeChunks.merge(parts(3), listOf(listOf(1, 2), listOf(2, 3)), listOf(first, second), null)

        // Budget → today is the widest pause (1.8 s): the first piece keeps "budget", the second gives "today".
        assertEquals(
            listOf("good morning", "we agreed on the budget", "today.", "yes great", "see you"),
            merged.segments.map { it.text },
        )
        assertEquals(listOf("A", "A", "A", "B", "A"), merged.segments.map { it.speaker })
        // The cut segment ends with its last kept word; the later piece's starts with its first, on the joined axis.
        assertEquals(1202.2, merged.segments[1].end)
        assertEquals(1204.2, merged.segments[2].start, 1e-9)
        assertEquals(1900.0, merged.segments[4].start, 1e-9)
        assertEquals(2700.0, merged.durationSec)
    }

    @Test
    fun `a run too short to trust gives the earlier piece's widest pause in the middle half`() {
        // The middle half of part 2 is 1125..1575. The pause from 1400 to 1790 is wider, but its middle is
        // outside that, so the seam is the middle of the 1200..1400 one.
        val first = result(
            segment("A", 905.0, 905.5, "zero", word(905.0, 905.5, "zero")),
            segment("A", 1000.0, 1001.5, "one two", word(1000.0, 1000.5, "one"), word(1001.0, 1001.5, "two")),
            segment("A", 1200.0, 1200.5, "three", word(1200.0, 1200.5, "three")),
            segment("A", 1400.0, 1400.5, "four", word(1400.0, 1400.5, "four")),
            segment("A", 1790.0, 1790.5, "five", word(1790.0, 1790.5, "five")),
        )
        val second = result(
            segment("A", 300.0, 300.5, "uno", word(300.0, 300.5, "uno")),
            segment("A", 500.0, 500.5, "dos", word(500.0, 500.5, "dos")),
            segment("A", 890.0, 890.5, "tres", word(890.0, 890.5, "tres")),
        )

        val seam = TranscribeChunks.seam(first.segments, shift(second, 900.0), 900.0, 1800.0)

        assertEquals(1300.25 to 1300.25, seam)
        val merged = TranscribeChunks.merge(parts(3), listOf(listOf(1, 2), listOf(2, 3)), listOf(first, second), null)
        assertEquals(listOf("zero", "one two", "three", "dos", "tres"), merged.segments.map { it.text })
    }

    @Test
    fun `segment-only results are cut at the middle of the shared part by where each segment starts`() {
        val first = result(
            segment("A", 1000.0, 1010.0, "one"),
            segment("A", 1340.0, 1360.0, "two"),
            segment("A", 1400.0, 1410.0, "three"),
        )
        val second = result(
            segment("X", 101.0, 111.0, "one"),
            segment("X", 449.0, 460.0, "two"),
            segment("X", 452.0, 460.0, "two, later"),
            segment("X", 501.0, 511.0, "three"),
        )

        val merged = TranscribeChunks.merge(parts(3), listOf(listOf(1, 2), listOf(2, 3)), listOf(first, second), null)

        // The middle is 1350: "two" started before it on the first piece and stays whole; the second piece's
        // own "two" started before it too and goes.
        assertEquals(listOf("one", "two", "two, later", "three"), merged.segments.map { it.text })
        assertEquals(listOf("A", "A", "A", "A"), merged.segments.map { it.speaker })
    }

    @Test
    fun `a segment across the seam is split by its words, its text rebuilt from them`() {
        val spaced = segment(
            "A", 10.0, 14.0, "see you next week",
            word(10.0, 10.4, "see"), word(10.5, 10.9, "you"), word(12.0, 12.4, "next"), word(12.5, 14.0, "week"),
        )
        // Japanese is written without spaces, and is joined back without them.
        val unspaced = segment(
            "A", 20.0, 24.0, "今日は会議です",
            word(20.0, 20.8, "今日"), word(20.9, 21.2, "は"), word(22.0, 22.8, "会議"), word(22.9, 24.0, "です"),
        )

        val earlier = TranscribeChunks.earlierSide(listOf(spaced, unspaced), 11.5)
        val later = TranscribeChunks.laterSide(listOf(spaced, unspaced), 21.5)

        assertEquals(listOf(SttSegment(10.0, 10.9, "A", "see you", spaced.words!!.take(2))), earlier)
        assertEquals(listOf(SttSegment(22.0, 24.0, "A", "会議です", unspaced.words!!.drop(2))), later)
        // Wholly on one side, a segment is kept as the provider wrote it.
        assertEquals(listOf(spaced, unspaced), TranscribeChunks.earlierSide(listOf(spaced, unspaced), 30.0))
    }

    @Test
    fun `labels swapped between the pieces follow who speaks at the same moment`() {
        val first = result(
            segment("A", 1000.0, 1020.0, "first speaker"),
            segment("B", 1100.0, 1120.0, "second speaker"),
        )
        // The second piece calls the same two people the other way round.
        val second = result(
            segment("B", 100.0, 120.0, "first speaker"),
            segment("A", 200.0, 220.0, "second speaker"),
            segment("B", 1000.0, 1010.0, "first speaker again"),
            segment("A", 1100.0, 1110.0, "second speaker again"),
        )

        val merged = TranscribeChunks.merge(parts(3), listOf(listOf(1, 2), listOf(2, 3)), listOf(first, second), null)

        assertEquals(
            listOf("first speaker" to "A", "second speaker" to "B", "first speaker again" to "A", "second speaker again" to "B"),
            merged.segments.map { it.text to it.speaker },
        )
    }

    @Test
    fun `a new speaker in the later piece gets a label nobody has`() {
        val first = result(segment("A", 1000.0, 1020.0, "hello"))
        val second = result(
            segment("1", 100.0, 120.0, "hello"),
            segment("2", 1000.0, 1010.0, "I just came in"),
        )

        val merged = TranscribeChunks.merge(parts(3), listOf(listOf(1, 2), listOf(2, 3)), listOf(first, second), null)

        assertEquals(listOf("A", "2:2"), merged.segments.map { it.speaker })
    }

    @Test
    fun `a fresh label steps around one the result already has`() {
        val earlier = listOf(segment("A", 0.0, 10.0, "a"), segment("2:C", 20.0, 30.0, "c"))
        val joining = listOf(segment("C", 100.0, 110.0, "new"))

        val labels = TranscribeChunks.speakers(earlier, joining, 0.0, 50.0, earlier, joining, null, 2)

        assertEquals(mapOf("C" to "2:C'"), labels)
    }

    @Test
    fun `the expected speaker count is never exceeded`() {
        val first = result(
            segment("A", 1000.0, 1020.0, "one"),
            segment("B", 1030.0, 1050.0, "two"),
        )
        // Y only speaks after the shared part, so nothing pairs it with anyone.
        val second = result(
            segment("X", 100.0, 120.0, "one"),
            segment("Y", 1000.0, 1010.0, "two, later"),
        )
        fun speakers(expected: Int?) = TranscribeChunks.merge(
            parts(3), listOf(listOf(1, 2), listOf(2, 3)), listOf(first, second), expected,
        ).segments.map { it.speaker }

        // Two people in the room: Y can only be the one X is not.
        assertEquals(listOf("A", "B", "B"), speakers(2))
        assertEquals(listOf("A", "B", "2:Y"), speakers(3))
        assertEquals(listOf("A", "B", "2:Y"), speakers(null))
    }

    @Test
    fun `segments without a speaker stay without one`() {
        val first = result(segment(null, 1000.0, 1020.0, "one"))
        val second = result(segment(null, 100.0, 120.0, "one"), segment(null, 1000.0, 1010.0, "two"))

        val merged = TranscribeChunks.merge(parts(3), listOf(listOf(1, 2), listOf(2, 3)), listOf(first, second), 2)

        assertEquals(listOf("one", "two"), merged.segments.map { it.text })
        assertEquals(listOf(null, null), merged.segments.map { it.speaker })
    }

    @Test
    fun `the merged result takes the first piece's model and language`() {
        val merged = TranscribeChunks.merge(
            parts(3),
            listOf(listOf(1, 2), listOf(2, 3)),
            listOf(result(language = "ko", model = "m1"), result(language = "en", model = "m2")),
            null,
        )

        assertEquals("ko", merged.language)
        assertEquals("m1", merged.model)
    }

    private fun parts(count: Int, bytes: Long = 16, durationSec: Double = 900.0): List<Part> = (1..count).map { number ->
        Part(
            part = number,
            track = Track.MONO,
            file = "part$number.m4a",
            bytes = bytes,
            sha256 = "0".repeat(64),
            startOffsetSec = (number - 1) * 905.0,
            durationSec = durationSec,
        )
    }

    private fun segment(speaker: String?, start: Double, end: Double, text: String, vararg words: SttWord) =
        SttSegment(start, end, speaker, text, words.toList().ifEmpty { null })

    private fun word(start: Double, end: Double, text: String) = SttWord(start, end, text)

    private fun result(vararg segments: SttSegment, language: String? = "ko", model: String? = null) =
        SttResult(segments.toList(), language, null, model)

    private fun shift(result: SttResult, by: Double): List<SttSegment> = result.segments.map { segment ->
        segment.copy(
            start = segment.start + by,
            end = segment.end + by,
            words = segment.words?.map { it.copy(start = it.start + by, end = it.end + by) },
        )
    }
}
