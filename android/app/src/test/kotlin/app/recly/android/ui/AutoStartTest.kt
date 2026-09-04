package app.recly.android.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * docs/11 A9 "spend or drop": what a tile, widget or shortcut tap is worth by the time the app is
 * in a position to act on it. The regression is a tap that was neither spent nor dropped — it sat
 * on the ViewModel behind another tab and opened the microphone on whatever switch to Record came
 * next, which could be minutes or hours later.
 */
class AutoStartTest {

    @Test
    fun `a tap the app reaches straight away is a recording`() {
        assertTrue(autoStartStillWanted(requestedAt = 1_000, now = 1_000))
        assertTrue(autoStartStillWanted(requestedAt = 1_000, now = 1_400))
    }

    /** The edge is inclusive: the tap the app only just caught still counts. */
    @Test
    fun `a tap is still wanted right up to the deadline`() {
        assertTrue(autoStartStillWanted(requestedAt = 1_000, now = 1_000 + AUTO_START_TTL_MS))
        assertFalse(autoStartStillWanted(requestedAt = 1_000, now = 1_000 + AUTO_START_TTL_MS + 1))
    }

    /** Long past it, the user has moved on — starting then would be the app recording by itself. */
    @Test
    fun `a tap the app never got to is dropped`() {
        assertFalse(autoStartStillWanted(requestedAt = 1_000, now = 5 * 60_000))
    }

    /** A clock that went backwards says nothing about what the user wanted; it is not a licence. */
    @Test
    fun `an age below zero is dropped`() {
        assertFalse(autoStartStillWanted(requestedAt = 5_000, now = 1_000))
    }

    /**
     * The regression the tap stamp is for. The request used to be timestamped where the activity
     * consumed the intent, so the age it measured started *after* the launch: a tile tap that took
     * the app twelve seconds to come up read as zero seconds old and opened the microphone for a
     * tap the user had long since given up on. `MainActivity.EXTRA_REQUESTED_AT` carries the tap
     * instead, and the age is then the real one.
     */
    @Test
    fun `a tap twelve seconds before the app came up is dropped`() {
        val clock = FakeClock(now = 30_000)

        // The tile builds its intent in `onClick`, so this is the tap the intent carries.
        val requestedAt = clock.elapsedRealtime()
        // The launch: process start, onCreate reading the stamp off the intent, first composition.
        clock.advance(12_000)
        val consumedAt = clock.elapsedRealtime()
        clock.advance(50)

        assertFalse(autoStartStillWanted(requestedAt, clock.elapsedRealtime()))
        // What the old code stamped instead — the launch was free, so the tap always survived it.
        assertTrue(autoStartStillWanted(consumedAt, clock.elapsedRealtime()))
    }

    /**
     * The launcher shortcut is static XML and the widget's `PendingIntent` is built when the widget
     * is redrawn rather than when it is tapped, so neither has a tap to stamp: `MainActivity` reads
     * a missing extra as the moment it got the intent, and the same twelve-second launch is honoured
     * — no worse than the behaviour this replaced, and never worse than dropping every tap.
     */
    @Test
    fun `a tap with no stamp is measured from the app coming up`() {
        val clock = FakeClock(now = 30_000)

        clock.advance(12_000)
        val requestedAt = clock.elapsedRealtime()
        clock.advance(50)

        assertTrue(autoStartStillWanted(requestedAt, clock.elapsedRealtime()))
    }
}

/** `SystemClock.elapsedRealtime()` with the test holding the hands. */
private class FakeClock(private var now: Long) {

    fun elapsedRealtime(): Long = now

    fun advance(ms: Long) {
        now += ms
    }
}
