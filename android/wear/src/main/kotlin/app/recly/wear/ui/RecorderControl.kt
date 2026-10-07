package app.recly.wear.ui

import android.content.Context
import app.recly.recording.RecorderEvent
import app.recly.recording.RecorderService
import app.recly.recording.RecorderState
import app.recly.wear.core.CoreModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    fun start()

    fun stop()

    /** docs/03 "Metadata": marks [atSec] of [recordingId]; false when the core took it as a repeat. */
    suspend fun highlight(recordingId: String, atSec: Double): Boolean
}

/**
 * The real one. The stop is the plain "ready now" one — there is no title dialog on a watch, so
 * nothing is being held back — and what ready means here is `RecWearApp.onRecordingReady`: the
 * transfer queue, never a job (docs/11 "Caveats"). The screen does not decide that and cannot get it
 * wrong.
 */
class ServiceRecorderControl(private val context: Context) : RecorderControl {

    override val state: StateFlow<RecorderState> = RecorderService.state

    override val events: SharedFlow<RecorderEvent> = RecorderService.events

    override fun start() = RecorderService.start(context)

    override fun stop() = RecorderService.stop(context, title = null)

    // Into the watch's own meta, which goes to the phone with the recording (docs/03 "Watch → phone
    // transfer contract") — the phone files the marks it carries.
    override suspend fun highlight(recordingId: String, atSec: Double): Boolean = withContext(Dispatchers.IO) {
        CoreModule.get(context).recordings.addHighlight(recordingId, atSec)
    }
}
