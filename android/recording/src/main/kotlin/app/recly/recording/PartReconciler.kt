@file:OptIn(ExperimentalTime::class)

package app.recly.recording

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.withContext
import okio.Path
import recly.core.ReclyCore
import recly.core.model.Part
import recly.core.model.Range
import recly.core.model.Track
import recly.core.platform.Logger
import recly.core.recording.PartHasher
import recly.core.recording.RecordingRecord

/** What one pass over a recording's directory found. */
internal data class Reconciled(
    /** Segment files with audio in them, registered or not. */
    val files: Int,
    /** Parts this pass filed for the first time. */
    val registered: Int,
    /** Parts whose audio is on disk and whose row still is not — the `.pending` markers left. */
    val pending: Int,
    val durationSec: Double,
    val endedAtMs: Long,
)

/** The result of the core-only half of a stop. */
sealed interface StopResult {
    /** A second stop: the button and the notification action can both land. */
    data object NotRecording : StopResult

    data class Finalized(val outcome: RecordingOutcome) : StopResult

    /**
     * Audio is on disk that could not be filed, so the meta is deliberately left open: a row that
     * says `finalized` is a row nothing goes looking at again, and the missing part would be
     * uploaded away. The next [RecordingRecovery] pass finishes the job.
     */
    data class Deferred(val recordingId: String, val pending: Int) : StopResult
}

/**
 * The directory is the truth about what was recorded; the rows are only what the app managed to
 * write down. This reconciles the two — and it is one class because the stop path and the recovery
 * path must agree exactly: whatever a stop refuses to lose, a later recovery has to find.
 */
internal class PartReconciler(
    private val core: ReclyCore,
    private val duration: DurationProbe = MediaDuration,
) {

    /**
     * Files the encoder left, in part order: registers the ones no row knows about, deletes the
     * empty arming, and clears the marker of every part that made it in.
     *
     * A registration that fails again leaves (or re-writes) its marker and the walk continues —
     * the offset still advances past it, because the audio is there whether or not the row is.
     */
    suspend fun reconcile(recordingId: String): Reconciled? {
        val record = core.recordings.get(recordingId) ?: return null
        val known = record.meta.parts.associateBy { it.file }
        var offsetSec = 0.0
        var endedAtMs = 0L
        var files = 0
        var registered = 0
        var failed = 0

        for (path in segmentFiles(record.dir)) {
            val metadata = withContext(core.deps.io) { core.deps.fileSystem.metadataOrNull(path) }
            val bytes = metadata?.size ?: 0L
            if (bytes <= 0L) {
                // The arming of a segment that never started, or a file the encoder never filled.
                withContext(core.deps.io) { core.deps.fileSystem.delete(path, mustExist = false) }
                continue
            }
            val existing = known[path.name]
            // Only a file no row knows about is a candidate for filing — and so for quarantine.
            val number = if (existing == null) partNumber(path.name) else null
            val durationSec = if (number != null) duration.seconds(path) else null
            if (number != null && durationSec == null) {
                // A tail the process died in the middle of: no moov, so no honest length. Filing it
                // with a length guessed from the bitrate would upload audio nothing can decode, so
                // it is set aside instead (docs/03) and counts as neither a file nor a pending part.
                quarantine(recordingId, record.dir, path, bytes)
                continue
            }

            endedAtMs = maxOf(endedAtMs, metadata?.lastModifiedAtMillis ?: 0L)
            files++

            if (existing != null) {
                offsetSec += existing.durationSec
                clearMarker(record.dir, path.name)
                continue
            }
            // A segment file whose name carries no part number: nothing this walk can file.
            if (number == null || durationSec == null) continue
            try {
                core.recordings.addPart(
                    recordingId,
                    Part(
                        part = number,
                        track = Track.MONO,
                        file = path.name,
                        bytes = bytes,
                        sha256 = withContext(core.deps.io) { PartHasher.sha256(core.deps.fileSystem, path) },
                        startOffsetSec = offsetSec,
                        durationSec = durationSec,
                    ),
                )
                registered++
                clearMarker(record.dir, path.name)
            } catch (e: Exception) {
                failed++
                // The marker is a hint for the next pass; if the storage that refused the row also
                // refuses the marker, the count below still carries the failure.
                runCatching { markPending(record.dir, path.name) }
                core.deps.logger.log(
                    Logger.Level.WARN,
                    "rec.part.pending",
                    mapOf("recordingId" to recordingId, "file" to path.name),
                    e,
                )
            }
            offsetSec += durationSec
        }

        return Reconciled(
            files = files,
            registered = registered,
            pending = maxOf(markers(record.dir).size, failed),
            durationSec = offsetSec,
            endedAtMs = endedAtMs,
        )
    }

    /**
     * The half of a stop that only touches the core, kept out of [SegmentedRecorder] so it can be
     * tested without a microphone: settle every part that is on disk, then close the meta — or
     * refuse to, and say so.
     */
    suspend fun closeOut(
        recordingId: String,
        ledgerSec: Double,
        title: String?,
        silenced: List<Range>,
    ): StopResult {
        val record = core.recordings.get(recordingId) ?: return StopResult.NotRecording
        // Always walk the directory: a marker is only a hint. A boundary whose `addPart` failed for
        // a storage reason may have failed to write its marker for the same reason, and then the
        // file itself is the only record of that audio. Files already filed are not re-hashed.
        val reconciled = reconcile(recordingId) ?: return StopResult.NotRecording
        if (reconciled.pending > 0) {
            core.deps.logger.log(
                Logger.Level.ERROR,
                "rec.recorder.stopDeferred",
                mapOf(
                    "recordingId" to recordingId,
                    "pending" to reconciled.pending,
                    // Not written anywhere yet: `finalize` is what carries them, and it is not running.
                    "silenced" to silenced.size,
                ),
            )
            return StopResult.Deferred(recordingId, reconciled.pending)
        }
        val finalized = core.recordings.finalize(
            recordingId = recordingId,
            endedAt = core.deps.clock.now(),
            // What is on disk is the truth; the ledger only backs an empty directory.
            durationSec = if (reconciled.files > 0) reconciled.durationSec else ledgerSec,
            title = title,
            silenced = silenced,
        )
        return StopResult.Finalized(
            RecordingOutcome(
                recordingId = recordingId,
                durationSec = finalized.meta.durationSec ?: ledgerSec,
                parts = finalized.meta.parts.size,
                silenced = silenced,
            ),
        )
    }

    /** Brings an already-finalized recording's meta up to date after a late part joined it. */
    suspend fun refinalize(record: RecordingRecord, reconciled: Reconciled) {
        val known = record.meta.endedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val fromFiles = Instant.fromEpochMilliseconds(reconciled.endedAtMs)
        core.recordings.finalize(
            recordingId = record.id,
            endedAt = if (known != null && known > fromFiles) known else fromFiles,
            durationSec = reconciled.durationSec,
            title = null,
        )
    }

    /** Markers whose segment still exists. An orphan (segment gone — quarantined or purged) is
     *  deleted on sight so it can never hold a stop or a recovery open. */
    suspend fun markers(dir: Path): List<Path> = withContext(core.deps.io) {
        val fs = core.deps.fileSystem
        fs.listOrNull(dir).orEmpty()
            .filter { it.name.endsWith(SegmentedRecorder.PENDING_SUFFIX) }
            .filter { marker ->
                val segment = dir / marker.name.removeSuffix(SegmentedRecorder.PENDING_SUFFIX)
                fs.exists(segment).also { if (!it) fs.delete(marker, mustExist = false) }
            }
    }

    private suspend fun segmentFiles(dir: Path): List<Path> = withContext(core.deps.io) {
        core.deps.fileSystem.listOrNull(dir).orEmpty()
            .filter { it.name.endsWith(SEGMENT_SUFFIX) }
            .sortedBy { it.name }
    }

    /**
     * Sets an unreadable segment aside under [CORRUPT_SUFFIX], out of the way of every later walk:
     * the audio is kept for a support ticket, but nothing registers it and nothing uploads it. Its
     * marker goes too — there is no part left for a later pass to file.
     */
    private suspend fun quarantine(recordingId: String, dir: Path, path: Path, bytes: Long) {
        // Marker first, then the move: a death in between leaves the segment where the next walk
        // finds and quarantines it again, whereas the other order strands an orphan marker.
        clearMarker(dir, path.name)
        withContext(core.deps.io) {
            core.deps.fileSystem.atomicMove(path, dir / "${path.name}$CORRUPT_SUFFIX")
        }
        core.deps.logger.log(
            Logger.Level.WARN,
            "rec.part.corrupt",
            mapOf("recordingId" to recordingId, "file" to path.name, "bytes" to bytes),
        )
    }

    private suspend fun clearMarker(dir: Path, file: String) {
        withContext(core.deps.io) {
            core.deps.fileSystem.delete(dir / "$file${SegmentedRecorder.PENDING_SUFFIX}", mustExist = false)
        }
    }

    private suspend fun markPending(dir: Path, file: String) {
        withContext(core.deps.io) {
            core.deps.fileSystem.write(dir / "$file${SegmentedRecorder.PENDING_SUFFIX}") { writeUtf8("") }
        }
    }

    internal companion object {
        private const val SEGMENT_SUFFIX = "_mono.m4a"

        /** Appended, so a quarantined file no longer looks like a segment to any later walk. */
        internal const val CORRUPT_SUFFIX = ".corrupt"
        private val PART_NUMBER = Regex("_p(\\d+)_mono\\.m4a$")

        internal fun partNumber(file: String): Int? =
            PART_NUMBER.find(file)?.groupValues?.get(1)?.toIntOrNull()
    }
}
