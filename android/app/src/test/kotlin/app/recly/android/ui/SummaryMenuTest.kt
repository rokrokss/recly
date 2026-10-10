package app.recly.android.ui

import app.recly.android.R
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
import recly.core.chatgpt.Summary
import recly.core.chatgpt.SummaryFormat
import recly.core.chatgpt.SummaryState
import recly.core.message.CoreMessage
import recly.core.model.Track
import recly.core.recording.SearchHit
import recly.core.recording.SearchRange
import recly.core.recording.SearchSnippet
import recly.core.recording.SummaryMatch
import recly.core.storage.StorageKind
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptProvider
import recly.core.transcribe.TranscriptSpeaker

/**
 * docs/09 "Detail header and More menu" · "Summary view": why Summarize waits, in the order the reasons are
 * checked; when it says "again"; when the Transcript | Summary chips are there; what a failed summary offers; the
 * summary editor's item, question, note and draft; Summarize as and Ask; the footer; citations; a summary-only search
 * hit; and "Me".
 */
class SummaryMenuTest {

    private val signedIn = ChatGptConnection.SignedIn("me@example.com", listOf(ChatGptModel("gpt-5", "GPT-5")), "gpt-5")
    private val summary = Summary("r1", "Summary\n- one", "gpt-5", "2026-10-09T10:00:00Z")

    @Test
    fun `no transcript comes first`() {
        assertEquals(R.string.detail_no_transcript,
            summarizeReason(false, transcribing = true, busyReason = R.string.detail_transcribing, ChatGptConnection.SignedOut, SummaryState.Running(null)))
    }

    @Test
    fun `a transcription on its way says what the menu already says about it`() {
        assertEquals(R.string.detail_transcribing, summarizeReason(true, true, R.string.detail_transcribing, ChatGptConnection.SignedOut, SummaryState.None))
        assertEquals(R.string.detail_not_uploaded, summarizeReason(true, true, R.string.detail_not_uploaded, signedIn, SummaryState.None))
    }

    @Test
    fun `signed out or expired sends the user to Settings`() {
        assertEquals(R.string.core_chatgpt_sign_in_required, summarizeReason(true, false, R.string.detail_transcribing, ChatGptConnection.SignedOut, SummaryState.None))
        assertEquals(R.string.core_chatgpt_sign_in_required,
            summarizeReason(true, false, R.string.detail_transcribing, ChatGptConnection.Expired("me@example.com"), SummaryState.Ready(summary)))
    }

    @Test
    fun `a summary already being written says so`() {
        assertEquals(R.string.summary_running, summarizeReason(true, false, R.string.detail_transcribing, signedIn, SummaryState.Running(summary)))
    }

    @Test
    fun `signed in with a transcript, it can run - again after a summary or a failure`() {
        assertNull(summarizeReason(true, false, R.string.detail_transcribing, signedIn, SummaryState.None))
        assertNull(summarizeReason(true, false, R.string.detail_transcribing, signedIn, SummaryState.Ready(summary)))
        assertNull(summarizeReason(true, false, R.string.detail_transcribing, signedIn, SummaryState.Failed(CoreMessage.CHATGPT_USAGE_LIMIT.code(), null)))
    }

    @Test
    fun `Summarize again once there is a summary to replace`() {
        assertFalse(hasSummary(SummaryState.None))
        assertFalse(hasSummary(SummaryState.Running(null)))
        assertFalse(hasSummary(SummaryState.Failed(CoreMessage.PROVIDER_ERROR.code(), null)))
        assertTrue(hasSummary(SummaryState.Ready(summary)))
        assertTrue(hasSummary(SummaryState.Running(summary)))
        assertTrue(hasSummary(SummaryState.Failed(CoreMessage.PROVIDER_ERROR.code(), summary)))
    }

    @Test
    fun `the chips are there for a summary or one just asked for, never while editing`() {
        assertFalse(showsSummaryChips(SummaryState.None, asked = false, editing = false))
        assertTrue(showsSummaryChips(SummaryState.None, asked = true, editing = false))
        assertTrue(showsSummaryChips(SummaryState.Ready(summary), asked = false, editing = false))
        assertTrue(showsSummaryChips(SummaryState.Failed(CoreMessage.PROVIDER_ERROR.code(), null), asked = false, editing = false))
        assertFalse(showsSummaryChips(SummaryState.Ready(summary), asked = true, editing = true))
    }

    @Test
    fun `a failed summary offers the one button that helps`() {
        assertEquals(SummaryRecovery.MANAGE_USAGE, summaryRecovery(CoreMessage.CHATGPT_USAGE_LIMIT.code()))
        assertEquals(SummaryRecovery.NONE, summaryRecovery(CoreMessage.CHATGPT_SIGN_IN_REQUIRED.code()))
        assertEquals(SummaryRecovery.RETRY, summaryRecovery(CoreMessage.CHATGPT_PLAN_REQUIRED.code()))
        assertEquals(SummaryRecovery.RETRY, summaryRecovery(CoreMessage.PROVIDER_ERROR.code(detail = "500")))
        assertEquals(SummaryRecovery.RETRY, summaryRecovery("not a key"))
    }

    @Test
    fun `Edit summary is there once there is a summary, and waits while a new one is written`() {
        val edited = summary.copy(editedAt = "2026-10-09T11:00:00Z")
        assertNull(savedSummary(SummaryState.None))
        assertNull(savedSummary(SummaryState.Running(null)))
        assertEquals(summary, savedSummary(SummaryState.Ready(summary)))
        assertEquals(edited, savedSummary(SummaryState.Running(edited)))
        assertEquals(summary, savedSummary(SummaryState.Failed(CoreMessage.PROVIDER_ERROR.code(), summary)))
        assertNull(editSummaryReason(SummaryState.Ready(summary)))
        assertNull(editSummaryReason(SummaryState.Failed(CoreMessage.PROVIDER_ERROR.code(), summary)))
        assertEquals(R.string.summary_running, editSummaryReason(SummaryState.Running(summary)))
    }

    @Test
    fun `Summarize again asks first only over a summary the user edited`() {
        val edited = summary.copy(editedAt = "2026-10-09T11:00:00Z")
        assertFalse(asksBeforeReplacing(SummaryState.None))
        assertFalse(asksBeforeReplacing(SummaryState.Ready(summary)))
        assertTrue(asksBeforeReplacing(SummaryState.Ready(edited)))
        assertTrue(asksBeforeReplacing(SummaryState.Failed(CoreMessage.PROVIDER_ERROR.code(), edited)))
        assertFalse(asksBeforeReplacing(SummaryState.Failed(CoreMessage.PROVIDER_ERROR.code(), null)))
    }

    @Test
    fun `the editor's note says the summary travels only with Drive or iCloud`() {
        assertEquals(R.string.summary_edit_note_shared, summaryEditNote(StorageKind.DRIVE))
        assertEquals(R.string.summary_edit_note_shared, summaryEditNote(StorageKind.ICLOUD))
        assertEquals(R.string.summary_edit_note_local, summaryEditNote(StorageKind.FOLDER))
        // Not uploaded yet: there is no folder for it to go to.
        assertEquals(R.string.summary_edit_note_local, summaryEditNote(null))
    }

    @Test
    fun `a draft has changed only when Save would write it`() {
        val draft = SummaryDraft.of(summary)
        assertFalse(draft.changed)
        assertTrue(draft.copy(text = "Summary\n- two").changed)
        // The core trims, and keeps the summary for blank text.
        assertFalse(draft.copy(text = "  ${summary.text}\n").changed)
        assertFalse(draft.copy(text = "   ").changed)
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
    fun `Ask waits for what Summarize waits for, but not for a summary being written`() {
        assertEquals(R.string.detail_no_transcript, askReason(false, true, R.string.detail_transcribing, ChatGptConnection.SignedOut))
        assertEquals(R.string.detail_not_uploaded, askReason(true, true, R.string.detail_not_uploaded, signedIn))
        assertEquals(R.string.core_chatgpt_sign_in_required, askReason(true, false, R.string.detail_transcribing, ChatGptConnection.SignedOut))
        assertEquals(R.string.core_chatgpt_sign_in_required, askReason(true, false, R.string.detail_transcribing, ChatGptConnection.Expired("me@example.com")))
        assertNull(askReason(true, false, R.string.detail_transcribing, signedIn))
        // Summarize as waits as Summarize does, a running summary included.
        assertEquals(R.string.summary_running, summarizeReason(true, false, R.string.detail_transcribing, signedIn, SummaryState.Running(null)))
    }

    @Test
    fun `Summarize as marks the format the summary was written in`() {
        assertNull(currentFormat(SummaryState.None))
        assertEquals(SummaryFormat.AUTO, currentFormat(SummaryState.Ready(summary)))
        assertEquals(SummaryFormat.LECTURE, currentFormat(SummaryState.Ready(summary.copy(format = "lecture"))))
        assertEquals(SummaryFormat.INTERVIEW, currentFormat(SummaryState.Running(summary.copy(format = "interview"))))
    }

    @Test
    fun `every format has its own words`() {
        assertEquals(SummaryFormat.entries.size, SummaryFormat.entries.map { it.formatLabel() }.toSet().size)
        assertEquals(R.string.summary_format_general, SummaryFormat.AUTO.formatLabel())
        assertEquals(R.string.summary_format_custom, SummaryFormat.CUSTOM.formatLabel())
    }

    @Test
    fun `the footer names the format unless it is General, then Edited`() {
        assertNull(footerFormat(summary))
        assertEquals(SummaryFormat.ONE_ON_ONE, footerFormat(summary.copy(format = "one_on_one")))
        // A format this build does not know is read as General, and not named.
        assertNull(footerFormat(summary.copy(format = "someday")))
        assertEquals("ChatGPT · GPT-5", footerLine("ChatGPT · GPT-5", null, null))
        assertEquals("ChatGPT · GPT-5 · Lecture", footerLine("ChatGPT · GPT-5", "Lecture", null))
        assertEquals("ChatGPT · GPT-5 · Edited", footerLine("ChatGPT · GPT-5", null, "Edited"))
        assertEquals("ChatGPT · GPT-5 · Lecture · Edited", footerLine("ChatGPT · GPT-5", "Lecture", "Edited"))
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
    fun `text with no citation, or one that starts and ends on one, is still covered`() {
        assertEquals(listOf(TextRun(0, 5)), textRuns("plain"))
        assertEquals(emptyList(), textRuns(""))
        val text = "[00:01]"
        assertEquals(listOf(0 to 7), textRuns(text).map { it.start to it.end })
        // Not a time: 75 seconds stays text.
        assertEquals(listOf(TextRun(0, 8)), textRuns("[00:75] "))
    }

    @Test
    fun `a hit opens on the summary only when the summary is all it found`() {
        val match = SummaryMatch("- Budget approved [00:01:00]", listOf(SearchRange(2, 6)))
        val snippet = SearchSnippet(12.0, "the budget", listOf(SearchRange(4, 6)))
        fun hit(inTitle: Boolean, snippets: List<SearchSnippet>, summary: SummaryMatch?) =
            SearchHit("r1", "Weekly", "2026-10-09T10:00:00Z", inTitle, emptyList(), snippets, summary)
        assertTrue(opensOnSummary(hit(false, emptyList(), match)))
        assertFalse(opensOnSummary(hit(false, listOf(snippet), match)))
        assertFalse(opensOnSummary(hit(true, emptyList(), match)))
        assertFalse(opensOnSummary(hit(false, listOf(snippet), null)))
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
