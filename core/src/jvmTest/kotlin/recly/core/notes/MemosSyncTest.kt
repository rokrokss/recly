package recly.core.notes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import recly.core.model.RecordingMeta
import recly.core.model.recJson
import recly.core.recording.MetaWriter
import recly.core.testing.CoreFixture

/** docs/08 "Memo" — "Storage": a memo goes to the recording's folder after the last change, and comes back from it on the other devices. */
class MemosSyncTest {
    private val f = CoreFixture()
    private val memos get() = f.core.memos

    private fun base(meta: RecordingMeta) = MetaWriter.baseName(meta)

    private fun stored(meta: RecordingMeta): MemoFile =
        MemoFolder.decode(f.fs.read(f.dirOf(meta) / MemoFile.name(base(meta))) { readUtf8() })!!

    private fun inFolder(meta: RecordingMeta): MemoFile? =
        f.drive.byName(MemoFolder.name(base(meta)))?.content?.decodeToString()?.let(MemoFolder::decode)

    private suspend fun folderOf(meta: RecordingMeta): String = f.core.recordings.get(meta.recordingId)!!.driveFolderId!!

    private fun json(memo: MemoFile) = recJson.encodeToString(MemoFile.serializer(), memo).encodeToByteArray()

    private fun theirs(recordingId: String, text: String) = MemoFile(recordingId = recordingId, text = text, updatedAt = LATER)

    @Test
    fun `a memo goes up once the typing stops, with its memoAt`() = runBlocking {
        val meta = f.recordAndRun()
        val folderId = folderOf(meta)
        memos.publishDelayMillis = 200
        memos.set(meta.recordingId, "Agen")
        memos.set(meta.recordingId, "Agenda")
        f.core.awaitPushes()

        assertEquals(stored(meta), inFolder(meta))
        assertEquals("Agenda", inFolder(meta)?.text)
        assertEquals(folderId, f.drive.byName(MemoFolder.name(base(meta)))!!.parents.single())
        assertEquals(stored(meta).updatedAt, f.drive.files.getValue(folderId).appProperties[MemoFolder.STAMP])
        assertEquals(1, f.logger.events.count { it == "remote.memo.pushed" }, "one upload for the typing")
        assertTrue(f.core.recordings.pendingMemos().isEmpty())

        f.clock.advance(1.minutes)
        memos.set(meta.recordingId, "")
        f.core.awaitPushes()
        assertEquals("", inFolder(meta)?.text, "emptied, the folder's copy is emptied too")
    }

    @Test
    fun `a memo typed before the recording has a folder waits for it`() = runBlocking {
        val meta = f.record()
        memos.publishDelayMillis = 0
        memos.set(meta.recordingId, "Agenda")
        f.core.awaitPushes()
        assertEquals(setOf(meta.recordingId), f.core.recordings.pendingMemos().keys)

        f.core.enqueue(meta.recordingId)
        f.drain()
        f.core.pullRemoteRecordings(force = true)
        assertEquals("Agenda", inFolder(meta)?.text)
        assertTrue(f.core.recordings.pendingMemos().isEmpty())
    }

    @Test
    fun `a memo typed on another device is read at the next pull, once`() = runBlocking {
        val meta = f.recordAndRun()
        val folderId = folderOf(meta)
        memos.publishDelayMillis = 0
        memos.set(meta.recordingId, "Mine")
        f.core.awaitPushes()
        val fileId = f.drive.idOf(MemoFolder.name(base(meta)))!!
        val seen = Channel<String>(Channel.UNLIMITED)
        val watching = launch(Dispatchers.Default) { memos.observe(meta.recordingId).collect { seen.send(it) } }
        assertEquals("Mine", withTimeout(10_000) { seen.receive() })

        f.drive.overwrite(fileId, json(theirs(meta.recordingId, "Theirs")))
        f.drive.files.getValue(folderId).appProperties += (MemoFolder.STAMP to LATER)
        f.core.pullRemoteRecordings(force = true)

        withTimeout(10_000) { while (seen.receive() != "Theirs") Unit }
        watching.cancel()
        assertTrue("remote.memo.read" in f.logger.events)
        assertTrue(f.core.recordings.pendingMemos().isEmpty(), "a copy from the folder does not go back up")

        fun reads() = f.drive.requests.count { it.query["alt"] == "media" && it.path.endsWith(fileId) }
        val before = reads()
        f.clock.advance(10.minutes)
        f.core.pullRemoteRecordings(force = true)
        assertEquals(before, reads(), "a memoAt already taken in is not read again")
    }

    @Test
    fun `a memo typed here that has not gone up yet is kept over the folder's`() = runBlocking {
        val meta = f.recordAndRun()
        val folderId = folderOf(meta)
        memos.publishDelayMillis = 0
        f.drive.put(MemoFolder.name(base(meta)), folderId, json(theirs(meta.recordingId, "Theirs")), MemoFolder.MIME)
        f.drive.files.getValue(folderId).appProperties += (MemoFolder.STAMP to LATER)
        f.drive.failNext(500, times = 10) { it.query["uploadType"] == "multipart" || it.method == "PATCH" }
        memos.set(meta.recordingId, "Mine")
        f.core.awaitPushes()
        assertEquals(setOf(meta.recordingId), f.core.recordings.pendingMemos().keys)

        f.core.pullRemoteRecordings(force = true)
        assertEquals("Mine", memos.get(meta.recordingId))

        // Back online: it goes up.
        f.drive.clearFaults()
        f.clock.advance(10.minutes)
        f.core.pullRemoteRecordings(force = true)
        assertEquals("Mine", inFolder(meta)?.text)
        assertTrue(f.core.recordings.pendingMemos().isEmpty())
        assertEquals("Mine", memos.get(meta.recordingId))
    }

    @Test
    fun `another device's memo is read at the next pull`() = runBlocking {
        val other = f.otherDevice("01J9PH0NE10000000000000000", folderProperties = mapOf(MemoFolder.STAMP to LATER))
        f.drive.put(MemoFolder.name(MetaWriter.baseName(other.meta)), other.folderId, json(theirs(other.recordingId, "From the phone")), MemoFolder.MIME)

        f.core.pullRemoteRecordings(force = true)

        assertEquals("From the phone", memos.get(other.recordingId))
        assertTrue("remote.memo.read" in f.logger.events)
        memos.publishDelayMillis = 0
        memos.set(other.recordingId, "Changed on the Mac")
        f.core.awaitPushes()
        assertEquals("Changed on the Mac", f.drive.byName(MemoFolder.name(MetaWriter.baseName(other.meta)))?.content?.decodeToString()?.let(MemoFolder::decode)?.text)
    }

    @Test
    fun `a recording opened before a pull reads its folder's memo, and asks its folder once a run`() = runBlocking {
        val meta = f.recordAndRun()
        f.drive.put(MemoFolder.name(base(meta)), folderOf(meta), json(theirs(meta.recordingId, "Typed on the phone")), MemoFolder.MIME)

        assertEquals("Typed on the phone", memos.get(meta.recordingId))
        assertEquals(theirs(meta.recordingId, "Typed on the phone"), stored(meta), "kept here as it was")
        f.core.awaitPushes()
        assertTrue(f.core.recordings.pendingMemos().isEmpty(), "a copy from the folder does not go back up")

        val bare = f.record(id = "01J9BBBBBB0000000000000000", startedAt = "2026-08-26T02:00:00.000Z")
        f.core.enqueue(bare.recordingId)
        f.drain()
        val name = MemoFolder.name(base(bare))
        fun lookups() = f.drive.requests.count { name in it.query["q"].orEmpty() }
        assertEquals("", memos.get(bare.recordingId))
        f.drive.put(name, folderOf(bare), json(theirs(bare.recordingId, "Too late")), MemoFolder.MIME)
        assertEquals("", memos.get(bare.recordingId))
        assertEquals(1, lookups(), "a folder without a memo is asked once a run")
    }

    private companion object {
        /** Later than anything this device writes in a test. */
        const val LATER = "2026-08-26T03:00:00.000Z"
    }
}
