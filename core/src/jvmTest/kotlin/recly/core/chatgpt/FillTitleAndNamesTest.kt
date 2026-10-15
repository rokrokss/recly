package recly.core.chatgpt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import recly.core.job.EnqueueResult
import recly.core.model.recJson
import recly.core.recording.MetaWriter
import recly.core.testing.CoreFixture
import recly.core.testing.FakeLocalEngine
import recly.core.transcribe.TranscribeRunner
import recly.core.transcribe.Transcript

/** docs/08 "Titles and speaker names" through the whole core: the title sheet's request, and More → Fill in title and names. */
class FillTitleAndNamesTest {
    private val chatGpt = ChatGptHarness()
    private val f = CoreFixture(engine = FakeLocalEngine(supportsDiarization = true), chatGpt = chatGpt.server.transport(chatGpt.fs))

    private suspend fun signIn() {
        chatGpt.signIn(account = f.core.chatGpt)
    }

    private fun answer(text: String) {
        chatGpt.server.reply(
            "data: {\"type\":\"response.output_text.delta\",\"delta\":" + kotlinx.serialization.json.Json.encodeToString(String.serializer(), text) +
                "}\n\ndata: {\"type\":\"response.completed\",\"response\":{}}\n\n",
            headers = mapOf("Content-Type" to "text/event-stream"),
        )
    }

    private fun asked() = chatGpt.server.requests.count { it.url.endsWith("/responses") }

    private fun inFolder(base: String): Pair<Transcript, Map<String, String>> =
        f.drive.byName(TranscribeRunner.jsonFileName(base))!!.let { recJson.decodeFromString<Transcript>(it.content.decodeToString()) to it.appProperties }

    @Test
    fun `a recording the title sheet asked for goes up with its title and names`() = runBlocking {
        signIn()
        val meta = f.record()
        f.core.requestFill(meta.recordingId)
        answer("""{"title":"Launch plan","speakers":{"S1":"Mina","S2":null}}""")
        assertEquals(EnqueueResult.Enqueued::class, f.core.enqueue(meta.recordingId)::class)
        f.drain()

        val base = MetaWriter.baseName(meta)
        val (published, marks) = inFolder(base)
        assertEquals("Mina", published.speakers.single { it.id == "S1" }.name)
        assertNull(published.editedAt, "part of the transcription")
        assertEquals("transcribed", marks["reclyTranscript"])
        assertEquals("Launch plan", f.core.recordings.get(meta.recordingId)!!.meta.title)
        assertEquals("Launch plan", f.drive.byName(base)!!.description, "the folder has the title")
        assertFalse(f.core.recordings.fillRequested(meta.recordingId))
        assertEquals(1, asked())
    }

    @Test
    fun `a recording nobody asked for is published as it is`() = runBlocking {
        signIn()
        val meta = f.recordAndRun()
        assertEquals(0, asked())
        assertTrue(inFolder(MetaWriter.baseName(meta)).first.speakers.all { it.name == null })
        assertNull(f.core.recordings.get(meta.recordingId)!!.meta.title)
    }

    @Test
    fun `a request after the publication fills in at once, the names as an edit`() = runBlocking {
        signIn()
        val meta = f.recordAndRun()
        answer("""{"title":"Launch plan","speakers":{"S2":"Joon"}}""")
        f.core.requestFill(meta.recordingId)
        f.core.awaitPushes()

        assertEquals("Launch plan", f.core.recordings.get(meta.recordingId)!!.meta.title)
        val local = f.core.results(meta.recordingId).transcript!!
        assertEquals("Joon", local.speakers.single { it.id == "S2" }.name)
        assertNotNull(local.editedAt, "a change the user asked for, to a transcript already published")
        val (published, marks) = inFolder(MetaWriter.baseName(meta))
        assertEquals("Joon", published.speakers.single { it.id == "S2" }.name)
        assertEquals("edited", marks["reclyTranscript"])
        assertFalse(f.core.recordings.fillRequested(meta.recordingId))
    }

    @Test
    fun `More fills in what is missing and leaves a name the user gave`() = runBlocking {
        signIn()
        val meta = f.recordAndRun()
        f.core.editTranscript(meta.recordingId, recly.core.transcribe.TranscriptEdit.RenameSpeaker("S1", "Ji-won"))
        f.core.awaitPushes()
        answer("""{"title":"Launch plan","speakers":{"S2":"Joon"}}""")
        assertEquals(FillResult.Filled(title = true, names = 1), f.core.fillTitleAndNames(meta.recordingId))
        val asked = chatGpt.server.requests.last { it.url.endsWith("/responses") }.json().toString()
        assertTrue("speaker ids: S2." in asked, "only the speaker with no name")
        f.core.awaitPushes()
        val names = f.core.results(meta.recordingId).transcript!!.speakers.associate { it.id to it.name }
        assertEquals(mapOf("S1" to "Ji-won", "S2" to "Joon"), names)
        assertEquals(emptyMap(), f.core.recordings.pendingTranscripts(), "in the folder")
        assertEquals(emptyMap(), f.core.recordings.pendingTitles())

        assertEquals(FillResult.NothingToFill, f.core.fillTitleAndNames(meta.recordingId))
        assertEquals(1, asked())
    }

    @Test
    fun `More waits for the recording's job, and needs a transcript`() = runBlocking {
        signIn()
        val meta = f.record()
        assertEquals(FillResult.NoTranscript, f.core.fillTitleAndNames(meta.recordingId))
        f.core.enqueue(meta.recordingId)
        assertEquals(FillResult.Busy, f.core.fillTitleAndNames(meta.recordingId))
        assertEquals(0, asked())
    }
}
