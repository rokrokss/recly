package app.recly.windows.detect

import app.recly.windows.detect.MeetingDetectionRule.Prompt
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import app.recly.windows.i18n.StringTable
import java.awt.SystemTray
import java.awt.TrayIcon
import recly.core.platform.Logger

/**
 * The two notifications the meeting detector is allowed to raise (docs/14 "감지", ADR-011: detect,
 * then ask, then record). Both are offers — the app never starts or stops a recording because it
 * thinks it should.
 *
 * A tray app has nowhere else to put this: the menu is only visible while the user is already
 * looking at it, and the whole point of the detection is to reach someone who is looking at Teams.
 *
 * **Every offer carries a token.** A notification outlives the situation that raised it — the
 * meeting ends, the microphone comes back, the user stops the recording by hand — and an offer taken
 * after that would start a recording nobody is in, or stop one that is still going. The token is how
 * a click is matched back to the offer, and [invalidate] is how an offer that has gone stale stops
 * being clickable at all ([MeetingDetector.act] re-checks the state as well).
 */
interface MeetingNotifier {
    /** Called with the token of the offer the user took. [MeetingDetector] wires it to the shell. */
    var onAction: ((Long) -> Unit)?

    fun post(prompt: Prompt, token: Long)

    /** Withdraws [token]'s offer if it is still the outstanding one. Later offers are untouched. */
    fun invalidate(token: Long)

    companion object {
        fun title(prompt: Prompt): Str = when (prompt) {
            Prompt.START -> Str.NOTIFY_MEETING_TITLE
            Prompt.STOP -> Str.NOTIFY_IDLE_TITLE
        }

        fun body(prompt: Prompt): Str = when (prompt) {
            // The Mac puts "Start recording" on a button; a tray balloon has no buttons, so the
            // sentence has to carry the instruction (see [TrayNotifier]).
            Prompt.START -> Str.NOTIFY_MEETING_BODY
            Prompt.STOP -> Str.NOTIFY_IDLE_BODY
        }

        fun create(
            logger: Logger,
            strings: () -> Strings = { StringTable.of(StringTable.BASE) },
        ): MeetingNotifier = TrayNotifier(logger, strings)
    }
}

/**
 * `java.awt.TrayIcon.displayMessage` — a Windows toast raised through the tray icon Compose already
 * put there, and the same call works on the macOS development host.
 *
 * **The limitation, deliberately accepted:** an AWT balloon has no action buttons. Clicking the
 * balloon itself is the whole of its interaction, and Windows reports that as the tray icon's action
 * event — which a double-click on the icon also raises. So a click only counts while an offer is
 * outstanding *and* fresh, and the reliable way to take one is the tray menu item the shell adds
 * ([Str.TRAY_START_DETECTED]). A Maven toast library with real buttons would need a dependency that
 * is Windows-only at runtime and unbuildable here; the menu item costs nothing and works on both.
 *
 * [strings] is read at the moment the balloon goes up, not when this was built: a language change
 * has to reach a notification the same as it reaches a window (docs/07 rule 3).
 */
class TrayNotifier(
    private val logger: Logger,
    private val strings: () -> Strings,
) : MeetingNotifier {

    override var onAction: ((Long) -> Unit)? = null

    private val lock = Any()
    private var pending: Long = NONE
    private var pendingAt: Long = 0
    private var listening: TrayIcon? = null

    override fun post(prompt: Prompt, token: Long) {
        val icon = trayIcon()
        if (icon == null) {
            logger.log(Logger.Level.WARN, "detect.notify.noTray", mapOf("prompt" to prompt.name))
            return
        }
        synchronized(lock) {
            pending = token
            pendingAt = System.currentTimeMillis()
        }
        listen(icon)
        runCatching {
            val table = strings()
            icon.displayMessage(
                table[MeetingNotifier.title(prompt)],
                table[MeetingNotifier.body(prompt)],
                TrayIcon.MessageType.INFO,
            )
        }.onFailure { logger.log(Logger.Level.WARN, "detect.notify.failed", error = it) }
        logger.log(Logger.Level.INFO, "detect.notify", mapOf("prompt" to prompt.name, "token" to token))
    }

    override fun invalidate(token: Long) {
        val withdrawn = synchronized(lock) {
            if (pending != token) return
            pending = NONE
            true
        }
        if (withdrawn) logger.log(Logger.Level.INFO, "detect.notify.stale", mapOf("token" to token))
    }

    /**
     * Compose owns the tray icon and does not hand it out, so it is taken from AWT — where there is
     * exactly one, this app's. The listener is attached once per icon; Compose replaces the icon
     * when the status icon changes (recording ↔ idle), which is why this is not done at startup.
     */
    private fun listen(icon: TrayIcon) {
        if (listening === icon) return
        icon.addActionListener {
            val token = synchronized(lock) {
                if (pending == NONE || System.currentTimeMillis() - pendingAt > FRESH_MS) return@addActionListener
                pending.also { pending = NONE }
            }
            logger.log(Logger.Level.INFO, "detect.notify.action", mapOf("token" to token))
            onAction?.invoke(token)
        }
        listening = icon
    }

    private fun trayIcon(): TrayIcon? = runCatching {
        if (!SystemTray.isSupported()) null else SystemTray.getSystemTray().trayIcons.firstOrNull()
    }.getOrNull()

    private companion object {
        /** No offer outstanding — [MeetingDetector]'s tokens start at 1. */
        const val NONE = 0L

        /**
         * How long a click still means "yes" — about as long as the balloon is on screen. Past it a
         * click on the tray icon is the user opening their tray, not answering a question.
         */
        const val FRESH_MS = 60_000L
    }
}
