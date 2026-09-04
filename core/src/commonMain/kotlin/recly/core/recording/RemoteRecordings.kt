@file:OptIn(ExperimentalTime::class)

package recly.core.recording

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.jsonObject
import recly.core.drive.DriveApi
import recly.core.drive.DriveNotFound
import recly.core.drive.string
import recly.core.model.RecordingMeta
import recly.core.model.RecordingStatus
import recly.core.model.Track
import recly.core.model.recJson
import recly.core.platform.AuthRequiredException
import recly.core.platform.CoreDeps
import recly.core.platform.Logger

/** What one [RemoteRecordings.pull] did. [skipped] is a pull that did not run — see the reasons. */
data class PullSummary(
    val adopted: Int = 0,
    val dropped: Int = 0,
    /** Rows whose title changed to what Drive says (docs/03 "제목"). */
    val retitled: Int = 0,
    /** Why nothing was pulled: `throttled`, `busy`, `auth` (no account), or the error's message. */
    val skipped: String? = null,
)

/**
 * docs/03 "다른 기기의 녹음": Drive is the shared list. Every device uploads to a `{base}/` folder
 * stamped with the recording's id (ADR-014), so one listing of those folders is every recording
 * the account has, whichever device made it. What this device does not have a row for is read
 * back — the folder's `meta.json`, and the id of each part file — and adopted as a row of its own
 * with no local audio and no job ([RecordingRepository.adopt]). What it adopted earlier and Drive
 * no longer lists was deleted elsewhere, and goes.
 *
 * Nothing this device made is touched: its own rows are never dropped by a listing, and a
 * recording is only adopted when no row of that id exists. A recording the user deleted here while
 * keeping its folder ("로컬만 삭제") stays deleted: the folder is remembered
 * ([RecordingRepository.ignored]) until Drive stops listing it. A folder that has no `meta.json`
 * yet is an upload still in progress (the meta goes up last, docs/03) and is left for the next
 * pull. Two folders with the same id — a re-run into another path — resolve to the newest one that
 * is complete: an adopted row moves to a newer folder once that one completes, and to whatever is
 * left when its own folder goes.
 *
 * The same listing carries every folder's `description`, which is the recording's title on Drive:
 * a title renamed on another device is applied here from it, and one renamed here is pushed there
 * ([pushTitles], docs/03 "제목").
 *
 * One pull at a time, and no more often than [MIN_INTERVAL] unless forced: every job pass calls it
 * (`ReclyCore.runDueJobs`), and the desktop passes come every few minutes.
 */
class RemoteRecordings(
    private val api: DriveApi,
    private val recordings: RecordingRepository,
    private val deps: CoreDeps,
) {
    private val mutex = Mutex()
    private var lastPulledAt: Instant? = null

    /** One push at a time: two renames in a row must reach Drive in that order, not the other. */
    private val pushing = Mutex()

    /**
     * Never throws: a pull is a background courtesy, and what stopped it is in the summary and the
     * log. No account is the ordinary case on a device that has not signed in, and is logged at
     * `INFO` rather than as a failure.
     */
    suspend fun pull(force: Boolean = false): PullSummary {
        if (!mutex.tryLock()) return PullSummary(skipped = "busy")
        try {
            val now = deps.clock.now()
            val last = lastPulledAt
            if (!force && last != null && now - last < MIN_INTERVAL) return PullSummary(skipped = "throttled")
            val summary = try {
                sync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthRequiredException) {
                deps.logger.log(Logger.Level.INFO, "remote.pull.skipped", mapOf("reason" to "auth"))
                return PullSummary(skipped = "auth")
            } catch (e: Throwable) {
                deps.logger.log(Logger.Level.WARN, "remote.pull.failed", emptyMap(), e)
                return PullSummary(skipped = e.message ?: e::class.simpleName ?: "failed")
            }
            lastPulledAt = deps.clock.now()
            deps.logger.log(
                Logger.Level.INFO,
                "remote.pull",
                mapOf("adopted" to summary.adopted, "dropped" to summary.dropped, "retitled" to summary.retitled),
            )
            return summary
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Carries the titles renamed on this device to Drive: the folder's `description`, and the
     * `meta.json` in it. A recording whose folder is not known yet — not uploaded — waits; so does
     * one whose folder has no `meta.json` yet (an upload in flight may still write the old title
     * into it, and the pass after it corrects that). A rename is pending until both landed.
     * Never throws: what did not land stays pending for the next pass.
     */
    suspend fun pushTitles(): Unit = pushing.withLock {
        for ((recordingId, title) in recordings.pendingTitles()) {
            val record = recordings.get(recordingId) ?: continue
            val folderId = record.driveFolderId ?: continue
            try {
                api.updateDescription(folderId, title)
                val name = MetaWriter.metaFileName(MetaWriter.baseName(record.meta))
                val metaFile = api.findChild(folderId, name) ?: continue
                api.updateMedia(metaFile.id, recJson.encodeToString(record.meta).encodeToByteArray(), META_MIME)
                recordings.titlePushed(recordingId, title)
                deps.logger.log(Logger.Level.INFO, "remote.title.pushed", mapOf("recordingId" to recordingId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: DriveNotFound) {
                // The folder went — deleted elsewhere; nothing to tell, and the pull will drop or
                // keep the row on its own terms.
                recordings.titlePushed(recordingId, title)
            } catch (e: AuthRequiredException) {
                return
            } catch (e: Throwable) {
                deps.logger.log(Logger.Level.WARN, "remote.title.push.failed", mapOf("recordingId" to recordingId), e)
                return
            }
        }
    }

    private suspend fun sync(): PullSummary {
        // Newest folder first, so a re-run's folder is the one an id resolves to.
        val folders = api.recordingFolders()
            .mapNotNull { json ->
                val id = json["appProperties"]?.jsonObject?.string("recordingId") ?: return@mapNotNull null
                Folder(
                    recordingId = id,
                    id = json.string("id") ?: return@mapNotNull null,
                    createdTime = json.string("createdTime").orEmpty(),
                    description = json.string("description"),
                )
            }
            .sortedByDescending { it.createdTime }
            .groupBy { it.recordingId }
        val listed = folders.values.flatten().map { it.id }.toSet()

        // Adopted earlier from a folder Drive no longer lists: deleted on another device, by the
        // user in Drive, or replaced by a re-run. The listing above ran to its last page, or it
        // would have thrown — a partial answer never reaches this. Dropped before adopting, so a
        // recording whose id is still there under another folder is taken up again from that one.
        var dropped = 0
        for ((recordingId, folderId) in recordings.adopted()) {
            if (folderId in listed) continue
            if (recordings.drop(recordingId, folderId)) dropped++
        }

        // A "로컬만 삭제" is kept for as long as the folder it kept is listed.
        val ignored = recordings.ignored()
        for ((recordingId, folderId) in ignored) {
            if (folderId !in listed) recordings.unignore(recordingId)
        }

        val adoptedRows = recordings.adopted()
        val known = recordings.ids()
        var adopted = 0
        for ((recordingId, candidates) in folders) {
            val current = adoptedRows[recordingId]
            if (current != null) {
                // Newer folders than the one the row came from: a re-run that has completed since.
                val newer = candidates.takeWhile { it.id != current }
                val replacement = newer.firstNotNullOfOrNull { read(recordingId, it) } ?: continue
                if (recordings.drop(recordingId, current)) {
                    dropped++
                    if (recordings.adopt(replacement.meta, replacement.folder.id, replacement.fileIds)) adopted++
                }
                continue
            }
            if (recordingId in known || ignored[recordingId] in listed) continue
            val found = candidates.firstNotNullOfOrNull { read(recordingId, it) } ?: continue
            if (recordings.adopt(found.meta, found.folder.id, found.fileIds)) {
                adopted++
                deps.logger.log(
                    Logger.Level.INFO,
                    "remote.adopt",
                    mapOf("recordingId" to recordingId, "folderId" to found.folder.id, "parts" to found.fileIds.size),
                )
            }
        }

        // Titles: the folder's description is what every device reads, so what it says is applied
        // to the row here — unless a rename of this device's own is still waiting to go up. The
        // row's own folder when it knows one; the newest otherwise (a recording uploaded before the
        // row learned to keep its folder id).
        var retitled = 0
        // A row of this device's own that predates the folder column learns its folder from the
        // listing, so that a rename of it has somewhere to go.
        for (recordingId in known) {
            val newest = folders[recordingId]?.first() ?: continue
            recordings.rememberFolder(recordingId, newest.id)
        }
        val own = recordings.driveFolders()
        for ((recordingId, candidates) in folders) {
            val folder = candidates.firstOrNull { it.id == own[recordingId] } ?: candidates.first()
            val title = folder.description?.takeIf { it.isNotBlank() } ?: continue
            if (recordings.applyTitle(recordingId, title)) retitled++
        }
        pushTitles()
        return PullSummary(adopted, dropped, retitled)
    }

    /**
     * The folder read back: its `meta.json` and the id of each part file, or null when it is not
     * a complete, well-formed recording of this id.
     */
    private suspend fun read(recordingId: String, folder: Folder): Adoptable? {
        val children = try {
            api.children(folder.id)
        } catch (e: DriveNotFound) {
            return null
        }
        val metaFile = children.firstOrNull { it.name.endsWith(META_SUFFIX) } ?: return null
        val meta = try {
            recJson.decodeFromString<RecordingMeta>(api.download(metaFile.id).decodeToString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveNotFound) {
            return null
        } catch (e: Throwable) {
            deps.logger.log(
                Logger.Level.WARN,
                "remote.meta.unreadable",
                mapOf("recordingId" to recordingId, "folderId" to folder.id),
                e,
            )
            return null
        }
        if (!wellFormed(meta, recordingId)) {
            deps.logger.log(
                Logger.Level.WARN,
                "remote.meta.mismatch",
                mapOf("recordingId" to recordingId, "folderId" to folder.id, "metaId" to meta.recordingId),
            )
            return null
        }
        val byName = children.associate { it.name to it.id }
        val fileIds = meta.parts.mapNotNull { part -> byName[part.file]?.let { (part.part to part.track) to it } }.toMap()
        return Adoptable(folder, meta, fileIds)
    }

    /**
     * The meta names paths this device will create and delete (`recordings/{recordingId}/`, the
     * part files), so what it says is held to the schema before any of that: the id is a ULID and
     * each part file is the name the rules give it (docs/03 "이름 규칙") — nothing with a separator
     * in it, nothing that is not this recording's.
     */
    private fun wellFormed(meta: RecordingMeta, recordingId: String): Boolean {
        if (meta.recordingId != recordingId || !ULID.matches(meta.recordingId)) return false
        if (meta.status == RecordingStatus.RECORDING) return false
        // `baseName` parses `startedAt`; a meta that cannot name itself is refused, not thrown over.
        val base = runCatching { MetaWriter.baseName(meta) }.getOrElse { return false }
        return meta.parts.all { it.file == MetaWriter.partFileName(base, it.part, it.track) }
    }

    private class Folder(val recordingId: String, val id: String, val createdTime: String, val description: String?)

    private class Adoptable(val folder: Folder, val meta: RecordingMeta, val fileIds: Map<Pair<Int, Track>, String>)

    companion object {
        /** Between two unforced pulls. A pass every few minutes must not become a listing each. */
        val MIN_INTERVAL: Duration = 2.minutes

        private const val META_SUFFIX = ".meta.json"

        private const val META_MIME = "application/json"

        /** `spec/recording.meta.schema.json` `recordingId`. */
        private val ULID = Regex("^[0-7][0-9A-HJKMNP-TV-Z]{25}$")
    }
}
