@file:OptIn(ExperimentalTime::class)

package app.recly.android.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

/**
 * docs/09 화면 원칙 1: the recording screen's state node says `UPLOADING` while the recorder is idle
 * and the ledger is running something. "Running" is the ledger's own `RUNNING` — a pass in flight —
 * and nothing else: a job that is merely waiting its turn is not work the user can see happening.
 *
 * The other thing it can borrow is `RECEIVING`, a recording arriving from the watch (docs/03), and
 * the order between the two is what [ledgerCode] holds.
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

    /**
     * docs/03 "워치 → 폰 전송 계약": a recording coming in from the watch is the other thing this
     * screen can be told is happening while the recorder is idle — and only a *local* transfer is.
     * What another device is doing is not this phone's node to show.
     */
    @Test
    fun `a transfer from the watch is what the idle node says`() {
        assertEquals("RECEIVING", ledgerCode(listOf(item(ItemState.DONE), item(ItemState.RECEIVING))))
        assertNull(ledgerCode(listOf(item(ItemState.REMOTE_UPLOADING))))
        assertNull(ledgerCode(listOf(item(ItemState.REMOTE_TRANSCRIBING))))
    }

    /** A pass of this phone's own is the one thing the user could be waiting on here. */
    @Test
    fun `a running job wins over a transfer coming in`() {
        assertEquals("UPLOADING", ledgerCode(listOf(item(ItemState.RECEIVING), item(ItemState.RUNNING))))
    }

    /** Nothing to borrow: the node is the recorder's own code (`IDLE`), which is not this list's. */
    @Test
    fun `a quiet ledger says nothing`() {
        assertNull(ledgerCode(listOf(item(ItemState.DONE), item(ItemState.PENDING))))
        assertNull(ledgerCode(emptyList()))
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
