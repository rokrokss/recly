@file:OptIn(ExperimentalTime::class)

package recly.core.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import recly.core.job.JobStatus
import recly.core.message.CoreMessage
import recly.core.message.CoreMessageRef
import recly.core.model.RecordingStatus
import recly.core.model.Source
import recly.core.testing.CoreFixture
import recly.core.testing.testMeta

/** docs/03 "Naming rules" (`source: import`): a picked file, whole or not at all. */
class AudioImportTest {
    private val f = CoreFixture()
    private val fileDate = Instant.parse("2026-08-20T09:30:00.000Z")

    /** Writes [count] parts of [content] into the directory it is given, unless told to fail. */
    private inner class FakeImporter(
        private val count: Int = 2,
        private val answer: TranscodeResult? = null,
        private val failure: Throwable? = null,
        private val hold: CompletableDeferred<Unit>? = null,
        private val content: String = "audio",
    ) : AudioImporter {
        val started = CompletableDeferred<String>()

        override suspend fun transcode(sourcePath: String, outDir: String, segmentSec: Int): TranscodeResult {
            assertEquals(300, segmentSec)
            started.complete(outDir.toPath().name)
            hold?.await()
            failure?.let { throw it }
            answer?.let { return it }
            return TranscodeResult.Done(
                (1..count).map { index ->
                    val name = "out-$index.m4a"
                    f.fs.write(outDir.toPath() / name) { writeUtf8(content) }
                    ImportedPart(name, if (index < count) 300.0 else 61.5)
                },
            )
        }
    }

    @Test
    fun `a picked file becomes a finished recording that runs the fixed plan`() = runBlocking<Unit> {
        val result = f.core.importAudio("/picked/Interview.final.mp4", "Interview.final.mp4", fileDate, FakeImporter())

        val id = assertIs<ImportResult.Imported>(result).recordingId
        val record = f.core.recordings.get(id)!!
        assertEquals(Source.IMPORT, record.meta.source)
        assertEquals(RecordingStatus.FINALIZED, record.meta.status)
        assertEquals("Interview.final", record.meta.title)
        assertEquals("2026-08-20T09:30:00.000Z", record.meta.startedAt)
        assertEquals(fileDate, recly.core.ids.Ulid.timestamp(id), "the id dates the file, as a recording's dates its start")
        assertEquals(361.5, record.meta.durationSec)
        val base = MetaWriter.baseName(record.meta)
        assertTrue(base.startsWith("20260820T093000Z_import_"))
        assertEquals(listOf("${base}_p001_mono.m4a", "${base}_p002_mono.m4a"), record.meta.parts.map { it.file })
        assertEquals(listOf(0.0, 300.0), record.meta.parts.map { it.startOffsetSec })
        assertEquals(PartHasher.sha256("audio".encodeToByteArray()), record.meta.parts.first().sha256)
        assertTrue(record.meta.parts.all { f.fs.exists(record.dir / it.file) })
        assertFalse(f.fs.exists("/data/import-tmp/$id".toPath()))

        f.drain()

        assertEquals(JobStatus.DONE, f.core.jobs.list().single { it.recordingId == id && !it.retranscription }.status)
        assertTrue(f.drive.byName("${base}_p002_mono.m4a") != null, "uploaded like any recording")
        assertTrue(f.drive.byName(MetaWriter.metaFileName(base)) != null)
    }

    @Test
    fun `files dated in the same second each get a recording of their own`() = runBlocking<Unit> {
        // Files copied together carry one date to the second, and the base name keeps only that second and the
        // id's first eight characters: two imports of that second shared one directory and overwrote each other.
        val dates = listOf(fileDate, fileDate, fileDate + 400.milliseconds)
        val ids = dates.mapIndexed { index, date ->
            val imported = f.core.importAudio("/picked/$index.m4a", "$index.m4a", date, FakeImporter(count = 1, content = "file $index"))
            assertIs<ImportResult.Imported>(imported).recordingId
        }

        val records = ids.map { f.core.recordings.get(it)!! }
        val bases = records.map { MetaWriter.baseName(it.meta) }
        assertEquals(3, bases.toSet().size, "one base name each: $bases")
        assertEquals(3, records.map { it.dir }.toSet().size)
        assertEquals(
            listOf("2026-08-20T09:30:00.000Z", "2026-08-20T09:30:01.000Z", "2026-08-20T09:30:02.400Z"),
            records.map { it.meta.startedAt },
            "the first keeps the file's date; each one after it starts a second later until its name is free",
        )
        records.forEachIndexed { index, record ->
            assertEquals(Instant.parse(record.meta.startedAt), recly.core.ids.Ulid.timestamp(record.meta.recordingId), "the id carries the start")
            assertEquals("file $index", f.fs.read(record.dir / record.meta.parts.single().file) { readUtf8() })
        }

        f.drain()

        bases.forEach { assertTrue(f.drive.byName("${it}_p001_mono.m4a") != null, "$it uploaded") }
    }

    @Test
    fun `the list shows the import while it is transcoded, and recovery leaves it alone`() = runBlocking<Unit> {
        val hold = CompletableDeferred<Unit>()
        val importer = FakeImporter(hold = hold)
        val running = async(Dispatchers.Default) { f.core.importAudio("/picked/a.wav", "a.wav", null, importer) }
        val id = withTimeout(5000) { importer.started.await() }

        val row = f.core.recordings.get(id)!!
        assertTrue(row.importing)
        assertEquals(emptyList(), row.meta.parts)
        assertFalse(f.core.dropAbandonedImport(id), "an import in progress is not recovery's")

        hold.complete(Unit)
        assertIs<ImportResult.Imported>(withTimeout(5000) { running.await() })
        assertFalse(f.core.recordings.get(id)!!.importing)
    }

    @Test
    fun `a file that cannot be read leaves nothing behind`() = runBlocking<Unit> {
        val importer = FakeImporter(failure = IllegalStateException("permission denied"))

        val failed = assertIs<ImportResult.Failed>(f.core.importAudio("/picked/x.m4a", "x.m4a", null, importer))

        val code = CoreMessageRef.parse(failed.reason)!!
        assertEquals(CoreMessage.IMPORT_UNREADABLE, code.message)
        assertEquals("permission denied", code.detail)
        val id = importer.started.await()
        assertNull(f.core.recordings.get(id))
        assertFalse(f.fs.exists("/data/import-tmp/$id".toPath()))
        assertEquals(emptyList(), f.core.jobs.list())
    }

    @Test
    fun `a file without audio says so`() = runBlocking<Unit> {
        val failed = f.core.importAudio("/picked/x.pdf", "x.pdf", null, FakeImporter(answer = TranscodeResult.Unsupported))

        assertEquals(CoreMessage.IMPORT_UNSUPPORTED.code(), assertIs<ImportResult.Failed>(failed).reason)
        assertEquals(emptyList(), f.core.recordings.list(10))
        assertEquals(
            CoreMessage.IMPORT_UNREADABLE.code(),
            assertIs<ImportResult.Failed>(f.core.importAudio("/picked/y", "y", null, FakeImporter(answer = TranscodeResult.Unreadable))).reason,
        )
        assertIs<ImportResult.Failed>(f.core.importAudio("/picked/z.m4a", "z.m4a", null, FakeImporter(count = 0)))
    }

    @Test
    fun `a cancelled import takes its row with it`() = runBlocking<Unit> {
        val importer = FakeImporter(hold = CompletableDeferred())
        val running = async(Dispatchers.Default) { f.core.importAudio("/picked/a.wav", "a.wav", null, importer) }
        val id = withTimeout(5000) { importer.started.await() }

        running.cancelAndJoin()

        assertNull(f.core.recordings.get(id))
        assertFalse(f.fs.exists("/data/import-tmp/$id".toPath()))
    }

    @Test
    fun `an import a killed process left is dropped, never finalized`() = runBlocking<Unit> {
        val meta = testMeta(recordingId = "01J9IMP0RT0000000000000000", source = Source.IMPORT, parts = emptyList())
        f.core.recordings.create(meta, f.dirOf(meta))

        assertTrue(f.core.dropAbandonedImport(meta.recordingId))

        assertNull(f.core.recordings.get(meta.recordingId))
        assertFalse(f.core.dropAbandonedImport(CoreFixture.ID), "not an import")
    }
}
