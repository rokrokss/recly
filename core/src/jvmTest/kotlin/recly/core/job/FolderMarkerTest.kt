@file:OptIn(ExperimentalTime::class)

package recly.core.job

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import recly.core.drive.FolderMarker
import recly.core.message.CoreMessage
import recly.core.model.OnError
import recly.core.testing.driveStep
import recly.core.testing.transcribeStep
import recly.core.testing.webhookStep

/**
 * docs/03 "다른 기기의 녹음": Drive holds the audio but says nothing about the transcribe that is
 * still four minutes away, so the device running the job writes what is left onto the recording's
 * folder. These are the executor's half of that — the upload runner writes the first one
 * ([recly.core.drive.DriveUploadRunnerTest]), and every step after it moves the marker on.
 */
class FolderMarkerTest {
    @Test
    fun `every step that finishes takes its type off the marker, and DONE empties it`() = runBlocking {
        val marker = RecordingMarker()
        val f = Fixture(runners(), marker = marker)
        val recording = f.seed()
        val jobId = f.enqueue(recording, driveStep("up"), webhookStep("hook"), transcribeStep("stt"))

        f.service.runDueJobs()

        assertEquals(JobStatus.DONE, f.store.get(jobId)!!.status)
        assertEquals(
            listOf(
                FOLDER to listOf("webhook", "transcribe"),
                FOLDER to listOf("transcribe"),
                FOLDER to emptyList(),
            ),
            marker.marks,
            "the DONE marker is the one the last step already wrote, and is not sent twice",
        )
    }

    /** A job in `FAILED` is not coming back on its own: nothing it promised will ever run. */
    @Test
    fun `a job that parks in FAILED stops the other devices waiting`() = runBlocking {
        val marker = RecordingMarker()
        val f = Fixture(runners(hook = { throw StepFailure(retryable = false, reason = CoreMessage.STEP_FAILED.code()) }), marker = marker)
        val recording = f.seed()
        val jobId = f.enqueue(recording, driveStep("up"), webhookStep("hook"), transcribeStep("stt"))

        f.service.runDueJobs()

        assertEquals(JobStatus.FAILED, f.store.get(jobId)!!.status)
        assertEquals(
            listOf(FOLDER to listOf("webhook", "transcribe"), FOLDER to emptyList()),
            marker.marks,
        )
    }

    /**
     * The upload makes and marks the folder before the bytes go (`DriveUploadRunner`), so an upload
     * that then fails for good has promised work on Drive with no successful output to find the
     * folder by — the persisted output of its own row is what takes the marker down (Sol, 2026-09-04).
     */
    @Test
    fun `an upload that fails terminally after making its folder still clears the marker`() = runBlocking {
        val marker = RecordingMarker()
        val upload = ScriptedRunner("drive.upload") { ctx, _ ->
            ctx.saveOutput(buildJsonObject { put("folderId", FOLDER) })
            throw StepFailure(retryable = false, reason = CoreMessage.STEP_FAILED.code())
        }
        val f = Fixture(listOf(upload, ScriptedRunner("transcribe") { _, _ -> output("transcript" to "ok") }), marker = marker)
        val recording = f.seed()
        val jobId = f.enqueue(recording, driveStep("up"), transcribeStep("stt"))

        f.service.runDueJobs()

        assertEquals(JobStatus.FAILED, f.store.get(jobId)!!.status)
        assertEquals(listOf(FOLDER to emptyList()), marker.marks)
    }

    /** `WAITING` is a job that *is* coming back, so what it still owes stands. */
    @Test
    fun `a job waiting out a backoff keeps its marker`() = runBlocking {
        val marker = RecordingMarker()
        val f = Fixture(runners(hook = { throw StepFailure(retryable = true, reason = CoreMessage.STEP_FAILED.code()) }), marker = marker)
        val recording = f.seed()
        val jobId = f.enqueue(recording, driveStep("up"), webhookStep("hook"))

        f.service.runDueJobs()

        assertEquals(JobStatus.WAITING, f.store.get(jobId)!!.status)
        assertEquals(listOf(FOLDER to listOf("webhook")), marker.marks)
    }

    /** A step whose `onError` is `continue` leaves the job running, and the marker moves on with it. */
    @Test
    fun `a failed step the job continues past still comes off the marker`() = runBlocking {
        val marker = RecordingMarker()
        val f = Fixture(runners(hook = { throw StepFailure(retryable = false, reason = CoreMessage.STEP_FAILED.code()) }), marker = marker)
        val recording = f.seed()
        val jobId = f.enqueue(
            recording,
            driveStep("up"),
            webhookStep("hook", onError = OnError.CONTINUE),
            transcribeStep("stt"),
        )

        f.service.runDueJobs()

        assertEquals(JobStatus.DONE, f.store.get(jobId)!!.status)
        assertEquals(
            listOf(FOLDER to listOf("webhook", "transcribe"), FOLDER to emptyList()),
            marker.marks,
            "the webhook wrote nothing itself; the transcribe after it cleared what was left",
        )
    }

    /** Nothing has been uploaded, so there is no folder anywhere to write a marker on. */
    @Test
    fun `a workflow without an upload marks nothing`() = runBlocking {
        val marker = RecordingMarker()
        val f = Fixture(runners(), marker = marker)
        val recording = f.seed()
        val jobId = f.enqueue(recording, webhookStep("hook"))

        f.service.runDueJobs()

        assertEquals(JobStatus.DONE, f.store.get(jobId)!!.status)
        assertEquals(emptyList(), marker.marks)
    }

    /** The upload's output names the folder; the two steps after it are scripted to do nothing. */
    private fun runners(hook: suspend () -> StepOutcome = { output("status" to "200") }): List<StepRunner> = listOf(
        ScriptedRunner("drive.upload") { ctx, _ -> uploadOutput(ctx) },
        ScriptedRunner("webhook") { _, _ -> hook() },
        ScriptedRunner("transcribe") { _, _ -> output("transcript" to "ok") },
    )

    private class RecordingMarker : FolderMarker {
        val marks = mutableListOf<Pair<String, List<String>>>()

        override suspend fun mark(folderId: String, pending: List<String>) {
            marks += folderId to pending
        }
    }

    private companion object {
        /** What [uploadOutput] reports as the recording's `{base}/` folder. */
        const val FOLDER = "F1"
    }
}
