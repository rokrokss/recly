package app.recly.windows.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * docs/14 deliverable 5 (2026-10-09): Settings says nothing about a capture helper that works, and one line when
 * recording cannot start on this PC — never on the development host, and not before the shell has looked.
 */
class HelperLineTest {

    @Test
    fun `a missing or silent helper on Windows is said once the shell is ready`() {
        assertTrue(helperUnavailable(windows = true, ready = true, missing = true, version = null))
        assertTrue(helperUnavailable(windows = true, ready = true, missing = false, version = null))
        assertFalse(helperUnavailable(windows = true, ready = true, missing = false, version = "recly-capture 0.3.0"))
        assertFalse(helperUnavailable(windows = true, ready = false, missing = true, version = null))
    }

    @Test
    fun `the development host says nothing about it`() {
        assertFalse(helperUnavailable(windows = false, ready = true, missing = true, version = null))
        assertFalse(helperUnavailable(windows = false, ready = true, missing = false, version = null))
    }
}
