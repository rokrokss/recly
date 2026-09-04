package app.recly.recording

import android.app.Notification
import android.app.PendingIntent
import recly.core.ReclyCore

/**
 * How [RecorderService] reaches the core without knowing which shell it is running in: the phone
 * app and the watch app each build their own [ReclyCore] and let their `Application` hand it over.
 * A library module cannot depend on either, and a service has no constructor to inject through.
 */
interface RecorderHost {
    suspend fun core(): ReclyCore

    /**
     * Whether the shell is in the middle of something a new recording must not race. On the phone
     * that is docs/03's disconnect: its "also delete the recordings" walks the very directory a
     * capture would be writing into, and a capture has no job yet, so the core's own `Busy` guard
     * does not see it. The watch has no such state and takes the default.
     *
     * Asked here rather than left to whoever starts the recording, because by then they have gone:
     * a tile tap fires the start intent from an activity that is already finishing, and the shell
     * can shut its gate while that intent is still in flight.
     */
    fun startsRefused(): Boolean = false

    /**
     * A recording is finalized on disk and nothing else in this module will touch it. What that is
     * worth is entirely the shell's business: the phone queues a job and wakes WorkManager, the
     * watch hands it to its transfer queue and never enqueues anything (docs/11 "주의" — it runs no
     * workflow and never touches Drive). This module therefore does not call `ReclyCore.enqueue` at
     * all; it says the recording is ready and lets the device decide what ready means.
     *
     * [enqueue] false is the one thing the caller of the stop gets to say: "hold it, I am going to
     * name it first". The phone's own Stop button does that — docs/03 asks for a title after the
     * recording has stopped, and `RecordingViewModel` enqueues once the dialog is answered. Every
     * other finalize (the notification's stop action, a fatal error, a recovery pass) has nobody to
     * ask and passes true.
     */
    suspend fun onRecordingReady(recordingId: String, enqueue: Boolean)

    /**
     * The foreground-service notification, when the shell wants a different one. Null — the phone's
     * answer — keeps [RecorderService]'s own.
     *
     * The watch overrides it to wrap the notification in an `OngoingActivity` (docs/11 W3), which
     * only accepts a `NotificationCompat.Builder` and so cannot be applied to a notification that
     * is already built. This module stays free of `androidx.wear`: it hands over the pieces the
     * service owns — the [channelId] it just created, the [notificationId] the ongoing activity has
     * to match, and the same [stop] action the default notification carries — and takes back a
     * finished [Notification].
     */
    fun recordingNotification(
        notificationId: Int,
        channelId: String,
        stop: PendingIntent,
    ): Notification? = null
}
