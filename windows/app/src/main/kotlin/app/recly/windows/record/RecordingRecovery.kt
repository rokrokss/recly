@file:OptIn(ExperimentalTime::class)

package app.recly.windows.record

import kotlin.time.ExperimentalTime
import kotlinx.coroutines.withContext
import recly.core.ReclyCore
import recly.core.job.EnqueueResult
import recly.core.model.RecordingStatus
import recly.core.platform.Logger
import recly.core.recording.MetaWriter
import recly.core.recording.RecordingRecord

/**
 * docs/03 "복구", at the app level because on Windows the app is not the process that writes the
 * audio — the helper is (docs/14). The phone's `RecordingRecovery` and the Mac's can re-read a
 * segment they find on disk; this one cannot, and that difference is the whole of the rule below.
 *
 * At launch, before the tray offers to record:
 *
 * - A recording whose row still says `recording` was interrupted — the helper was killed, or the
 *   app was. Every part the helper reported through `part_done` is already registered and already
 *   in `meta.json`, except one whose row the database refused: that one left a [PartMarker] beside
 *   its audio and is registered from it here, because everything about it is known. A marker that
 *   cannot be read or cannot be filed leaves the recording open for the next launch — nothing is
 *   finalized over a part that is still owed, and none of its audio is quarantined.
 * - A `.m4a` in the directory that is *not* registered never got a `part_done`, so nothing knows
 *   its duration or its hash, and no JVM here can work them out. It is quarantined as
 *   `<file>.corrupt` — never registered (it would upload as audio of an unknown length) — beside
 *   the parts that were.
 * - A recording with no registered part at all has nothing to send and nothing the app can do
 *   with it, whatever else is in the directory (a quarantined tail, a helper temp file, a
 *   half-written meta): a row that stays `recording` for good is one the user can do nothing
 *   with (2026-09-04 decision, docs/03), so the row and the directory are dropped.
 * - What is left is finalized through the last registered part, which is exactly what
 *   [WindowsRecorder] would have written had the stop happened normally, and queued if the crash
 *   came before the job existed.
 * - A recording that *is* finalized but has no job at all was interrupted between the stop and the
 *   title prompt's answer (see `completeRecording`). It is queued now.
 */
class RecordingRecovery(private val core: ReclyCore) {

    /** How many recordings this pass moved on. */
    suspend fun reconcile(): Int {
        var recovered = 0
        core.recordings.list(LIMIT).forEach { record ->
            val moved = when (record.meta.status) {
                RecordingStatus.RECORDING -> recoverOpen(record)
                RecordingStatus.FINALIZED -> enqueueIfNoJob(record)
                // The watch's, and this shell has no watch (ADR-002).
                RecordingStatus.TRANSFERRED -> false
            }
            if (moved) recovered++
        }
        return recovered
    }

    private suspend fun recoverOpen(open: RecordingRecord): Boolean {
        // Markers first: a marked part is audio this app already knows the duration and hash of, so
        // it belongs in the meta — and once it is registered its file is not an unknown segment.
        val marked = registerMarked(open)
        val record = core.recordings.get(open.id) ?: return false
        if (marked.unresolved > 0) {
            // A marker that could not be read or could not be filed is a part this recording still
            // owes: finalizing would publish a meta that is missing it, and quarantining would
            // condemn audio that is not corrupt at all. The row stays `recording` and the next
            // launch tries again.
            log("rec.recovered.markerPending", record, mapOf("pending" to marked.unresolved))
            return false
        }
        val scan = quarantine(record)
        if (record.meta.parts.isEmpty()) {
            // Nothing the app can play or send: a start that failed, a helper that died inside its
            // first segment (quarantined above), a stray helper file. The row and the directory go,
            // whatever was in it — the counts say what that was.
            core.recordings.delete(record.id)
            log("rec.recovered.empty", record, mapOf("corrupt" to scan.corrupt, "unknown" to scan.unknown))
            return true
        }
        // The same arithmetic the recorder uses: the tracks cover the same time, so the end of the
        // furthest part is the length — not the sum of all of them.
        val durationSec = record.meta.parts.maxOf { it.startOffsetSec + it.durationSec }
        val finalized = core.recordings.finalize(
            recordingId = record.id,
            endedAt = core.deps.clock.now(),
            durationSec = durationSec,
        )
        log(
            "rec.recovered",
            record,
            mapOf(
                "parts" to finalized.meta.parts.size,
                "durationSec" to durationSec,
                "marked" to marked.registered,
                "corrupt" to scan.corrupt,
            ),
        )
        enqueueIfNoJob(finalized)
        return true
    }

    /** What the markers in one directory came to: filed, and still owed. */
    private data class Marked(val registered: Int, val unresolved: Int)

    /** Files every [PartMarker] in the directory and clears the ones that went in. */
    private suspend fun registerMarked(record: RecordingRecord): Marked {
        val fileSystem = core.deps.fileSystem
        val markers = withContext(core.deps.io) {
            if (fileSystem.exists(record.dir)) {
                fileSystem.list(record.dir).filter { it.name.endsWith(PartMarker.SUFFIX) }
            } else {
                emptyList()
            }
        }
        var registered = 0
        var unresolved = 0
        markers.forEach { path ->
            val part = withContext(core.deps.io) { PartMarker.read(fileSystem, path) }
            if (part == null) {
                // Left where it is: an unreadable marker is not something to guess at, and the part
                // it stands for is still owed — see the caller.
                unresolved++
                core.deps.logger.log(
                    Logger.Level.WARN,
                    "rec.part.markerUnreadable",
                    mapOf("recordingId" to record.id, "file" to path.name),
                )
                return@forEach
            }
            val filed = runCatching { core.recordings.addPart(record.id, part) }.isSuccess
            if (!filed) {
                unresolved++
                core.deps.logger.log(
                    Logger.Level.ERROR,
                    "rec.part.markerFailed",
                    mapOf("recordingId" to record.id, "part" to part.part),
                )
                return@forEach
            }
            withContext(core.deps.io) { fileSystem.delete(path, mustExist = false) }
            registered++
            core.deps.logger.log(
                Logger.Level.INFO,
                "rec.part.recovered",
                mapOf("recordingId" to record.id, "part" to part.part, "track" to part.track.name),
            )
        }
        return Marked(registered = registered, unresolved = unresolved)
    }

    /** What one directory turned out to hold, once the unregistered segments were moved aside. */
    private data class Scan(val corrupt: Int, val unknown: Int)

    private suspend fun quarantine(record: RecordingRecord): Scan = withContext(core.deps.io) {
        val fileSystem = core.deps.fileSystem
        if (!fileSystem.exists(record.dir)) return@withContext Scan(corrupt = 0, unknown = 0)
        val registered = record.meta.parts.map { it.file }.toSet()
        val meta = MetaWriter.metaFileName(MetaWriter.baseName(record.meta))
        var corrupt = 0
        var unknown = 0
        fileSystem.list(record.dir).forEach { path ->
            val name = path.name
            when {
                name.endsWith(CORRUPT_SUFFIX) -> corrupt++
                // A marker `registerMarked` could not read. It is expected, and it is not audio.
                name.endsWith(PartMarker.SUFFIX) -> Unit
                name == meta -> Unit
                name in registered -> Unit

                name.endsWith(".m4a") -> {
                    // No `part_done` ever arrived for it, so nothing knows its duration or its hash
                    // and no JVM here can work them out: aside, never registered, never deleted.
                    val quarantined = record.dir / "$name$CORRUPT_SUFFIX"
                    fileSystem.atomicMove(path, quarantined)
                    corrupt++
                    core.deps.logger.log(
                        Logger.Level.WARN,
                        "rec.part.corrupt",
                        mapOf("recordingId" to record.id, "file" to name),
                    )
                }

                // A helper temp file, a half-written meta — something this pass has no rule for.
                else -> unknown++
            }
        }
        Scan(corrupt = corrupt, unknown = unknown)
    }

    /** A finalized recording with no job at all — the crash landed inside the title prompt. */
    private suspend fun enqueueIfNoJob(record: RecordingRecord): Boolean {
        if (core.recordings.jobStatuses(record.id).isNotEmpty()) return false
        val result = core.enqueue(record.id)
        log("rec.recovered.enqueue", record, mapOf("result" to result::class.simpleName))
        return result is EnqueueResult.Enqueued
    }

    private fun log(event: String, record: RecordingRecord, extra: Map<String, Any?> = emptyMap()) {
        core.deps.logger.log(
            Logger.Level.INFO,
            event,
            mapOf("recordingId" to record.id, "base" to MetaWriter.baseName(record.meta)) + extra,
        )
    }

    private companion object {
        /** Deep enough that a machine left off for a while still has its interrupted recording. */
        const val LIMIT = 100

        const val CORRUPT_SUFFIX = ".corrupt"
    }
}
