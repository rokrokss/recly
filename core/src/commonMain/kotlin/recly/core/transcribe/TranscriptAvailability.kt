package recly.core.transcribe

import recly.core.job.Job
import recly.core.job.JobStatus
import recly.core.job.StepRun
import recly.core.job.StepStatus
import recly.core.model.RecordingStatus
import recly.core.model.Step
import recly.core.recording.RecordingRecord

/** docs/08 "Result files": absence has a reason even when there is no transcript to render. */
internal fun missingTranscriptAvailability(
    record: RecordingRecord,
    jobs: List<Job>,
    runs: List<StepRun>,
): TranscriptAvailability {
    if (record.meta.status == RecordingStatus.RECORDING || "transcribe" in record.remotePending) {
        return TranscriptAvailability.PENDING
    }
    val requested = jobs.filter { job -> job.workflow?.steps?.any { it is Step.Transcribe || it is Step.LocalTranscribe } == true }
    if (requested.isEmpty()) return if (jobs.any { it.snapshotError != null }) {
        TranscriptAvailability.FAILED
    } else TranscriptAvailability.NOT_REQUESTED
    val ids = requested.flatMap { job ->
        job.workflow!!.steps.filter { it is Step.Transcribe || it is Step.LocalTranscribe }.map { job.id to it.id }
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
    // A job waiting on the user has not failed its transcription: the step has yet to run, or it
    // stopped on the same wait the job did.
    if (requested.any { job ->
        job.status in PARKED_JOBS &&
            transcription.filter { it.jobId == job.id }.all { it.status in PARKED_STEPS }
    }) {
        return TranscriptAvailability.PARKED
    }
    return TranscriptAvailability.FAILED
}

private val PARKED_JOBS = setOf(JobStatus.NEEDS_AUTH, JobStatus.NEEDS_SPACE, JobStatus.NEEDS_CONSENT, JobStatus.NEEDS_MODEL)
private val PARKED_STEPS = setOf(
    StepStatus.PENDING, StepStatus.NEEDS_AUTH, StepStatus.NEEDS_SPACE, StepStatus.NEEDS_CONSENT, StepStatus.NEEDS_MODEL,
)
