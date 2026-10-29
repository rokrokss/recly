package recly.core.chatgpt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

        /** What the core's detail reads, whether a job is in the way, and what went to the transcript editor. */
        var transcript: Transcript? = TRANSCRIPT
        var busy = false
        var editRefused = false
        val renamed = mutableListOf<Map<String, String>>()

        /** The notes' consent: with a sign-in, what lets a publication ask (docs/08 "Organizing"). */
        var agreed = true

        /** What was being filled in when a title or names were applied — inside the run. */
        val during = mutableListOf<List<FillRun>>()

        /** Holds a title back once [reached], until the test lets it go. */
        var gate: CompletableDeferred<Unit>? = null
        val reached = CompletableDeferred<Unit>()

        val fill = AutoFill(
            h.db, h.deps, summaries, consents, recordings,
            agreed = { agreed && h.account.standing().let { it == ChatGptStanding.SIGNED_IN || it == ChatGptStanding.EXPIRED } },
            setTitle = { id, title ->
                during += running()
                reached.complete(Unit)
                gate?.await()
                recordings.fillTitle(id, title).also { if (it) titles += title }
            },
            transcript = { transcript },
            busy = { busy },
            name = { _, names ->
                during += running()
                if (editRefused) null else names.size.also { renamed += names }
            },
        )
        val meta = testMeta(title = title, status = RecordingStatus.FINALIZED)
        val dir = "/data/recordings/${MetaWriter.baseName(meta)}".toPath()
        val record get() = RecordingRecord(ID, meta, dir)

        /** The recording and a sign-in. */
        suspend fun ready(signIn: Boolean = true) {
            recordings.create(meta, dir)
            if (signIn) h.signIn()
        }

        fun requests() = h.server.requests.count { it.url.endsWith("/responses") }

        suspend fun running(): List<FillRun> = fill.observe().first()

        fun instructions() = h.server.requests.last { it.url.endsWith("/responses") }.json()["instructions"]!!.jsonPrimitive.content
    }

    @Test
    fun `signed in but not agreed to the background use, a recording is published as it is`() = runBlocking {
        val f = Fixture()
        f.agreed = false
        f.ready()
        assertSame(TRANSCRIPT, f.fill.fill(f.record, TRANSCRIPT).transcript)
        assertEquals(0, f.requests())
        assertTrue(f.h.logger.events.none { it.startsWith("summary.fill") })
    }

    @Test
    fun `an untitled recording gets a title and the unnamed speakers their names, before it is published`() = runBlocking {
        val f = Fixture()
        f.ready()
        f.h.server.reply(answer("""{"title":"Launch plan","speakers":{"S1":"Mina","S2":null}}"""), headers = SSE)
        val filled = f.fill.fill(f.record, TRANSCRIPT)

        assertEquals("Launch plan", filled.title)
        assertEquals(listOf("Launch plan"), f.titles)
        assertEquals(listOf(listOf(FillRun(ID, title = true, names = true))), f.during, "shown while it is filled in")
        assertEquals(emptyList(), f.running(), "and no longer once it is")
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
        val done = f.h.logger.entries.single { it.event == "summary.fill.done" }
        assertEquals("publish", done.fields["trigger"])
        assertEquals("publish", f.h.logger.entries.single { it.event == "summary.fill.model" }.fields["trigger"])
        val start = f.h.logger.entries.single { it.event == "summary.fill.start" }.fields
        assertEquals(listOf(true, true, 2), listOf(start["title"], start["names"], start["speakers"]))

        f.h.server.reply(answer("""{"title":"Ignored","speakers":{"S2":"Joon"}}"""), headers = SSE)
        val again = f.fill.fill(f.record, TRANSCRIPT)
        assertTrue("\"title\"" !in f.instructions(), "a publication tried again, or a re-transcription, asks for what is still missing")
        assertTrue("speaker ids: S1, S2." in f.instructions())
        assertNull(again.title)
        assertEquals("Joon", again.transcript.speakers[1].name)
        assertEquals(2, f.requests())
    }

    @Test
    fun `an untitled recording whose speakers all have names asks for its title alone`() = runBlocking {
        val f = Fixture()
        f.ready()
        val named = TRANSCRIPT.copy(speakers = listOf(TranscriptSpeaker("S1", "Mina"), TranscriptSpeaker("S2", "Joon"), TranscriptSpeaker("S3", me = true)))
        f.h.server.reply(answer("""{"title":"Launch plan","speakers":{"S1":"Somebody"}}"""), headers = SSE)
        val filled = f.fill.fill(f.record, named)
        assertTrue("\"title\"" in f.instructions())
        assertTrue("speaker ids" !in f.instructions())
        assertEquals("Launch plan", filled.title)
        assertSame(named, filled.transcript, "no name was asked for, none is taken")
        assertEquals(listOf(listOf(FillRun(ID, title = true, names = false))), f.during)
        val start = f.h.logger.entries.single { it.event == "summary.fill.start" }.fields
        assertEquals(listOf(true, false, 0), listOf(start["title"], start["names"], start["speakers"]))
    }

    @Test
    fun `a titled recording asks for its speaker names alone`() = runBlocking {
        val f = Fixture(title = "Weekly sync")
        f.ready()
        f.h.server.reply(answer("""{"title":"Ignored","speakers":{"S1":"Mina"}}"""), headers = SSE)
        val filled = f.fill.fill(f.record, TRANSCRIPT)
        assertTrue("\"title\"" !in f.instructions())
        assertTrue("speaker ids: S1, S2." in f.instructions())
        assertNull(filled.title)
        assertEquals("Weekly sync", f.recordings.get(ID)!!.meta.title, "a title is never replaced")
        assertEquals("Mina", filled.transcript.speakers[0].name)
    }

    @Test
    fun `a title the recording has and the names it has are not asked for`() = runBlocking {
        val f = Fixture(title = "Weekly sync")
        f.ready()
        val named = TRANSCRIPT.copy(speakers = listOf(TranscriptSpeaker("S1", "Mina"), TranscriptSpeaker("S2", "Joon"), TranscriptSpeaker("S3", me = true)))
        assertSame(named, f.fill.fill(f.record, named).transcript)
        assertEquals(0, f.requests())
        assertEquals("nothing", f.h.logger.entries.last().fields["reason"])

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
    fun `signed out, the transcript goes as it is and nothing is sent`() = runBlocking {
        val f = Fixture()
        f.ready(signIn = false)
        val sent = f.h.server.requests.size
        assertSame(TRANSCRIPT, f.fill.fill(f.record, TRANSCRIPT).transcript)
        assertEquals(sent, f.h.server.requests.size)
        assertTrue(f.h.logger.events.none { it.startsWith("summary.fill") }, "the same no-op as for anyone not agreed")
    }

    @Test
    fun `on iPhone nothing is sent before ChatGPT is allowed`() = runBlocking {
        val f = Fixture(requireTransferConsent = true)
        f.ready()
        assertSame(TRANSCRIPT, f.fill.fill(f.record, TRANSCRIPT).transcript)
        assertEquals(0, f.requests())
        assertEquals("consent", f.h.logger.entries.last().fields["reason"], "the notes' consent gives the permission; a publication cannot")

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
    fun `the fill uses its own model and effort when the plan offers it, else the notes' model as it is`() = runBlocking {
        val f = Fixture()
        f.recordings.create(f.meta, f.dir)
        f.h.signIn(models = """{"models":[{"slug":"gpt-a","display_name":"GPT A","visibility":"list"},
            {"slug":"gpt-6.1-terra","display_name":"Second","visibility":"list"},
            {"slug":"gpt-6.1-sol","display_name":"First","visibility":"list"}]}""")
        f.h.server.reply(answer("""{"title":"Launch plan"}"""), headers = SSE)
        f.fill.fill(f.record, TRANSCRIPT)
        val body = f.h.server.requests.last().json()
        assertEquals("gpt-6.1-sol", body["model"]!!.jsonPrimitive.content, "the best measured model the plan offers")
        assertEquals("low", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)

        val other = Fixture()
        other.ready()
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
        f.h.signIn(models = """{"models":[{"slug":"gpt-6.1-terra","display_name":"Second","visibility":"list"}]}""")
        f.h.server.reply("""{"error":{"code":"unsupported_parameter","message":"reasoning"}}""", status = 400)
        f.h.server.reply(answer("""{"title":"Launch plan"}"""), headers = SSE)
        assertEquals("Launch plan", f.fill.fill(f.record, TRANSCRIPT).title)
        val (first, second) = f.h.server.requests.filter { it.url.endsWith("/responses") }.map { it.json() }
        assertEquals("low", first["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertNull(second["reasoning"])
        assertTrue("summary.fill.effort.refused" in f.h.logger.events, "the fallback says so in the log")
    }

    @Test
    fun `what a recording lacks is one rule for the core and the shells`() {
        assertEquals(FillNeeds(true, listOf("S1", "S2")), FillNeeds.of(null, TRANSCRIPT), "Me is never asked about")
        assertEquals(FillNeeds(false, listOf("S1", "S2")), FillNeeds.of("2026-08-26 Interview", TRANSCRIPT), "an import's file name is a title")
        assertTrue(FillNeeds.of(" ", TRANSCRIPT).title)
        val named = TRANSCRIPT.copy(speakers = listOf(TranscriptSpeaker("S1", "Mina"), TranscriptSpeaker("S2", "Joon"), TranscriptSpeaker("S3", me = true)))
        assertFalse(FillNeeds.of("Weekly sync", named).any)
        val silent = TRANSCRIPT.copy(segments = TRANSCRIPT.segments.map { it.copy(text = " ") })
        assertEquals(FillNeeds(false, emptyList()), FillNeeds.of(null, silent), "nothing said, nothing to tell")
        val quiet = TRANSCRIPT.copy(speakers = TRANSCRIPT.speakers + TranscriptSpeaker("S4"))
        assertEquals(listOf("S1", "S2"), FillNeeds.of(null, quiet).speakers, "a speaker who says nothing is not asked about")
        assertTrue(FillNeeds.of(null, TRANSCRIPT).names)
        assertFalse(FillNeeds.of("Weekly sync", named).names)
        assertEquals(FillNeeds(true, emptyList()), FillNeeds.of(null, TRANSCRIPT).only(title = true, names = false))
        assertEquals(FillNeeds(false, listOf("S1", "S2")), FillNeeds.of(null, TRANSCRIPT).only(title = false, names = true))
    }

    @Test
    fun `asked for both, a missing title and the unnamed speakers come in one request, the names as an edit`() = runBlocking {
        val f = Fixture()
        f.ready()
        f.h.server.reply(answer("""{"title":"Launch plan","speakers":{"S1":"Mina","S2":null}}"""), headers = SSE)
        assertEquals(FillResult.Filled(title = true, names = 1), f.fill.manual(ID, title = true, names = true))
        assertEquals("Launch plan", f.recordings.get(ID)!!.meta.title)
        assertEquals(listOf(mapOf("S1" to "Mina")), f.renamed, "only what was asked, through the editor")
        assertEquals("manual", f.h.logger.entries.single { it.event == "summary.fill.done" }.fields["trigger"])
        assertEquals("manual", f.h.logger.entries.single { it.event == "summary.fill.start" }.fields["trigger"])

        f.h.server.reply(answer("""{"speakers":{}}"""), headers = SSE)
        assertEquals(FillResult.Filled(title = false, names = 0), f.fill.manual(ID, title = true, names = true), "asked, and ChatGPT could not tell")
    }

    @Test
    fun `More - Fill in title asks for the title alone`() = runBlocking {
        val f = Fixture()
        f.ready()
        f.h.server.reply(answer("""{"title":"Launch plan","speakers":{"S1":"Mina"}}"""), headers = SSE)
        assertEquals(FillResult.Filled(title = true, names = 0), f.fill.manual(ID, title = true, names = false))
        assertTrue("\"title\"" in f.instructions())
        assertTrue("speaker ids" !in f.instructions())
        assertEquals(emptyList(), f.renamed, "the names were not asked for")
        assertEquals(listOf(listOf(FillRun(ID, title = true, names = false))), f.during)
        assertEquals(emptyList(), f.running())

        assertEquals(FillResult.NothingToFill, f.fill.manual(ID, title = true, names = false), "it has one now")
        assertEquals(1, f.requests())
    }

    @Test
    fun `More - Fill in speaker names asks for the names alone`() = runBlocking {
        val f = Fixture()
        f.ready()
        f.h.server.reply(answer("""{"title":"Ignored","speakers":{"S1":"Mina","S2":"Joon"}}"""), headers = SSE)
        assertEquals(FillResult.Filled(title = false, names = 2), f.fill.manual(ID, title = false, names = true))
        assertTrue("\"title\"" !in f.instructions())
        assertEquals(emptyList(), f.titles)
        assertNull(f.recordings.get(ID)!!.meta.title)
        assertEquals(listOf(mapOf("S1" to "Mina", "S2" to "Joon")), f.renamed)
        assertEquals(listOf(listOf(FillRun(ID, title = false, names = true))), f.during)
        val start = f.h.logger.entries.single { it.event == "summary.fill.start" }.fields
        assertEquals(listOf("manual", false, true, 2), listOf(start["trigger"], start["title"], start["names"], start["speakers"]))
    }

    @Test
    fun `Organize asks for the names alone, and its fill is no second line beside the notes' own`() = runBlocking {
        val f = Fixture()
        f.ready()
        f.h.server.beforeReply = { if (it.url.endsWith("/responses")) f.during += f.running() }
        f.h.server.reply(answer("""{"title":"Ignored","speakers":{"S1":"Mina","S2":"Joon"}}"""), headers = SSE)
        f.fill.withOrganize(ID)
        f.fill.awaitRuns()
        assertEquals(listOf(mapOf("S1" to "Mina", "S2" to "Joon")), f.renamed)
        assertEquals(emptyList(), f.titles)
        assertEquals(listOf(emptyList<FillRun>(), emptyList()), f.during, "neither while asking nor while applying")
        assertEquals("organize", f.h.logger.entries.single { it.event == "summary.fill.start" }.fields["trigger"])
    }

    @Test
    fun `More keeps an import's title and asks only for the names`() = runBlocking {
        val f = Fixture(title = "2026-08-26 Interview")
        f.ready()
        f.h.server.reply(answer("""{"title":"Ignored","speakers":{"S2":"Joon"}}"""), headers = SSE)
        assertEquals(FillResult.Filled(title = false, names = 1), f.fill.manual(ID, title = true, names = true))
        assertTrue("\"title\"" !in f.h.server.requests.last().json()["instructions"]!!.jsonPrimitive.content)
        assertEquals("2026-08-26 Interview", f.recordings.get(ID)!!.meta.title)
        assertEquals(emptyList(), f.titles)
    }

    @Test
    fun `More sends nothing when there is nothing to fill, no transcript, or a job in the way`() = runBlocking {
        val f = Fixture(title = "Weekly sync")
        f.ready()
        f.transcript = TRANSCRIPT.copy(speakers = listOf(TranscriptSpeaker("S1", "Mina"), TranscriptSpeaker("S2", "Joon"), TranscriptSpeaker("S3", me = true)))
        assertEquals(FillResult.NothingToFill, f.fill.manual(ID, title = true, names = true))
        f.transcript = TRANSCRIPT
        assertEquals(FillResult.NothingToFill, f.fill.manual(ID, title = true, names = false), "the title is there; the names were not asked for")
        f.transcript = null
        assertEquals(FillResult.NoTranscript, f.fill.manual(ID, title = false, names = true))
        assertEquals(FillResult.NoTranscript, f.fill.manual("01J9NOSUCHRECORDING0000000", title = true, names = false))
        f.transcript = TRANSCRIPT
        f.busy = true
        assertEquals(FillResult.Busy, f.fill.manual(ID, title = false, names = true))
        assertEquals(0, f.requests())

        f.busy = false
        f.editRefused = true
        f.h.server.reply(answer("""{"speakers":{"S1":"Mina"}}"""), headers = SSE)
        assertEquals(FillResult.Busy, f.fill.manual(ID, title = false, names = true), "a transcription started while ChatGPT answered: nothing changed")
    }

    @Test
    fun `More ignores the pause, and a usage limit it meets still pauses the publications`() = runBlocking {
        val f = Fixture()
        f.ready()
        f.h.server.reply("""{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}""", status = 429)
        val failed = assertIs<FillResult.Failed>(f.fill.manual(ID, title = true, names = false))
        assertTrue(failed.reason.startsWith(CoreMessage.CHATGPT_USAGE_LIMIT.name), failed.reason)
        assertEquals("manual", f.h.logger.entries.last { it.event == "summary.fill.failed" }.fields["trigger"])

        assertSame(TRANSCRIPT, f.fill.fill(f.record, TRANSCRIPT).transcript)
        assertEquals("paused", f.h.logger.entries.last().fields["reason"])
        assertEquals(1, f.requests())

        f.h.server.reply(answer("""{"title":"Launch plan"}"""), headers = SSE)
        assertEquals(FillResult.Filled(title = true, names = 0), f.fill.manual(ID, title = true, names = false), "the user asked")
        assertEquals(2, f.requests())
    }

    @Test
    fun `More signed out says so`() = runBlocking {
        val f = Fixture()
        f.ready(signIn = false)
        val failed = assertIs<FillResult.Failed>(f.fill.manual(ID, title = true, names = false))
        assertTrue(failed.reason.startsWith(CoreMessage.CHATGPT_SIGN_IN_REQUIRED.name), failed.reason)
    }

    @Test
    fun `on iPhone More answers as Ask does before ChatGPT is allowed`() = runBlocking {
        val f = Fixture(requireTransferConsent = true)
        f.ready()
        assertEquals(FillResult.Failed(CoreMessage.TRANSFER_CONSENT_REQUIRED.code()), f.fill.manual(ID, title = false, names = true))
        assertEquals(0, f.requests())
    }

    @Test
    fun `what is being filled in is shown until the answer is applied, and cleared however the run ends`() = runBlocking {
        val f = Fixture()
        f.ready()
        f.h.server.beforeReply = { if (it.url.endsWith("/responses")) f.during += f.running() }

        f.h.server.reply(answer("I could not tell."), headers = SSE)
        assertSame(TRANSCRIPT, f.fill.fill(f.record, TRANSCRIPT).transcript)
        assertEquals(listOf(listOf(FillRun(ID, title = true, names = true))), f.during, "a publication's")
        assertEquals(emptyList(), f.running(), "cleared when the answer could not be read")

        f.h.server.reply("""{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}""", status = 429)
        assertIs<FillResult.Failed>(f.fill.manual(ID, title = true, names = false))
        assertEquals(listOf(FillRun(ID, title = true, names = false)), f.during.last(), "the detail's")
        assertEquals(emptyList(), f.running(), "cleared on a failure")

        // A publication cut short: the pass is cancelled while ChatGPT has not answered.
        f.h.clock.advance(5.hours)
        f.h.server.reply(f.h.tokens("access-2", "refresh-2"))
        f.h.server.beforeReply = { if (it.url.endsWith("/responses")) awaitCancellation() }
        val pass = launch(Dispatchers.Default) { f.fill.fill(f.record, TRANSCRIPT) }
        assertEquals(listOf(FillRun(ID, title = true, names = true)), withTimeout(10_000) { f.fill.observe().first { it.isNotEmpty() } })
        pass.cancelAndJoin()
        assertEquals(emptyList(), f.running(), "cleared when cancelled")
    }

    @Test
    fun `asking again for what the run going asks answers that run, and the other half runs after it`() = runBlocking {
        val f = Fixture()
        f.ready()
        f.gate = CompletableDeferred()
        f.h.server.reply(answer("""{"title":"Launch plan","speakers":{"S1":"Mina"}}"""), headers = SSE)
        val first = async(Dispatchers.Default) { f.fill.manual(ID, title = true, names = false) }
        withTimeout(10_000) { f.reached.await() }
        val again = async(Dispatchers.Default) { f.fill.manual(ID, title = true, names = false) }
        val other = async(Dispatchers.Default) { f.fill.manual(ID, title = false, names = true) }
        // Both reach the run going while its title is held back.
        delay(300)
        assertEquals(listOf(FillRun(ID, title = true, names = false)), f.running(), "only what is asked now")
        f.h.server.reply(answer("""{"speakers":{"S1":"Mina"}}"""), headers = SSE)
        f.gate!!.complete(Unit)

        assertEquals(FillResult.Filled(title = true, names = 0), first.await())
        assertEquals(FillResult.Filled(title = true, names = 0), again.await(), "the same run's answer")
        assertEquals(FillResult.Filled(title = false, names = 1), other.await(), "its own run, after")
        assertEquals(2, f.requests())
        assertTrue("\"title\"" !in f.instructions(), "the second run asks for the names alone")
        assertEquals(listOf(FillRun(ID, title = true, names = false)), f.during[0])
        assertEquals(listOf(FillRun(ID, title = false, names = true)), f.during[1])
        assertEquals(emptyList(), f.running())
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
