package app.recly.android.ui.component

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * docs/09 screen principle 2: the ledger measures its length column so no length is ever clipped, and on
 * a wide screen that is free. On a narrow window it is not — the column takes its width out of the
 * title. This is the rule that decides, without a screen, whether three columns still fit or the row
 * becomes two lines.
 */
class LedgerLayoutTest {

    /**
     * The row's own padding (16dp each side), the 62dp time column and the two 8dp gaps: what the
     * title never gets, whatever the measured column comes to.
     */
    private val fixed = 110.dp

    /** `42:10` as a phone at a font scale of 1.3 measures it. */
    private val wide = LedgerColumns(length = 45.dp)

    @Test
    fun `a phone-width ledger keeps its three columns`() {
        assertEquals(LedgerLayout.COLUMNS, ledgerLayout(available = 411.dp, columns = wide))
    }

    /**
     * The case from the review — 320dp at a font scale of 1.3 — stacked while the state had a column of
     * its own; with the badge under the title (2026-11-04) the title keeps 165dp of it.
     */
    @Test
    fun `a narrow phone at a large font size keeps its columns`() {
        assertEquals(LedgerLayout.COLUMNS, ledgerLayout(available = 320.dp, columns = wide))
    }

    /** A split-screen window leaves the title less than its minimum, and the row stacks. */
    @Test
    fun `a split-screen width at a large font size stacks`() {
        assertEquals(LedgerLayout.STACKED, ledgerLayout(available = 240.dp, columns = wide))
    }

    /**
     * A font scale past 1.3: the measured column outgrows the window on its own. The old layout
     * answered that by squeezing it — which is the clipping the measured column was for — and this
     * one still stacks.
     */
    @Test
    fun `an extreme font scale stacks rather than squeezing the measured column`() {
        val huge = LedgerColumns(length = 120.dp)

        assertEquals(LedgerLayout.STACKED, ledgerLayout(available = 320.dp, columns = huge))
    }

    /** The line itself: a title of exactly the minimum is still a title, a dp under it is not. */
    @Test
    fun `the switch is at the title minimum`() {
        val columns = LedgerColumns(length = 50.dp)
        val exactly = fixed + columns.length + LedgerTitleMin

        assertEquals(LedgerLayout.COLUMNS, ledgerLayout(exactly, columns))
        assertEquals(LedgerLayout.STACKED, ledgerLayout(exactly - 1.dp, columns))
    }

    /** The caller may hold the ledger to a wider title; the same width then stacks. */
    @Test
    fun `a wider title minimum stacks a ledger that fitted`() {
        assertEquals(LedgerLayout.COLUMNS, ledgerLayout(411.dp, wide, titleMin = 96.dp))
        assertEquals(LedgerLayout.STACKED, ledgerLayout(411.dp, wide, titleMin = 260.dp))
    }
}
