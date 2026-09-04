package app.recly.wear.transfer

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.recly.wear.RecWearApp
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import recly.core.platform.Logger

/**
 * docs/11 W4 · "주의": the transfer runs in WorkManager, not in the screen and not in a `dataSync`
 * foreground service. A three-hour recording is handed over long after the app was swiped away, and
 * Samsung's sleeping-apps policy will delay this — which is why the UI says "n waiting" honestly
 * rather than pretending the transfer is immediate.
 *
 * No `NetworkType` constraint anywhere: the Data Layer is Bluetooth to the phone. A watch with no
 * Wi-Fi and no LTE can still hand over everything it has, and a `CONNECTED` constraint would keep
 * it from doing so.
 */
object TransferScheduler {

    const val UNIQUE: String = "rec-transfer"
    const val UNIQUE_NEXT: String = "rec-transfer-next"
    const val UNIQUE_PERIODIC: String = "rec-transfer-periodic"

    /**
     * Every trigger in docs/11 W4 lands here — the finalize that queued a recording, the phone
     * appearing, and the app coming to the foreground.
     *
     * KEEP while a pass is *sending*: it sends the same files this one would, and REPLACE would
     * cancel it mid-file. But a pass that is only *waiting* — the exponential backoff a stalled
     * ack leaves behind, minutes to hours — is exactly what a new trigger should override: the
     * phone appearing or the app opening is a better reason to try than a timer set when the last
     * attempt failed, and WorkManager will not run a backed-off worker early on its own (Watch7,
     * 2026-09-04: opening the app did nothing against a 24-minute backoff). A pass that is
     * enqueued to run *now* is kept too: process start fires this three times in a second, and
     * replacing a pass in the instant between "enqueued" and "running" cancels it mid-file. The
     * state is read off WorkManager's executor, not the caller's thread — this is called from
     * `onStart`.
     */
    fun runNow(context: Context) {
        val manager = WorkManager.getInstance(context)
        val infos = manager.getWorkInfosForUniqueWork(UNIQUE)
        infos.addListener({
            val soon = System.currentTimeMillis() + IMMINENT.inWholeMilliseconds
            val keep = runCatching { infos.get() }.getOrDefault(emptyList()).any {
                it.state == WorkInfo.State.RUNNING ||
                    (it.state == WorkInfo.State.ENQUEUED && it.nextScheduleTimeMillis <= soon)
            }
            manager.enqueueUniqueWork(
                UNIQUE,
                if (keep) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<TransferWorker>()
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .build(),
            )
        }, Dispatchers.IO.asExecutor())
    }

    /**
     * The successor a pass leaves behind when it found another pass already sending. Its own unique
     * name, so it cannot be swallowed by the KEEP on [UNIQUE] — that name is held by the very pass
     * this one is waiting for. APPEND_OR_REPLACE, not REPLACE: the pass that is sending may itself
     * be an earlier follow-up under this name, and REPLACE cancelled it mid-file (Watch7,
     * 2026-09-04) — appending runs this one after it instead, and a second follow-up that finds
     * nothing left is one idle pass.
     */
    fun runLater(context: Context, delay: Duration = FOLLOW_UP) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_NEXT,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<TransferWorker>()
                .setInitialDelay(delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build(),
        )
    }

    /**
     * The insurance policy, as on the phone: every trigger above needs this process to have noticed
     * something, and a watch that was out of range when the last one fired would otherwise wait for
     * the next recording.
     */
    fun armPeriodic(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<TransferWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build(),
        )
    }

    /**
     * How long after a pass that found the sender busy to come back. The same 60 seconds the phone
     * gives `WorkflowWorker`: long enough that the pass in flight has usually finished, short enough
     * that a pass killed mid-file is picked up again without waiting for the six-hour periodic.
     */
    internal val FOLLOW_UP: Duration = 60.seconds

    /** How far ahead an enqueued pass still counts as "about to run" rather than backed off. */
    private val IMMINENT: Duration = 5.seconds

    private const val PERIOD_HOURS = 6L
    private const val BACKOFF_SECONDS = 30L
}

/**
 * One pass of [TransferSender]. Nothing else: the worker exists to give the transfer a lifetime
 * that is not the screen's and not the recorder's.
 */
class TransferWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? RecWearApp ?: return Result.success()
        val outcome = try {
            app.sender().run()
        } catch (e: CancellationException) {
            // WorkManager stopping this pass — a job limit, a policy, a replace. Not a failure of
            // the transfer, and the result of a stopped worker is not read anyway.
            throw e
        } catch (e: Throwable) {
            app.logger.log(Logger.Level.ERROR, "transfer.worker.failed", emptyMap(), e)
            return Result.retry()
        }
        if (outcome == TransferOutcome.ALREADY_RUNNING) {
            // Another pass holds the sender and has its own snapshot of the queue. This one saw
            // nothing, so it reshapes nothing on the strength of that — it only guarantees that
            // *someone* comes back, shortly after the pass in flight should be done.
            TransferScheduler.runLater(applicationContext)
            return Result.success()
        }
        app.refreshEntryPoints()
        // NO_PHONE is not a failure and must not burn the retry budget — the capability listener is
        // what brings the queue back, and the six-hour periodic is the backstop.
        return if (outcome == TransferOutcome.STALLED) Result.retry() else Result.success()
    }
}
