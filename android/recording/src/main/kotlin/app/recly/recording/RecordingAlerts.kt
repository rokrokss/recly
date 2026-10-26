package app.recly.recording

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import recly.core.platform.Logger

/**
 * What a long recording tells the user while nobody is looking at it (docs/11 ":android:recording"):
 * that it may have died, that it is still going, and that the battery ended it. Each is its own
 * notification in a channel of its own, apart from the ongoing one.
 *
 * The rules are the pure functions at the bottom, so `RecordingAlertsTest` can hold them on the JVM.
 */
internal object RecordingAlerts {
    const val CHANNEL_ID: String = "recording-alerts"

    /** Clear of the ongoing notification (1, `recording`) and of the phone's jobs (100+). */
    const val WATCHDOG_ID: Int = 2
    const val REMINDER_ID: Int = 3
    const val BATTERY_ID: Int = 4

    /** A process that is gone cannot cancel this; only a recording that is still alive pushes it back. */
    fun armWatchdog(context: Context, segmentSec: Int) {
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + watchdogDelayMs(segmentSec),
            watchdog(context),
        )
    }

    fun cancelWatchdog(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(watchdog(context))
    }

    /** The same request code and intent every time, so arming again replaces the alarm. */
    private fun watchdog(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, RecordingWatchdog::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )

    fun post(context: Context, id: Int, title: String, text: String? = null, stop: PendingIntent? = null) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.rec_alerts_channel), NotificationManager.IMPORTANCE_DEFAULT)
                .apply {
                    // A sound would be recorded into the very recording it is about; a vibration is felt.
                    setSound(null, null)
                    enableVibration(true)
                },
        )
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_rec_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
        stop?.let {
            builder.addAction(NotificationCompat.Action.Builder(null as IconCompat?, context.getString(R.string.rec_notification_stop), it).build())
        }
        manager.notify(id, builder.build())
    }

    /** Two segments: one late boundary is not a dead recording, two in a row are. */
    fun watchdogDelayMs(segmentSec: Int): Long = WATCHDOG_SEGMENTS * segmentSec * 1000L

    /**
     * How long from [elapsedMs] into a recording until its next reminder: at [FIRST_REMINDER_HOURS],
     * then on every whole hour after. From the elapsed time each time rather than counted up, so a
     * reminder that came late does not make the next ones come in a burst.
     */
    fun reminderDelayMs(elapsedMs: Long): Long {
        val hours = maxOf(FIRST_REMINDER_HOURS, elapsedMs / HOUR_MS + 1)
        return hours * HOUR_MS - elapsedMs
    }

    /**
     * Stop before the phone or watch shuts itself down mid-segment, which would cost everything since
     * the last boundary. Not while it charges: then the level only goes up. A level the platform
     * cannot read (-1) stops nothing.
     */
    fun batteryTooLow(level: Int, scale: Int, plugged: Int): Boolean =
        level >= 0 && scale > 0 && plugged == 0 && level * 100 / scale <= BATTERY_STOP_PERCENT

    /**
     * [batteryTooLow], except for a recording that [startedLow] — [batteryTooLow] of the reading at its
     * start. That one the user chose to make on a low battery, and stopping it at once would leave a
     * recording with next to nothing in it, so it is never stopped for the battery.
     */
    fun batteryStops(startedLow: Boolean, level: Int, scale: Int, plugged: Int): Boolean =
        !startedLow && batteryTooLow(level, scale, plugged)

    const val HOUR_MS: Long = 3_600_000L
    private const val WATCHDOG_SEGMENTS = 2
    private const val FIRST_REMINDER_HOURS = 2L
    private const val BATTERY_STOP_PERCENT = 5
}

/**
 * The watchdog's alarm went off: nothing pushed it back for two segments, which a recording that is
 * still running always does at its boundary. Usually that means the process died with the recording
 * in it — the alarm starts a new one, whose `Application` files what was left on disk (docs/03 "after
 * a crash, everything up to the last boundary is recoverable") — so the user is told to look.
 */
class RecordingWatchdog : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RecordingAlerts.post(
            context,
            RecordingAlerts.WATCHDOG_ID,
            context.getString(R.string.rec_watchdog_title),
            context.getString(R.string.rec_watchdog_body),
        )
        val host = context.applicationContext as? RecorderHost ?: return
        val finish = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { host.core().deps.logger.log(Logger.Level.WARN, "rec.watchdog.fired", emptyMap()) }
            finish.finish()
        }
    }
}
