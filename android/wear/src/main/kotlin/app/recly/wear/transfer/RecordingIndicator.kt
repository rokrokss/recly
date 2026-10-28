@file:OptIn(ExperimentalTime::class)

package app.recly.wear.transfer

import android.content.Context
import android.net.Uri
import app.recly.datalayer.WearRecording
import app.recly.recording.RecorderService
import app.recly.recording.RecorderState
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import recly.core.platform.Logger

/**
 * docs/03 "Watch → phone transfer contract": whether this watch is recording, told to the phone ([WearRecording]),
 * which shows it while the first part is still a segment away (docs/11 "While the paired Galaxy Watch records").
 *
 * The recorder's own state is the only input: the item is put when it becomes [RecorderState.Recording] and deleted
 * when it leaves it. The first reading is the one a process starts with — nothing records yet — so a process that
 * died mid-recording deletes the item it left standing as soon as the app runs again.
 */
object RecordingIndicator {

    /**
     * For the life of the process. Every call is awaited before the next reading is taken, so a delete can never
     * overtake the put before it; a state flow keeps only the newest reading while one is in flight, which is all
     * the phone wants.
     */
    suspend fun run(context: Context, logger: Logger) {
        RecorderService.state
            .map { it as? RecorderState.Recording }
            .distinctUntilChanged()
            .collect { recording ->
                // A watch with no phone paired fails every Data Layer call; that is a line, not a fault.
                runCatching { if (recording != null) put(context, recording, logger) else clear(context, logger) }
                    .onFailure {
                        logger.log(Logger.Level.WARN, "transfer.recording.failed", mapOf("recording" to (recording != null)), it)
                    }
            }
    }

    /** Urgent: the phone shows it at once, not when the Data Layer next batches. */
    private fun put(context: Context, recording: RecorderState.Recording, logger: Logger) {
        val request = PutDataRequest.create(WearRecording.PATH)
            .setData(WearRecording.encode(recording.recordingId, recording.startedAt))
            .setUrgent()
        Wearable.getDataClient(context).putDataItem(request).await()
        logger.log(
            Logger.Level.INFO,
            "transfer.recording.state",
            mapOf("recording" to true, "recordingId" to recording.recordingId),
        )
    }

    /**
     * Only this watch's own item: a phone paired with two watches syncs each one's item to the other, and a
     * wildcard host would take down the one the other watch is still recording under.
     */
    private fun clear(context: Context, logger: Logger) {
        val node = Wearable.getNodeClient(context).localNode.await().id
        val uri = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).authority(node).path(WearRecording.PATH).build()
        val deleted = Wearable.getDataClient(context).deleteDataItems(uri).await()
        logger.log(Logger.Level.INFO, "transfer.recording.state", mapOf("recording" to false, "deleted" to deleted))
    }

    /** Called from a collector on `Dispatchers.IO` ([app.recly.wear.RecWearApp]); blocking on the task is the point. */
    private fun <T> Task<T>.await(): T = Tasks.await(this)
}
