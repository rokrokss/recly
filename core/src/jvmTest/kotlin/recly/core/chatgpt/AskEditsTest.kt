package recly.core.chatgpt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import recly.core.model.RecordingMeta
import recly.core.model.Track
import recly.core.model.recJson
import recly.core.notes.NoteEvidence
import recly.core.notes.NoteItem
import recly.core.notes.NoteKind
import recly.core.notes.NoteSection
import recly.core.notes.NotesFile
import recly.core.notes.NotesStage
import recly.core.notes.RecordingNotes
import recly.core.notes.StoredNote
import recly.core.notes.StoredSection
import recly.core.recording.MetaWriter
import recly.core.testing.CoreFixture
import recly.core.testing.FakeLocalEngine
import recly.core.transcribe.TranscribeRunner
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptEdit
import recly.core.transcribe.TranscriptProvider
import recly.core.transcribe.TranscriptSegment
import recly.core.transcribe.TranscriptSpeaker
import recly.core.transcribe.TranscriptVersionKind

/** docs/08 "Ask" — "Changes", through the whole core, and the proposal's own rules. */
class AskEditsTest {

    private val chatGpt = ChatGptHarness()
    private val f = CoreFixture(engine = FakeLocalEngine(supportsDiarization = true), chatGpt = chatGpt.server.transport(chatGpt.fs))

    private fun answer(text: String) {
        chatGpt.server.reply(
            "data: {\"type\":\"response.output_text.delta\",\"delta\":" + kotlinx.serialization.json.Json.encodeToString(String.serializer(), text) +
                "}\n\ndata: {\"type\":\"response.completed\",\"response\":{}}\n\n",
            headers = mapOf("Content-Type" to "text/event-stream"),
        )
    }

    private fun requests() = chatGpt.server.requests.filter { it.url.endsWith("/responses") && AskPrompts.REQUEST_HEADING in it.text }

    private suspend fun ask(meta: RecordingMeta, question: String): AskAnswer =
        assertIs<AskState.Ready>(f.core.summaries.ask(meta.recordingId, null, question)).answer

    /** Final notes, as a final pass writes them: a decision and a to-do, the built-in groups and a section of the user's own. */
    private suspend fun finalNotes(meta: RecordingMeta) {
        val at = "2026-08-26T02:00:00.000Z"
        val file = NotesFile(
            recordingId = meta.recordingId, stage = NotesFile.FINAL, overview = "Weekly sync about the 레클리 launch.",
            items = listOf(
                StoredNote("D1", NoteKind.DECISION, "레클리 ships on Friday.", evidence = NoteEvidence(0.0, "hello 1"), provisional = false, createdAt = at),
                StoredNote("T1", NoteKind.TODO, "Write the notes.", owner = "S2", evidence = NoteEvidence(1.0, "hello 2"), provisional = false, createdAt = at),
            ),
            sections = NoteSection.DEFAULTS.map(StoredSection::of) + StoredSection("risks", "Risks", "What could go wrong"),
            createdAt = at, updatedAt = at, finalizedAt = at,
        )
        f.core.recordings.writeFile(meta.recordingId, NotesFile.name(MetaWriter.baseName(meta)), recJson.encodeToString(NotesFile.serializer(), file))
    }

    private suspend fun notes(meta: RecordingMeta): RecordingNotes = f.core.notes.get(meta.recordingId)

    private fun RecordingNotes.texts(items: List<NoteItem>) = items.map { it.text }

    @Test
    fun `one request changes the transcript and the summary at once, and Undo brings both back`() = runBlocking {
        chatGpt.signIn(account = f.core.chatGpt)
        val meta = f.recordAndRun()
        finalNotes(meta)
        answer(
            """```json
            {"lines":[{"n":1,"text":"Hello, Recly."},{"n":2,"speaker":"S1"}],"names":{"S1":"Mina"},
             "overview":"Weekly sync about the Recly launch.",
             "items":[{"n":1,"text":"Recly ships on Friday."},{"n":2,"text":""}],
             "add":[{"section":"risks","text":"The launch could slip.","line":2},
                    {"section":"todo","text":"Send the build.","line":1,"owner":"S1","due":"Friday"}]}
            ```""",
        )

        val answer = ask(meta, "  레클리 is spelled Recly.\n\nOther transcript:\n[00:00] Mina: Hello, Recly.  ")

        assertEquals("", answer.text, "a change alone says nothing")
        val changes = assertNotNull(answer.changes)
        assertEquals(listOf(0, 1), changes.segments)
        assertEquals(1, changes.names)
        assertEquals(3, changes.transcriptCount)
        assertEquals("D1", changes.items.first())
        assertEquals(3, changes.items.size, "the decision changed and the two added")
        assertEquals(1, changes.removed)
        assertTrue(changes.overview)
        assertEquals(5, changes.summaryCount)

        val sent = requests().single().json().toString()
        assertTrue("#1 [00:00:00] S1: hello 1" in sent, sent)
        assertTrue("S2: no name" in sent, "the speakers, without names")
        assertTrue("Section decision — Decisions\\n@1 레클리 ships on Friday. [00:00:00]" in sent, sent)
        assertTrue("@2 Write the notes. (owner: S2) [00:00:01]" in sent || "@2 Write the notes. [00:00:01]" in sent, sent)
        assertTrue("Section risks — Risks: What could go wrong\\n(none)" in sent, sent)
        assertTrue("레클리 is spelled Recly." in sent, "the request and what was pasted")

        val transcript = f.core.results(meta.recordingId).transcript!!
        assertEquals(listOf("Hello, Recly.", "hello 2"), transcript.segments.map { it.text })
        assertEquals(listOf("S1", "S1"), transcript.segments.map { it.speaker })
        assertEquals(listOf(TranscriptSpeaker("S1", "Mina")), transcript.speakers)
        assertEquals(
            listOf(TranscriptVersionKind.CORRECTED, TranscriptVersionKind.TRANSCRIBED),
            f.core.transcriptVersions(meta.recordingId).map { it.kind },
        )
        val changed = notes(meta)
        assertEquals("Weekly sync about the Recly launch.", changed.overview)
        assertEquals(listOf("Recly ships on Friday."), changed.texts(changed.decisions))
        assertEquals(listOf("Send the build."), changed.texts(changed.todos), "the old to-do removed, the new one added")
        assertEquals("Mina", changed.todos.single().owner)
        assertEquals("Friday", changed.todos.single().due)
        assertEquals(0.0, changed.todos.single().atSec)
        assertEquals("The launch could slip.", changed.sections.single().items.single().text)
        // Both go to the recording's folder, where the other devices read them at their next pull.
        f.core.awaitPushes()
        val base = MetaWriter.baseName(meta)
        assertEquals("edited", f.drive.byName(TranscribeRunner.jsonFileName(base))!!.appProperties["reclyTranscript"])
        assertEquals(transcript, folderTranscript(base))
        val folderNotes = assertNotNull(folderNotes(base))
        assertEquals("Weekly sync about the Recly launch.", folderNotes.overview)
        assertEquals(listOf("Recly ships on Friday.", "The launch could slip.", "Send the build."), folderNotes.items.map { it.shown })
        assertEquals(emptyMap(), f.core.recordings.pendingTranscripts())
        assertEquals(emptyMap(), f.core.recordings.pendingNotes())

        assertTrue(f.core.summaries.undoAsk(meta.recordingId))

        val undone = f.core.results(meta.recordingId).transcript!!
        assertEquals(listOf("hello 1", "hello 2"), undone.segments.map { it.text })
        assertEquals(listOf("S1", "S2"), undone.segments.map { it.speaker })
        assertTrue(undone.speakers.all { it.name == null })
        val back = notes(meta)
        assertEquals("Weekly sync about the 레클리 launch.", back.overview)
        assertEquals(listOf("레클리 ships on Friday."), back.texts(back.decisions))
        assertEquals(listOf("Write the notes."), back.texts(back.todos))
        assertTrue(back.sections.isEmpty())
        assertEquals(
            listOf(TranscriptVersionKind.RESTORED, TranscriptVersionKind.CORRECTED, TranscriptVersionKind.TRANSCRIBED),
            f.core.transcriptVersions(meta.recordingId).map { it.kind },
        )
        assertEquals(AskState.None, f.core.summaries.observeAsk(meta.recordingId).first(), "nothing left to show")
        assertFalse(f.core.summaries.undoAsk(meta.recordingId), "undone once")
        f.core.awaitPushes()
        assertEquals(emptyMap(), f.core.recordings.pendingTranscripts(), "the undo went to the folder")
        assertEquals(emptyMap(), f.core.recordings.pendingNotes())
        assertEquals(undone, folderTranscript(base))
        val folderBack = assertNotNull(folderNotes(base))
        assertEquals("Weekly sync about the 레클리 launch.", folderBack.overview)
        assertEquals(listOf("레클리 ships on Friday.", "Write the notes."), folderBack.items.map { it.shown })
    }

    private fun folderTranscript(base: String): Transcript? =
        f.drive.byName(TranscribeRunner.jsonFileName(base))?.content?.decodeToString()?.let { recJson.decodeFromString(Transcript.serializer(), it) }

    private fun folderNotes(base: String): NotesFile? =
        f.drive.byName(recly.core.notes.NotesFolder.name(base))?.content?.decodeToString()?.let(recly.core.notes.NotesFolder::decode)

    @Test
    fun `a question is answered and changes nothing, and an answer that is no object is all words`() = runBlocking {
        chatGpt.signIn(account = f.core.chatGpt)
        val meta = f.recordAndRun()
        val before = f.core.results(meta.recordingId).transcript
        answer("""{"answer":"Mina says hello [00:00:00]."}""")

        val answered = ask(meta, "Who says hello?")

        assertEquals("Mina says hello [00:00:00].", answered.text)
        assertNull(answered.changes)
        assertEquals(before, f.core.results(meta.recordingId).transcript)
        assertEquals(emptyMap(), f.core.recordings.pendingTranscripts())
        assertFalse("SUMMARY" in requests().single().text, "no final notes, no summary")

        answer("Mina says hello.")
        assertEquals("Mina says hello.", ask(meta, "Who?").text)
        answer("""{"lines":[{"n":1,"text":"hello 1"}]}""")
        val unchanged = ask(meta, "Check the names.")
        assertEquals("", unchanged.text)
        assertNull(unchanged.changes, "the same words are no change")
    }

    @Test
    fun `a line the user changed while ChatGPT answered keeps the user's words`() = runBlocking {
        chatGpt.signIn(account = f.core.chatGpt)
        val meta = f.recordAndRun()
        answer("""{"lines":[{"n":1,"text":"Hello, Recly."},{"n":2,"text":"Hello again."}]}""")
        chatGpt.server.beforeReply = { request ->
            if (AskPrompts.REQUEST_HEADING in request.text) {
                f.core.editTranscript(meta.recordingId, TranscriptEdit.SetText(1, "typed meanwhile"))
            }
        }

        assertEquals(listOf(0), ask(meta, "Fix the product name.").changes!!.segments)
        assertEquals(listOf("Hello, Recly.", "typed meanwhile"), f.core.results(meta.recordingId).transcript!!.segments.map { it.text })
    }

    @Test
    fun `a summary changed again is not undone, and the rest is`() = runBlocking {
        chatGpt.signIn(account = f.core.chatGpt)
        val meta = f.recordAndRun()
        finalNotes(meta)
        answer("""{"lines":[{"n":1,"text":"Hello, Recly."}],"items":[{"n":1,"text":"Recly ships on Friday."}]}""")
        ask(meta, "레클리 is Recly.")
        f.core.notes.edit(meta.recordingId, "My own overview", emptyList())

        assertFalse(f.core.summaries.undoAsk(meta.recordingId))

        assertEquals("My own overview", notes(meta).overview)
        assertEquals(listOf("Recly ships on Friday."), notes(meta).texts(notes(meta).decisions))
        assertEquals(listOf("hello 1", "hello 2"), f.core.results(meta.recordingId).transcript!!.segments.map { it.text }, "the transcript was undone")
    }

    @Test
    fun `the transcript is left while its recording's job has not settled, and failures are reported`() = runBlocking {
        assertIs<AskState.Failed>(f.core.summaries.ask(f.record().recordingId, null, "Fix it."), "no transcript yet")
        val meta = f.recordAndRun(id = "01J9ASKB0SY000000000000000")
        assertEquals(
            recly.core.message.CoreMessage.CHATGPT_SIGN_IN_REQUIRED.code(),
            assertIs<AskState.Failed>(f.core.summaries.ask(meta.recordingId, null, "Fix it.")).reason,
        )
        chatGpt.signIn(account = f.core.chatGpt)
        assertIs<recly.core.transcribe.RetranscribeResult.Started>(f.core.retranscribe(meta.recordingId))
        answer("""{"lines":[{"n":1,"text":"Hello, Recly."}]}""")
        assertNull(ask(meta, "Fix it.").changes, "a transcription on its way would write over it")
        f.drain()

        chatGpt.server.reply("""{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}""", status = 429)
        assertEquals(
            recly.core.message.CoreMessage.CHATGPT_USAGE_LIMIT.code(),
            assertIs<AskState.Failed>(f.core.summaries.ask(meta.recordingId, null, "Fix it.")).reason,
        )
        assertEquals(listOf("hello 1", "hello 2"), f.core.results(meta.recordingId).transcript!!.segments.map { it.text })
    }

    // --- The proposal's own rules ---

    private val sent = Transcript(
        recordingId = "R", track = Track.MONO, language = "en", provider = TranscriptProvider("fake"),
        createdAt = "2026-08-26T01:00:00.000Z", durationSec = 10.0,
        speakers = listOf(TranscriptSpeaker("S1", "Mina"), TranscriptSpeaker("S2")),
        segments = listOf(
            TranscriptSegment(0.0, 1.0, "S1", "hello 1"),
            TranscriptSegment(1.0, 2.0, "S2", "hello 2"),
            TranscriptSegment(2.0, 3.0, "S2", " "),
        ),
    )

    private val summary = AskSummary(
        RecordingNotes(
            recordingId = "R", stage = NotesStage.FINAL, overview = "Sync.",
            decisions = listOf(NoteItem("D1", NoteKind.DECISION, "Ship it.", null, null, 0.0, "hello 1", false)),
            todos = listOf(NoteItem("T1", NoteKind.TODO, "Write it.", "Mina", "Friday", 1.0, "hello 2", false)),
            questions = emptyList(), organizing = false,
        ),
        listOf(NoteSection("risks", "Risks", "What could go wrong")),
    )

    @Test
    fun `only what fits the transcript is kept`() {
        val proposal = AskProposal.parse(
            """Here you go: {"lines":[
                {"n":0,"text":"no such line"},
                {"n":1,"text":"hello 1"},
                {"n":2,"text":"two\nlines","speaker":"S9"},
                {"n":3,"text":"said nothing"},
                {"n":4,"text":"past the end"},
                {"n":2.5,"speaker":"S1"}
            ],"names":{"S1":"Mina","S2":"S1","S3":"Ghost"}} Done.""",
            sent, null,
        )
        assertEquals(mapOf(1 to "two lines"), proposal.texts)
        assertEquals(emptyMap(), proposal.speakers, "an id the transcript has not, and an index that is no number")
        assertEquals(emptyMap(), proposal.names, "the same name, an id as a name, a speaker not there")
        assertFalse(AskProposal.parse("""{"lines":[]}""", sent, null).changesTranscript)
        assertEquals("No changes needed.", AskProposal.parse("No changes needed.", sent, null).answer)
        assertEquals("Use {braces} like this.", AskProposal.parse("Use {braces} like this.", sent, null).answer, "an object of no known key is words")
    }

    @Test
    fun `only what fits the summary is kept`() {
        val proposal = AskProposal.parse(
            """{"overview":"Sync.","items":[
                {"n":1,"text":"Ship it."},
                {"n":2,"text":"Write it\non Friday."},
                {"n":3,"text":"no such item"}
            ],"add":[
                {"section":"nowhere","text":"lost"},
                {"section":"decision","text":"  "},
                {"section":"decision","text":"Price later.","line":2,"owner":"S1","due":"Monday"},
                {"section":"todo","text":"Send it.","line":1,"owner":"S9","due":"Monday"},
                {"section":"risks","text":"It may slip.","line":1},
                {"section":"todo","text":"Only in the memo.","line":9},
                {"section":"todo","text":"No line at all."},
                {"section":"todo","text":"A line that says nothing.","line":3}
            ]}""",
            sent, summary,
        )
        assertNull(proposal.overview, "the same overview is no change")
        assertEquals(mapOf("T1" to "Write it on Friday."), proposal.items)
        assertEquals(
            listOf(
                AskNote(NoteKind.DECISION, null, "Price later.", 1.0, "hello 2", null, null),
                AskNote(NoteKind.TODO, null, "Send it.", 0.0, "hello 1", null, "Monday"),
                AskNote(NoteKind.SECTION, "risks", "It may slip.", 0.0, "hello 1", null, null),
            ),
            proposal.added,
            "an owner and a due date only for a to-do, an owner only a speaker of the transcript, and an item only with a line " +
                "that is there and says something",
        )
        assertFalse(AskProposal.parse("""{"items":[{"n":1,"text":"Changed."}]}""", sent, null).changesSummary, "no summary, no summary changes")
    }

    @Test
    fun `a time copied into a summary item's words is dropped, and words that were only times change nothing`() {
        val proposal = AskProposal.parse(
            """{"items":[
                {"n":1,"text":"Ship it on Friday. [00:00:00] [00:00:01]"},
                {"n":2,"text":"[00:00:01]"}
            ],"add":[
                {"section":"decision","text":"Price later [00:00:01], then launch.","line":2},
                {"section":"decision","text":"[00:00:01–00:00:05]","line":2}
            ]}""",
            sent, summary,
        )
        assertEquals(mapOf("D1" to "Ship it on Friday."), proposal.items)
        assertEquals(listOf("Price later, then launch."), proposal.added.map { it.text })
        assertTrue("never a time" in AskPrompts.instructions(sent, summary, SummaryPreferences()))
    }

    @Test
    fun `an item's owner and due date copied after its words are dropped, and words that were only those change nothing`() {
        val proposal = AskProposal.parse(
            """{"items":[
                {"n":2,"text":"Write it by noon. (owner: Mina; due: Friday) [00:00:01]"}
            ],"add":[
                {"section":"todo","text":"Send it. (owner: S1; due: Monday)","line":1,"owner":"S1","due":"Monday"},
                {"section":"todo","text":"Call back. (due: Monday)","line":1},
                {"section":"todo","text":"(due: Monday)","line":1,"due":"Monday"}
            ]}""",
            sent, summary,
        )
        assertEquals(mapOf("T1" to "Write it by noon."), proposal.items)
        assertEquals(
            listOf(AskNote(NoteKind.TODO, null, "Send it.", 0.0, "hello 1", "S1", "Monday"), AskNote(NoteKind.TODO, null, "Call back. (due: Monday)", 0.0, "hello 1", null, null)),
            proposal.added,
            "only what the item's own owner and due date would say is taken off: words the model gave none for stay",
        )
        assertTrue(AskProposal.parse("""{"items":[{"n":2,"text":"(owner: Mina; due: Friday)"}]}""", sent, summary).items.isEmpty())
        val instructions = AskPrompts.instructions(sent, summary, SummaryPreferences())
        assertTrue("never a time, owner or due date: the (owner: …; due: …) and the [HH:MM:SS] that end a summary line are kept apart" in instructions, instructions)
        assertTrue("@number text (owner: …; due: …) [HH:MM:SS]" in AskPrompts.input(sent, summary, "", "Fix it."))
    }

    @Test
    fun `the request names segments by number, speakers by id and summary items by number, with the memo`() {
        val transcript = sent.copy(speakers = sent.speakers + TranscriptSpeaker("S3", me = true))
        val input = AskPrompts.input(transcript, summary, "Agenda: launch", "Fix it.")
        assertTrue("#1 [00:00:00] S1: hello 1\n#2 [00:00:01] S2: hello 2\n\n" in input, input)
        assertTrue("#3" !in input, "a segment that says nothing is not sent")
        assertTrue("S1: Mina\nS2: no name\nS3: Me — the person who made the recording" in input, input)
        assertTrue("Overview: Sync.\nSection decision — Decisions\n@1 Ship it. [00:00:00]\n" in input, input)
        assertTrue("@2 Write it. (owner: Mina; due: Friday) [00:00:01]" in input, input)
        val noTime = AskSummary(summary.notes.copy(decisions = summary.notes.decisions.map { it.copy(atSec = null, quote = "") }), summary.sections)
        assertTrue("@1 Ship it.\n" in AskPrompts.input(transcript, noTime, "", "Fix it."), "an item with no time says none")
        assertTrue("Section risks — Risks: What could go wrong\n(none)" in input, input)
        assertTrue("Agenda: launch" in input)
        assertTrue(input.endsWith("${AskPrompts.REQUEST_HEADING}\nFix it.\n"))
        val plain = AskPrompts.instructions(sent.copy(speakers = emptyList()), null, SummaryPreferences())
        assertTrue("\"speaker\"" !in plain, "no speakers to move lines between")
        assertTrue("\"add\"" !in plain, "no summary to add to")
        assertTrue("\"add\"" in AskPrompts.instructions(sent, summary, SummaryPreferences()))
    }

    @Test
    fun `with a memo, the instructions say what it is, keep its names from guessing speakers, and add nothing it alone says`() {
        val without = AskPrompts.instructions(sent, summary, SummaryPreferences())
        assertTrue("memo" !in without, without)
        assertTrue("something only in the request — add nothing" in without, without)

        val with = AskPrompts.instructions(sent, summary, SummaryPreferences(), memo = true)
        assertTrue("then the summary made of it, then the user's memo; then the request." in with, with)
        assertTrue("Take it as material when the request says to use it; on its own it asks for nothing." in with, with)
        assertTrue(
            "A name in the memo may be one of the speakers', but give it only where the transcript itself shows which speaker it is: a role " +
                "or anything else the memo says about someone is not enough." in with,
            with,
        )
        assertTrue("Never guess a name from how someone speaks or what they talk about." in with, with)
        assertTrue("something only in the request or the memo — add nothing, and say so in \"answer\"." in with, with)

        val unnamed = AskPrompts.instructions(sent.copy(speakers = emptyList()), null, SummaryPreferences(), memo = true)
        assertTrue("A name in the memo" !in unnamed, "no speakers to name")
        assertTrue("then the user's memo; then the request." in unnamed, unnamed)
    }
}
