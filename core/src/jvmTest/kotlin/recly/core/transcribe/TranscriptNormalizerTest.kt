package recly.core.transcribe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import recly.core.model.Part
import recly.core.model.Track

class TranscriptNormalizerTest {
    /** Two 900 s parts, the second one starting 5 s later than the first one ended (a gap). */
    private val parts = listOf(part(1, 0.0), part(2, 905.0))

    private fun part(number: Int, startOffsetSec: Double) = Part(
        part = number,
        track = Track.MONO,
        file = "p$number.m4a",
        bytes = 16,
        sha256 = "0".repeat(64),
        startOffsetSec = startOffsetSec,
        durationSec = 900.0,
    )

    private fun normalize(
        segments: List<SttSegment>,
        diarize: Boolean = true,
        parts: List<Part> = this.parts,
    ): Transcript = TranscriptNormalizer.normalize(
        recordingId = "01J9ABCDEF0123456789ABCDEF",
        track = Track.MONO,
        parts = parts,
        result = SttResult(segments, "ko", 1800.0, "universal-2"),
        diarize = diarize,
        provider = TranscriptProvider("assemblyai", "universal-2", "t-1"),
        createdAt = "2026-08-29T03:10:00.000Z",
        language = "ko",
    )

    @Test
    fun `the second part's segments come back on the recording's time axis`() {
        val transcript = normalize(
            listOf(
                SttSegment(0.0, 3.2, "A", "첫 파트"),
                // 5.0 s into the second part, which the recorder started at 905.0 s.
                SttSegment(905.0, 906.8, "A", "두 번째 파트"),
            ),
        )

        assertEquals(listOf(0.0, 910.0), transcript.segments.map { it.start })
        assertEquals(listOf(3.2, 911.8), transcript.segments.map { it.end })
        assertEquals(1805.0, transcript.durationSec, "the recording ends where the last part does")
    }

    @Test
    fun `provider labels are renamed S1, S2 in order of first appearance`() {
        val transcript = normalize(
            listOf(
                SttSegment(0.0, 1.0, "B", "먼저 말한 사람"),
                SttSegment(1.0, 2.0, "A", "두 번째 사람"),
                SttSegment(2.0, 3.0, "B", "다시 첫 사람"),
            ),
        )

        assertEquals(listOf("S1", "S2", "S1"), transcript.segments.map { it.speaker })
        assertEquals(listOf("S1", "S2"), transcript.speakers.map { it.id })
        assertNull(transcript.speakers.first().name, "v1 never names a speaker")
    }

    @Test
    fun `without diarization every segment is S1 and there is one speaker`() {
        val transcript = normalize(
            listOf(SttSegment(0.0, 1.0, null, "가"), SttSegment(1.0, 2.0, null, "나")),
            diarize = false,
        )

        assertEquals(listOf("S1", "S1"), transcript.segments.map { it.speaker })
        assertEquals(listOf("S1"), transcript.speakers.map { it.id })
    }

    @Test
    fun `word timings are shifted too, and stay absent when the provider omits them`() {
        val transcript = normalize(
            listOf(
                SttSegment(905.0, 906.8, "A", "두 번째 파트", listOf(SttWord(905.0, 905.4, "두"))),
                SttSegment(910.0, 911.0, "A", "말없이"),
            ),
        )

        assertEquals(listOf(TranscriptWord(910.0, 910.4, "두")), transcript.segments[0].words)
        assertNull(transcript.segments[1].words)
    }

    @Test
    fun `the text file breaks a line on a speaker change and on a run over 60 seconds`() {
        val transcript = normalize(
            listOf(
                SttSegment(0.0, 3.2, "A", "안녕하세요"),
                SttSegment(3.6, 9.1, "B", "반갑습니다"),
                SttSegment(10.0, 20.0, "A", "그럼 시작하죠"),
                // Same speaker, but the line would now span more than 60 s of speech.
                SttSegment(30.0, 75.0, "A", "길게 이어지는 이야기"),
                SttSegment(80.0, 85.0, "A", "계속"),
            ),
        )

        assertEquals(
            listOf(
                "[00:00:00] S1: 안녕하세요",
                "[00:00:03] S2: 반갑습니다",
                "[00:00:10] S1: 그럼 시작하죠",
                "[00:00:30] S1: 길게 이어지는 이야기 계속",
                "",
            ),
            TranscriptNormalizer.text(transcript).split("\n"),
        )
    }

    @Test
    fun `the timestamp is hours, minutes and seconds of the recording`() {
        val transcript = normalize(listOf(SttSegment(0.0, 1.0, "A", "한 시간쯤")), parts = listOf(part(1, 3661.0)))

        assertEquals("[01:01:01] S1: 한 시간쯤\n", TranscriptNormalizer.text(transcript))
    }

    @Test
    fun `an empty transcript still names one speaker`() {
        val transcript = normalize(emptyList())

        assertEquals(listOf("S1"), transcript.speakers.map { it.id })
        assertEquals("", TranscriptNormalizer.text(transcript))
    }
}
