package app.recly.android.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * docs/03 "앱에서 지우기": the delete dialog leads with how many parts are only on this phone, and
 * that count is a trip to the core. So the question the user sees is put up *after* an await, and
 * two taps in a row are two reads in flight — the Mac's `MenuModel.deleteAsked` is this counter, and
 * these are the two ways a late one used to lie.
 */
class DeleteAsksTest {

    @Test
    fun `a newer ask takes the older one's answer away`() {
        val asks = DeleteAsks()

        val first = asks.ask()
        val second = asks.ask()

        assertFalse(asks.isCurrent(first), "the slower read has no dialog left to open")
        assertTrue(asks.isCurrent(second), "the newest ask is the question")
    }

    /** A dismissal is the answer to a question nobody is asking any more, count or no count. */
    @Test
    fun `a cancel leaves a read in flight with nothing to say`() {
        val asks = DeleteAsks()

        val asked = asks.ask()
        asks.cancel()

        assertFalse(asks.isCurrent(asked), "a dismissed question does not reopen when its count lands")
    }
}
