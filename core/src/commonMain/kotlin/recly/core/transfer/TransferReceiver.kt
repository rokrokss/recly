@file:OptIn(ExperimentalTime::class)

package recly.core.transfer

import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okio.Path
import recly.core.db.RecDatabase
import recly.core.ids.Ulid
import recly.core.model.AudioSettings
import recly.core.model.Codec
import recly.core.model.Container
import recly.core.model.Part
import recly.core.model.RecordingMeta
import recly.core.model.RecordingStatus
import recly.core.model.Source
import recly.core.model.Track
import recly.core.model.isoUtc
import recly.core.model.recJson
import recly.core.platform.CoreDeps
import recly.core.platform.Logger
import recly.core.recording.MetaWriter
import recly.core.recording.PartHasher
import recly.core.recording.RecordingRepository

/** One queued file on the watch side. The queue itself is shell code (Data Layer / WCSession). */
data class TransferItem(
    val recordingId: String,
    val part: Int,
    val track: Track,
    val path: Path,
    val sha256: String,
)

/** `{recordingId, part, track, ok}` — what the watch waits for before it deletes its copy. */
data class Ack(val ok: Boolean, val reason: String? = null)

sealed interface AcceptMetaResult {
    /** Every part listed in the meta is on disk and hashes as promised; enqueue can run. */
    data class Complete(val recordingId: String) : AcceptMetaResult

    /** [missingParts] have not arrived (or arrived corrupt); the watch resends and tries again. */
    data class Incomplete(val missingParts: List<Part>) : AcceptMetaResult

    data class Invalid(val reason: String) : AcceptMetaResult
}

/**
 * The phone half of docs/03 "워치 → 폰 전송 계약". Parts arrive one at a time and `meta.json` last,
 * so the receiver has to hold an unfinished recording open, verify each part against the sha256
 * the watch computed, and only declare the recording finalized once the meta agrees with what is
 * on disk.
 *
 * The directory is named after the recordingId, not the docs/03 `{base}`: the base is derived from
 * the meta, and the meta is the last thing to arrive.
 */
class TransferReceiver(
    private val db: RecDatabase,
    private val recordings: RecordingRepository,
    private val deps: CoreDeps,
) {
    private val queries get() = db.recQueries
    private val mutex = Mutex()

    /**
     * Verifies and files one part. [tmpPath] is wherever the platform transfer API dropped the
     * bytes; its file name is the watch's and is kept, because the meta will refer to it by that
     * name. A part that is sent twice overwrites itself — the ack may have been lost, and the watch
     * is right to resend.
     */
    suspend fun acceptPart(
        recordingId: String,
        part: Int,
        track: Track,
        sha256Claimed: String,
        tmpPath: Path,
    ): Ack = locked {
        val fs = deps.fileSystem
        val actual = PartHasher.sha256(fs, tmpPath)
        if (!actual.equals(sha256Claimed, ignoreCase = true)) {
            // Nothing good can come of keeping it: the watch still has the original.
            fs.delete(tmpPath, mustExist = false)
            deps.logger.log(
                Logger.Level.WARN,
                "transfer.nack",
                mapOf("recordingId" to recordingId, "part" to part, "reason" to SHA_MISMATCH),
            )
            return@locked Ack(ok = false, reason = SHA_MISMATCH)
        }

        val now = deps.clock.now()
        val existing = recordings.get(recordingId)
        val dir = existing?.dir ?: (deps.dataDir / RECORDINGS / recordingId)
        fs.createDirectories(dir)
        val name = tmpPath.name
        fs.atomicMove(tmpPath, dir / name)

        val bytes = fs.metadata(dir / name).size ?: 0L
        val received = Part(
            part = part,
            track = track,
            file = name,
            bytes = bytes,
            sha256 = actual,
            startOffsetSec = 0.0,
            durationSec = 0.0,
        )
        val meta = existing?.meta ?: placeholder(recordingId, now)
        val parts = meta.parts.filterNot { it.part == part && it.track == track } + received
        recordings.receive(
            meta.copy(parts = parts.sortedWith(compareBy({ it.part }, { it.track.ordinal }))),
            dir,
        )
        // The 24 h orphan clock starts at the first part and is also what marks this row as one the
        // receiver opened, so the purge can never touch a recording this device is making itself.
        if (existing == null) queries.kvSet(pendingKey(recordingId), now.isoUtc())
        deps.logger.log(
            Logger.Level.INFO,
            "transfer.ack",
            mapOf("recordingId" to recordingId, "part" to part, "track" to track.name),
        )
        Ack(ok = true)
    }

    /**
     * The last thing the watch sends. Nothing is finalized until every part the meta lists is on
     * disk with the hash the meta claims — a recording that says it has five parts and has four is
     * not a recording, it is a transfer that is still going.
     */
    suspend fun acceptMeta(json: String): AcceptMetaResult = locked {
        val meta = try {
            recJson.decodeFromString<RecordingMeta>(json)
        } catch (e: SerializationException) {
            return@locked AcceptMetaResult.Invalid("malformed meta: ${e.message}")
        }
        val existing = recordings.get(meta.recordingId)
        val dir = existing?.dir ?: (deps.dataDir / RECORDINGS / meta.recordingId)
        val received = existing?.meta?.parts.orEmpty()
        val missing = meta.parts.filterNot { wanted -> arrived(wanted, received, dir) }
        if (missing.isNotEmpty()) {
            deps.logger.log(
                Logger.Level.INFO,
                "transfer.incomplete",
                mapOf("recordingId" to meta.recordingId, "missing" to missing.size),
            )
            return@locked AcceptMetaResult.Incomplete(missing)
        }
        val finalized = meta.copy(status = RecordingStatus.FINALIZED)
        recordings.receive(finalized, dir)
        MetaWriter.write(deps.fileSystem, dir, finalized)
        queries.kvDelete(pendingKey(meta.recordingId))
        deps.logger.log(
            Logger.Level.INFO,
            "transfer.complete",
            mapOf("recordingId" to meta.recordingId, "parts" to meta.parts.size),
        )
        AcceptMetaResult.Complete(meta.recordingId)
    }

    /**
     * docs/03: parts without a meta after 24 hours are rubbish — the watch gave up, or was reset.
     * Only rows this receiver opened and that never got their meta are candidates.
     */
    suspend fun purgeOrphans(now: Instant): List<String> = locked {
        val purged = mutableListOf<String>()
        for (row in queries.kvSelectPrefix(PENDING_PREFIX).executeAsList()) {
            val since = runCatching { Instant.parse(row.value_) }.getOrNull()
            if (since != null && now - since < ORPHAN_AFTER) continue
            val recordingId = row.key.removePrefix(PENDING_PREFIX)
            val record = recordings.get(recordingId)
            if (record == null || record.meta.status == RecordingStatus.RECORDING) {
                recordings.delete(recordingId)
                purged += recordingId
                deps.logger.log(Logger.Level.INFO, "transfer.orphan", mapOf("recordingId" to recordingId))
            }
            queries.kvDelete(row.key)
        }
        purged
    }

    /**
     * A part counts as arrived only if [acceptPart] hashed it to what the meta claims *and* the
     * file is still there — a hash we recorded is not evidence about the disk right now.
     */
    private fun arrived(wanted: Part, received: List<Part>, dir: Path): Boolean =
        received.any {
            it.part == wanted.part &&
                it.track == wanted.track &&
                it.sha256.equals(wanted.sha256, ignoreCase = true)
        } &&
            deps.fileSystem.exists(dir / wanted.file)

    /**
     * Stands in until the real meta lands. It never reaches the disk and every field of it is
     * replaced by [acceptMeta]; what matters is that the row exists so the parts have somewhere to
     * belong and the purge has something to find.
     *
     * [startedAt] is the one field the list reads before then, and it is the watch's, not this
     * moment's: the id the watch made carries the millisecond it started (docs/01 "식별자·시간"), so
     * a 20-minute recording handed over at its end sits where it belongs among the rows instead of
     * at the top. [now] is only the fallback for an id that is not a ULID.
     */
    private fun placeholder(recordingId: String, now: Instant): RecordingMeta = RecordingMeta(
        schema = 1,
        recordingId = recordingId,
        source = Source.WATCH,
        platform = deps.device.platform,
        deviceId = deps.device.deviceId,
        deviceName = deps.device.name,
        startedAt = (Ulid.timestamp(recordingId) ?: now).isoUtc(),
        timezone = "UTC",
        audio = AudioSettings(Codec.AAC_LC, Container.M4A, 16_000, 1, 32, 900),
        tracks = emptyList(),
        parts = emptyList(),
        status = RecordingStatus.RECORDING,
    )

    private fun pendingKey(recordingId: String): String = "$PENDING_PREFIX$recordingId"

    /** The repository takes its own lock; this one only orders whole transfer operations. */
    private suspend fun <T> locked(body: suspend () -> T): T =
        withContext(deps.io) { mutex.withLock { body() } }

    private companion object {
        const val RECORDINGS = "recordings"
        const val PENDING_PREFIX = "transfer.pending."
        const val SHA_MISMATCH = "SHA256_MISMATCH"
        val ORPHAN_AFTER = 24.hours
    }
}
