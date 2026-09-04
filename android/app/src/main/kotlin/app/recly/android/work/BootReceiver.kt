@file:OptIn(ExperimentalTime::class)

package app.recly.android.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.recly.android.core.AndroidLogger
import app.recly.android.core.CoreModule
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import recly.core.job.JobStatus
import recly.core.platform.Logger

/**
 * Trigger (c). A reboot clears nothing WorkManager owns, but a phone rebooted with a job parked
 * should not have to wait for the user to open the app. The same rule every pass follows applies
 * here: re-arm the insurance, arm exactly one successor from the job table, and run a pass now
 * only if something is actually due.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        val finish = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val core = CoreModule.get(app).core
                val jobs = core.jobs.observe().first()
                val now = core.deps.clock.now()
                val scheduler = WorkScheduler(app)
                scheduler.armPeriodic()
                NextRun.delay(jobs, now)?.let { scheduler.armNext(it) }
                val runnable = jobs.count { it.status in RUNNABLE }
                if (runnable > 0) scheduler.runNow()
                core.deps.logger.log(Logger.Level.INFO, "job.boot", mapOf("runnable" to runnable))
            } catch (e: Exception) {
                // A failed boot arm must not take the process down: the periodic insurance and the
                // next foreground re-arm everything. Log and move on — on the shared event stream,
                // because what failed may well be the core the line above logs through.
                AndroidLogger().log(Logger.Level.WARN, "job.boot.failed", error = e)
            } finally {
                finish.finish()
            }
        }
    }

    private companion object {
        /** RUNNING too: a job the kill left mid-step is recovered by the executor's first pass. */
        val RUNNABLE = setOf(JobStatus.PENDING, JobStatus.WAITING, JobStatus.RUNNING)
    }
}
