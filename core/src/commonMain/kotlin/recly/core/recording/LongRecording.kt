package recly.core.recording

/**
 * docs/01 "Long recordings — watchdog · reminders · battery stop · waiting to resume": when a recording has run
 * for ten minutes with nobody speaking — the one that was forgotten, or walked away from — read off
 * the tenth-of-a-second peaks the live strip draws. One rule for every shell that records: Android
 * (phone, Galaxy Watch) and Apple (iPhone, Apple Watch, Mac) feed it their recorder's own windows,
 * Windows the capture helper's `level` lines.
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
 *   waits, and a call is never "nobody spoke". (An interruption on Apple stops the input instead, and
 *   then no windows arrive at all.)
 *
 * Not thread-safe: one reader feeds it. It allocates nothing after construction, because Apple calls
 * it from the recorder's write path.
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

/**
 * docs/01 "Long recordings": stop a recording before the device shuts itself down in the middle of a
 * segment, which would cost everything since the last boundary. One per recording, fed every reading
 * of the battery — the first one being the reading at the start. Every shell with a battery reads it
 * its own way (Android's sticky `ACTION_BATTERY_CHANGED`, `UIDevice` / `WKInterfaceDevice`, IOKit power
 * sources on the Mac, `GetSystemPowerStatus` on Windows) and hands it over as a whole percent.
 *
 * - [STOP_PERCENT] or less, unplugged, stops it. Not while it charges: then the level only goes up.
 * - Not a recording that **started** that low, unplugged: the user chose to record on a low battery,
 *   and stopping it at once would leave a recording with next to nothing in it. One that started on
 *   the charger is stopped once it is unplugged that low.
 * - A level the platform cannot read (−1) stops nothing.
 *
 * Not thread-safe: one reader feeds it.
 */
class BatteryStop {
    /** [tooLow] of the first reading, once there has been one. */
    private var startedLow: Boolean? = null

    /** True when this reading should stop the recording. The first reading, the start's, never does. */
    fun check(percent: Int, unplugged: Boolean): Boolean {
        val low = tooLow(percent, unplugged)
        val started = startedLow
        if (started == null) {
            startedLow = low
            return false
        }
        return !started && low
    }

    companion object {
        const val STOP_PERCENT: Int = 5

        /** How often the shells that poll (Apple, the Mac, Windows) read the battery while a recording runs. */
        const val CHECK_SEC: Int = 60

        fun tooLow(percent: Int, unplugged: Boolean): Boolean = unplugged && percent in 0..STOP_PERCENT
    }
}
