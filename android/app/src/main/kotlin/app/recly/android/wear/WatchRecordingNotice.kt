@file:OptIn(ExperimentalTime::class)

package app.recly.android.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationCompat
import app.recly.android.R
import app.recly.android.core.AndroidLogger
import app.recly.datalayer.WearRecording
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.Wearable
import kotlin.time.ExperimentalTime
import recly.core.platform.Logger
import app.recly.recording.R as RecordingR

/**
 * docs/11 "While the paired Galaxy Watch records": an ongoing notification for as long as the watch's
 * [WearRecording] item stands, with the platform's chronometer from the watch's own start.
 *
 * Two things paint it, as two paint the job notices ([app.recly.android.work.JobAlertNotifier]): the item's events,
 * through [RecListenerService], and a read of the item that is standing, at app start and on a locale change. The
 * read is a task that lands later, so an event that came in while it was out wins — a read that began before the
 * watch stopped would otherwise put the notice back up, and no event would ever take it down again.
 */
object WatchRecordingNotice {

    private val lock = Any()

    /** Bumped by every event; a read that started under an older one paints nothing. */
    private var generation = 0L

    /** The watch's newest word: its state while it records, null once it has stopped. */
    fun onEvent(context: Context, state: WearRecording.State?) = synchronized(lock) {
        generation++
        AndroidLogger().log(
            Logger.Level.INFO,
            "transfer.recording.state",
            mapOf("recording" to (state != null), "recordingId" to state?.recordingId),
        )
        paint(context, state)
    }

    /**
     * The item that is standing now, drawn or taken down. At app start because a reboot clears the shade and not the
     * item, and on a locale change because the notice was painted once in the old language (docs/07 rule 3). A phone
     * without the Wear OS app has no Data Layer to read, and that is not worth more than a line.
     */
    fun repaint(context: Context) {
        val seen = synchronized(lock) { generation }
        Wearable.getDataClient(context)
            .getDataItems(Uri.parse("wear://*${WearRecording.PATH}"), DataClient.FILTER_LITERAL)
            .addOnSuccessListener { items ->
                // The newest when two watches each have one standing — the phone keeps only the newest state (docs/03).
                val state = try {
                    items.mapNotNull { item -> item.data?.let(WearRecording::decode) }.maxByOrNull { it.startedAt }
                } finally {
                    items.release()
                }
                synchronized(lock) { if (generation == seen) paint(context, state) }
            }
            .addOnFailureListener { AndroidLogger().log(Logger.Level.DEBUG, "transfer.recording.failed", error = it) }
    }

    /**
     * In a group of its own, under a summary, as the job notices are: Android 16 bundles an app's ungrouped notifications
     * of one section once two are up, and on a phone recording at the same time this one took in the recorder's own
     * notification — its Stop behind two expands (API 36 emulator, 2026-10-28). A group without a summary is bundled the
     * same way, which is what the summary is for; with one child the shade shows the child alone.
     */
    private fun paint(context: Context, state: WearRecording.State?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (state == null) {
            manager.cancel(NOTIFICATION_ID)
            manager.cancel(SUMMARY_ID)
            return
        }
        manager.createNotificationChannel(channel(context))
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, NOTIFICATION_ID, it, PendingIntent.FLAG_IMMUTABLE)
        }
        manager.notify(NOTIFICATION_ID, notification(context, state, open))
        manager.notify(SUMMARY_ID, summary(context, open))
    }

    /** Nothing to act on and nothing to wake anybody for: it is the watch's recording, and the watch has the Stop. */
    private fun channel(context: Context): NotificationChannel = NotificationChannel(
        CHANNEL_ID,
        context.getString(R.string.watch_recording_channel),
        NotificationManager.IMPORTANCE_LOW,
    ).apply {
        setShowBadge(false)
        setSound(null, null)
        enableVibration(false)
    }

    private fun notification(context: Context, state: WearRecording.State, open: PendingIntent?): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(RecordingR.drawable.ic_rec_notification)
            .setContentTitle(context.getString(R.string.watch_recording_title))
            .setGroup(GROUP)
            // The platform ticks it, from the watch's start. Never from later than now: a watch clock a second ahead
            // of the phone's would otherwise open on a negative time.
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setWhen(minOf(state.startedAt.toEpochMilliseconds(), System.currentTimeMillis()))
            .setOngoing(true)
            // A phone notification is bridged to the watch by default, and this one would buzz the watch that is recording.
            .setLocalOnly(true)
            .setContentIntent(open)
            // A Live Update on Android 16, as the recorder's own notification is (`RecorderService`): the elapsed time in
            // a status bar chip. Older versions ignore it.
            .setRequestPromotedOngoing(true)
            .build()

    /**
     * Ongoing and local like its child: a summary the user could clear would leave the child without one, and one
     * bridged to the watch would buzz it all the same.
     */
    private fun summary(context: Context, open: PendingIntent?): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(RecordingR.drawable.ic_rec_notification)
            .setContentTitle(context.getString(R.string.watch_recording_title))
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setOngoing(true)
            .setLocalOnly(true)
            .setContentIntent(open)
            .build()

    private const val CHANNEL_ID = "watch-recording"
    private const val GROUP = "watch-recording"

    /** Clear of the recorder's 1–4, the job notices' 99 and up, and the model download's 200. */
    private const val NOTIFICATION_ID = 300

    /** Just above the notice's own id. */
    private const val SUMMARY_ID = 301
}
