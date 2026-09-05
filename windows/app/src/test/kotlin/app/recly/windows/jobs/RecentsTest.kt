@file:OptIn(ExperimentalTime::class)

package app.recly.windows.jobs

import app.recly.windows.job
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.StringTable
import app.recly.windows.i18n.UiMessage
import app.recly.windows.i18n.coreMessage
import app.recly.windows.i18n.message
import app.recly.windows.i18n.text
import app.recly.windows.ui.component.BadgeTone
import app.recly.windows.ui.ledgerStatus
import app.recly.windows.ui.retryable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.Path.Companion.toPath
import recly.core.job.JobStatus
import recly.core.job.StepReport
import recly.core.job.StepRun
import recly.core.job.StepStatus
import recly.core.message.CoreMessage
import recly.core.model.AudioSettings
import recly.core.model.Codec
import recly.core.model.DriveLocation
import recly.core.model.Container
import recly.core.model.Platform
import recly.core.model.RecordingMeta
import recly.core.model.RecordingStatus
import recly.core.model.Source
import recly.core.model.Track
import recly.core.recording.RecordingRecord

/** Deliverable 7: what the tray's recents list says about a recording (docs/14 "앱"). */
class RecentsTest {

    @Test
    fun `a recording still being written says so whatever its job says`() {
        val item = Recents.item(record(status = RecordingStatus.RECORDING), job("j", JobStatus.PENDING), emptyList())

        assertEquals(Str.STATUS_RECORDING.message(), item.state)
    }

    @Test
    fun `a parked job is the tray's sign-in prompt`() {
        val item = Recents.item(record(), job("j", JobStatus.NEEDS_AUTH), emptyList())

        assertEquals(Str.STATUS_SIGN_IN_NEEDED.message(), item.state)
        // The row's Retry reads the status rather than the sentence, so it is carried across.
        assertEquals(JobStatus.NEEDS_AUTH, item.jobStatus)
    }

    @Test
    fun `a recording no workflow matched is not a failure`() {
        // docs/02: `EnqueueResult.NoWorkflow` leaves no job at all, and the row is not an error.
        val item = Recents.item(record(), job = null, steps = emptyList())

        assertEquals(Str.STATE_NO_WORKFLOW.message(), item.state)
        assertNull(item.jobId)
        assertNull(item.jobStatus)
    }

    /**
     * docs/03: another device recorded it and this PC read it out of Drive. There is no job here, so
     * "no workflow" would say something was skipped — it is finished work, and the row says the same
     * thing a finished job's does. What is left of "remote" is the delete: there is nothing on this
     * PC to run, and nothing local to keep.
     */
    @Test
    fun `a recording another device uploaded reads as finished, and has nothing to retry`() {
        val item = Recents.item(record(remote = true), job = null, steps = emptyList())

        assertEquals(Str.STATE_DONE.message(), item.state)
        assertEquals("DONE", item.state.ledgerStatus().code)
        assertTrue(item.remote)
        assertNull(item.jobStatus)
        // docs/09 화면 원칙 2: Details and Delete are offered exactly as for any finished row.
        assertTrue(item.deletable)
        assertFalse(Recents.uploading(listOf(item)))
    }

    /**
     * docs/03 "다른 기기의 녹음" (2026-09-04): a watch transfer still coming in. It cannot happen on
     * this shell — a PC has no watch to receive from — but the mapping is one product's, so the rule
     * is held here too: `RECEIVING` before the plain `REC` the row's `status = recording` would
     * otherwise get, and none of the actions a row that is being written to would refuse.
     */
    @Test
    fun `a transfer still coming in from the watch is RECEIVING, not this device's REC`() {
        val item = Recents.item(
            record(status = RecordingStatus.RECORDING, source = Source.WATCH),
            job = null,
            steps = emptyList(),
        )

        assertEquals(Str.STATE_RECEIVING.message(), item.state)
        assertEquals("RECEIVING", item.state.ledgerStatus().code)
        assertEquals(BadgeTone.ACCENT, item.state.ledgerStatus().tone)
        // No delete, no retry, no Drive link — the same exclusions as a row that is recording.
        assertFalse(item.deletable)
        assertFalse(retryable(item.jobStatus, transcribing = item.waitingMinutes != null))
        assertNull(item.link)
        // docs/09 화면 원칙 2: nothing is finalized yet, so the 길이 column says "not in yet".
        assertNull(item.durationSec)
    }

    /**
     * docs/03: the provisional row a pull opens for a folder that has no `meta.json` in it yet. It
     * carries `status = recording` like a take being written here, and the local `REC` would be this
     * PC claiming another device's work.
     */
    @Test
    fun `another device's upload is UPLOADING, and never this PC's REC`() {
        val item = Recents.item(
            record(status = RecordingStatus.RECORDING, remote = true),
            job = null,
            steps = emptyList(),
        )

        assertEquals(Str.STATE_REMOTE_UPLOADING.message(), item.state)
        assertEquals("UPLOADING", item.state.ledgerStatus().code)
        assertEquals(BadgeTone.ACCENT, item.state.ledgerStatus().tone)
        // Deleting it would pull the folder out from under the upload that is filling it.
        assertFalse(item.deletable)
        assertFalse(retryable(item.jobStatus, transcribing = item.waitingMinutes != null))
        assertNull(item.link)
        assertNull(item.durationSec)
        // docs/09 화면 원칙 1: the State node is what *this* PC is doing, and it is doing nothing.
        assertFalse(Recents.uploading(listOf(item)))
    }

    /**
     * docs/03: the upload landed and the device that made the recording still has its `transcribe` to
     * run. The row says so, and otherwise behaves as the finished remote row it already is.
     */
    @Test
    fun `another device still transcribing says so, and is a remote DONE row otherwise`() {
        val item = Recents.item(
            record(remote = true, remotePending = setOf("transcribe")),
            job = null,
            steps = emptyList(),
        )

        assertEquals(Str.STATE_REMOTE_TRANSCRIBING.message(), item.state)
        assertEquals("TRANSCRIBING", item.state.ledgerStatus().code)
        assertEquals(BadgeTone.ACCENT, item.state.ledgerStatus().tone)
        // Details and Delete, exactly as for any other row another device uploaded.
        assertTrue(item.deletable)
        assertFalse(Recents.uploading(listOf(item)))
    }

    /** docs/03: a `webhook` still to run is nothing this list has to report — the recording is done. */
    @Test
    fun `a remote row with only a webhook left to run reads as finished`() {
        val item = Recents.item(record(remote = true, remotePending = setOf("webhook")), null, emptyList())

        assertEquals(Str.STATE_DONE.message(), item.state)
        assertEquals("DONE", item.state.ledgerStatus().code)
    }

    @Test
    fun `an untitled recording is named, not blank`() {
        assertEquals(Str.UNTITLED.message(), Recents.item(record(title = "  "), null, emptyList()).title)
        assertEquals(
            UiMessage.Text("Weekly meeting"),
            Recents.item(record(title = "Weekly meeting"), null, emptyList()).title,
        )
    }

    /**
     * docs/08 "폴링 · 상태": a job parked while a provider transcribes is waiting on someone else,
     * not on a retry timer, so it says how long it has been rather than "waiting to retry" — and it
     * gets its own ledger badge instead of the RETRY one.
     */
    @Test
    fun `a job waiting for a transcription says how long it has been`() {
        val submitted = JsonObject(mapOf("submittedAt" to JsonPrimitive("2026-08-27T10:00:00.000Z")))
        // Still to run: a transcribe waiting on its provider is a PENDING step, not a finished one.
        val steps = listOf(step("stt", state = submitted, status = StepStatus.PENDING))

        val item = Recents.item(
            record(),
            job("j", JobStatus.WAITING),
            steps,
            now = Instant.parse("2026-08-27T10:07:30.000Z"),
        )

        assertEquals(7, item.waitingMinutes)
        assertEquals(Str.STATE_WAITING_TRANSCRIPTION.message(7), item.state)
        assertEquals("TRANSCRIBING", item.state.ledgerStatus().code)
    }

    /** Nothing has been submitted, so the ordinary retry wait is the honest thing to say. */
    @Test
    fun `a job waiting on a retry timer is not a transcription wait`() {
        val item = Recents.item(record(), job("j", JobStatus.WAITING), listOf(step("upload")))

        assertNull(item.waitingMinutes)
        assertEquals(Str.STATE_RETRY_WAIT.message(), item.state)
    }

    /** docs/08 "오류": the step that is holding the job up names the reason the window acts on. */
    @Test
    fun `the reason comes from the step that is holding the job up`() {
        val rejected = CoreMessage.AUTH_REJECTED.code(detail = "rtzr.transcribe HTTP 401")
        val steps = listOf(
            step("upload", lastError = "something Drive said"),
            step("stt", status = StepStatus.FAILED, lastError = rejected),
        )

        val item = Recents.item(record(), job("j", JobStatus.FAILED), steps)

        assertEquals(rejected, item.lastError)
        assertEquals("The provider rejected the key.", coreMessage(rejected).text(StringTable.of(StringTable.BASE)))
        assertTrue(StepReport.needsKey(item.lastError))
    }

    @Test
    fun `the Drive link comes from the upload step's output`() {
        val steps = listOf(
            step("hook", output = JsonObject(mapOf("status" to JsonPrimitive(200)))),
            step("upload", output = JsonObject(mapOf("folderWebViewLink" to JsonPrimitive(LINK)))),
        )

        assertEquals(LINK, Recents.item(record(), job("j", JobStatus.DONE), steps).link)
    }

    /** docs/03: an adopted row has no upload step here, but it was read out of its Drive folder. */
    @Test
    fun `an adopted recording links to the folder it was read from`() {
        val item = Recents.item(record(remote = true, driveFolderId = "abc"), job = null, steps = emptyList())

        assertEquals(LINK, item.link)
    }

    /** The link `drive.upload` wrote into `meta.json` wins over one built from the id. */
    @Test
    fun `the meta's own folder link is preferred`() {
        val record = record(remote = true, driveFolderId = "abc").let {
            it.copy(meta = it.meta.copy(drive = DriveLocation("abc", "https://drive.google.com/drive/folders/abc?x")))
        }

        assertEquals("$LINK?x", Recents.item(record, job = null, steps = emptyList()).link)
    }

    @Test
    fun `a job that has not uploaded anything has no link`() {
        assertNull(Recents.item(record(), job("j", JobStatus.PENDING), listOf(step("upload"))).link)
    }

    /**
     * docs/09 화면 원칙 1: the State node reads `UPLOADING` off the ledger it sits above, so what it
     * folds is the rows' own state and not a second reading of the queue.
     */
    @Test
    fun `a running job among the finished ones is the node's UPLOADING`() {
        val items = listOf(
            Recents.item(record(), job("done", JobStatus.DONE), emptyList()),
            Recents.item(record(), job("running", JobStatus.RUNNING), emptyList()),
        )

        assertEquals("UPLOADING", items[1].state.ledgerStatus().code)
        assertTrue(Recents.uploading(items))
    }

    @Test
    fun `a queue with nothing running is not uploading`() {
        assertFalse(Recents.uploading(emptyList()))
        assertFalse(
            Recents.uploading(
                listOf(
                    Recents.item(record(), job("pending", JobStatus.PENDING), emptyList()),
                    Recents.item(record(), job("done", JobStatus.DONE), emptyList()),
                ),
            ),
        )
    }

    /**
     * A recording still being written says `REC` whatever its job says ([Recents.stateLabel]), and the node
     * says `REC` too — so a `RUNNING` job under it may not turn the loader on behind that.
     */
    @Test
    fun `a recording in flight is not an upload`() {
        val recording = Recents.item(
            record(status = RecordingStatus.RECORDING),
            job("running", JobStatus.RUNNING),
            emptyList(),
        )

        assertFalse(Recents.uploading(listOf(recording)))
    }

    /**
     * docs/09 화면 원칙 2 "삭제(녹음·업로드 중 제외)": the two rows that do not offer a Delete, in both
     * of the surfaces that draw them. The core refuses the delete anyway, and a button that only
     * ever produces a refusal is not one to draw.
     */
    @Test
    fun `a recording being written to or uploaded is not one to delete`() {
        assertFalse(Recents.item(record(status = RecordingStatus.RECORDING), null, emptyList()).deletable)
        assertFalse(Recents.item(record(), job("running", JobStatus.RUNNING), emptyList()).deletable)
        assertTrue(Recents.item(record(), job("done", JobStatus.DONE), emptyList()).deletable)
        assertTrue(Recents.item(record(), job("failed", JobStatus.FAILED), emptyList()).deletable)
    }

    /** docs/09 화면 원칙 2: the 길이 column, off `meta.json` — and empty until it is finalized. */
    @Test
    fun `the length is the meta's, and there is none until the recording is finalized`() {
        assertEquals(90.0, Recents.item(record(durationSec = 90.0), null, emptyList()).durationSec)
        assertNull(Recents.item(record(status = RecordingStatus.RECORDING), null, emptyList()).durationSec)
    }

    private fun step(
        id: String,
        output: JsonObject? = null,
        state: JsonObject? = null,
        status: StepStatus = StepStatus.SUCCEEDED,
        lastError: String? = null,
    ) = StepRun(
        id = "run-$id",
        jobId = "j",
        stepId = id,
        ordinal = 0,
        status = status,
        attempts = 0,
        nextAttemptAt = null,
        lastError = lastError,
        state = state,
        output = output,
    )

    private fun record(
        status: RecordingStatus = RecordingStatus.FINALIZED,
        title: String? = "Weekly meeting",
        durationSec: Double? = null,
        remote: Boolean = false,
        source: Source = Source.DESKTOP,
        remotePending: Set<String> = emptySet(),
        driveFolderId: String? = null,
    ) = RecordingRecord(
        id = "rec-1",
        meta = RecordingMeta(
            schema = 1,
            recordingId = "rec-1",
            source = source,
            platform = Platform.WINDOWS,
            deviceId = "device",
            deviceName = "PC",
            title = title,
            startedAt = "2026-08-27T10:00:00.000Z",
            timezone = "Asia/Seoul",
            durationSec = durationSec,
            audio = AudioSettings(Codec.AAC_LC, Container.M4A, 16_000, 1, 32, 900),
            tracks = listOf(Track.MIC, Track.SYS, Track.MIX),
            parts = emptyList(),
            status = status,
        ),
        dir = "/tmp/rec-1".toPath(),
        driveFolderId = driveFolderId,
        remote = remote,
        remotePending = remotePending,
    )

    private companion object {
        const val LINK = "https://drive.google.com/drive/folders/abc"
    }
}
