@file:OptIn(ExperimentalTime::class)

package app.recly.android.wear

import android.net.Uri
import app.recly.android.core.CoreModule
import app.recly.android.work.WorkScheduler
import app.recly.datalayer.TransferPath
import app.recly.datalayer.WearJson
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import recly.core.ReclyCore
import recly.core.platform.Logger

/**
 * The phone half of docs/03 "워치 → 폰 전송 계약" (docs/11 A8). One file per channel, parts first
 * and `meta.json` last; every file is verified against the sha256 in its own path and acked, and
 * only a meta whose parts are all present turns into a Job.
 *
 * Play Services dispatches these callbacks on its own background thread and keeps the service
 * bound for as long as one is running, so the work is done inline with [runBlocking] rather than
 * launched onto a scope that would outlive the binding. It also serialises them, which is what we
 * want: the watch sends one file at a time.
 */
class RecListenerService : WearableListenerService() {

    /** A channel whose bytes are still arriving. Keyed by node + path — unique while it is open. */
    private data class Staged(val path: TransferPath, val file: File)

    private val staging = ConcurrentHashMap<String, Staged>()

    private val channels: ChannelClient get() = Wearable.getChannelClient(this)
    private val messages: MessageClient get() = Wearable.getMessageClient(this)

    /**
     * The path is the whole request: it says what is coming and what it must hash to. Anything that
     * is not one of the two shapes is a client this app does not speak to, and the channel is
     * closed rather than read.
     */
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        val parsed = TransferPath.parse(channel.path)
        if (parsed == null) {
            log(Logger.Level.WARN, "transfer.channel.rejected", mapOf("path" to channel.path))
            channels.close(channel)
            return
        }
        val file = stagingFile(parsed)
        file.parentFile?.mkdirs()
        staging[key(channel)] = Staged(parsed, file)
        // Awaited rather than given a listener: the task completes when the request is accepted,
        // not when the bytes are in, and a listener would run its failure branch on the main thread.
        runCatching { channels.receiveFile(channel, Uri.fromFile(file), false).await() }.onFailure {
            staging.remove(key(channel))
            file.delete()
            log(Logger.Level.ERROR, "transfer.receive.failed", mapOf("path" to channel.path), it)
        }
    }

    /**
     * The bytes have stopped. `CLOSE_REASON_NORMAL` is the watch's output closing on its own — the
     * whole file — and `CLOSE_REASON_REMOTE_CLOSE` is the watch calling `close(channel)`, which it
     * does once the file is out and which Play Services queues behind the data; either way every
     * byte the watch sent is here, and the sha256 in `acceptPart` says whether they are the right
     * ones. A disconnect, a timeout or our own close leaves a truncated file: drop it.
     */
    override fun onInputClosed(channel: ChannelClient.Channel, closeReason: Int, appErrorCode: Int) {
        val staged = staging.remove(key(channel)) ?: return
        if (closeReason !in COMPLETE_CLOSE_REASONS) {
            staged.file.delete()
            log(Logger.Level.WARN, "transfer.channel.aborted", mapOf("path" to channel.path, "reason" to closeReason))
            return
        }
        runBlocking {
            val core = CoreModule.get(applicationContext).core
            // The accept and the disk work it does belong on the core's IO dispatcher, not on
            // whatever thread Play Services handed us.
            withContext(core.deps.io) {
                when (val path = staged.path) {
                    is TransferPath.PartFile -> acceptPart(core, channel.nodeId, path, staged.file)
                    is TransferPath.Meta -> acceptMeta(core, channel.nodeId, path, staged.file)
                }
            }
        }
    }

    /**
     * The watch appearing is the cheapest moment to notice that an earlier transfer died: docs/03
     * gives parts without a meta 24 hours, and the only other look is at app start (`RecApp`).
     */
    override fun onCapabilityChanged(info: CapabilityInfo) {
        runBlocking {
            val core = CoreModule.get(applicationContext).core
            withContext(core.deps.io) {
                runCatching { core.transfer.purgeOrphans(core.deps.clock.now()) }
                    .onFailure { core.log(Logger.Level.ERROR, "transfer.purge.failed", emptyMap(), it) }
            }
        }
    }

    /**
     * `acceptPart` moves the staged file into the recording directory itself, so there is nothing
     * to clean up on the happy path; a part it rejects it deletes.
     */
    private suspend fun acceptPart(
        core: ReclyCore,
        nodeId: String,
        path: TransferPath.PartFile,
        file: File,
    ) {
        val ack = core.transfer.acceptPart(
            recordingId = path.recordingId,
            part = path.part,
            track = path.track,
            sha256Claimed = path.sha256,
            tmpPath = file.absolutePath.toPath(),
        )
        send(core, nodeId, WearJson.ACK_PART, WearJson.partAck(path, ack))
    }

    /**
     * The meta ends the transfer and starts the work. The protocol of it — the identity check, and
     * the ack that comes only after the Job exists and the executor has been woken — is
     * [MetaAcceptor], so that it is testable without a watch; this side only reads the staged file
     * and takes it off the disk, which it does whatever the acceptor makes of it.
     */
    private suspend fun acceptMeta(
        core: ReclyCore,
        nodeId: String,
        path: TransferPath.Meta,
        file: File,
    ) {
        val text = file.readText()
        file.delete()
        val acceptor = MetaAcceptor(
            core = CoreMetaFacade(core, WorkScheduler(applicationContext)),
            ack = AckSender { messagePath, payload -> send(core, nodeId, messagePath, payload) },
        )
        acceptor.accept(path, text)
    }

    /**
     * A failed ack is not a failed transfer: the file is filed, and the watch resends when its
     * five-minute timeout expires (docs/11 W4) — which this side answers idempotently.
     */
    private suspend fun send(core: ReclyCore, nodeId: String, path: String, payload: String) {
        runCatching { messages.sendMessage(nodeId, path, payload.encodeToByteArray()).await() }
            .onFailure { core.log(Logger.Level.WARN, "transfer.ack.failed", mapOf("path" to path), it) }
    }

    /**
     * Per recording, so that two recordings cannot write over each other's `meta.json` while both
     * are in flight. A part is staged under the exact name the path carries, because `acceptPart`
     * keeps the temp file's name when it files it. Neither segment can escape the directory — that
     * is what [TransferPath.parse] is for.
     */
    private fun stagingFile(path: TransferPath): File {
        val dir = File(cacheDir, "$STAGING/${path.recordingId}")
        return when (path) {
            is TransferPath.PartFile -> File(dir, path.file)
            is TransferPath.Meta -> File(dir, "meta.json")
        }
    }

    private fun key(channel: ChannelClient.Channel): String = "${channel.nodeId}${channel.path}"

    /**
     * The callbacks that are not suspending still have to say what went wrong. Play Services runs
     * every one of them on its own background thread, so blocking here is not the main thread.
     */
    private fun log(level: Logger.Level, event: String, fields: Map<String, Any?>, error: Throwable? = null) =
        runBlocking { CoreModule.get(applicationContext).core.log(level, event, fields, error) }

    private suspend fun ReclyCore.log(
        level: Logger.Level,
        event: String,
        fields: Map<String, Any?>,
        error: Throwable? = null,
    ) = deps.logger.log(level, event, fields, error)

    /** Already inside `runBlocking` on a Play Services thread; blocking on the task is the point. */
    private fun <T> Task<T>.await(): T = Tasks.await(this)

    private companion object {
        const val STAGING = "rec-transfer"
        val COMPLETE_CLOSE_REASONS = setOf(
            ChannelClient.ChannelCallback.CLOSE_REASON_NORMAL,
            ChannelClient.ChannelCallback.CLOSE_REASON_REMOTE_CLOSE,
        )
    }
}
