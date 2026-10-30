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
import recly.core.ids.Ulid
import recly.core.model.Part
import recly.core.model.RecordingMeta
import recly.core.model.RecordingStatus
import recly.core.model.Source
import recly.core.model.Track
import recly.core.model.isoUtc
import recly.core.model.recJson
import recly.core.recording.DeleteResult
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

    /** What the receiver handed the provisional pass, and the recordings it opened — `ReclyCore` wires both. */
    private val organized = mutableListOf<Part>()
    private val opened = mutableListOf<String>()

    init {
        receiver.partReceived = { _, part -> organized += part }
        receiver.recordingOpened = { opened += it }
    }

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

    /** The watch's `meta.json` while it is still recording: what it sends with each part. */
    private fun progressFor(vararg numbers: Int): RecordingMeta = metaFor(*numbers).copy(
        endedAt = null,
        durationSec = null,
        status = RecordingStatus.RECORDING,
    )

    private suspend fun progress(vararg numbers: Int): Boolean =
        receiver.acceptProgress(watchMeta.recordingId, recJson.encodeToString(progressFor(*numbers)))

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

    /**
     * docs/03 "Recordings from other devices": the row a transfer opens is what the list shows for as long as the
     * transfer takes, and a 20-minute recording handed over at its end must not sit at the top of
     * the list as if it had just started. The watch's id carries the millisecond it began.
     */
    @Test
    fun `the row a transfer opens is dated by the watch's id, not by the moment it arrived`() = runBlocking {
        clock.advance(3.hours)

        accept(1)

        val record = assertNotNull(recordings.get(watchMeta.recordingId))
        assertEquals(assertNotNull(Ulid.timestamp(watchMeta.recordingId)).isoUtc(), record.meta.startedAt)
        assertTrue(record.receiving, "a phone never records with source watch: this is a transfer in flight")
        assertFalse(record.remoteUploading)

        // And it is the watch's own meta once that lands, as before.
        accept(2)
        receiver.acceptMeta(recJson.encodeToString(metaFor(1, 2)))
        val finalized = assertNotNull(recordings.get(watchMeta.recordingId))
        assertEquals(watchMeta.startedAt, finalized.meta.startedAt)
        assertFalse(finalized.receiving)
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

    /**
     * The row a transfer opens says `recording` and has no tracks until the meta lands; a recovery
     * pass asks this before treating it as a recording the process died in.
     */
    @Test
    fun `a row the receiver opened is receiving until its meta lands`() = runBlocking {
        accept(1)
        assertTrue(receiver.receiving(watchMeta.recordingId))

        assertIs<AcceptMetaResult.Incomplete>(receiver.acceptMeta(recJson.encodeToString(metaFor(1, 2))))
        assertTrue(receiver.receiving(watchMeta.recordingId))

        accept(2)
        assertIs<AcceptMetaResult.Complete>(receiver.acceptMeta(recJson.encodeToString(metaFor(1, 2))))
        assertFalse(receiver.receiving(watchMeta.recordingId))
    }

    @Test
    fun `a recording this device is making is not receiving`() = runBlocking {
        val own = testMeta(recordingId = "01J9ZZZZZZZZZZZZZZZZZZZZZZ")
        recordings.create(own, "/data/recordings/own".toPath())

        assertFalse(receiver.receiving(own.recordingId))
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

    /**
     * docs/03 (2026-10-30): the user may delete a row still receiving from the watch — the row, its parts and the
     * receiver's marks go together, so the purge has nothing left to find. A watch that still has the recording keeps
     * it until `ack-meta ok:true`, and its resend brings the recording back whole.
     */
    @Test
    fun `a receiving row the user deletes goes with its marks, and the watch's resend brings it back`() = runBlocking {
        assertTrue(progress(1))
        assertTrue(accept(1).ok)

        assertIs<DeleteResult.Deleted>(recordings.delete(watchMeta.recordingId, deleteDrive = false))
        assertNull(recordings.get(watchMeta.recordingId))
        assertFalse(fs.exists(dir))
        assertFalse(receiver.receiving(watchMeta.recordingId))
        assertNull(db.recQueries.kvGet("transfer.progress.${watchMeta.recordingId}").executeAsOneOrNull())
        assertTrue(receiver.purgeOrphans(START + 48.hours).isEmpty())

        val missing = assertIs<AcceptMetaResult.Incomplete>(receiver.acceptMeta(recJson.encodeToString(metaFor(1, 2))))
        assertEquals(listOf(1, 2), missing.missingParts.map { it.part })
        assertTrue(accept(1).ok)
        assertTrue(accept(2).ok)
        assertIs<AcceptMetaResult.Complete>(receiver.acceptMeta(recJson.encodeToString(metaFor(1, 2))))
        assertEquals(RecordingStatus.FINALIZED, assertNotNull(recordings.get(watchMeta.recordingId)).meta.status)
    }

    /** The purge only ever looks at rows the receiver opened — never at a recording in progress. */
    @Test
    fun `a recording this device is making is never purged`() = runBlocking<Unit> {
        val own = testMeta(recordingId = "01J9ZZZZZZZZZZZZZZZZZZZZZZ")
        recordings.create(own, "/data/recordings/own".toPath())

        assertTrue(receiver.purgeOrphans(START + 72.hours).isEmpty())
        assertNotNull(recordings.get(own.recordingId))
    }

    // --- docs/03: a watch sends each part while it is still recording, with an in-progress meta ---

    /**
     * The in-progress meta opens the row the parts are filed into: the watch's start, time zone and tracks —
     * still `recording`, still the receiver's, and nothing on disk as its `meta.json`.
     */
    @Test
    fun `an in-progress meta opens a receiving row with the watch's own fields`() = runBlocking {
        assertTrue(progress(1))

        val record = assertNotNull(recordings.get(watchMeta.recordingId))
        assertEquals(RecordingStatus.RECORDING, record.meta.status)
        assertTrue(record.receiving)
        assertTrue(receiver.receiving(watchMeta.recordingId), "the recovery pass leaves it alone")
        assertEquals(watchMeta.startedAt, record.meta.startedAt)
        assertEquals(watchMeta.timezone, record.meta.timezone)
        assertEquals(listOf(Track.MONO), record.meta.tracks)
        assertEquals(emptyList(), record.meta.parts, "a part it lists that has not arrived is not one")
        assertFalse(fs.exists(dir / MetaWriter.metaFileName(base)), "only the final meta is written")
        assertEquals(listOf(watchMeta.recordingId), opened, "the phone's settings are pinned now")
        assertEquals(emptyList(), organized)
    }

    /**
     * Each part goes to the provisional pass once it is both here and listed — whichever came first — with
     * the in-progress meta's offset and length, so its notes land at the right moment of the recording.
     */
    @Test
    fun `a part that is here and listed goes to the provisional pass with its offset, whichever came first`() = runBlocking {
        // The meta ahead of the part, as both watches send it.
        assertTrue(progress(1))
        assertTrue(accept(1).ok)
        assertEquals(listOf(1), organized.map { it.part })
        assertEquals(0.0, organized.single().startOffsetSec)
        assertEquals(900.0, organized.single().durationSec)

        // A part that arrives before the meta that lists it waits for that meta.
        assertTrue(accept(2).ok)
        assertEquals(listOf(1), organized.map { it.part }, "not listed yet: its place on the recording is not known")
        assertTrue(progress(1, 2))
        assertEquals(listOf(1, 2), organized.map { it.part })
        assertEquals(900.0, organized.last().startOffsetSec)

        // A refreshed meta that lists nothing new organizes nothing again.
        assertTrue(progress(1, 2))
        assertEquals(listOf(1, 2), organized.map { it.part })
        assertEquals(listOf(watchMeta.recordingId), opened, "pinned once")

        val parts = assertNotNull(recordings.get(watchMeta.recordingId)).meta.parts
        assertEquals(listOf(0.0, 900.0), parts.map { it.startOffsetSec })
        assertEquals(listOf(900.0, 900.0), parts.map { it.durationSec })
    }

    /** Parts on their own — a meta lost on the way — are filed as before and organized by nobody. */
    @Test
    fun `parts without an in-progress meta are filed and not organized`() = runBlocking<Unit> {
        assertTrue(accept(1).ok)
        assertTrue(accept(2).ok)

        assertEquals(emptyList(), organized)
        assertEquals(emptyList(), opened)
        assertIs<AcceptMetaResult.Complete>(receiver.acceptMeta(recJson.encodeToString(metaFor(1, 2))))
    }

    /**
     * The final meta is still the only thing that files the recording; afterwards an in-progress meta that
     * arrives late changes nothing — it must never re-open a recording behind its job.
     */
    @Test
    fun `the final meta files the recording, and a late in-progress meta does not re-open it`() = runBlocking {
        progress(1)
        accept(1)
        progress(1, 2)
        accept(2)

        assertIs<AcceptMetaResult.Complete>(receiver.acceptMeta(recJson.encodeToString(metaFor(1, 2))))
        val filed = assertNotNull(recordings.get(watchMeta.recordingId))
        assertEquals(RecordingStatus.FINALIZED, filed.meta.status)
        assertFalse(receiver.receiving(watchMeta.recordingId))
        assertEquals(RecordingStatus.FINALIZED, recJson.decodeFromString<RecordingMeta>(fs.read(dir / MetaWriter.metaFileName(base)) { readUtf8() }).status)

        assertFalse(progress(1, 2))
        assertEquals(RecordingStatus.FINALIZED, assertNotNull(recordings.get(watchMeta.recordingId)).meta.status)
        assertFalse(receiver.receiving(watchMeta.recordingId))
        assertEquals(listOf(1, 2), organized.map { it.part }, "nothing organized after the final meta")
        // A part sent again after that keeps the place the final meta gave it.
        accept(2)
        assertEquals(900.0, assertNotNull(recordings.get(watchMeta.recordingId)).meta.parts.single { it.part == 2 }.startOffsetSec)
        assertEquals(listOf(1, 2), organized.map { it.part })
    }

    @Test
    fun `an in-progress meta that is not this recording's or not a watch's is refused`() = runBlocking {
        assertFalse(receiver.acceptProgress(watchMeta.recordingId, "{\"schema\":1}"))
        assertFalse(receiver.acceptProgress("01J9ZZZZZZZZZZZZZZZZZZZZZZ", recJson.encodeToString(progressFor(1))))
        assertFalse(receiver.acceptProgress(watchMeta.recordingId, recJson.encodeToString(progressFor(1).copy(source = Source.PHONE))))
        assertNull(recordings.get(watchMeta.recordingId))

        val own = testMeta(recordingId = "01J9ZZZZZZZZZZZZZZZZZZZZZZ", source = Source.WATCH)
        recordings.create(own, "/data/recordings/own".toPath())
        assertFalse(receiver.acceptProgress(own.recordingId, recJson.encodeToString(own)), "a recording this device is making")
        assertFalse(receiver.receiving(own.recordingId))
    }

    /**
     * docs/03: the 24-hour orphan clock runs from the last part that arrived. A watch recording three hours
     * long sends its first part three hours before its meta, and that is not an orphan.
     */
    @Test
    fun `the orphan clock runs from the last part, not the first`() = runBlocking {
        progress(1)
        accept(1)
        clock.advance(20.hours)
        progress(1, 2)
        accept(2)

        assertTrue(receiver.purgeOrphans(START + 25.hours).isEmpty(), "the first part is 25 hours old, the last 5")
        assertNotNull(recordings.get(watchMeta.recordingId))

        assertEquals(listOf(watchMeta.recordingId), receiver.purgeOrphans(START + 45.hours))
        assertNull(recordings.get(watchMeta.recordingId))
        assertFalse(receiver.receiving(watchMeta.recordingId))
        // The in-progress meta went with it: a part of the same id arriving later starts over.
        accept(1)
        assertEquals(0.0, assertNotNull(recordings.get(watchMeta.recordingId)).meta.parts.single().durationSec)
    }
}
