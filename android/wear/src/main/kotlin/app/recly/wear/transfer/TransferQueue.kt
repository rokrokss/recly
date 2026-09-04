package app.recly.wear.transfer

import app.recly.datalayer.PartRef
import app.recly.datalayer.wire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import recly.core.model.Track
import recly.core.platform.Logger

/** One recording waiting for the phone, and how far it got. */
data class TransferRow(
    val recordingId: String,
    /** The parts the phone has already said `ok` to — never sent again (docs/03). */
    val acked: Set<PartRef> = emptySet(),
    /** Non-null once the phone refused the recording outright. The audio is kept, not deleted. */
    val failedReason: String? = null,
) {
    val waiting: Boolean get() = failedReason == null
}

/**
 * What the watch does with a recording once it is finalized: hold it until the phone has it
 * (docs/11 W4). The watch has no job queue of its own — it never runs a workflow and never touches
 * Drive (ADR-002) — so "done" here means the phone acked every part, not that anything ran.
 *
 * This is the half the screen and the shell see. [TransferStore] is the other half, and only
 * [TransferSender] holds one: a queue whose rows anything could rewrite would be a queue whose
 * "acked" nobody could trust to mean the phone has the audio.
 */
interface TransferQueue {

    /** How many recordings are still waiting for the phone — the badge on the main screen. */
    val pending: StateFlow<Int>

    /** How many the phone refused. Their audio is still on the watch, and the screen says so. */
    val failed: StateFlow<Int>

    /**
     * Idempotent, because more than one thing hands the same recording over: the stop that
     * finalized it, the recovery scan that finds it again before the next recording, and
     * [reconcile] at startup. A recording already on the queue keeps the parts it has acked.
     */
    suspend fun add(recordingId: String)

    /**
     * Everything finalized on disk that the phone has not acked. The process can die between the
     * finalize and the [add], and a recording nothing ever hands over is audio that sits on the
     * watch forever. Also the first read of the file the rows live in.
     */
    suspend fun reconcile()
}

/** The sender's view: the rows themselves, and the four things that can happen to one. */
interface TransferStore : TransferQueue {

    /** Every row, waiting and failed alike, oldest first. Loads the file if it is not loaded. */
    suspend fun all(): List<TransferRow>

    /** The phone said `ok` to this part; the watch may delete its copy. */
    suspend fun acked(recordingId: String, part: PartRef)

    /** `ack-meta` came back `Incomplete`: the phone wants these parts again after all. */
    suspend fun unack(recordingId: String, parts: Collection<PartRef>)

    /** A nack this recording cannot recover from. The row stays so the screen can say so. */
    suspend fun fail(recordingId: String, reason: String)

    /** The transfer is over — acked end to end, or the recording is gone from disk. */
    suspend fun remove(recordingId: String)
}

/**
 * The rows, in a JSON file next to the recordings.
 *
 * Not SQLDelight and not DataStore, and the reason is the shape of the data rather than taste: the
 * whole queue is a handful of rows that are always read together, always written together, and
 * never queried. A second database would mean a schema, a migration story and a driver on a device
 * that already carries one for the recordings; DataStore would mean a preferences codec for a list.
 * A file written the way `MetaWriter` writes `meta.json` — temp file, `atomicMove` — is the same
 * durability with none of that, and a corrupt one costs a rescan, not a recording ([reconcile]
 * finds every finalized recording on disk whatever the file says).
 *
 * [finalized] is how [reconcile] finds work: a lambda, not the `ReclyCore` it is read from, so the
 * whole of this runs on the JVM.
 */
class FileTransferQueue(
    private val fs: FileSystem,
    private val file: Path,
    private val logger: Logger,
    private val finalized: suspend () -> List<String>,
) : TransferStore {

    private val _pending = MutableStateFlow(0)
    override val pending: StateFlow<Int> = _pending.asStateFlow()

    private val _failed = MutableStateFlow(0)
    override val failed: StateFlow<Int> = _failed.asStateFlow()

    private val mutex = Mutex()

    /** Null until the file has been read once. Order is insertion order — oldest first. */
    private var rows: LinkedHashMap<String, TransferRow>? = null

    override suspend fun add(recordingId: String): Unit = mutate { rows ->
        if (rows.containsKey(recordingId)) return@mutate false
        rows[recordingId] = TransferRow(recordingId)
        logger.log(Logger.Level.INFO, "transfer.queue.add", mapOf("recordingId" to recordingId))
        true
    }

    override suspend fun reconcile() {
        // Outside the lock: it reads the database, and nothing here may hold the queue while it
        // does. `add` takes the lock per recording, which is where the idempotency lives anyway.
        val ids = finalized()
        mutex.withLock { load() }
        ids.forEach { add(it) }
    }

    override suspend fun all(): List<TransferRow> = mutex.withLock { load().values.toList() }

    override suspend fun acked(recordingId: String, part: PartRef): Unit = mutate { rows ->
        val row = rows[recordingId] ?: return@mutate false
        if (part in row.acked) return@mutate false
        rows[recordingId] = row.copy(acked = row.acked + part)
        true
    }

    override suspend fun unack(recordingId: String, parts: Collection<PartRef>): Unit = mutate { rows ->
        val row = rows[recordingId] ?: return@mutate false
        val kept = row.acked - parts.toSet()
        if (kept.size == row.acked.size) return@mutate false
        rows[recordingId] = row.copy(acked = kept)
        logger.log(
            Logger.Level.INFO,
            "transfer.queue.resend",
            mapOf("recordingId" to recordingId, "parts" to parts.size),
        )
        true
    }

    override suspend fun fail(recordingId: String, reason: String): Unit = mutate { rows ->
        val row = rows[recordingId] ?: return@mutate false
        if (row.failedReason == reason) return@mutate false
        rows[recordingId] = row.copy(failedReason = reason)
        logger.log(
            Logger.Level.ERROR,
            "transfer.queue.failed",
            mapOf("recordingId" to recordingId, "reason" to reason),
        )
        true
    }

    override suspend fun remove(recordingId: String): Unit = mutate { rows ->
        if (rows.remove(recordingId) == null) return@mutate false
        logger.log(Logger.Level.INFO, "transfer.queue.done", mapOf("recordingId" to recordingId))
        true
    }

    /**
     * Every change goes through here, so there is exactly one place that writes the file and
     * exactly one that republishes the counts. [change] returns false when it changed nothing —
     * an [add] of a recording already queued must not rewrite the file the phone is being sent
     * from.
     */
    private suspend fun mutate(change: (LinkedHashMap<String, TransferRow>) -> Boolean) = mutex.withLock {
        val rows = load()
        if (!change(rows)) return@withLock
        write(rows.values)
        publish(rows.values)
    }

    private fun publish(rows: Collection<TransferRow>) {
        _pending.value = rows.count { it.waiting }
        _failed.value = rows.count { !it.waiting }
    }

    private fun load(): LinkedHashMap<String, TransferRow> = rows ?: read().also {
        rows = it
        publish(it.values)
    }

    /**
     * A file that will not parse is a file this build cannot act on, and acting on half of it would
     * mean re-sending parts the phone already has or — worse — believing it has parts it does not.
     * Start empty; [reconcile] puts every finalized recording back, and the sender re-derives what
     * is acked from the part files that are still on disk.
     */
    private fun read(): LinkedHashMap<String, TransferRow> {
        val text = runCatching { if (fs.exists(file)) fs.read(file) { readUtf8() } else null }
            .onFailure { logger.log(Logger.Level.ERROR, "transfer.queue.unreadable", emptyMap(), it) }
            .getOrNull()
            ?: return LinkedHashMap()
        val parsed = runCatching { parse(text) }
            .onFailure { logger.log(Logger.Level.ERROR, "transfer.queue.corrupt", emptyMap(), it) }
            .getOrNull()
            ?: return LinkedHashMap()
        return parsed
    }

    private fun parse(text: String): LinkedHashMap<String, TransferRow> {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return LinkedHashMap()
        val rows = root[ROWS] as? JsonArray ?: return LinkedHashMap()
        val out = LinkedHashMap<String, TransferRow>()
        rows.forEach { element ->
            val entry = element as? JsonObject ?: return@forEach
            val id = entry.string(RECORDING_ID) ?: return@forEach
            out[id] = TransferRow(
                recordingId = id,
                acked = (entry[ACKED] as? JsonArray).orEmpty().mapNotNullTo(LinkedHashSet()) { ack ->
                    (ack as? JsonObject)?.partRef()
                },
                failedReason = entry.string(FAILED),
            )
        }
        return out
    }

    /** `MetaWriter.write`'s rule, for the same reason: never truncate the only copy of the truth. */
    private fun write(rows: Collection<TransferRow>) {
        val payload = json.encodeToString(
            buildJsonObject {
                putJsonArray(ROWS) {
                    rows.forEach { row ->
                        addJsonObject {
                            put(RECORDING_ID, row.recordingId)
                            putJsonArray(ACKED) {
                                row.acked.forEach { ref ->
                                    addJsonObject {
                                        put(PART, ref.part)
                                        put(TRACK, ref.track.wire)
                                    }
                                }
                            }
                            row.failedReason?.let { put(FAILED, it) }
                        }
                    }
                }
            },
        )
        file.parent?.let { fs.createDirectories(it) }
        val temp = "$file.tmp".toPath()
        fs.write(temp) { writeUtf8(payload) }
        fs.atomicMove(temp, file)
    }

    private fun JsonObject.partRef(): PartRef? {
        val part = (this[PART] as? JsonPrimitive)?.intOrNull ?: return null
        val wire = string(TRACK) ?: return null
        return PartRef(part, Track.entries.firstOrNull { it.wire == wire } ?: return null)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

    companion object {
        /** Next to `recordings/`, under the core's data directory. */
        const val FILE_NAME: String = "transfer-queue.json"

        private val json = Json

        private const val ROWS = "rows"
        private const val RECORDING_ID = "recordingId"
        private const val ACKED = "acked"
        private const val FAILED = "failed"
        private const val PART = "part"
        private const val TRACK = "track"
    }
}
