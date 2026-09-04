package app.recly.android.ui.component

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * docs/09 화면 원칙 2: the ledger measures its length and status columns so no code is ever clipped,
 * and on a wide screen that is free. On a narrow one it is not — the columns take their width out of
 * the title, and at 320dp and a font scale of 1.3 the title was left some 34dp. This is the rule
 * that decides, without a screen, whether four columns still fit or the row becomes two lines.
 */
class LedgerLayoutTest {

    /**
     * The row's own padding (16dp each side), the 62dp time column and the three 8dp gaps: what the
     * title never gets, whatever the two measured columns come to.
     */
    private val fixed = 118.dp

    /** `42:10` and `NEEDS_AUTH` as a phone at a font scale of 1.3 measures them. */
    private val wide = LedgerColumns(length = 45.dp, status = 113.dp)

    @Test
    fun `a phone-width ledger keeps its four columns`() {
        assertEquals(LedgerLayout.COLUMNS, ledgerLayout(available = 411.dp, columns = wide))
    }

    /** The case from the review: 320dp at a font scale of 1.3 leaves the title 44dp of a 96dp minimum. */
    @Test
    fun `a narrow ledger at a large font size stacks`() {
        assertEquals(LedgerLayout.STACKED, ledgerLayout(available = 320.dp, columns = wide))
    }

    /**
     * A font scale past 1.3, or a longer code in another language: the columns outgrow the screen on
     * their own. The old layout answered that by squeezing them — which is the clipping the measured
     * columns were for — and this one still stacks.
     */
    @Test
    fun `an extreme font scale stacks rather than squeezing the measured columns`() {
        val huge = LedgerColumns(length = 70.dp, status = 180.dp)

        assertEquals(LedgerLayout.STACKED, ledgerLayout(available = 320.dp, columns = huge))
    }

    /** The line itself: a title of exactly the minimum is still a title, a dp under it is not. */
    @Test
    fun `the switch is at the title minimum`() {
        val columns = LedgerColumns(length = 50.dp, status = 100.dp)
        val exactly = fixed + columns.length + columns.status + LedgerTitleMin

        assertEquals(LedgerLayout.COLUMNS, ledgerLayout(exactly, columns))
        assertEquals(LedgerLayout.STACKED, ledgerLayout(exactly - 1.dp, columns))
    }

    /** The caller may hold the ledger to a wider title; the same width then stacks. */
    @Test
    fun `a wider title minimum stacks a ledger that fitted`() {
        assertEquals(LedgerLayout.COLUMNS, ledgerLayout(411.dp, wide, titleMin = 96.dp))
        assertEquals(LedgerLayout.STACKED, ledgerLayout(411.dp, wide, titleMin = 160.dp))
    }
}
