package app.recly.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/11 ":android:recording": when each alert of a long recording goes off. */
class RecordingAlertsTest {

    private val minute = 60_000L

    @Test
    fun `the watchdog waits two segments, so one late boundary is not a dead recording`() {
        assertEquals(30 * minute, RecordingAlerts.watchdogDelayMs(SegmentedRecorder.DEFAULT_SEGMENT_SEC))
        assertEquals(20_000L, RecordingAlerts.watchdogDelayMs(10))
    }

    @Test
    fun `five percent on battery stops the recording`() {
        assertTrue(RecordingAlerts.batteryTooLow(level = 5, scale = 100, plugged = 0))
        assertTrue(RecordingAlerts.batteryTooLow(level = 1, scale = 100, plugged = 0))
        assertFalse(RecordingAlerts.batteryTooLow(level = 6, scale = 100, plugged = 0))
    }

    @Test
    fun `the level is a share of the scale, not a percentage`() {
        assertTrue(RecordingAlerts.batteryTooLow(level = 10, scale = 200, plugged = 0))
        assertFalse(RecordingAlerts.batteryTooLow(level = 12, scale = 200, plugged = 0))
    }

    @Test
    fun `nothing stops while it charges`() {
        assertFalse(RecordingAlerts.batteryTooLow(level = 2, scale = 100, plugged = 1))
        assertFalse(RecordingAlerts.batteryTooLow(level = 2, scale = 100, plugged = 4))
    }

    @Test
    fun `a recording started on a good battery stops when it drops to five percent`() {
        val startedLow = RecordingAlerts.batteryTooLow(level = 50, scale = 100, plugged = 0)

        assertFalse(RecordingAlerts.batteryStops(startedLow, level = 6, scale = 100, plugged = 0))
        assertTrue(RecordingAlerts.batteryStops(startedLow, level = 5, scale = 100, plugged = 0))
    }

    /** The user chose to record on a low battery; stopping at once would leave next to nothing. */
    @Test
    fun `a recording started at five percent or less, unplugged, is never stopped for the battery`() {
        val startedLow = RecordingAlerts.batteryTooLow(level = 4, scale = 100, plugged = 0)

        assertFalse(RecordingAlerts.batteryStops(startedLow, level = 4, scale = 100, plugged = 0))
        assertFalse(RecordingAlerts.batteryStops(startedLow, level = 1, scale = 100, plugged = 0))
    }

    @Test
    fun `a recording started low while charging stops once it is unplugged`() {
        val startedLow = RecordingAlerts.batteryTooLow(level = 4, scale = 100, plugged = 1)

        assertTrue(RecordingAlerts.batteryStops(startedLow, level = 4, scale = 100, plugged = 0))
    }

    @Test
    fun `a level the platform could not read stops nothing`() {
        assertFalse(RecordingAlerts.batteryTooLow(level = -1, scale = 100, plugged = 0))
        assertFalse(RecordingAlerts.batteryTooLow(level = 3, scale = -1, plugged = 0))
        assertFalse(RecordingAlerts.batteryTooLow(level = 3, scale = 100, plugged = -1))
    }
}
