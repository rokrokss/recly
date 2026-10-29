@file:OptIn(ExperimentalTime::class)

package recly.core.notes

import com.networknt.schema.InputFormat
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path
import okio.Path.Companion.toPath
import recly.core.chatgpt.ChatGptConnection
import recly.core.chatgpt.ChatGptHarness
import recly.core.chatgpt.SummaryPreferences
import recly.core.db.RecDatabase
import recly.core.job.EnqueueResult
import recly.core.job.JobStatus
import recly.core.job.JobStore
import recly.core.job.StepReport
import recly.core.job.StepStatus
import recly.core.message.CoreMessage
import recly.core.message.CoreMessageRef
import recly.core.model.RecordingMeta
import recly.core.model.RecordingStatus
import recly.core.model.Source
import recly.core.model.Track
import recly.core.model.isoUtc
import recly.core.model.recJson
import recly.core.platform.SecureStore
import recly.core.processing.ExternalTranscription
import recly.core.processing.ProcessingSaveResult
import recly.core.processing.TranscriptionMode
import recly.core.recording.MetaWriter
import recly.core.testing.CoreFixture
import recly.core.testing.FakeLocalEngine
import recly.core.testing.SEEDED_AUDIO
import recly.core.testing.SEEDED_AUDIO_SHA256
import recly.core.testing.START
import recly.core.testing.testMeta
import recly.core.testing.testPart
import recly.core.transcribe.EditResult
import recly.core.transcribe.PollResult
import recly.core.transcribe.RetranscribeResult
import recly.core.transcribe.SttContext
import recly.core.transcribe.SttProvider
import recly.core.transcribe.SttResult
import recly.core.transcribe.SttSegment
import recly.core.transcribe.Submitted
import recly.core.transcribe.TranscriptEdit
import recly.core.transfer.AcceptMetaResult

/** docs/08 "Organizing" through the whole core: consent, the parts while recording, and the final pass after Stop. */
class NotesTest {
    private val chatGpt = ChatGptHarness()
    private val engine = FakeLocalEngine(supportsDiarization = true)
    private val f = CoreFixture(engine = engine, chatGpt = chatGpt.server.transport(chatGpt.fs))
    private val notes get() = f.core.notes

    // --- ChatGPT, scripted ---

    private suspend fun ready(consent: Boolean = true) {
        chatGpt.signIn(account = f.core.chatGpt)
        if (consent) notes.consent()
        chatGpt.answerFills()
    }

    private fun answer(json: String) {
        chatGpt.server.reply(
            "data: {\"type\":\"response.output_text.delta\",\"delta\":" + Json.encodeToString(String.serializer(), json) +
                "}\n\ndata: {\"type\":\"response.completed\",\"response\":{}}\n\n",
            headers = mapOf("Content-Type" to "text/event-stream"),
        )
    }

    private fun usageLimit() = chatGpt.server.reply("""{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}""", status = 429)

    private fun item(kind: String, text: String, at: String, quote: String, owner: String? = null, due: String? = null): String =
        kotlinx.serialization.json.buildJsonObject {
            put("kind", kotlinx.serialization.json.JsonPrimitive(kind))
            put("text", kotlinx.serialization.json.JsonPrimitive(text))
            put("owner", kotlinx.serialization.json.JsonPrimitive(owner))
            put("due", kotlinx.serialization.json.JsonPrimitive(due))
            put("at", kotlinx.serialization.json.JsonPrimitive(at))
            put("quote", kotlinx.serialization.json.JsonPrimitive(quote))
        }.toString()

    private fun items(vararg items: String) = """{"items":[${items.joinToString(",")}]}"""

    private fun conversation(summary: String, vararg items: String) = """{"summary":"$summary","items":[${items.joinToString(",")}]}"""

    private fun responses() = chatGpt.server.requests.filter { it.url.endsWith("/responses") && !chatGpt.isFill(it) }

    private fun input(index: Int): String =
        responses()[index].json()["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content

    private fun instructions(index: Int): String = responses()[index].json()["instructions"]!!.jsonPrimitive.content

    // --- A recording in progress ---

    /** `alpha1 alpha2 … alpha{n}`: words a quote can be cut out of. */
    private fun speech(word: String, n: Int = 45) = (1..n).joinToString(" ") { "$word$it" }

    private suspend fun startRecording(): RecordingMeta {
        val meta = testMeta(parts = emptyList())
        f.core.recordings.create(meta, f.dirOf(meta))
        return meta
    }

    /** The recorder files part [n]: its file on disk, then `addPart` — and waits for what organizing does with it. */
    private suspend fun file(meta: RecordingMeta, n: Int) {
        val part = testPart(meta, n).copy(bytes = SEEDED_AUDIO.length.toLong(), sha256 = SEEDED_AUDIO_SHA256)
        f.fs.createDirectories(f.dirOf(meta))
        f.fs.write(f.dirOf(meta) / part.file) { writeUtf8(SEEDED_AUDIO) }
        f.core.recordings.addPart(meta.recordingId, part)
        notes.awaitWork()
    }

    private fun partPath(meta: RecordingMeta, n: Int): String = (f.dirOf(meta) / testPart(meta, n).file).toString()

    private fun notesFile(meta: RecordingMeta): Path = f.dirOf(meta) / NotesFile.name(MetaWriter.baseName(meta))

    private fun stored(meta: RecordingMeta): NotesFile =
        recly.core.model.recJson.decodeFromString(NotesFile.serializer(), f.fs.read(notesFile(meta)) { readUtf8() })

    private suspend fun stop(meta: RecordingMeta, parts: Int) {
        f.core.recordings.finalize(meta.recordingId, START, parts * 900.0)
        assertIs<EnqueueResult.Enqueued>(f.core.enqueue(meta.recordingId))
    }

    private suspend fun job() = f.core.jobs.list().single { !it.retranscription }

    private suspend fun organizeStep(jobId: String) = f.core.jobs.steps(jobId).single { it.stepId == "organize" }

    // --- Consent ---

    @Test
    fun `organizing waits for a sign-in and the user's consent, which goes with the sign-out`() = runBlocking {
        assertFalse(notes.needsConsent())
        assertEquals(false, notes.observeNeedsConsent().first())
        chatGpt.signIn(account = f.core.chatGpt)
        assertTrue(notes.needsConsent(), "signed in before organizing existed: asked at the first recording")
        assertEquals(true, notes.observeNeedsConsent().first())

        engine.answer = { listOf(SttSegment(0.0, 30.0, null, speech("alpha"))) }
        val meta = startRecording()
        file(meta, 1)
        assertTrue(engine.requests.isEmpty(), "nothing is transcribed for the notes before the user agreed")
        assertEquals(0, responses().size)
        assertEquals(RecordingNotes(meta.recordingId, NotesStage.NONE, null, emptyList(), emptyList(), emptyList(), false), notes.get(meta.recordingId))

        notes.consent()
        assertFalse(notes.needsConsent())
        assertEquals(false, notes.observeNeedsConsent().first())
        assertTrue("organize.consent" in f.logger.events)

        chatGpt.server.reply("""{"issuer":"https://auth.openai.com","revocation_endpoint":"https://auth.openai.com/oauth/revoke"}""")
        chatGpt.server.reply("", status = 200)
        f.core.chatGpt.signOut()
        assertFalse(notes.needsConsent(), "signed out: nothing to ask")
        chatGpt.signIn(account = f.core.chatGpt)
        assertTrue(notes.needsConsent(), "a new sign-in asks again")
    }

    // --- While recording ---

    @Test
    fun `a part filed while recording is transcribed on the device alone and its checked items are shown`() = runBlocking {
        ready()
        engine.answer = { listOf(SttSegment(0.0, 20.0, null, speech("alpha")), SttSegment(20.0, 40.0, null, speech("bravo", 10))) }
        answer(
            items(
                item("decision", "Use alpha", "00:00:00", "alpha3 alpha4 alpha5 alpha6"),
                item("todo", "Invented", "00:00:05", "nobody said any of these words"),
            ),
        )
        val meta = startRecording()
        file(meta, 1)

        val request = engine.requests.single()
        assertEquals(partPath(meta, 1), request.path, "the part on its own")
        assertFalse(request.diarize)
        val shown = notes.get(meta.recordingId)
        assertEquals(NotesStage.PROVISIONAL, shown.stage)
        assertEquals(listOf("Use alpha"), shown.decisions.map { it.text })
        assertEquals(emptyList(), shown.todos, "a quote that is not in the transcript is dropped")
        val decision = shown.decisions.single()
        assertTrue(decision.provisional)
        assertEquals(0.0, decision.atSec)
        assertEquals("alpha3 alpha4 alpha5 alpha6", decision.quote)
        assertEquals(shown, notes.observe(meta.recordingId).first())

        val body = responses().single().json()
        assertEquals("false", body["store"].toString())
        assertEquals("\"gpt-a\"", body["model"].toString(), "the model Settings picked")
        val sent = input(0)
        assertTrue("THIS PART:\n[00:00:00] alpha1 alpha2" in sent, sent)
        assertTrue("[00:00:20] bravo1" in sent)
        assertTrue("NOTES SO FAR:\n(none yet)\n" in sent && "PREVIOUS PART (its end, for context only):\n(none)\n" in sent, sent)
        assertTrue("decisions, to-dos and open questions" in instructions(0))
        assertTrue(f.fs.exists(f.dirOf(meta) / ".provisional-p001.json"), "the part's transcript is kept beside it")
        assertEquals(1, f.logger.fieldsOf("organize.part.done").single()["items"])

        val lines = notes.linesAround(meta.recordingId, 0.0)
        assertEquals(listOf(0.0, 20.0), lines.map { it.atSec }, "there is no playback while recording: the part's lines")
        assertTrue(lines.all { it.provisional && it.speakerId == null })
    }

    // --- A watch recording, as it arrives (docs/03 "Watch → phone transfer contract") ---

    private val watch = testMeta(recordingId = "01J9WATCHABCDEFGHJKMNPQRST", source = Source.WATCH)

    private fun watchPart(n: Int) = testPart(watch, n).copy(bytes = SEEDED_AUDIO.length.toLong(), sha256 = SEEDED_AUDIO_SHA256)

    /** The watch's in-progress meta, listing [parts]. */
    private suspend fun progressOf(vararg parts: Int): Boolean =
        f.core.transfer.acceptProgress(watch.recordingId, recJson.encodeToString(watch.copy(parts = parts.map(::watchPart))))

    /** Part [n] as the transfer API hands it over: a file in a staging directory. */
    private suspend fun send(n: Int): Boolean {
        val staged = "/incoming/${watchPart(n).file}".toPath()
        f.fs.createDirectories(staged.parent!!)
        f.fs.write(staged) { writeUtf8(SEEDED_AUDIO) }
        return f.core.transfer.acceptPart(watch.recordingId, n, Track.MONO, SEEDED_AUDIO_SHA256, staged).ok
    }

    @Test
    fun `a watch recording is organized part by part as it arrives, and its final meta files it with one job`() = runBlocking {
        ready()
        engine.answer = { listOf(SttSegment(0.0, 20.0, null, speech("alpha"))) }
        answer(items(item("decision", "Use alpha", "00:00:00", "alpha3 alpha4 alpha5 alpha6")))

        assertTrue(progressOf(1))
        assertTrue(send(1))
        notes.awaitWork()

        assertEquals("/data/recordings/${watch.recordingId}/${watchPart(1).file}", engine.requests.single().path, "the part as it was filed here")
        val first = notes.get(watch.recordingId)
        assertEquals(NotesStage.PROVISIONAL, first.stage)
        assertEquals(listOf("Use alpha"), first.decisions.map { it.text })

        // A part ahead of the meta that lists it is organized once that meta comes, at its place on the recording.
        engine.answer = { listOf(SttSegment(0.0, 20.0, null, speech("bravo"))) }
        answer(items(item("todo", "Send bravo", "00:15:05", "bravo3 bravo4 bravo5 bravo6")))
        assertTrue(send(2))
        notes.awaitWork()
        assertEquals(1, engine.requests.size)
        assertTrue(progressOf(1, 2))
        notes.awaitWork()
        assertEquals(2, engine.requests.size)
        assertEquals(905.0, notes.get(watch.recordingId).todos.single().atSec)

        // The final meta still files it, and the job is made once.
        val final = watch.copy(
            parts = listOf(watchPart(1), watchPart(2)),
            endedAt = "2026-08-26T01:30:00.000Z",
            durationSec = 1800.0,
            status = RecordingStatus.FINALIZED,
        )
        assertIs<AcceptMetaResult.Complete>(f.core.transfer.acceptMeta(recJson.encodeToString(final)))
        val job = assertIs<EnqueueResult.Enqueued>(f.core.enqueue(watch.recordingId))
        assertEquals(job, f.core.enqueue(watch.recordingId), "a meta sent again lands on the same job")
        assertEquals(listOf(job.jobId), f.core.jobs.list().filter { it.recordingId == watch.recordingId }.map { it.id })
        assertFalse(progressOf(1, 2), "a late in-progress meta does not re-open it")
        assertEquals(2, engine.requests.size)
        assertEquals(2, responses().size)
    }

    @Test
    fun `a watch's own recording is not organized on the watch`() = runBlocking {
        ready()
        engine.answer = { listOf(SttSegment(0.0, 20.0, null, speech("alpha"))) }
        val own = testMeta(recordingId = "01J9WATCHABCDEFGHJKMNPQRST", source = Source.WATCH)
        f.core.recordings.create(own, f.dirOf(own))

        file(own, 1)

        assertTrue(engine.requests.isEmpty())
        assertEquals(0, responses().size)
    }

    private class FakeProvider(private val result: SttResult) : SttProvider {
        override val name = "assemblyai"
        val submitted = mutableListOf<Pair<String, Double?>>()
        var polls = 0

        override suspend fun submit(ctx: SttContext, file: okio.Path): Submitted {
            submitted += file.toString() to ctx.audioDurationSec
            return Submitted.Polling("job-1")
        }

        override suspend fun poll(ctx: SttContext, ref: String): PollResult = if (++polls < 2) PollResult.Pending else PollResult.Done(result)
    }

    private suspend fun external() {
        val current = f.core.processingSettings.initialize().document
        val settings = current.settings.copy(
            transcription = current.settings.transcription.copy(mode = TranscriptionMode.EXTERNAL, external = ExternalTranscription("assemblyai", "assemblyai")),
        )
        assertIs<ProcessingSaveResult.Saved>(f.core.processingSettings.save(settings, current.revision))
        f.store.put(SecureStore.SECRETS, "assemblyai", "key-1".encodeToByteArray())
    }

    @Test
    fun `with an external provider each part is submitted and polled on its own, with the user's key`() = runBlocking {
        ready()
        external()
        val provider = FakeProvider(SttResult(listOf(SttSegment(0.0, 25.0, "A", speech("delta")), SttSegment(25.0, 40.0, "B", speech("echo", 5))), "en", null, "universal"))
        notes.providers = { if (it == "assemblyai") provider else null }
        notes.pollMillis = 1
        answer(items(item("todo", "Send the delta report", "00:15:25", "echo1 echo2 echo3", owner = "S2", due = "Monday")))
        val meta = startRecording()
        file(meta, 2)

        assertEquals(listOf<Pair<String, Double?>>(partPath(meta, 2) to 900.0), provider.submitted)
        assertEquals(2, provider.polls)
        assertTrue(engine.requests.isEmpty())
        val todo = notes.get(meta.recordingId).todos.single()
        assertEquals(925.0, todo.atSec, "on the recording's axis")
        assertEquals("Monday", todo.due)
        assertNull(todo.owner, "a part's own label names nobody the user can see")
        assertEquals("S2", stored(meta).items.single().owner)
        assertTrue("[00:15:25] S2: echo1" in input(0), "labels, never names")
    }

    @Test
    fun `later parts only add — what is shown stays, a repeat is not shown twice, a deletion stays deleted`() = runBlocking {
        ready()
        val meta = startRecording()
        engine.answer = { request ->
            if (request.path == partPath(meta, 1)) listOf(SttSegment(0.0, 30.0, null, speech("alpha"))) else listOf(SttSegment(0.0, 30.0, null, speech("bravo")))
        }
        answer(items(item("decision", "Use alpha", "00:00:00", "alpha1 alpha2 alpha3 alpha4"), item("todo", "Book alpha room", "00:00:10", "alpha10 alpha11 alpha12")))
        file(meta, 1)
        val first = notes.get(meta.recordingId)
        val decision = first.decisions.single()
        assertTrue(notes.deleteItem(meta.recordingId, first.todos.single().id))
        assertFalse(notes.deleteItem(meta.recordingId, "01J9NOSUCHITEM000000000000"))
        assertEquals(emptyList(), notes.get(meta.recordingId).todos)

        answer(
            items(
                item("decision", "Use alpha", "00:15:00", "bravo1 bravo2 bravo3 bravo4"),
                item("todo", "Book the alpha room", "00:15:05", "bravo5 bravo6 bravo7"),
                item("decision", "Drop alpha and use bravo", "00:15:10", "bravo9 bravo10 bravo11"),
            ),
        )
        file(meta, 2)

        val second = notes.get(meta.recordingId)
        assertEquals(listOf("Drop alpha and use bravo", "Use alpha"), second.decisions.map { it.text }, "newest first; a reversal is a new item")
        assertEquals(decision, second.decisions.last(), "an item shown is never changed while recording")
        assertEquals(emptyList(), second.todos, "the deleted item does not come back")
        val sent = input(1)
        assertTrue("NOTES SO FAR:\n- decision: Use alpha [00:00:00]\n" in sent, sent)
        assertFalse("Book alpha room" in sent, "a deleted item is not shown, so it is not sent")
        assertTrue("PREVIOUS PART (its end, for context only):\n[00:00:00] alpha1" in sent)
        assertTrue("THIS PART:\n[00:15:00] bravo1" in sent)
        val done = f.logger.fieldsOf("organize.part.done").last()
        assertEquals(1, done["items"])
        assertEquals(1, done["tombstoned"])
        assertEquals(1, done["repeated"])
        assertEquals(listOf(1, 2), stored(meta).parts)
    }

    @Test
    fun `numbers and addresses leave as placeholders and come back on the device`() = runBlocking {
        ready()
        val said = speech("alpha", 40) + " call me on 010-1234-5678 or write to mina@example.com"
        engine.answer = { listOf(SttSegment(0.0, 30.0, null, said)) }
        answer(items(item("todo", "Call [phone#1] about alpha", "00:00:00", "call me on [phone#1] or write to [email#1]", due = "today")))
        val meta = startRecording()
        file(meta, 1)

        val sent = input(0)
        assertFalse("010-1234-5678" in sent || "mina@example.com" in sent, sent)
        assertTrue("call me on [phone#1] or write to [email#1]" in sent)
        val todo = notes.get(meta.recordingId).todos.single()
        assertEquals("Call 010-1234-5678 about alpha", todo.text)
        assertEquals("call me on 010-1234-5678 or write to mina@example.com", todo.quote, "the quote is checked as it was said")
    }

    @Test
    fun `a part with too few words is not sent`() = runBlocking {
        ready()
        engine.answer = { listOf(SttSegment(0.0, 5.0, null, "just a few words here")) }
        val meta = startRecording()
        file(meta, 1)

        assertEquals(0, responses().size)
        assertEquals("short", f.logger.fieldsOf("organize.part.skipped").single()["reason"])
        assertEquals(listOf(1), stored(meta).parts)
        assertEquals(NotesStage.PROVISIONAL, notes.get(meta.recordingId).stage)
    }

    @Test
    fun `an error while recording leaves the part out and the next part goes on`() = runBlocking {
        ready()
        val meta = startRecording()
        engine.answer = { listOf(SttSegment(0.0, 30.0, null, speech("alpha"))) }
        usageLimit()
        file(meta, 1)
        assertEquals(1, responses().size)
        assertTrue(notes.get(meta.recordingId).isEmpty)
        val failed = f.logger.fieldsOf("organize.part.failed").single()
        assertEquals("CHATGPT_USAGE_LIMIT", failed["reason"])
        assertEquals("extract", failed["stage"])

        engine.answer = { error("the engine stopped") }
        file(meta, 2)
        assertEquals("transcribe", f.logger.fieldsOf("organize.part.failed").last()["stage"])
        assertEquals(1, responses().size)

        engine.answer = { listOf(SttSegment(0.0, 30.0, null, speech("charlie"))) }
        answer(items(item("question", "Charlie?", "00:30:00", "charlie1 charlie2 charlie3")))
        file(meta, 3)
        assertEquals(2, responses().size, "no part is asked twice")
        assertEquals(listOf("Charlie?"), notes.get(meta.recordingId).questions.map { it.text })
        assertEquals(listOf(1, 2, 3), stored(meta).parts)
    }

    // --- After Stop ---

    /** Two stretches of speech ten minutes and more apart, the second over 45 minutes long. */
    private val recording = listOf(
        SttSegment(0.0, 30.0, "spk-a", speech("alpha")),
        SttSegment(30.0, 60.0, "spk-b", speech("able")),
        SttSegment(700.0, 730.0, "spk-a", speech("bravo")),
        SttSegment(1290.0, 1320.0, "spk-b", speech("baker")),
        SttSegment(1880.0, 1910.0, "spk-a", speech("bingo", 10)),
        SttSegment(2400.0, 2430.0, "spk-b", speech("charlie")),
        SttSegment(2990.0, 3020.0, "spk-a", speech("chess")),
        SttSegment(3580.0, 3610.0, "spk-b", speech("cobra", 10)),
        SttSegment(3870.0, 3900.0, "spk-a", speech("comet", 10)),
    )

    @Test
    fun `after Stop the final pass cuts the conversations, writes their notes and replaces the provisional ones`() = runBlocking {
        ready()
        val meta = startRecording()
        engine.answer = { listOf(SttSegment(0.0, 30.0, null, speech("alpha"))) }
        answer(
            items(
                item("decision", "Ship alpha", "00:00:00", "alpha1 alpha2 alpha3 alpha4"),
                item("question", "Who owns alpha?", "00:00:05", "alpha8 alpha9 alpha10"),
                item("todo", "Write the alpha memo", "00:00:10", "alpha15 alpha16 alpha17"),
            ),
        )
        file(meta, 1)
        val provisional = notes.get(meta.recordingId)
        val shipped = provisional.decisions.single()
        assertTrue(notes.deleteItem(meta.recordingId, provisional.todos.single().id))
        engine.answer = { listOf(SttSegment(0.0, 5.0, null, "a short part")) }
        for (n in 2..5) file(meta, n)
        assertEquals(1, responses().size)

        engine.answer = { recording }
        answer("""{"boundaries":["00:40:00"]}""")
        answer(
            conversation(
                "Alpha was shipped.",
                item("decision", "Ship alpha", "00:00:00", "alpha1 alpha2 alpha3 alpha4"),
                item("todo", "Write the alpha memo", "00:00:30", "able1 able2 able3", owner = "S2"),
                item("todo", "Order able parts", "00:00:30", "able5 able6 able7", owner = "S2", due = "Friday"),
            ),
        )
        answer(conversation("Bravo was planned.", item("decision", "Plan bravo", "00:11:40", "bravo1 bravo2 bravo3")))
        answer(
            conversation(
                "Charlie was reviewed.",
                item("question", "When does charlie start?", "00:40:00", "charlie5 charlie6 charlie7"),
                item("decision", "Made up", "00:41:00", "words nobody said"),
            ),
        )
        answer("""{"overview":"Alpha shipped, bravo planned, charlie reviewed."}""")
        stop(meta, 5)
        f.drain(6)

        assertEquals(JobStatus.DONE, job().status)
        assertEquals(6, responses().size)
        assertTrue("where a new conversation begins" in instructions(1))
        assertTrue("bravo1" in input(1) && "comet1" in input(1) && "alpha1" !in input(1), "only the long stretch is asked where it changes")
        assertTrue("alpha1" in input(2) && "able1" in input(2) && "bravo1" !in input(2))
        assertTrue("bravo1" in input(3) && "bingo1" in input(3) && "charlie1" !in input(3))
        assertTrue("charlie1" in input(4) && "comet1" in input(4))
        assertTrue("[00:00:30] S2: able1" in input(2), "the recording's labels")
        assertTrue("Alpha was shipped." in input(5) && "Charlie was reviewed." in input(5))

        val final = notes.get(meta.recordingId)
        assertEquals(NotesStage.FINAL, final.stage)
        assertEquals("Alpha shipped, bravo planned, charlie reviewed.", final.overview)
        assertEquals(listOf("Plan bravo", "Ship alpha"), final.decisions.map { it.text })
        assertEquals(shipped.id, final.decisions.last().id, "the provisional item the final pass made again keeps its id")
        assertFalse(final.decisions.last().provisional)
        assertEquals(listOf("Order able parts"), final.todos.map { it.text }, "the deleted item does not come back")
        assertEquals("Friday", final.todos.single().due)
        assertNull(final.todos.single().owner, "an unnamed speaker is no owner")
        assertEquals(listOf("When does charlie start?"), final.questions.map { it.text }, "the provisional question the final pass did not make is gone")

        val file = stored(meta)
        assertEquals(listOf(0.0 to 60.0, 700.0 to 2400.0, 2400.0 to 3900.0), file.conversations.map { it.startSec to it.endSec })
        assertEquals(listOf("Alpha was shipped.", "Bravo was planned.", "Charlie was reviewed."), file.conversations.map { it.summary })
        assertEquals(1, file.tombstones.size)
        assertEquals(listOf(0, 0, 1, 2), file.items.map { it.conversation }.sortedBy { it })
        assertTrue((1..5).none { f.fs.exists(f.dirOf(meta) / Notes.provisionalName(it)) }, "the parts' transcripts have served")
        val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(File("../spec/notes.schema.json").readText(), SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build())
        assertEquals(emptySet(), schema.validate(f.fs.read(notesFile(meta)) { readUtf8() }, InputFormat.JSON).map { it.message }.toSet())
        assertEquals(1, f.logger.fieldsOf("organize.final.boundaries").single()["kept"])
        val done = f.logger.fieldsOf("organize.final.done").single()
        assertEquals(4, done["items"])
        assertEquals(1, done["tombstoned"])
        assertEquals(1, done["dropped"])

        // An owner is shown once the user names the speaker.
        assertIs<EditResult.Edited>(f.core.editTranscript(meta.recordingId, TranscriptEdit.RenameSpeaker("S2", "Mina")))
        f.core.awaitPushes()
        assertEquals("Mina", notes.observe(meta.recordingId).first().todos.single().owner)
        val lines = notes.linesAround(meta.recordingId, 2400.0)
        assertEquals(listOf(2400.0), lines.map { it.atSec })
        assertEquals("Mina", lines.single().speakerName)
        assertFalse(lines.single().provisional)
    }

    @Test
    fun `a usage limit waits through the job's retries without holding up the recording, and the next try asks only what is left`() = runBlocking {
        ready()
        engine.answer = { listOf(SttSegment(0.0, 30.0, "spk-a", speech("alpha")), SttSegment(700.0, 730.0, "spk-b", speech("bravo"))) }
        answer(conversation("Alpha.", item("decision", "Ship alpha", "00:00:00", "alpha1 alpha2 alpha3")))
        usageLimit()
        val meta = f.record()
        f.core.enqueue(meta.recordingId)
        f.drain()

        val waiting = job()
        assertEquals(JobStatus.WAITING, waiting.status)
        val step = organizeStep(waiting.id)
        assertEquals(StepStatus.PENDING, step.status)
        assertEquals(CoreMessage.CHATGPT_USAGE_LIMIT, CoreMessageRef.parse(step.lastError!!)!!.message)
        assertTrue(step.nextAttemptAt!! > f.clock.now() + 4.hours, "five hours, the plan's usage window")
        assertTrue(StepReport.notesOnly(waiting.workflow, f.core.jobs.steps(waiting.id)), "the shells show the recording as done")
        assertTrue(f.core.jobs.steps(waiting.id).filter { it.stepId != "organize" }.all { it.status == StepStatus.SUCCEEDED })
        assertIs<EditResult.Edited>(f.core.editTranscript(meta.recordingId, TranscriptEdit.RenameSpeaker("S1", "Joon")), "not in the way of an edit")
        f.core.awaitPushes()
        assertEquals(NotesStage.NONE, notes.get(meta.recordingId).stage)
        assertEquals("CHATGPT_USAGE_LIMIT", f.logger.fieldsOf("organize.final.failed").single()["reason"])

        f.clock.advance(5.hours)
        chatGpt.server.reply(chatGpt.tokens("access-2", "refresh-2"), headers = mapOf("Content-Type" to "application/json"))
        answer(conversation("Bravo.", item("question", "Bravo when?", "00:11:40", "bravo1 bravo2 bravo3")))
        answer("""{"overview":"Alpha, then bravo."}""")
        f.drain()

        assertEquals(JobStatus.DONE, job().status)
        assertEquals(4, responses().size, "the conversation already done is not asked again")
        assertTrue("bravo1" in input(2) && "alpha1" !in input(2))
        val final = notes.get(meta.recordingId)
        assertEquals("Alpha, then bravo.", final.overview)
        assertEquals(listOf("Ship alpha"), final.decisions.map { it.text })
        assertEquals(listOf("Bravo when?"), final.questions.map { it.text })
    }

    @Test
    fun `without a sign-in or without consent the final step does nothing`() = runBlocking {
        engine.answer = { listOf(SttSegment(0.0, 30.0, "spk-a", speech("alpha"))) }
        val meta = f.recordAndRun()
        val done = job()
        assertEquals(JobStatus.DONE, done.status)
        val step = organizeStep(done.id)
        assertEquals(StepStatus.SUCCEEDED, step.status)
        assertEquals("skipped", step.output!!["notes"]!!.jsonPrimitive.content)
        assertEquals(0, responses().size)
        assertEquals(NotesStage.NONE, notes.get(meta.recordingId).stage)
        assertFalse(f.fs.exists(notesFile(meta)))
        assertEquals("off", f.logger.fieldsOf("organize.final.skipped").single()["reason"])
        assertEquals("", f.drive.byName(MetaWriter.baseName(meta))!!.appProperties["pending"], "other devices wait for no notes")

        ready(consent = false)
        val other = f.record(id = "01J9ABCDEF0123456789ABCDEG", startedAt = "2026-08-26T02:00:00.000Z")
        f.core.enqueue(other.recordingId)
        f.drain()
        assertEquals(0, responses().size, "signed in but not agreed")
        assertEquals(2, f.logger.fieldsOf("organize.final.skipped").size)
    }

    @Test
    fun `a sign-in OpenAI ended waits, and signing in again lets the notes go`() = runBlocking {
        ready()
        engine.answer = { listOf(SttSegment(0.0, 30.0, "spk-a", speech("alpha"))) }
        chatGpt.server.reply("{}", status = 401)
        chatGpt.server.reply(chatGpt.tokens("access-2", "refresh-2"))
        chatGpt.server.reply("{}", status = 401)
        val meta = f.record()
        f.core.enqueue(meta.recordingId)
        f.drain()

        assertIs<ChatGptConnection.Expired>(f.core.chatGpt.observe().value)
        val waiting = job()
        assertEquals(JobStatus.WAITING, waiting.status)
        assertEquals(CoreMessage.CHATGPT_SIGN_IN_REQUIRED, CoreMessageRef.parse(organizeStep(waiting.id).lastError!!)!!.message)
        assertFalse(notes.needsConsent(), "the consent stays with an ended sign-in")

        chatGpt.signIn(account = f.core.chatGpt)
        assertEquals(1, f.logger.fieldsOf("organize.resumed").single()["jobs"])
        assertEquals(JobStatus.PENDING, job().status, "goes at the next pass, not hours later")
        answer(conversation("Alpha.", item("decision", "Ship alpha", "00:00:00", "alpha1 alpha2 alpha3")))
        f.drain()
        assertEquals(JobStatus.DONE, job().status)
        assertEquals(listOf("Ship alpha"), notes.get(meta.recordingId).decisions.map { it.text })
    }

    @Test
    fun `transcribing again makes the notes again, and a deleted item stays deleted`() = runBlocking {
        ready()
        engine.answer = { listOf(SttSegment(0.0, 30.0, "spk-a", speech("alpha")), SttSegment(30.0, 60.0, "spk-b", speech("able"))) }
        answer(
            conversation(
                "Alpha.",
                item("decision", "Ship alpha", "00:00:00", "alpha1 alpha2 alpha3"),
                item("todo", "Order able parts", "00:00:30", "able1 able2 able3", owner = "S2"),
            ),
        )
        val meta = f.recordAndRun()
        val first = notes.get(meta.recordingId)
        assertEquals(NotesStage.FINAL, first.stage)
        assertTrue(notes.deleteItem(meta.recordingId, first.todos.single().id))

        answer(
            conversation(
                "Alpha again.",
                item("decision", "Ship alpha now", "00:00:00", "alpha1 alpha2 alpha3"),
                item("todo", "Order the able parts", "00:00:31", "able1 able2 able3", owner = "S2"),
                item("question", "Able price?", "00:00:40", "able20 able21 able22"),
            ),
        )
        assertIs<RetranscribeResult.Started>(f.core.retranscribe(meta.recordingId))
        f.drain()

        assertEquals(2, responses().size)
        val again = notes.get(meta.recordingId)
        assertEquals("Alpha again.", again.overview)
        assertEquals(listOf("Ship alpha now"), again.decisions.map { it.text })
        assertEquals(first.decisions.single().id, again.decisions.single().id)
        assertEquals(emptyList(), again.todos)
        assertEquals(listOf("Able price?"), again.questions.map { it.text })
        assertNotEquals(first, again)
    }

    @Test
    fun `notes waiting out a usage limit do not hold up a transcription again, which closes them`() = runBlocking {
        ready()
        engine.answer = { listOf(SttSegment(0.0, 30.0, "spk-a", speech("alpha"))) }
        usageLimit()
        val meta = f.record()
        f.core.enqueue(meta.recordingId)
        f.drain()
        val waiting = job()
        assertEquals(JobStatus.WAITING, waiting.status)
        assertTrue(StepReport.notesOnly(waiting.workflow, f.core.jobs.steps(waiting.id)))

        val store = JobStore(RecDatabase(f.driver), f.deps)
        store.updateJob(waiting.id, JobStatus.RUNNING, null, f.clock.now())
        assertEquals(RetranscribeResult.Busy, f.core.retranscribe(meta.recordingId), "notes being made right now are in the way")
        store.updateJob(waiting.id, JobStatus.WAITING, waiting.nextRunAt, f.clock.now())

        val started = assertIs<RetranscribeResult.Started>(f.core.retranscribe(meta.recordingId))
        assertEquals(JobStatus.DONE, job().status, "the old job is settled")
        val closed = organizeStep(waiting.id)
        assertEquals(StepStatus.SKIPPED, closed.status)
        assertNull(closed.nextAttemptAt)
        assertNull(closed.lastError)
        assertFalse(store.claimRunning(waiting.id, f.clock.now()), "a pass that picked it up before is refused")
        assertEquals(JobStatus.DONE, job().status)
        assertEquals(RetranscribeResult.Busy, f.core.retranscribe(meta.recordingId), "the transcription again has real work left")

        answer(conversation("Alpha again.", item("decision", "Ship alpha", "00:00:00", "alpha1 alpha2 alpha3")))
        f.drain()
        f.clock.advance(6.hours)
        f.drain()

        assertEquals(JobStatus.DONE, f.core.jobs.list().single { it.id == started.jobId }.status)
        assertEquals(StepStatus.SKIPPED, organizeStep(waiting.id).status, "the closed step never ran")
        assertEquals(2, responses().size, "the limit, then the transcription again's notes")
        assertEquals("Alpha again.", notes.get(meta.recordingId).overview)
    }

    // --- Key points, the user's own sections and what Settings add ---

    /** One conversation of two speakers, S1 then S2. */
    private val meeting = listOf(SttSegment(0.0, 30.0, "spk-a", speech("alpha")), SttSegment(30.0, 60.0, "spk-b", speech("able")))

    @Test
    fun `after Stop the notes have key points and the user's own sections`() = runBlocking {
        ready()
        val risks = notes.setPreferences(NotesPreferences(sections = listOf(NoteSection("", "Risks", "anything that could go wrong")))).sections.single()
        engine.answer = { meeting }
        answer(
            conversation(
                "Alpha.",
                item("point", "Alpha costs twice as much", "00:00:00", "alpha5 alpha6 alpha7"),
                item("section1", "Alpha may slip", "00:00:30", "able1 able2 able3"),
                item("section2", "Nobody asked for this", "00:00:30", "able5 able6 able7"),
                item("decision", "Ship alpha", "00:00:00", "alpha1 alpha2 alpha3"),
            ),
        )
        val meta = f.recordAndRun()

        val asked = instructions(0)
        assertTrue("key points, decisions, to-dos and open questions, and the items of the user's own sections" in asked, asked)
        assertTrue("\"point\" for a key point" in asked, asked)
        assertTrue("\"section1\" for the user's own section \"Risks\" (anything that could go wrong)" in asked, asked)
        val final = notes.get(meta.recordingId)
        assertEquals(listOf(NoteKind.POINT to "Alpha costs twice as much"), final.points.map { it.kind to it.text })
        assertEquals(listOf("Ship alpha"), final.decisions.map { it.text })
        val section = final.sections.single()
        assertEquals(risks.id to "Risks", section.id to section.name)
        assertEquals(listOf(Triple(NoteKind.SECTION, "Alpha may slip", risks.id)), section.items.map { Triple(it.kind, it.text, it.section) })
        assertEquals(1, f.logger.fieldsOf("organize.final.done").single()["dropped"], "a section the user does not have")
        assertEquals(listOf(StoredSection(risks.id, "Risks", "anything that could go wrong")), stored(meta).sections)

        // Renamed in Settings later, the section keeps the name its items were made under.
        notes.setPreferences(NotesPreferences(sections = listOf(risks.copy(name = "Threats"))))
        assertEquals("Risks", notes.get(meta.recordingId).sections.single().name)
    }

    @Test
    fun `the user's instructions and About you go with the request`() = runBlocking {
        ready()
        notes.setPreferences(NotesPreferences(instructions = "  Use short sentences.  "))
        f.core.summaries.setPreferences(SummaryPreferences(aboutMe = "Product manager"))
        engine.answer = { meeting }
        answer(conversation("Alpha.", item("decision", "Ship alpha", "00:00:00", "alpha1 alpha2 alpha3")))
        val meta = f.record()
        f.core.enqueue(meta.recordingId)
        f.drain()

        val asked = instructions(0)
        assertTrue(
            "The user's own instructions for their notes follow. Follow them where they do not change the JSON format, the kinds or the evidence rules:\n" +
                "Use short sentences.\n" in asked,
            asked,
        )
        assertTrue("About the person who made the recording, to judge what matters to them: Product manager\n" in asked, asked)
        assertEquals(listOf("Ship alpha"), notes.get(meta.recordingId).decisions.map { it.text })
    }

    @Test
    fun `speaker labels stay out of the words the notes are written in, and only a to-do's owner carries one`() = runBlocking {
        ready()
        engine.answer = { meeting }
        answer(conversation("Alpha.", item("todo", "Write the alpha memo", "00:00:30", "able1 able2 able3", owner = "S2")))
        f.recordAndRun()

        val rule = "Never write a speaker label such as S1 in the words you write — a summary, an overview or an item's text"
        val asked = instructions(0)
        assertTrue(rule in asked, asked)
        assertTrue("\"owner\": for a todo, the speaker label of the person who will do it" in asked, asked)
        assertTrue(rule in NotesPrompts.provisional("English"), "while recording too")
        assertTrue(rule in NotesPrompts.overview("English"), "and the overview of a recording of several conversations")
    }

    @Test
    fun `Settings keep the instructions and up to three named sections, trimmed, and give a new section its id`() = runBlocking {
        assertEquals(NotesPreferences(), notes.preferences())
        val kept = notes.setPreferences(
            NotesPreferences(
                instructions = "  " + "x".repeat(1100) + "  ",
                sections = listOf(
                    NoteSection("", "  Risks\nand issues  ", "  " + "d".repeat(250)),
                    NoteSection("01J9SECT10N000000000000000", "Hiring", "Who to hire"),
                    NoteSection("", "   ", "a section without a name is dropped"),
                    NoteSection("", "n".repeat(50), ""),
                    NoteSection("", "Fourth", "one too many"),
                ),
            ),
        )

        assertEquals("x".repeat(NotesPreferences.INSTRUCTIONS_MAX), kept.instructions)
        assertEquals(listOf("Risks and issues", "Hiring", "n".repeat(NotesPreferences.SECTION_NAME_MAX)), kept.sections.map { it.name })
        assertEquals(listOf(NotesPreferences.DESCRIPTION_MAX, 11, 0), kept.sections.map { it.description.length })
        assertEquals("01J9SECT10N000000000000000", kept.sections[1].id, "a section kept keeps its id")
        assertTrue(kept.sections[0].id.isNotBlank() && kept.sections[2].id.isNotBlank() && kept.sections[0].id != kept.sections[2].id)
        assertEquals(kept, notes.preferences())
        assertEquals(kept, notes.observePreferences().first())
        val kv = RecDatabase(f.driver).recQueries
        assertEquals(kept.instructions, kv.kvGet(Notes.PREF_INSTRUCTIONS).executeAsOneOrNull(), "kept on this device")
        assertTrue(kept.sections[0].id in kv.kvGet(Notes.PREF_SECTIONS).executeAsOne())

        assertEquals(NotesPreferences(), notes.setPreferences(NotesPreferences(instructions = "   ")))
        assertNull(kv.kvGet(Notes.PREF_INSTRUCTIONS).executeAsOneOrNull())
        assertNull(kv.kvGet(Notes.PREF_SECTIONS).executeAsOneOrNull())
    }

    @Test
    fun `the notes as text are the overview, then each group in the detail's order, under the app's headings`() {
        fun noteItem(kind: NoteKind, text: String, atSec: Double, owner: String? = null, due: String? = null, section: String? = null) =
            NoteItem("01J9N0TE000000000000000000", kind, text, owner, due, atSec, "quote", provisional = false, section = section)
        val risks = "01J9SECT10N000000000000000"
        val all = RecordingNotes(
            "01J9ABCDEF0123456789ABCDEF", NotesStage.FINAL, "We met.\nWe shipped.",
            decisions = listOf(noteItem(NoteKind.DECISION, "Ship on Friday", 1.0)),
            todos = listOf(noteItem(NoteKind.TODO, "Send the deck", 65.0, owner = "Mina", due = "Monday")),
            questions = listOf(noteItem(NoteKind.QUESTION, "Who pays?", 3723.0)),
            organizing = false,
            points = listOf(noteItem(NoteKind.POINT, "Costs doubled", 30.0)),
            sections = listOf(
                NoteSectionItems(risks, "Risks", listOf(noteItem(NoteKind.SECTION, "May slip", 40.0, section = risks))),
                NoteSectionItems("01J9SECT20N000000000000000", "Empty", emptyList()),
            ),
        )

        assertEquals(
            "We met.\nWe shipped.\n\n" +
                "Key points\n- Costs doubled [00:00:30]\n\n" +
                "Decisions\n- Ship on Friday [00:00:01]\n\n" +
                "To-dos\n- Send the deck — Mina · Monday [00:01:05]\n\n" +
                "Questions\n- Who pays? [01:02:03]\n\n" +
                "Risks\n- May slip [00:00:40]\n",
            NotesText.plain(all, NotesHeadings()),
        )
        assertEquals(
            "## Notes\n\nWe met.  \nWe shipped.\n\n" +
                "### Key points\n\n- Costs doubled [00:00:30]\n\n" +
                "### Decisions\n\n- Ship on Friday [00:00:01]\n\n" +
                "### To-dos\n\n- Send the deck — Mina · Monday [00:01:05]\n\n" +
                "### Questions\n\n- Who pays? [01:02:03]\n\n" +
                "### Risks\n\n- May slip [00:00:40]\n",
            NotesText.markdown(all, NotesHeadings()),
        )
        val korean = NotesHeadings(notes = "노트", keyPoints = "핵심", decisions = "결정", todos = "할 일", questions = "질문")
        assertTrue(NotesText.plain(all, korean)!!.startsWith("We met.\nWe shipped.\n\n핵심\n- Costs doubled"))
        assertTrue("\n\n질문\n- Who pays? [01:02:03]\n\nRisks\n" in NotesText.plain(all, korean)!!, "a section is headed by its own name")
        assertTrue(NotesText.markdown(all, korean)!!.startsWith("## 노트\n\n"))

        val decisionsOnly = RecordingNotes("01J9ABCDEF0123456789ABCDEF", NotesStage.PROVISIONAL, null, all.decisions, emptyList(), emptyList(), false)
        assertEquals("Decisions\n- Ship on Friday [00:00:01]\n", NotesText.plain(decisionsOnly, NotesHeadings()))
        val nothing = decisionsOnly.copy(decisions = emptyList(), sections = all.sections.drop(1))
        assertNull(NotesText.plain(nothing, NotesHeadings()))
        assertNull(NotesText.markdown(nothing, NotesHeadings()))
    }

    // --- Edit notes and Organize again ---

    /** A recording organized after Stop: a decision, a to-do of S2's, a question and a key point. */
    private suspend fun organized(): RecordingMeta {
        ready()
        engine.answer = { meeting }
        answer(
            conversation(
                "Alpha.",
                item("decision", "Ship alpha", "00:00:00", "alpha1 alpha2 alpha3"),
                item("todo", "Order able parts", "00:00:30", "able1 able2 able3", owner = "S2"),
                item("question", "Who owns alpha?", "00:00:05", "alpha8 alpha9 alpha10"),
                item("point", "Alpha is late", "00:00:10", "alpha15 alpha16 alpha17"),
            ),
        )
        return f.recordAndRun()
    }

    @Test
    fun `Edit notes changes an item and the overview, and an emptied item is deleted for good`() = runBlocking {
        val meta = organized()
        val first = notes.get(meta.recordingId)
        f.clock.advance(1.minutes)
        val now = f.clock.now().isoUtc()

        assertTrue(
            notes.edit(
                meta.recordingId, "  My overview  ",
                listOf(NoteEdit(first.decisions.single().id, "  Ship alpha on Friday  "), NoteEdit(first.todos.single().id, "   ")),
            ),
        )

        val edited = notes.get(meta.recordingId)
        assertEquals("My overview", edited.overview)
        assertEquals(listOf(first.decisions.single().id to "Ship alpha on Friday"), edited.decisions.map { it.id to it.text })
        assertEquals(emptyList(), edited.todos)
        assertEquals(first.questions, edited.questions, "an item not edited is as it was")
        val file = stored(meta)
        val decision = file.items.single { it.kind == NoteKind.DECISION }
        assertEquals("Ship alpha", decision.text, "the model's words stay, to know the item by")
        assertEquals("Ship alpha on Friday" to now, decision.userText to decision.editedAt)
        assertEquals(now, file.overviewEditedAt)
        assertEquals(listOf(NoteKind.TODO to "Order able parts"), file.tombstones.map { it.kind to it.text })
        assertEquals(
            mapOf<String, Any?>("recordingId" to meta.recordingId, "items" to 1, "deleted" to 1, "overview" to true),
            f.logger.fieldsOf("organize.edited").single(),
        )

        assertFalse(notes.edit(meta.recordingId, "My overview", listOf(NoteEdit(decision.id, "Ship alpha on Friday"))), "nothing changed, nothing written")
        assertFalse(notes.edit(meta.recordingId, null, listOf(NoteEdit("01J9NOSUCHITEM000000000000", "Anything"))))
        assertTrue(notes.edit(meta.recordingId, "", emptyList()))
        assertNull(notes.get(meta.recordingId).overview, "an emptied overview is removed")
        assertEquals(listOf("Ship alpha on Friday"), notes.get(meta.recordingId).decisions.map { it.text })
    }

    @Test
    fun `Organize again keeps what the user edited and deleted`() = runBlocking {
        val meta = organized()
        val first = notes.get(meta.recordingId)
        val decision = first.decisions.single()
        val question = first.questions.single()
        assertTrue(
            notes.edit(
                meta.recordingId, "My overview",
                listOf(NoteEdit(decision.id, "Ship alpha on Friday"), NoteEdit(question.id, "Who owns alpha now?"), NoteEdit(first.todos.single().id, "")),
            ),
        )
        answer(
            conversation(
                "Alpha again.",
                item("decision", "Ship alpha", "00:00:00", "alpha1 alpha2 alpha3"),
                item("todo", "Order able parts", "00:00:30", "able1 able2 able3", owner = "S2"),
                item("todo", "Book a room", "00:00:50", "able40 able41 able42", owner = "S2"),
            ),
        )

        assertNull(notes.organizeAgain(meta.recordingId))

        assertEquals(2, responses().size)
        assertTrue("organize.again.start" in f.logger.events)
        val again = notes.get(meta.recordingId)
        assertNull(again.failure)
        assertFalse(again.organizing)
        assertEquals("My overview", again.overview, "the user's overview stays theirs")
        assertEquals(listOf(decision.id to "Ship alpha on Friday"), again.decisions.map { it.id to it.text }, "made again, it keeps the user's words")
        assertEquals(listOf("Book a room"), again.todos.map { it.text }, "the deleted item stays deleted")
        assertEquals(listOf(question.id to "Who owns alpha now?"), again.questions.map { it.id to it.text }, "not made again, an edited item is kept")
        assertFalse(again.questions.single().provisional)
        assertEquals(emptyList(), again.points, "an item the user left alone goes with the pass that made it")
        assertEquals(listOf("Alpha again."), stored(meta).conversations.map { it.summary })
    }

    @Test
    fun `an edited item passes its words only to one that says the same, and words put back make it the model's again`() = runBlocking {
        val meta = organized()
        val first = notes.get(meta.recordingId)
        val decision = first.decisions.single()
        val question = first.questions.single()
        assertTrue(notes.edit(meta.recordingId, null, listOf(NoteEdit(decision.id, "Ship alpha on Friday"), NoteEdit(question.id, "Who owns alpha now?"))))
        assertTrue(notes.edit(meta.recordingId, null, listOf(NoteEdit(question.id, question.text))))
        val reverted = stored(meta).items.single { it.id == question.id }
        assertEquals(null to null, reverted.userText to reverted.editedAt, "the model's own words again: not the user's any more")
        answer(
            conversation(
                "Alpha again.",
                item("decision", "Cancel the beta launch", "00:00:00", "alpha1 alpha2 alpha3"),
            ),
        )

        assertNull(notes.organizeAgain(meta.recordingId))

        val again = notes.get(meta.recordingId)
        assertEquals(
            setOf(decision.id to "Ship alpha on Friday", "Cancel the beta launch"),
            again.decisions.map { if (it.id == decision.id) it.id to it.text else it.text }.toSet(),
            "a different decision at the same moment shows its own words, beside the user's",
        )
        assertEquals(emptyList(), again.questions, "a put-back item goes with the pass that made it, like one never edited")
    }

    @Test
    fun `an item the user edited while recording is theirs through the final pass`() = runBlocking {
        ready()
        val meta = startRecording()
        engine.answer = { listOf(SttSegment(0.0, 30.0, null, speech("alpha"))) }
        answer(items(item("todo", "Book the alpha room", "00:00:10", "alpha10 alpha11 alpha12")))
        file(meta, 1)
        val todo = notes.get(meta.recordingId).todos.single()
        assertTrue(notes.edit(meta.recordingId, null, listOf(NoteEdit(todo.id, "Book the bravo room"))))
        assertEquals(listOf("Book the bravo room"), notes.get(meta.recordingId).todos.map { it.text })
        assertEquals(NotesStage.PROVISIONAL, notes.get(meta.recordingId).stage)

        engine.answer = { listOf(SttSegment(0.0, 30.0, "spk-a", speech("alpha"))) }
        answer(conversation("Alpha.", item("decision", "Ship alpha", "00:00:00", "alpha1 alpha2 alpha3")))
        stop(meta, 1)
        f.drain(6)

        val final = notes.get(meta.recordingId)
        assertEquals(NotesStage.FINAL, final.stage)
        assertEquals(listOf("Ship alpha"), final.decisions.map { it.text })
        assertEquals(listOf(todo.id to "Book the bravo room"), final.todos.map { it.id to it.text }, "not made again, and kept")
        assertFalse(final.todos.single().provisional)
    }

    @Test
    fun `Organize again needs the sign-in but not the consent to organizing, and its failure shows until the next try`() = runBlocking {
        engine.answer = { meeting }
        val meta = f.recordAndRun()
        val signIn = CoreMessage.CHATGPT_SIGN_IN_REQUIRED.code()

        assertEquals(signIn, notes.organizeAgain(meta.recordingId))

        assertEquals(signIn, notes.get(meta.recordingId).failure)
        assertEquals(signIn, notes.observe(meta.recordingId).first().failure)
        assertEquals(NotesStage.NONE, notes.get(meta.recordingId).stage)
        assertFalse(notes.get(meta.recordingId).organizing)
        assertEquals(0, responses().size)
        assertEquals("CHATGPT_SIGN_IN_REQUIRED", f.logger.fieldsOf("organize.again.failed").single()["reason"])

        ready(consent = false)
        assertTrue(notes.needsConsent())
        answer(conversation("Alpha.", item("decision", "Ship alpha", "00:00:00", "alpha1 alpha2 alpha3")))
        assertNull(notes.organizeAgain(meta.recordingId))
        val again = notes.observe(meta.recordingId).first()
        assertNull(again.failure, "the next try clears it")
        assertEquals(NotesStage.FINAL, again.stage)
        assertEquals(listOf("Ship alpha"), again.decisions.map { it.text })
        assertTrue(notes.needsConsent(), "asking once is not agreeing to organize every recording")
    }

    @Test
    fun `only final notes go up to the recording's folder, not the provisional ones`() = runBlocking {
        ready()
        val meta = startRecording()
        engine.answer = { listOf(SttSegment(0.0, 30.0, null, speech("alpha"))) }
        answer(items(item("decision", "Ship alpha", "00:00:00", "alpha1 alpha2 alpha3 alpha4")))
        file(meta, 1)
        assertTrue(notes.edit(meta.recordingId, null, listOf(NoteEdit(notes.get(meta.recordingId).decisions.single().id, "Ship alpha now"))))
        f.core.awaitPushes()
        assertEquals(emptyMap(), f.core.recordings.pendingNotes(), "provisional notes, edited or not, stay on this device")

        engine.answer = { listOf(SttSegment(0.0, 30.0, "spk-a", speech("alpha"))) }
        answer(conversation("Alpha.", item("decision", "Ship alpha", "00:00:00", "alpha1 alpha2 alpha3 alpha4")))
        stop(meta, 1)
        f.drain(6)
        f.core.awaitPushes()

        fun inFolder() = NotesFolder.decode(f.drive.byName(NotesFolder.name(MetaWriter.baseName(meta)))!!.content.decodeToString())
        assertEquals(NotesStage.FINAL, notes.get(meta.recordingId).stage)
        assertEquals(stored(meta), inFolder())
        assertEquals(1, f.logger.events.count { it == "remote.notes.pushed" })
        assertEquals(emptyMap(), f.core.recordings.pendingNotes())

        f.clock.advance(1.minutes)
        assertTrue(notes.edit(meta.recordingId, "My overview", emptyList()))
        f.core.awaitPushes()
        assertEquals("My overview", inFolder()?.overview, "an edit of final notes goes up too")
        assertEquals(2, f.logger.events.count { it == "remote.notes.pushed" })
    }
}
