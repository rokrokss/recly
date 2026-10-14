package recly.core.chatgpt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toPath
import recly.core.message.CoreMessage
import recly.core.model.RecordingStatus
import recly.core.model.Track
import recly.core.privacy.TransferConsents
import recly.core.privacy.TransferTargets
import recly.core.recording.MetaWriter
import recly.core.recording.RecordingRecord
import recly.core.recording.RecordingRepository
import recly.core.testing.testMeta
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptProvider
import recly.core.transcribe.TranscriptSegment
import recly.core.transcribe.TranscriptSpeaker

class AutoFillTest {
    private class Fixture(requireTransferConsent: Boolean = false, title: String? = null) {
        val h = ChatGptHarness(requireTransferConsent)
        val recordings = RecordingRepository(h.db, h.deps)
        val consents = TransferConsents(h.db, h.deps)
        val summaries = Summaries(h.db, h.deps, h.account, recordings, { null }, consents)
        val titles = mutableListOf<String>()
        val fill = AutoFill(h.db, h.deps, summaries, consents, recordings) { id, title ->
            recordings.fillTitle(id, title).also { if (it) titles += title }
        }
        val meta = testMeta(title = title, status = RecordingStatus.FINALIZED)
        val dir = "/data/recordings/${MetaWriter.baseName(meta)}".toPath()
        val record get() = RecordingRecord(ID, meta, dir)

        suspend fun ready(autoTitle: Boolean = true, autoSpeakers: Boolean = true, signIn: Boolean = true) {
            recordings.create(meta, dir)
            if (signIn) h.signIn()
            summaries.setPreferences(SummaryPreferences(autoTitle = autoTitle, autoSpeakers = autoSpeakers))
        }

        fun requests() = h.server.requests.count { it.url.endsWith("/responses") }
    }

    @Test
    fun `the switches are kept on this device and start off`() = runBlocking {
        val f = Fixture()
        assertEquals(SummaryPreferences(), f.summaries.preferences())
        f.summaries.setPreferences(SummaryPreferences(aboutMe = "PM", autoTitle = true))
        val fresh = Summaries(f.h.db, f.h.deps, f.h.account, f.recordings, { null }, f.consents)
        assertEquals(SummaryPreferences(aboutMe = "PM", autoTitle = true, autoSpeakers = false), fresh.preferences())
    }

    @Test
    fun `with both switches off nothing is asked`() = runBlocking {
        val f = Fixture()
        f.ready(autoTitle = false, autoSpeakers = false)
        assertSame(TRANSCRIPT, f.fill.fill(f.record, TRANSCRIPT).transcript)
        assertEquals(0, f.requests())
    }

    @Test
    fun `an untitled recording gets a title and the unnamed speakers their names, before it is published`() = runBlocking {
        val f = Fixture()
        f.ready()
        f.h.server.reply(answer("""{"title":"Launch plan","speakers":{"S1":"Mina","S2":null}}"""), headers = SSE)
        val filled = f.fill.fill(f.record, TRANSCRIPT)

        assertEquals("Launch plan", filled.title)
        assertEquals(listOf("Launch plan"), f.titles)
        assertEquals("Launch plan", f.recordings.get(ID)!!.meta.title)
        assertEquals(listOf(TranscriptSpeaker("S1", "Mina"), TranscriptSpeaker("S2"), TranscriptSpeaker("S3", me = true)), filled.transcript.speakers)
        assertNull(filled.transcript.editedAt, "a filled name is part of the transcription, not an edit")

        val body = f.h.server.requests.last().json()
        val instructions = body["instructions"]!!.jsonPrimitive.content
        assertTrue("\"title\"" in instructions)
        assertTrue("speaker ids: S1, S2." in instructions, "Me is never asked about")
        assertTrue("\"Me\" in the transcript is the person who made the recording" in instructions)
        val text = body["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue("[00:00:00] S1: Mina, can you take the launch?" in text)
        assertTrue("summary.fill.done" in f.h.logger.events)
    }

    @Test
    fun `a title the recording has and the names it has are not asked for`() = runBlocking {
        val f = Fixture(title = "Weekly sync")
        f.ready()
        val named = TRANSCRIPT.copy(speakers = listOf(TranscriptSpeaker("S1", "Mina"), TranscriptSpeaker("S2", "Joon"), TranscriptSpeaker("S3", me = true)))
        assertSame(named, f.fill.fill(f.record, named).transcript)
        assertEquals(0, f.requests())

        f.h.server.reply(answer("""{"title":"Ignored","speakers":{"S2":"Joon"}}"""), headers = SSE)
        val filled = f.fill.fill(f.record, TRANSCRIPT.copy(speakers = listOf(TranscriptSpeaker("S1", "Mina"), TranscriptSpeaker("S2"), TranscriptSpeaker("S3", me = true))))
        val instructions = f.h.server.requests.last().json()["instructions"]!!.jsonPrimitive.content
        assertTrue("\"title\"" !in instructions)
        assertTrue("speaker ids: S2." in instructions)
        assertNull(filled.title)
        assertEquals("Weekly sync", f.recordings.get(ID)!!.meta.title)
        assertEquals("Joon", filled.transcript.speakers[1].name)
    }

    @Test
    fun `only the switch that is on is asked for`() = runBlocking {
        val f = Fixture()
        f.ready(autoTitle = false)
        f.h.server.reply(answer("""{"title":"Not asked","speakers":{"S1":"Mina"}}"""), headers = SSE)
        val filled = f.fill.fill(f.record, TRANSCRIPT)
        assertNull(filled.title)
        assertNull(f.recordings.get(ID)!!.meta.title)
        assertEquals("Mina", filled.transcript.speakers[0].name)
    }

    @Test
    fun `signed out, the transcript goes as it is and nothing is sent`() = runBlocking {
        val f = Fixture()
        f.ready(signIn = false)
        val sent = f.h.server.requests.size
        assertSame(TRANSCRIPT, f.fill.fill(f.record, TRANSCRIPT).transcript)
        assertEquals(sent, f.h.server.requests.size)
        assertEquals("summary.fill.skipped", f.h.logger.events.last())
    }

    @Test
    fun `on iPhone nothing is sent before ChatGPT is allowed`() = runBlocking {
        val f = Fixture(requireTransferConsent = true)
        f.ready()
        assertSame(TRANSCRIPT, f.fill.fill(f.record, TRANSCRIPT).transcript)
        assertEquals(0, f.requests())
        assertEquals("consent", f.h.logger.entries.last().fields["reason"])

        f.consents.grant(listOf(TransferTargets.chatGptSummary()))
        f.h.server.reply(answer("""{"title":"Launch plan","speakers":{}}"""), headers = SSE)
        assertEquals("Launch plan", f.fill.fill(f.record, TRANSCRIPT).title)
    }

    @Test
    fun `the usage limit pauses the fills instead of asking after every transcription`() = runBlocking {
        val f = Fixture()
        f.ready()
        f.h.server.reply("""{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}""", status = 429)
        assertSame(TRANSCRIPT, f.fill.fill(f.record, TRANSCRIPT).transcript)
        assertEquals(1, f.requests())
        assertEquals("summary.fill.failed", f.h.logger.events.last())

        assertSame(TRANSCRIPT, f.fill.fill(f.record, TRANSCRIPT).transcript)
        assertEquals(1, f.requests(), "paused")
        assertEquals("paused", f.h.logger.entries.last().fields["reason"])

        f.h.clock.advance(5.hours)
        // The access token ran out meanwhile: refreshed first.
        f.h.server.reply(f.h.tokens("access-2", "refresh-2"))
        f.h.server.reply(answer("""{"title":"Launch plan"}"""), headers = SSE)
        assertEquals("Launch plan", f.fill.fill(f.record, TRANSCRIPT).title)
        assertEquals(2, f.requests())
    }

    @Test
    fun `an answer that cannot be read publishes the transcript as it is`() = runBlocking {
        val f = Fixture()
        f.ready()
        f.h.server.reply(answer("I could not tell."), headers = SSE)
        val filled = f.fill.fill(f.record, TRANSCRIPT)
        assertSame(TRANSCRIPT, filled.transcript)
        assertNull(filled.title)
        assertNull(f.recordings.get(ID)!!.meta.title)
    }

    @Test
    fun `a transcript with nothing said asks for nothing`() = runBlocking {
        val f = Fixture()
        f.ready()
        val silent = TRANSCRIPT.copy(speakers = emptyList(), segments = emptyList())
        assertSame(silent, f.fill.fill(f.record, silent).transcript)
        assertEquals(0, f.requests())
    }

    @Test
    fun `the fill uses its own model and effort when the plan offers it, else the summary's model as it is`() = runBlocking {
        val f = Fixture()
        f.recordings.create(f.meta, f.dir)
        f.h.signIn(models = """{"models":[{"slug":"gpt-a","display_name":"GPT A","visibility":"list"},
            {"slug":"gpt-5.6-terra","display_name":"Second","visibility":"list"},
            {"slug":"gpt-6.1-sol","display_name":"First","visibility":"list"}]}""")
        f.summaries.setPreferences(SummaryPreferences(autoTitle = true))
        f.h.server.reply(answer("""{"title":"Launch plan"}"""), headers = SSE)
        f.fill.fill(f.record, TRANSCRIPT)
        val body = f.h.server.requests.last().json()
        assertEquals("gpt-6.1-sol", body["model"]!!.jsonPrimitive.content, "the best measured model the plan offers")
        assertEquals("low", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)

        val other = Fixture()
        other.ready(autoSpeakers = false)
        other.h.server.reply(answer("""{"title":"Launch plan"}"""), headers = SSE)
        other.fill.fill(other.record, TRANSCRIPT)
        val plain = other.h.server.requests.last().json()
        assertEquals("gpt-a", plain["model"]!!.jsonPrimitive.content, "the model Settings picked")
        assertNull(plain["reasoning"], "at the model's own effort")
    }

    @Test
    fun `a plan that refuses the effort is asked again without it`() = runBlocking {
        val f = Fixture()
        f.recordings.create(f.meta, f.dir)
        f.h.signIn(models = """{"models":[{"slug":"gpt-5.6-terra","display_name":"Second","visibility":"list"}]}""")
        f.summaries.setPreferences(SummaryPreferences(autoTitle = true))
        f.h.server.reply("""{"error":{"code":"unsupported_parameter","message":"reasoning"}}""", status = 400)
        f.h.server.reply(answer("""{"title":"Launch plan"}"""), headers = SSE)
        assertEquals("Launch plan", f.fill.fill(f.record, TRANSCRIPT).title)
        val (first, second) = f.h.server.requests.filter { it.url.endsWith("/responses") }.map { it.json() }
        assertEquals("medium", first["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertNull(second["reasoning"])
        assertTrue("summary.fill.effort.refused" in f.h.logger.events, "the fallback says so in the log")
    }

    @Test
    fun `the answer is read leniently and only for what was asked`() {
        val ids = listOf("S1", "S2", "S3")
        assertEquals(
            FillAnswer("Launch plan", mapOf("S1" to "Mina")),
            FillAnswer.parse("```json\n{\"title\": \"\\\"Launch plan\\\"\", \"speakers\": {\"S1\": \" Mina \", \"S2\": null, \"S9\": \"Who\"}}\n```", true, ids),
        )
        assertEquals(FillAnswer(null, mapOf("S1" to "Mina")), FillAnswer.parse("""Here: {"title":"Not asked","speakers":{"S1":"Mina"}} done""", false, ids))
        assertEquals(FillAnswer("First line", emptyMap()), FillAnswer.parse("""{"title":"\n First line\nsecond"}""", true, ids))
        assertEquals(FillAnswer.TITLE_MAX, FillAnswer.parse("""{"title":"${"a".repeat(150)}"}""", true, ids).title!!.length)
        assertEquals(
            FillAnswer(null, mapOf("S3" to "Joon")),
            FillAnswer.parse("""{"speakers":{"S1":"Mina","S2":"mina","S3":"Joon"}}""", false, ids),
            "one name for two speakers names neither",
        )
        assertEquals(
            FillAnswer(null, emptyMap()),
            FillAnswer.parse("""{"title":7,"speakers":{"S1":"Me","S2":"S3","S3":{"name":"Joon"}}}""", true, ids),
        )
        assertEquals(
            CoreMessage.PROVIDER_ERROR.code(detail = "unreadable answer"),
            assertFailsWith<ChatGptFailure> { FillAnswer.parse("no object", true, ids) }.reason,
        )
    }

    private companion object {
        const val ID = "01J9ABCDEF0123456789ABCDEF"
        val SSE = mapOf("Content-Type" to "text/event-stream")

        val TRANSCRIPT = Transcript(
            recordingId = ID,
            track = Track.MIX,
            language = "en",
            provider = TranscriptProvider("openai"),
            createdAt = "2026-08-26T01:20:00Z",
            durationSec = 60.0,
            speakers = listOf(TranscriptSpeaker("S1"), TranscriptSpeaker("S2"), TranscriptSpeaker("S3", me = true)),
            segments = listOf(
                TranscriptSegment(0.0, 4.0, "S1", "Mina, can you take the launch?"),
                TranscriptSegment(4.0, 8.0, "S2", "Sure."),
                TranscriptSegment(8.0, 12.0, "S3", "Thanks both."),
            ),
        )

        fun answer(text: String): String = buildString {
            append("data: {\"type\":\"response.output_text.delta\",\"delta\":")
            append(kotlinx.serialization.json.Json.encodeToString(String.serializer(), text))
            append("}\n\n")
            append("data: {\"type\":\"response.completed\",\"response\":{}}\n\n")
        }
    }
}
