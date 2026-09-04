@file:OptIn(ExperimentalTime::class)

package app.recly.android.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.recly.android.settings.AppSettings
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.flow.first

/**
 * The WorkManager half of [JobScheduler] (docs/11 A5). Every request is built with the network
 * setting as it is right now, which is why building one suspends.
 */
class WorkScheduler(private val context: Context) : JobScheduler {

    override suspend fun runNow(expedited: Boolean) {
        val request = request()
            .apply { if (expedited) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
            .build()
        // KEEP: a pass that is already queued does the same work this one would, and REPLACE would
        // cancel it mid-upload. "Upload now" is the exception — the point of the button is that
        // *this* request, with its expedited flag, is the one that runs.
        val policy = if (expedited) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_RUN, policy, request)
    }

    override suspend fun armNext(delay: Duration) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_NEXT,
            ExistingWorkPolicy.REPLACE,
            request().setInitialDelay(delay.inWholeMilliseconds, TimeUnit.MILLISECONDS).build(),
        )
    }

    override suspend fun armPeriodic(replace: Boolean) {
        val request = PeriodicWorkRequestBuilder<WorkflowWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            // The worker has to know: a periodic instance that ends in failure is dropped by
            // WorkManager and the six-hour cadence is gone for good.
            .setInputData(workDataOf(WorkflowWorker.KEY_PERIODIC to true))
            .build()
        val policy = if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_PERIODIC, policy, request)
    }

    private suspend fun request(): OneTimeWorkRequest.Builder = OneTimeWorkRequestBuilder<WorkflowWorker>()
        .setConstraints(constraints())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)

    private suspend fun constraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(networkType(AppSettings(context).wifiOnly.first()))
        .build()

    companion object {
        const val UNIQUE_RUN: String = "rec-jobs"
        const val UNIQUE_NEXT: String = "rec-jobs-next"
        const val UNIQUE_PERIODIC: String = "rec-jobs-periodic"

        /** docs/11 A5: `UNMETERED` when the Wi-Fi-only setting is on, `CONNECTED` otherwise. */
        fun networkType(wifiOnly: Boolean): NetworkType =
            if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

        private const val PERIOD_HOURS = 6L
        private const val BACKOFF_SECONDS = 30L
    }
}
