@file:OptIn(ExperimentalTime::class)

package app.recly.windows.detect

import app.recly.windows.FixedClock
import app.recly.windows.SilentLogger
import app.recly.windows.detect.MeetingDetectionRule.Prompt
import app.recly.windows.helper.FakeHelperCommand
import app.recly.windows.helper.HelperClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * M6-L3 deliverable 5: the routing between the rule and the shell — a notification or an automatic
 * recording, the toast's action reaching the model, and the two things that make an offer or a
 * microphone report *stale* (the situation passing, and detection changing hands).
 *
 * The helper is `null` in most of these (there is no capture helper on the development host, M6-L1
 * "환경 제약"), so the microphone events are handed over exactly as the recorder's reader hands them
 * over, and the two-second tick is driven by hand — the timer is not the part that can be wrong in a
 * way the user notices.
 */
class MeetingDetectorTest {

    private class FakeNotifier : MeetingNotifier {
        override var onAction: ((Long) -> Unit)? = null
        val posted = mutableListOf<Pair<Prompt, Long>>()
        val invalidated = mutableListOf<Long>()

        override fun post(prompt: Prompt, token: Long) {
            posted += prompt to token
        }

        override fun invalidate(token: Long) {
            invalidated += token
        }

        /** The balloon (or the tray item behind it) being taken. */
        fun take(token: Long = posted.last().second) = onAction!!.invoke(token)
    }

    private class FakeActions(var recording: Boolean = false, var startable: Boolean = true) :
        MeetingDetector.Actions {
        var starts = 0
        var stops = 0
        var offer: Prompt? = null
        var token: Long = MeetingDetector.NO_OFFER

        override fun canStart() = startable

        override fun isRecording() = recording

        override fun start() {
            starts++
        }

        override fun stop() {
            stops++
        }

        override fun offered(prompt: Prompt?, token: Long) {
            offer = prompt
            this.token = token
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clock = FixedClock()
    private val notifier = FakeNotifier()

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun detector(
        actions: FakeActions,
        processes: Set<String> = setOf("Zoom.exe"),
        helper: () -> HelperClient? = { null },
    ) = MeetingDetector(
        helper = helper,
        apps = FixedApps(processes),
        notifier = notifier,
        actions = actions,
        clock = clock,
        scope = scope,
        logger = SilentLogger,
        // Long enough that the ticker never fires: every pass in this file is made by hand.
        tickInterval = 1.hours,
    ).also { it.start() }

    /** Which recording session owns detection in this test, once one has taken it. */
    private var owner: Long = MeetingDetector.NO_OWNER

    /** The microphone, as the recorder's helper reports it once detection has been handed over. */
    private fun MeetingDetector.recorderSees(app: String, inUse: Boolean) {
        if (owner == MeetingDetector.NO_OWNER) owner = runBlocking { yieldToRecorder() }
        micInUse(owner, app, inUse)
    }

    @Test
    fun `a meeting is offered once, and the tray carries the offer the balloon cannot`() {
        val actions = FakeActions()
        val detector = detector(actions)

        detector.detectSaw("Zoom.exe", inUse = true)
        detector.evaluate()
        detector.evaluate()

        assertEquals(1, notifier.posted.size)
        assertEquals(Prompt.START, notifier.posted.single().first)
        assertEquals(Prompt.START, actions.offer)
        assertEquals(notifier.posted.single().second, actions.token)
        assertEquals(0, actions.starts, "ADR-011: detect, then ask, then record")
    }

    /** Deliverable 5: the toast's action reaches the model — and spends the offer doing it. */
    @Test
    fun `taking the offer starts the recording`() {
        val actions = FakeActions()
        val detector = detector(actions)

        detector.detectSaw("Zoom.exe", inUse = true)
        detector.evaluate()
        notifier.take()

        assertEquals(1, actions.starts)
        assertNull(actions.offer)
        assertEquals(MeetingDetector.NO_OFFER, actions.token)
    }

    /** An offer that cannot be taken is worse than no offer (the shell is not ready, or is busy). */
    @Test
    fun `nothing is offered while the shell cannot start a recording`() {
        val actions = FakeActions(startable = false)
        val detector = detector(actions)

        detector.detectSaw("Zoom.exe", inUse = true)
        detector.evaluate()

        assertTrue(notifier.posted.isEmpty())
        assertEquals(0, actions.starts)
    }

    /** Nothing running that looks like a meeting: a microphone is a voice memo. */
    @Test
    fun `a microphone with no meeting app offers nothing`() {
        val actions = FakeActions()
        val detector = detector(actions, processes = setOf("explorer.exe"))

        detector.detectSaw("SoundRecorder.exe", inUse = true)
        detector.evaluate()

        assertTrue(notifier.posted.isEmpty())
    }

    /**
     * docs/14 "감지": sixty seconds idle → "End the recording?", and it is an offer — the recording
     * is still running until the user says otherwise.
     */
    @Test
    fun `an idle microphone offers to end the recording and never ends it`() {
        val actions = FakeActions(recording = true)
        val detector = detector(actions)

        detector.recorderSees("Zoom.exe", inUse = true)
        detector.evaluate()
        detector.recorderSees("Zoom.exe", inUse = false)
        detector.evaluate()
        assertTrue(notifier.posted.isEmpty(), "fifty seconds of quiet is a meeting")

        clock.instant += MeetingDetectionRule.MIC_IDLE + 1.seconds
        detector.evaluate()
        assertEquals(Prompt.STOP, notifier.posted.single().first)
        assertEquals(0, actions.stops, "docs/14: never an automatic stop")

        notifier.take()
        assertEquals(1, actions.stops)
        assertNull(actions.offer)
    }

    // --- offers go stale --------------------------------------------------------------------------

    /**
     * A balloon outlives the meeting that raised it. Taking it half an hour later would start a
     * recording of an empty room, so the offer is withdrawn the moment its own signals stop holding
     * — from the notification, from the tray item, and from the token.
     */
    @Test
    fun `a start offer stops working once the meeting is over`() {
        val actions = FakeActions()
        val detector = detector(actions)
        detector.detectSaw("Zoom.exe", inUse = true)
        detector.evaluate()
        val token = notifier.posted.single().second

        detector.detectSaw("Zoom.exe", inUse = false)
        detector.evaluate()

        assertEquals(listOf(token), notifier.invalidated)
        assertNull(actions.offer)
        assertEquals(MeetingDetector.NO_OFFER, actions.token)

        notifier.take(token)
        detector.act(token)
        assertEquals(0, actions.starts, "the meeting it was about has ended")
    }

    /** And the other way: the room went quiet, then someone spoke. The recording is still wanted. */
    @Test
    fun `a stop offer is withdrawn at the next pass when the microphone comes back`() {
        val actions = FakeActions(recording = true)
        val detector = detector(actions)
        detector.recorderSees("Zoom.exe", inUse = true)
        detector.evaluate()
        detector.recorderSees("Zoom.exe", inUse = false)
        detector.evaluate()
        clock.instant += MeetingDetectionRule.MIC_IDLE + 1.seconds
        detector.evaluate()
        val token = notifier.posted.single().second

        detector.recorderSees("Zoom.exe", inUse = true)
        detector.evaluate()

        assertEquals(listOf(token), notifier.invalidated)
        detector.act(token)
        assertEquals(0, actions.stops, "somebody is speaking")
    }

    /** A token that was never the current one — a second click, a menu drawn a minute ago. */
    @Test
    fun `a token that is not the outstanding offer does nothing`() {
        val actions = FakeActions()
        val detector = detector(actions)
        detector.detectSaw("Zoom.exe", inUse = true)
        detector.evaluate()

        detector.act(MeetingDetector.NO_OFFER)
        detector.act(notifier.posted.single().second + 1)

        assertEquals(0, actions.starts)
    }

    // --- one helper at a time ---------------------------------------------------------------------

    /**
     * The generation check: a `mic_in_use` counts only while its helper still owns detection.
     * Anything else is the previous owner's event arriving late, and it must not move the rule.
     */
    @Test
    fun `microphone events count only while their session owns detection`() = runBlocking {
        val actions = FakeActions()
        val detector = detector(actions)

        // A token no session ever held: not the recorder's to report.
        detector.micInUse(MeetingDetector.NO_OWNER, "Zoom.exe", inUse = true)
        detector.evaluate()
        assertTrue(notifier.posted.isEmpty())

        // A session takes over, and the same event counts.
        val session = detector.yieldToRecorder()
        detector.micInUse(session, "Zoom.exe", inUse = true)
        detector.evaluate()
        assertEquals(1, notifier.posted.size)

        // It hands detection back; whatever was still in flight from it no longer counts.
        detector.resume(session)
        detector.micInUse(session, "Zoom.exe", inUse = true)
        detector.evaluate()
        assertEquals(1, notifier.posted.size)
    }

    /**
     * docs/03 `StopResult.Deferred`: a stop that could not file every part clears the session but
     * leaves its consumer running, and that consumer reaches EOF long after a later recording has
     * taken the helper. Its `resume` would put a detect-only helper beside the running one, and its
     * queued microphone events would be a finished meeting's.
     */
    @Test
    fun `a stale session's late EOF neither resumes detection nor moves the rule`() = runBlocking {
        var opens = 0
        val actions = FakeActions()
        val detector = detector(actions, helper = { opens++; null })
        val started = opens

        val stale = detector.yieldToRecorder()
        val current = detector.yieldToRecorder()

        // The abandoned consumer finally reaches the end of its helper's stdout.
        detector.resume(stale)
        assertEquals(started, opens, "no detect helper beside the recording that owns detection")
        detector.micInUse(stale, "Zoom.exe", inUse = true)
        detector.evaluate()
        assertTrue(notifier.posted.isEmpty(), "a finished session's microphone is not a meeting")

        // The session that does own detection still does, and still gives it back.
        detector.micInUse(current, "Zoom.exe", inUse = true)
        detector.evaluate()
        assertEquals(1, notifier.posted.size)
        detector.resume(current)
        assertEquals(started + 1, opens)
    }

    // --- an offer does not outlive its own signal by even one tick --------------------------------

    /**
     * The events arrive every two seconds and the balloon is clickable the whole time. An offer
     * whose microphone has just gone away has to stop working *now*, not at the next pass.
     */
    @Test
    fun `a start offer stops working the moment the microphone goes idle`() {
        val actions = FakeActions()
        val detector = detector(actions)
        detector.detectSaw("Zoom.exe", inUse = true)
        detector.evaluate()
        val token = notifier.posted.single().second

        // No `evaluate` in between: the click lands between two passes.
        detector.detectSaw("Zoom.exe", inUse = false)
        detector.act(token)

        assertEquals(listOf(token), notifier.invalidated)
        assertEquals(0, actions.starts)
    }

    /** And its mirror: the room was quiet, somebody spoke, and the offer to end is gone with it. */
    @Test
    fun `a stop offer stops working the moment the microphone comes back`() {
        val actions = FakeActions(recording = true)
        val detector = detector(actions)
        detector.recorderSees("Zoom.exe", inUse = true)
        detector.evaluate()
        detector.recorderSees("Zoom.exe", inUse = false)
        detector.evaluate()
        clock.instant += MeetingDetectionRule.MIC_IDLE + 1.seconds
        detector.evaluate()
        val token = notifier.posted.single().second

        detector.recorderSees("Zoom.exe", inUse = true)
        detector.act(token)

        assertEquals(listOf(token), notifier.invalidated)
        assertEquals(0, actions.stops)
    }

    /**
     * The handoff itself, against a real helper process: `yieldToRecorder` does not return until the
     * detect-only helper is closed **and** its reader has ended, so there is no moment at which the
     * recorder's helper and this one are both alive. `resume` is what brings a new one back.
     */
    @Test
    fun `the detect helper is gone before the recorder gets one, and comes back only after`() =
        runBlocking {
            var spawns = 0
            val actions = FakeActions()
            val detector = detector(actions, helper = {
                spawns++
                HelperClient(
                    FakeHelperCommand.command("micInUse=Zoom.exe", "parts=1", "sec=0.2"),
                    Dispatchers.IO,
                    SilentLogger,
                )
            })

            withTimeout(TIMEOUT_MS) { while (!detector.holdsHelper) delay(20) }
            // Its `detect on` reaches the rule, which is the proof it is the one reporting.
            withTimeout(TIMEOUT_MS) {
                while (notifier.posted.isEmpty()) {
                    detector.evaluate()
                    delay(50)
                }
            }
            assertEquals(1, spawns)

            val session = detector.yieldToRecorder()
            assertFalse(detector.holdsHelper, "the recorder's helper is opened after this returns")
            assertEquals(1, spawns, "and no new one was started behind it")

            detector.resume(session)
            withTimeout(TIMEOUT_MS) { while (!detector.holdsHelper) delay(20) }
            assertEquals(2, spawns)
            detector.stop()
        }

    private companion object {
        const val TIMEOUT_MS = 60_000L
    }
}
