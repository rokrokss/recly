@file:OptIn(ExperimentalTime::class)

package app.recly.windows.ui

import recly.core.transcribe.TranscriptAvailability
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.StringTable
import app.recly.windows.job
import app.recly.windows.plain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import recly.core.job.Job
import recly.core.job.JobStatus
import recly.core.job.StepRun
import recly.core.job.StepStatus
import recly.core.model.Step

/**
 * The UX decisions of 2026-10-08, item 7: while a job of the recording is unsettled, Edit transcript and Transcribe
 * again stay disabled as before, and only their reason changes — `Transcribing…` while a transcription is really
 * queued or running, `Waiting for Drive` for a job parked on the connection, `Not uploaded yet` for any other
 * upload still to happen.
 */
class DetailReasonsTest {

    @Test
    fun `the reason names what the recording is waiting for`() {
        assertEquals(Str.DETAIL_TRANSCRIBING, busyReason(transcribing = true, waitsForDrive = true, uploaded = false))
        assertEquals(Str.DRIVE_PENDING, busyReason(transcribing = false, waitsForDrive = true, uploaded = false))
        assertEquals(Str.DETAIL_NOT_UPLOADED, busyReason(transcribing = false, waitsForDrive = false, uploaded = false))
        assertEquals(Str.DETAIL_TRANSCRIBING, busyReason(transcribing = false, waitsForDrive = false, uploaded = true))
        // `Waiting for Drive` is the badge's word, in both languages.
        assertEquals("Waiting for Drive", StringTable.of(StringTable.BASE)[Str.DRIVE_PENDING])
        assertEquals("Drive 연결 대기", StringTable.of(StringTable.KOREAN)[Str.DRIVE_PENDING].plain())
    }

    @Test
    fun `a transcription is in flight only once the job has reached it`() {
        val pending = plan(JobStatus.PENDING)
        // Nothing has run: the upload comes first.
        assertFalse(transcriptionInFlight(pending, emptyList()))
        assertFalse(transcriptionInFlight(pending, listOf(run("upload", 0, StepStatus.RUNNING))))
        // The upload is in, and the transcription is next — queued, then running.
        assertTrue(transcriptionInFlight(pending, listOf(run("upload", 0, StepStatus.SUCCEEDED))))
        assertTrue(
            transcriptionInFlight(
                plan(JobStatus.RUNNING),
                listOf(run("upload", 0, StepStatus.SUCCEEDED), run("stt", 1, StepStatus.RUNNING)),
            ),
        )
        // Parked on the Drive connection: not a transcription, whatever its steps say.
        assertFalse(transcriptionInFlight(plan(JobStatus.NEEDS_AUTH), listOf(run("upload", 0, StepStatus.NEEDS_AUTH))))
        // A re-transcription has nothing to upload: it is the transcription from the start.
        val again = job("a", JobStatus.PENDING).let { it.copy(workflow = it.workflow!!.copy(steps = listOf(Step.LocalTranscribe("stt")))) }
        assertTrue(transcriptionInFlight(again, emptyList()))
    }

    private fun plan(status: JobStatus): Job = job("j", status).let {
        it.copy(workflow = it.workflow!!.copy(steps = listOf(Step.DriveUpload("upload"), Step.Transcribe("stt", provider = "assemblyai", secretRef = "key"))))
    }

    private fun run(stepId: String, ordinal: Int, status: StepStatus) = StepRun(
        id = "run-$stepId",
        jobId = "j",
        stepId = stepId,
        ordinal = ordinal,
        status = status,
        attempts = 0,
        nextAttemptAt = null,
        lastError = null,
        state = null,
        output = null,
    )

    /**
     * 2026-10-10: in the transcript's place the detail says the real reason — the wait for Drive, with Connect Drive,
     * whatever the availability says; another device transcribing; then the availability's own sentence.
     */
    @Test
    fun `the detail says why there is no transcript`() {
        assertEquals(Str.DETAIL_WAITING_DRIVE, transcriptNotice(TranscriptAvailability.NOT_REQUESTED, waitsForDrive = true, remoteTranscribing = false))
        assertEquals(Str.DETAIL_WAITING_DRIVE, transcriptNotice(TranscriptAvailability.PENDING, waitsForDrive = true, remoteTranscribing = false))
        assertEquals(Str.DETAIL_FAILED, transcriptNotice(TranscriptAvailability.FAILED, waitsForDrive = true, remoteTranscribing = false))
        assertEquals(Str.STATE_REMOTE_TRANSCRIBING, transcriptNotice(TranscriptAvailability.PENDING, waitsForDrive = false, remoteTranscribing = true))
        assertEquals(Str.DETAIL_NOT_REQUESTED, transcriptNotice(TranscriptAvailability.NOT_REQUESTED, waitsForDrive = false, remoteTranscribing = false))
        assertEquals(
            "Transcription is off. Turn it on in Settings, then use Transcribe again.",
            StringTable.of(StringTable.BASE)[Str.DETAIL_NOT_REQUESTED],
        )
        assertEquals("Transcribing on another device", StringTable.of(StringTable.BASE)[Str.STATE_REMOTE_TRANSCRIBING])
    }
}
