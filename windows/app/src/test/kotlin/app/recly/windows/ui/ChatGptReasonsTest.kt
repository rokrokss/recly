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
import recly.core.message.CoreMessage
import recly.core.model.Track
import recly.core.notes.NoteItem
import recly.core.notes.NoteKind
import recly.core.notes.NotesStage
import recly.core.notes.RecordingNotes
import recly.core.recording.NotesMatch
import recly.core.recording.SearchHit
import recly.core.recording.SearchRange
import recly.core.recording.SearchSnippet
import recly.core.storage.StorageKind
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptProvider
import recly.core.transcribe.TranscriptSegment
import recly.core.transcribe.TranscriptSpeaker

/**
 * docs/09 "AI view" · "Recording detail": what the More menu's Organize and Edit notes say, when the
 * Transcript | Notes chips are there, and how a failed sign-in, Organize or answer is worded.
 */
class ChatGptReasonsTest {

    private val signedIn = ChatGptConnection.SignedIn("me@example.com", listOf(ChatGptModel("gpt-x", "GPT X")), "gpt-x")
    private val item = NoteItem("n1", NoteKind.DECISION, "Ship on Friday", null, null, 31.0, "ship on Friday", provisional = false)

    private fun notes(
        items: List<NoteItem> = emptyList(),
        overview: String? = null,
        organizing: Boolean = false,
        failure: String? = null,
    ) = RecordingNotes("r1", NotesStage.FINAL, overview, items, emptyList(), emptyList(), organizing, failure = failure)

    @Test
    fun `Organize says the one reason it cannot run, in order`() {
        fun blocked(
            writing: Boolean = false,
            hasTranscript: Boolean = true,
            busy: Str? = null,
            connection: ChatGptConnection = signedIn,
            organizing: Boolean = false,
        ) = organizeBlocked(writing, hasTranscript, busy, connection, organizing)

        assertNull(blocked())
        // A take still being written has no transcript either; it says what it is first, as Rename does.
        assertEquals(Str.DETAIL_STILL_RECORDING, blocked(writing = true, hasTranscript = false, connection = ChatGptConnection.SignedOut))
        assertEquals(Str.DETAIL_NO_TRANSCRIPT, blocked(hasTranscript = false, connection = ChatGptConnection.SignedOut))
        assertEquals(Str.DETAIL_TRANSCRIBING, blocked(busy = Str.DETAIL_TRANSCRIBING, connection = ChatGptConnection.SignedOut))
        // Any unsettled job, with its own reason (D16): an upload still to happen, a wait for Drive.
        assertEquals(Str.DETAIL_NOT_UPLOADED, blocked(busy = Str.DETAIL_NOT_UPLOADED))
        assertEquals(Str.DRIVE_PENDING, blocked(busy = Str.DRIVE_PENDING))
        assertEquals(Str.CORE_CHATGPT_SIGN_IN_REQUIRED, blocked(connection = ChatGptConnection.SignedOut))
        assertEquals(Str.CORE_CHATGPT_SIGN_IN_REQUIRED, blocked(connection = ChatGptConnection.Expired("me@example.com")))
        assertEquals(Str.NOTES_ORGANIZING, blocked(organizing = true))
        // Signed out says so before the notes being made, which are this PC's own pass.
        assertEquals(Str.CORE_CHATGPT_SIGN_IN_REQUIRED, blocked(connection = ChatGptConnection.SignedOut, organizing = true))
        // The reason is the core's own sentence for it.
        assertEquals("Sign in to ChatGPT in Settings", StringTable.of(StringTable.BASE)[Str.CORE_CHATGPT_SIGN_IN_REQUIRED])
    }

    @Test
    fun `Organize again once the recording has notes`() {
        assertEquals(Str.NOTES_ORGANIZE, organizeLabel(null))
        assertEquals(Str.NOTES_ORGANIZE, organizeLabel(notes()))
        assertEquals(Str.NOTES_ORGANIZE, organizeLabel(notes(organizing = true)))
        assertEquals(Str.NOTES_ORGANIZE_AGAIN, organizeLabel(notes(listOf(item))))
        assertEquals(Str.NOTES_ORGANIZE_AGAIN, organizeLabel(notes(overview = "We met.")))
        assertEquals("Organize again", base[Str.NOTES_ORGANIZE_AGAIN])
    }

    @Test
    fun `Edit notes waits while the take is written or the notes are made`() {
        assertNull(editNotesBlocked(notes(listOf(item)), writing = false))
        assertEquals(Str.DETAIL_STILL_RECORDING, editNotesBlocked(notes(listOf(item), organizing = true), writing = true))
        assertEquals(Str.NOTES_ORGANIZING, editNotesBlocked(notes(listOf(item), organizing = true), writing = false))
    }

    @Test
    fun `the chips are there once there are notes or Organize was chosen, and never over the transcript's editor`() {
        assertFalse(showsNotesChips(null, notesChosen = false, editing = false))
        assertFalse(showsNotesChips(notes(), notesChosen = false, editing = false))
        assertTrue(showsNotesChips(notes(), notesChosen = true, editing = false))
        assertTrue(showsNotesChips(null, notesChosen = true, editing = false))
        assertTrue(showsNotesChips(notes(listOf(item)), notesChosen = false, editing = false))
        assertTrue(showsNotesChips(notes(failure = CoreMessage.PROVIDER_ERROR.code()), notesChosen = false, editing = false))
        assertFalse(showsNotesChips(notes(listOf(item)), notesChosen = true, editing = true))
    }

    @Test
    fun `a failed Organize is its own sentence where it has one, and Could not organize otherwise`() {
        val limit = chatGptFailure(CoreMessage.CHATGPT_USAGE_LIMIT.code(), Str.NOTES_FAILED)
        assertEquals(coreSentence(CoreMessage.CHATGPT_USAGE_LIMIT), limit.headline.text(base))
        assertEquals(ChatGptRecovery.MANAGE_USAGE, limit.recovery)
        assertNull(limit.detail)

        // The sentence says where to go; there is nothing to press — nor where asking again cannot help (2026-10-10).
        assertEquals(ChatGptRecovery.NONE, chatGptFailure(CoreMessage.CHATGPT_SIGN_IN_REQUIRED.code(), Str.NOTES_FAILED).recovery)
        assertEquals(ChatGptRecovery.NONE, chatGptFailure(CoreMessage.CHATGPT_PLAN_REQUIRED.code(), Str.NOTES_FAILED).recovery)
        assertEquals(ChatGptRecovery.RETRY, chatGptFailure(CoreMessage.PROVIDER_REGION_RESTRICTED.code(), Str.NOTES_FAILED).recovery)
        // A sentence of its own is something to attend to, in the warning tone; the rest failed.
        assertTrue(limit.attention)
        assertTrue(chatGptFailure(CoreMessage.CHATGPT_PLAN_REQUIRED.code(), Str.NOTES_FAILED).attention)
        assertFalse(chatGptFailure(CoreMessage.PROVIDER_ERROR.code(detail = "HTTP 500"), Str.NOTES_FAILED).attention)

        // PROVIDER_ERROR has no sentence of its own here: the headline is the screen's, with the detail under it.
        val provider = chatGptFailure(CoreMessage.PROVIDER_ERROR.code(detail = "HTTP 500: upstream"), Str.NOTES_FAILED)
        assertEquals(Str.NOTES_FAILED.message(), provider.headline)
        assertEquals("HTTP 500: upstream", provider.detail)
        assertEquals(ChatGptRecovery.RETRY, provider.recovery)

        val step = chatGptFailure(CoreMessage.STEP_FAILED.code("no transcript"), Str.NOTES_FAILED)
        assertEquals(Str.NOTES_FAILED.message(), step.headline)
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
        assertEquals("GPT X", chatGptModelLabel("gpt-x", null, signedIn))
        assertEquals("gpt-old", chatGptModelLabel("gpt-old", null, signedIn))
        assertEquals("gpt-x", chatGptModelLabel("gpt-x", null, ChatGptConnection.SignedOut))
        // The name kept with the answer reads the same signed out, and over a plan that names it otherwise now.
        assertEquals("GPT-5.4", chatGptModelLabel("gpt-5.4", "GPT-5.4", ChatGptConnection.SignedOut))
        assertEquals("GPT X (2026)", chatGptModelLabel("gpt-x", "GPT X (2026)", signedIn))
        assertEquals("ChatGPT · GPT X", base[Str.SUMMARY_MODEL, "GPT X"])
    }

    @Test
    fun `the editor's note says whether the other devices will show the edit`() {
        assertEquals(Str.NOTES_EDIT_NOTE_SHARED, notesEditNote(StorageKind.DRIVE))
        assertEquals(Str.NOTES_EDIT_NOTE_SHARED, notesEditNote(StorageKind.ICLOUD))
        assertEquals(Str.NOTES_EDIT_NOTE_LOCAL, notesEditNote(StorageKind.FOLDER))
        // Not uploaded yet: nothing has reached a folder another device reads.
        assertEquals(Str.NOTES_EDIT_NOTE_LOCAL, notesEditNote(null))
    }

    @Test
    fun `Ask waits for what Organize waits for, but not for the notes being made`() {
        assertNull(askBlocked(writing = false, hasTranscript = true, busy = null, connection = signedIn))
        assertEquals(Str.DETAIL_STILL_RECORDING, askBlocked(writing = true, hasTranscript = false, busy = null, connection = signedIn))
        assertEquals(Str.DETAIL_NO_TRANSCRIPT, askBlocked(writing = false, hasTranscript = false, busy = null, connection = signedIn))
        assertEquals(Str.DETAIL_TRANSCRIBING, askBlocked(writing = false, hasTranscript = true, busy = Str.DETAIL_TRANSCRIBING, connection = signedIn))
        assertEquals(
            Str.CORE_CHATGPT_SIGN_IN_REQUIRED,
            askBlocked(writing = false, hasTranscript = true, busy = null, connection = ChatGptConnection.SignedOut),
        )
        assertEquals(Str.NOTES_ORGANIZING, organizeBlocked(false, true, null, signedIn, organizing = true))
    }

    @Test
    fun `an answer is cut at its times, and nothing else is`() {
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
        val failed = chatGptFailure(CoreMessage.PROVIDER_ERROR.code(detail = "HTTP 500"), Str.ASK_FAILED)
        assertEquals("Could not answer", failed.headline.text(base))
        assertEquals("HTTP 500", failed.detail)
        assertEquals(ChatGptRecovery.RETRY, failed.recovery)
        assertEquals(ChatGptRecovery.MANAGE_USAGE, chatGptFailure(CoreMessage.CHATGPT_USAGE_LIMIT.code(), Str.ASK_FAILED).recovery)
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
    fun `a hit found in the notes alone opens on the notes, with Notes in front of its line`() {
        val match = NotesMatch("Ship the release on Friday", listOf(SearchRange(9, 7)))
        fun hit(title: Boolean = false, snippets: Boolean = false, notes: NotesMatch? = match) = SearchHit(
            "r1", "Weekly", "2026-10-09T10:00:00Z", title, if (title) listOf(SearchRange(0, 6)) else emptyList(),
            if (snippets) listOf(SearchSnippet(31.0, "the release", listOf(SearchRange(4, 7)))) else emptyList(), notes,
        )
        assertTrue(opensOnNotes(hit()))
        assertFalse(opensOnNotes(hit(title = true)))
        assertFalse(opensOnNotes(hit(snippets = true)))
        assertFalse(opensOnNotes(hit(title = true, notes = null)))

        val line = notesSnippet(base[Str.NOTES_TAB], match)
        assertEquals("Notes · Ship the release on Friday", line.text)
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
