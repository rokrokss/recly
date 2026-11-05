@file:OptIn(ExperimentalTime::class)

package app.recly.android.ui

import app.recly.android.ui.component.UnderTitle
import app.recly.android.ui.component.underTitle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.ExperimentalTime

/**
 * docs/09 screen principle 2 (2026-11-04): the ledger has no status column. A row that is not done wears its
 * badge under the title, in place of the transcript's first words; a done row has no badge — on every one
 * it said nothing — and keeps its preview, or nothing when it has none.
 */
class LedgerUnderTitleTest {

    private val preview = "So the plan for Thursday is"

    @Test
    fun `a done row draws no badge and shows its preview`() {
        assertEquals(UnderTitle.Preview(preview), underTitle(row(ItemState.DONE).badge(), preview))
        // Another device's recording with no job here is done as well.
        assertEquals(UnderTitle.Preview(preview), underTitle(row(ItemState.NO_JOB).badge(), preview))
    }

    @Test
    fun `a done row without a preview draws nothing under its title`() {
        assertEquals(null, underTitle(row(ItemState.DONE).badge(), ""))
    }

    @Test
    fun `a failed row draws its badge and no preview`() {
        val failed = row(ItemState.FAILED).badge()

        assertEquals(UnderTitle.Badge(failed), underTitle(failed, preview))
        assertEquals("FAILED", failed.code)
    }

    /** Work in hand on a done recording: the badge, with its loader, until the run ends. */
    @Test
    fun `a done row being organized or filled in draws its busy badge and no preview`() {
        listOf(row(ItemState.DONE).badge(organizing = true), row(ItemState.DONE).badge(filling = true)).forEach { status ->
            val line = assertIs<UnderTitle.Badge>(underTitle(status, preview), status.code)
            assertEquals(status, line.status)
            assertEquals(true, line.status.busy, status.code)
        }
    }

    @Test
    fun `every state but done wears its badge`() {
        ItemState.entries.forEach { state ->
            val status = row(state).badge()
            val line = underTitle(status, preview)
            if (status.code == "DONE") {
                assertEquals(UnderTitle.Preview(preview), line, "$state")
            } else {
                assertEquals(UnderTitle.Badge(status), line, "$state")
            }
        }
    }

    private fun row(state: ItemState): JobItem = JobItem(
        recordingId = "01J0${state.name}",
        jobId = "job",
        title = null,
        startedAt = "2026-11-04T09:00:00Z",
        durationSec = 60.0,
        state = state,
        error = null,
        waitingMinutes = null,
        link = null,
        nextRunAt = null,
    )
}
