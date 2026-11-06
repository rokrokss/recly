package recly.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import recly.core.notes.NotesStage
import recly.core.processing.ProcessingSettingsState
import recly.core.testing.CoreFixture
import recly.core.transcribe.TranscriptAvailability

/**
 * docs/01: a Swift `for await` over a flow that throws ends the app, so the flows the shells observe survive a read
 * that fails and read again at the next change. A table renamed away is the failure; renamed back, the next write wakes it.
 */
class ObserveReadFailureTest {
    private val f = CoreFixture()

    private fun away(table: String) = f.driver.execute(null, "ALTER TABLE $table RENAME TO ${table}_away", 0)

    private fun back(table: String) = f.driver.execute(null, "ALTER TABLE ${table}_away RENAME TO $table", 0)

    private class Watching<T>(private val job: Job, private val values: Channel<T>) {
        suspend fun next(): T = withTimeout(5_000) { values.receive() }

        suspend fun nothing() = assertNull(withTimeoutOrNull(200) { values.receive() })

        fun stop() = job.cancel()
    }

    private fun <T> CoroutineScope.watch(flow: Flow<T>): Watching<T> {
        val values = Channel<T>(Channel.UNLIMITED)
        return Watching(launch(Dispatchers.Unconfined) { flow.collect { values.send(it) } }, values)
    }

    @Test
    fun `a queue that will not be read emits nothing, and the next change reads again`() = runBlocking {
        val meta = f.record()
        away("job")
        val jobs = watch(f.core.jobs.observe())
        try {
            jobs.nothing()
            assertTrue("job.observe.read.failed" in f.logger.events)
            assertFails { f.core.jobs.list() }

            back("job")
            f.core.enqueue(meta.recordingId)
            assertEquals(listOf(meta.recordingId), jobs.next().map { it.recordingId })
        } finally { jobs.stop() }
    }

    @Test
    fun `steps that will not be read emit nothing, and the next change reads again`() = runBlocking {
        val meta = f.record()
        away("step_run")
        val steps = watch(f.core.jobs.observeSteps(meta.recordingId))
        try {
            steps.nothing()
            assertTrue("job.observe.read.failed" in f.logger.events)

            back("step_run")
            f.core.enqueue(meta.recordingId)
            assertTrue(steps.next().isNotEmpty())
        } finally { steps.stop() }
    }

    @Test
    fun `the recordings wake a collector without reading, so a table that will not be read cannot end them`() = runBlocking<Unit> {
        away("recording")
        assertEquals(Unit, withTimeout(5_000) { f.core.recordings.observe().first() })
        assertFails { f.core.recordings.ids() }
    }

    @Test
    fun `audio that will not be read emits nothing, not a recording that is gone, and the next change reads again`() = runBlocking {
        val meta = f.record()
        away("recording")
        val audio = watch(f.core.recordings.observeAudio(meta.recordingId))
        try {
            audio.nothing()
            assertEquals(listOf(mapOf<String, Any?>("recordingId" to meta.recordingId)), f.logger.fieldsOf("rec.observe.read.failed"))

            back("recording")
            f.core.recordings.rename(meta.recordingId, "Back")
            assertEquals(meta.recordingId, audio.next()?.id)
        } finally { audio.stop() }
    }

    @Test
    fun `results that will not be read are unavailable, and the next change reads again`() = runBlocking {
        val meta = f.record()
        away("recording")
        val results = watch(f.core.observeResults(meta.recordingId))
        try {
            assertEquals(TranscriptAvailability.UNAVAILABLE, results.next().availability)

            back("recording")
            f.core.recordings.rename(meta.recordingId, "Back")
            assertNotEquals(TranscriptAvailability.UNAVAILABLE, results.next().availability)
        } finally { results.stop() }
    }

    /** `combine` would wait for a first value from the steps, and show nothing and follow nothing without one. */
    @Test
    fun `one source that will not be read holds back neither the first result nor the other sources`() = runBlocking {
        val meta = f.record()
        away("step_run")
        val results = watch(f.core.observeResults(meta.recordingId))
        try {
            assertEquals(TranscriptAvailability.NOT_REQUESTED, results.next().availability)

            f.driver.execute(null, "DELETE FROM recording", 0)
            f.driver.notifyListeners("recording")
            assertEquals(TranscriptAvailability.UNAVAILABLE, results.next().availability)
        } finally { results.stop() }
    }

    @Test
    fun `settings that will not be read emit nothing, not that there are none, and the next change reads again`() = runBlocking {
        f.core.processingSettings.initialize()
        away("sync_state")
        val settings = watch(f.core.processingSettings.observe())
        try {
            settings.nothing()
            assertTrue("processing.observe.read.failed" in f.logger.events)

            back("sync_state")
            f.driver.notifyListeners("sync_state")
            assertTrue(settings.next() is ProcessingSettingsState.Ready)
        } finally { settings.stop() }
    }

    @Test
    fun `notes reached through results that will not be read are none`() = runBlocking {
        val meta = f.record()
        away("recording")
        assertEquals(NotesStage.NONE, withTimeout(5_000) { f.core.notes.observe(meta.recordingId).first() }.stage)
    }
}
