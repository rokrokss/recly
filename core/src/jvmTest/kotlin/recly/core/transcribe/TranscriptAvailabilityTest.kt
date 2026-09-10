@file:OptIn(kotlin.time.ExperimentalTime::class)

package recly.core.transcribe

import kotlin.test.Test
import kotlin.test.assertEquals
import recly.core.job.Job
import recly.core.job.JobStatus
import recly.core.job.StepRun
import recly.core.job.StepStatus
import recly.core.model.RecordingStatus
import recly.core.model.Step
import recly.core.model.Workflow
import recly.core.recording.RecordingRecord
import recly.core.testing.START

class TranscriptAvailabilityTest {
    private val h = TranscribeHarness()
    private val record = RecordingRecord(h.recordingId, h.meta.copy(status = RecordingStatus.FINALIZED), h.dir)
    private val workflow = Workflow("workflow", "Test", "2026-08-26T02:00:00.000Z", steps = listOf(h.transcribeStep()))
    private val job = Job("job", record.id, workflow.id, workflow, JobStatus.RUNNING, START, START, null)
    private fun run(status: StepStatus) = StepRun("run", job.id, workflow.steps[0].id, 0, status, 0, null, null, null, null)
    private fun availability(status: JobStatus, step: StepStatus) =
        missingTranscriptAvailability(record, listOf(job.copy(status = status)), listOf(run(step)))

    @Test
    fun `not requested and unfinished and failed results are distinct`() {
        assertEquals(TranscriptAvailability.NOT_REQUESTED, missingTranscriptAvailability(record, emptyList(), emptyList()))
        assertEquals(TranscriptAvailability.PENDING, availability(JobStatus.RUNNING, StepStatus.RUNNING))
        assertEquals(TranscriptAvailability.FAILED, availability(JobStatus.NEEDS_AUTH, StepStatus.NEEDS_AUTH))
        assertEquals(TranscriptAvailability.NOT_REQUESTED, availability(JobStatus.SKIPPED_SHORT, StepStatus.PENDING))
        assertEquals(TranscriptAvailability.UNAVAILABLE, availability(JobStatus.DONE, StepStatus.SUCCEEDED))
    }

    @Test
    fun `a failed transcribe is not pending while a later webhook is running`() {
        assertEquals(TranscriptAvailability.FAILED, availability(JobStatus.RUNNING, StepStatus.FAILED))
    }

    @Test
    fun `recording and another devices pending transcript remain pending without local jobs`() {
        assertEquals(TranscriptAvailability.PENDING, missingTranscriptAvailability(record.copy(remotePending = setOf("transcribe")), emptyList(), emptyList()))
        assertEquals(TranscriptAvailability.PENDING, missingTranscriptAvailability(record.copy(meta = record.meta.copy(status = RecordingStatus.RECORDING)), emptyList(), emptyList()))
    }

    @Test
    fun `an unreadable workflow snapshot is not mistaken for no transcription`() {
        assertEquals(TranscriptAvailability.FAILED, missingTranscriptAvailability(record, listOf(job.copy(workflow = null, snapshotError = "unknown step")), emptyList()))
    }
}
