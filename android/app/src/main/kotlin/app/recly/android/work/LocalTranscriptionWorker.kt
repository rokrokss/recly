@file:OptIn(kotlin.time.ExperimentalTime::class)
package app.recly.android.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.recly.android.core.CoreModule
import kotlinx.coroutines.CancellationException

/** Offline compute only. Publication is left for the existing network-constrained worker. */
class LocalTranscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val core = CoreModule.get(applicationContext).core
        core.runLocalJobs()
        val pending = NextRun.delay(core.localJobs(), core.deps.clock.now()) != null
        WorkScheduler(applicationContext).runNetwork()
        if (pending) Result.retry() else Result.success()
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { Result.retry() }
}
