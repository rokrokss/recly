package recly.core.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * docs/01 "Long recordings — watchdog · reminders · battery stop · waiting to resume": the reminder comes after ten
 * minutes without speech, whatever the microphone's gain, and never in a conversation. The one rule every
 * shell calls (Android, Apple through the XCFramework, Windows).
 */
class SilenceReminderTest {

    private val minute = 60 * SilenceReminder.WINDOWS_PER_SECOND
    private val tenMinutes = SilenceReminder.REMIND_AFTER_WINDOWS

    @Test
    fun `steady silence reminds once, at ten minutes`() {
        assertEquals(listOf(tenMinutes - 1), reminders(List(20 * minute) { room(QUIET, it) }))
    }

    @Test
    fun `a conversation is never reminded`() {
        assertEquals(emptyList(), reminders(List(30 * minute) { conversation(it) }))
    }

    /** A meeting where somebody says a sentence every few minutes is a meeting, not a forgotten recording. */
    @Test
    fun `a few sentences every few minutes keep it from reminding`() {
        val windows = List(30 * minute) { if (it % (5 * minute) < 30) speech(it) else room(QUIET, it) }

        assertEquals(emptyList(), reminders(windows))
    }

    /** The same room recorded ten times quieter or three times louder reminds at the same window. */
    @Test
    fun `the microphone's gain does not matter, only the room's own floor`() {
        val windows = List(9 * minute) { room(QUIET, it) } + List(50) { speech(it) } + List(15 * minute) { room(QUIET, it) }
        val expected = reminders(windows)

        assertEquals(1, expected.size)
        assertEquals(expected, reminders(windows.map { it * 0.1f }))
        assertEquals(expected, reminders(windows.map { minOf(1f, it * 3f) }))
    }

    @Test
    fun `a noisy room with nobody in it is silence`() {
        assertEquals(listOf(tenMinutes - 1), reminders(List(12 * minute) { room(0.05f, it) }))
    }

    /** A cough, a door, a chime: shorter than two seconds within ten, so the clock runs on. */
    @Test
    fun `short blips do not reset the clock`() {
        val windows = List(12 * minute) {
            when {
                it % 200 < 3 -> 0.5f
                it in 3_000 until 3_015 -> 0.6f
                else -> room(QUIET, it)
            }
        }

        assertEquals(listOf(tenMinutes - 1), reminders(windows))
    }

    /** Speech sets the clock back to zero; the next ten quiet minutes remind again. */
    @Test
    fun `sustained speech resets the clock, and the silence after it reminds again`() {
        val firstSpeech = 9 * minute
        val secondSpeech = 25 * minute
        val windows = List(40 * minute) {
            when (it) {
                in firstSpeech until firstSpeech + 50 -> speech(it)
                in secondSpeech until secondSpeech + 50 -> speech(it)
                else -> room(QUIET, it)
            }
        }

        val reminded = reminders(windows)

        assertEquals(2, reminded.size, "$reminded")
        assertInRange(reminded[0], firstSpeech + 50 + tenMinutes - 1)
        assertInRange(reminded[1], secondSpeech + 50 + tenMinutes - 1)
    }

    /** A call takes the microphone and the platform feeds zeroes: nobody could be heard, so the clock waits. */
    @Test
    fun `digital zero neither counts nor resets`() {
        val windows = List(5 * minute) { room(QUIET, it) } + List(20 * minute) { 0f } + List(6 * minute) { room(QUIET, it) }

        assertEquals(listOf(5 * minute + 20 * minute + 5 * minute - 1), reminders(windows))
    }

    /** A fan that starts is a sound for its first minute, then it is the room. */
    @Test
    fun `a fan that keeps running becomes the room`() {
        val fanOn = 2 * minute
        val windows = List(fanOn) { room(QUIET, it) } + List(15 * minute) { room(0.03f, it) }

        val reminded = reminders(windows)

        assertEquals(1, reminded.size, "$reminded")
        assertTrue(reminded[0] in fanOn + tenMinutes..fanOn + minute + RECENT + tenMinutes, "$reminded")
    }

    /** Behind a noise suppressor the floor is near zero; ten dB over it is still nobody speaking. */
    @Test
    fun `nothing under about -55 dBFS is speech`() {
        val windows = List(12 * minute) { if (it % 10 < 5) 0.0008f else room(0.00005f, it) }

        assertEquals(listOf(tenMinutes - 1), reminders(windows))
    }

    private fun reminders(windows: List<Float>): List<Int> {
        val reminder = SilenceReminder()
        return windows.indices.filter { reminder.add(windows[it]) }
    }

    /** Within the ten seconds the speech takes to leave the recent windows. */
    private fun assertInRange(actual: Int, earliest: Int) {
        assertTrue(actual in earliest..earliest + RECENT, "expected $earliest..${earliest + RECENT}, got $actual")
    }

    private companion object {
        /** A quiet room's floor, about −50 dBFS. */
        const val QUIET = 0.003f
        const val RECENT = SilenceReminder.RECENT_WINDOWS

        /** A steady room swings ±3 dB from window to window. */
        val JITTER = floatArrayOf(1f, 0.8f, 1.25f, 0.9f, 1.1f, 0.75f, 1.35f, 0.85f, 1f, 1.2f, 0.95f, 0.71f, 1.41f)

        /** One second of talking: syllables, with a gap or two at the room's level. */
        val SYLLABLES = floatArrayOf(0.25f, 0.4f, 0.1f, 0f, 0.3f, 0.35f, 0f, 0.2f, 0.45f, 0f)

        fun room(level: Float, index: Int): Float = level * JITTER[index % JITTER.size]

        fun speech(index: Int): Float = SYLLABLES[index % SYLLABLES.size].takeIf { it > 0f } ?: room(QUIET, index)

        /** Four seconds of talking, two of pause, over and over. */
        fun conversation(index: Int): Float = if (index % 60 < 40) speech(index) else room(QUIET, index)
    }
}
