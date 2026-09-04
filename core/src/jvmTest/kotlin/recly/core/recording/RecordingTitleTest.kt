@file:OptIn(ExperimentalTime::class)

package recly.core.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import recly.core.job.JobStatus
import recly.core.model.RecordingMeta
import recly.core.model.recJson
import recly.core.testing.inMemoryDatabase
import recly.core.testing.testDeps
import recly.core.testing.testMeta

/**
 * docs/03 lets the mobile user name a recording after it has stopped, which is after [finalize]
 * has already written the meta — so the title is its own write, with its own window.
 */
class RecordingTitleTest {
    private val fs = FakeFileSystem()
    private val deps = testDeps(fileSystem = fs)
    private val db = inMemoryDatabase()
    private val repository = RecordingRepository(db, deps)
    private val meta = testMeta()
    private val dir = "/data/recordings/${MetaWriter.baseName(meta)}".toPath()

    private fun onDisk(): RecordingMeta =
        recJson.decodeFromString(fs.read(dir / MetaWriter.metaFileName(MetaWriter.baseName(meta))) { readUtf8() })

    private suspend fun finalized() {
        repository.create(meta, dir)
        repository.finalize(meta.recordingId, Instant.parse("2026-08-26T01:15:00.000Z"), 900.0)
    }

    private fun job(status: JobStatus) {
        db.recQueries.insertJob(
            "01J9JOB0000000000000000000",
            meta.recordingId,
            "01J9ABCDEF0123456789ABCDEF",
            "{}",
            status.name,
            "2026-08-26T01:15:00.000Z",
            "2026-08-26T01:15:00.000Z",
            null,
        )
    }

    @Test
    fun `the title lands in the row and in meta json`() = runBlocking {
        finalized()
        assertNull(onDisk().title)

        assertTrue(repository.updateTitle(meta.recordingId, "주간 회의"))

        assertEquals("주간 회의", onDisk().title)
        assertEquals("주간 회의", repository.get(meta.recordingId)?.meta?.title)
        assertEquals(
            "주간 회의",
            db.recQueries.selectRecordingById(meta.recordingId).executeAsOne().title,
        )
    }

    @Test
    fun `a recording still being written is not titled this way`() = runBlocking {
        repository.create(meta, dir)

        assertFalse(repository.updateTitle(meta.recordingId, "너무 이름"))
        assertNull(onDisk().title)
    }

    @Test
    fun `a job that is already done has read the meta, so the title is refused`() = runBlocking {
        finalized()
        job(JobStatus.DONE)

        assertFalse(repository.updateTitle(meta.recordingId, "늦은 제목"))
        assertNull(onDisk().title)
    }

    @Test
    fun `a running job may be uploading the meta right now, so the title is refused`() = runBlocking {
        finalized()
        job(JobStatus.RUNNING)

        assertFalse(repository.updateTitle(meta.recordingId, "늦은 제목"))
        assertNull(onDisk().title)
    }

    @Test
    fun `a queued job has not read anything yet, so the title still applies`() = runBlocking {
        finalized()
        job(JobStatus.PENDING)

        assertTrue(repository.updateTitle(meta.recordingId, "대기 중 제목"))
        assertEquals("대기 중 제목", onDisk().title)
    }

    @Test
    fun `the participant count the stop dialog asked for lands in the meta context`() = runBlocking {
        finalized()

        assertTrue(repository.updateTitle(meta.recordingId, "주간 회의", participants = 4))

        // docs/03 `context.participants`, docs/08's speaker hint: one write, both answers.
        assertEquals(4, onDisk().context?.participants)
        assertEquals("주간 회의", onDisk().title)
    }

    @Test
    fun `no title and no count is the dialog skipped, and nothing is erased`() = runBlocking {
        finalized()
        repository.updateTitle(meta.recordingId, "주간 회의", participants = 3)

        assertTrue(repository.updateTitle(meta.recordingId, null, null))

        assertEquals("주간 회의", onDisk().title, "'모름' and 건너뛰기 leave what is there")
        assertEquals(3, onDisk().context?.participants)
    }

    @Test
    fun `an unknown recording is a false, not a throw`() = runBlocking {
        assertFalse(repository.updateTitle("01J9NOPE000000000000000000", "제목"))
    }

    @Test
    fun `jobStatuses is what a recovery reads to tell a forgotten recording from a settled one`() = runBlocking {
        finalized()
        assertEquals(emptyList(), repository.jobStatuses(meta.recordingId))

        job(JobStatus.DONE)

        assertEquals(listOf(JobStatus.DONE), repository.jobStatuses(meta.recordingId))
    }
}
