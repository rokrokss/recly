@file:OptIn(ExperimentalTime::class)

package recly.core.notes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import recly.core.chatgpt.ChatGptHarness
import recly.core.model.RecordingMeta
import recly.core.model.Track
import recly.core.model.isoUtc
import recly.core.model.recJson
import recly.core.recording.MetaWriter
import recly.core.testing.CoreFixture
import recly.core.testing.FakeLocalEngine
import recly.core.testing.testMeta
import recly.core.transcribe.EditResult
import recly.core.transcribe.SttSegment
import recly.core.transcribe.TranscribeRunner
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptEdit
import recly.core.transcribe.TranscriptProvider
import recly.core.transcribe.TranscriptSpeaker

/** docs/08 "To-dos": a to-do's check, and every recording's to-dos in one place. */
class TodosTest {
    private val chatGpt = ChatGptHarness()
    private val engine = FakeLocalEngine(
        supportsDiarization = true,
        answer = { listOf(SttSegment(0.0, 30.0, "spk-a", speech("alpha")), SttSegment(30.0, 60.0, "spk-b", speech("able"))) },
    )
    private val f = CoreFixture(engine = engine, chatGpt = chatGpt.server.transport(chatGpt.fs))
    private val notes get() = f.core.notes

    private fun speech(word: String) = (1..45).joinToString(" ") { "$word$it" }

    private fun answer(json: String) {
        chatGpt.server.reply(
            "data: {\"type\":\"response.output_text.delta\",\"delta\":" + Json.encodeToString(String.serializer(), json) +
                "}\n\ndata: {\"type\":\"response.completed\",\"response\":{}}\n\n",
            headers = mapOf("Content-Type" to "text/event-stream"),
        )
    }

    private fun todo(text: String, at: String, quote: String) =
        """{"kind":"todo","text":"$text","owner":"S2","at":"$at","quote":"$quote"}"""

    private val decision = """{"kind":"decision","text":"Ship alpha","at":"00:00:00","quote":"alpha1 alpha2 alpha3"}"""
    private val parts = todo("Order able parts", "00:00:30", "able1 able2 able3")
    private val room = todo("Book a room", "00:00:50", "able40 able41 able42")

    /** A recording organized after Stop, with a decision and two to-dos. */
    private suspend fun organized(): RecordingMeta {
        chatGpt.signIn(account = f.core.chatGpt)
        notes.consent()
        chatGpt.answerFills()
        answer("""{"summary":"Alpha.","items":[$decision,$parts,$room]}""")
        return f.recordAndRun().also { f.core.awaitPushes() }
    }

    private fun stored(meta: RecordingMeta): NotesFile =
        recJson.decodeFromString(NotesFile.serializer(), f.fs.read(f.dirOf(meta) / NotesFile.name(MetaWriter.baseName(meta))) { readUtf8() })

    private fun inFolder(meta: RecordingMeta): NotesFile? =
        f.drive.byName(NotesFolder.name(MetaWriter.baseName(meta)))?.content?.decodeToString()?.let(NotesFolder::decode)

    private fun RecordingNotes.todo(text: String): NoteItem = todos.single { it.text == text }

    /** A recording with no job behind it: its notes file, and its transcript when it has [speakers]. */
    private suspend fun seeded(id: String, startedAt: String, title: String?, items: List<StoredNote>, speakers: List<TranscriptSpeaker> = emptyList()) {
        val meta = testMeta(recordingId = id, startedAt = startedAt, title = title)
        f.core.recordings.create(meta, f.dirOf(meta))
        val base = MetaWriter.baseName(meta)
        val file = NotesFile(recordingId = id, stage = NotesFile.FINAL, items = items, createdAt = startedAt, updatedAt = startedAt, finalizedAt = startedAt)
        f.core.recordings.writeFile(id, NotesFile.name(base), recJson.encodeToString(NotesFile.serializer(), file))
        if (speakers.isNotEmpty()) {
            val transcript = Transcript(
                recordingId = id, track = Track.MONO, language = "en", provider = TranscriptProvider("fake"),
                createdAt = startedAt, durationSec = 10.0, speakers = speakers, segments = emptyList(),
            )
            f.core.recordings.writeFile(id, TranscribeRunner.jsonFileName(base), recJson.encodeToString(Transcript.serializer(), transcript))
        }
    }

    private fun note(id: String, kind: NoteKind, text: String, at: Double, owner: String? = null, provisional: Boolean = false, doneAt: String? = null) =
        StoredNote(id, kind, text, owner = owner, evidence = NoteEvidence(at, "quote"), provisional = provisional, createdAt = START, doneAt = doneAt)

    @Test
    fun `a check is kept in the notes, goes to the folder, and comes off again`() = runBlocking {
        val meta = organized()
        val item = notes.get(meta.recordingId).todo("Order able parts")
        assertFalse(item.done)
        assertNull(stored(meta).items.single { it.id == item.id }.doneAt)

        f.clock.advance(1.minutes)
        val now = f.clock.now().isoUtc()
        assertTrue(notes.setDone(meta.recordingId, item.id, true))
        f.core.awaitPushes()

        val checked = notes.get(meta.recordingId)
        assertTrue(checked.todo("Order able parts").done)
        assertFalse(checked.todo("Book a room").done, "only that one")
        assertEquals(now, stored(meta).items.single { it.id == item.id }.doneAt)
        assertEquals(stored(meta), inFolder(meta), "the other devices get it with the notes")
        assertEquals(mapOf<String, Any?>("recordingId" to meta.recordingId, "done" to true), f.logger.fieldsOf("organize.item.done").single())

        assertFalse(notes.setDone(meta.recordingId, item.id, true), "already so: nothing written")
        assertEquals(1, f.logger.fieldsOf("organize.item.done").size)

        assertTrue(notes.setDone(meta.recordingId, item.id, false))
        assertFalse(notes.get(meta.recordingId).todo("Order able parts").done)
        assertNull(stored(meta).items.single { it.id == item.id }.doneAt)
    }

    @Test
    fun `only a to-do of the notes can be checked`() = runBlocking {
        val meta = organized()
        val first = notes.get(meta.recordingId)

        assertFalse(notes.setDone(meta.recordingId, first.decisions.single().id, true))
        assertFalse(notes.setDone(meta.recordingId, "01J9NOSUCHITEM000000000000", true))
        assertFalse(notes.setDone("01J9NOSUCHRECORDING0000000", first.todos.first().id, true))
        assertEquals(first, notes.get(meta.recordingId))
    }

    @Test
    fun `Summarize again keeps the check on the same item, whoever edited it, and a new item comes open`() = runBlocking {
        val meta = organized()
        val first = notes.get(meta.recordingId)
        val orderId = first.todo("Order able parts").id
        val roomId = first.todo("Book a room").id
        assertTrue(notes.setDone(meta.recordingId, orderId, true))
        assertTrue(notes.setDone(meta.recordingId, roomId, true))
        assertTrue(notes.edit(meta.recordingId, null, listOf(NoteEdit(roomId, "Book the big room"))))

        // The new one comes first, and within 15 seconds of the checked one: it must not take the check.
        answer("""{"summary":"Alpha again.","items":[${todo("Call the vendor", "00:00:40", "able20 able21 able22")},$parts]}""")
        assertNull(notes.organizeAgain(meta.recordingId))

        val again = notes.get(meta.recordingId)
        assertEquals(orderId, again.todo("Order able parts").id)
        assertTrue(again.todo("Order able parts").done, "made again, it is the same item")
        assertTrue(again.todo("Book the big room").done, "the user's edit stays, with its check")
        assertFalse(again.todo("Call the vendor").done)
    }

    @Test
    fun `a checked to-do made again in other words comes open, rather than a different one taking its check`() = runBlocking {
        val meta = organized()
        assertTrue(notes.setDone(meta.recordingId, notes.get(meta.recordingId).todo("Order able parts").id, true))

        answer("""{"summary":"Alpha again.","items":[${todo("Ring the vendor about pricing", "00:00:31", "able1 able2 able3")}]}""")
        assertNull(notes.organizeAgain(meta.recordingId))

        assertEquals(listOf("Ring the vendor about pricing" to false), notes.get(meta.recordingId).todos.map { it.text to it.done })
    }

    @Test
    fun `the notes as text show each to-do's box, and nothing else gets one`() = runBlocking {
        val meta = organized()
        val first = notes.get(meta.recordingId)
        assertTrue(notes.setDone(meta.recordingId, first.todo("Order able parts").id, true))

        val text = notes.text(meta.recordingId)!!
        assertTrue("- [x] Order able parts [00:00:30]" in text, text)
        assertTrue("- [ ] Book a room [00:00:50]" in text, text)
        assertTrue("- Ship alpha [00:00:00]" in text, text)
        val markdown = notes.markdown(meta.recordingId)!!
        assertTrue("- [x] Order able parts [00:00:30]\n" in markdown, markdown)
        assertTrue("- [ ] Book a room [00:00:50]\n" in markdown, markdown)
    }

    @Test
    fun `every recording's to-dos, newest recording first, each in its detail's order`() = runBlocking {
        seeded(
            OLD, "2026-08-25T01:00:00.000Z", "Old",
            listOf(
                note("01J9N0TE000000000000000001", NoteKind.TODO, "Early", 10.0, owner = "S1", doneAt = START),
                note("01J9N0TE000000000000000002", NoteKind.TODO, "Late", 90.0, owner = "S2"),
                note("01J9N0TE000000000000000003", NoteKind.DECISION, "A decision", 5.0),
            ),
            speakers = listOf(TranscriptSpeaker("S1", "Mina"), TranscriptSpeaker("S2", me = true)),
        )
        seeded(NEW, "2026-08-27T01:00:00.000Z", null, listOf(note("01J9N0TE000000000000000004", NoteKind.TODO, "Part's own", 1.0, owner = "S1", provisional = true)))
        seeded(NONE, "2026-08-26T01:00:00.000Z", "No to-dos", listOf(note("01J9N0TE000000000000000005", NoteKind.DECISION, "Only a decision", 1.0)))

        val all = notes.todos()

        assertEquals(listOf(NEW, OLD), all.map { it.recordingId }, "a recording with no to-do is not there")
        assertEquals(RecordingTodos(NEW, null, "2026-08-27T01:00:00.000Z", all[0].items), all[0])
        assertEquals(listOf("Part's own" to null), all[0].items.map { it.text to it.owner }, "a part's own label names nobody")
        assertEquals("Old", all[1].title)
        assertEquals(listOf(Triple("Late", "Me", false), Triple("Early", "Mina", true)), all[1].items.map { Triple(it.text, it.owner, it.done) })
    }

    @Test
    fun `only what is on this device, since a recording's folder is asked when it is opened`() = runBlocking {
        val meta = organized()
        val notesFile = f.dirOf(meta) / NotesFile.name(MetaWriter.baseName(meta))
        f.fs.delete(notesFile)
        val requests = f.drive.requests.size

        assertEquals(emptyList(), notes.todos())
        assertEquals(requests, f.drive.requests.size)

        assertEquals(2, notes.get(meta.recordingId).todos.size)
        assertEquals(listOf(meta.recordingId), notes.todos().map { it.recordingId })
    }

    @Test
    fun `a notes file that cannot be read costs only its recording`() = runBlocking {
        seeded(OLD, "2026-08-25T01:00:00.000Z", "Old", listOf(note("01J9N0TE000000000000000001", NoteKind.TODO, "Kept", 1.0)))
        seeded(NEW, "2026-08-27T01:00:00.000Z", "New", listOf(note("01J9N0TE000000000000000002", NoteKind.TODO, "Gone for now", 1.0)))
        assertEquals(listOf(NEW, OLD), notes.todos().map { it.recordingId })

        val meta = f.core.recordings.get(NEW)!!.meta
        val file = f.dirOf(meta) / NotesFile.name(MetaWriter.baseName(meta))
        f.fs.delete(file)
        f.fs.createDirectory(file)

        assertEquals(listOf(NEW, OLD), notes.todos().map { it.recordingId }, "what was read before stays until it can be read again")
    }

    @Test
    fun `the collection follows a check, a named speaker, the word for me, a deletion and a new recording`() = runBlocking {
        val meta = organized()
        val seen = Channel<List<RecordingTodos>>(Channel.UNLIMITED)
        val watching = launch(Dispatchers.Default) { notes.observeTodos().collect { seen.send(it) } }
        suspend fun until(what: String, ok: (List<RecordingTodos>) -> Boolean) = withTimeout(10_000) {
            var last = seen.receive()
            while (!ok(last)) last = seen.receive()
            assertTrue(ok(last), what)
        }
        fun List<RecordingTodos>.item(text: String) = single { it.recordingId == meta.recordingId }.items.single { it.text == text }

        until("first") { it.isNotEmpty() && it.item("Order able parts").owner == null }

        val id = notes.get(meta.recordingId).todo("Order able parts").id
        assertTrue(notes.setDone(meta.recordingId, id, true))
        until("checked") { it.item("Order able parts").done }

        assertIs<EditResult.Edited>(f.core.editTranscript(meta.recordingId, TranscriptEdit.RenameSpeaker("S2", "Mina")))
        until("named") { it.item("Order able parts").owner == "Mina" }

        assertTrue(notes.deleteItem(meta.recordingId, id))
        until("deleted") { list -> list.single().items.none { it.id == id } }

        seeded(OLD, "2026-08-25T01:00:00.000Z", "Old", listOf(note("01J9N0TE000000000000000001", NoteKind.TODO, "Theirs", 1.0)))
        until("a new row") { list -> list.any { it.recordingId == OLD } }

        watching.cancel()
    }

    @Test
    fun `the view keeps what was just checked where it is, and the rest of what is done apart`() {
        fun item(id: String, done: Boolean) = NoteItem(id, NoteKind.TODO, id, null, null, 0.0, "quote", provisional = false, done = done)
        val all = listOf(
            RecordingTodos(NEW, null, START, listOf(item("a", false), item("b", true))),
            RecordingTodos(OLD, "Old", START, listOf(item("c", true), item("d", true))),
        )

        val nothingHeld = TodoGroups(all, emptySet())
        assertEquals(listOf(NEW to listOf("a")), nothingHeld.open.map { it.recordingId to it.items.map(NoteItem::id) })
        assertEquals(listOf(NEW to listOf("b"), OLD to listOf("c", "d")), nothingHeld.done.map { it.recordingId to it.items.map(NoteItem::id) })
        assertEquals(3, nothingHeld.doneCount)

        val held = TodoGroups(all, setOf("c"))
        assertEquals(listOf(NEW to listOf("a"), OLD to listOf("c")), held.open.map { it.recordingId to it.items.map(NoteItem::id) })
        assertEquals(2, held.doneCount)
    }

    private companion object {
        const val START = "2026-08-26T01:00:00.000Z"
        const val OLD = "01J9OLD0000000000000000000"
        const val NEW = "01J9NEW0000000000000000000"
        const val NONE = "01J9NONE000000000000000000"
    }
}
