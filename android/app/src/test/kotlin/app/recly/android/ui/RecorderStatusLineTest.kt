@file:OptIn(ExperimentalTime::class)

package app.recly.android.ui

import app.recly.android.R
import app.recly.android.core.UiMessage
import app.recly.recording.RecorderState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * docs/09 화면 원칙 1: the line under the record button says something only when there is news —
 * what the last start or stop had to say. What the recorder is doing is the State node's.
 */
class RecorderStatusLineTest {

    @Test
    fun `while the recorder works the line is empty`() {
        val failed = listOf(UiMessage.Res(R.string.recording_failed))

        assertEquals(emptyList(), statusLine(RecorderState.Starting, failed))
        assertEquals(emptyList(), statusLine(RecorderState.Recording("01J0", Instant.fromEpochMilliseconds(0)), failed))
        assertEquals(emptyList(), statusLine(RecorderState.Stopping, failed))
    }

    @Test
    fun `an idle recorder says what the last start or stop had to say`() {
        val said = listOf(UiMessage.Res(R.string.recording_title_too_late), UiMessage.Res(R.string.enqueue_skipped_short))

        assertEquals(said, statusLine(RecorderState.Idle, said))
    }

    /** A stop that went as asked is not news, and neither is waiting. */
    @Test
    fun `an idle recorder with nothing to say says nothing`() {
        assertEquals(emptyList(), statusLine(RecorderState.Idle, emptyList()))
    }
}
