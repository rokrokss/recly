package app.recly.android.ui

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * docs/09 screen principle 2: the ledger's time column is `MM-dd` over `HH:mm` in every language. A
 * twelve-hour clock without the day period would write a recording started at 15:05 as `03:05` —
 * the same text as one started at 03:05.
 */
class LedgerTimeColumnTest {

    @Test
    fun `the time column is month first and twenty-four hour`() {
        val at = LocalDateTime.of(2026, 2, 9, 15, 5).atZone(ZoneId.systemDefault()).toInstant().toString()

        assertEquals("02-09", ledgerColumn(at, LEDGER_DATE))
        assertEquals("15:05", ledgerColumn(at, LEDGER_TIME))
    }
}
