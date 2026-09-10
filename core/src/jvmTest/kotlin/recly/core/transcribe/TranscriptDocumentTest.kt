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
}
