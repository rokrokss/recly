@file:OptIn(ExperimentalTime::class)

package app.recly.recording

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import recly.core.model.Part
import recly.core.model.RecordingStatus
import recly.core.model.Track
import recly.core.recording.MetaWriter

/**
 * What the next process finds. docs/03 promises a recording is recoverable up to its last
 * boundary, which only holds if the segments the database never heard about are picked up and the
 * row is closed — and if a recording that was finalized but never queued eventually gets a job.
 */
class RecordingRecoveryTest {
    private val fs = FakeFileSystem()
    private val logger = RecordingLogger()
    private val core = testCore(fs, logger)
    private val host = TestHost(core)
    private val durations = TestDurations(fs)
    private val recovery = RecordingRecovery(core, host, durations)
    private val meta = phoneMeta()
    private val base = MetaWriter.baseName(meta)
    private val dir: Path = "/data/recordings/$base".toPath()

    private fun file(part: Int) = MetaWriter.partFileName(base, part, Track.MONO)

    private fun write(part: Int, bytes: Int) {
        fs.createDirectories(dir)
        fs.write(dir / file(part)) { write(ByteArray(bytes)) }
    }

    /**
     * The tail the process died inside: the container's opening, an `mdat` still open to the end
     * of the file with [bytes] of samples in it, but no moov, so nothing can read the container —
     * which is what [TestDurations.unreadable] stands in for on the JVM.
     */
    private fun writeUnclosedTail(part: Int, bytes: Int) {
        fs.createDirectories(dir)
        fs.write(dir / file(part)) {
            write(mp4Opening())
            writeInt(0).writeUtf8("mdat").write(Random(part).nextBytes(bytes))
        }
        durations.unreadable += file(part)
    }

    /** The boxes a muxer writes before the first sample: `ftyp` and nothing else. */
    private fun mp4Opening(): ByteArray =
        Buffer().writeInt(28).writeUtf8("ftypM4A ").writeInt(0).writeUtf8("M4A mp42isom").readByteArray()

    private fun registeredPart(number: Int, durationSec: Double) = Part(
        part = number,
        track = Track.MONO,
        file = file(number),
        bytes = 3_600_000,
        sha256 = "0".repeat(64),
        startOffsetSec = (number - 1) * durationSec,
        durationSec = durationSec,
    )

    @Test
    fun `a recording killed mid-flight is completed from what is on disk`() = runBlocking {
        core.recordings.create(meta, dir)
        write(1, 3_600_000)
        core.recordings.addPart(meta.recordingId, registeredPart(1, 900.0))
        // The boundary that never made it into the database, and the arming that never started.
        write(2, 8_000)
        write(3, 0)

        assertEquals(1, recovery.reconcile())

        val recovered = core.recordings.get(meta.recordingId)!!.meta
        assertEquals(RecordingStatus.FINALIZED, recovered.status)
        assertEquals(listOf(1, 2), recovered.parts.map { it.part })
        // The length the container reports: 8000 B at 32 kbps.
        assertEquals(2.0, recovered.parts[1].durationSec)
        assertEquals(900.0, recovered.parts[1].startOffsetSec)
        assertEquals(902.0, recovered.durationSec)
        assertFalse(fs.exists(dir / file(3)))
        assertTrue("rec.recovered" in logger.events)
    }

    @Test
    fun `a recovered recording is queued, and queued once`() = runBlocking {
        core.recordings.create(meta, dir)
        write(1, 8_000)

        recovery.reconcile()
        assertEquals(1, core.recordings.jobStatuses(meta.recordingId).size)
        // The recovery never enqueues itself: it says the recording is ready and the shell decides
        // (docs/11 "주의" — on the watch the same call means a transfer, not a job).
        assertEquals(listOf(meta.recordingId to true), host.ready)

        // Nothing left to do: the row is finalized and it already has its job.
        assertEquals(0, recovery.reconcile())
        assertEquals(1, core.recordings.jobStatuses(meta.recordingId).size)
        assertEquals(1, host.ready.size)
    }

    @Test
    fun `a part whose row never landed is registered and its marker cleared`() = runBlocking {
        core.recordings.create(meta, dir)
        write(1, 8_000)
        fs.write(dir / "${file(1)}${SegmentedRecorder.PENDING_SUFFIX}") { writeUtf8("") }

        recovery.reconcile()

        assertEquals(1, core.recordings.get(meta.recordingId)!!.meta.parts.size)
        assertFalse(fs.exists(dir / "${file(1)}${SegmentedRecorder.PENDING_SUFFIX}"))
    }

    @Test
    fun `a recording finalized while the title dialog was open still gets its job`() = runBlocking {
        core.recordings.create(meta, dir)
        write(1, 8_000)
        core.recordings.addPart(meta.recordingId, registeredPart(1, 2.0))
        core.recordings.finalize(meta.recordingId, Instant.parse("2026-08-26T01:00:02.000Z"), 2.0)
        assertTrue(core.recordings.jobStatuses(meta.recordingId).isEmpty())

        assertEquals(1, recovery.reconcile())

        assertEquals(1, core.recordings.jobStatuses(meta.recordingId).size)
        assertEquals(listOf(meta.recordingId to true), host.ready)
        assertTrue("rec.recovered.ready" in logger.events)
    }

    @Test
    fun `a start that died before producing a sample leaves nothing behind`() = runBlocking {
        core.recordings.create(meta, dir)
        write(1, 0)

        assertEquals(1, recovery.reconcile())

        assertNull(core.recordings.get(meta.recordingId))
        assertTrue("rec.recovered.empty" in logger.events)
        // Deleted, not handed over: there is nothing to transfer and nothing to run.
        assertEquals(emptyList(), host.ready)
    }

    /**
     * The failure this guards: a length guessed from the bitrate made the tail look like a part,
     * and an unplayable file was filed, finalized over and uploaded. docs/03 — the unreadable tail
     * is quarantined, and the recovery finalizes up to the last part that can be read.
     */
    @Test
    fun `a tail no decoder can read is set aside instead of filed`() = runBlocking {
        core.recordings.create(meta, dir)
        write(1, 3_600_000)
        core.recordings.addPart(meta.recordingId, registeredPart(1, 900.0))
        writeUnclosedTail(2, 8_000)

        assertEquals(1, recovery.reconcile())

        val recovered = core.recordings.get(meta.recordingId)!!.meta
        assertEquals(RecordingStatus.FINALIZED, recovered.status)
        // Only the part that can be played, and a duration that does not count the tail.
        assertEquals(listOf(1), recovered.parts.map { it.part })
        assertEquals(900.0, recovered.durationSec)
        assertFalse(fs.exists(dir / file(2)))
        assertTrue(fs.exists(dir / "${file(2)}${PartReconciler.CORRUPT_SUFFIX}"))
        assertEquals(
            // The 8 000 bytes of samples behind the 36 bytes of boxes the tail opens with.
            listOf(mapOf("recordingId" to meta.recordingId, "file" to file(2), "bytes" to 8_036L)),
            logger.fieldsOf("rec.part.corrupt"),
        )
        assertTrue("rec.recovered" in logger.events)
        assertEquals(1, core.recordings.jobStatuses(meta.recordingId).size)

        // And the quarantined file is not a segment any more: the next pass has nothing to do.
        assertEquals(0, recovery.reconcile())
        assertEquals(1, logger.fieldsOf("rec.part.corrupt").size)
    }

    /**
     * Nothing playable: the tail is quarantined, and with nothing else in the directory the
     * recording is dropped rather than left as a `recording` row the user can do nothing with
     * (2026-09-04 decision) — the quarantined bytes go with the directory.
     */
    @Test
    fun `a recording whose only segment is an unreadable tail is dropped`() = runBlocking {
        core.recordings.create(meta, dir)
        writeUnclosedTail(1, 8_000)

        assertEquals(1, recovery.reconcile())

        assertNull(core.recordings.get(meta.recordingId))
        assertFalse(fs.exists(dir))
        assertTrue("rec.part.corrupt" in logger.events)
        assertTrue("rec.recovered.empty" in logger.events)
        assertEquals(emptyList(), host.ready)
    }

    /** A segment that never got a sample — the opening boxes and no `mdat` payload — goes the same way. */
    @Test
    fun `a recording whose only segment never got a sample is dropped`() = runBlocking {
        core.recordings.create(meta, dir)
        fs.createDirectories(dir)
        fs.write(dir / file(1)) { write(mp4Opening()) }
        durations.unreadable += file(1)

        assertEquals(1, recovery.reconcile())

        assertNull(core.recordings.get(meta.recordingId))
        assertFalse(fs.exists(dir))
        assertTrue("rec.part.corrupt" in logger.events)
        assertTrue("rec.recovered.empty" in logger.events)
        assertEquals(emptyList(), host.ready)
    }

    @Test
    fun `part numbers come from the file name the spec fixes`() {
        assertEquals(1, PartReconciler.partNumber("${base}_p001_mono.m4a"))
        assertEquals(12, PartReconciler.partNumber("${base}_p012_mono.m4a"))
        assertNull(PartReconciler.partNumber("$base.meta.json"))
    }
}
