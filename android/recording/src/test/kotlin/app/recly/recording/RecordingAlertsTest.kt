package app.recly.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * docs/11 ":android:recording": when each alert of a long recording goes off. The battery rule itself is
 * the core's `BatteryStop` (`BatteryStopTest`); here is only how Android's reading is handed to it.
 */
class RecordingAlertsTest {

    private val minute = 60_000L

    @Test
    fun `the watchdog waits two segments, so one late boundary is not a dead recording`() {
        assertEquals(10 * minute, RecordingAlerts.watchdogDelayMs(SegmentedRecorder.DEFAULT_SEGMENT_SEC))
        assertEquals(20_000L, RecordingAlerts.watchdogDelayMs(10))
    }

    @Test
    fun `the level is a share of the scale, not a percentage`() {
        assertEquals(5, RecordingAlerts.batteryPercent(level = 5, scale = 100))
        assertEquals(5, RecordingAlerts.batteryPercent(level = 10, scale = 200))
        assertEquals(6, RecordingAlerts.batteryPercent(level = 12, scale = 200))
    }

    @Test
    fun `a level the platform could not read is -1, which stops nothing`() {
        assertEquals(-1, RecordingAlerts.batteryPercent(level = -1, scale = 100))
        assertEquals(-1, RecordingAlerts.batteryPercent(level = 3, scale = -1))
        assertEquals(-1, RecordingAlerts.batteryPercent(level = 3, scale = 0))
    }

    @Test
    fun `only a battery that says it is on battery is unplugged`() {
        assertTrue(RecordingAlerts.unplugged(plugged = 0))
        assertFalse(RecordingAlerts.unplugged(plugged = 1), "AC")
        assertFalse(RecordingAlerts.unplugged(plugged = 4), "wireless")
        assertFalse(RecordingAlerts.unplugged(plugged = -1), "a reading without the extra stops nothing")
    }
}
