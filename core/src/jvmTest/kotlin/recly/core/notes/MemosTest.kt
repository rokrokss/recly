package recly.core.notes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import recly.core.chatgpt.AskState
import recly.core.chatgpt.ChatGptHarness
import recly.core.chatgpt.Summaries
import recly.core.job.JobStatus
import recly.core.model.RecordingMeta
import recly.core.recording.MetaWriter
import recly.core.testing.CoreFixture
import recly.core.testing.FakeLocalEngine
import recly.core.testing.SEEDED_AUDIO
import recly.core.testing.SEEDED_AUDIO_SHA256
import recly.core.testing.testMeta
import recly.core.testing.testPart
import recly.core.transcribe.SttSegment

/** docs/08 "Memo": what is kept, and every request to ChatGPT that carries it. */
class MemosTest {
    private val chatGpt = ChatGptHarness()
    private val engine = FakeLocalEngine(supportsDiarization = true)
    private val f = CoreFixture(engine = engine, chatGpt = chatGpt.server.transport(chatGpt.fs))
    private val memos get() = f.core.memos
    private val notes get() = f.core.notes

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

    private fun item(kind: String, text: String, at: String, quote: String): String =
        """{"kind":"$kind","text":"$text","owner":null,"due":null,"at":"$at","quote":"$quote"}"""

    private fun conversation(summary: String, vararg items: String) = """{"summary":"$summary","items":[${items.joinToString(",")}]}"""

    private fun responses() = chatGpt.server.requests.filter { it.url.endsWith("/responses") && !chatGpt.isFill(it) }

    private fun text(request: recly.core.transcribe.Recorded): String =
        request.json()["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content

    private fun input(index: Int): String = text(responses()[index])

    private fun speech(word: String, n: Int = 45) = (1..n).joinToString(" ") { "$word$it" }

    /** Two conversations, a long silence between them: two requests and an overview after Stop. */
    private val twoConversations = listOf(SttSegment(0.0, 30.0, "spk-a", speech("alpha")), SttSegment(700.0, 730.0, "spk-b", speech("bravo")))

    private fun memoOf(meta: RecordingMeta): MemoFile? =
        (f.dirOf(meta) / MemoFile.name(MetaWriter.baseName(meta))).takeIf(f.fs::exists)?.let { path -> MemoFolder.decode(f.fs.read(path) { readUtf8() }) }

    private fun block(memo: String) = NotesPrompts.MEMO + "\n" + memo + "\n\n"

    @Test
    fun `a memo is kept as typed, at most 2000 characters, and an emptied one stays as an empty text`() = runBlocking {
        val meta = f.record()
        assertEquals("", memos.get(meta.recordingId))
        assertNull(memoOf(meta))

        val typed = "  Agenda:\n1. launch date  \n"
        assertTrue(memos.set(meta.recordingId, typed))
        assertEquals(typed, memos.get(meta.recordingId))
        assertEquals(typed, memos.observe(meta.recordingId).first())
        assertEquals(MemoFile(recordingId = meta.recordingId, text = typed, updatedAt = memoOf(meta)!!.updatedAt), memoOf(meta))
        assertFalse(memos.set(meta.recordingId, typed), "nothing changed, nothing written")
        assertEquals(listOf(mapOf<String, Any?>("recordingId" to meta.recordingId, "chars" to typed.length)), f.logger.fieldsOf("memo.saved"))

        // 2002 characters, the 2000th the first half of an emoji: the emoji goes whole.
        assertTrue(memos.set(meta.recordingId, "x".repeat(1999) + "😀" + "y"))
        assertEquals("x".repeat(1999), memos.get(meta.recordingId))
        assertTrue(memos.set(meta.recordingId, "z".repeat(2500)))
        assertEquals(Memos.MAX, memos.get(meta.recordingId).length)

        assertTrue(memos.set(meta.recordingId, ""))
        assertEquals("", memos.get(meta.recordingId))
        assertEquals("", memoOf(meta)!!.text, "emptied, it stays as an empty text for the other devices")
        assertFalse(memos.set("01J9N0SUCHRECORD1NG0000000", "x"))
    }

    @Test
    fun `a memo typed while recording goes with each part, its numbers hidden, and makes no item of its own`() = runBlocking {
        ready()
        engine.answer = { listOf(SttSegment(0.0, 30.0, null, speech("alpha"))) }
        answer(
            """{"items":[${item("decision", "Use alpha", "00:00:00", "alpha3 alpha4 alpha5")},""" +
                "${item("todo", "Call Minsu at [phone#1]", "00:00:00", "call Minsu at [phone#1]")}]}",
        )
        val meta = testMeta(parts = emptyList())
        f.core.recordings.create(meta, f.dirOf(meta))
        assertTrue(memos.set(meta.recordingId, "  With Minsu, 010-1234-5678\n"), "while it records")

        val part = testPart(meta, 1).copy(bytes = SEEDED_AUDIO.length.toLong(), sha256 = SEEDED_AUDIO_SHA256)
        f.fs.write(f.dirOf(meta) / part.file) { writeUtf8(SEEDED_AUDIO) }
        f.core.recordings.addPart(meta.recordingId, part)
        notes.awaitWork()

        val sent = input(0)
        assertTrue(sent.startsWith(block("With Minsu, [phone#1]") + NotesPrompts.SO_FAR), sent)
        assertFalse("010-1234-5678" in sent)
        assertEquals(listOf("Use alpha"), notes.get(meta.recordingId).decisions.map { it.text })
        assertEquals(emptyList(), notes.get(meta.recordingId).todos, "what only the memo says needs a line of the transcript")
    }

    @Test
    fun `after Stop the memo goes with each conversation, the overview, the fill and Organize again, and not when blank`() = runBlocking {
        ready()
        engine.answer = { twoConversations }
        answer(conversation("Alpha.", item("decision", "Ship alpha", "00:00:00", "alpha1 alpha2 alpha3")))
        answer(conversation("Bravo."))
        answer("""{"overview":"Alpha, then bravo."}""")
        val meta = f.record()
        memos.set(meta.recordingId, "Agenda: alpha, bravo")
        f.core.enqueue(meta.recordingId)
        f.drain()

        assertEquals(JobStatus.DONE, f.core.jobs.list().single().status)
        assertEquals(3, responses().size)
        assertTrue(input(0).startsWith(block("Agenda: alpha, bravo") + NotesPrompts.TRANSCRIPT) && "alpha1" in input(0), input(0))
        assertTrue(input(1).startsWith(block("Agenda: alpha, bravo") + NotesPrompts.TRANSCRIPT) && "bravo1" in input(1), input(1))
        assertTrue(input(2).startsWith(block("Agenda: alpha, bravo") + NotesPrompts.CONVERSATIONS), input(2))
        val fillRequest = chatGpt.server.requests.first(chatGpt::isFill)
        val fill = text(fillRequest)
        assertTrue(fill.endsWith("\n" + block("Agenda: alpha, bravo")), fill)
        assertTrue("A name in the user's memo may be one of the speakers'" in fillRequest.json()["instructions"]!!.jsonPrimitive.content)
        assertFalse("user's memo" in Summaries.fillInstructions("English", title = true, speakers = listOf("S1")), "not without a memo")

        // Changed after the notes were written: nothing runs until Organize again, which reads it as it is then.
        memos.set(meta.recordingId, "  ")
        assertEquals(3, responses().size)
        answer(conversation("Alpha."))
        answer(conversation("Bravo."))
        answer("""{"overview":"Alpha, then bravo."}""")
        assertNull(notes.organizeAgain(meta.recordingId))
        assertEquals(6, responses().size)
        (3..5).forEach { assertFalse(NotesPrompts.MEMO in input(it), "a blank memo is not sent") }
    }

    @Test
    fun `a try after a usage limit asks again from the start when the memo changed meanwhile`() = runBlocking {
        ready()
        engine.answer = { twoConversations }
        answer(conversation("Alpha.", item("decision", "Ship alpha", "00:00:00", "alpha1 alpha2 alpha3")))
        chatGpt.server.reply("""{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}""", status = 429)
        val meta = f.record()
        memos.set(meta.recordingId, "first")
        f.core.enqueue(meta.recordingId)
        f.drain()
        assertEquals(JobStatus.WAITING, f.core.jobs.list().single().status)

        memos.set(meta.recordingId, "second")
        f.clock.advance(5.hours)
        chatGpt.server.reply(chatGpt.tokens("access-2", "refresh-2"), headers = mapOf("Content-Type" to "application/json"))
        answer(conversation("Alpha.", item("decision", "Ship alpha", "00:00:00", "alpha1 alpha2 alpha3")))
        answer(conversation("Bravo."))
        answer("""{"overview":"Alpha, then bravo."}""")
        f.drain()

        assertEquals(JobStatus.DONE, f.core.jobs.list().single().status)
        assertEquals(5, responses().size, "the conversation already done is asked again, with the memo as it is now")
        assertTrue(input(2).startsWith(block("second")) && "alpha1" in input(2), input(2))
        assertTrue(input(4).startsWith(block("second") + NotesPrompts.CONVERSATIONS), input(4))
    }

    @Test
    fun `Ask carries the memo between the transcript and the question`() = runBlocking {
        val meta = f.recordAndRun()
        ready(consent = false)
        memos.set(meta.recordingId, "Who owns pricing?\n")
        answer("Mina does.")

        assertIs<AskState.Ready>(f.core.summaries.ask(meta.recordingId, null, "Who owns it?"))
        val sent = input(0)
        assertTrue(sent.endsWith("\n" + block("Who owns pricing?") + Summaries.QUESTION_HEADING + "\nWho owns it?\n"), sent)
    }
}
