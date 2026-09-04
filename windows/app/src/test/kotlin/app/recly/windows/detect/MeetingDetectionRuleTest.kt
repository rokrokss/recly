@file:OptIn(ExperimentalTime::class)

package app.recly.windows.detect

import app.recly.windows.NOW
import app.recly.windows.detect.MeetingDetectionRule.Prompt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * M6-L3 deliverable 1: the detection rule as pure logic — mic in use × running app × cooldown.
 *
 * A port of `apple/RecKit/Tests/RecKitTests/MeetingDetectionRuleTests.swift`, case for case: the two
 * desktops answer the same question, and this is the file that would notice if one of them stopped.
 * The signals it reads come from a helper process and the Windows process table, neither of which a
 * test can stage; the decision they feed is entirely here, and it is the part that can be wrong in a
 * way the user notices (a notification every two seconds, or none at all).
 */
class MeetingDetectionRuleTest {

    private fun signals(
        mic: Boolean = false,
        app: String? = null,
        recording: Boolean = false,
    ) = MeetingDetectionRule.Signals(micInUse = mic, meetingApp = app, isRecording = recording)

    // --- the invitation to record -----------------------------------------------------------------

    /**
     * Either signal alone is somebody working, not somebody in a meeting: a voice memo in another
     * app, or Slack sitting in the tray all day.
     */
    @Test
    fun `neither signal alone offers a recording`() {
        val rule = MeetingDetectionRule()

        assertNull(rule.evaluate(signals(mic = true), NOW))
        assertNull(rule.evaluate(signals(app = "Zoom.exe"), NOW))
    }

    /**
     * ADR-011: 감지 → 확인 → 녹음. Both signals together is the offer, and docs/20 M6 says it is made
     * once — the tick two seconds later must not make it again.
     */
    @Test
    fun `both signals offer a recording exactly once`() {
        val rule = MeetingDetectionRule()

        assertEquals(Prompt.START, rule.evaluate(signals(mic = true, app = "Zoom.exe"), NOW))
        assertNull(rule.evaluate(signals(mic = true, app = "Zoom.exe"), NOW + 2.seconds))
        assertNull(rule.evaluate(signals(mic = true, app = "Zoom.exe"), NOW + 120.seconds))
    }

    /**
     * The meeting ends and another begins. The signal going away is what re-arms the offer — but not
     * before the cooldown is up, so a microphone that flickers cannot spam the user.
     */
    @Test
    fun `a second meeting is offered only after the cooldown`() {
        val rule = MeetingDetectionRule()
        assertEquals(Prompt.START, rule.evaluate(signals(mic = true, app = "Zoom.exe"), NOW))

        val leftEarly = NOW + 60.seconds
        assertNull(rule.evaluate(signals(), leftEarly))
        assertNull(
            rule.evaluate(signals(mic = true, app = "Zoom.exe"), leftEarly + 30.seconds),
            "within the cooldown",
        )

        val later = NOW + MeetingDetectionRule.COOLDOWN + 1.seconds
        assertEquals(Prompt.START, rule.evaluate(signals(mic = true, app = "Zoom.exe"), later))
    }

    /**
     * A recording the user stopped by hand while still in the meeting is a decision, not an
     * oversight: offering it straight back is the app arguing with them.
     */
    @Test
    fun `a recording stopped by hand is not offered back while the meeting runs`() {
        val rule = MeetingDetectionRule()
        assertNull(rule.evaluate(signals(mic = true, app = "ms-teams.exe", recording = true), NOW))

        val stopped = NOW + MeetingDetectionRule.COOLDOWN + 1.seconds
        assertNull(rule.evaluate(signals(mic = true, app = "ms-teams.exe"), stopped))
    }

    // --- the offer to end it ----------------------------------------------------------------------

    /**
     * docs/14 "감지": sixty unbroken seconds of an unused microphone — and only then. A meeting
     * that goes quiet for fifty seconds is a meeting.
     */
    @Test
    fun `an idle microphone offers to end the recording only after sixty seconds`() {
        val rule = MeetingDetectionRule()
        rule.evaluate(signals(mic = true, recording = true), NOW)

        val idleFrom = NOW + 10.seconds
        assertNull(rule.evaluate(signals(recording = true), idleFrom))
        assertNull(rule.evaluate(signals(recording = true), idleFrom + MeetingDetectionRule.MIC_IDLE - 1.seconds))
        assertEquals(
            Prompt.STOP,
            rule.evaluate(signals(recording = true), idleFrom + MeetingDetectionRule.MIC_IDLE),
        )
    }

    /**
     * The offer is made once. It is never a stop (docs/14: never an automatic stop), so the recording
     * is still running afterwards and the same idle microphone is still being read.
     */
    @Test
    fun `the offer to end is made once and the clock restarts when the microphone comes back`() {
        val rule = MeetingDetectionRule()
        rule.evaluate(signals(recording = true), NOW)
        assertEquals(Prompt.STOP, rule.evaluate(signals(recording = true), NOW + 60.seconds))
        assertNull(rule.evaluate(signals(recording = true), NOW + 200.seconds))

        // Someone speaks again, then the meeting really ends.
        assertNull(rule.evaluate(signals(mic = true, recording = true), NOW + 210.seconds))
        assertNull(rule.evaluate(signals(recording = true), NOW + 220.seconds))
        assertEquals(Prompt.STOP, rule.evaluate(signals(recording = true), NOW + 280.seconds))
    }

    /** The end offer belongs to the recording, not to the app: nothing is offered once it is over. */
    @Test
    fun `an idle microphone with no recording offers nothing`() {
        val rule = MeetingDetectionRule()

        assertNull(rule.evaluate(signals(), NOW))
        assertNull(rule.evaluate(signals(), NOW + 600.seconds))
    }
}
