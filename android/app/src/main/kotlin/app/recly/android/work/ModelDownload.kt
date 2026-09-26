package app.recly.android.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.recly.android.R
import app.recly.android.core.CoreModule
import app.recly.android.ui.MainActivity
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import recly.core.model.Language
import recly.core.platform.Logger
import recly.core.transcribe.LocalEngineInfo
import app.recly.recording.R as RecordingR

/** Where the one speech-model download is. */
enum class ModelDownloadPhase { IDLE, DOWNLOADING, WAITING_FOR_WIFI, FAILED }

data class ModelDownloadState(
    val phase: ModelDownloadPhase = ModelDownloadPhase.IDLE,
    /** The engine's last reading — the model's size, and how much of it is on disk while partly there. */
    val info: LocalEngineInfo? = null,
    /** Why the last download stopped, while [phase] is [ModelDownloadPhase.FAILED]. */
    val error: String? = null,
) {
    /** A download is running or waiting for its network: the surfaces offer Cancel, not a second start. */
    val active: Boolean get() = phase == ModelDownloadPhase.DOWNLOADING || phase == ModelDownloadPhase.WAITING_FOR_WIFI
}

/**
 * docs/05 "고정 처리 설정 도입": the one download controller. Settings, the list's banner and rows, and
 * the Record tab's first-run card all read [state] and start or cancel through here, so no two of
 * them ever start two downloads. The download is [ModelDownloadWorker] under one unique work name —
 * it outlives the screen, and WorkManager is what remembers it across a process death.
 */
class ModelDownload private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(ModelDownloadState())
    val state: StateFlow<ModelDownloadState> = _state.asStateFlow()

    init {
        scope.launch { follow() }
    }

    /**
     * [language] is the waiting recording's local step language, or null for the saved processing
     * settings' own. [onWifi] is the mobile-data prompt's "Download on Wi-Fi": the same work, held
     * until an unmetered network is there.
     */
    fun start(language: String?, onWifi: Boolean) {
        if (state.value.phase == ModelDownloadPhase.DOWNLOADING) return
        scope.launch {
            val tag = language ?: savedLanguage()
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (onWifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .setInputData(workDataOf(ModelDownloadWorker.KEY_LANGUAGE to tag))
                .addTag(LANGUAGE_TAG + tag)
                .build()
            // REPLACE: a download waiting for Wi-Fi that is now wanted over mobile data is the same
            // download, sooner — and the partial files carry over to it.
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, request)
        }
    }

    /** Stops it where it is. The partial files stay, and the next start resumes from them. */
    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
    }

    /** The work's state as the phase, and the engine's reading beside it — every 500 ms while it downloads. */
    private suspend fun follow() {
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UNIQUE).collectLatest { works ->
            val work = works.firstOrNull { !it.state.isFinished } ?: works.lastOrNull()
            val phase = phaseOf(work?.state, work?.constraints?.requiredNetworkType)
            val language = work?.tags?.firstNotNullOfOrNull { tag -> tag.removePrefix(LANGUAGE_TAG).takeIf { it != tag } }
                ?: savedLanguage()
            val error = if (phase == ModelDownloadPhase.FAILED) work?.outputData?.getString(ModelDownloadWorker.KEY_ERROR) else null
            while (true) {
                val info = try {
                    CoreModule.get(context).core.localEngineInfo(language)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    state.value.info
                }
                _state.value = ModelDownloadState(phase, info, error)
                if (phase != ModelDownloadPhase.DOWNLOADING) break
                delay(POLL_MS)
            }
        }
    }

    private suspend fun savedLanguage(): String =
        CoreModule.get(context).core.initializeProcessing().document.settings.transcription.language.wireTag()

    companion object {
        const val UNIQUE: String = "rec-model-download"
        private const val LANGUAGE_TAG = "language:"
        private const val POLL_MS = 500L
        private const val BACKOFF_SECONDS = 10L

        @Volatile private var instance: ModelDownload? = null

        fun get(context: Context): ModelDownload = instance ?: synchronized(this) {
            instance ?: ModelDownload(context.applicationContext).also { instance = it }
        }
    }
}

/**
 * A queued download that is not running yet is waiting for Wi-Fi when that is what it asked for;
 * one on any network is about to start (or between attempts) and reads as downloading.
 */
internal fun phaseOf(state: WorkInfo.State?, network: NetworkType?): ModelDownloadPhase = when (state) {
    WorkInfo.State.RUNNING -> ModelDownloadPhase.DOWNLOADING
    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
        if (network == NetworkType.UNMETERED) ModelDownloadPhase.WAITING_FOR_WIFI else ModelDownloadPhase.DOWNLOADING
    WorkInfo.State.FAILED -> ModelDownloadPhase.FAILED
    WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED, null -> ModelDownloadPhase.IDLE
}

/** Whole percent of the model on disk; nothing known yet is 0. */
fun modelPercent(progress: Double?): Int = ((progress ?: 0.0) * 100).toInt().coerceIn(0, 100)

/** The bytes already on disk, when the engine knows both the size and the share. */
fun downloadedBytes(info: LocalEngineInfo?): Long? {
    val total = info?.modelBytes ?: return null
    val progress = info.progress ?: return null
    return (progress * total).toLong()
}

/**
 * A model's size the way every shell writes it: decimal units, whole megabytes below 1,000 MB
 * ("988 MB"), gigabytes with one decimal from there ("1.2 GB"). The units are SI symbols, not words.
 */
fun modelSize(bytes: Long, locale: Locale = Locale.getDefault()): String {
    val megabytes = Math.round(bytes / 1_000_000.0)
    return if (megabytes < 1_000) {
        String.format(locale, "%d MB", megabytes)
    } else {
        String.format(locale, "%.1f GB", bytes / 1_000_000_000.0)
    }
}

/** How the core writes a language on the wire (`KO_EN` is `ko-en`) — `Language.wire` is core-internal. */
fun Language.wireTag(): String = name.lowercase().replace('_', '-')

/**
 * docs/05 "고정 처리 설정 도입": the download itself — `prepareLocalEngine`, which also resumes the
 * recordings that were waiting for the model — as a `dataSync` foreground service, so it carries on
 * after the user leaves the app. The notification's Cancel is the same cancel as the app's.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val language = inputData.getString(KEY_LANGUAGE) ?: return Result.failure()
        val core = CoreModule.get(applicationContext).core
        val notice = ModelDownloadNotice(applicationContext, id)
        // Android 12+ refuses a foreground service started from the background — a Wi-Fi wait that
        // ends while the app is closed. The download then runs as plain work, and resumes from its
        // partial files if the system stops it.
        val foreground = try {
            setForeground(notice.info(null))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            core.deps.logger.log(Logger.Level.WARN, "model.download.background", error = e)
            false
        }
        return try {
            coroutineScope {
                val progress = if (foreground) {
                    launch {
                        while (true) {
                            delay(NOTICE_MS)
                            try {
                                setForeground(notice.info(core.localEngineInfo(language)))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                // A missed progress update is not worth the download.
                            }
                        }
                    }
                } else {
                    null
                }
                try {
                    core.prepareLocalEngine(language)
                } finally {
                    progress?.cancel()
                }
            }
            // The recordings the download released are due now.
            WorkScheduler(applicationContext).onJobsDue()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            core.deps.logger.log(Logger.Level.WARN, "model.download.failed", mapOf("attempt" to runAttemptCount + 1), e)
            if (runAttemptCount < RETRIES) Result.retry() else Result.failure(workDataOf(KEY_ERROR to e.message.orEmpty()))
        }
    }

    companion object {
        const val KEY_LANGUAGE: String = "language"
        const val KEY_ERROR: String = "error"

        /** A dropped connection is worth another go or two; a download that keeps failing is the user's to resume. */
        private const val RETRIES = 2
        private const val NOTICE_MS = 1_000L
    }
}

/** The download's ongoing notification: its progress, its bytes, and a Cancel. */
private class ModelDownloadNotice(private val context: Context, private val work: UUID) {

    fun info(reading: LocalEngineInfo?): ForegroundInfo {
        val manager = context.getSystemService(NotificationManager::class.java)
        // The model is what the channel is about; its name is the settings row's own.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.processing_speech_model), NotificationManager.IMPORTANCE_LOW),
        )
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(RecordingR.drawable.ic_rec_notification)
            .setContentTitle(context.getString(R.string.model_download_notification))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, modelPercent(reading?.progress), reading?.progress == null)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    NOTIFICATION_ID,
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(R.string.processing_cancel_download),
                    WorkManager.getInstance(context).createCancelPendingIntent(work),
                ).build(),
            )
        val total = reading?.modelBytes
        val done = downloadedBytes(reading)
        if (total != null && done != null) {
            builder.setContentText(
                context.getString(
                    R.string.processing_download_bytes,
                    modelSize(done),
                    modelSize(total),
                ),
            )
        }
        return ForegroundInfo(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private companion object {
        const val CHANNEL_ID = "model-download"

        /** Clear of the recorder's 1 and the job alerts' 100 and up. */
        const val NOTIFICATION_ID = 200
    }
}
