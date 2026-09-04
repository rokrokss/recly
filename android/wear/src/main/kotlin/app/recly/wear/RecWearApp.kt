package app.recly.wear

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import app.recly.recording.RecorderHost
import app.recly.recording.RecorderService
import app.recly.recording.RecordingRecovery
import app.recly.wear.core.CoreModule
import app.recly.wear.core.WearLogger
import app.recly.wear.entry.RecComplicationService
import app.recly.wear.entry.RecTileService
import app.recly.wear.transfer.FileTransferQueue
import app.recly.wear.transfer.Recordings
import app.recly.wear.transfer.TransferScheduler
import app.recly.wear.transfer.TransferSender
import app.recly.wear.transfer.TransferStore
import app.recly.wear.transfer.WearableTransferLink
import app.recly.wear.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path.Companion.toPath
import recly.core.ReclyCore
import recly.core.model.RecordingStatus
import recly.core.platform.Logger
import recly.core.recording.RecordingRecord

/**
 * The watch's shell. `RecorderService` lives in `:android:recording` and cannot see [CoreModule],
 * so — exactly as on the phone — the application hands its core over through [RecorderHost].
 *
 * What it does *not* do is the phone's other half: no `WorkScheduler`, no sync, no widget, and
 * nothing that could mint a job. It does run the same startup recovery the phone does, because
 * what a recovered recording is worth here is the transfer queue, not a job — [onRecordingReady]
 * is where that difference lives, and it is the only place it lives.
 */
class RecWearApp : Application(), RecorderHost {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One logger for the whole process, as the core's — the worker logs through it too. */
    val logger: Logger = WearLogger

    /**
     * The transfer queue, held on the application because four things reach it and none of them
     * outlives the process: the recorder that finishes a recording, the screen that shows the
     * badge, the tile and complication that show the same count, and the worker that sends.
     *
     * Durable, so a phone that never acked cannot be forgotten by a restart — the file is read the
     * first time anything asks, and `RecWearApp.onCreate` asks.
     */
    val queue: TransferStore by lazy {
        FileTransferQueue(
            fs = FileSystem.SYSTEM,
            file = filesDir.absolutePath.toPath() / "rec" / FileTransferQueue.FILE_NAME,
            logger = logger,
            finalized = { finalizedRecordings() },
        )
    }

    override suspend fun core(): ReclyCore = CoreModule.get(this)

    /** What [TransferWorker] runs. Built per pass: it holds a Data Layer listener while it does. */
    suspend fun sender(): TransferSender = TransferSender(
        store = queue,
        recordings = CoreRecordings(core()),
        fs = FileSystem.SYSTEM,
        link = WearableTransferLink(this, logger),
        logger = logger,
    )

    /** The badge, for the surfaces that are asked for it before anything has loaded the file. */
    suspend fun pendingCount(): Int = queue.all().count { it.waiting }

    /**
     * A process that is starting may have been killed mid-recording (docs/03 "크래시 시 마지막
     * 경계까지는 복구 가능"). Recovery first, so anything it finalizes exists before the queue looks;
     * then the queue's own scan, which catches a recording that was finalized by a run that died
     * before it could hand it over.
     */
    override fun onCreate() {
        super.onCreate()
        scope.launch {
            val core = core()
            runCatching { RecordingRecovery(core, this@RecWearApp).reconcile() }
                .onFailure { core.deps.logger.log(Logger.Level.ERROR, "rec.recovery.failed", emptyMap(), it) }
            runCatching { queue.reconcile() }
                .onFailure { core.deps.logger.log(Logger.Level.ERROR, "transfer.reconcile.failed", emptyMap(), it) }
            // Whatever the scan turned up — and it is the only look a process that was killed
            // mid-transfer gets before the six-hour periodic.
            TransferScheduler.runNow(this@RecWearApp)
            TransferScheduler.armPeriodic(this@RecWearApp)
        }
        // The tile and the complication render outside this process and nothing else would tell
        // them a recording started or a transfer finished. `drop(1)` because the first emission is
        // the state they already have.
        scope.launch { RecorderService.state.drop(1).collect { refreshEntryPoints() } }
        scope.launch { queue.pending.drop(1).collect { refreshEntryPoints() } }
        // docs/11 W2: the same count reads differently once a pass has a phone, so the two surfaces
        // that cannot notice anything for themselves are told when that changes too.
        scope.launch { queue.sending.drop(1).collect { refreshEntryPoints() } }
    }

    /**
     * docs/11 "주의": nothing is ever enqueued on this device, so [enqueue] — the phone's question
     * about whether a title is still coming — is not one the watch has to answer. A finalized
     * recording here has exactly one thing left to happen to it, and that is reaching the phone.
     */
    override suspend fun onRecordingReady(recordingId: String, enqueue: Boolean) {
        queue.add(recordingId)
        TransferScheduler.runNow(this)
    }

    /** docs/11 W5: the two surfaces that cannot notice anything for themselves. */
    fun refreshEntryPoints() {
        runCatching { TileService.getUpdater(this).requestUpdate(RecTileService::class.java) }
        runCatching {
            ComplicationDataSourceUpdateRequester
                .create(this, ComponentName(this, RecComplicationService::class.java))
                .requestUpdateAll()
        }
    }

    /** docs/11 W4: "the phone does not have it yet" is the row still saying `finalized`. */
    private suspend fun finalizedRecordings(): List<String> = core().recordings.list(SCAN_LIMIT)
        .filter { it.meta.status == RecordingStatus.FINALIZED }
        .map { it.id }

    /**
     * docs/11 W3. The same foreground-service notification the phone posts, plus the thing that
     * makes it a watch notification: an [OngoingActivity] puts a chip on the watch face and keeps
     * the recording one tap away for as long as it runs — which is the point, since the user's next
     * move after starting is to drop their wrist and let the screen go dark.
     *
     * A static icon, not an animated one: three hours of animation on an OLED watch face is battery
     * the recording needs (docs/11 W7). The elapsed time is a [Status.StopwatchPart], so the system
     * ticks it and this process never wakes to redraw.
     */
    override fun recordingNotification(
        notificationId: Int,
        channelId: String,
        stop: PendingIntent,
    ): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(app.recly.recording.R.drawable.ic_rec_notification)
            .setContentTitle(getString(app.recly.recording.R.string.rec_notification_title))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis())
            .setContentIntent(open)
            .addAction(0, getString(app.recly.recording.R.string.rec_notification_stop), stop)

        OngoingActivity.Builder(this, notificationId, builder)
            .setStaticIcon(app.recly.recording.R.drawable.ic_rec_notification)
            .setTouchIntent(open)
            // `StopwatchPart` counts from a point on the elapsed-realtime clock, not the wall one.
            .setStatus(Status.forPart(Status.StopwatchPart(SystemClock.elapsedRealtime())))
            .build()
            .apply(this)

        return builder.build()
    }

    private companion object {
        /** Deep enough that a watch left away from its phone for weeks still hands everything over. */
        const val SCAN_LIMIT = 200
    }
}

/**
 * [TransferSender]'s two calls into the core. `delete` takes the row, the parts and the directory:
 * a recording the phone has acked end to end is done with this watch, and ADR-002 leaves it nothing
 * to show a history for. Keeping the row would also mean `reconcile` offering it again forever.
 */
private class CoreRecordings(private val core: ReclyCore) : Recordings {

    override suspend fun get(recordingId: String): RecordingRecord? = core.recordings.get(recordingId)

    override suspend fun delete(recordingId: String) = core.recordings.delete(recordingId)
}
