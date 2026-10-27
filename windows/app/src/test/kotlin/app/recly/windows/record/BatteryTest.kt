package app.recly.windows.record

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * docs/01 "Long recordings — battery stop" on Windows: `GetSystemPowerStatus`'s bytes as the core's `BatteryStop`
 * reads them. The call itself needs Windows.
 */
class BatteryTest {

    @Test
    fun `a laptop on battery is its percent, unplugged`() {
        assertEquals(BatteryReading(5, true), WindowsPowerStatus.reading(acLine = 0, flag = 4, percent = 5))
        assertEquals(BatteryReading(80, false), WindowsPowerStatus.reading(acLine = 1, flag = 8, percent = 80))
    }

    @Test
    fun `a desktop has no battery to read`() {
        assertNull(WindowsPowerStatus.reading(acLine = 1, flag = 128, percent = 255))
    }

    /** 255 is "unknown" for both, and has the no-battery bit set too: it is unreadable, not a desktop. */
    @Test
    fun `unknown values stop nothing`() {
        assertEquals(BatteryReading(-1, true), WindowsPowerStatus.reading(acLine = 0, flag = 255, percent = 255))
        assertEquals(BatteryReading(3, false), WindowsPowerStatus.reading(acLine = 255, flag = 2, percent = 3))
    }
}
