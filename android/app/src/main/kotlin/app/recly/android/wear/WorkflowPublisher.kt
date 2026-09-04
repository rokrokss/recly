package app.recly.android.wear

import android.content.Context
import app.recly.datalayer.WearJson
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.withContext
import recly.core.ReclyCore
import recly.core.platform.Logger

/**
 * docs/05 "워치" row: the phone owns `workflows.json` and pushes the watch a summary of it — id and
 * name, which after ADR-016 is the whole of a definition the watch could act on. The watch never
 * reads Drive, so this data item is the only way it can know what its picker should offer; which of
 * them the watch starts with is the watch's own local pointer and never travels this way.
 *
 * A data item, not a message: it is state, it has to survive the watch being out of range, and the
 * watch has to see it the moment it reconnects without the phone having to notice. `setUrgent`
 * because a workflow the user just renamed is worth a wake-up, and the payload is a few hundred
 * bytes. Publishing the same bytes twice is free — the Data Layer drops a no-op write.
 */
object WorkflowPublisher {

    /**
     * Collects for the life of the process. Every local change to the document republishes — the
     * source is `observe()`, so an edit on this phone and one that arrived by sync both count.
     */
    suspend fun run(context: Context, core: ReclyCore) {
        core.workflows.observe().collect {
            runCatching { publish(context, core) }.onFailure { report(core, it) }
        }
    }

    /**
     * A device with no Wear OS support answers every Data Layer call with `API_NOT_CONNECTED`, and
     * that is not a fault — it is what a phone-only install looks like. It gets a line, not the
     * stack trace a real failure earns.
     */
    private suspend fun report(core: ReclyCore, error: Throwable) {
        val unavailable = generateSequence(error) { it.cause }
            .any { it is ApiException && it.statusCode == CommonStatusCodes.API_NOT_CONNECTED }
        if (unavailable) {
            core.deps.logger.log(Logger.Level.INFO, "wear.workflows.unavailable", emptyMap())
        } else {
            core.deps.logger.log(Logger.Level.WARN, "wear.workflows.failed", emptyMap(), error)
        }
    }

    private suspend fun publish(context: Context, core: ReclyCore) {
        val payload = WearJson.workflows(core.workflows.summary()).encodeToByteArray()
        withContext(core.deps.io) {
            Tasks.await(
                Wearable.getDataClient(context).putDataItem(
                    PutDataRequest.create(WearJson.WORKFLOWS).setData(payload).setUrgent(),
                ),
            )
        }
    }
}
