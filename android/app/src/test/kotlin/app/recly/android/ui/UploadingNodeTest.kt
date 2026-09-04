@file:OptIn(ExperimentalTime::class)

package app.recly.android.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

/**
 * docs/09 화면 원칙 1: the recording screen's state node says `UPLOADING` while the recorder is idle
 * and the ledger is running something. "Running" is the ledger's own `RUNNING` — a pass in flight —
 * and nothing else: a job that is merely waiting its turn is not work the user can see happening.
 */
class UploadingNodeTest {

    @Test
    fun `a running job among others is a pass in flight`() {
        val items = listOf(item(ItemState.DONE), item(ItemState.RUNNING), item(ItemState.PENDING))

        assertTrue(uploading(items))
    }

    @Test
    fun `an empty ledger is not uploading`() {
        assertFalse(uploading(emptyList()))
    }

    /** A queued job runs at its own time, and a finished one is not news. */
    @Test
    fun `pending and done alone are not uploading`() {
        assertFalse(uploading(listOf(item(ItemState.PENDING), item(ItemState.DONE))))
    }

    private fun item(state: ItemState): JobItem = JobItem(
        recordingId = "01J0${state.name}",
        jobId = null,
        workflowId = null,
        title = null,
        startedAt = "2026-09-03T09:00:00Z",
        durationSec = 60.0,
        state = state,
        error = null,
        waitingMinutes = null,
        link = null,
        nextRunAt = null,
    )
}
