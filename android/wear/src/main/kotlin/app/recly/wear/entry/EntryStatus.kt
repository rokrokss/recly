package app.recly.wear.entry

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import app.recly.recording.RecorderService
import app.recly.recording.RecorderState
import app.recly.wear.R
import app.recly.wear.RecWearApp
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The one line the tile and the complication both show. They render outside this app's process but
 * *run* inside it, so the recorder state is the live [RecorderService.state] rather than a guess,
 * and the queue is the same queue the screen's badge reads.
 */
suspend fun Context.entryStatus(): String {
    // P7: the phones' words for the moments either side of the recording.
    when (RecorderService.state.value) {
        RecorderState.Idle -> Unit
        RecorderState.Starting -> return getString(R.string.recording_starting)
        is RecorderState.Recording -> return getString(R.string.recording_active)
        RecorderState.Stopping -> return getString(R.string.recording_stopping)
    }
    val app = applicationContext as? RecWearApp
    // P1: a recording the phone refused outranks a wait, as on the screen.
    if ((app?.failedCount() ?: 0) > 0) return getString(R.string.transfer_failed_badge)
    val pending = app?.pendingCount() ?: 0
    if (pending == 0) return getString(R.string.entry_ready)
    // docs/11 W2: the screen's own rule — a pass with a phone on the other end is "sending", and
    // the same recordings with nobody there are "waiting".
    val sending = app?.queue?.sending?.value == true
    return getString(if (sending) R.string.sending_badge else R.string.pending_badge)
}

/**
 * `TileService` answers in a `ListenableFuture` and the counts come off a suspending queue. The
 * adapter is the sanctioned bridge; cancelling the future cancels the read, which matters because
 * the tile is asked for and dropped every time the user swipes past it.
 */
fun <T> CoroutineScope.future(tag: String, block: suspend () -> T): ListenableFuture<T> =
    CallbackToFutureAdapter.getFuture { completer ->
        val job = launch {
            runCatching { block() }
                .onSuccess { completer.set(it) }
                .onFailure { completer.setException(it) }
        }
        completer.addCancellationListener({ job.cancel() }, Runnable::run)
        tag
    }
