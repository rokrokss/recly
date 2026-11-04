package recly.core.chatgpt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import recly.core.recording.MetaWriter
import recly.core.testing.CoreFixture
import recly.core.testing.FakeLocalEngine
import recly.core.transcribe.EditResult
import recly.core.transcribe.TranscribeRunner
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptEdit
import recly.core.transcribe.TranscriptProvider
import recly.core.transcribe.TranscriptSegment
import recly.core.transcribe.TranscriptSpeaker
import recly.core.transcribe.TranscriptVersionKind
import recly.core.model.Track

/** docs/08 "Correcting", through the whole core, and the proposal's own rules. */
class CorrectionTest {

    private val chatGpt = ChatGptHarness()
    private val f = CoreFixture(engine = FakeLocalEngine(supportsDiarization = true), chatGpt = chatGpt.server.transport(chatGpt.fs))

    private fun answer(text: String) {
        chatGpt.server.reply(
            "data: {\"type\":\"response.output_text.delta\",\"delta\":" + kotlinx.serialization.json.Json.encodeToString(String.serializer(), text) +
                "}\n\ndata: {\"type\":\"response.completed\",\"response\":{}}\n\n",
            headers = mapOf("Content-Type" to "text/event-stream"),
        )
    }

    private fun corrections() = chatGpt.server.requests.filter { it.url.endsWith("/responses") && TranscriptCorrection.REQUEST_HEADING in it.text }

    @Test
    fun `a request corrects words, speakers and names, saved as an edit the version before it undoes`() = runBlocking {
        chatGpt.signIn(account = f.core.chatGpt)
        val meta = f.recordAndRun()
        answer("""```json
            {"lines":[{"n":1,"text":"Hello, Recly."},{"n":2,"speaker":"S1"}],"names":{"S1":"Mina"}}
            ```""")

        val result = assertIs<CorrectionResult.Corrected>(
            f.core.correctTranscript(meta.recordingId, "  The product is called Recly.\n\nOther transcript:\n[00:00] Mina: Hello, Recly.  "),
        )

        assertEquals(listOf(0, 1), result.segments)
        assertEquals(1, result.names)
        val sent = corrections().single().json().toString()
        assertTrue("#1 [00:00:00] S1: hello 1" in sent, sent)
        assertTrue("S2: no name" in sent, "the speakers, without names")
        assertTrue("The product is called Recly." in sent, "the request and what was pasted")
        val corrected = f.core.results(meta.recordingId).transcript!!
        assertEquals(listOf("Hello, Recly.", "hello 2"), corrected.segments.map { it.text })
        assertEquals(listOf("S1", "S1"), corrected.segments.map { it.speaker })
        assertEquals(listOf(TranscriptSpeaker("S1", "Mina")), corrected.speakers)
        assertNotNull(corrected.editedAt)

        f.core.awaitPushes()
        val base = MetaWriter.baseName(meta)
        assertEquals("edited", f.drive.byName(TranscribeRunner.jsonFileName(base))!!.appProperties["reclyTranscript"])
        assertEquals(
            listOf(TranscriptVersionKind.CORRECTED, TranscriptVersionKind.TRANSCRIBED),
            f.core.transcriptVersions(meta.recordingId).map { it.kind },
        )

        val undone = assertIs<EditResult.Edited>(f.core.restoreTranscript(meta.recordingId, result.previousVersionId!!)).transcript
        assertEquals(listOf("hello 1", "hello 2"), undone.segments.map { it.text })
        assertEquals(listOf("S1", "S2"), undone.segments.map { it.speaker })
        assertTrue(undone.speakers.all { it.name == null })
        assertEquals(
            listOf(TranscriptVersionKind.RESTORED, TranscriptVersionKind.CORRECTED, TranscriptVersionKind.TRANSCRIBED),
            f.core.transcriptVersions(meta.recordingId).map { it.kind },
        )
        f.core.awaitPushes()
        assertEquals(emptyMap(), f.core.recordings.pendingTranscripts(), "the undo went to the folder")
    }

    @Test
    fun `nothing to change saves nothing`() = runBlocking {
        chatGpt.signIn(account = f.core.chatGpt)
        val meta = f.recordAndRun()
        val before = f.core.results(meta.recordingId).transcript
        answer("""{"lines":[{"n":1,"text":"hello 1"}]}""")

        assertEquals(CorrectionResult.NothingToCorrect, f.core.correctTranscript(meta.recordingId, "Check the names."))

        assertEquals(before, f.core.results(meta.recordingId).transcript)
        assertEquals(emptyMap(), f.core.recordings.pendingTranscripts())
        assertEquals(CorrectionResult.NothingToCorrect, f.core.correctTranscript(meta.recordingId, "   "), "an empty request asks nothing")
        assertEquals(1, corrections().size)
    }

    @Test
    fun `a line the user changed while ChatGPT answered keeps the user's words`() = runBlocking {
        chatGpt.signIn(account = f.core.chatGpt)
        val meta = f.recordAndRun()
        answer("""{"lines":[{"n":1,"text":"Hello, Recly."},{"n":2,"text":"Hello again."}]}""")
        chatGpt.server.beforeReply = { request ->
            if (TranscriptCorrection.REQUEST_HEADING in request.text) {
                f.core.editTranscript(meta.recordingId, TranscriptEdit.SetText(1, "typed meanwhile"))
            }
        }

        val result = assertIs<CorrectionResult.Corrected>(f.core.correctTranscript(meta.recordingId, "Fix the product name."))

        assertEquals(listOf(0), result.segments)
        assertEquals(listOf("Hello, Recly.", "typed meanwhile"), f.core.results(meta.recordingId).transcript!!.segments.map { it.text })
    }

    @Test
    fun `it waits for the recording's job, needs a transcript and a sign-in, and reports ChatGPT's refusal`() = runBlocking {
        val meta = f.record()
        assertEquals(CorrectionResult.NoTranscript, f.core.correctTranscript(meta.recordingId, "Fix it."))
        f.core.enqueue(meta.recordingId)
        assertEquals(CorrectionResult.Busy, f.core.correctTranscript(meta.recordingId, "Fix it."))
        f.drain()
        assertEquals(
            CorrectionResult.Failed(recly.core.message.CoreMessage.CHATGPT_SIGN_IN_REQUIRED.code()),
            f.core.correctTranscript(meta.recordingId, "Fix it."),
        )

        chatGpt.signIn(account = f.core.chatGpt)
        chatGpt.server.reply("""{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}""", status = 429)
        assertEquals(
            CorrectionResult.Failed(recly.core.message.CoreMessage.CHATGPT_USAGE_LIMIT.code()),
            f.core.correctTranscript(meta.recordingId, "Fix it."),
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

    @Test
    fun `only what fits the transcript is kept`() {
        val proposal = CorrectionProposal.parse(
            """Here you go: {"lines":[
                {"n":0,"text":"no such line"},
                {"n":1,"text":"hello 1"},
                {"n":2,"text":"two\nlines","speaker":"S9"},
                {"n":3,"text":"said nothing"},
                {"n":4,"text":"past the end"},
                {"n":2.5,"speaker":"S1"}
            ],"names":{"S1":"Mina","S2":"S1","S3":"Ghost"}} Done.""",
            sent,
        )
        assertEquals(mapOf(1 to "two lines"), proposal.texts)
        assertEquals(emptyMap(), proposal.speakers, "an id the transcript has not, and an index that is no number")
        assertEquals(emptyMap(), proposal.names, "the same name, an id as a name, a speaker not there")
        assertTrue(CorrectionProposal.parse("""{"lines":[]}""", sent).empty)
        assertFailsWith<ChatGptFailure> { CorrectionProposal.parse("No changes needed.", sent) }
    }

    @Test
    fun `the request names segments by number and speakers by id, with the memo`() {
        val input = TranscriptCorrection.input(sent.copy(speakers = sent.speakers + TranscriptSpeaker("S3", me = true)), "Agenda: launch", "Fix it.")
        assertTrue("#1 [00:00:00] S1: hello 1\n#2 [00:00:01] S2: hello 2\n\n" in input, input)
        assertTrue("#3" !in input, "a segment that says nothing is not sent")
        assertTrue("S1: Mina\nS2: no name\nS3: Me — the person who made the recording" in input, input)
        assertTrue("Agenda: launch" in input)
        assertTrue(input.endsWith("${TranscriptCorrection.REQUEST_HEADING}\nFix it.\n"))
        assertTrue("\"speaker\"" !in TranscriptCorrection.instructions(sent.copy(speakers = emptyList())), "no speakers to move lines between")
    }
}
