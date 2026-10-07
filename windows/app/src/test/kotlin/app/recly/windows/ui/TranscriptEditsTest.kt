package app.recly.windows.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import recly.core.model.Track
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptDocument
import recly.core.transcribe.TranscriptEdits
import recly.core.transcribe.TranscriptProvider
import recly.core.transcribe.TranscriptSegment
import recly.core.transcribe.TranscriptSpeaker

/**
 * docs/08 "Editing": what the editor sends is checked against the core's own rules — the edit is applied
 * with `TranscriptEdits.apply`, and the transcript that comes out has to be the one the editor showed.
 */
class TranscriptEditsTest {

    private fun transcript(speakers: List<String>, vararg lines: Pair<String, String>) = Transcript(
        recordingId = "rec",
        track = Track.MONO,
        language = "en",
        provider = TranscriptProvider("test"),
        createdAt = "2026-10-07T00:00:00Z",
        durationSec = lines.size * 5.0,
        speakers = speakers.map { TranscriptSpeaker(it) },
        segments = lines.mapIndexed { index, (speaker, text) -> TranscriptSegment(index * 5.0, index * 5.0 + 5, speaker, text) },
    )

    private fun applied(original: Transcript, texts: List<String>, speakers: List<String>, names: Map<String, String?> = emptyMap()): Transcript {
        val edit = transcriptEdit(original, texts, speakers, names) ?: return original
        return TranscriptEdits.apply(original, edit, "2026-10-07T01:00:00Z")
    }

    @Test
    fun `nothing changed is no edit`() {
        val original = transcript(listOf("S1"), "S1" to "hello", "S1" to "there")
        assertNull(transcriptEdit(original, listOf("hello ", "there"), listOf("S1", "S1")))
    }

    @Test
    fun `text, an existing speaker and a name are applied as shown`() {
        val original = transcript(listOf("S1", "S2"), "S1" to "hello", "S2" to "there", "S1" to "again")
        val out = applied(original, listOf("hello", "their", "again"), listOf("S1", "S1", "S2"), mapOf("S2" to "Mina"))
        assertEquals(listOf("hello", "their", "again"), out.segments.map { it.text })
        assertEquals(listOf("S1", "S1", "S2"), out.segments.map { it.speaker })
        assertEquals("Mina", out.speakers.first { it.id == "S2" }.name)
    }

    @Test
    fun `new speakers are made in order and numbered the way the core numbers them`() {
        val original = transcript(listOf("S1", "S2"), "S1" to "a", "S2" to "b", "S1" to "c", "S2" to "d")
        // The draft's new S3 and S5 become the core's S3 and S4, in the order of their numbers.
        val out = applied(original, listOf("a", "b", "c", "d"), listOf("S5", "S2", "S3", "S5"), mapOf("S5" to "Joon"))
        assertEquals(listOf("S4", "S2", "S3", "S4"), out.segments.map { it.speaker })
        assertEquals("Joon", out.speakers.first { it.id == "S4" }.name)
    }

    @Test
    fun `a transcript nobody was identified in gets its speakers`() {
        val original = transcript(emptyList(), "" to "a", "" to "b", "" to "c")
        val out = applied(original, listOf("a", "b", "c"), listOf("S1", "S2", "S1"))
        assertEquals(listOf("S1", "S2", "S1"), out.segments.map { it.speaker })
    }

    @Test
    fun `blocks know their segments and finds are counted in reading order`() {
        val original = transcript(listOf("S1", "S2"), "S1" to "Hello there", "S1" to "hello", "S2" to "", "S2" to "say HELLO")
        val blocks = TranscriptDocument(original).blocks
        assertEquals(listOf(0..2, 3..3), blockSegments(original, blocks))
        assertEquals(listOf(FindMatch(0, 0, 5), FindMatch(0, 12, 5), FindMatch(1, 4, 5)), findMatches(blocks, " hello"))
    }
}
