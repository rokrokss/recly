@file:OptIn(ExperimentalTime::class)

package app.recly.windows.helper

import app.recly.windows.NOW
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * M6-L3 deliverable 3 · deliverable 5: restart the helper when it dies — but not forever. A detection
 * helper that cannot run on this machine would otherwise be spawned every five seconds for the rest
 * of the session, and nothing about the tenth attempt is more likely to work than the fourth.
 */
class HelperRestartsTest {

    @Test
    fun `a few restarts are allowed and then the app stops trying`() {
        val restarts = HelperRestarts()

        repeat(HelperRestarts.MAX) { attempt ->
            assertTrue(restarts.allow(NOW + (attempt * 5).seconds), "attempt ${attempt + 1}")
        }
        assertFalse(restarts.allow(NOW + 20.seconds))
    }

    /** The budget is a window, not a life sentence: a helper that failed at breakfast may try again. */
    @Test
    fun `the budget comes back once the window has passed`() {
        val restarts = HelperRestarts()
        repeat(HelperRestarts.MAX) { restarts.allow(NOW) }
        assertFalse(restarts.allow(NOW + 1.minutes))

        assertTrue(restarts.allow(NOW + HelperRestarts.WINDOW + 1.minutes))
    }

    /** Crashes spread thinly are not a broken machine, and must not accumulate into one. */
    @Test
    fun `a crash an hour never runs out of budget`() {
        val restarts = HelperRestarts()

        repeat(10) { hour ->
            assertTrue(restarts.allow(NOW + (hour * 60).minutes), "hour $hour")
        }
    }
}
