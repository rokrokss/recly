@file:OptIn(ExperimentalTime::class)

package recly.core.transcribe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.runBlocking
import recly.core.db.RecDatabase
import recly.core.job.JobStatus
import recly.core.job.JobStore
import recly.core.job.Retention
import recly.core.model.recJson
import recly.core.processing.ProcessingPlan
import recly.core.processing.ProcessingSaveResult
import recly.core.processing.ProcessingSettingsState
import recly.core.processing.TranscriptionMode
import recly.core.recording.MetaWriter
import recly.core.testing.CoreFixture

/** docs/10 "Re-transcription". */
class RetranscriptionTest {
    private val f = CoreFixture()

    private suspend fun settings(change: (recly.core.processing.ProcessingSettings) -> recly.core.processing.ProcessingSettings) {
        val current = assertIs<ProcessingSettingsState.Ready>(f.core.processingSettings.read()).document
        assertIs<ProcessingSaveResult.Saved>(f.core.processingSettings.save(change(current.settings), current.revision))
    }

    private fun onDrive(name: String): FakeEntry {
        val entry = assertNotNull(f.drive.byName(name), name)
        return FakeEntry(entry.content.decodeToString(), entry.appProperties)
    }

    private class FakeEntry(val text: String, val properties: Map<String, String>)

    @Test
    fun `a finished recording is transcribed again with the settings as they are now`() = runBlocking {
        val meta = f.recordAndRun(title = "Weekly")
        val base = MetaWriter.baseName(meta)
        assertEquals("transcribed", onDrive(TranscribeRunner.textFileName(base)).properties["reclyTranscript"])
        val first = f.core.results(meta.recordingId).transcript!!
        settings { it.copy(transcription = it.transcription.copy(vocabulary = listOf("Recly", "Minsu"))) }
        f.engine!!.text = "again"

        val started = assertIs<RetranscribeResult.Started>(f.core.retranscribe(meta.recordingId))

        val job = f.core.jobs.list().single { it.id == started.jobId }
        assertTrue(job.retranscription)
        assertEquals(ProcessingPlan.RETRANSCRIBE_ID, job.workflowId)
        assertEquals(listOf("transcribe", "publish"), job.workflow!!.steps.map { it.id }, "nothing is uploaded again")
        assertEquals(first, f.core.results(meta.recordingId).transcript, "the old transcript stays until the new one is in")
        assertEquals("transcribe", f.drive.byName(base)!!.appProperties["pending"], "other devices are told at once")

        f.drain()

        assertEquals(JobStatus.DONE, f.core.jobs.list().single { it.id == started.jobId }.status)
        assertEquals(listOf("Recly", "Minsu"), f.engine.requests.last().vocabulary)
        val second = f.core.results(meta.recordingId).transcript!!
        assertEquals("again 1", second.segments.first().text)
        val json = onDrive(TranscribeRunner.jsonFileName(base))
        assertEquals(second, recJson.decodeFromString<Transcript>(json.text))
        assertEquals("transcribed", json.properties["reclyTranscript"])
        assertEquals(second.createdAt, f.drive.byName(base)!!.appProperties["transcriptAt"])
        assertEquals("", f.drive.byName(base)!!.appProperties["pending"], "and told when it is over")
    }

    @Test
    fun `a recording whose audio the sweep took is fetched back first, and the sweep waits for the job`() = runBlocking {
        val meta = f.recordAndRun()
        val part = f.dirOf(meta) / meta.parts.single().file
        f.clock.advance(Retention.WINDOW)
        f.core.runDueJobs(f.clock.now())
        assertFalse(f.fs.exists(part), "swept")

        assertIs<RetranscribeResult.Started>(f.core.retranscribe(meta.recordingId))
        assertTrue(f.fs.exists(part), "fetched back from Drive")
        f.clock.advance(Retention.WINDOW * 2)
        Retention(f.deps, JobStore(RecDatabase(f.driver), f.deps), f.core.recordings).sweep(f.clock.now())
        assertTrue(f.fs.exists(part), "a job that has not run yet keeps the parts")
        f.drain()

        assertTrue(f.core.jobs.list().all { it.status == JobStatus.DONE })
        assertEquals("hello 1", f.core.results(meta.recordingId).transcript!!.segments.first().text)
    }

    @Test
    fun `another device's recording is transcribed here and published into its folder`() = runBlocking {
        val other = f.otherDevice("01J9PH0NE10000000000000000")
        f.core.pullRemoteRecordings(force = true)
        f.engine!!.text = "here"

        assertIs<RetranscribeResult.Started>(f.core.retranscribe(other.recordingId))
        f.drain()

        val base = MetaWriter.baseName(other.meta)
        val published = recJson.decodeFromString<Transcript>(onDrive(TranscribeRunner.jsonFileName(base)).text)
        assertEquals("here 1", published.segments.first().text)
        assertEquals(other.folderId, f.drive.files.entries.single { it.value.name == TranscribeRunner.jsonFileName(base) }.value.parents.single())
        assertEquals("here 1", f.core.results(other.recordingId).transcript!!.segments.first().text)
        assertTrue(f.core.recordings.get(other.recordingId)!!.remote, "still another device's recording")
    }

    @Test
    fun `nothing starts while a job of the recording has not settled`() = runBlocking {
        val meta = f.record()
        f.core.enqueue(meta.recordingId)
        assertEquals(RetranscribeResult.Unsupported, f.core.retranscribe(meta.recordingId), "never uploaded: no folder")
        f.drain()

        assertIs<RetranscribeResult.Started>(f.core.retranscribe(meta.recordingId))

        assertEquals(RetranscribeResult.Busy, f.core.retranscribe(meta.recordingId))
        assertEquals(RetranscribeResult.Unsupported, f.core.retranscribe("01J9N0THERE000000000000000"))
    }

    @Test
    fun `with transcription off there is nothing to run`() = runBlocking {
        val meta = f.recordAndRun()
        settings { it.copy(transcription = it.transcription.copy(mode = TranscriptionMode.OFF)) }

        assertEquals(RetranscribeResult.NoTranscriptionConfigured, f.core.retranscribe(meta.recordingId))
    }

    @Test
    fun `a second request replaces the first one once it has settled`() = runBlocking {
        val meta = f.recordAndRun()
        val first = assertIs<RetranscribeResult.Started>(f.core.retranscribe(meta.recordingId))
        f.drain()

        val second = assertIs<RetranscribeResult.Started>(f.core.retranscribe(meta.recordingId))

        val ids = f.core.jobs.list().filter { it.recordingId == meta.recordingId }.map { it.id }
        assertEquals(2, ids.size, "the recording's own job and one re-transcription")
        assertFalse(first.jobId in ids)
        assertTrue(second.jobId in ids)
    }
}
