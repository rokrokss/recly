@file:OptIn(ExperimentalTime::class)

package recly.core.recording

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.jsonObject
import recly.core.drive.DriveApi
import recly.core.drive.DriveFolderMarker
import recly.core.drive.DriveNotFound
import recly.core.drive.string
import recly.core.ids.Ulid
import recly.core.model.AudioSettings
import recly.core.model.Codec
import recly.core.model.Container
import recly.core.model.RecordingMeta
import recly.core.model.RecordingStatus
import recly.core.model.Source
import recly.core.model.Track
import recly.core.model.isoUtc
import recly.core.model.recJson
import recly.core.model.wire
import recly.core.platform.AuthRequiredException
import recly.core.platform.CoreDeps
import recly.core.platform.Logger

/** What one [RemoteRecordings.pull] did. [skipped] is a pull that did not run — see the reasons. */
data class PullSummary(
    /** Rows taken up from Drive: a recording read back whole, and a provisional one opened for a
     * folder still being uploaded into (docs/03), which counts again when its meta lands. */
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
 * recording is only adopted when no row of that id exists — or when the row that does is the
 * provisional one below. A recording the user deleted here while keeping its folder ("로컬만 삭제")
 * stays deleted: the folder is remembered ([RecordingRepository.ignored]) until Drive stops listing
 * it. Two folders with the same id — a re-run into another path — resolve to the newest one that is
 * complete: an adopted row moves to a newer folder once that one completes, and to whatever is left
 * when its own folder goes.
 *
 * **What is still in flight elsewhere is a row too.** A folder with no `meta.json` in it is an
 * upload still going (the meta goes up last, docs/03), and waiting for it would leave the user
 * looking at nothing for the length of the upload — so it becomes a *provisional* row built out of
 * what the listing gives: the id off the folder, the start time out of that id, the title out of
 * the folder's `description`. The row says [RecordingRecord.remoteUploading] until the meta lands
 * and [RecordingRepository.adopt] replaces the placeholder with the recording itself; a folder that
 * has stood empty for [ABANDONED_AFTER] is not an upload in flight and is dropped instead. And what
 * that device still has to do *after* the upload is on the folder too
 * ([DriveFolderMarker.PENDING]), which is where [RecordingRecord.remotePending] comes from.
 *
 * The same listing carries every folder's `description`, which is the recording's title on Drive:
 * a title renamed on another device is applied here from it, and one renamed here is pushed there
 * ([pushTitles], docs/03 "제목").
 *
 * One pull at a time, and no more often than [MIN_INTERVAL] unless forced: every job pass calls it
 * (`ReclyCore.runDueJobs`), and the desktop passes come every few minutes. While another device is
 * in the middle of something the wait is [FAST_INTERVAL] instead — a list that is showing progress
 * has to move.
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
            // How long the wait is depends on what the last pull found: a row that says another
            // device is uploading or transcribing is a row that has to change soon (docs/03).
            val interval = if (recordings.remoteInFlight()) FAST_INTERVAL else MIN_INTERVAL
            if (!force && last != null && now - last < interval) return PullSummary(skipped = "throttled")
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
        val now = deps.clock.now()
        // Newest folder first, so a re-run's folder is the one an id resolves to.
        val folders = api.recordingFolders()
            .mapNotNull { json ->
                val properties = json["appProperties"]?.jsonObject
                val id = properties?.string("recordingId") ?: return@mapNotNull null
                Folder(
                    recordingId = id,
                    id = json.string("id") ?: return@mapNotNull null,
                    name = json.string("name").orEmpty(),
                    createdTime = json.string("createdTime").orEmpty(),
                    description = json.string("description"),
                    pending = properties.string(DriveFolderMarker.PENDING),
                    pendingAt = properties.string(DriveFolderMarker.PENDING_AT),
                )
            }
            .sortedByDescending { it.createdTime }
            .groupBy { it.recordingId }
        val byFolderId = folders.values.flatten().associateBy { it.id }
        val listed = byFolderId.keys

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
        val provisionalRows = recordings.provisional()
        val known = recordings.ids()
        var adopted = 0
        for ((recordingId, candidates) in folders) {
            val current = adoptedRows[recordingId]
            if (current != null && recordingId !in provisionalRows) {
                // Newer folders than the one the row came from: a re-run that has completed since.
                val newer = candidates.takeWhile { it.id != current }
                val replacement = newer.firstNotNullOfOrNull { (read(recordingId, it) as? Read.Complete)?.adoptable }
                    ?: continue
                if (recordings.drop(recordingId, current)) {
                    dropped++
                    if (recordings.adopt(replacement.meta, replacement.folder.id, replacement.fileIds)) adopted++
                }
                continue
            }
            if (current == null && (recordingId in known || ignored[recordingId] in listed)) continue
            // Unknown here, or here only as the placeholder an earlier pull opened. Either way the
            // newest folder that has its `meta.json` wins, and adopting it replaces a placeholder.
            var uploading: Folder? = null
            var complete: Adoptable? = null
            for (candidate in candidates) {
                when (val read = read(recordingId, candidate)) {
                    is Read.Complete -> {
                        complete = read.adoptable
                        break
                    }

                    Read.Uploading -> uploading = uploading ?: candidate
                    Read.Refused -> Unit
                }
            }
            if (complete != null) {
                if (recordings.adopt(complete.meta, complete.folder.id, complete.fileIds)) {
                    adopted++
                    deps.logger.log(
                        Logger.Level.INFO,
                        "remote.adopt",
                        mapOf(
                            "recordingId" to recordingId,
                            "folderId" to complete.folder.id,
                            "parts" to complete.fileIds.size,
                        ),
                    )
                }
                continue
            }
            // Still uploading: a placeholder row already here stays as it is, and an id this device
            // has never seen becomes one, so the list can say what is happening instead of nothing.
            if (current != null) continue
            val folder = uploading?.takeUnless { abandoned(it, now) } ?: continue
            val placeholder = provisionalMeta(recordingId, folder) ?: continue
            if (recordings.adopt(placeholder, folder.id, emptyMap())) {
                adopted++
                deps.logger.log(
                    Logger.Level.INFO,
                    "remote.adopt.provisional",
                    mapOf("recordingId" to recordingId, "folderId" to folder.id),
                )
            }
        }

        // An upload that has left nothing but a folder for a day is not one in flight: the device
        // that started it gave up, was reset, or lost the account. Dropped the same way a folder
        // that vanished is — and after the loop above, so one whose meta has just landed is a
        // finished recording by now rather than a placeholder to throw away.
        for ((recordingId, folderId) in recordings.provisional()) {
            if (!abandoned(byFolderId[folderId], now)) continue
            if (recordings.drop(recordingId, folderId)) {
                dropped++
                deps.logger.log(
                    Logger.Level.INFO,
                    "remote.abandoned",
                    mapOf("recordingId" to recordingId, "folderId" to folderId),
                )
            }
        }

        // What the device running the workflow says is still to come (docs/03): read off the same
        // listing, for every remote row. This device's own rows never get one — their job is here.
        for ((recordingId, folderId) in recordings.adopted()) {
            recordings.setRemotePending(recordingId, pendingOf(byFolderId[folderId], now))
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
     * The folder read back: its `meta.json` and the id of each part file. The three answers are
     * kept apart because they mean different things to the caller — a folder that has no meta *yet*
     * is a recording being uploaded right now, and one whose meta this device will not act on is
     * not a recording at all.
     */
    private suspend fun read(recordingId: String, folder: Folder): Read {
        val children = try {
            api.children(folder.id)
        } catch (e: DriveNotFound) {
            return Read.Refused
        }
        val metaFile = children.firstOrNull { it.name.endsWith(META_SUFFIX) } ?: return Read.Uploading
        val meta = try {
            recJson.decodeFromString<RecordingMeta>(api.download(metaFile.id).decodeToString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveNotFound) {
            return Read.Refused
        } catch (e: Throwable) {
            deps.logger.log(
                Logger.Level.WARN,
                "remote.meta.unreadable",
                mapOf("recordingId" to recordingId, "folderId" to folder.id),
                e,
            )
            return Read.Refused
        }
        if (!wellFormed(meta, recordingId)) {
            deps.logger.log(
                Logger.Level.WARN,
                "remote.meta.mismatch",
                mapOf("recordingId" to recordingId, "folderId" to folder.id, "metaId" to meta.recordingId),
            )
            return Read.Refused
        }
        val byName = children.associate { it.name to it.id }
        val fileIds = meta.parts.mapNotNull { part -> byName[part.file]?.let { (part.part to part.track) to it } }.toMap()
        return Read.Complete(Adoptable(folder, meta, fileIds))
    }

    /**
     * The meta of a recording that has not written one yet, out of what the listing already said:
     * the id stamped on the folder, the folder's name (`{yyyyMMddTHHmmssZ}_{source}_{ulid8}`,
     * docs/03 "이름 규칙") and its `description`. The start time is the id's own — a ULID carries the
     * millisecond it was made — so the row sits where it belongs in the list from the first pull.
     *
     * Null when the id is not a ULID: it names the directory this device is about to create, and is
     * held to the schema for the same reason [wellFormed] holds an adopted meta to it.
     *
     * The device fields are this device's, not the uploader's — the listing does not say whose the
     * folder is, and nothing reads them off a row that is about to be replaced by the real meta.
     */
    private fun provisionalMeta(recordingId: String, folder: Folder): RecordingMeta? {
        val startedAt = Ulid.timestamp(recordingId) ?: return null
        return RecordingMeta(
            schema = 1,
            recordingId = recordingId,
            source = Source.entries.firstOrNull { it.wire == folder.name.split('_').getOrNull(1) } ?: Source.PHONE,
            platform = deps.device.platform,
            deviceId = deps.device.deviceId,
            deviceName = deps.device.name,
            title = folder.description?.takeIf { it.isNotBlank() },
            startedAt = startedAt.isoUtc(),
            timezone = "UTC",
            audio = AudioSettings(Codec.AAC_LC, Container.M4A, 16_000, 1, 32, 900),
            tracks = emptyList(),
            parts = emptyList(),
            status = RecordingStatus.RECORDING,
        )
    }

    /**
     * What the folder's marker says is still to come, or null for "nothing is". A marker nobody has
     * refreshed in [MARKER_TTL] — the longest a provider result can take (docs/08) — is not read:
     * the device that wrote it has been gone longer than the work it promised could possibly take.
     */
    private fun pendingOf(folder: Folder?, now: Instant): String? {
        val pending = folder?.pending?.takeIf { it.isNotBlank() } ?: return null
        val at = folder.pendingAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        return pending.takeIf { now - at <= MARKER_TTL }
    }

    /** A folder that has held nothing but itself for [ABANDONED_AFTER] is not an upload in flight.
     * A `createdTime` Drive did not give, or one that will not parse, is left alone. */
    private fun abandoned(folder: Folder?, now: Instant): Boolean {
        val created = folder?.createdTime?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return false
        return now - created > ABANDONED_AFTER
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

    /** One `{base}/` folder as the listing gives it (`RECORDING_FOLDER_FIELDS`). */
    private class Folder(
        val recordingId: String,
        val id: String,
        /** `{yyyyMMddTHHmmssZ}_{source}_{ulid8}` (docs/03 "이름 규칙") — where a placeholder's source
         * comes from, since nothing else in the listing says what recorded it. */
        val name: String,
        val createdTime: String,
        val description: String?,
        val pending: String?,
        val pendingAt: String?,
    )

    private class Adoptable(val folder: Folder, val meta: RecordingMeta, val fileIds: Map<Pair<Int, Track>, String>)

    /** What one folder turned out to be when it was read (see [read]). */
    private sealed interface Read {
        /** `meta.json` is there and is this recording's: it can be adopted whole. */
        data class Complete(val adoptable: Adoptable) : Read

        /** No `meta.json` in it. The meta goes up last (docs/03), so the upload is still going. */
        data object Uploading : Read

        /** There is a meta and this device will not act on it: unreadable, or another recording's. */
        data object Refused : Read
    }

    companion object {
        /** Between two unforced pulls. A pass every few minutes must not become a listing each. */
        val MIN_INTERVAL: Duration = 2.minutes

        /** The wait while another device is uploading or still has steps to run: a list that says
         * "업로드 중" has to stop saying it soon after it stops being true (docs/03). */
        val FAST_INTERVAL: Duration = 30.seconds

        /** How long a folder with no `meta.json` in it is still an upload in flight (docs/03). */
        val ABANDONED_AFTER: Duration = 24.hours

        /** How long a `pending` marker is believed — the longest provider result timeout (docs/08). */
        val MARKER_TTL: Duration = 8.hours

        private const val META_SUFFIX = ".meta.json"

        private const val META_MIME = "application/json"

        /** `spec/recording.meta.schema.json` `recordingId`. */
        private val ULID = Regex("^[0-7][0-9A-HJKMNP-TV-Z]{25}$")
    }
}
