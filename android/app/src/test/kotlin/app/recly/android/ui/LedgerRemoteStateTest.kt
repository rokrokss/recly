@file:OptIn(ExperimentalTime::class)

package app.recly.android.ui

import app.recly.android.work.job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import okio.Path.Companion.toPath
import recly.core.job.JobStatus
import recly.core.model.AudioSettings
import recly.core.model.Codec
import recly.core.model.Container
import recly.core.model.Platform
import recly.core.model.RecordingMeta
import recly.core.model.RecordingStatus
import recly.core.model.Source
import recly.core.model.Track
import recly.core.recording.RecordingRecord

/**
 * docs/03 "다른 기기의 녹음", docs/09 화면 원칙 2: the three things the ledger can be told are
 * happening somewhere else. Each of them is a row with no job on this device, so each of them is
 * decided before the job status is looked at — and two of the three are `status = recording`, which
 * is exactly what a `REC` row is.
 */
class LedgerRemoteStateTest {

    /** A watch transfer in flight is not this phone recording, however the row's status reads. */
    @Test
    fun `a recording coming in from the watch is RECEIVING`() {
        val record = record(source = Source.WATCH, status = RecordingStatus.RECORDING)

        assertEquals(ItemState.RECEIVING, stateOf(record, job = null))
    }

    /** This phone's own open recording still says `REC` — nothing is being received. */
    @Test
    fun `this phone's own open recording is still RECORDING`() {
        val record = record(status = RecordingStatus.RECORDING)

        assertEquals(ItemState.RECORDING, stateOf(record, job = null))
    }

    /** The provisional row a pull opens for a folder with no `meta.json` in it yet. */
    @Test
    fun `a folder another device is still uploading is UPLOADING`() {
        val record = record(status = RecordingStatus.RECORDING, remote = true)

        assertEquals(ItemState.REMOTE_UPLOADING, stateOf(record, job = null))
    }

    @Test
    fun `an adopted row whose marker still names transcribe is TRANSCRIBING`() {
        val record = record(remote = true, pending = setOf("transcribe", "webhook"))

        assertEquals(ItemState.REMOTE_TRANSCRIBING, stateOf(record, job = null))
    }

    /**
     * A webhook is a request this phone will never see the answer to, and the recording itself is
     * finished — the row is the `DONE` every adopted row is.
     */
    @Test
    fun `a marker that names only webhook is DONE`() {
        val record = record(remote = true, pending = setOf("webhook"))

        assertEquals(ItemState.DONE, stateOf(record, job = null))
    }

    @Test
    fun `an adopted row with nothing pending is DONE`() {
        assertEquals(ItemState.DONE, stateOf(record(remote = true), job = null))
    }

    /** The rules are read before the job status, and a job of this device's is unaffected by them. */
    @Test
    fun `this device's own job still decides its own row`() {
        val record = record()

        assertEquals(
            ItemState.RUNNING,
            stateOf(record, job("j", JobStatus.RUNNING, nextRunAt = null)),
        )
    }

    private fun record(
        source: Source = Source.PHONE,
        status: RecordingStatus = RecordingStatus.FINALIZED,
        remote: Boolean = false,
        pending: Set<String> = emptySet(),
    ) = RecordingRecord(
        id = ID,
        meta = RecordingMeta(
            schema = 1,
            recordingId = ID,
            source = source,
            platform = Platform.ANDROID,
            deviceId = "device",
            deviceName = "Z Fold7",
            startedAt = "2026-09-04T09:00:00Z",
            timezone = "Asia/Seoul",
            audio = AudioSettings(Codec.AAC_LC, Container.M4A, 16_000, 1, 32, 900),
            tracks = listOf(Track.MONO),
            parts = emptyList(),
            status = status,
        ),
        dir = "/data/rec/$ID".toPath(),
        remote = remote,
        remotePending = pending,
    )

    private companion object {
        const val ID = "01J0LEDGER"
    }
}
