package app.recly.windows.ui

import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import java.awt.SystemTray
import java.awt.TrayIcon
import recly.core.platform.Logger

/** What a job alert does to the tray. The app's is a balloon; a test's is a list. */
fun interface AlertBalloon {
    fun show(alert: JobAlert)
}

/**
 * docs/10 "사용자가 고칠 수 있는 실패와 그 알림" on Windows, and the one rule a tray balloon makes hard:
 * **it is posted on entry and on change, never on every runner pass.**
 *
 * A balloon cannot be replaced or withdrawn the way a phone's notification can — `displayMessage`
 * always raises a new toast — so a pass that re-posted everything it still saw would toast the user
 * every five minutes about a job that has been stuck since this morning. [standing] is what the last
 * publish actually put up, and only a reason that was not standing, or one whose count now says
 * something else, is worth interrupting anybody for.
 *
 * A reason the queue no longer has simply leaves [standing], which is what lets it toast again if it
 * comes back. The *visible* half of docs/10 rule 3 — a reason that goes away takes its line down —
 * is the popup banner and the tray icon's own state (`ShellModel.alerts`), because those are drawn
 * from the queue rather than from anything this object remembers.
 */
object JobAlertBalloons {

    private val lock = Any()

    /** What was last posted, per reason. Process state, like the tray itself. */
    private var standing: Map<AlertReason, JobAlert> = emptyMap()

    /** A new reading of the queue: whatever is new or has changed in it goes up. */
    fun publish(balloon: AlertBalloon, alerts: List<JobAlert>) = synchronized(lock) {
        val posted = mutableMapOf<AlertReason, JobAlert>()
        AlertReason.entries.forEach { reason ->
            val alert = alerts.firstOrNull { it.reason == reason } ?: return@forEach
            if (standing[reason] != alert) balloon.show(alert)
            posted[reason] = alert
        }
        standing = posted
    }

    /** Test seam: this object outlives a single test, and what is standing is per-process state. */
    internal fun forget() = synchronized(lock) { standing = emptyMap() }
}

/**
 * The real one: `java.awt.TrayIcon.displayMessage`, the same call the meeting detector's offers go
 * through ([app.recly.windows.detect.TrayNotifier]) and the same limitation — a balloon has no
 * buttons, so the *fix* is the popup's banner row and this only says that there is one.
 *
 * [strings] is read at the moment the balloon goes up rather than when this was built, so a language
 * change reaches it the same as it reaches a window (docs/07 rule 3).
 */
class TrayAlertBalloon(
    private val logger: Logger,
    private val strings: () -> Strings,
) : AlertBalloon {

    override fun show(alert: JobAlert) {
        val icon = trayIcon()
        if (icon == null) {
            logger.log(Logger.Level.WARN, "job.alert.noTray", mapOf("reason" to alert.reason.name))
            return
        }
        runCatching {
            val table = strings()
            icon.displayMessage(
                table[alert.reason.label],
                table[Str.ALERT_WAITING, alert.count],
                TrayIcon.MessageType.WARNING,
            )
        }.onFailure { logger.log(Logger.Level.WARN, "job.alert.failed", error = it) }
        logger.log(
            Logger.Level.INFO,
            "job.alert",
            mapOf("reason" to alert.reason.name, "count" to alert.count),
        )
    }

    /** Compose owns the tray icon and does not hand it out, so it is taken from AWT — there is one. */
    private fun trayIcon(): TrayIcon? = runCatching {
        if (!SystemTray.isSupported()) null else SystemTray.getSystemTray().trayIcons.firstOrNull()
    }.getOrNull()
}
