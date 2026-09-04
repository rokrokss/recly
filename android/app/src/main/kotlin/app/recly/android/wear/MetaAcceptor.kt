package app.recly.android.wear

import app.recly.android.work.JobScheduler
import app.recly.datalayer.TransferPath
import app.recly.datalayer.WearJson
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import recly.core.ReclyCore
import recly.core.job.EnqueueResult
import recly.core.platform.Logger
import recly.core.transfer.AcceptMetaResult

/**
 * Everything the meta half of the transfer needs from the core and the scheduler, and so the whole
 * of what a JVM test has to stand in for — as [app.recly.android.work.JobFacade] is for the worker.
 */
interface MetaFacade {

    suspend fun acceptMeta(json: String): AcceptMetaResult

    /** The workflow the watch started the recording with, as the stored meta now records it. */
    suspend fun workflowId(recordingId: String): String?

    suspend fun enqueue(recordingId: String, workflowId: String?): EnqueueResult

    /** Wakes the executor — the same path the phone recorder's own stop uses (docs/11 A5 (a)). */
    suspend fun onJobsDue()

    suspend fun log(level: Logger.Level, event: String, fields: Map<String, Any?>, error: Throwable? = null)
}

/** One ack message back to the node that opened the channel. Never throws — see `RecListenerService.send`. */
fun interface AckSender {
    suspend fun send(path: String, payload: String)
}

/**
 * docs/03 "워치 → 폰 전송 계약", the part of it that is a protocol rather than a file transfer: the
 * meta ends the transfer and starts the work, and `ack-meta ok:true` is the watch's licence to
 * delete its only copy of the audio. Two rules follow, and this class exists to keep both testable
 * off a device.
 *
 * 1. The meta must say it is the recording the path said it was. The body's `recordingId` is what
 *    the core files everything under, so a body that disagrees with the path would write into a
 *    recording the watch never named — checked before any core call, not after.
 * 2. The ok ack goes out last, after the recording is filed *and* queued *and* the executor woken.
 *    Anything that throws before that leaves no ack, and the watch's five-minute retry (docs/11 W4)
 *    resends the meta; `acceptMeta` and `enqueue` are both idempotent, so the second pass lands on
 *    the same rows and finishes the job.
 */
class MetaAcceptor(
    private val core: MetaFacade,
    private val ack: AckSender,
) {
    suspend fun accept(path: TransferPath.Meta, json: String) {
        // A body whose id cannot be read at all is treated as a mismatch too: it is fatal for this
        // recording either way, and the alternative is handing the core a document that decides for
        // itself which directory it belongs to.
        if (recordingIdOf(json) != path.recordingId) {
            // The nack goes out first and on its own: nothing — not even logging — may stand
            // between a body the path does not vouch for and the watch being told so.
            nack(path, reason = RECORDING_ID_MISMATCH)
            core.log(
                Logger.Level.WARN,
                "wear.meta.mismatch",
                mapOf("recordingId" to path.recordingId),
            )
            return
        }
        try {
            when (val result = core.acceptMeta(json)) {
                is AcceptMetaResult.Complete -> complete(path)

                is AcceptMetaResult.Incomplete -> send(
                    WearJson.metaAck(path.recordingId, ok = false, missing = result.missingParts),
                )

                is AcceptMetaResult.Invalid -> nack(path, reason = result.reason)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // No ack. The transfer is not lost — the watch still holds the audio and resends.
            core.log(Logger.Level.ERROR, "wear.meta.failed", mapOf("recordingId" to path.recordingId), e)
        }
    }

    /**
     * The order is the contract: filed, queued, executor woken, and only then acked.
     *
     * If the process dies between the enqueue and the wake, the job is still on the queue and
     * nothing is lost: `RecApp.onCreate` re-arms the queue at the next app start (after
     * `RecordingRecovery`), and the six-hour periodic worker covers a process that never starts.
     */
    private suspend fun complete(path: TransferPath.Meta) {
        val enqueued = core.enqueue(path.recordingId, core.workflowId(path.recordingId))
        val level = when (enqueued) {
            // Not failures — the recording is filed and shows in the list either way. NoWorkflow
            // means nothing is configured to run on it, PartsPurged that the work is already done.
            is EnqueueResult.NoWorkflow, is EnqueueResult.PartsPurged -> Logger.Level.WARN
            else -> Logger.Level.INFO
        }
        core.onJobsDue()
        // The ack is the last thing the protocol needs; the log is informational and must never
        // stand between a filed-and-woken recording and the watch being told so.
        send(WearJson.metaAck(path.recordingId, ok = true))
        core.log(
            level,
            "transfer.enqueued",
            mapOf("recordingId" to path.recordingId, "enqueue" to enqueued::class.simpleName),
        )
    }

    /** Every ack names the id the *path* carried: it is the only id both sides agreed on. */
    private suspend fun nack(path: TransferPath.Meta, reason: String) =
        send(WearJson.metaAck(path.recordingId, ok = false, reason = reason))

    private suspend fun send(payload: String) = ack.send(WearJson.ACK_META, payload)

    /** `recJson` is `internal` to :core, and one field is all this side needs. */
    private fun recordingIdOf(json: String): String? = runCatching {
        Json.parseToJsonElement(json).jsonObject["recordingId"]?.jsonPrimitive?.content
    }.getOrNull()

    private companion object {
        const val RECORDING_ID_MISMATCH = "RECORDING_ID_MISMATCH"
    }
}

/**
 * The real one. Every core call is hopped onto `CoreDeps.io` as [app.recly.android.work.CoreJobFacade]
 * does — the caller is on whatever thread Play Services handed the listener service.
 */
class CoreMetaFacade(
    private val core: ReclyCore,
    private val scheduler: JobScheduler,
) : MetaFacade {

    override suspend fun acceptMeta(json: String): AcceptMetaResult =
        withContext(core.deps.io) { core.transfer.acceptMeta(json) }

    override suspend fun workflowId(recordingId: String): String? =
        withContext(core.deps.io) { core.recordings.get(recordingId)?.meta?.workflowId }

    override suspend fun enqueue(recordingId: String, workflowId: String?): EnqueueResult =
        withContext(core.deps.io) { core.enqueue(recordingId, workflowId) }

    override suspend fun onJobsDue() = scheduler.onJobsDue()

    override suspend fun log(
        level: Logger.Level,
        event: String,
        fields: Map<String, Any?>,
        error: Throwable?,
    ) = core.deps.logger.log(level, event, fields, error)
}
