@file:OptIn(ExperimentalTime::class)

package app.recly.android.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import app.recly.android.R
import app.recly.android.ui.AlertReason
import app.recly.android.ui.AlertSource
import app.recly.android.ui.JobAlert
import app.recly.android.ui.MainActivity
import app.recly.android.ui.alertReasonOf
import app.recly.android.ui.blockingError
import app.recly.android.ui.foldAlerts
import kotlin.time.ExperimentalTime
import recly.core.ReclyCore
import recly.core.job.Job
import recly.core.job.JobStatus
import app.recly.recording.R as RecordingR

/**
 * docs/10 "사용자가 고칠 수 있는 실패와 그 알림" on the phone. Three rules, and they are the whole of
 * it:
 *
 * 1. **One notification per reason.** Five jobs blocked on the same thing are one notification
 *    whose body counts them, not five notifications.
 * 2. **Only what a person can fix.** A step inside its retry budget is `WAITING` and never reaches
 *    [alertReasonOf], so a webhook 500 on its way round the backoff calls nobody.
 * 3. **It comes down by itself.** The queue is the source of truth, so a reason that is no longer
 *    in it is cancelled on the next emission — a sign-in, a `retry()`, a deletion.
 *
 * The channel is `jobs`, separate from the recorder's foreground-service channel: one is a thing
 * that is happening and the other is a thing that has stopped happening, and a user who silences
 * either should not lose the other.
 */
class JobAlertNotifier(private val context: Context) {

    /** Runs for the life of the process. `jobs.observe()` re-emits on every queue change. */
    suspend fun run(core: ReclyCore) {
        core.jobs.observe().collect { jobs -> JobAlertShade.publish(SystemShade(context), alerts(core, jobs)) }
    }

    private suspend fun alerts(core: ReclyCore, jobs: List<Job>): List<JobAlert> = foldAlerts(
        jobs.map { job ->
            // A parked job says why in its own status; only a FAILED one has to be asked, and
            // asking for every job on every emission would be a query per row per change.
            val error = if (job.status == JobStatus.FAILED) blockingError(core.jobs.steps(job.id)) else null
            AlertSource(alertReasonOf(job.status, error), job.workflowId)
        },
    )

    companion object {
        /**
         * A locale change: the notifications were painted once and are still in the old language
         * (docs/07 rule 3), the same reason the recorder's ongoing notification and the widget are
         * on [app.recly.android.settings.LocalizedSurfaces].
         */
        fun refresh(context: Context) = JobAlertShade.repaint(SystemShade(context))
    }
}

/** What a job alert does to the shade. The app's is the platform's; a test's is a list. */
interface AlertShade {
    fun notify(alert: JobAlert)

    fun cancel(reason: AlertReason)
}

/**
 * What the shade is showing, and the one lock everything that paints it goes through.
 *
 * Two things paint: the queue collector, and a locale change from whatever thread the settings
 * screen is on. Unserialized they interleave — a refresh that had read the old contents could
 * re-post a reason the collector had just cancelled, and nothing would ever take it down again,
 * because the queue it is no longer in will not emit about it a second time.
 *
 * So the refresh repaints [latest] — the last thing the *queue* said, empty included — rather than
 * a snapshot of what was posted, and both halves hold the lock while they do it. The work under it
 * is a handful of `NotificationManager` calls and never suspends.
 */
internal object JobAlertShade {

    private val lock = Any()

    /** The last reading of the queue. Survives the collector, which is what a refresh needs. */
    private var latest: List<JobAlert> = emptyList()

    /**
     * What is actually standing in the shade, so a reading that changed nothing posts nothing.
     *
     * `NotificationManager.notify` under an id that is already showing *replaces* the notification
     * and alerts again while it does, so posting every reason on every runner pass would buzz the
     * user every five minutes for one job that has been stuck since this morning. Only a reason
     * that was not standing, or one whose count now says something else, is worth their attention.
     */
    private var standing: Map<AlertReason, JobAlert> = emptyMap()

    /** A new reading of the queue: it becomes what the shade shows, and what a refresh repaints. */
    fun publish(shade: AlertShade, alerts: List<JobAlert>) = synchronized(lock) {
        latest = alerts
        paint(shade, alerts, force = false)
    }

    /**
     * The same contents in the app's new language — never more than the queue last asked for, and
     * unconditionally: what changed is the sentence rather than the queue, so a reason whose count
     * is exactly what is already up is precisely the one that needs reposting (docs/07 rule 3).
     */
    fun repaint(shade: AlertShade) = synchronized(lock) { paint(shade, latest, force = true) }

    private fun paint(shade: AlertShade, alerts: List<JobAlert>, force: Boolean) {
        val posted = mutableMapOf<AlertReason, JobAlert>()
        AlertReason.entries.forEach { reason ->
            val alert = alerts.firstOrNull { it.reason == reason }
            when {
                // Cancelled whether or not this process posted it: a notification left standing by
                // the last launch is still in the shade, and this reading is what knows it is stale.
                alert == null -> shade.cancel(reason)
                force || standing[reason] != alert -> {
                    shade.notify(alert)
                    posted[reason] = alert
                }
                // Unchanged: it is already on screen, and re-posting it would alert again.
                else -> posted[reason] = alert
            }
        }
        standing = posted
    }

    /** Test seam: this object outlives a single test, and what is standing is per-process state. */
    internal fun forget() = synchronized(lock) {
        latest = emptyList()
        standing = emptyMap()
    }
}

/** The real shade: one notification id per [AlertReason], in the app's own `jobs` channel. */
private class SystemShade(private val context: Context) : AlertShade {

    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

    override fun notify(alert: JobAlert) {
        manager.createNotificationChannel(channel())
        manager.notify(notificationId(alert.reason), notification(alert))
    }

    override fun cancel(reason: AlertReason) = manager.cancel(notificationId(reason))

    /** docs/10: "무음 · 우선순위 기본" — it is worth seeing, and never worth waking anybody up. */
    private fun channel(): NotificationChannel = NotificationChannel(
        CHANNEL_ID,
        context.getString(R.string.alert_channel),
        NotificationManager.IMPORTANCE_DEFAULT,
    ).apply {
        setShowBadge(false)
        setSound(null, null)
        enableVibration(false)
    }

    private fun notification(alert: JobAlert): Notification =
        Notification.Builder(context, CHANNEL_ID)
            // The recorder's own icon, from the module that owns it: `nonTransitiveRClass` keeps a
            // library's resources in the library's own R.
            .setSmallIcon(RecordingR.drawable.ic_rec_notification)
            .setContentTitle(context.getString(alert.reason.label))
            .setContentText(context.resources.getQuantityString(R.plurals.alert_waiting, alert.count, alert.count))
            // docs/10: "탭하면 고칠 수 있는 화면으로 간다 — '앱 열기'로 끝내지 않는다."
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    notificationId(alert.reason),
                    MainActivity.fix(context, alert),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            // No `setAutoCancel`: the tap opens the fix screen, it does not unblock the job. An
            // alert taken down on the tap would never come back — opening an activity emits no
            // queue change, so nothing would repost it while the reason is still there. docs/10
            // rule 3 is the only thing that cancels: a reading of the queue without the reason.
            .build()

    private fun notificationId(reason: AlertReason): Int = NOTIFICATION_BASE + reason.ordinal

    private companion object {
        const val CHANNEL_ID = "jobs"

        /** Well clear of the recorder's own id, which is 1 in the `recording` channel. */
        const val NOTIFICATION_BASE = 100
    }
}
