@file:OptIn(ExperimentalTime::class)

package app.recly.windows.record

import app.recly.windows.core.AppModule
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import recly.core.ReclyCore
import recly.core.job.JobStatus
import recly.core.model.AudioSettings
import recly.core.model.Codec
import recly.core.model.Container
import recly.core.model.Part
import recly.core.model.Platform
import recly.core.model.RecordingMeta
import recly.core.model.RecordingStatus
import recly.core.model.Source
import recly.core.model.Track
import recly.core.recording.MetaWriter
import recly.core.sync.WorkflowRepository

/**
 * docs/03 "복구" as [RecordingRecovery] implements it for a shell that did not write the audio
 * itself. Every case starts from a directory and a row in the state a kill would leave them in.
 */
class RecordingRecoveryTest {

    @Test
    fun `a crash finalizes through the last registered part and quarantines the tail`() = runBlocking {
        val core = core()
        val (id, dir, base) = open(core)
        // Two segments the helper reported…
        register(core, id, base, part = 1, sec = 900.0)
        register(core, id, base, part = 2, sec = 900.0)
        write(dir, MetaWriter.partFileName(base, 1, Track.MIC))
        write(dir, MetaWriter.partFileName(base, 2, Track.MIC))
        // …and one it was still writing when it was killed: no `part_done`, so no duration and no
        // hash, and nothing in this process can work them out.
        val tail = MetaWriter.partFileName(base, 3, Track.MIC)
        write(dir, tail)

        assertEquals(1, RecordingRecovery(core).reconcile())

        val record = assertNotNull(core.recordings.get(id))
        assertEquals(RecordingStatus.FINALIZED, record.meta.status)
        assertEquals(1800.0, record.meta.durationSec, "through the last part that has a duration")
        assertEquals(2, record.meta.parts.size)
        // Never registered (it would upload as audio of an unknown length) and never deleted.
        assertFalse(FileSystem.SYSTEM.exists(dir / tail))
        assertTrue(FileSystem.SYSTEM.exists(dir / "$tail.corrupt"))
        assertEquals(listOf(JobStatus.PENDING), core.recordings.jobStatuses(id))
    }

    @Test
    fun `a recording with nothing but a corrupt segment is dropped`() = runBlocking {
        // docs/03 (2026-09-04): nothing the app can play or send, so the row does not stay
        // `recording` for good — it goes, and the quarantined bytes with the directory.
        val core = core()
        val (id, dir, base) = open(core)
        val only = MetaWriter.partFileName(base, 1, Track.MIC)
        writeTruncatedTail(dir, only)

        assertEquals(1, RecordingRecovery(core).reconcile())

        assertNull(core.recordings.get(id))
        assertFalse(FileSystem.SYSTEM.exists(dir))
        assertTrue(core.recordings.jobStatuses(id).isEmpty())
    }

    /** A segment that never got a sample — the container's opening and no `mdat` payload — goes the same way. */
    @Test
    fun `a recording whose only segment never got a sample is dropped`() = runBlocking {
        val core = core()
        val (id, dir, base) = open(core)
        val only = MetaWriter.partFileName(base, 1, Track.MIC)
        FileSystem.SYSTEM.write(dir / only) { write(mp4Opening()) }

        assertEquals(1, RecordingRecovery(core).reconcile())

        assertNull(core.recordings.get(id))
        assertFalse(FileSystem.SYSTEM.exists(dir))
    }

    @Test
    fun `a recording that never got a single part goes away with its directory`() = runBlocking {
        val core = core()
        val (id, dir, _) = open(core)

        assertEquals(1, RecordingRecovery(core).reconcile())

        assertNull(core.recordings.get(id))
        assertFalse(FileSystem.SYSTEM.exists(dir))
    }

    @Test
    fun `a marked part is registered from its marker instead of quarantined`() = runBlocking {
        // The part's audio is good and everything about it is known — it is only the row that never
        // landed (`WindowsRecorder` writes the marker when `addPart` throws).
        val core = core()
        val (id, dir, base) = open(core)
        val file = MetaWriter.partFileName(base, 1, Track.MIC)
        write(dir, file)
        PartMarker.write(FileSystem.SYSTEM, dir, part(base, part = 1, sec = 300.0))

        assertEquals(1, RecordingRecovery(core).reconcile())

        val record = assertNotNull(core.recordings.get(id))
        assertEquals(RecordingStatus.FINALIZED, record.meta.status)
        assertEquals(listOf(file), record.meta.parts.map { it.file })
        assertEquals(300.0, record.meta.durationSec)
        assertTrue(FileSystem.SYSTEM.exists(dir / file), "the audio stays where it is")
        assertFalse(FileSystem.SYSTEM.exists(dir / "$file.corrupt"), "a marked part is not corrupt")
        assertFalse(FileSystem.SYSTEM.exists(PartMarker.path(dir, part(base, 1, 300.0))))
        assertEquals(listOf(JobStatus.PENDING), core.recordings.jobStatuses(id))
    }

    @Test
    fun `a marked part and an unregistered tail are told apart`() = runBlocking {
        val core = core()
        val (id, dir, base) = open(core)
        val marked = MetaWriter.partFileName(base, 1, Track.MIC)
        val tail = MetaWriter.partFileName(base, 2, Track.MIC)
        write(dir, marked)
        write(dir, tail)
        PartMarker.write(FileSystem.SYSTEM, dir, part(base, part = 1, sec = 900.0))

        assertEquals(1, RecordingRecovery(core).reconcile())

        val record = assertNotNull(core.recordings.get(id))
        assertEquals(listOf(marked), record.meta.parts.map { it.file })
        assertEquals(900.0, record.meta.durationSec)
        assertTrue(FileSystem.SYSTEM.exists(dir / "$tail.corrupt"), "no part_done, no duration, no hash")
        assertFalse(FileSystem.SYSTEM.exists(dir / "$marked.corrupt"))
    }

    @Test
    fun `a marker this version cannot read leaves the recording open`() = runBlocking {
        // The part it stands for is still owed: finalizing would publish a meta without it, and
        // quarantining would condemn audio that is not corrupt at all.
        val core = core()
        val (id, dir, base) = open(core)
        register(core, id, base, part = 1, sec = 60.0)
        write(dir, MetaWriter.partFileName(base, 1, Track.MIC))
        val marked = MetaWriter.partFileName(base, 2, Track.MIC)
        write(dir, marked)
        FileSystem.SYSTEM.write(dir / "$marked${PartMarker.SUFFIX}") { writeUtf8("{\"part\":2,") }

        assertEquals(0, RecordingRecovery(core).reconcile())

        val record = assertNotNull(core.recordings.get(id))
        assertEquals(RecordingStatus.RECORDING, record.meta.status, "nothing is finalized over it")
        assertEquals(1, record.meta.parts.size)
        assertTrue(FileSystem.SYSTEM.exists(dir / marked), "its audio is not quarantined")
        assertFalse(FileSystem.SYSTEM.exists(dir / "$marked.corrupt"))
        assertTrue(FileSystem.SYSTEM.exists(dir / "$marked${PartMarker.SUFFIX}"), "the marker stays")
        assertTrue(core.recordings.jobStatuses(id).isEmpty())
    }

    @Test
    fun `a directory holding only files this pass has no rule for goes away too`() = runBlocking {
        // A helper temp file, a half-written meta — and no part: nothing the app can do with it.
        val core = core()
        val (id, dir, _) = open(core)
        write(dir, "capture-helper-42.tmp")

        assertEquals(1, RecordingRecovery(core).reconcile())

        assertNull(core.recordings.get(id))
        assertFalse(FileSystem.SYSTEM.exists(dir))
    }

    @Test
    fun `a recording finalized but never queued is queued now`() = runBlocking {
        // The crash landed between the stop and the title prompt's answer (`completeRecording`).
        val core = core()
        val (id, dir, base) = open(core)
        register(core, id, base, part = 1, sec = 60.0)
        write(dir, MetaWriter.partFileName(base, 1, Track.MIC))
        core.recordings.finalize(id, core.deps.clock.now(), durationSec = 60.0)

        assertEquals(1, RecordingRecovery(core).reconcile())

        assertEquals(listOf(JobStatus.PENDING), core.recordings.jobStatuses(id))
    }

    @Test
    fun `a recording that already has a job is left alone`() = runBlocking {
        val core = core()
        val (id, dir, base) = open(core)
        register(core, id, base, part = 1, sec = 60.0)
        write(dir, MetaWriter.partFileName(base, 1, Track.MIC))
        core.recordings.finalize(id, core.deps.clock.now(), durationSec = 60.0)
        core.enqueue(id)

        assertEquals(0, RecordingRecovery(core).reconcile())

        assertEquals(1, core.recordings.jobStatuses(id).size, "no second job for the same recording")
    }

    /** A row and a directory in the state a kill leaves them: `recording`, with no parts filed. */
    private suspend fun open(core: ReclyCore): Triple<String, Path, String> {
        val id = "01M138NW9RTJ1JMM6TNHFG1ARC"
        val meta = RecordingMeta(
            schema = 1,
            recordingId = id,
            source = Source.DESKTOP,
            platform = Platform.WINDOWS,
            deviceId = core.deps.device.deviceId,
            deviceName = core.deps.device.name,
            startedAt = "2026-08-27T10:00:00.000Z",
            timezone = "Asia/Seoul",
            audio = AudioSettings(Codec.AAC_LC, Container.M4A, 16_000, 1, 32, 900),
            tracks = listOf(Track.MIC),
            parts = emptyList(),
            status = RecordingStatus.RECORDING,
        )
        val base = MetaWriter.baseName(meta)
        val dir = core.deps.dataDir / "recordings" / base
        FileSystem.SYSTEM.createDirectories(dir)
        core.recordings.create(meta, dir)
        return Triple(id, dir, base)
    }

    private suspend fun register(core: ReclyCore, id: String, base: String, part: Int, sec: Double) {
        core.recordings.addPart(
            id,
            Part(
                part = part,
                track = Track.MIC,
                file = MetaWriter.partFileName(base, part, Track.MIC),
                bytes = 3_600_000,
                sha256 = "0".repeat(64),
                startOffsetSec = (part - 1) * sec,
                durationSec = sec,
            ),
        )
    }

    private fun part(base: String, part: Int, sec: Double) = Part(
        part = part,
        track = Track.MIC,
        file = MetaWriter.partFileName(base, part, Track.MIC),
        bytes = 3_600_000,
        sha256 = "1".repeat(64),
        startOffsetSec = (part - 1) * sec,
        durationSec = sec,
    )

    private fun write(dir: Path, name: String) {
        FileSystem.SYSTEM.write(dir / name) { writeUtf8("not really audio") }
    }

    /** The boxes a muxer writes before the first sample: `ftyp` and nothing else. */
    private fun mp4Opening(): ByteArray =
        Buffer().writeInt(28).writeUtf8("ftypM4A ").writeInt(0).writeUtf8("M4A mp42isom").readByteArray()

    /** The tail a kill leaves: the opening, an `mdat` open to the end of the file with samples in it, no moov. */
    private fun writeTruncatedTail(dir: Path, name: String) {
        FileSystem.SYSTEM.write(dir / name) {
            write(mp4Opening())
            writeInt(0).writeUtf8("mdat").write(ByteArray(6000) { (it * 31 + 7).toByte() })
        }
    }

    /**
     * As `ShellModel.load` opens it: the docs/05 starters seeded and this device's default pointed
     * at 회의 (ADR-016), without which a recovered recording would resolve no workflow at all.
     */
    private suspend fun core(): ReclyCore = AppModule.build(
        dataDir = Files.createTempDirectory("recly-recovery").toString().toPath(),
    ).core.also { it.workflows.seed(WorkflowRepository.MEMO_ID) }
}
