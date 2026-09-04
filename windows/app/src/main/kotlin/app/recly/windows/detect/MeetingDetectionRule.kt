@file:OptIn(ExperimentalTime::class)

package app.recly.windows.detect

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * When to offer a recording and when to offer to end one (ADR-011: 감지 → 확인 → 녹음, never a
 * recording nobody asked for and never a stop nobody asked for).
 *
 * A port of the Mac's `MeetingDetectionRule` (docs/12 M4-L5), constant for constant and branch for
 * branch, and its tests come with it: the two desktops answer the same question and a Windows user
 * who has used the Mac must not find a different app under the notification.
 *
 * Pure, and separated from the two signal sources for exactly that reason: "mic in use × meeting app
 * × cooldown" is the part with the rules in it, and the part a test can hold still.
 */
class MeetingDetectionRule {

    enum class Prompt {
        /** "Are you in a meeting? Start recording" */
        START,

        /** "End the recording?" — an offer, never a stop (docs/14 "감지": never an automatic stop). */
        STOP,
    }

    data class Signals(
        /** The microphone is open for some process other than Recly's own capture helper. */
        val micInUse: Boolean,
        /** The meeting app this would be attributed to, or `null`. */
        val meetingApp: String?,
        /** A recording is in flight. */
        val isRecording: Boolean,
    )

    /**
     * False from the moment a prompt is made until the meeting signal goes away again — so one
     * meeting gets one invitation (docs/20 M6: "Teams 입장 → 알림"), and so a recording the user
     * stopped by hand is not immediately offered back to them.
     */
    private var armed = true
    private var lastPromptAt: Instant? = null
    private var micIdleSince: Instant? = null
    private var stopPrompted = false

    fun evaluate(signals: Signals, now: Instant): Prompt? {
        if (!signals.isRecording) {
            micIdleSince = null
            stopPrompted = false
            if (!signals.micInUse || signals.meetingApp == null) {
                armed = true
                return null
            }
            if (!armed) return null
            val last = lastPromptAt
            if (last != null && now - last < COOLDOWN) return null
            armed = false
            lastPromptAt = now
            return Prompt.START
        }

        // Recording. There is nothing to invite the user to start, and the meeting they stop by
        // hand must not be offered again while they are still in it.
        armed = false
        if (signals.micInUse) {
            // The microphone coming back is the meeting app taking the device again — a call that is
            // still going, or the next one. Either way the idle clock starts over, and so does the
            // one offer that clock is allowed to make.
            micIdleSince = null
            stopPrompted = false
            return null
        }
        val since = micIdleSince ?: now
        micIdleSince = since
        if (stopPrompted || now - since < MIC_IDLE) return null
        stopPrompted = true
        return Prompt.STOP
    }

    companion object {
        /**
         * A prompt the user ignored must not come back every two seconds. Ten minutes is long enough
         * that a declined offer stays declined for the length of a stand-up.
         */
        val COOLDOWN: Duration = 600.seconds

        /** docs/12 "종료 감지" · docs/14 "감지": sixty unbroken seconds of an unused microphone. */
        val MIC_IDLE: Duration = 60.seconds
    }
}
