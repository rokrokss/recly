package app.recly.wear.transfer

import android.content.Context
import android.net.Uri
import app.recly.datalayer.AckJson
import app.recly.datalayer.AckMessage
import app.recly.datalayer.WearJson
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import java.io.IOException
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import okio.Path
import recly.core.platform.Logger

/**
 * docs/11 W4. `CapabilityClient` finds the phone, `ChannelClient` carries the file and
 * `MessageClient` brings the ack back.
 *
 * Every Play Services call is a `Task`, and every one of them is awaited on [Dispatchers.IO] rather
 * than given a listener: this runs inside a `CoroutineWorker` whose whole job is to block until the
 * transfer is done, and a listener would put the failure branch on the main thread.
 */
class WearableTransferLink(
    private val context: Context,
    private val logger: Logger,
) : TransferLink {

    override suspend fun open(): TransferChannel? = withContext(Dispatchers.IO) {
        val node = phoneNode() ?: return@withContext null
        // The listener before the first byte: an ack for a small part can beat the code that would
        // have registered it, and an ack that lands with nowhere to go costs five minutes.
        val inbox = Channel<AckMessage>(Channel.UNLIMITED)
        val messages = Wearable.getMessageClient(context)
        val listener = MessageClient.OnMessageReceivedListener { event ->
            val ack = AckJson.parse(event.path, event.data)
            if (ack == null) {
                logger.log(Logger.Level.WARN, "transfer.ack.unreadable", mapOf("path" to event.path))
            } else {
                inbox.trySend(ack)
            }
        }
        // `/rec/ack` as a prefix, which is also `/rec/ack-meta` — the two paths the phone acks on,
        // and nothing else this app would have to ignore.
        messages.addListener(listener, ackUri(), MessageClient.FILTER_PREFIX).await()
        WearableChannel(Wearable.getChannelClient(context), messages, listener, node, inbox, logger)
    }

    /**
     * A watch with no phone paired answers every Data Layer call with a failure, and a watch whose
     * phone is out of range answers with an empty node set. Neither is a fault — it is the state
     * docs/11 W4 exists for — so both are "no phone", logged at INFO and retried on the capability
     * event.
     */
    private fun phoneNode(): String? = runCatching {
        Wearable.getCapabilityClient(context)
            .getCapability(PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .await()
            .nodes
            // Nearby is the Bluetooth link this transfer actually rides; a node reachable only over
            // the cloud relay cannot take a `ChannelClient` file.
            .firstOrNull { it.isNearby }
            ?.id
    }.onFailure {
        logger.log(Logger.Level.INFO, "transfer.phone.unavailable", emptyMap(), it)
    }.getOrNull()

    private class WearableChannel(
        private val channels: ChannelClient,
        private val messages: MessageClient,
        private val listener: MessageClient.OnMessageReceivedListener,
        private val nodeId: String,
        private val inbox: Channel<AckMessage>,
        private val logger: Logger,
    ) : TransferChannel {

        /**
         * One channel per file, as the phone's `RecListenerService` expects: the path *is* the
         * request. `sendFile` closes the output stream when the last byte is in, which is what
         * turns into the phone's `onInputClosed(CLOSE_REASON_NORMAL)` and its ack.
         *
         * The `sendFile` Task completes when the request is *queued*, not when the bytes are out,
         * and its documentation says "the channel should not be immediately closed after calling
         * this method" — the "sent" signal is `ChannelCallback.onOutputClosed`. Closing right after
         * the Task, as this used to, queued a CLOSE behind the data, and the phone read that as
         * `CLOSE_REASON_REMOTE_CLOSE`, dropped the file and never acked: a 3.8 MB part went over
         * six times in an afternoon (Watch7, 2026-09-04). So the callback is registered before the
         * first byte — a small file can finish before code that runs after `sendFile` — and the
         * close waits for it.
         */
        override suspend fun send(path: String, file: Path) {
            withContext(Dispatchers.IO) {
                // Where a transfer's minutes go: each phase of one file is timed and logged, because
                // on a real Watch7 the bytes took 40 s and the file took two minutes (2026-09-04).
                val started = TimeSource.Monotonic.markNow()
                val channel = channels.openChannel(nodeId, path).await()
                val openedMs = started.elapsedNow().inWholeMilliseconds
                var queuedMs = -1L
                var sentMs = -1L
                var reasonSeen = -1
                try {
                    val outputClosed = CompletableDeferred<Int>()
                    val callback = object : ChannelClient.ChannelCallback() {
                        override fun onOutputClosed(c: ChannelClient.Channel, closeReason: Int, appErrorCode: Int) {
                            outputClosed.complete(closeReason)
                        }

                        // A link that dies mid-file closes the whole channel without ever closing
                        // the output on its own; without this the wait would outlive the worker.
                        override fun onChannelClosed(c: ChannelClient.Channel, closeReason: Int, appErrorCode: Int) {
                            outputClosed.complete(closeReason)
                        }
                    }
                    channels.registerChannelCallback(channel, callback).await()
                    try {
                        channels.sendFile(channel, Uri.fromFile(file.toFile())).await()
                        queuedMs = started.elapsedNow().inWholeMilliseconds
                        val reason = outputClosed.await()
                        sentMs = started.elapsedNow().inWholeMilliseconds
                        reasonSeen = reason
                        if (reason != ChannelClient.ChannelCallback.CLOSE_REASON_NORMAL) {
                            throw IOException("channel output closed with reason $reason before the file was sent")
                        }
                    } finally {
                        runCatching { channels.unregisterChannelCallback(channel, callback).await() }
                    }
                } finally {
                    // The bytes are the phone's business by now; closing frees the socket.
                    runCatching { channels.close(channel).await() }
                    logger.log(
                        Logger.Level.INFO,
                        "transfer.send.timing",
                        mapOf(
                            "path" to path.substringAfterLast('/'),
                            "openMs" to openedMs,
                            "queuedMs" to queuedMs,
                            "sentMs" to sentMs,
                            "closedMs" to started.elapsedNow().inWholeMilliseconds,
                            "reason" to reasonSeen,
                        ),
                    )
                }
            }
        }

        override suspend fun nextAck(): AckMessage = inbox.receive()

        override suspend fun close() {
            withContext(Dispatchers.IO) { runCatching { messages.removeListener(listener).await() } }
            inbox.close()
        }
    }

    /** Any node — only the paired phone ever acks, and there is only ever one of it. */
    private fun ackUri(): Uri = Uri.Builder().scheme("wear").authority("*").path(WearJson.ACK_PART).build()

    private companion object {
        /** Declared by the phone in `android/app/src/main/res/values/wear.xml`. */
        const val PHONE_CAPABILITY = "rec_phone"
    }
}

/** Already on [Dispatchers.IO] and the point is to wait: this is not the main thread. */
private fun <T> Task<T>.await(): T = Tasks.await(this)
