@file:OptIn(ExperimentalTime::class)

package app.recly.recording

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import recly.core.platform.Logger

/** The capture as [RecorderSession] sees it — [SegmentedRecorder] on a device, a fake on the JVM. */
interface Capture {
    suspend fun stop(title: String?): StopResult
}

/**
 * [RecorderService] minus the service: which tap counts, what a stop that arrives while the
 * microphone is still opening does, and who the finished recording is handed to.
 *
 * It is a class of its own because that is the part that has to be right and the part a `Service`
 * cannot be asked about — the real one needs a foreground notification and a `MediaRecorder` before
 * it will do anything. Everything here runs on the JVM in `RecorderSessionTest`.
 *
 * One instance per service instance, holding one recording at a time; the state and event flows are
 * the process-wide ones the service owns, because a screen that comes and goes has to be able to
 * read them without binding.
 */
internal class RecorderSession(
    private val host: RecorderHost,
    private val state: MutableStateFlow<RecorderState>,
    private val events: MutableSharedFlow<RecorderEvent>,
    /** Outlives the service on purpose — a finalize may not be cancelled half way. */
    private val completionScope: CoroutineScope,
    /** `stopSelf`: nothing is recording and nothing is finalizing any more. */
    private val onIdle: () -> Unit,
) {
    private var capture: Capture? = null
    private var stopJob: Job? = null

    /**
     * A stop that arrived while the microphone was still opening. It cannot be served then — there
     * is no recording to finalize yet — and it must not be dropped either, so it waits here for
     * [started] and is replayed the instant there is something to stop.
     */
    private var pendingStop: PendingStop? = null

    private data class PendingStop(val title: String?, val enqueue: Boolean)

    /** Idle to Starting. False when something is already running: one recording at a time. */
    fun begin(): Boolean {
        if (state.value != RecorderState.Idle) return false
        state.value = RecorderState.Starting
        return true
    }

    /** The microphone is open and the row exists. Any stop that raced the start is served now. */
    fun started(capture: Capture, recordingId: String, startedAt: Instant, workflowId: String?) {
        this.capture = capture
        state.value = RecorderState.Recording(recordingId, startedAt, workflowId)
        pendingStop?.let {
            pendingStop = null
            stop(it.title, it.enqueue)
        }
    }

    /** The microphone never opened: back to Idle, and a stop that was waiting has nothing to do. */
    fun startFailed() {
        capture = null
        pendingStop = null
        state.value = RecorderState.Idle
    }

    /**
     * The button, the notification action and a second tap on either can all arrive. A stop happens
     * once: finalize is not idempotent from the user's side, and handing the same recording over
     * twice is not free.
     */
    fun stop(title: String?, enqueue: Boolean) {
        if (stopJob != null || state.value == RecorderState.Stopping) return
        if (state.value == RecorderState.Starting) {
            // Not `stopSelf`: leaving now would strand a recording that is about to start, and a
            // service that goes away with the state still Starting wedges the button for good.
            pendingStop = PendingStop(title, enqueue)
            return
        }
        val capture = capture ?: run { onIdle(); return }
        this.capture = null
        state.value = RecorderState.Stopping

        // Built lazily and stored BEFORE it runs: a stop that completes inline (or on a fast
        // dispatcher) would otherwise clear `stopJob` in its finally and then have the completed
        // job stored over the null — and every later stop would be refused.
        val job = completionScope.launch(start = CoroutineStart.LAZY) {
            // The service is on its way out and the process may follow; a cancelled finalize is a
            // recording with no `meta.json` and no job.
            withContext(NonCancellable) {
                try {
                    val core = host.core()
                    when (val result = capture.stop(title)) {
                        StopResult.NotRecording -> Unit

                        // Not finalized on purpose: nothing to hand over until the missing parts
                        // are filed, and nothing to name.
                        is StopResult.Deferred -> events.emit(
                            RecorderEvent.Finished(
                                recordingId = result.recordingId,
                                durationSec = 0.0,
                                parts = 0,
                                silenced = emptyList(),
                                enqueue = enqueue,
                                deferred = true,
                            ),
                        )

                        is StopResult.Finalized -> {
                            val outcome = result.outcome
                            host.onRecordingReady(outcome.recordingId, enqueue)
                            events.emit(
                                RecorderEvent.Finished(
                                    recordingId = outcome.recordingId,
                                    durationSec = outcome.durationSec,
                                    parts = outcome.parts,
                                    silenced = outcome.silenced,
                                    enqueue = enqueue,
                                ),
                            )
                            core.deps.logger.log(
                                Logger.Level.INFO,
                                "rec.recorder.stop",
                                mapOf(
                                    "recordingId" to outcome.recordingId,
                                    "durationSec" to outcome.durationSec,
                                    "parts" to outcome.parts,
                                    "enqueue" to enqueue,
                                ),
                            )
                        }
                    }
                } catch (e: Exception) {
                    events.emit(RecorderEvent.Failed(null, e.message ?: e::class.simpleName.orEmpty()))
                } finally {
                    state.value = RecorderState.Idle
                    onIdle()
                }
            }
        }
        stopJob = job
        job.invokeOnCompletion { if (stopJob === job) stopJob = null }
        job.start()
    }

    /**
     * A fatal capture error ends the recording the same way the notification's stop action does:
     * there is nobody to ask for a title, so what was captured is ready as it stands. A start that
     * is still in flight is not abandoned — [stop] parks it and [started] finishes the job.
     */
    suspend fun failed(recordingId: String?, reason: String) {
        events.emit(RecorderEvent.Failed(recordingId, reason))
        if (state.value == RecorderState.Starting || capture != null) {
            stop(title = null, enqueue = true)
        } else if (stopJob == null) {
            state.value = RecorderState.Idle
            onIdle()
        }
    }
}
