@file:OptIn(kotlin.time.ExperimentalTime::class)
package recly.core.transcribe

import kotlin.test.*
import kotlinx.coroutines.runBlocking
import recly.core.job.*
import recly.core.model.*
import recly.core.processing.*
import recly.core.recording.RecordingRecord
import recly.core.testing.*

class FixedTranscriptPublicationTest {
    @Test fun `publication failure retries cached result without resubmitting audio`() = runBlocking<Unit> {
        val h = TranscribeHarness()
        val settings = ProcessingSettings(transcription = ProcessingTranscription(mode = TranscriptionMode.EXTERNAL,
            external = ExternalTranscription("assemblyai", STT_KEY)))
        val workflow = ProcessingPlan.compile(ProcessingSettingsDocument(revision = 1, updatedAt = START.isoUtc(), updatedBy = "test", settings = settings))
        val job = Job("01J9JOB0000000000000000000", h.recordingId, workflow.id, workflow, JobStatus.RUNNING, START, START, null)
        fun context(step: Step, prior: Map<String, StepOutput>) = StepContext(job, workflow, STEP_RUN_ID, step,
            RecordingRecord(h.recordingId, h.meta, h.dir), prior, h.state, { h.state = it }, {}, h.deps)
        val prior = mapOf("upload" to h.uploadOutput())
        val transcribe = workflow.steps[1]
        var outcome = h.transcribe.run(context(transcribe, prior))
        while (outcome is StepOutcome.Waiting) outcome = h.transcribe.run(context(transcribe, prior))
        val computed = assertIs<StepOutcome.Done>(outcome).output
        assertEquals(1, h.submits())
        assertNull(h.driveContent(TranscribeRunner.jsonFileName(h.base)))
        val publisher = TranscriptPublishRunner(h.deps)
        val publishContext = context(workflow.steps[2], prior + ("transcribe" to computed))
        h.drive.failNext(500, times = 10) { it.method == "POST" }
        assertFails { publisher.run(publishContext) }
        h.drive.clearFaults()
        assertIs<StepOutcome.Done>(publisher.run(publishContext))
        assertNotNull(h.driveContent(TranscribeRunner.jsonFileName(h.base)))
        // The compute step remains cached even after credentials are removed.
        h.secrets.delete("secrets", STT_KEY)
        assertIs<StepOutcome.Done>(h.transcribe.run(context(transcribe, prior)))
        assertEquals(1, h.submits())
    }
}
