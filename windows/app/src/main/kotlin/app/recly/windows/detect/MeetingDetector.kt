@file:OptIn(ExperimentalTime::class)

package app.recly.windows.detect

import app.recly.windows.detect.MeetingDetectionRule.Prompt
import app.recly.windows.helper.HelperClient
import app.recly.windows.helper.HelperCommand
import app.recly.windows.helper.HelperEvent
import app.recly.windows.helper.HelperRestarts
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import recly.core.platform.Clock
import recly.core.platform.Logger

/**
 * docs/14 "감지" wired together: the helper's `mic_in_use` events, the running processes, the rule
 * and a clock. The shell gets a handful of calls out of it ([Actions]) and owns everything visible.
 *
 * **One helper at a time** ([Detection]): a detect-only helper while nothing is being recorded, and
 * the recorder's own for the length of a recording. The reason is in `Detection`'s header, and the
 * handoff is awaited in both directions so the two are never alive together.
 *
 * **One offer at a time, and it expires.** Every prompt is posted with a token; the token stops
 * matching as soon as the signals that raised it stop holding (the meeting ended, the microphone
 * came back, the recording state changed). [act] takes an offer only if its token is still the
 * current one *and* the shell is still in the state that offer was about — a balloon that has been
 * sitting in Notification Center for ten minutes cannot start a recording nobody is in.
 *
 * Everything that touches the rule, the offer or the helper does it under [lock], because the four
 * callers are four threads: the ticker, the helper's reader, the recorder, and the AWT/Compose
 * thread the user clicks on.
 */
class MeetingDetector(
    /** A fresh detect-only helper, or null when there is no helper binary at all. */
    private val helper: () -> HelperClient?,
    private val apps: RunningApps,
    private val notifier: MeetingNotifier,
    private val actions: Actions,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val logger: Logger,
    private val restarts: HelperRestarts = HelperRestarts(),
    private val tickInterval: Duration = TICK,
    private val restartDelay: Duration = RESTART_DELAY,
) : Detection {

    /** What the shell can do about a detected meeting. */
    interface Actions {
        /** Whether a recording could be started at all right now — core open, helper present. */
        fun canStart(): Boolean

        fun isRecording(): Boolean

        fun start()

        fun stop()

        /**
         * The offer that is outstanding and the token that identifies it, or `(null, NO_OFFER)` once
         * it has been taken or has gone stale. The tray shows a menu item for it, because an AWT
         * balloon has no buttons ([TrayNotifier]) — and that item must stop working when this does.
         */
        fun offered(prompt: Prompt?, token: Long)
    }

    private val lock = Any()
    private val rule = MeetingDetectionRule()

    private var micInUse = false
    private var holder: String? = null
    private var closing = false

    /**
     * The recording session that holds the helper, and with it the only microphone report that
     * counts — [NO_OWNER] while detection holds it itself. A *session*, not "the recorder": a
     * deferred stop leaves its consumer running, and that consumer must not be able to hand
     * detection back after a later recording has taken it (`Detection`).
     */
    private var owner: Long = NO_OWNER
    private var owners: Long = NO_OWNER

    private var own: HelperClient? = null
    private var ownJob: Job? = null

    private var offer: Prompt? = null
    private var offerToken: Long = NO_OFFER
    private var tokens: Long = NO_OFFER

    private var ticker: Job? = null

    /** For the tests: whether a detect-only helper is alive right now. */
    internal val holdsHelper: Boolean get() = synchronized(lock) { own != null }

    fun start() {
        notifier.onAction = ::act
        openHelper()
        ticker = scope.launch {
            while (isActive) {
                delay(tickInterval)
                evaluate()
            }
        }
    }

    fun stop() {
        synchronized(lock) { closing = true }
        ticker?.cancel()
        ticker = null
        scope.launch { closeOwn() }
    }

    // --- Detection: the handoff (see `Detection`) --------------------------------------------------

    override suspend fun yieldToRecorder(): Long {
        var client: HelperClient? = null
        var job: Job? = null
        val token = synchronized(lock) {
            owner = ++owners
            clearOffer()
            // The microphone this helper was reporting is not its to report any more; the recorder's
            // own `detect on` answers from here, and until it does nothing is known.
            micInUse = false
            client = own
            job = ownJob
            own = null
            ownJob = null
            owner
        }
        client?.close()
        // Not just closed — *finished*: the reader may still have a queued event, and an event from
        // a helper that no longer owns detection must not reach the rule.
        job?.join()
        logger.log(Logger.Level.INFO, "detect.helper.yielded", mapOf("owner" to token))
        return token
    }

    override fun resume(token: Long) {
        synchronized(lock) {
            if (token != owner || owner == NO_OWNER) {
                // A session that is already over reaching EOF. Handing detection back for it would
                // put a detect-only helper beside the recording that owns it now.
                logger.log(Logger.Level.WARN, "detect.resume.stale", mapOf("token" to token))
                return
            }
            owner = NO_OWNER
            clearOffer()
            micInUse = false
        }
        logger.log(Logger.Level.INFO, "detect.helper.resumed")
        openHelper()
    }

    /** A `mic_in_use` from a recording's helper — dropped unless that recording still owns it. */
    override fun micInUse(token: Long, app: String, inUse: Boolean) = synchronized(lock) {
        if (token != owner || owner == NO_OWNER) {
            logger.log(
                Logger.Level.WARN,
                "detect.mic.stale",
                mapOf("app" to app, "from" to "recorder", "token" to token),
            )
            return
        }
        observe(app, inUse)
    }

    /**
     * A `mic_in_use` from the detect-only helper — dropped while the recorder owns detection.
     * Internal rather than private so a test can stand in for that helper, which on this host does
     * not exist.
     */
    internal fun detectSaw(app: String, inUse: Boolean) = synchronized(lock) {
        if (owner != NO_OWNER) {
            logger.log(Logger.Level.WARN, "detect.mic.stale", mapOf("app" to app, "from" to "detect"))
            return
        }
        observe(app, inUse)
    }

    /** Caller holds [lock]. */
    private fun observe(app: String, inUse: Boolean) {
        micInUse = inUse
        if (inUse) holder = app
        logger.log(Logger.Level.INFO, "detect.mic", mapOf("app" to app, "inUse" to inUse))
        // Here and not at the next tick: the events arrive every two seconds, and a click landing in
        // between would otherwise take an offer whose own signal has just gone away. Only the
        // microphone is checked — it is the half of [stillHolds] this event actually knows about,
        // and the rest costs a scan of the process table on every event.
        offer?.let { if (!micHolds(it, inUse)) clearOffer() }
    }

    // --- the rule ---------------------------------------------------------------------------------

    /**
     * One pass of the rule. Internal rather than private so a test can drive it against a fixed
     * clock — the two-second timer is not the part that can be wrong in a way the user notices.
     */
    internal fun evaluate() = synchronized(lock) {
        val signals = signals()
        // Before anything new: an offer whose situation has passed is withdrawn, so neither the
        // balloon nor the tray item can still act on it.
        offer?.let { if (!stillHolds(it, signals)) clearOffer() }
        val prompt = rule.evaluate(signals, clock.now()) ?: return
        route(prompt, signals.meetingApp)
    }

    /** Caller holds [lock]. */
    private fun signals(): MeetingDetectionRule.Signals {
        val isRecording = actions.isRecording()
        // The process list and the window titles cost a round trip, and they only decide anything
        // while a microphone is open and nothing is being recorded yet (the Mac's `tick()`).
        val app = if (micInUse && !isRecording) {
            MeetingApps.attribute(apps.processes(), apps::windowTitles)
        } else {
            null
        }
        return MeetingDetectionRule.Signals(micInUse, app, isRecording)
    }

    /** What each offer is about, and therefore what has to still be true for it to mean anything. */
    private fun stillHolds(prompt: Prompt, signals: MeetingDetectionRule.Signals): Boolean =
        micHolds(prompt, signals.micInUse) && when (prompt) {
            Prompt.START -> signals.meetingApp != null && !signals.isRecording
            Prompt.STOP -> signals.isRecording
        }

    /** The microphone half of [stillHolds], which is the half a `mic_in_use` event settles alone. */
    private fun micHolds(prompt: Prompt, micInUse: Boolean): Boolean =
        if (prompt == Prompt.START) micInUse else !micInUse

    /**
     * ADR-011: 감지 → 확인 → 녹음, so both prompts are a notification and nothing else — a
     * recording never starts on its own.
     *
     * Caller holds [lock].
     */
    private fun route(prompt: Prompt, app: String?) {
        when (prompt) {
            Prompt.START -> {
                if (actions.isRecording() || !actions.canStart()) return
                logger.log(Logger.Level.INFO, "detect.meeting", mapOf("app" to (app ?: holder)))
                publish(Prompt.START)
            }

            Prompt.STOP -> {
                // Never a stop of its own (docs/14 "감지": never an automatic stop).
                if (!actions.isRecording()) return
                logger.log(Logger.Level.INFO, "detect.meeting.idle")
                publish(Prompt.STOP)
            }
        }
    }

    /** Caller holds [lock]. */
    private fun publish(prompt: Prompt) {
        clearOffer()
        val token = ++tokens
        offer = prompt
        offerToken = token
        actions.offered(prompt, token)
        notifier.post(prompt, token)
    }

    /** Caller holds [lock]. */
    private fun clearOffer() {
        val token = offerToken
        if (offer == null) return
        offer = null
        offerToken = NO_OFFER
        actions.offered(null, NO_OFFER)
        notifier.invalidate(token)
    }

    /**
     * The notification's click, or the tray item the shell shows for it. Does nothing unless [token]
     * is still the outstanding offer and the shell is still in the state that offer was about.
     */
    fun act(token: Long) = synchronized(lock) {
        val prompt = offer
        if (prompt == null || token != offerToken) {
            logger.log(Logger.Level.WARN, "detect.act.stale", mapOf("token" to token))
            return
        }
        // The full check, against the signals as they are *now* — not as they were when the balloon
        // went out. `canStart` is the shell's own half of it.
        val applies = stillHolds(prompt, signals()) &&
            (prompt != Prompt.START || actions.canStart())
        if (!applies) {
            logger.log(Logger.Level.WARN, "detect.act.moot", mapOf("prompt" to prompt.name))
            clearOffer()
            return
        }
        clearOffer()
        logger.log(Logger.Level.INFO, "detect.act", mapOf("prompt" to prompt.name))
        when (prompt) {
            Prompt.START -> actions.start()
            Prompt.STOP -> actions.stop()
        }
    }

    // --- the detect-only helper -------------------------------------------------------------------

    private fun openHelper() = synchronized(lock) {
        if (closing || owner != NO_OWNER || own != null) return
        val client = helper() ?: run {
            logger.log(Logger.Level.WARN, "detect.helper.missing")
            return
        }
        own = client
        ownJob = scope.launch { consume(client) }
    }

    private suspend fun consume(client: HelperClient) {
        runCatching {
            client.open(scope)
            client.send(HelperCommand.Detect(on = true))
            for (event in client.events) {
                if (event !is HelperEvent.MicInUse) continue
                // The generation check: this helper's events count only while it is still the one
                // holding detection — `detectSaw` refuses them once the recorder has taken over.
                synchronized(lock) { if (own === client) detectSaw(event.app, event.inUse) }
            }
        }.onFailure { logger.log(Logger.Level.WARN, "detect.helper.failed", error = it) }

        // The channel closing is the helper's death (`HelperClient`) — unless this is the client we
        // closed ourselves, which `own` no longer points at.
        val mine = synchronized(lock) {
            if (own !== client) return
            own = null
            ownJob = null
            !closing && owner == NO_OWNER
        }
        if (!mine) return
        if (!restarts.allow(clock.now())) {
            logger.log(Logger.Level.ERROR, "detect.helper.givingUp")
            return
        }
        logger.log(Logger.Level.WARN, "detect.helper.restarting")
        delay(restartDelay)
        openHelper()
    }

    private suspend fun closeOwn() {
        var client: HelperClient? = null
        var job: Job? = null
        synchronized(lock) {
            client = own
            job = ownJob
            own = null
            ownJob = null
        }
        client?.close()
        job?.join()
    }

    companion object {
        /** No offer outstanding. Tokens start at 1 so that zero is never a match. */
        const val NO_OFFER: Long = 0

        /** Detection holds the helper itself — no recording session owns it. */
        const val NO_OWNER: Long = 0

        /** docs/14: the helper polls every two seconds, and there is no point in ticking faster. */
        val TICK: Duration = 2.seconds

        val RESTART_DELAY: Duration = 5.seconds
    }
}
