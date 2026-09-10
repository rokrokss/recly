package recly.core.transcribe

import recly.core.job.Job
import recly.core.job.JobStatus
import recly.core.job.StepRun
import recly.core.job.StepStatus
import recly.core.model.RecordingStatus
import recly.core.model.Step
import recly.core.recording.RecordingRecord

/** docs/08 "결과 파일": absence has a reason even when there is no transcript to render. */
internal fun missingTranscriptAvailability(
    record: RecordingRecord,
    jobs: List<Job>,
    runs: List<StepRun>,
): TranscriptAvailability {
    if (record.meta.status == RecordingStatus.RECORDING || "transcribe" in record.remotePending) {
        return TranscriptAvailability.PENDING
    }
    val requested = jobs.filter { job -> job.workflow?.steps?.any { it is Step.Transcribe } == true }
    if (requested.isEmpty()) return if (jobs.any { it.snapshotError != null }) {
        TranscriptAvailability.FAILED
    } else TranscriptAvailability.NOT_REQUESTED
    val ids = requested.flatMap { job ->
        job.workflow!!.steps.filterIsInstance<Step.Transcribe>().map { job.id to it.id }
    }.toSet()
    val transcription = runs.filter { (it.jobId to it.stepId) in ids }
    if (transcription.any { it.status == StepStatus.SUCCEEDED }) return TranscriptAvailability.UNAVAILABLE
    if (requested.all { it.status == JobStatus.SKIPPED_SHORT }) return TranscriptAvailability.NOT_REQUESTED
    if (requested.any { job ->
        job.status in setOf(JobStatus.PENDING, JobStatus.RUNNING, JobStatus.WAITING) &&
            (transcription.none { it.jobId == job.id } || transcription.any {
                it.jobId == job.id && it.status in setOf(StepStatus.PENDING, StepStatus.RUNNING)
            })
    }) {
        return TranscriptAvailability.PENDING
    }
    return TranscriptAvailability.FAILED
}
