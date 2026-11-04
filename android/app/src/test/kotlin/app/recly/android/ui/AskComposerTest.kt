package app.recly.android.ui

import androidx.compose.ui.unit.dp
import app.recly.android.R
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import recly.core.chatgpt.AskChanges
import recly.core.chatgpt.AskPreset
import recly.core.chatgpt.AskRequest
import recly.core.chatgpt.ChatGptConnection
import recly.core.chatgpt.ChatGptModel
import recly.core.model.Track
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptProvider
import recly.core.transcribe.TranscriptSegment
import recly.core.transcribe.TranscriptSpeaker

/**
 * docs/09 "Ask" on the phone: where the composer is and why it is off; what its field takes, when Send can be pressed,
 * when the presets show and what a Return does; the changes line, the view it turns to and its Undo; and the lines the
 * reader tints.
 */
class AskComposerTest {

    private val signedIn = ChatGptConnection.SignedIn("me@example.com", listOf(ChatGptModel("gpt-5", "GPT-5")), "gpt-5")

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
    fun `the bar is there where ChatGPT is offered and the transcript says something, but not while editing or on the memo`() {
        assertTrue(showsAskBar(signedIn, loading = false, editing = false, DetailView.TRANSCRIPT, transcript))
        assertTrue(showsAskBar(signedIn, loading = false, editing = false, DetailView.NOTES, transcript))
        // Signed out it is there, off, saying why.
        assertTrue(showsAskBar(ChatGptConnection.SignedOut, loading = false, editing = false, DetailView.TRANSCRIPT, transcript))
        assertFalse(showsAskBar(ChatGptConnection.Unavailable, loading = false, editing = false, DetailView.TRANSCRIPT, transcript))
        assertFalse(showsAskBar(signedIn, loading = true, editing = false, DetailView.TRANSCRIPT, transcript))
        assertFalse(showsAskBar(signedIn, loading = false, editing = true, DetailView.TRANSCRIPT, transcript))
        assertFalse(showsAskBar(signedIn, loading = false, editing = false, DetailView.MEMO, transcript))
        assertFalse(showsAskBar(signedIn, loading = false, editing = false, DetailView.TRANSCRIPT, null))
        val silent = transcript.copy(segments = listOf(TranscriptSegment(0.0, 1.0, "S1", "  ")))
        assertFalse(showsAskBar(signedIn, loading = false, editing = false, DetailView.TRANSCRIPT, silent))
    }

    @Test
    fun `the field says why nothing can be asked, Still recording before More's old reasons`() {
        assertEquals(R.string.detail_still_recording, composerReason(true, true, false, R.string.detail_transcribing, signedIn))
        assertEquals(R.string.detail_no_transcript, composerReason(false, false, false, R.string.detail_transcribing, signedIn))
        assertEquals(R.string.detail_not_uploaded, composerReason(false, true, true, R.string.detail_not_uploaded, signedIn))
        assertEquals(R.string.core_chatgpt_sign_in_required, composerReason(false, true, false, R.string.detail_transcribing, ChatGptConnection.SignedOut))
        assertNull(composerReason(false, true, false, R.string.detail_transcribing, signedIn))
    }

    @Test
    fun `the field takes no more than the core does, never half a character`() {
        assertEquals("as typed\nwith lines", askInput("as typed\nwith lines"))
        assertEquals(AskRequest.MAX, askInput("x".repeat(AskRequest.MAX + 10)).length)
        assertEquals("x".repeat(AskRequest.MAX - 1), askInput("x".repeat(AskRequest.MAX - 1) + "😀"))
    }

    @Test
    fun `Send needs words, nothing running and nothing in the way`() {
        assertTrue(canSend(running = false, reason = null, text = "Minsu, not Minseo"))
        assertFalse(canSend(running = false, reason = null, text = " \n "))
        assertFalse(canSend(running = true, reason = null, text = "Minsu, not Minseo"))
        assertFalse(canSend(running = false, reason = R.string.detail_still_recording, text = "Minsu, not Minseo"))
    }

    @Test
    fun `the presets are over the field while it is empty and has the cursor, and only while a chip could ask`() {
        val presets = listOf(AskPreset.ACTION_ITEMS)
        assertTrue(showsPresets(focused = true, text = "", running = false, reason = null, presets = presets))
        assertTrue(showsPresets(focused = true, text = "  ", running = false, reason = null, presets = presets))
        assertFalse(showsPresets(focused = false, text = "", running = false, reason = null, presets = presets))
        assertFalse(showsPresets(focused = true, text = "Why?", running = false, reason = null, presets = presets))
        assertFalse(showsPresets(focused = true, text = "", running = true, reason = null, presets = presets))
        assertFalse(showsPresets(focused = true, text = "", running = false, reason = R.string.detail_no_transcript, presets = presets))
        assertFalse(showsPresets(focused = true, text = "", running = false, reason = null, presets = emptyList()))
    }

    @Test
    fun `an answer scrolls past a third of the page, and never past 240`() {
        assertEquals(200.dp, answerHeight(600.dp))
        assertEquals(240.dp, answerHeight(900.dp))
        // Before the page has been measured.
        assertEquals(240.dp, answerHeight(0.dp))
    }

    @Test
    fun `a new line typed sends, and a paste keeps its lines`() {
        assertTrue(typedNewline("Why?", "Why?\n"))
        assertTrue(typedNewline("Wh", "W\nh"), "wherever the cursor is")
        assertTrue(typedNewline("one\ntwo", "one\ntwo\n"))
        assertFalse(typedNewline("Why", "Why?"))
        assertFalse(typedNewline("Fix:", "Fix:\nMinsu\nnot Minseo"), "a paste of several lines")
        assertFalse(typedNewline("", "\n\n"))
        assertFalse(typedNewline("Why?\n", "Why?"))
    }

    @Test
    fun `the changes line names only the documents that changed, with how many places`() {
        assertEquals(listOf(R.string.ask_changed_transcript to 3, R.string.ask_changed_summary to 3),
            changedParts(AskChanges(listOf(0, 3), names = 1, items = listOf("a"), removed = 1, overview = true)))
        assertEquals(listOf(R.string.ask_changed_transcript to 1), changedParts(AskChanges(emptyList(), names = 1, items = emptyList(), removed = 0, overview = false)))
        assertEquals(listOf(R.string.ask_changed_summary to 1), changedParts(AskChanges(emptyList(), names = 0, items = emptyList(), removed = 0, overview = true)))
    }

    @Test
    fun `changes to one of the two turn the page to it, and to both leave it where it is`() {
        val transcriptOnly = AskChanges(listOf(1), names = 0, items = emptyList(), removed = 0, overview = false)
        val summaryOnly = AskChanges(emptyList(), names = 0, items = listOf("a"), removed = 0, overview = false)
        val both = AskChanges(listOf(1), names = 0, items = listOf("a"), removed = 0, overview = false)
        assertEquals(DetailView.TRANSCRIPT, askChangesView(transcriptOnly, hasNotes = true))
        assertEquals(DetailView.NOTES, askChangesView(summaryOnly, hasNotes = true))
        assertNull(askChangesView(summaryOnly, hasNotes = false))
        assertNull(askChangesView(both, hasNotes = true))
    }

    @Test
    fun `an Undo that could not undo everything says so under the header`() {
        assertNull(undoRefusal(true))
        assertEquals(R.string.ask_undo_failed, undoRefusal(false))
    }

    @Test
    fun `the tints fall on each changed segment's own words in the reader's groups`() {
        val groups = readerGroups(transcript)
        val tints = segmentTints(transcript, listOf(4, 1, 3, 2))
        // In reading order; the segment that says nothing has no words to tint.
        assertEquals(listOf("We met at Recly.", "Thanks, Minsu.", "Budget next."),
            tints.map { groups[it.group].text.substring(it.offset, it.offset + it.length) })
        assertEquals(listOf(0, 1, 2), tints.map { it.group })
        assertEquals(emptyList(), segmentTints(transcript, emptyList()))
        // A segment the transcript no longer has tints nothing.
        assertEquals(emptyList(), segmentTints(transcript, listOf(9)))
    }
}
