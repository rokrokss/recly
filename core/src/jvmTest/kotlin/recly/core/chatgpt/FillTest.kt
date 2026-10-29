package recly.core.chatgpt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import recly.core.job.EnqueueResult
import recly.core.model.recJson
import recly.core.recording.MetaWriter
import recly.core.testing.CoreFixture
import recly.core.testing.FakeLocalEngine
import recly.core.transcribe.TranscribeRunner
import recly.core.transcribe.Transcript

/**
 * docs/08 "Titles and speaker names" through the whole core: a publication's fill for a user who agreed, More → Fill in
 * title and More → Fill in speaker names, and what is being filled in meanwhile.
 */
class FillTest {

    private val chatGpt = ChatGptHarness()
    private val f = CoreFixture(engine = FakeLocalEngine(supportsDiarization = true), chatGpt = chatGpt.server.transport(chatGpt.fs))

    private suspend fun signIn() {
        chatGpt.signIn(account = f.core.chatGpt)
    }

    /** Signed in and agreed to the plan's background use (docs/08 "Organizing"): every publication fills in. */
    private suspend fun agree() {
        signIn()
        f.core.notes.consent()
    }

    private fun answer(text: String) {
        chatGpt.server.reply(
            "data: {\"type\":\"response.output_text.delta\",\"delta\":" + kotlinx.serialization.json.Json.encodeToString(String.serializer(), text) +
                "}\n\ndata: {\"type\":\"response.completed\",\"response\":{}}\n\n",
            headers = mapOf("Content-Type" to "text/event-stream"),
        )
    }

    private fun asked() = chatGpt.server.requests.count { it.url.endsWith("/responses") }

    /** The fill's requests alone: an agreed user's recording is organized after it is published, too. */
    private fun fills() = chatGpt.server.requests.filter(chatGpt::isFill)

    private fun inFolder(base: String): Pair<Transcript, Map<String, String>> =
        f.drive.byName(TranscribeRunner.jsonFileName(base))!!.let { recJson.decodeFromString<Transcript>(it.content.decodeToString()) to it.appProperties }

    @Test
    fun `an agreed user's recording goes up with its title and names`() = runBlocking {
        agree()
        val meta = f.record()
        answer("""{"title":"Launch plan","speakers":{"S1":"Mina","S2":null}}""")
        val seen = mutableListOf<List<FillRun>>()
        chatGpt.server.beforeReply = { if (chatGpt.isFill(it)) seen += f.core.observeFills().first() }
        assertEquals(EnqueueResult.Enqueued::class, f.core.enqueue(meta.recordingId)::class)
        f.drain()
        assertEquals(listOf(listOf(FillRun(meta.recordingId, title = true, names = true))), seen, "being filled in while ChatGPT answers")
        assertEquals(emptyList(), f.core.observeFills().first())

        val base = MetaWriter.baseName(meta)
        val (published, marks) = inFolder(base)
        assertEquals("Mina", published.speakers.single { it.id == "S1" }.name)
        assertNull(published.editedAt, "part of the transcription")
        assertEquals("transcribed", marks["reclyTranscript"])
        assertEquals("Launch plan", f.core.recordings.get(meta.recordingId)!!.meta.title)
        assertEquals("Launch plan", f.drive.byName(base)!!.description, "the folder has the title")
        assertEquals(1, fills().size)
    }

    @Test
    fun `a recording titled at Stop goes up named, its title kept`() = runBlocking {
        agree()
        val meta = f.record()
        f.core.rename(meta.recordingId, "Weekly sync")
        answer("""{"title":"Ignored","speakers":{"S1":"Mina"}}""")
        f.core.enqueue(meta.recordingId)
        f.drain()

        assertEquals("Mina", inFolder(MetaWriter.baseName(meta)).first.speakers.single { it.id == "S1" }.name)
        assertEquals("Weekly sync", f.core.recordings.get(meta.recordingId)!!.meta.title)
        assertTrue("\\\"title\\\"" !in fills().single().json().toString(), "no title asked for")
    }

    @Test
    fun `signed in but not agreed, a recording is published as it is`() = runBlocking {
        signIn()
        val meta = f.recordAndRun()
        assertEquals(0, asked())
        assertTrue(inFolder(MetaWriter.baseName(meta)).first.speakers.all { it.name == null })
        assertNull(f.core.recordings.get(meta.recordingId)!!.meta.title)
    }

    @Test
    fun `More fills in the names and the title, each on its own, and leaves a name the user gave`() = runBlocking {
        signIn()
        val meta = f.recordAndRun()
        f.core.editTranscript(meta.recordingId, recly.core.transcribe.TranscriptEdit.RenameSpeaker("S1", "Ji-won"))
        f.core.awaitPushes()
        answer("""{"title":"Ignored","speakers":{"S2":"Joon"}}""")
        assertEquals(FillResult.Filled(title = false, names = 1), f.core.fillNames(meta.recordingId))
        val asked = chatGpt.server.requests.last { it.url.endsWith("/responses") }.json().toString()
        assertTrue("speaker ids: S2." in asked, "only the speaker with no name")
        f.core.awaitPushes()
        val names = f.core.results(meta.recordingId).transcript!!.speakers.associate { it.id to it.name }
        assertEquals(mapOf("S1" to "Ji-won", "S2" to "Joon"), names)
        assertNull(f.core.recordings.get(meta.recordingId)!!.meta.title, "the title was not asked for")
        assertEquals(emptyMap(), f.core.recordings.pendingTranscripts(), "in the folder")

        answer("""{"title":"Launch plan"}""")
        assertEquals(FillResult.Filled(title = true, names = 0), f.core.fillTitle(meta.recordingId))
        assertEquals("Launch plan", f.core.recordings.get(meta.recordingId)!!.meta.title)
        assertEquals(emptyMap(), f.core.recordings.pendingTitles())

        assertEquals(FillResult.NothingToFill, f.core.fillNames(meta.recordingId))
        assertEquals(FillResult.NothingToFill, f.core.fillTitle(meta.recordingId))
        assertEquals(2, asked())
        assertEquals(emptyList(), f.core.observeFills().first())
    }

    @Test
    fun `More waits for the recording's job, and needs a transcript`() = runBlocking {
        signIn()
        val meta = f.record()
        assertEquals(FillResult.NoTranscript, f.core.fillTitle(meta.recordingId))
        f.core.enqueue(meta.recordingId)
        assertEquals(FillResult.Busy, f.core.fillNames(meta.recordingId))
        assertEquals(0, asked())
    }
}
