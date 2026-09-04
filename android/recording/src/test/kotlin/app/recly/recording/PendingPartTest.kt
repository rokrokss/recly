@file:OptIn(ExperimentalTime::class)

package app.recly.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import recly.core.db.RecDatabase
import recly.core.job.JobStatus
import recly.core.model.Part
import recly.core.model.RecordingStatus
import recly.core.model.Track
import recly.core.recording.MetaWriter

/**
 * The path that loses audio if nobody guards it: a boundary whose `addPart` failed leaves only a
 * marker, and a stop that finalizes over it produces a `meta.json` — and then an upload — with a
 * segment silently missing. Once the row says `finalized`, nothing walks the directory again.
 */
class PendingPartTest {
    private val disk = FakeFileSystem()
    private val fs = FlakyFileSystem(disk)
    private val logger = RecordingLogger()
    private val driver = testDriver()
    private val core = testCore(fs, logger, driver)
    private val durations = TestDurations(fs)
    private val reconciler = PartReconciler(core, durations)
    private val recovery = RecordingRecovery(core, TestHost(core), durations)
    private val meta = phoneMeta()
    private val base = MetaWriter.baseName(meta)
    private val dir: Path = "/data/recordings/$base".toPath()

    private fun file(part: Int) = MetaWriter.partFileName(base, part, Track.MONO)

    private fun write(part: Int, bytes: Int) {
        fs.createDirectories(dir)
        fs.write(dir / file(part)) { write(ByteArray(bytes)) }
    }

    private fun mark(part: Int) {
        fs.write(dir / "${file(part)}${SegmentedRecorder.PENDING_SUFFIX}") { writeUtf8("") }
    }

    private fun marked(part: Int) = fs.exists(dir / "${file(part)}${SegmentedRecorder.PENDING_SUFFIX}")

    private fun registered(number: Int, durationSec: Double) = Part(
        part = number,
        track = Track.MONO,
        file = file(number),
        bytes = 3_600_000,
        sha256 = "0".repeat(64),
        startOffsetSec = (number - 1) * durationSec,
        durationSec = durationSec,
    )

    /** Part 1 filed, part 2 on disk with only a marker to show for it — what a stop walks into. */
    private suspend fun recordingWithOnePending() {
        core.recordings.create(meta, dir)
        write(1, 3_600_000)
        core.recordings.addPart(meta.recordingId, registered(1, 900.0))
        write(2, 8_000)
        mark(2)
    }

    private suspend fun closeOut() = reconciler.closeOut(
        recordingId = meta.recordingId,
        ledgerSec = 902.0,
        title = "회의",
        silenced = emptyList(),
    )

    @Test
    fun `a stop files the pending part before it closes the meta`() = runBlocking {
        recordingWithOnePending()

        val result = closeOut()

        assertIs<StopResult.Finalized>(result)
        assertEquals(2, result.outcome.parts)
        val stored = core.recordings.get(meta.recordingId)!!.meta
        assertEquals(RecordingStatus.FINALIZED, stored.status)
        assertEquals(listOf(1, 2), stored.parts.map { it.part })
        assertEquals(900.0, stored.parts[1].startOffsetSec)
        assertEquals(902.0, stored.durationSec)
        assertEquals("회의", stored.title)
        assertFalse(marked(2))
    }

    @Test
    fun `a stop that still cannot file a part does not finalize it away`() = runBlocking {
        recordingWithOnePending()
        fs.failReadsOf += file(2)

        val result = closeOut()

        assertIs<StopResult.Deferred>(result)
        assertEquals(1, result.pending)
        // The row stays open: a `finalized` row is one nothing looks at the directory for again.
        val stored = core.recordings.get(meta.recordingId)!!.meta
        assertEquals(RecordingStatus.RECORDING, stored.status)
        assertEquals(listOf(1), stored.parts.map { it.part })
        assertTrue(core.recordings.jobStatuses(meta.recordingId).isEmpty())
        assertTrue(marked(2))
        assertTrue("rec.recorder.stopDeferred" in logger.events)
    }

    @Test
    fun `the next recovery finishes what the deferred stop left`() = runBlocking {
        recordingWithOnePending()
        fs.failReadsOf += file(2)
        assertIs<StopResult.Deferred>(closeOut())

        fs.failReadsOf.clear()
        assertEquals(1, recovery.reconcile())

        val stored = core.recordings.get(meta.recordingId)!!.meta
        assertEquals(RecordingStatus.FINALIZED, stored.status)
        assertEquals(listOf(1, 2), stored.parts.map { it.part })
        assertEquals(902.0, stored.durationSec)
        assertFalse(marked(2))
        assertEquals(1, core.recordings.jobStatuses(meta.recordingId).size)

        // And only once.
        assertEquals(0, recovery.reconcile())
        assertEquals(1, core.recordings.jobStatuses(meta.recordingId).size)
    }

    @Test
    fun `a finalized recording is not queued while a part is still unfiled`() = runBlocking {
        core.recordings.create(meta, dir)
        write(1, 3_600_000)
        core.recordings.addPart(meta.recordingId, registered(1, 900.0))
        write(2, 8_000)
        mark(2)
        core.recordings.finalize(meta.recordingId, Instant.parse("2026-08-26T01:15:00.000Z"), 900.0)
        fs.failReadsOf += file(2)

        recovery.reconcile()

        assertTrue(core.recordings.jobStatuses(meta.recordingId).isEmpty())
        assertTrue("rec.recovered.pendingRemains" in logger.events)

        fs.failReadsOf.clear()
        recovery.reconcile()

        val stored = core.recordings.get(meta.recordingId)!!.meta
        assertEquals(listOf(1, 2), stored.parts.map { it.part })
        // The duration it was finalized with did not include part 2; now it does.
        assertEquals(902.0, stored.durationSec)
        assertEquals(1, core.recordings.jobStatuses(meta.recordingId).size)
    }

    @Test
    fun `a part that arrives after its job is done is filed and called out, never dropped`() = runBlocking {
        core.recordings.create(meta, dir)
        write(1, 3_600_000)
        core.recordings.addPart(meta.recordingId, registered(1, 900.0))
        core.recordings.finalize(meta.recordingId, Instant.parse("2026-08-26T01:15:00.000Z"), 900.0)
        RecDatabase(driver).recQueries.insertJob(
            "01J9JOB0000000000000000000",
            meta.recordingId,
            "00000000000000000000RECMTG",
            "{}",
            JobStatus.DONE.name,
            "2026-08-26T01:15:00.000Z",
            "2026-08-26T01:15:00.000Z",
            null,
        )
        write(2, 8_000)
        mark(2)

        recovery.reconcile()

        assertEquals(listOf(1, 2), core.recordings.get(meta.recordingId)!!.meta.parts.map { it.part })
        assertTrue("rec.recovered.partLate" in logger.events)
        // A re-run of a DONE job is the list screen's business (M2-L3), not the recovery's.
        assertEquals(listOf(JobStatus.DONE), core.recordings.jobStatuses(meta.recordingId))
    }

    @Test
    fun `a stop with no markers does not re-hash what is already filed`() = runBlocking {
        core.recordings.create(meta, dir)
        write(1, 3_600_000)
        core.recordings.addPart(meta.recordingId, registered(1, 900.0))
        // A read of it would throw; the fast path must not read anything.
        fs.failReadsOf += file(1)

        val result = closeOut()

        assertIs<StopResult.Finalized>(result)
        // The filed part is the truth about what is on disk; the ledger's 902 only backs an empty dir.
        assertEquals(900.0, result.outcome.durationSec)
    }

    /**
     * A marker is only ever a hint that audio is on disk. When that audio turns out to be a tail
     * the process died inside, there is no part to file and nothing for the stop to wait for — the
     * file is quarantined and the marker goes with it, or every later stop would defer forever.
     */
    @Test
    fun `an unreadable tail is quarantined and does not hold the stop open`() = runBlocking {
        recordingWithOnePending()
        durations.unreadable += file(2)

        val result = closeOut()

        assertIs<StopResult.Finalized>(result)
        assertEquals(1, result.outcome.parts)
        assertEquals(900.0, result.outcome.durationSec)
        assertEquals(listOf(1), core.recordings.get(meta.recordingId)!!.meta.parts.map { it.part })
        assertFalse(marked(2))
        assertFalse(fs.exists(dir / file(2)))
        assertTrue(fs.exists(dir / "${file(2)}${PartReconciler.CORRUPT_SUFFIX}"))
        assertEquals(
            listOf(mapOf("recordingId" to meta.recordingId, "file" to file(2), "bytes" to 8_000L)),
            logger.fieldsOf("rec.part.corrupt"),
        )
        assertFalse("rec.recorder.stopDeferred" in logger.events)
    }

    /** A marker whose segment is gone (quarantined or purged) must never hold a stop open. */
    @Test
    fun `an orphan marker without a segment is discarded and does not defer the stop`() = runBlocking {
        core.recordings.create(meta, dir)
        write(1, 3_600_000)
        core.recordings.addPart(meta.recordingId, registered(1, 900.0))
        mark(2) // no part-2 file at all

        val result = closeOut()

        assertIs<StopResult.Finalized>(result)
        assertFalse(marked(2), "the orphan marker is deleted on sight")
        assertEquals(1, core.recordings.get(meta.recordingId)!!.meta.parts.size)
    }

    /** The correlated failure: the row and its marker refused by the same storage. */
    @Test
    fun `a part whose marker could not be written still defers the stop`() = runBlocking {
        core.recordings.create(meta, dir)
        write(1, 3_600_000)
        core.recordings.addPart(meta.recordingId, registered(1, 900.0))
        write(2, 8_000)
        fs.failReadsOf += file(2)                                   // hashing part 2 fails → addPart never lands
        fs.failWritesOf += "${file(2)}${SegmentedRecorder.PENDING_SUFFIX}"  // …and so does its marker

        val result = closeOut()

        assertIs<StopResult.Deferred>(result)
        assertEquals(1, result.pending)
        assertFalse(marked(2), "the marker write was refused, so none exists")
        assertEquals(RecordingStatus.RECORDING, core.recordings.get(meta.recordingId)!!.meta.status)
        assertTrue(core.recordings.jobStatuses(meta.recordingId).isEmpty(), "nothing may be queued over a missing part")

        // Storage recovers: the next pass files it and finishes the stop.
        fs.failReadsOf.clear()
        fs.failWritesOf.clear()
        recovery.reconcile()
        val record = core.recordings.get(meta.recordingId)!!
        assertEquals(RecordingStatus.FINALIZED, record.meta.status)
        assertEquals(2, record.meta.parts.size)
        assertEquals(listOf(JobStatus.PENDING), core.recordings.jobStatuses(meta.recordingId))
    }
}
