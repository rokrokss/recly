@file:OptIn(kotlin.time.ExperimentalTime::class)

package app.recly.android

import android.app.Application
import app.recly.android.core.CoreModule
import app.recly.android.entry.RecWidget
import app.recly.android.ui.DisconnectGate
import app.recly.android.wear.WorkflowPublisher
import app.recly.android.work.JobAlertNotifier
import app.recly.android.work.NextRun
import app.recly.android.work.WorkScheduler
import app.recly.recording.RecorderHost
import app.recly.recording.RecorderService
import app.recly.recording.RecordingRecovery
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import recly.core.ReclyCore
import recly.core.job.EnqueueResult
import recly.core.platform.Logger

/**
 * Three things only. `RecorderService` lives in `:android:recording`, which cannot see [CoreModule],
 * so the shell hands its core over through `RecorderHost`. A process that is starting is a process
 * that may have been killed mid-recording, so it reconciles what the last one left (docs/03
 * "크래시 시 마지막 경계까지는 복구 가능") before the user can do anything about it. And whatever the
 * reconcile queued — plus anything a previous run left parked — needs a scheduler behind it.
 */
class RecApp : Application(), RecorderHost {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            val core = core()
            runCatching { RecordingRecovery(core, this@RecApp).reconcile() }
                .onFailure { core.deps.logger.log(Logger.Level.ERROR, "rec.recovery.failed", emptyMap(), it) }
            // docs/11 A5 trigger (d): the 6-hour insurance, and one run now for whatever recovery
            // just put back on the queue.
            runCatching {
                val scheduler = WorkScheduler(this@RecApp)
                scheduler.armPeriodic()
                NextRun.delay(core.jobs.observe().first(), core.deps.clock.now())
                    ?.let { scheduler.armNext(it) }
                scheduler.runNow()
            }.onFailure { core.deps.logger.log(Logger.Level.ERROR, "job.schedule.failed", emptyMap(), it) }

            // docs/03: parts that never got their meta are rubbish after 24 hours. The other look
            // is `RecListenerService.onCapabilityChanged`; between them a watch that gave up mid
            // transfer cannot leave audio on the phone forever.
            runCatching { core.transfer.purgeOrphans(core.deps.clock.now()) }
                .onFailure { core.deps.logger.log(Logger.Level.ERROR, "transfer.purge.failed", emptyMap(), it) }
        }
        // docs/05 "워치" row: the watch's picker is fed from here and nowhere else.
        scope.launch { WorkflowPublisher.run(this@RecApp, core()) }
        // docs/10 "사용자가 고칠 수 있는 실패와 그 알림": the queue is the only thing that knows a
        // reason has been fixed, so the notifications live off it rather than off the failures.
        scope.launch { JobAlertNotifier(this@RecApp).run(core()) }
        scope.launch { publishRecorderState() }
    }

    /**
     * The widget lives in the launcher's process and only sees what it is pushed. Glance's own
     * session keeps it live while the host is bound; this covers the rest. A `StateFlow` already
     * drops repeats, so this is one RemoteViews round trip per real change of state.
     */
    private suspend fun publishRecorderState() {
        RecorderService.state.collect {
            runCatching { RecWidget().updateAll(this@RecApp) }
        }
    }

    override suspend fun core(): ReclyCore = CoreModule.get(this).core

    /**
     * docs/03: while a disconnect is running, nothing on this phone starts a recording. The screens
     * that can start one ask [DisconnectGate] before they fire the intent; this is the answer for
     * the one that was already in flight when the gate shut.
     */
    override fun startsRefused(): Boolean = DisconnectGate.busy

    /**
     * What "ready" costs on a phone: a job, and a scheduler woken to run it (docs/11 A5 trigger
     * (a)) — the finalize that produced this had no UI behind it to do either.
     *
     * [enqueue] false is `RecordingViewModel`'s Stop: docs/03 asks for a title first, so the job
     * waits for the dialog and the ViewModel enqueues once it is answered.
     */
    override suspend fun onRecordingReady(recordingId: String, enqueue: Boolean) {
        if (!enqueue) return
        if (core().enqueue(recordingId) is EnqueueResult.Enqueued) WorkScheduler(this).onJobsDue()
    }
}
