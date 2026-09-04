@file:OptIn(ExperimentalTime::class)

package recly.core.job

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.runBlocking
import recly.core.recording.AudioParts
import recly.core.testing.SEEDED_AUDIO
import recly.core.testing.driveStep

/**
 * ADR-017 as the product decision of 2026-09-03 leaves it: once the upload has succeeded the local
 * parts are a cache with a fixed [Retention.WINDOW] on it, taken by the sweep at the end of a
 * `runDueJobs` pass and not by the job that finished. An upload that never succeeded still keeps
 * its parts forever.
 */
class RetentionTest {
    private fun uploader() = ScriptedRunner("drive.upload") { ctx, _ -> uploadOutput(ctx) }

    @Test
    fun `a DONE job with a successful upload does not purge at completion`() = runBlocking {
        val f = Fixture(listOf(uploader()))
        val recording = f.seed()
        val jobId = f.enqueue(recording, driveStep("up"))

        f.service.runDueJobs()

        assertEquals(JobStatus.DONE, f.store.get(jobId)!!.status)
        recording.meta.parts.forEach {
            assertTrue(f.fs.exists(recording.dir / it.file), "${it.file} went with the job")
        }
        assertEquals(
            listOf(0L, 0L),
            f.db.recQueries.selectPartsByRecording(recording.id).executeAsList().map { it.deleted },
            "nothing is claimed while the window is open",
        )
        assertEquals(listOf("within_window"), f.logger.fieldsOf("rec.retained").map { it["reason"] })
    }

    @Test
    fun `the sweep takes the parts a week on, and not a day earlier`() = runBlocking {
        val f = Fixture(listOf(uploader()))
        val recording = f.seed()
        f.enqueue(recording, driveStep("up"))
        f.service.runDueJobs()

        f.clock.advance(Retention.WINDOW - 1.days)
        f.service.runDueJobs()

        recording.meta.parts.forEach {
            assertTrue(f.fs.exists(recording.dir / it.file), "${it.file} went a day early")
        }

        f.clock.advance(1.days)
        f.service.runDueJobs()

        recording.meta.parts.forEach {
            assertFalse(f.fs.exists(recording.dir / it.file), "${it.file} survived the sweep")
        }
        assertEquals(
            listOf(1L, 1L),
            f.db.recQueries.selectPartsByRecording(recording.id).executeAsList().map { it.deleted },
        )
    }

    /**
     * The window runs from the upload as well as from the file: a recording whose audio has been
     * sitting here for a week but whose job only finished today has just been uploaded, and a
     * cache of it that lasts an hour is not what was decided.
     */
    @Test
    fun `a week-old file whose job has only just finished keeps the whole window`() = runBlocking {
        val f = Fixture(listOf(uploader()))
        val recording = f.seed()
        f.clock.advance(Retention.WINDOW)
        f.enqueue(recording, driveStep("up"))

        f.service.runDueJobs()

        recording.meta.parts.forEach { assertTrue(f.fs.exists(recording.dir / it.file)) }
        assertEquals(listOf("within_window"), f.logger.fieldsOf("rec.retained").map { it["reason"] })

        f.clock.advance(Retention.WINDOW)
        f.service.runDueJobs()

        recording.meta.parts.forEach { assertFalse(f.fs.exists(recording.dir / it.file)) }
    }

    /** docs/03 "로컬 저장": a FAILED job's parts are what `retry()` has left to work with. */
    @Test
    fun `a failed job keeps its parts however old they are`() = runBlocking {
        val failing = ScriptedRunner("drive.upload") { _, _ ->
            throw StepFailure(retryable = false, reason = "MISSING_SECRET")
        }
        val f = Fixture(listOf(failing))
        val recording = f.seed()
        val jobId = f.enqueue(recording, driveStep("up"))
        f.service.runDueJobs()
        assertEquals(JobStatus.FAILED, f.store.get(jobId)!!.status)

        f.clock.advance(Retention.WINDOW * 10)
        f.service.runDueJobs()

        recording.meta.parts.forEach {
            assertTrue(f.fs.exists(recording.dir / it.file), "${it.file} went with a FAILED job")
        }
        assertEquals(
            listOf("other_jobs_pending", "other_jobs_pending"),
            f.logger.fieldsOf("rec.retained").map { it["reason"] },
        )
    }

    @Test
    fun `a job that has not run yet keeps its parts however old they are`() = runBlocking {
        val f = Fixture(emptyList())
        val recording = f.seed()
        f.enqueue(recording, driveStep("up"))

        Retention(f.deps, f.store, f.recordings).sweep(f.clock.now() + Retention.WINDOW * 10)

        recording.meta.parts.forEach { assertTrue(f.fs.exists(recording.dir / it.file)) }
        assertEquals(listOf("other_jobs_pending"), f.logger.fieldsOf("rec.retained").map { it["reason"] })
    }

    /**
     * What `ReclyCore.audio` leaves behind when the detail screen fetches a part back from Drive:
     * the file is here again and its row says so, and the sweep dates the new window from it
     * rather than from the upload that happened a week ago.
     */
    @Test
    fun `a part fetched back after a purge gets the window over again`() = runBlocking {
        val f = Fixture(listOf(uploader()))
        val recording = f.seed()
        f.enqueue(recording, driveStep("up"))
        f.service.runDueJobs()
        f.clock.advance(Retention.WINDOW)
        f.service.runDueJobs()
        val part = recording.meta.parts.first()
        assertFalse(f.fs.exists(recording.dir / part.file))

        f.clock.advance(1.days)
        val temp = recording.dir.parent!! / AudioParts.TEMP_DIR / "${part.file}.tmp"
        f.fs.createDirectories(temp.parent!!)
        f.fs.write(temp) { writeUtf8(SEEDED_AUDIO) }
        f.recordings.restorePart(recording.id, part.part, part.track, temp)

        f.clock.advance(Retention.WINDOW - 1.days)
        f.service.runDueJobs()

        assertTrue(f.fs.exists(recording.dir / part.file), "the fetched part lost its window")

        f.clock.advance(1.days)
        f.service.runDueJobs()

        assertFalse(f.fs.exists(recording.dir / part.file), "and it is swept like any other")
    }
}
