package app.recly.recording

/**
 * docs/01 "Long recordings — watchdog · reminders · battery stop · waiting to resume": when a recording has run
 * for ten minutes with nobody speaking — the one that was forgotten, or walked away from — read off
 * the tenth-of-a-second peaks the live strip draws. RecKit's `SilenceReminder` is the same rule with
 * the same constants; the two are meant to stay readable side by side.
 *
 * Speech is told from the room by its level against the recording's own floor, so no microphone's
 * gain is assumed:
 * - The floor is the quietest window of the last minute: the room between words, or the steady
 *   hum of a fan once it has run for a minute.
 * - A window sounds when its peak is [ABOVE_FLOOR] (10 dB) over that floor and at least
 *   [MIN_SOUND] (about −55 dBFS) — a floor near zero, behind a noise suppressor, must not make a
 *   pin drop a voice.
 * - Speech is [SPEECH_WINDOWS] sounding windows (2 s) within the last [RECENT_WINDOWS] (10 s):
 *   a cough, a door or a notification chime is shorter and leaves the clock running; a sentence is
 *   longer and sets it back to zero.
 * - A window of digital zero is a microphone the platform has taken away (docs/03 `silenced`: a
 *   call, the privacy toggle). Nobody could be heard, so it is neither silence nor speech: the clock
 *   waits, and a call is never "nobody spoke".
 *
 * Not thread-safe: one poller feeds it.
 */
class SilenceReminder {
    /** The quietest window of each of the last [FLOOR_SECONDS] seconds, as a ring. */
    private val quietest = FloatArray(FLOOR_SECONDS) { Float.MAX_VALUE }
    private var quietestNext = 0
    private var quietestOfMinute = Float.MAX_VALUE
    private var secondQuietest = Float.MAX_VALUE
    private var secondWindows = 0

    /** Whether each of the last [RECENT_WINDOWS] windows sounded, as a ring, and how many did. */
    private val recent = BooleanArray(RECENT_WINDOWS)
    private var recentNext = 0
    private var recentSounding = 0

    /** Windows since speech was last heard, or since the start. */
    private var quietWindows = 0

    /**
     * One window's peak, 0–1. True at the window that completes [REMIND_AFTER_WINDOWS] without
     * speech — once: the next reminder needs speech first, then ten more quiet minutes.
     */
    fun add(peak: Float): Boolean {
        if (peak <= 0f) return false
        secondQuietest = minOf(secondQuietest, peak)
        val floor = minOf(quietestOfMinute, secondQuietest)
        val sounding = peak >= maxOf(MIN_SOUND, floor * ABOVE_FLOOR)

        if (recent[recentNext]) recentSounding--
        recent[recentNext] = sounding
        if (sounding) recentSounding++
        recentNext = (recentNext + 1) % RECENT_WINDOWS

        if (++secondWindows == WINDOWS_PER_SECOND) {
            quietest[quietestNext] = secondQuietest
            quietestNext = (quietestNext + 1) % FLOOR_SECONDS
            quietestOfMinute = quietest.min()
            secondQuietest = Float.MAX_VALUE
            secondWindows = 0
        }

        if (recentSounding >= SPEECH_WINDOWS) {
            quietWindows = 0
            return false
        }
        return ++quietWindows == REMIND_AFTER_WINDOWS
    }

    companion object {
        /** Ten minutes of tenth-of-a-second windows; the notification's text says "10 minutes". */
        const val REMIND_AFTER_WINDOWS: Int = 6_000
        const val WINDOWS_PER_SECOND: Int = 10
        const val FLOOR_SECONDS: Int = 60

        /** 10 dB as an amplitude ratio (10^(10/20)). */
        const val ABOVE_FLOOR: Float = 3.1623f

        /** About −55 dBFS as a peak amplitude (10^(−55/20)). */
        const val MIN_SOUND: Float = 0.0017783f
        const val RECENT_WINDOWS: Int = 100
        const val SPEECH_WINDOWS: Int = 20
    }
}
