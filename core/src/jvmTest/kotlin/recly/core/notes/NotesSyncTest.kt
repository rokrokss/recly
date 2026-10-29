package recly.core.notes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import recly.core.chatgpt.ChatGptHarness
import recly.core.model.RecordingMeta
import recly.core.model.recJson
import recly.core.recording.MetaWriter
import recly.core.testing.CoreFixture
import recly.core.testing.FakeLocalEngine
import recly.core.transcribe.SttSegment

/** docs/08 "Organizing" — "Storage": final notes go to the recording's folder, and come back from it on the other devices. */
class NotesSyncTest {
    private val chatGpt = ChatGptHarness()
    private val engine = FakeLocalEngine(supportsDiarization = true, answer = { listOf(SttSegment(0.0, 30.0, "spk-a", (1..45).joinToString(" ") { "alpha$it" })) })
    private val f = CoreFixture(engine = engine, chatGpt = chatGpt.server.transport(chatGpt.fs))
    private val notes get() = f.core.notes

    /** A recording of this device, organized after Stop by a signed-in, consenting user. */
    private suspend fun organized(): RecordingMeta {
        chatGpt.signIn(account = f.core.chatGpt)
        notes.consent()
        chatGpt.answerFills()
        val answer = """{"summary":"Alpha.","items":[{"kind":"decision","text":"Ship alpha","at":"00:00:00","quote":"alpha1 alpha2 alpha3"}]}"""
        chatGpt.server.reply(
            "data: {\"type\":\"response.output_text.delta\",\"delta\":" + Json.encodeToString(String.serializer(), answer) +
                "}\n\ndata: {\"type\":\"response.completed\",\"response\":{}}\n\n",
            headers = mapOf("Content-Type" to "text/event-stream"),
        )
        return f.recordAndRun().also { f.core.awaitPushes() }
    }

    private fun base(meta: RecordingMeta) = MetaWriter.baseName(meta)

    private fun stored(meta: RecordingMeta): NotesFile =
        recJson.decodeFromString(NotesFile.serializer(), f.fs.read(f.dirOf(meta) / NotesFile.name(base(meta))) { readUtf8() })

    private fun inFolder(meta: RecordingMeta): NotesFile? =
        f.drive.byName(NotesFolder.name(base(meta)))?.content?.decodeToString()?.let(NotesFolder::decode)

    private suspend fun folderOf(meta: RecordingMeta): String = f.core.recordings.get(meta.recordingId)!!.driveFolderId!!

    private fun json(notes: NotesFile) = recJson.encodeToString(NotesFile.serializer(), notes).encodeToByteArray()

    /** Final notes another device wrote at [at]: [overview] and one decision. */
    private fun theirs(recordingId: String, overview: String, at: String = LATER) = NotesFile(
        recordingId = recordingId, stage = NotesFile.FINAL, overview = overview,
        items = listOf(
            StoredNote("01J9N0TE000000000000000001", NoteKind.DECISION, "Ship bravo", evidence = NoteEvidence(0.0, "alpha1 alpha2"), provisional = false, createdAt = at),
        ),
        createdAt = at, updatedAt = at, finalizedAt = at,
    )

    @Test
    fun `final notes made here go up to the recording's folder with their version`() = runBlocking {
        val meta = organized()
        val folderId = folderOf(meta)
        val here = stored(meta)

        assertEquals(NotesStage.FINAL, notes.get(meta.recordingId).stage)
        assertEquals(here, inFolder(meta))
        assertEquals(folderId, f.drive.byName(NotesFolder.name(base(meta)))!!.parents.single())
        assertEquals(here.updatedAt, f.drive.files.getValue(folderId).appProperties[NotesFolder.STAMP])
        assertTrue(f.core.recordings.pendingNotes().isEmpty())

        f.clock.advance(1.minutes)
        assertTrue(notes.edit(meta.recordingId, "My overview", emptyList()))
        f.core.awaitPushes()
        val edited = stored(meta)
        assertNotEquals(here.updatedAt, edited.updatedAt)
        assertEquals(edited, inFolder(meta), "an edit replaces it in the folder")
        assertEquals(edited.updatedAt, f.drive.files.getValue(folderId).appProperties[NotesFolder.STAMP])
    }

    @Test
    fun `notes organized again or edited on another device are read at the next pull`() = runBlocking {
        val meta = organized()
        val folderId = folderOf(meta)
        val fileId = f.drive.idOf(NotesFolder.name(base(meta)))!!
        val overviews = Channel<String?>(Channel.UNLIMITED)
        val watching = launch(Dispatchers.Default) { notes.observe(meta.recordingId).collect { overviews.send(it.overview) } }
        assertEquals("Alpha.", withTimeout(10_000) { overviews.receive() })

        f.drive.overwrite(fileId, json(theirs(meta.recordingId, "Theirs")))
        f.drive.files.getValue(folderId).appProperties += (NotesFolder.STAMP to LATER)
        f.core.pullRemoteRecordings(force = true)

        withTimeout(10_000) { while (overviews.receive() != "Theirs") Unit }
        watching.cancel()
        assertEquals(listOf("Ship bravo"), notes.get(meta.recordingId).decisions.map { it.text })
        assertTrue("remote.notes.read" in f.logger.events)
        assertTrue(f.core.recordings.pendingNotes().isEmpty(), "a copy from the folder does not go back up")

        fun reads() = f.drive.requests.count { it.query["alt"] == "media" && it.path.endsWith(fileId) }
        val before = reads()
        f.clock.advance(10.minutes)
        f.core.pullRemoteRecordings(force = true)
        assertEquals(before, reads(), "a version already taken in is not read again")
    }

    @Test
    fun `notes edited here that have not gone up yet are kept over the folder's`() = runBlocking {
        val meta = organized()
        val folderId = folderOf(meta)
        // Another device organizes again, later than anything here; this one edits too, offline.
        f.drive.overwrite(f.drive.idOf(NotesFolder.name(base(meta)))!!, json(theirs(meta.recordingId, "Theirs")))
        f.drive.files.getValue(folderId).appProperties += (NotesFolder.STAMP to LATER)
        f.drive.failNext(500, times = 10) { it.query["uploadType"] == "multipart" || it.method == "PATCH" }
        f.clock.advance(20.minutes)
        assertTrue(notes.edit(meta.recordingId, "Mine", emptyList()))
        f.core.awaitPushes()
        assertEquals(setOf(meta.recordingId), f.core.recordings.pendingNotes().keys)

        f.core.pullRemoteRecordings(force = true)
        assertEquals("Mine", notes.get(meta.recordingId).overview)

        // Back online: the edit goes up.
        f.drive.clearFaults()
        f.clock.advance(10.minutes)
        f.core.pullRemoteRecordings(force = true)
        assertEquals("Mine", inFolder(meta)?.overview)
        assertTrue(f.core.recordings.pendingNotes().isEmpty())
        assertEquals("Mine", notes.get(meta.recordingId).overview)
    }

    @Test
    fun `another device's notes are read at the next pull`() = runBlocking {
        val other = f.otherDevice("01J9PH0NE10000000000000000", folderProperties = mapOf(NotesFolder.STAMP to LATER))
        f.drive.put(NotesFolder.name(MetaWriter.baseName(other.meta)), other.folderId, json(theirs(other.recordingId, "From the phone")), NotesFolder.MIME)

        f.core.pullRemoteRecordings(force = true)

        val read = notes.get(other.recordingId)
        assertEquals(NotesStage.FINAL, read.stage)
        assertEquals("From the phone", read.overview)
        assertEquals(listOf("Ship bravo"), read.decisions.map { it.text })
        assertTrue("remote.notes.read" in f.logger.events)
    }

    @Test
    fun `a recording opened before a pull reads its folder's notes, and asks its folder once a run`() = runBlocking {
        val meta = f.recordAndRun()
        f.drive.put(NotesFolder.name(base(meta)), folderOf(meta), json(theirs(meta.recordingId, "Made on the Mac")), NotesFolder.MIME)

        assertEquals("Made on the Mac", notes.get(meta.recordingId).overview)
        assertEquals(theirs(meta.recordingId, "Made on the Mac"), stored(meta), "kept here as it was")
        f.core.awaitPushes()
        assertTrue(f.core.recordings.pendingNotes().isEmpty(), "a copy from the folder does not go back up")
        assertFalse("remote.notes.pushed" in f.logger.events)

        val bare = f.record(id = "01J9BBBBBB0000000000000000", startedAt = "2026-08-26T02:00:00.000Z")
        f.core.enqueue(bare.recordingId)
        f.drain()
        val name = NotesFolder.name(base(bare))
        fun lookups() = f.drive.requests.count { name in it.query["q"].orEmpty() }
        assertEquals(NotesStage.NONE, notes.get(bare.recordingId).stage)
        f.drive.put(name, folderOf(bare), json(theirs(bare.recordingId, "Too late")), NotesFolder.MIME)
        assertEquals(NotesStage.NONE, notes.get(bare.recordingId).stage)
        assertEquals(1, lookups(), "a folder without notes is asked once a run")
    }

    private companion object {
        /** Later than anything this device writes in a test. */
        const val LATER = "2026-08-26T03:00:00.000Z"
    }
}
