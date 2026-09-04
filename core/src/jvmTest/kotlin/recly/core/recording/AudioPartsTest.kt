@file:OptIn(ExperimentalTime::class)

package recly.core.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import recly.core.drive.DriveApi
import recly.core.drive.ScriptedTokenProvider
import recly.core.drive.mockTransport
import recly.core.model.Part
import recly.core.model.RecordingMeta
import recly.core.model.RecordingStatus
import recly.core.model.Track
import recly.core.model.wire
import recly.core.testing.FakeClock
import recly.core.testing.FakeDrive
import recly.core.testing.FakeLogger
import recly.core.testing.SEEDED_AUDIO
import recly.core.testing.SEEDED_AUDIO_SHA256
import recly.core.testing.inMemoryDatabase
import recly.core.testing.testDeps
import recly.core.testing.testMeta

/**
 * What the detail screen plays (docs/03 "로컬 저장"): the local parts when they are here, and the
 * ones the retention sweep has taken fetched back from Drive — verified against the row before
 * anything is written under a part's name.
 */
class AudioPartsTest {

    @Test
    fun `the local parts are handed back in order without touching Drive`() = runBlocking {
        val h = harness()

        val audio = h.audio.load(h.record(), listOf(h.uploadOutput()))

        assertEquals(Track.MONO, audio.track)
        assertEquals(h.paths(Track.MONO), audio.paths)
        assertEquals(emptyList(), audio.missing)
        assertEquals(0, h.downloads, "the copies are on disk")
    }

    /** A recording that was mixed down plays the mix, not one of the two tracks it was made of. */
    @Test
    fun `the mix track is what a recording that has one plays`() = runBlocking {
        val h = harness(tracks = listOf(Track.MIC, Track.SYS, Track.MIX))

        val audio = h.audio.load(h.record(), listOf(h.uploadOutput()))

        assertEquals(Track.MIX, audio.track)
        assertEquals(h.paths(Track.MIX), audio.paths)
    }

    @Test
    fun `parts the sweep took are fetched back from Drive, verified and kept`() = runBlocking {
        val h = harness()
        h.purge()

        val audio = h.audio.load(h.record(), listOf(h.uploadOutput()))

        assertEquals(h.paths(Track.MONO), audio.paths)
        assertEquals(emptyList(), audio.missing)
        assertEquals(2, h.downloads, "one round trip per part")
        assertEquals(SEEDED_AUDIO, h.fs.read(h.path(1, Track.MONO)) { readUtf8() })
        assertEquals(
            listOf(0L, 0L),
            h.partRows().map { it.deleted },
            "a fetched part is present again, so the sweep dates it from the new file",
        )

        // The second opening is offline: the fetch was kept as the local copy.
        h.audio.load(h.record(), listOf(h.uploadOutput()))
        assertEquals(2, h.downloads)
    }

    @Test
    fun `bytes that do not hash to the row leave the part missing and write nothing`() = runBlocking {
        val h = harness(corruptPart = 1)
        h.purge()

        val audio = h.audio.load(h.record(), listOf(h.uploadOutput()))

        assertEquals(listOf(h.path(2, Track.MONO)), audio.paths)
        assertEquals(listOf(1), audio.missing)
        assertFalse(h.fs.exists(h.path(1, Track.MONO)), "nothing is written under a part's name")
        assertEquals(listOf(1L, 0L), h.partRows().map { it.deleted })
        assertEquals(listOf("audio.download.mismatch", "audio.download"), h.logger.events)
    }

    @Test
    fun `a part no upload ever named stays missing`() = runBlocking {
        val h = harness()
        h.purge()

        val audio = h.audio.load(h.record(), listOf(h.uploadOutput(skip = listOf(1))))

        assertEquals(listOf(h.path(2, Track.MONO)), audio.paths)
        assertEquals(listOf(1), audio.missing)
        assertEquals(1, h.downloads, "nothing was asked of Drive for the part it never had")
    }

    /**
     * The sweep's purge and the restore of a fetched part run under the repository's one lock, so
     * a purge is either wholly before the restore or wholly after it. Before: it takes the old
     * file and the restore writes the new one back. After: the row the restore marked present is
     * not one a claim named, so the file that has just landed stays.
     */
    @Test
    fun `a purge on either side of a restore leaves the part it wrote alone`() = runBlocking {
        val h = harness()
        val part = h.meta.parts.first()
        val path = h.path(part.part, part.track)
        h.purge()

        val temp = h.temps / "${part.file}.tmp"
        h.fs.createDirectories(h.temps)
        h.fs.write(temp) { writeUtf8(SEEDED_AUDIO) }
        val restored = h.recordings.restorePart(h.meta.recordingId, part.part, part.track, temp)

        assertEquals(path, restored)
        assertTrue(h.fs.exists(path), "the purge that ran first took the file the restore wrote")
        assertEquals(
            listOf(0L, 1L),
            h.partRows().map { it.deleted },
            "only the restored row is present again",
        )

        h.recordings.purgeParts(h.meta.recordingId)

        assertTrue(h.fs.exists(path), "a purge that ran after the restore took the fetched part")
        assertFalse(h.fs.exists(temp))
    }

    /**
     * The other order of the same lock: the recording is deleted while the part is downloading, so
     * the bytes that land afterwards are dropped rather than re-creating a directory and a file
     * that nothing names any more. The temp they were written to is beside the recordings, so the
     * delete never had it open in the first place — and the restore drops it.
     */
    @Test
    fun `a recording deleted while a part is in flight is not re-created by it`() = runBlocking {
        val h = harness()
        h.purge()
        val record = h.record()
        h.recordings.delete(record.id)

        val audio = h.audio.load(record, listOf(h.uploadOutput()))

        assertEquals(emptyList(), audio.paths)
        assertEquals(listOf(1, 2), audio.missing)
        assertFalse(h.fs.exists(h.dir), "the fetch re-created the directory of a deleted recording")
        assertEquals(emptyList(), h.fs.list(h.temps), "the temp of a fetch that found nothing to restore")
        assertEquals(
            listOf("rec.delete", "audio.download.gone", "audio.download.gone"),
            h.logger.events,
            "nothing was written under a part's name",
        )
    }

    /**
     * `RecordingRepository.delete` takes the recording's whole directory: a temp written inside it
     * would be deleted out from under the download (and on Windows would keep the directory from
     * going at all). It goes beside the recordings instead, where no delete reaches.
     */
    @Test
    fun `a fetch writes its temp outside the recording's own directory`() = runBlocking {
        val h = harness()
        h.purge()

        h.audio.load(h.record(), listOf(h.uploadOutput()))

        assertTrue(h.fs.list(h.dir).none { it.name.endsWith(".tmp") }, "a temp inside the recording")
        assertEquals(emptyList(), h.fs.list(h.temps), "the temps are renamed away, not left behind")
    }

    private suspend fun harness(
        tracks: List<Track> = listOf(Track.MONO),
        partCount: Int = 2,
        corruptPart: Int? = null,
    ): AudioHarness = AudioHarness(tracks, partCount, corruptPart).also { it.seed() }
}

/** A finalized recording on a fake disk, its parts already in a [FakeDrive] folder. */
private class AudioHarness(
    val tracks: List<Track>,
    val partCount: Int,
    /** The part Drive holds something else for — a file that cannot verify against its row. */
    private val corruptPart: Int? = null,
) {
    val drive = FakeDrive()
    val clock = FakeClock()
    val fs = FakeFileSystem(clock)
    val logger = FakeLogger()
    val deps = testDeps(
        clock = clock,
        fileSystem = fs,
        logger = logger,
        tokenProvider = ScriptedTokenProvider(),
        transport = mockTransport(drive, fs),
    )
    val db = inMemoryDatabase()
    val recordings = RecordingRepository(db, deps)
    val audio = AudioParts(DriveApi(deps), recordings, deps)

    val base: String = MetaWriter.baseName(testMeta())
    val dir: Path = "/data/recordings/$base".toPath()

    /** Where a fetch writes its temp: beside the recordings, not inside one of them. */
    val temps: Path = "/data/recordings".toPath() / AudioParts.TEMP_DIR

    val meta: RecordingMeta = testMeta().copy(
        tracks = tracks,
        parts = (1..partCount).flatMap { number ->
            tracks.map { track ->
                Part(
                    part = number,
                    track = track,
                    file = MetaWriter.partFileName(base, number, track),
                    bytes = SEEDED_AUDIO.length.toLong(),
                    sha256 = SEEDED_AUDIO_SHA256,
                    startOffsetSec = (number - 1) * 900.0,
                    durationSec = 900.0,
                )
            }
        },
        status = RecordingStatus.FINALIZED,
    )

    /** The Drive file id of each part, by file name — what a `drive.upload` output carries. */
    private val fileIds = mutableMapOf<String, String>()

    suspend fun seed() {
        fs.createDirectories(dir)
        val folderId = drive.put(base, "root", ByteArray(0), FakeDrive.FOLDER_MIME)
        meta.parts.forEach { part ->
            fs.write(dir / part.file) { writeUtf8(SEEDED_AUDIO) }
            val uploaded = if (part.part == corruptPart) CORRUPT else SEEDED_AUDIO
            fileIds[part.file] = drive.put(part.file, folderId, uploaded.encodeToByteArray())
        }
        recordings.create(meta, dir)
    }

    suspend fun record(): RecordingRecord = recordings.get(meta.recordingId)!!

    fun path(number: Int, track: Track): Path = dir / MetaWriter.partFileName(base, number, track)

    fun paths(track: Track): List<Path> = (1..partCount).map { path(it, track) }

    /** What the retention sweep leaves behind: every row claimed, every part file gone. */
    fun purge() {
        db.recQueries.markPartsDeleted(meta.recordingId)
        meta.parts.forEach { fs.delete(dir / it.file) }
    }

    fun partRows() = db.recQueries.selectPartsByRecording(meta.recordingId).executeAsList()

    /** The `drive.upload` output the parts were uploaded under; [skip] names one it never got. */
    fun uploadOutput(skip: List<Int> = emptyList()): JsonObject = buildJsonObject {
        putJsonArray("files") {
            meta.parts.filter { it.part !in skip }.forEach { part ->
                add(
                    buildJsonObject {
                        put("part", part.part)
                        put("track", part.track.wire)
                        put("name", part.file)
                        put("sha256", part.sha256)
                        put("fileId", fileIds.getValue(part.file))
                    },
                )
            }
        }
    }

    val downloads: Int get() = drive.requests.count { it.query["alt"] == "media" }

    private companion object {
        const val CORRUPT = "not the audio"
    }
}
