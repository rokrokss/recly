package app.recly.wear.transfer

import app.recly.datalayer.AckMessage
import app.recly.datalayer.PartRef
import app.recly.datalayer.TransferPath
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import recly.core.model.Part
import recly.core.platform.Logger
import recly.core.recording.MetaWriter
import recly.core.recording.RecordingRecord

/** The two calls the sender makes into the core, so a JVM test does not need a database. */
interface Recordings {

    suspend fun get(recordingId: String): RecordingRecord?

    /** Row, parts and the whole directory — the watch keeps no history (ADR-002). */
    suspend fun delete(recordingId: String)
}

/** What one pass amounted to; [TransferWorker] turns it into a WorkManager result. */
enum class TransferOutcome {
    /** Nothing was waiting. */
    IDLE,

    /** Another pass holds the sender's lock. This one saw nothing and did nothing. */
    ALREADY_RUNNING,

    /** No phone declaring `rec_phone` is reachable. Not a failure — the capability listener wakes us. */
    NO_PHONE,

    /** Everything waiting was either handed over or refused. */
    DONE,

    /** An ack did not come, or the link broke. The files are all still here; try again. */
    STALLED,
}

/**
 * docs/03 "워치 → 폰 전송 계약", the sending half: per recording, every part in `meta.parts` order,
 * then `meta.json` last. **`ack-meta ok:true` is the only thing that deletes audio from this
 * watch.** A part ack is recorded in the row — it is what stops the part being sent again — but it
 * does not license a delete: until the phone has filed the meta it has loose parts and a purge
 * timer, and a watch that has already deleted its copy has nothing to answer a `missing` list with.
 *
 * Four outcomes are worth spelling out, because they are the ones that lose a recording if they
 * are wrong:
 *
 * - **Timeout.** Five minutes with no ack (docs/11 W4) ends the pass and nothing is deleted. The
 *   part is re-sent next pass and `acceptPart` on the phone is idempotent.
 * - **`SHA256_MISMATCH`.** The bytes arrived corrupted and the phone deleted what it staged, so a
 *   resend is safe and is the likeliest fix. Once per part: a second mismatch on the same part is
 *   the file itself, not the wire, and is fatal.
 * - **`Incomplete(missing)`.** Never a completion. Nothing leaves this watch before `ack-meta
 *   ok:true`, so a phone that has not acked the meta is asking for parts the watch still has:
 *   exactly the listed ones go out again — the ones the row calls acked included, because the
 *   phone losing them is what the list means — and then the meta, for [MAX_RESENDS] rounds before
 *   `RESEND_LOOP` gives up with the audio intact. A listed part that really is not on disk can only
 *   have been deleted from outside this app: `PART_MISSING_LOCALLY`, and the rest is kept too.
 * - **A fatal nack.** The recording is marked failed and its audio is *kept*, parts and meta both.
 *   Nothing else on the watch will ever look at it again, so deleting it here would be deleting the
 *   only copy.
 */
class TransferSender(
    private val store: TransferStore,
    private val recordings: Recordings,
    private val fs: FileSystem,
    private val link: TransferLink,
    private val logger: Logger,
    private val ackTimeout: Duration = ACK_TIMEOUT,
) {

    suspend fun run(): TransferOutcome {
        // The immediate worker and the six-hour periodic one are separate unique names — WorkManager
        // will not let a periodic request share one with a one-time request — so nothing but this
        // stops two passes opening a channel on the same path and sending the same part twice.
        if (!passes.tryLock()) return TransferOutcome.ALREADY_RUNNING
        try {
            return pass()
        } finally {
            passes.unlock()
        }
    }

    private suspend fun pass(): TransferOutcome {
        val waiting = store.all().filter { it.waiting }
        if (waiting.isEmpty()) return TransferOutcome.IDLE
        val channel = link.open() ?: return TransferOutcome.NO_PHONE
        return try {
            for (row in waiting) {
                if (sendRecording(channel, row) == Step.STALLED) return TransferOutcome.STALLED
            }
            TransferOutcome.DONE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // A broken link fails the next recording the same way; end the pass and let WorkManager
            // bring it back. Nothing was deleted that the phone had not filed the meta for.
            logger.log(Logger.Level.WARN, "transfer.send.failed", emptyMap(), e)
            TransferOutcome.STALLED
        } finally {
            runCatching { channel.close() }
        }
    }

    /** Whether the pass can go on to the next recording, or has to stop here. */
    private enum class Step { NEXT, STALLED }

    private suspend fun sendRecording(channel: TransferChannel, row: TransferRow): Step {
        val id = row.recordingId
        // Deleted, or never existed: the row is all that is left of it and it sends nothing.
        val record = recordings.get(id) ?: run {
            store.remove(id)
            return Step.NEXT
        }
        val acked = row.acked.toMutableSet()
        /** Parts a `SHA256_MISMATCH` has already bought one resend. */
        val retried = mutableSetOf<PartRef>()
        var resends = 0

        resend@ while (true) {
            for (part in record.meta.parts) {
                val ref = PartRef(part.part, part.track)
                if (ref in acked) continue
                val file = record.dir / part.file
                if (!fs.exists(file)) {
                    // Purged from under us, or deleted by a completed transfer whose row write did
                    // not survive. There is nothing to send and nothing to wait for; the meta ack
                    // settles whether the phone has it.
                    store.acked(id, ref)
                    acked += ref
                    continue
                }
                channel.send(path(id, part).serialize(), file)
                val ack = awaitAck<AckMessage.Part>(channel, id) { it.ref == ref }
                    ?: return stalled(id, "part ${part.part}/${part.track}")
                if (!ack.ok) {
                    // The phone deletes what it staged on a mismatch, so a resend cannot duplicate
                    // anything — and corruption on the wire is the likeliest cause. Once: a second
                    // mismatch on the same bytes is the file, and re-sending it forever costs the
                    // battery a three-hour recording's worth of Bluetooth.
                    if (ack.reason == SHA256_MISMATCH && retried.add(ref)) {
                        logger.log(
                            Logger.Level.WARN,
                            "transfer.part.mismatch.retry",
                            mapOf("recordingId" to id, "part" to part.part, "track" to part.track),
                        )
                        continue@resend
                    }
                    store.fail(id, ack.reason ?: UNKNOWN)
                    return Step.NEXT
                }
                // Recorded, not acted on: the part stays on disk until the meta is acked.
                store.acked(id, ref)
                acked += ref
            }

            val meta = record.dir / MetaWriter.metaFileName(MetaWriter.baseName(record.meta))
            if (!fs.exists(meta)) {
                // Every part is acked and the phone can never be told the recording is complete.
                // Not recoverable by re-sending, and the parts are already on the phone as orphans
                // it purges after 24 hours (docs/03).
                store.fail(id, META_MISSING)
                return Step.NEXT
            }
            channel.send(TransferPath.Meta(id).serialize(), meta)
            val ack = awaitAck<AckMessage.Meta>(channel, id) { true } ?: return stalled(id, "meta")

            when {
                ack.ok -> {
                    complete(id)
                    return Step.NEXT
                }

                ack.missing.isNotEmpty() -> {
                    val gone = ack.missing.filter { ref ->
                        val name = record.file(ref) ?: return@filter true
                        !fs.exists(record.dir / name)
                    }
                    if (gone.isNotEmpty()) {
                        // Not reachable by any transfer this app ran: the watch keeps every part
                        // until `ack-meta ok:true`, so a phone that is still asking cannot have
                        // licensed a delete. Something outside deleted the audio, and there is no
                        // pass that can produce it — but the phone has just said it does *not* have
                        // the recording, so completing here would delete the rest of the only copy.
                        logger.log(
                            Logger.Level.ERROR,
                            "transfer.meta.missing.gone",
                            mapOf("recordingId" to id, "parts" to gone.size),
                        )
                        store.fail(id, PART_MISSING_LOCALLY)
                        return Step.NEXT
                    }
                    if (++resends > MAX_RESENDS) {
                        store.fail(id, RESEND_LOOP)
                        return Step.NEXT
                    }
                    // Listed parts the row already calls acked go out again too: the list is the
                    // phone saying it lost them, and `acceptPart` overwrites what it staged.
                    store.unack(id, ack.missing)
                    acked -= ack.missing.toSet()
                }

                else -> {
                    store.fail(id, ack.reason ?: UNKNOWN)
                    return Step.NEXT
                }
            }
        }
    }

    /**
     * `ack-meta ok:true` and nothing else — the one place in this class that deletes audio. Every
     * part, the meta, the directory and the row go together, and the recording is off this watch:
     * the phone has filed all of it, and the watch never had anything else to do with it.
     */
    private suspend fun complete(recordingId: String) {
        // Files first: if the process dies between the two, the next pass finds no recording and
        // drops the row. The other order would leave audio nothing ever looks at again.
        recordings.delete(recordingId)
        store.remove(recordingId)
        logger.log(Logger.Level.INFO, "transfer.complete", mapOf("recordingId" to recordingId))
    }

    private fun stalled(recordingId: String, what: String): Step {
        logger.log(
            Logger.Level.WARN,
            "transfer.ack.timeout",
            mapOf("recordingId" to recordingId, "waiting" to what, "timeoutSec" to ackTimeout.inWholeSeconds),
        )
        return Step.STALLED
    }

    /**
     * The ack for what was just sent, or null after [ackTimeout]. Anything else that arrives is
     * dropped: only one file is in flight at a time, so an ack that is not this one is a duplicate
     * of one already acted on or belongs to a recording this pass has finished with.
     */
    private suspend inline fun <reified T : AckMessage> awaitAck(
        channel: TransferChannel,
        recordingId: String,
        crossinline match: (T) -> Boolean,
    ): T? = withTimeoutOrNull(ackTimeout) {
        val started = TimeSource.Monotonic.markNow()
        while (true) {
            val ack = channel.nextAck()
            if (ack is T && ack.recordingId == recordingId && match(ack)) {
                // Timed for the same reason as `transfer.send.timing`: the ack's lateness is the
                // other half of a slow transfer.
                logger.log(
                    Logger.Level.INFO,
                    "transfer.ack.received",
                    mapOf("recordingId" to recordingId, "waitMs" to started.elapsedNow().inWholeMilliseconds),
                )
                return@withTimeoutOrNull ack
            }
        }
        @Suppress("UNREACHABLE_CODE") null
    }

    private fun path(recordingId: String, part: Part) = TransferPath.PartFile(
        recordingId = recordingId,
        part = part.part,
        track = part.track,
        sha256 = part.sha256,
        file = part.file,
    )

    /** The name the meta gives the part the phone is asking for; null if the meta never had one. */
    private fun RecordingRecord.file(ref: PartRef): String? =
        meta.parts.firstOrNull { it.part == ref.part && it.track == ref.track }?.file

    companion object {
        /**
         * One pass at a time in this process. On the companion because a [TransferSender] is built
         * per pass ([app.recly.wear.RecWearApp.sender]), so an instance field would lock nothing.
         */
        private val passes = Mutex()

        /** docs/11 W4. */
        val ACK_TIMEOUT: Duration = 5.minutes

        /**
         * A phone that answers a resend with the same `missing` list is not going to stop. Two
         * rounds is generous — one covers the ordinary lost-ack case — and the third marks the
         * recording failed with its audio intact rather than sending it forever.
         */
        const val MAX_RESENDS: Int = 2

        /** `recly.core.transfer.TransferReceiver.SHA_MISMATCH`, the one nack reason worth a resend. */
        const val SHA256_MISMATCH: String = "SHA256_MISMATCH"

        const val UNKNOWN: String = "UNKNOWN"
        const val META_MISSING: String = "META_MISSING"
        const val RESEND_LOOP: String = "RESEND_LOOP"

        /** A part the phone asked for again is not on disk — only possible by an outside delete. */
        const val PART_MISSING_LOCALLY: String = "PART_MISSING_LOCALLY"
    }
}
