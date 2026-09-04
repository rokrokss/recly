package app.recly.wear.ui

import android.content.Context
import app.recly.recording.RecorderEvent
import app.recly.recording.RecorderService
import app.recly.recording.RecorderState
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The recorder as the screen sees it. [RecorderService] is a process-wide object with static state,
 * which is right for something that outlives every screen and wrong for something a JVM test wants
 * to drive — so the ViewModel talks to this and the activity hands it the real one.
 */
interface RecorderControl {
    val state: StateFlow<RecorderState>
    val events: SharedFlow<RecorderEvent>

    fun start(workflowId: String?)

    fun stop()
}

/**
 * The real one. The stop is the plain "ready now" one — there is no title dialog on a watch, so
 * nothing is being held back — and what ready means here is `RecWearApp.onRecordingReady`: the
 * transfer queue, never a job (docs/11 "주의"). The screen does not decide that and cannot get it
 * wrong.
 */
class ServiceRecorderControl(private val context: Context) : RecorderControl {

    override val state: StateFlow<RecorderState> = RecorderService.state

    override val events: SharedFlow<RecorderEvent> = RecorderService.events

    override fun start(workflowId: String?) = RecorderService.start(context, workflowId)

    override fun stop() = RecorderService.stop(context, title = null)
}
