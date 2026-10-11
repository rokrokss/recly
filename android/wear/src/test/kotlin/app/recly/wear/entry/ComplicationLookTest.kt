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
    fun `starting and saving have no time to count, and keep the recording's square`() {
        assertEquals(R.drawable.ic_complication_recording, complicationLook(RecorderState.Starting, 0, false).glyph)
        assertEquals(
            ComplicationLook.Words(R.string.recording_busy, R.string.recording_stopping, R.drawable.ic_complication_recording),
            complicationLook(RecorderState.Stopping, pending = 0, sending = false),
        )
    }
}
