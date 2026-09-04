package app.recly.wear.data

import android.content.Context
import android.net.Uri
import app.recly.datalayer.WearJson
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import recly.core.platform.Logger
import recly.core.sync.WorkflowSummary

/**
 * docs/11 W2: the picker's only source of truth. The phone publishes a summary to the
 * `/rec/workflows` data item and this watches it — a data item and not a message precisely because
 * the watch is often out of range, and it has to see the last published state the moment it comes
 * back rather than waiting for the phone to notice it is there.
 *
 * The first emission is whatever is already on the device, so a watch that starts with the phone
 * asleep still has a picker.
 */
class WorkflowSource(
    private val context: Context,
    private val logger: Logger,
) {

    fun flow(): Flow<List<WorkflowSummary>> = callbackFlow {
        val client = Wearable.getDataClient(context)
        val listener = DataClient.OnDataChangedListener { events -> trySend(events.summaries()) }

        client.addListener(listener, uri(), DataClient.FILTER_LITERAL)
        launch { send(current(client)) }

        awaitClose { client.removeListener(listener) }
    }.flowOn(Dispatchers.IO)

    /**
     * A watch with no phone paired answers every Data Layer call with a failure; that is what an
     * unpaired watch looks like, not a fault, and the picker's answer to it is "Default".
     */
    private fun current(client: DataClient): List<WorkflowSummary> = try {
        Tasks.await(client.getDataItems(uri(), DataClient.FILTER_LITERAL)).use { buffer ->
            buffer.firstOrNull()?.data?.let { WearWorkflows.parse(it) }.orEmpty()
        }
    } catch (e: Exception) {
        logger.log(Logger.Level.INFO, "wear.workflows.unavailable", emptyMap(), e)
        emptyList()
    }

    /**
     * Deleting the data item is the phone saying "no workflows", not "keep the last list" — so a
     * delete resolves to the empty list the same way an empty payload does.
     */
    private fun DataEventBuffer.summaries(): List<WorkflowSummary> = use { buffer ->
        buffer.lastOrNull()?.let { event ->
            if (event.type == DataEvent.TYPE_DELETED) emptyList() else WearWorkflows.parse(event.dataItem.data)
        }.orEmpty()
    }

    /** The wear-scheme URI for the path, on any node — only the paired phone ever publishes it. */
    private fun uri(): Uri = Uri.Builder()
        .scheme(SCHEME)
        .authority(ANY_NODE)
        .path(WearJson.WORKFLOWS)
        .build()

    private companion object {
        const val SCHEME = "wear"
        const val ANY_NODE = "*"
    }
}
