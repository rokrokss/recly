package app.recly.wear.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ElapsedTest {

    @Test
    fun `under an hour the hour is not shown`() {
        assertEquals("00:00", formatElapsed(0))
        assertEquals("00:09", formatElapsed(9))
        assertEquals("01:00", formatElapsed(60))
        assertEquals("59:59", formatElapsed(3599))
    }

    /** docs/20 S1 measures a three-hour recording; the hour cannot roll over silently. */
    @Test
    fun `past an hour the hour appears, un-padded`() {
        assertEquals("1:00:00", formatElapsed(3600))
        assertEquals("3:00:01", formatElapsed(10801))
        assertEquals("12:34:56", formatElapsed(45296))
    }

    /** A clock that stepped backwards between two ticks must not print a negative timer. */
    @Test
    fun `a backwards clock reads zero`() {
        assertEquals("00:00", formatElapsed(-5))
    }
}
