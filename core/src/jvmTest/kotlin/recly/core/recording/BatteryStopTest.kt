package recly.core.recording

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/01 "Long recordings": when the battery ends a recording, on every shell that has one. */
class BatteryStopTest {

    @Test
    fun `five percent or less on battery is too low`() {
        assertTrue(BatteryStop.tooLow(percent = 5, unplugged = true))
        assertTrue(BatteryStop.tooLow(percent = 1, unplugged = true))
        assertTrue(BatteryStop.tooLow(percent = 0, unplugged = true))
        assertFalse(BatteryStop.tooLow(percent = 6, unplugged = true))
    }

    @Test
    fun `nothing is too low while it charges`() {
        assertFalse(BatteryStop.tooLow(percent = 2, unplugged = false))
    }

    @Test
    fun `a level the platform could not read stops nothing`() {
        assertFalse(BatteryStop.tooLow(percent = -1, unplugged = true))
        assertFalse(stops(-1 to true, -1 to true))
    }

    @Test
    fun `the first reading is the start's and never stops`() {
        assertFalse(BatteryStop().check(percent = 3, unplugged = true))
        assertFalse(BatteryStop().check(percent = 50, unplugged = true))
    }

    @Test
    fun `a recording started on a good battery stops when it drops to five percent`() {
        assertFalse(stops(50 to true, 6 to true))
        assertTrue(stops(60 to true, 5 to true))
        assertTrue(stops(60 to true, 1 to true))
        assertFalse(stops(60 to true, 5 to false), "on the charger the level only goes up")
    }

    /** The user chose to record on a low battery; stopping at once would leave next to nothing. */
    @Test
    fun `a recording started at five percent or less, unplugged, is never stopped for the battery`() {
        assertFalse(stops(4 to true, 4 to true))
        assertFalse(stops(5 to true, 1 to true))
        assertFalse(stops(4 to true, 6 to true, 2 to true))
    }

    @Test
    fun `a recording started low while charging stops once it is unplugged`() {
        assertTrue(stops(4 to false, 4 to true))
        assertTrue(stops(3 to false, 2 to true))
    }

    /** Unreadable at the start is not "started low": a later reading that is low stops it. */
    @Test
    fun `a start that could not be read does not count as starting low`() {
        assertTrue(stops(-1 to true, 4 to true))
    }

    /** True when any of the readings after [start] stops the recording. */
    private fun stops(start: Pair<Int, Boolean>, vararg then: Pair<Int, Boolean>): Boolean {
        val rule = BatteryStop()
        rule.check(start.first, start.second)
        return then.any { (percent, unplugged) -> rule.check(percent, unplugged) }
    }
}
