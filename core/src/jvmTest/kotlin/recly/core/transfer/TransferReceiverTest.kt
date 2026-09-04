@file:OptIn(ExperimentalTime::class)

package recly.core.transfer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.encodeUtf8
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import recly.core.model.Part
import recly.core.model.RecordingMeta
import recly.core.model.RecordingStatus
import recly.core.model.Source
import recly.core.model.Track
import recly.core.model.recJson
import recly.core.recording.MetaWriter
import recly.core.recording.PartHasher
import recly.core.recording.RecordingRepository
import recly.core.testing.FakeClock
import recly.core.testing.START
import recly.core.testing.inMemoryDatabase
import recly.core.testing.testDeps
import recly.core.testing.testMeta

class TransferReceiverTest {
    private val fs = FakeFileSystem()
    private val clock = FakeClock()
    private val deps = testDeps(clock = clock, fileSystem = fs)
    private val db = inMemoryDatabase()
    private val recordings = RecordingRepository(db, deps)
    private val receiver = TransferReceiver(db, recordings, deps)

    private val watchMeta = testMeta(source = Source.WATCH, title = "산책").copy(
        endedAt = "2026-08-26T01:30:00.000Z",
        durationSec = 1800.0,
        status = RecordingStatus.FINALIZED,
    )
    private val base = MetaWriter.baseName(watchMeta)
    private val dir = "/data/recordings/${watchMeta.recordingId}".toPath()

    /** What the watch's transfer API hands over: a file in a staging directory. */
    private fun incoming(number: Int, content: String = "audio-$number"): Path {
        val path = "/incoming/${MetaWriter.partFileName(base, number, Track.MONO)}".toPath()
        fs.createDirectories(path.parent!!)
        fs.write(path) { writeUtf8(content) }
        return path
    }

    private suspend fun sha(path: Path) = PartHasher.sha256(fs, path)

    private suspend fun accept(number: Int, claimed: String? = null): Ack {
        val path = incoming(number)
        return receiver.acceptPart(watchMeta.recordingId, number, Track.MONO, claimed ?: sha(path), path)
    }

    /** The meta the watch will send, describing the same content [incoming] writes. */
    private fun metaFor(vararg numbers: Int): RecordingMeta = watchMeta.copy(
        parts = numbers.map { number ->
            val content = "audio-$number"
            Part(
                part = number,
                track = Track.MONO,
                file = MetaWriter.partFileName(base, number, Track.MONO),
                bytes = content.length.toLong(),
                sha256 = content.encodeUtf8().sha256().hex(),
                startOffsetSec = (number - 1) * 900.0,
                durationSec = 900.0,
            )
        },
    )

    @Test
    fun `a part whose hash matches is filed under the recording and acked`() = runBlocking {
        val ack = accept(1)

        assertTrue(ack.ok)
        assertNull(ack.reason)
        assertTrue(fs.exists(dir / MetaWriter.partFileName(base, 1, Track.MONO)))
        assertFalse(fs.exists("/incoming/${MetaWriter.partFileName(base, 1, Track.MONO)}".toPath()))
        val record = assertNotNull(recordings.get(watchMeta.recordingId))
        assertEquals(RecordingStatus.RECORDING, record.meta.status)
        assertEquals(1, record.meta.parts.size)
    }

    /** docs/03: the phone verifies sha256 and nacks; the watch still holds the original. */
    @Test
    fun `a part whose hash does not match is nacked and discarded`() = runBlocking {
        val ack = accept(1, claimed = "0".repeat(64))

        assertFalse(ack.ok)
        assertEquals("SHA256_MISMATCH", ack.reason)
        assertFalse(fs.exists(dir / MetaWriter.partFileName(base, 1, Track.MONO)), "a bad part must not be kept")
        assertFalse(fs.exists("/incoming/${MetaWriter.partFileName(base, 1, Track.MONO)}".toPath()))
        assertNull(recordings.get(watchMeta.recordingId), "a nacked first part opens no recording")
    }

    /** The ack may have been lost on the way back, so the watch resends — that must be harmless. */
    @Test
    fun `the same part sent twice is accepted once`() = runBlocking {
        assertTrue(accept(1).ok)
        assertTrue(accept(1).ok)

        assertEquals(1, db.recQueries.selectPartsByRecording(watchMeta.recordingId).executeAsList().size)
        assertEquals(1, assertNotNull(recordings.get(watchMeta.recordingId)).meta.parts.size)
    }

    @Test
    fun `a meta that arrives before all its parts is incomplete`() = runBlocking {
        accept(1)
        val meta = metaFor(1, 2)

        val result = receiver.acceptMeta(recJson.encodeToString(meta))

        assertIs<AcceptMetaResult.Incomplete>(result)
        assertEquals(listOf(2), result.missingParts.map { it.part })
        assertEquals(RecordingStatus.RECORDING, assertNotNull(recordings.get(watchMeta.recordingId)).meta.status)
        assertFalse(fs.exists(dir / MetaWriter.metaFileName(base)))
    }

    @Test
    fun `a meta whose parts are all present finalizes the recording and writes meta json`() = runBlocking<Unit> {
        accept(1)
        accept(2)
        val meta = metaFor(1, 2)

        val result = receiver.acceptMeta(recJson.encodeToString(meta))

        assertIs<AcceptMetaResult.Complete>(result)
        assertEquals(watchMeta.recordingId, result.recordingId)
        val record = assertNotNull(recordings.get(watchMeta.recordingId))
        assertEquals(RecordingStatus.FINALIZED, record.meta.status)
        assertEquals("산책", record.meta.title)
        assertEquals(2, db.recQueries.selectPartsByRecording(watchMeta.recordingId).executeAsList().size)
        val written = fs.read(dir / MetaWriter.metaFileName(base)) { readUtf8() }
        assertEquals(RecordingStatus.FINALIZED, recJson.decodeFromString<RecordingMeta>(written).status)
        // The transfer is over, so the orphan purge has nothing left to find.
        assertTrue(receiver.purgeOrphans(START + 48.hours).isEmpty())
        assertNotNull(recordings.get(watchMeta.recordingId))
    }

    @Test
    fun `malformed meta json is reported rather than thrown`() = runBlocking<Unit> {
        assertIs<AcceptMetaResult.Invalid>(receiver.acceptMeta("""{"schema":1}"""))
    }

    /** docs/03: parts with no meta after 24 hours are deleted, files and row alike. */
    @Test
    fun `orphan parts are purged after 24 hours and not before`() = runBlocking {
        accept(1)

        assertTrue(receiver.purgeOrphans(START + 23.hours).isEmpty())
        assertNotNull(recordings.get(watchMeta.recordingId))

        assertEquals(listOf(watchMeta.recordingId), receiver.purgeOrphans(START + 25.hours))
        assertNull(recordings.get(watchMeta.recordingId))
        assertFalse(fs.exists(dir))
        assertTrue(db.recQueries.selectPartsByRecording(watchMeta.recordingId).executeAsList().isEmpty())
    }

    /** The purge only ever looks at rows the receiver opened — never at a recording in progress. */
    @Test
    fun `a recording this device is making is never purged`() = runBlocking<Unit> {
        val own = testMeta(recordingId = "01J9ZZZZZZZZZZZZZZZZZZZZZZ")
        recordings.create(own, "/data/recordings/own".toPath())

        assertTrue(receiver.purgeOrphans(START + 72.hours).isEmpty())
        assertNotNull(recordings.get(own.recordingId))
    }
}
