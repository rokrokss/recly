package app.recly.android.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * docs/09 "Typography" (UX decisions of 2026-10-08): `MM:SS` under an hour, `HH:MM:SS` from one; on a
 * recording's own screen the recording's length picks the format, so every time there has one width.
 */
class ClockFormatTest {

    @Test
    fun `a live timer follows the time itself`() {
        assertEquals("00:12", clock(12))
        assertEquals("59:59", clock(3599))
        assertEquals("01:00:00", clock(3600))
        assertEquals("12:34:56", clock(45296))
    }

    @Test
    fun `on a recording an hour long every time takes the hours`() {
        assertEquals("00:00:12", clock(12, scaleSec = 3725))
        assertEquals("01:02:05", clock(3725, scaleSec = 3725))
    }

    @Test
    fun `on a short recording, or one of unknown length, the time decides`() {
        assertEquals("00:12", clock(12, scaleSec = 2530))
        assertEquals("00:12", clock(12, scaleSec = null))
        assertEquals("01:00:01", clock(3601, scaleSec = 60))
    }

    @Test
    fun `what a screen reader hears keeps its one format`() {
        assertEquals("00:00:12", hms(12))
    }

    @Test
    fun `a backwards clock reads zero`() {
        assertEquals("00:00", clock(-5))
    }
}
