package recly.core.transcribe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import recly.core.model.Highlight
import recly.core.model.Track
import recly.core.testing.testMeta

/** docs/08 "Result files" and "Exports": the text, Markdown and subtitle renderings of one transcript. */
class TranscriptRenderTest {
    private fun transcript(
        segments: List<TranscriptSegment>,
        speakers: List<TranscriptSpeaker> = listOf(TranscriptSpeaker("S1", "Minsu"), TranscriptSpeaker("S2")),
    ) = Transcript(
        recordingId = "01J9ABCDEF0123456789ABCDEF",
        track = Track.MONO,
        language = "ko",
        provider = TranscriptProvider("assemblyai"),
        createdAt = "2026-08-29T03:10:00.000Z",
        durationSec = 100.0,
        speakers = speakers,
        segments = segments,
    )

    private val talk = transcript(
        listOf(
            TranscriptSegment(1.0, 3.2, "S1", "시작하겠습니다."),
            TranscriptSegment(3.6, 9.1, "S2", "네, 보시죠."),
        ),
    )

    @Test
    fun `the text uses a speaker's name when it has one and the id otherwise`() {
        assertEquals(
            "[00:00:01] Minsu: 시작하겠습니다.\n[00:00:03] S2: 네, 보시죠.\n",
            TranscriptNormalizer.text(talk),
        )
    }

    @Test
    fun `a blank name reads as no name`() {
        val unnamed = talk.copy(speakers = listOf(TranscriptSpeaker("S1", "  "), TranscriptSpeaker("S2")))

        assertEquals("[00:00:01] S1: 시작하겠습니다.\n[00:00:03] S2: 네, 보시죠.\n", TranscriptNormalizer.text(unnamed))
    }

    @Test
    fun `srt numbers its cues and prefixes the speaker`() {
        assertEquals(
            "1\n00:00:01,000 --> 00:00:03,200\nMinsu: 시작하겠습니다.\n\n" +
                "2\n00:00:03,600 --> 00:00:09,100\nS2: 네, 보시죠.\n\n",
            TranscriptNormalizer.srt(talk),
        )
    }

    @Test
    fun `vtt has its header and dotted milliseconds`() {
        assertEquals(
            "WEBVTT\n\n00:00:01.000 --> 00:00:03.200\nMinsu: 시작하겠습니다.\n\n" +
                "00:00:03.600 --> 00:00:09.100\nS2: 네, 보시죠.\n\n",
            TranscriptNormalizer.vtt(talk),
        )
    }

    @Test
    fun `a transcript without speakers has no prefix and blank segments make no cue`() {
        val local = transcript(
            listOf(TranscriptSegment(3661.5, 3662.0, "", "한 시간"), TranscriptSegment(3663.0, 3664.0, "", "  ")),
            speakers = emptyList(),
        )

        assertEquals("1\n01:01:01,500 --> 01:01:02,000\n한 시간\n\n", TranscriptNormalizer.srt(local))
    }

    @Test
    fun `a long segment is cut at its words, and joined back without spaces when it had none`() {
        val words = (0 until 20).map { TranscriptWord(it.toDouble(), it + 0.9, "w$it") }
        val long = transcript(listOf(TranscriptSegment(0.0, 20.0, "S2", words.joinToString(" ") { it.text }, words)))

        val cues = TranscriptNormalizer.srt(long).trimEnd().split("\n\n")

        assertEquals(3, cues.size, TranscriptNormalizer.srt(long))
        assertEquals("1\n00:00:00,000 --> 00:00:06,900\nS2: w0 w1 w2 w3 w4 w5 w6", cues[0])
        assertEquals("2\n00:00:07,000 --> 00:00:13,900\nS2: w7 w8 w9 w10 w11 w12 w13", cues[1])

        val cjk = (0 until 10).map { TranscriptWord(it.toDouble(), it + 0.9, "字") }
        val japanese = transcript(listOf(TranscriptSegment(0.0, 10.0, "S1", "字".repeat(10), cjk)))
        assertEquals("Minsu: 字字字字字字字", TranscriptNormalizer.vtt(japanese).split("\n")[3])
    }

    @Test
    fun `a long segment without word timings stays one cue`() {
        val long = transcript(listOf(TranscriptSegment(0.0, 30.0, "S1", "x".repeat(200))))

        assertEquals(1, TranscriptNormalizer.srt(long).trimEnd().split("\n\n").size)
    }

    @Test
    fun `markdown lists the highlights with what was being said, and the text keeps none`() {
        val meta = testMeta(title = "Weekly").copy(highlights = Highlight.normalize(listOf(2.0, 7.5, 95.0)))

        val markdown = TranscriptNormalizer.markdown(talk, meta)

        assertEquals(
            "---\ntitle: \"Weekly\"\nrecordingId: 01J9ABCDEF0123456789ABCDEF\nstartedAt: 2026-08-26T01:00:00.000Z\n" +
                "highlights:\n  - \"00:00:02\"\n  - \"00:00:07\"\n  - \"00:01:35\"\n---\n\n" +
                "## Highlights\n\n- 00:00:02 — 시작하겠습니다.\n- 00:00:07 — 네, 보시죠.\n- 00:01:35 — 네, 보시죠.\n\n" +
                "[00:00:01] Minsu: 시작하겠습니다.\n\n[00:00:03] S2: 네, 보시죠.\n",
            markdown,
        )
        assertFalse("Highlights" in TranscriptNormalizer.text(talk))
    }

    @Test
    fun `highlights are sorted, merged within a second and capped`() {
        assertEquals(listOf(0.0, 2.0, 5.5), Highlight.normalize(listOf(5.5, 2.0, 0.0, 2.4, -1.0, Double.NaN, 6.2)).map { it.atSec })
        assertEquals(Highlight.MAX, Highlight.normalize((0 until 600).map { it * 2.0 }).size)
    }
}
