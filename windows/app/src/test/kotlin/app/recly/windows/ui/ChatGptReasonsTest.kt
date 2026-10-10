package app.recly.windows.ui

import app.recly.windows.i18n.Str
import app.recly.windows.plain
import app.recly.windows.i18n.StringTable
import app.recly.windows.i18n.UiMessage
import app.recly.windows.i18n.message
import app.recly.windows.i18n.text
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
import recly.core.transcribe.TranscriptSegment
import recly.core.transcribe.TranscriptSpeaker

/**
 * docs/09 "Summary view" · "Recording detail": what the More menu's Summarize and Edit summary say, when the
 * Transcript | Summary chips are there, and how a failed sign-in or summary is worded.
 */
class ChatGptReasonsTest {

    private val signedIn = ChatGptConnection.SignedIn("me@example.com", listOf(ChatGptModel("gpt-x", "GPT X")), "gpt-x")
    private val summary = Summary("r1", "- one\n- two", "gpt-x", "2026-10-09T10:00:00Z")

    @Test
    fun `Summarize says the one reason it cannot run, in order`() {
        fun blocked(
            writing: Boolean = false,
            hasTranscript: Boolean = true,
            transcriptionRunning: Boolean = false,
            connection: ChatGptConnection = signedIn,
            summary: SummaryState = SummaryState.None,
        ) = summarizeBlocked(writing, hasTranscript, transcriptionRunning, connection, summary)

        assertNull(blocked())
        // A take still being written has no transcript either; it says what it is first, as Rename does.
        assertEquals(Str.DETAIL_STILL_RECORDING, blocked(writing = true, hasTranscript = false, connection = ChatGptConnection.SignedOut))
        assertEquals(Str.DETAIL_NO_TRANSCRIPT, blocked(hasTranscript = false, connection = ChatGptConnection.SignedOut))
        assertEquals(Str.DETAIL_TRANSCRIBING, blocked(transcriptionRunning = true, connection = ChatGptConnection.SignedOut))
        assertEquals(Str.CORE_CHATGPT_SIGN_IN_REQUIRED, blocked(connection = ChatGptConnection.SignedOut))
        assertEquals(Str.CORE_CHATGPT_SIGN_IN_REQUIRED, blocked(connection = ChatGptConnection.Expired("me@example.com")))
        assertEquals(Str.SUMMARY_RUNNING, blocked(summary = SummaryState.Running(previous = null)))
        // A failed or finished summary can be asked for again.
        assertNull(blocked(summary = SummaryState.Failed(CoreMessage.CHATGPT_USAGE_LIMIT.code(), previous = null)))
        assertNull(blocked(summary = SummaryState.Ready(summary)))
        // The reason is the core's own sentence for it.
        assertEquals("Sign in to ChatGPT in Settings", StringTable.of(StringTable.BASE)[Str.CORE_CHATGPT_SIGN_IN_REQUIRED])
    }

    @Test
    fun `Summarize again once there is a summary to replace`() {
        assertEquals(Str.SUMMARY_SUMMARIZE, summarizeLabel(SummaryState.None))
        assertEquals(Str.SUMMARY_SUMMARIZE, summarizeLabel(SummaryState.Running(previous = null)))
        assertEquals(Str.SUMMARY_SUMMARIZE, summarizeLabel(SummaryState.Failed("PROVIDER_ERROR", previous = null)))
        assertEquals(Str.SUMMARY_AGAIN, summarizeLabel(SummaryState.Ready(summary)))
        assertEquals(Str.SUMMARY_AGAIN, summarizeLabel(SummaryState.Running(previous = summary)))
        assertEquals(Str.SUMMARY_AGAIN, summarizeLabel(SummaryState.Failed("PROVIDER_ERROR", previous = summary)))
    }

    @Test
    fun `the chips are there once there is a summary or one was asked for, and never over the editor`() {
        assertFalse(showsSummaryChips(SummaryState.None, summaryChosen = false, editing = false))
        assertTrue(showsSummaryChips(SummaryState.None, summaryChosen = true, editing = false))
        assertTrue(showsSummaryChips(SummaryState.Ready(summary), summaryChosen = false, editing = false))
        assertTrue(showsSummaryChips(SummaryState.Failed("PROVIDER_ERROR", null), summaryChosen = false, editing = false))
        assertFalse(showsSummaryChips(SummaryState.Ready(summary), summaryChosen = true, editing = true))
    }

    @Test
    fun `a failed summary is its own sentence where it has one, and Could not summarize otherwise`() {
        val limit = summaryFailure(CoreMessage.CHATGPT_USAGE_LIMIT.code())
        assertEquals(coreSentence(CoreMessage.CHATGPT_USAGE_LIMIT), limit.headline.text(base))
        assertEquals(SummaryRecovery.MANAGE_USAGE, limit.recovery)
        assertNull(limit.detail)

        // The sentence says where to go; there is nothing to press — nor where asking again cannot help (2026-10-10).
        assertEquals(SummaryRecovery.NONE, summaryFailure(CoreMessage.CHATGPT_SIGN_IN_REQUIRED.code()).recovery)
        assertEquals(SummaryRecovery.NONE, summaryFailure(CoreMessage.CHATGPT_PLAN_REQUIRED.code()).recovery)
        assertEquals(SummaryRecovery.RETRY, summaryFailure(CoreMessage.PROVIDER_REGION_RESTRICTED.code()).recovery)
        // A sentence of its own is something to attend to, in the warning tone; the rest failed.
        assertTrue(limit.attention)
        assertTrue(summaryFailure(CoreMessage.CHATGPT_PLAN_REQUIRED.code()).attention)
        assertFalse(summaryFailure(CoreMessage.PROVIDER_ERROR.code(detail = "HTTP 500")).attention)

        // PROVIDER_ERROR has no sentence of its own here: the headline is the screen's, with the detail under it.
        val provider = summaryFailure(CoreMessage.PROVIDER_ERROR.code(detail = "HTTP 500: upstream"))
        assertEquals(Str.SUMMARY_FAILED.message(), provider.headline)
        assertEquals("HTTP 500: upstream", provider.detail)
        assertEquals(SummaryRecovery.RETRY, provider.recovery)

        val step = summaryFailure(CoreMessage.STEP_FAILED.code("no transcript"))
        assertEquals(Str.SUMMARY_FAILED.message(), step.headline)
        assertEquals("no transcript", step.detail)
    }

    @Test
    fun `a failed sign-in says the reason's sentence or its diagnostic, and a cancelled one says nothing`() {
        assertTrue(cancelled(CoreMessage.SIGN_IN_CANCELLED.code()))
        assertFalse(cancelled(CoreMessage.PROVIDER_ERROR.code(detail = "state mismatch")))

        assertEquals(coreSentence(CoreMessage.CHATGPT_PLAN_REQUIRED), signInFailureLine(CoreMessage.CHATGPT_PLAN_REQUIRED.code())?.text(base))
        assertEquals(UiMessage.Text("account changed"), signInFailureLine(CoreMessage.PROVIDER_ERROR.code(detail = "account changed")))
        // A sign-in this shell could not start at all — no browser, no port — is its diagnostic as it came.
        assertEquals(UiMessage.Text("Address already in use"), signInFailureLine("Address already in use"))
        assertNull(signInFailureLine(CoreMessage.PROVIDER_ERROR.code()))
    }

    @Test
    fun `the footer names the model the way the plan does, or by its id`() {
        assertEquals("GPT X", summaryModelLabel("gpt-x", null, signedIn))
        assertEquals("gpt-old", summaryModelLabel("gpt-old", null, signedIn))
        assertEquals("gpt-x", summaryModelLabel("gpt-x", null, ChatGptConnection.SignedOut))
        // The name kept with the summary reads the same signed out, and over a plan that names it otherwise now.
        assertEquals("GPT-5.4", summaryModelLabel("gpt-5.4", "GPT-5.4", ChatGptConnection.SignedOut))
        assertEquals("GPT X (2026)", summaryModelLabel("gpt-x", "GPT X (2026)", signedIn))
        assertEquals("ChatGPT · GPT X", base[Str.SUMMARY_MODEL, "GPT X"])
    }

    @Test
    fun `Edit summary is offered over a summary, and waits while a new one is made`() {
        assertEquals(SummaryEditItem(summary, null), summaryEditItem(SummaryState.Ready(summary)))
        assertEquals(SummaryEditItem(summary, Str.SUMMARY_RUNNING), summaryEditItem(SummaryState.Running(summary)))
        assertNull(summaryEditItem(SummaryState.Running(null)))
        assertNull(summaryEditItem(SummaryState.None))
        assertNull(summaryEditItem(SummaryState.Failed(CoreMessage.PROVIDER_ERROR.code(), null)))
        assertEquals(SummaryEditItem(summary, null), summaryEditItem(SummaryState.Failed(CoreMessage.PROVIDER_ERROR.code(), summary)))
    }

    @Test
    fun `Summarize again asks first only over a summary the user edited`() {
        val edited = summary.copy(editedAt = "2026-10-09T11:00:00Z")
        assertFalse(summarizeAsksFirst(SummaryState.None))
        assertFalse(summarizeAsksFirst(SummaryState.Ready(summary)))
        assertTrue(summarizeAsksFirst(SummaryState.Ready(edited)))
        // Summarize again after a failure replaces the summary still kept under it (Retry does not ask).
        assertTrue(summarizeAsksFirst(SummaryState.Failed(CoreMessage.PROVIDER_ERROR.code(), edited)))
        assertFalse(summarizeAsksFirst(SummaryState.Failed(CoreMessage.PROVIDER_ERROR.code(), null)))
    }

    @Test
    fun `an edit is text that differs once trimmed, and an emptied field is none`() {
        assertFalse(summaryEdited("- one", "- one"))
        assertFalse(summaryEdited("- one", "  - one\n"))
        assertFalse(summaryEdited("- one", "   "))
        assertTrue(summaryEdited("- one", "- one\n- two"))
    }

    @Test
    fun `the editor's note says whether the other devices will show the edit`() {
        assertEquals(Str.SUMMARY_EDIT_NOTE_SHARED, summaryEditNote(StorageKind.DRIVE))
        assertEquals(Str.SUMMARY_EDIT_NOTE_SHARED, summaryEditNote(StorageKind.ICLOUD))
        assertEquals(Str.SUMMARY_EDIT_NOTE_LOCAL, summaryEditNote(StorageKind.FOLDER))
        // Not uploaded yet: nothing has reached a folder another device reads.
        assertEquals(Str.SUMMARY_EDIT_NOTE_LOCAL, summaryEditNote(null))
    }

    @Test
    fun `Ask waits for what Summarize waits for, but not for a summary being made`() {
        assertNull(askBlocked(writing = false, hasTranscript = true, transcriptionRunning = false, connection = signedIn))
        assertEquals(Str.DETAIL_STILL_RECORDING, askBlocked(writing = true, hasTranscript = false, transcriptionRunning = false, connection = signedIn))
        assertEquals(Str.DETAIL_NO_TRANSCRIPT, askBlocked(writing = false, hasTranscript = false, transcriptionRunning = false, connection = signedIn))
        assertEquals(Str.DETAIL_TRANSCRIBING, askBlocked(writing = false, hasTranscript = true, transcriptionRunning = true, connection = signedIn))
        assertEquals(
            Str.CORE_CHATGPT_SIGN_IN_REQUIRED,
            askBlocked(writing = false, hasTranscript = true, transcriptionRunning = false, connection = ChatGptConnection.SignedOut),
        )
        // Summarize as… is Summarize's own item, with its reasons, the running summary among them.
        assertEquals(Str.SUMMARY_RUNNING, summarizeBlocked(false, true, false, signedIn, SummaryState.Running(summary)))
        assertNull(askBlocked(writing = false, hasTranscript = true, transcriptionRunning = false, connection = signedIn))
    }

    @Test
    fun `the footer adds the format unless it is General, then Edited`() {
        assertEquals("ChatGPT · GPT X", summaryFooter(base, "GPT X", SummaryFormat.AUTO, edited = false))
        assertEquals("ChatGPT · GPT X · Lecture", summaryFooter(base, "GPT X", SummaryFormat.LECTURE, edited = false))
        assertEquals("ChatGPT · GPT X · My format · Edited", summaryFooter(base, "GPT X", SummaryFormat.CUSTOM, edited = true))
        assertEquals("ChatGPT · GPT X · Edited", summaryFooter(base, "GPT X", SummaryFormat.AUTO, edited = true))
        assertEquals(SummaryFormat.ONE_ON_ONE, summary.copy(format = "one_on_one").summaryFormat)
        assertEquals("ChatGPT · GPT X · 1:1", summaryFooter(StringTable.of(StringTable.KOREAN), "GPT X", SummaryFormat.ONE_ON_ONE, edited = false))
    }

    @Test
    fun `a summary is cut at its times, and nothing else is`() {
        val text = "- Ship on Friday [00:00:31]\n- Notes by Thursday [1:05][00:02]. [99:99] is not one"
        val runs = citationRuns(text)

        assertEquals(text, runs.joinToString("") { it.text }, "every character once, in order")
        assertEquals(
            listOf(
                CitationRun("- Ship on Friday "),
                CitationRun("[00:00:31]", "00:00:31", 31.0),
                CitationRun("\n- Notes by Thursday "),
                CitationRun("[1:05]", "1:05", 65.0),
                CitationRun("[00:02]", "00:02", 2.0),
                CitationRun(". [99:99] is not one"),
            ),
            runs,
        )
        assertEquals(listOf(CitationRun("[00:12:34]", "00:12:34", 754.0)), citationRuns("[00:12:34]"))
        assertEquals(listOf(CitationRun("No times here.")), citationRuns("No times here."))
        assertEquals(emptyList(), citationRuns(""))
        assertEquals("Play from 00:00:31", base[Str.SUMMARY_PLAY_FROM, runs[1].time])
    }

    @Test
    fun `a failed answer says Could not answer where the reason has no sentence`() {
        val failed = summaryFailure(CoreMessage.PROVIDER_ERROR.code(detail = "HTTP 500"), Str.ASK_FAILED)
        assertEquals("Could not answer", failed.headline.text(base))
        assertEquals("HTTP 500", failed.detail)
        assertEquals(SummaryRecovery.RETRY, failed.recovery)
        assertEquals(SummaryRecovery.MANAGE_USAGE, summaryFailure(CoreMessage.CHATGPT_USAGE_LIMIT.code(), Str.ASK_FAILED).recovery)
    }

    @Test
    fun `the presets are named, Translate in the app's own language name`() {
        assertEquals("Translate to English", askPresetLabel(AskPreset.TRANSLATE, base))
        assertEquals("한국어로 번역", askPresetLabel(AskPreset.TRANSLATE, StringTable.of(StringTable.KOREAN)).plain())
        assertEquals("Feedback on how I spoke", askPresetLabel(AskPreset.MY_SPEAKING, base))
        assertEquals("Follow-up email", askPresetLabel(AskPreset.FOLLOW_UP_EMAIL, base))
        val answer = AskAnswer("r1", AskPreset.ACTION_ITEMS, null, "- Mina: notes [00:31]", "gpt-x")
        assertEquals(AskPreset.ACTION_ITEMS, AskState.Ready(answer).askedPreset())
        assertNull(AskState.Running(null, "When do we ship?").askedPreset())
        assertEquals("When do we ship?", AskState.Failed("PROVIDER_ERROR", null, "When do we ship?").askedQuestion())
    }

    @Test
    fun `a hit found in the summary alone opens on the summary, with Summary in front of its line`() {
        val match = SummaryMatch("Ship the release on Friday", listOf(SearchRange(9, 7)))
        fun hit(title: Boolean = false, snippets: Boolean = false, summary: SummaryMatch? = match) = SearchHit(
            "r1", "Weekly", "2026-10-09T10:00:00Z", title, if (title) listOf(SearchRange(0, 6)) else emptyList(),
            if (snippets) listOf(SearchSnippet(31.0, "the release", listOf(SearchRange(4, 7)))) else emptyList(), summary,
        )
        assertTrue(opensOnSummary(hit()))
        assertFalse(opensOnSummary(hit(title = true)))
        assertFalse(opensOnSummary(hit(snippets = true)))
        assertFalse(opensOnSummary(hit(title = true, summary = null)))

        val line = summarySnippet("Summary", match)
        assertEquals("Summary · Ship the release on Friday", line.text)
        assertEquals("release", line.text.substring(line.ranges.single().offset, line.ranges.single().offset + line.ranges.single().length))
    }

    @Test
    fun `the person who made the recording is Me until they are named, also in the editor`() {
        assertEquals("Me", speakerName(TranscriptSpeaker("S1", me = true), base))
        assertEquals("나", speakerName(TranscriptSpeaker("S1", me = true), StringTable.of(StringTable.KOREAN)))
        assertEquals("Mina", speakerName(TranscriptSpeaker("S1", "Mina", me = true), base))
        assertNull(speakerName(TranscriptSpeaker("S2"), base))
        assertNull(speakerName(null, base))

        val transcript = Transcript(
            recordingId = "r1", track = Track.MONO, language = "en", provider = TranscriptProvider("assemblyai"),
            createdAt = "2026-10-10T00:00:00Z", durationSec = 10.0,
            speakers = listOf(TranscriptSpeaker("S1", me = true), TranscriptSpeaker("S2", "Mina")),
            segments = listOf(TranscriptSegment(0.0, 5.0, "S1", "Hi."), TranscriptSegment(5.0, 10.0, "S2", "Hello.")),
        )
        val draft = TranscriptDraft(transcript)
        draft.rename("S1", "Hyungrok")
        draft.assign(1, null)
        // Renamed, still the one who made it; and a new speaker is nobody's "me".
        assertEquals(
            listOf(TranscriptSpeaker("S1", "Hyungrok", me = true), TranscriptSpeaker("S2", "Mina"), TranscriptSpeaker("S3")),
            draft.people,
        )
    }

    private val base = StringTable.of(StringTable.BASE)

    private fun coreSentence(message: CoreMessage): String =
        base[app.recly.windows.i18n.CoreMessages.keyOf(message), ""]
}
