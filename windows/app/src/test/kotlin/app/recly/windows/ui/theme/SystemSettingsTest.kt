package app.recly.windows.ui.theme

import app.recly.windows.core.Host
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/09 "Disabled, focus and structure": the Windows settings the app follows, as answers. */
class SystemSettingsTest {

    @Test
    fun `motion is reduced only when the system turned animation off`() {
        assertTrue(reduceMotionOf(animationsOn = false))
        assertFalse(reduceMotionOf(animationsOn = true))
        // The macOS development host, and a Windows whose call failed.
        assertFalse(reduceMotionOf(animationsOn = null))
    }

    @Test
    fun `the text size is the system's percentage, inside the range Windows offers`() {
        assertEquals(1f, textScaleOf(null))
        assertEquals(1f, textScaleOf(100))
        assertEquals(1.5f, textScaleOf(150))
        assertEquals(2.25f, textScaleOf(225))
        assertEquals(2.25f, textScaleOf(400))
        assertEquals(1f, textScaleOf(50))
    }

    @Test
    fun `a host that is not Windows says nothing`() {
        if (Host.isWindows) return
        assertNull(systemAnimationsOn())
        assertNull(systemTextScale())
    }
}
