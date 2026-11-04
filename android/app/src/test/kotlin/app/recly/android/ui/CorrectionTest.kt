package app.recly.android.ui

import app.recly.android.R
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import recly.core.chatgpt.CorrectionRequest
import recly.core.chatgpt.CorrectionResult
import recly.core.message.CoreMessage
import recly.core.model.Track
import recly.core.transcribe.EditResult
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptProvider
import recly.core.transcribe.TranscriptSegment
import recly.core.transcribe.TranscriptSpeaker
import recly.core.transcribe.TranscriptVersionKind

/**
 * docs/08 "Correcting" · "Versions" on the phone: what the Correct sheet's field takes and when Correct can be pressed;
 * what each answer says; the line after a correction and the lines the reader tints; why a restore did not happen; and
 * what Version history's rows say.
 */
class CorrectionTest {

    private val transcript = Transcript(
        recordingId = "r1", track = Track.MIC, language = "en", provider = TranscriptProvider("assemblyai"),
        createdAt = "2026-11-04T10:00:00Z", durationSec = 90.0,
        speakers = listOf(TranscriptSpeaker("S1"), TranscriptSpeaker("S2")),
        segments = listOf(
            TranscriptSegment(0.0, 2.0, "S1", " Hello there. "),
            TranscriptSegment(2.0, 4.0, "S1", "We met at Recly."),
            TranscriptSegment(4.0, 5.0, "S1", "   "),
            TranscriptSegment(5.0, 8.0, "S2", "Thanks, Minsu."),
            // Past 60 seconds from the group's start: a new group for the same speaker.
            TranscriptSegment(70.0, 75.0, "S2", "Budget next."),
        ),
    )

    @Test
    fun `the field takes no more than the core does, never half a character`() {
        assertEquals("as typed\n", correctionInput("as typed\n"))
        assertEquals(CorrectionRequest.MAX, correctionInput("x".repeat(CorrectionRequest.MAX + 10)).length)
        assertEquals("x".repeat(CorrectionRequest.MAX - 1), correctionInput("x".repeat(CorrectionRequest.MAX - 1) + "😀"))
    }

    @Test
    fun `Correct needs words and nothing running`() {
        assertFalse(canCorrect(running = false, request = " \n "))
        assertTrue(canCorrect(running = false, request = "Minsu, not Minseo"))
        assertFalse(canCorrect(running = true, request = "Minsu, not Minseo"))
    }

    @Test
    fun `a correction closes the sheet, anything else stays in it`() {
        assertNull(correctOutcome(CorrectionResult.Corrected(listOf(1), 0, "3"), R.string.detail_transcribing))
        assertEquals(CorrectOutcome.Said(R.string.correct_nothing), correctOutcome(CorrectionResult.NothingToCorrect, R.string.detail_transcribing))
        assertEquals(CorrectOutcome.Said(R.string.detail_no_transcript), correctOutcome(CorrectionResult.NoTranscript, R.string.detail_transcribing))
        // A job in the way, in the words the menu already uses for it.
        assertEquals(CorrectOutcome.Said(R.string.detail_not_uploaded), correctOutcome(CorrectionResult.Busy, R.string.detail_not_uploaded))
        val limit = CoreMessage.CHATGPT_USAGE_LIMIT.code()
        assertEquals(CorrectOutcome.Failed(limit), correctOutcome(CorrectionResult.Failed(limit), R.string.detail_transcribing))
        assertEquals(CorrectOutcome.Failed(null), correctOutcome(null, R.string.detail_transcribing))
    }

    @Test
    fun `the line counts the lines it changed, or the speakers it named when that is all`() {
        assertEquals(R.string.corrected_lines to 2, correctedLine(CorrectionMarks(listOf(0, 3), names = 1, previousVersionId = "2", createdAt = null)))
        assertEquals(R.string.corrected_names to 1, correctedLine(CorrectionMarks(emptyList(), names = 1, previousVersionId = "2", createdAt = null)))
    }

    @Test
    fun `the tints fall on each changed segment's own words in the reader's groups`() {
        val groups = readerGroups(transcript)
        val tints = correctionTints(transcript, listOf(4, 1, 3, 2))
        // In reading order; the segment that says nothing has no words to tint.
        assertEquals(listOf("We met at Recly.", "Thanks, Minsu.", "Budget next."),
            tints.map { groups[it.group].text.substring(it.offset, it.offset + it.length) })
        assertEquals(listOf(0, 1, 2), tints.map { it.group })
        assertEquals(emptyList(), correctionTints(transcript, emptyList()))
    }

    @Test
    fun `a restore that did not happen says why, a job in the way muted and anything else as a failure`() {
        assertNull(restoreRefusal(EditResult.Edited(transcript), R.string.detail_transcribing))
        assertEquals(RestoreRefusal(R.string.detail_transcribing, failed = false), restoreRefusal(EditResult.Busy, R.string.detail_transcribing))
        val failed = RestoreRefusal(R.string.version_restore_failed, failed = true)
        assertEquals(failed, restoreRefusal(EditResult.Invalid("no version 9"), R.string.detail_transcribing))
        assertEquals(failed, restoreRefusal(EditResult.NoTranscript, R.string.detail_transcribing))
        assertEquals(failed, restoreRefusal(null, R.string.detail_transcribing))
    }

    @Test
    fun `every kind of version has its own words`() {
        assertEquals(TranscriptVersionKind.entries.size, TranscriptVersionKind.entries.map { it.label() }.toSet().size)
        assertEquals(R.string.version_received, TranscriptVersionKind.RECEIVED.label())
    }

    @Test
    fun `a version's time is the locale's own, in the device's zone`() {
        val utc = ZoneId.of("UTC")
        val english = versionStamp("2026-11-04T15:04:00Z", Locale.US, utc)
        assertTrue("Nov 4, 2026" in english, english)
        assertTrue("3:04" in english, english)
        val korean = versionStamp("2026-11-04T15:04:00Z", Locale.KOREA, ZoneId.of("Asia/Seoul"))
        assertTrue("2026. 11. 5." in korean, korean)
        assertEquals("not a time", versionStamp("not a time", Locale.US, utc))
    }
}
