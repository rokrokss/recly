package app.recly.android.ui.component

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * docs/09 화면 원칙 2: the status column used to be a flat 76dp, and `NEEDS_AUTH` — the code that
 * matters most, because it is the one the user has to act on — did not fit in it. The width the
 * screen measures is the widest code plus what the badge draws around it; this is that arithmetic.
 */
class StatusColumnTest {

    @Test
    fun `the column is the widest code plus the badge's own padding and borders`() {
        // 8dp of padding and one 1dp border on each side.
        assertEquals(118.dp, statusColumn(widest = 100.dp, line = 1.dp))
    }

    /** A larger font measures wider, and the column follows it rather than the other way round. */
    @Test
    fun `a wider measurement is a wider column`() {
        val scaled = statusColumn(widest = 130.dp, line = 1.dp)

        assertEquals(30.dp, scaled - statusColumn(widest = 100.dp, line = 1.dp))
    }

    @Test
    fun `an empty ledger still leaves room for the badge`() {
        assertEquals(18.dp, statusColumn(widest = 0.dp, line = 1.dp))
    }
}
