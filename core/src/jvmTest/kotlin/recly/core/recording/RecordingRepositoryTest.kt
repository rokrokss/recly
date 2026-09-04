@file:OptIn(ExperimentalTime::class)

package recly.core.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import recly.core.model.RecordingMeta
import recly.core.model.RecordingStatus
import recly.core.model.Track
import recly.core.model.recJson
import recly.core.testing.FakeLogger
import recly.core.testing.inMemoryDatabase
import recly.core.testing.testDeps
import recly.core.testing.testMeta
import recly.core.testing.testPart

class RecordingRepositoryTest {
    private val fs = FakeFileSystem()
    private val logger = FakeLogger()
    private val deps = testDeps(fileSystem = fs, logger = logger)
    private val db = inMemoryDatabase()
    private val repository = RecordingRepository(db, deps)
    private val meta = testMeta()
    private val dir = "/data/recordings/${MetaWriter.baseName(meta)}".toPath()

    private fun readMeta(): RecordingMeta =
        recJson.decodeFromString(fs.read(dir / MetaWriter.metaFileName(MetaWriter.baseName(meta))) { readUtf8() })

    @Test
    fun namesFilesAsTheSpecRequires() {
        val base = MetaWriter.baseName(testMeta(startedAt = "2026-08-26T01:00:00.000Z"))
        assertEquals("20260826T010000Z_desktop_01J9ABCD", base)
        assertEquals("${base}_p003_sys.m4a", MetaWriter.partFileName(base, 3, Track.SYS))
        assertEquals("$base.meta.json", MetaWriter.metaFileName(base))
    }

    @Test
    fun createAddPartsFinalizeThenPurge() = runBlocking {
        repository.create(meta, dir)
        assertEquals(RecordingStatus.RECORDING, readMeta().status)

        (1..3).forEach { repository.addPart(meta.recordingId, testPart(meta, it)) }
        assertEquals(3, readMeta().parts.size)

        val finalized = repository.finalize(
            meta.recordingId,
            endedAt = Instant.parse("2026-08-26T01:45:00.000Z"),
            durationSec = 2700.0,
            title = "주간 회의",
        )
        val onDisk = readMeta()
        assertEquals(RecordingStatus.FINALIZED, onDisk.status)
        assertEquals(3, onDisk.parts.size)
        assertEquals("2026-08-26T01:45:00.000Z", onDisk.endedAt)
        assertEquals(2700.0, onDisk.durationSec)
        assertEquals("주간 회의", onDisk.title)
        assertEquals(onDisk, finalized.meta)
        assertEquals(onDisk, repository.get(meta.recordingId)?.meta)
        assertTrue("rec.finalize" in logger.events)

        // The audio the parts describe, as the recorder would have written it.
        onDisk.parts.forEach { fs.write(dir / it.file) { writeUtf8("audio") } }

        // A purge is claimed in the DB first (JobStore.claimPurge); the repository only deletes
        // the files of the rows that claim marked.
        db.recQueries.markPartsDeleted(meta.recordingId)
        repository.purgeParts(meta.recordingId)
        onDisk.parts.forEach { assertFalse(fs.exists(dir / it.file), "${it.file} survived the purge") }
        assertEquals(3, readMeta().parts.size, "meta.json must keep describing the purged parts")
        assertEquals(RecordingStatus.FINALIZED, repository.get(meta.recordingId)?.meta?.status)
    }

    @Test
    fun purgeIsIdempotentAndToleratesMissingFiles() = runBlocking {
        repository.create(meta, dir)
        val part = testPart(meta, 1)
        repository.addPart(meta.recordingId, part)
        db.recQueries.markPartsDeleted(meta.recordingId)

        repository.purgeParts(meta.recordingId) // the file was never written in the first place
        fs.write(dir / part.file) { writeUtf8("audio") }
        repository.purgeParts(meta.recordingId)
        repository.purgeParts(meta.recordingId)

        assertFalse(fs.exists(dir / part.file))
        assertEquals(1, repository.get(meta.recordingId)?.meta?.parts?.size)
    }

    @Test
    fun purgeLeavesUnclaimedPartsAlone() = runBlocking {
        repository.create(meta, dir)
        val part = testPart(meta, 1)
        repository.addPart(meta.recordingId, part)
        fs.write(dir / part.file) { writeUtf8("audio") }

        repository.purgeParts(meta.recordingId)

        assertTrue(fs.exists(dir / part.file), "a part nobody claimed must not be deleted")
    }

    @Test
    fun concurrentAddPartsAllLandInBothTheRowsAndTheFile() = runBlocking {
        // A real dispatcher, unlike the rest of the suite: the recorder finishes segments on
        // whatever thread it likes, and addPart is a read-modify-write of meta_json and meta.json.
        val threaded = FakeFileSystem()
        val repo = RecordingRepository(inMemoryDatabase(), testDeps(fileSystem = threaded, io = Dispatchers.Default))
        repo.create(meta, dir)

        (1..3).map { number ->
            async(Dispatchers.Default) { repo.addPart(meta.recordingId, testPart(meta, number)) }
        }.awaitAll()

        assertEquals(listOf(1, 2, 3), repo.get(meta.recordingId)!!.meta.parts.map { it.part })
        val onDisk: RecordingMeta = recJson.decodeFromString(
            threaded.read(dir / MetaWriter.metaFileName(MetaWriter.baseName(meta))) { readUtf8() },
        )
        assertEquals(listOf(1, 2, 3), onDisk.parts.map { it.part })
        assertTrue(
            threaded.list(dir).none { it.name.endsWith(".tmp") },
            "a temp file was left behind: ${threaded.list(dir)}",
        )
        threaded.checkNoOpenFiles()
    }

    @Test
    fun listsNewestFirstAndReportsUnknownIds() = runBlocking {
        val older = testMeta(recordingId = "01J9AAAAAAAAAAAAAAAAAAAAAA", startedAt = "2026-08-25T01:00:00.000Z")
        repository.create(older, "/data/recordings/${MetaWriter.baseName(older)}".toPath())
        repository.create(meta, dir)
        assertEquals(
            listOf(meta.recordingId, older.recordingId),
            repository.list(10).map { it.id },
        )
        assertEquals(1, repository.list(1).size)
        assertNull(repository.get("01J9ZZZZZZZZZZZZZZZZZZZZZZ"))
    }
}
