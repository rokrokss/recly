package recly.core.transcribe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import recly.core.model.Track

class TranscriptDocumentTest {
    private fun transcript(segments: List<TranscriptSegment>) = Transcript(
        recordingId = "test", track = Track.MONO, language = "en", provider = TranscriptProvider("test"),
        createdAt = "2026-09-10T00:00:00Z", durationSec = 7200.0,
        speakers = listOf(TranscriptSpeaker("S1")), segments = segments,
    )

    @Test fun longMonologuesKeepBoundedParagraphsAndAllWords() {
        val segments = List(7200) { TranscriptSegment(it.toDouble(), it + 1.0, "S1", "word$it") }
        val document = TranscriptDocument(transcript(segments))
        assertTrue(document.blocks.size >= 120)
        assertTrue(document.blocks.all { it.text.length <= 1200 })
        assertEquals(segments.joinToString(" ") { it.text }, document.blocks.joinToString(" ") { it.text })
        assertEquals(document.blocks.indices.toList(), document.blocks.map { it.index })
        assertEquals(60.0, document.blocks[1].start)
        assertTrue(document.plainText.contains("[00:01:00] S1: word60"))
    }

    @Test fun searchKeepsStableTargetsAndDoesNotChangeCopiedText() {
        val document = TranscriptDocument(transcript(listOf(
            TranscriptSegment(0.0, 1.0, "S1", " Hello "),
            TranscriptSegment(1.0, 2.0, "S1", "world"),
            TranscriptSegment(3.0, 4.0, "S2", "안녕하세요"),
            TranscriptSegment(5.0, 6.0, "S2", " "),
        )))
        assertEquals("Hello world", document.search(" HELLO ").single().text)
        assertEquals(1, document.search("안녕").single().index)
        assertEquals(3.0, document.search("s2").single().start)
        assertTrue(document.search("missing").isEmpty())
        assertEquals(document.blocks, document.search(" "))
        assertTrue(document.plainText.contains("Hello world"))
        assertTrue(document.plainText.contains("안녕하세요"))
    }

    @Test fun segmentRangesFindEachSegmentsWordsInItsBlock() {
        val document = TranscriptDocument(transcript(listOf(
            TranscriptSegment(0.0, 1.0, "S1", " Hello "),
            TranscriptSegment(1.0, 2.0, "S1", "world"),
            TranscriptSegment(3.0, 4.0, "S2", " "),
            TranscriptSegment(5.0, 6.0, "S2", "안녕하세요"),
        )))
        assertEquals(
            listOf(BlockRange(0, 0, 5), BlockRange(0, 6, 5), BlockRange(1, 0, 5)),
            document.segmentRanges(listOf(3, 1, 0, 2, 1, 9)),
        )
        assertEquals("world", document.blocks[0].text.substring(6, 11))
    }

    /** docs/09 "Transcript reader": a paragraph's segments join as the transcript file joins them (docs/08 "Result files"). */
    @Test fun japaneseAndChineseSegmentsRunOnWithoutASpace() {
        val document = TranscriptDocument(transcript(listOf(
            TranscriptSegment(0.0, 1.0, "S1", "今日は"),
            TranscriptSegment(1.0, 2.0, "S1", "会議です。"),
            TranscriptSegment(2.0, 3.0, "S1", "Recly"),
            TranscriptSegment(3.0, 4.0, "S1", "を使います"),
            TranscriptSegment(4.0, 5.0, "S2", "我们"),
            TranscriptSegment(5.0, 6.0, "S2", "开会"),
            TranscriptSegment(6.0, 7.0, "S3", "오늘"),
            TranscriptSegment(7.0, 8.0, "S3", "회의"),
        )))
        assertEquals(listOf("今日は会議です。 Recly を使います", "我们开会", "오늘 회의"), document.blocks.map { it.text })
        assertEquals(
            listOf(BlockRange(0, 3, 5), BlockRange(0, 9, 5), BlockRange(0, 15, 5), BlockRange(1, 2, 2)),
            document.segmentRanges(listOf(1, 2, 3, 5)),
        )
        assertEquals(0, document.search("は会議").single().index, "a phrase across two segments is found")
        assertEquals(1, document.search("们开").single().index)
    }
}
