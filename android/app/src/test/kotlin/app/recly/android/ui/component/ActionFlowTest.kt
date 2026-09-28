package app.recly.android.ui.component

import kotlin.test.Test
import kotlin.test.assertEquals

/** Where [ActionFlow] puts an expanded row's buttons — the whole of what it decides. */
class ActionFlowTest {

    /** Open in Drive, Retry and Details at 360dp: two lines, Delete at the end of the second. */
    @Test
    fun `the trailing button ends the last line when it fits there`() {
        val spots = actionFlow(listOf(99, 88, 120, 56), width = 258, gap = 8, trailingLast = true)

        assertEquals(listOf(Spot(0, 0), Spot(107, 0), Spot(0, 1), Spot(258 - 56, 1)), spots)
    }

    @Test
    fun `a trailing button that does not fit takes a line of its own at the end`() {
        val spots = actionFlow(listOf(120, 120, 56), width = 258, gap = 8, trailingLast = true)

        assertEquals(listOf(Spot(0, 0), Spot(128, 0), Spot(258 - 56, 1)), spots)
    }

    /** A recording in flight has no Delete: its last button is not pushed to the end. */
    @Test
    fun `without a trailing button the last one flows like the rest`() {
        val spots = actionFlow(listOf(99, 120), width = 258, gap = 8, trailingLast = false)

        assertEquals(listOf(Spot(0, 0), Spot(107, 0)), spots)
    }

    @Test
    fun `a button wider than the row starts a line and is not pushed off it`() {
        val spots = actionFlow(listOf(300, 56), width = 258, gap = 8, trailingLast = true)

        assertEquals(listOf(Spot(0, 0), Spot(258 - 56, 1)), spots)
    }
}
