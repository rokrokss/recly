@file:OptIn(kotlin.time.ExperimentalTime::class)

package app.recly.wear.entry

import app.recly.recording.RecorderState
import app.recly.wear.R
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * docs/11 W5 · N4 (UX decisions of 2026-10-11): what the complication shows — the record square, the time
 * a recording has run where `REC` used to be, and the waiting recordings' word otherwise.
 */
class ComplicationLookTest {

    @Test
    fun `a recording is the filled square and the time since it started`() {
        val started = Instant.fromEpochMilliseconds(1_760_000_000_000)
        val look = complicationLook(RecorderState.Recording("r", started), pending = 2, sending = true)
        assertEquals(ComplicationLook.Elapsed(1_760_000_000_000), look)
        assertEquals(R.drawable.ic_complication_recording, look.glyph)
    }

    @Test
    fun `idle is the record node, and says whether recordings are waiting or going`() {
        assertEquals(
            ComplicationLook.Words(R.string.complication_idle, R.string.entry_ready, R.drawable.ic_complication_record),
            complicationLook(RecorderState.Idle, pending = 0, sending = false),
        )
        assertEquals(
            ComplicationLook.Words(R.string.complication_pending, R.string.pending_badge, R.drawable.ic_complication_record),
            complicationLook(RecorderState.Idle, pending = 1, sending = false),
        )
        assertEquals(
            ComplicationLook.Words(R.string.complication_sending, R.string.sending_badge, R.drawable.ic_complication_record),
            complicationLook(RecorderState.Idle, pending = 1, sending = true),
        )
    }

    @Test
    fun `starting and saving have no time to count, keep the recording's square, and say the phones' words`() {
        assertEquals(
            ComplicationLook.Words(R.string.recording_starting, R.string.recording_starting, R.drawable.ic_complication_recording),
            complicationLook(RecorderState.Starting, pending = 0, sending = false),
        )
        assertEquals(
            ComplicationLook.Words(R.string.recording_stopping, R.string.recording_stopping, R.drawable.ic_complication_recording),
            complicationLook(RecorderState.Stopping, pending = 0, sending = false),
        )
    }

    /** P1: a refused transfer is said as the screen says it, ahead of the recordings still waiting. */
    @Test
    fun `a failed transfer outranks the waiting ones`() {
        assertEquals(
            ComplicationLook.Words(R.string.transfer_failed_badge, R.string.transfer_failed_badge, R.drawable.ic_complication_record),
            complicationLook(RecorderState.Idle, pending = 2, sending = true, failed = 1),
        )
    }
}
