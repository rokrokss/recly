@file:OptIn(ExperimentalTime::class)

package app.recly.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import recly.core.model.Platform
import recly.core.model.Source
import recly.core.platform.Logger

/**
 * The microphone foreground service (docs/11): recording outlives the screen, the app being
 * swiped away and — with the `microphone` type — Doze, which a plain background service does not.
 *
 * It must be started from something the user can see (an activity, a tile, a notification action):
 * a while-in-use type started from the background throws, by design. Stopping is the other half of
 * the contract, and the delicate half — the last segments are drained, the recording finalized and
 * handed to the shell through [RecorderHost.onRecordingReady]. None of that may be cancelled or
 * repeated, so it runs once, on a scope that outlives the service, and `stopSelf` waits for it.
 *
 * This class is the Android half only: the intents, the foreground notification and the recorder
 * itself. Every rule about which tap counts lives in [RecorderSession], where it can be tested.
 *
 * State and events are `companion` members because the observer is a screen that comes and goes
 * while the service stays; binding would tie the recording's lifetime to the UI's.
 */
class RecorderService : Service() {

    /** Start and segment boundaries: work that dies with the service, and should. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Built lazily: [host] needs the application, which does not exist at construction time. */
    private val session by lazy { RecorderSession(host(), _state, _events, completionScope, ::stopSelf) }

    /** The level poller, for as long as the microphone is open — see [livePeaks]. */
    private var levels: Job? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> onStart(intent)
            ACTION_STOP -> {
                // Before the finalize, not after it: what the strip would draw while the last
                // segments are being drained is the level of a microphone that is already closed.
                stopLevels()
                session.stop(
                    title = intent.getStringExtra(EXTRA_TITLE),
                    enqueue = intent.getBooleanExtra(EXTRA_ENQUEUE, true),
                )
            }

            // docs/07 rule 3: the notification is drawn once and then sits there for hours, so a
            // language change has to ask for it again. Only ever sent to a service that is already
            // recording — `refreshNotification` checks — so this is an update, not a start.
            ACTION_REFRESH -> notificationManager().notify(NOTIFICATION_ID, notification())

            else -> stopSelf()
        }
        // A restart would be a microphone FGS started from the background: it would throw, and the
        // recording is gone either way.
        return START_NOT_STICKY
    }

    private fun onStart(intent: Intent) {
        if (!session.begin()) return
        // Asked after `begin` so the state is this start's to put back: `begin` only says yes when
        // nothing else is running, so leaving here strands nothing. A start already in flight when
        // the shell shut its gate is the one it cannot refuse at the caller, and it must not become
        // a recording that a disconnect's clean-up is about to delete (see [RecorderHost]).
        if (host().startsRefused()) {
            session.startFailed()
            stopSelf()
            return
        }
        // Before any suspending work: the window to post the notification is short and missing it
        // kills the process.
        startForeground(
            NOTIFICATION_ID,
            notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )

        val chosen = intent.getStringExtra(EXTRA_WORKFLOW_ID)
        val title = intent.getStringExtra(EXTRA_TITLE)
        val segmentSec = intent.getIntExtra(EXTRA_SEGMENT_SEC, SegmentedRecorder.DEFAULT_SEGMENT_SEC)

        scope.launch {
            val core = host().core()
            // Before a new recording opens, not after: the scan finalizes anything the last one
            // left half-written, and it must not be looking at rows this recording is creating.
            runCatching { RecordingRecovery(core, host()).reconcile() }
                .onFailure { core.deps.logger.log(Logger.Level.ERROR, "rec.recovery.failed", emptyMap(), it) }

            val recorder = SegmentedRecorder(
                context = applicationContext,
                core = core,
                scope = scope,
                segmentSec = segmentSec,
                // docs/03 이름 규칙: a watch recording is `_watch_` and says `"source": "watch"`.
                // The shell already declares what it is running on, so nothing has to be passed in.
                source = if (core.deps.device.platform == Platform.WEAROS) Source.WATCH else Source.PHONE,
                onError = { fail(it) },
            )
            try {
                val recordingId = recorder.start(chosen, title)
                session.started(recorder, recordingId, core.deps.clock.now(), chosen)
                startLevels(recorder)
            } catch (e: Exception) {
                session.startFailed()
                core.deps.logger.log(Logger.Level.ERROR, "rec.recorder.startFailed", emptyMap(), e)
                fail(RecorderError("could not start recording: ${e.describe()}", e))
            }
        }
    }

    /**
     * docs/09 화면 원칙 6: the levels the live strip draws, one window per [LEVEL_MS]. The recorder
     * is the only place the audio exists, so this is a reading of the track being written and not a
     * second tap on the microphone — which is the whole reason the strip answers "yes, it is
     * recording" at all.
     *
     * It runs on the watch too, whose screen never draws them; polling an integer ten times a
     * second is cheaper than a shell flag to turn it off with.
     */
    private fun startLevels(recorder: SegmentedRecorder) {
        waveform.reset()
        levels = scope.launch {
            try {
                while (true) {
                    delay(LEVEL_MS)
                    recorder.peakSinceLastAsk()?.let { waveform.add(it) }
                }
            } finally {
                // Also the way a service that is being destroyed leaves it: nothing is recording,
                // so there is nothing for the next screen to draw.
                waveform.reset()
            }
        }
    }

    private fun stopLevels() {
        levels?.cancel()
        levels = null
    }

    /**
     * A segment that could not be filed is not the end of the recording — it is marked on disk and
     * [RecordingRecovery] files it later, so the encoder keeps running and the user is not told
     * their three hours failed. Only a fatal error ends the capture.
     */
    private fun fail(error: RecorderError) {
        val recordingId = (_state.value as? RecorderState.Recording)?.recordingId
        val host = host()
        scope.launch {
            val level = if (error.fatal) Logger.Level.ERROR else Logger.Level.WARN
            runCatching { host.core().deps.logger.log(level, "rec.recorder.failed", emptyMap(), error) }
            if (!error.fatal) return@launch
            stopLevels()
            session.failed(recordingId, error.message ?: "recorder failed")
        }
    }

    override fun onDestroy() {
        // Not the stop job: that one is deliberately on a scope this cannot reach.
        scope.cancel()
        super.onDestroy()
    }

    private fun host(): RecorderHost = application as? RecorderHost
        ?: error("Application must implement RecorderHost")

    /** `IllegalStateException` alone says nothing; the chain is what names the real cause. */
    private fun Throwable.describe(): String = generateSequence(this) { it.cause }
        .joinToString(" <- ") { "${it::class.simpleName}: ${it.message ?: "-"}" }

    private fun notificationManager(): NotificationManager = getSystemService(NotificationManager::class.java)

    private fun notification(): Notification {
        val manager = notificationManager()
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.rec_notification_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, RecorderService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_ENQUEUE, true),
            PendingIntent.FLAG_IMMUTABLE,
        )
        host().recordingNotification(NOTIFICATION_ID, CHANNEL_ID, stop)?.let { return it }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_rec_notification)
            .setContentTitle(getString(R.string.rec_notification_title))
            // The platform ticks the elapsed time for us; updating the notification once a second
            // for three hours would not.
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis())
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.rec_notification_stop), stop).build())
            .build()
    }

    companion object {
        const val ACTION_START: String = "app.recly.recording.START"
        const val ACTION_STOP: String = "app.recly.recording.STOP"
        const val ACTION_REFRESH: String = "app.recly.recording.REFRESH"
        const val EXTRA_WORKFLOW_ID: String = "workflowId"
        const val EXTRA_TITLE: String = "title"

        /**
         * Passed straight through to [RecorderHost.onRecordingReady]: false is the caller saying it
         * will name the recording first and finish it itself. The notification's own stop action
         * has no UI to ask with, so it says true — "ready now" — and the shell decides what that
         * costs, which on the watch is a transfer and not a job.
         */
        const val EXTRA_ENQUEUE: String = "enqueue"

        /** Only a smoke test passes this; production takes ADR-006's fifteen minutes. */
        const val EXTRA_SEGMENT_SEC: String = "segmentSec"

        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1

        /** The rate a window of the live strip finishes at (docs/09 화면 원칙 6). */
        private const val LEVEL_MS = 100L

        /** Companion for the same reason [state] is: the screen comes and goes, the recording does not. */
        private val waveform = LiveWaveform()

        /** The live levels, oldest first — empty while nothing is recording. */
        fun livePeaks(): List<Float> = waveform.peaks

        /** Outlives the service on purpose — see [RecorderSession.stop]. */
        private val completionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        private val _state = MutableStateFlow<RecorderState>(RecorderState.Idle)
        val state: StateFlow<RecorderState> = _state.asStateFlow()

        private val _events = MutableSharedFlow<RecorderEvent>(extraBufferCapacity = 8)
        val events: SharedFlow<RecorderEvent> = _events.asSharedFlow()

        /** Call from something visible — a while-in-use type started from the background throws. */
        fun start(
            context: Context,
            workflowId: String?,
            title: String? = null,
            segmentSec: Int = SegmentedRecorder.DEFAULT_SEGMENT_SEC,
        ) {
            context.startForegroundService(
                Intent(context, RecorderService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_WORKFLOW_ID, workflowId)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_SEGMENT_SEC, segmentSec),
            )
        }

        /**
         * Redraws the ongoing notification from the configuration the app has now — what a change
         * of language needs, because nothing else touches this notification for the length of a
         * recording. A no-op when nothing is recording: there is no notification, and starting the
         * service to say so would be a background start of a microphone FGS.
         */
        fun refreshNotification(context: Context) {
            if (state.value == RecorderState.Idle) return
            context.startService(Intent(context, RecorderService::class.java).setAction(ACTION_REFRESH))
        }

        fun stop(context: Context, title: String? = null, enqueue: Boolean = true) {
            context.startService(
                Intent(context, RecorderService::class.java)
                    .setAction(ACTION_STOP)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_ENQUEUE, enqueue),
            )
        }
    }
}
