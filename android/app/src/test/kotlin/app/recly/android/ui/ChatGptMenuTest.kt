package app.recly.android.ui

import app.recly.android.R
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import recly.core.chatgpt.AskAnswer
import recly.core.chatgpt.AskPreset
import recly.core.chatgpt.AskState
import recly.core.chatgpt.ChatGptConnection
import recly.core.chatgpt.ChatGptModel
import recly.core.message.CoreMessage
import recly.core.model.Track
import recly.core.recording.NotesMatch
import recly.core.recording.SearchHit
import recly.core.recording.SearchRange
import recly.core.recording.SearchSnippet
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptProvider
import recly.core.transcribe.TranscriptSpeaker

/**
 * docs/09 "Detail header and More menu" · "AI view": why Organize and Edit notes wait, in the order the reasons are
 * checked; what failed notes or a failed answer offer; Ask; the footer's model; citations; a notes-only search hit;
 * and "Me".
 */
class ChatGptMenuTest {

    private val signedIn = ChatGptConnection.SignedIn("me@example.com", listOf(ChatGptModel("gpt-5", "GPT-5")), "gpt-5")

    @Test
    fun `no transcript comes first`() {
        assertEquals(R.string.detail_no_transcript,
            organizeReason(false, transcribing = true, busyReason = R.string.detail_transcribing, ChatGptConnection.SignedOut, organizing = true))
    }

    @Test
    fun `a transcription on its way says what the menu already says about it`() {
        assertEquals(R.string.detail_transcribing, organizeReason(true, true, R.string.detail_transcribing, ChatGptConnection.SignedOut, false))
        assertEquals(R.string.detail_not_uploaded, organizeReason(true, true, R.string.detail_not_uploaded, signedIn, false))
    }

    @Test
    fun `signed out or expired sends the user to Settings`() {
        assertEquals(R.string.core_chatgpt_sign_in_required, organizeReason(true, false, R.string.detail_transcribing, ChatGptConnection.SignedOut, false))
        assertEquals(R.string.core_chatgpt_sign_in_required,
            organizeReason(true, false, R.string.detail_transcribing, ChatGptConnection.Expired("me@example.com"), true))
    }

    @Test
    fun `notes being organized say so`() {
        assertEquals(R.string.notes_organizing, organizeReason(true, false, R.string.detail_transcribing, signedIn, organizing = true))
    }

    @Test
    fun `signed in with a transcript and nothing organizing, Organize can run`() {
        assertNull(organizeReason(true, false, R.string.detail_transcribing, signedIn, organizing = false))
    }

    @Test
    fun `Edit notes waits only while the notes are being organized - no sign-in needed`() {
        assertNull(editNotesReason(organizing = false))
        assertEquals(R.string.notes_organizing, editNotesReason(organizing = true))
    }

    @Test
    fun `failed notes or a failed answer offer the one button that helps`() {
        assertEquals(ChatGptRecovery.MANAGE_USAGE, chatGptRecovery(CoreMessage.CHATGPT_USAGE_LIMIT.code()))
        assertEquals(ChatGptRecovery.NONE, chatGptRecovery(CoreMessage.CHATGPT_SIGN_IN_REQUIRED.code()))
        // Asking again cannot change the plan.
        assertEquals(ChatGptRecovery.NONE, chatGptRecovery(CoreMessage.CHATGPT_PLAN_REQUIRED.code()))
        assertEquals(ChatGptRecovery.RETRY, chatGptRecovery(CoreMessage.PROVIDER_ERROR.code(detail = "500")))
        assertEquals(ChatGptRecovery.RETRY, chatGptRecovery(CoreMessage.STEP_FAILED.code("no transcript")))
        assertEquals(ChatGptRecovery.RETRY, chatGptRecovery("not a key"))
    }

    @Test
    fun `four reasons are said in their own words, any other as a failure with its detail`() {
        assertEquals(CoreMessage.CHATGPT_USAGE_LIMIT, spokenReason(CoreMessage.CHATGPT_USAGE_LIMIT.code()))
        assertEquals(CoreMessage.PROVIDER_REGION_RESTRICTED, spokenReason(CoreMessage.PROVIDER_REGION_RESTRICTED.code()))
        assertNull(spokenReason(CoreMessage.PROVIDER_ERROR.code(detail = "state mismatch")))
        assertEquals("state mismatch", reasonDetail(CoreMessage.PROVIDER_ERROR.code(detail = "state mismatch")))
        assertEquals("no transcript", reasonDetail(CoreMessage.STEP_FAILED.code("no transcript")))
        assertEquals("as it came", reasonDetail("as it came"))
    }

    @Test
    fun `Ask waits for what Organize waits for, but not for notes being organized`() {
        assertEquals(R.string.detail_no_transcript, askReason(false, true, R.string.detail_transcribing, ChatGptConnection.SignedOut))
        assertEquals(R.string.detail_not_uploaded, askReason(true, true, R.string.detail_not_uploaded, signedIn))
        assertEquals(R.string.core_chatgpt_sign_in_required, askReason(true, false, R.string.detail_transcribing, ChatGptConnection.SignedOut))
        assertEquals(R.string.core_chatgpt_sign_in_required, askReason(true, false, R.string.detail_transcribing, ChatGptConnection.Expired("me@example.com")))
        assertNull(askReason(true, false, R.string.detail_transcribing, signedIn))
    }

    @Test
    fun `citations cut the text into runs that cover it end to end`() {
        val text = "Decisions\n- Ship on Friday [00:12:34]\n- [01:05] and [1:02:03]."
        val runs = textRuns(text)
        assertEquals(text, runs.joinToString("") { text.substring(it.start, it.end) })
        assertEquals(runs.zipWithNext().map { it.first.end }, runs.drop(1).map { it.start })
        val cited = runs.mapNotNull { run -> run.citation?.let { text.substring(run.start, run.end) to it.atSec } }
        assertEquals(listOf("[00:12:34]" to 754.0, "[01:05]" to 65.0, "[1:02:03]" to 3723.0), cited)
        assertEquals(listOf("00:12:34", "01:05", "1:02:03"), runs.mapNotNull { run -> run.citation?.let { citationTime(text, it) } })
        // Ends on text, and the last run is the full stop after the last citation.
        assertEquals(".", text.substring(runs.last().start, runs.last().end))
    }

    @Test
    fun `a tap at the middle of each of three stacked citations plays that one, not the next line's`() {
        // Three bulleted lines 20 px apart, a citation at the start of each — the targets, 48 px tall, overlap.
        val glyphs = listOf(Rect(16f, 0f, 96f, 20f), Rect(16f, 20f, 96f, 40f), Rect(16f, 40f, 96f, 60f))
        val targets = glyphs.mapIndexed { run, box -> CitationTarget(run * 2, box) }
        glyphs.forEachIndexed { index, box ->
            assertEquals(index * 2, citationAt(box.center, targets, reach = 48f)?.run, "the middle of citation $index")
        }
        // Near the bottom of the middle line it is still the middle one's; above the first line, within its reach, the first's.
        assertEquals(2, citationAt(Offset(50f, 38f), targets, 48f)?.run)
        assertEquals(0, citationAt(Offset(56f, -10f), targets, 48f)?.run)
        // Out of every reach: no citation, and the tap is the text's.
        assertNull(citationAt(Offset(200f, 30f), targets, 48f))
    }

    @Test
    fun `a target is the glyphs grown to the reach each way, never shrunk`() {
        assertEquals(Rect(32f, -14f, 80f, 34f), CitationTarget(0, Rect(50f, 0f, 62f, 20f)).area(48f))
        assertEquals(Rect(0f, -14f, 120f, 34f), CitationTarget(0, Rect(0f, 0f, 120f, 20f)).area(48f))
    }

    @Test
    fun `the footer names the model by the name it had when written, then the plan's list, then its id`() {
        val models = listOf(ChatGptModel("gpt-5", "GPT-5"))
        assertEquals("GPT-5.4", modelLabel("GPT-5.4", "gpt-5", models))
        assertEquals("GPT-5", modelLabel(null, "gpt-5", models))
        assertEquals("gpt-5-mini", modelLabel(null, "gpt-5-mini", models))
    }

    @Test
    fun `the preset chip of the answer on screen is marked, and none for an own question`() {
        assertNull(askedPreset(AskState.None))
        assertEquals(AskPreset.ACTION_ITEMS, askedPreset(AskState.Running(AskPreset.ACTION_ITEMS, null)))
        assertEquals(AskPreset.TRANSLATE, askedPreset(AskState.Ready(AskAnswer("r1", AskPreset.TRANSLATE, null, "Hola", "gpt-5"))))
        assertEquals(AskPreset.OPEN_QUESTIONS, askedPreset(AskState.Failed(CoreMessage.PROVIDER_ERROR.code(), AskPreset.OPEN_QUESTIONS, null)))
        assertNull(askedPreset(AskState.Ready(AskAnswer("r1", null, "Why?", "Because.", "gpt-5"))))
    }

    @Test
    fun `text with no citation, or one that starts and ends on one, is still covered`() {
        assertEquals(listOf(TextRun(0, 5)), textRuns("plain"))
        assertEquals(emptyList(), textRuns(""))
        val text = "[00:01]"
        assertEquals(listOf(0 to 7), textRuns(text).map { it.start to it.end })
        // Not a time: 75 seconds stays text.
        assertEquals(listOf(TextRun(0, 8)), textRuns("[00:75] "))
    }

    @Test
    fun `a hit opens on the notes only when the notes are all it found`() {
        val match = NotesMatch("Budget approved", listOf(SearchRange(0, 6)))
        val snippet = SearchSnippet(12.0, "the budget", listOf(SearchRange(4, 6)))
        fun hit(inTitle: Boolean, snippets: List<SearchSnippet>, notes: NotesMatch?) =
            SearchHit("r1", "Weekly", "2026-10-09T10:00:00Z", inTitle, emptyList(), snippets, notes)
        assertTrue(opensOnNotes(hit(false, emptyList(), match)))
        assertFalse(opensOnNotes(hit(false, listOf(snippet), match)))
        assertFalse(opensOnNotes(hit(true, emptyList(), match)))
        assertFalse(opensOnNotes(hit(false, listOf(snippet), null)))
    }

    @Test
    fun `a hit opens on the memo only when the memo is all it found`() {
        val match = NotesMatch("Pricing with Minsu", listOf(SearchRange(13, 5)))
        val snippet = SearchSnippet(12.0, "minsu said", listOf(SearchRange(0, 5)))
        fun hit(inTitle: Boolean, snippets: List<SearchSnippet>, notes: NotesMatch?, memo: NotesMatch?) =
            SearchHit("r1", "Weekly", "2026-10-09T10:00:00Z", inTitle, emptyList(), snippets, notes, memo)
        assertTrue(opensOnMemo(hit(false, emptyList(), null, match)))
        assertFalse(opensOnMemo(hit(false, emptyList(), match, match)), "the notes first")
        assertFalse(opensOnMemo(hit(false, listOf(snippet), null, match)))
        assertFalse(opensOnMemo(hit(true, emptyList(), null, match)))
        assertFalse(opensOnNotes(hit(false, emptyList(), null, match)))
    }

    @Test
    fun `the memo row shows the first line that says anything, and the field takes 2000 characters`() {
        assertEquals("Agenda", memoPreview("\n   \n  Agenda  \nWith Minsu"))
        assertNull(memoPreview(" \n "))
        assertNull(memoPreview(""))
        assertEquals(2000, memoInput("x".repeat(2100)).length)
        assertEquals("x".repeat(1999), memoInput("x".repeat(1999) + "😀"), "an emoji is not cut in half")
        assertEquals("as typed  \n", memoInput("as typed  \n"))
    }

    @Test
    fun `the person who made the recording is Me until they are named`() {
        val transcript = Transcript(
            recordingId = "r1", track = Track.MIC, language = "en", provider = TranscriptProvider("assemblyai"),
            createdAt = "2026-10-09T10:00:00Z", durationSec = 4.0,
            speakers = listOf(TranscriptSpeaker("S1", me = true), TranscriptSpeaker("S2"), TranscriptSpeaker("S3", "Alex", me = true)),
            segments = emptyList(),
        )
        assertEquals("Me", speakerLabel(transcript, "S1", "Me"))
        assertEquals("나", speakerLabel(transcript, "S1", "나"))
        assertEquals("S2", speakerLabel(transcript, "S2", "Me"))
        assertEquals("Alex", speakerLabel(transcript, "S3", "Me"))
        assertTrue(speakerIsWord(transcript, "S1"))
        assertFalse(speakerIsWord(transcript, "S2"))
        assertTrue(speakerIsWord(transcript, "S3"))
    }

    @Test
    fun `the Ask field starts from the question asked, and Ask needs words and nothing running`() {
        assertEquals("", askedQuestion(AskState.None))
        assertEquals("", askedQuestion(AskState.Running(AskPreset.ACTION_ITEMS, null)))
        assertEquals("Who owns the budget?", askedQuestion(AskState.Running(null, "Who owns the budget?")))
        assertEquals("Why?", askedQuestion(AskState.Ready(AskAnswer("r1", null, "Why?", "Because [00:01].", "gpt-5"))))
        assertEquals("Why?", askedQuestion(AskState.Failed(CoreMessage.PROVIDER_ERROR.code(), null, "Why?")))
        assertFalse(canAsk(AskState.None, "  "))
        assertTrue(canAsk(AskState.None, "Why?"))
        assertFalse(canAsk(AskState.Running(null, "Why?"), "Why?"))
        assertTrue(canAsk(AskState.Failed(CoreMessage.PROVIDER_ERROR.code(), null, "Why?"), "Why?"))
    }

    @Test
    fun `every preset has its own words`() {
        assertEquals(AskPreset.entries.size, AskPreset.entries.map { it.presetLabel() }.toSet().size)
    }
}
