@file:OptIn(ExperimentalTime::class)

package recly.core.recording

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOne
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okio.Path
import okio.Path.Companion.toPath
import recly.core.db.RecDatabase
import recly.core.drive.DriveApi
import recly.core.drive.DriveUploadState
import recly.core.job.JobStatus
import recly.core.model.Context
import recly.core.model.Part
import recly.core.model.Range
import recly.core.model.RecordingMeta
import recly.core.model.RecordingStatus
import recly.core.model.Source
import recly.core.model.Track
import recly.core.model.isoUtc
import recly.core.model.recJson
import recly.core.model.wire
import recly.core.platform.CoreDeps
import recly.core.platform.Logger

/**
 * A recording row plus the directory its parts and `meta.json` live in.
 *
 * [driveFolderId] is the recording's `{base}/` folder on Drive once one is known — made by this
 * device's upload, or read by a pull. A [remote] recording is one another device uploaded and this
 * one adopted from Drive (docs/03 "다른 기기의 녹음"): it has no job here and never gets one — Drive
 * already holds it — and its parts are fetched by file id when played.
 *
 * The three "still in flight elsewhere" answers a ledger needs are [receiving], [remoteUploading]
 * and [remotePending] — none of them is a job of this device's, which is why none of them can be
 * read off the queue.
 */
data class RecordingRecord(
    val id: String,
    val meta: RecordingMeta,
    val dir: Path,
    val driveFolderId: String? = null,
    val remote: Boolean = false,
    /**
     * docs/03 "다른 기기의 녹음": the step types the device that is running the workflow still has to
     * run after its upload (`transcribe`, `webhook`), read off the folder's marker. Empty when that
     * device is done, when the marker is too old to believe, and always for this device's own rows.
     */
    val remotePending: Set<String> = emptySet(),
) {
    /**
     * A watch transfer in flight (docs/03 "워치 → 폰 전송 계약"): the phone opens the row when the
     * first part arrives and replaces it wholesale when `meta.json` lands. A phone never *records*
     * with source `watch` — it only ever receives one — so a local row of this shape can only be
     * that transfer, still coming in.
     */
    val receiving: Boolean get() = !remote && meta.source == Source.WATCH && meta.status == RecordingStatus.RECORDING

    /**
     * Another device is still uploading (docs/03 "다른 기기의 녹음"): its folder is on Drive with no
     * `meta.json` in it yet — the meta goes up last — so what this row carries is the placeholder a
     * pull built out of the folder's name.
     */
    val remoteUploading: Boolean get() = remote && meta.status == RecordingStatus.RECORDING
}

/** What "녹음 삭제" (docs/03) did, or why it did nothing. */
sealed interface DeleteResult {
    /**
     * Everything local is gone. [driveDeleted] is true only when the user asked for the Drive
     * folder too and Drive agreed; [driveError] is what it said when it did not — the local files
     * are gone either way, so there is nothing left to retry with and the screen has to say so.
     */
    data class Deleted(val driveDeleted: Boolean, val driveError: String? = null) : DeleteResult

    /** A job of this recording is `RUNNING`: it is reading the very files this would delete. */
    data object Busy : DeleteResult

    data object NotFound : DeleteResult
}

/**
 * The DB row and `meta.json` are written together: the row is what the UI and the executor query,
 * the file is what ships to Drive and survives an app reinstall.
 *
 * Every public call runs on [CoreDeps.io] under one mutex. `addPart` and `finalize` are
 * read-modify-write of the same `meta_json` and the same file, and the recorder calls them from
 * whatever thread finished a segment.
 */
class RecordingRepository(
    private val db: RecDatabase,
    private val deps: CoreDeps,
    /** Only [delete] with `deleteDrive` uses it, and only for `files.delete`. */
    private val drive: DriveApi = DriveApi(deps),
) {
    private val queries get() = db.recQueries
    private val mutex = Mutex()

    suspend fun create(meta: RecordingMeta, dir: Path): Unit = locked {
        db.transaction {
            queries.insertRecording(
                meta.recordingId,
                meta.source.wire,
                meta.platform.wire,
                meta.workflowId,
                meta.title,
                meta.startedAt,
                meta.endedAt,
                meta.durationSec,
                meta.timezone,
                dir.toString(),
                recJson.encodeToString(meta),
                meta.status.wire,
            )
            meta.parts.forEach { insertPart(meta.recordingId, it) }
        }
        MetaWriter.write(deps.fileSystem, dir, meta)
    }

    /**
     * The watch→phone path (docs/03 "워치 → 폰 전송 계약"): parts arrive before `meta.json` does, so
     * the row is opened with a placeholder meta and replaced wholesale when the real one lands.
     *
     * Unlike [create] it writes no `meta.json`: the placeholder must never reach the disk, and the
     * real meta is the file the watch sent, which `TransferReceiver` writes verbatim.
     */
    suspend fun receive(meta: RecordingMeta, dir: Path): Unit = locked {
        db.transaction {
            queries.upsertRecording(
                meta.recordingId,
                meta.source.wire,
                meta.platform.wire,
                meta.workflowId,
                meta.title,
                meta.startedAt,
                meta.endedAt,
                meta.durationSec,
                meta.timezone,
                dir.toString(),
                recJson.encodeToString(meta),
                meta.status.wire,
                meta.recordingId,
            )
            meta.parts.forEach { insertPart(meta.recordingId, it) }
        }
    }

    /**
     * docs/03 "다른 기기의 녹음": a recording read back from Drive, where another device put it. The
     * row is what the list shows; the parts are written as already purged (`deleted = 1`) with the
     * Drive file id each was found under, so [recly.core.job.JobStore.enqueue] never opens a job
     * for it and `AudioParts` can fetch one back when it is played. `meta.json` is written like
     * [create]'s, so the directory exists for every scan that lists one.
     *
     * Nothing is written when the recording is already here — the one this device made, or the
     * one an earlier pull adopted — or when the user deleted it here while keeping its folder
     * ([ignored]); that check is inside the transaction, so a "로컬만 삭제" that lands during a pull
     * is not undone by it.
     *
     * The one existing row it does write over is a **provisional** one ([provisional]): the
     * placeholder a pull opened for a folder that had no `meta.json` yet, which this call is the
     * completion of. A row of this device's own (`remote = 0`) is never touched, whatever Drive says.
     *
     * @param fileIds the Drive file of each part, keyed by `(part, track)`; a part the folder did
     * not have is adopted without one and stays missing when played.
     */
    suspend fun adopt(
        meta: RecordingMeta,
        folderId: String,
        fileIds: Map<Pair<Int, Track>, String>,
    ): Boolean = locked {
        val dir = deps.dataDir / RECORDINGS / meta.recordingId
        val written = db.transactionWithResult {
            val existing = queries.selectRecordingById(meta.recordingId).executeAsOneOrNull()
            if (existing != null && !(existing.remote == 1L && existing.status == RecordingStatus.RECORDING.wire)) {
                return@transactionWithResult false
            }
            if (queries.kvGet(IGNORED_PREFIX + meta.recordingId).executeAsOneOrNull() != null) {
                return@transactionWithResult false
            }
            queries.insertAdoptedRecording(
                meta.recordingId,
                meta.source.wire,
                meta.platform.wire,
                meta.workflowId,
                meta.title,
                meta.startedAt,
                meta.endedAt,
                meta.durationSec,
                meta.timezone,
                dir.toString(),
                recJson.encodeToString(meta),
                meta.status.wire,
                folderId,
            )
            meta.parts.forEach {
                queries.insertAdoptedPart(
                    meta.recordingId,
                    it.part.toLong(),
                    it.track.wire,
                    it.file,
                    it.bytes,
                    it.sha256,
                    fileIds[it.part to it.track],
                )
            }
            true
        }
        if (written) MetaWriter.write(deps.fileSystem, dir, meta)
        written
    }

    /** Every recording adopted from Drive, with the folder it was read from. */
    suspend fun adopted(): Map<String, String> = locked {
        queries.selectAdoptedRecordings().executeAsList().associate { it.id to it.drive_folder_id!! }
    }

    /**
     * The adopted rows that are still only a placeholder (docs/03 "다른 기기의 녹음"): another device's
     * folder is on Drive but its `meta.json` is not, so all this row knows is what the folder's name
     * says. Kept apart from [adopted] because these are the two rows a pull may write over — the
     * meta landing completes one ([adopt]), and 24 hours without it abandons the other ([drop]).
     */
    suspend fun provisional(): Map<String, String> = locked {
        queries.selectProvisionalRecordings().executeAsList().associate { it.id to it.drive_folder_id!! }
    }

    /**
     * What the device running the workflow says is still to come, off the folder's marker (docs/03
     * "다른 기기의 녹음"): the comma-joined step types, or null for nothing. Only remote rows have one
     * — this device's own job rows are the truth about its own recordings.
     *
     * @return true when the row changed, so an unchanged marker does not wake every ledger on
     * `recordings.observe()` once a pass.
     */
    suspend fun setRemotePending(recordingId: String, pending: String?): Boolean = locked {
        val row = queries.selectRecordingById(recordingId).executeAsOneOrNull() ?: return@locked false
        if (row.remote != 1L || row.remote_pending == pending) return@locked false
        queries.updateRemotePending(pending, recordingId)
        true
    }

    /** Whether any other device is still uploading or still has steps to run — what [RemoteRecordings]
     * asks to decide how often to look (docs/03 "다른 기기의 녹음"). */
    suspend fun remoteInFlight(): Boolean = locked { queries.countRemoteInFlight().executeAsOne() > 0 }

    /**
     * The other direction of [adopt]: an adopted row whose Drive folder is gone. Only that row —
     * the folder id is checked inside the transaction, so a row that has since become this
     * device's own (a watch transfer landing on the same id clears the folder id) is left alone.
     * No tombstone is written: there is no folder left to keep out.
     */
    suspend fun drop(recordingId: String, folderId: String): Boolean = locked {
        val removed = db.transactionWithResult<Path?> {
            val row = queries.selectRecordingById(recordingId).executeAsOneOrNull()
                ?: return@transactionWithResult null
            if (row.remote != 1L || row.drive_folder_id != folderId) return@transactionWithResult null
            queries.kvDelete(TITLE_PREFIX + recordingId)
            queries.deleteStepRunsByRecording(recordingId)
            queries.deleteJobsByRecording(recordingId)
            queries.deletePartsByRecording(recordingId)
            queries.deleteRecording(recordingId)
            row.dir.toPath()
        } ?: return@locked false
        deps.fileSystem.deleteRecursively(removed, mustExist = false)
        true
    }

    /**
     * docs/03 "다른 기기의 녹음": the folders a pull must not adopt, by recording id. Written by
     * [delete] when the user kept the Drive folder ("로컬만 삭제"): the row is gone but the folder is
     * still listed, and without this the next pull would put the recording straight back.
     */
    suspend fun ignored(): Map<String, String> = locked {
        queries.kvSelectPrefix(IGNORED_PREFIX).executeAsList().associate { it.key.removePrefix(IGNORED_PREFIX) to it.value_ }
    }

    /** The folder is gone from Drive, so there is nothing left to keep out. */
    suspend fun unignore(recordingId: String): Unit = locked { queries.kvDelete(IGNORED_PREFIX + recordingId) }

    /** "연결 해제": a device wiped of its recordings starts over with what Drive has. */
    suspend fun clearIgnored(): Unit = locked { queries.kvDeletePrefix(IGNORED_PREFIX) }

    /**
     * The detail screen's rename (docs/03 "제목"): any finalized recording, this device's own or an
     * adopted one. Written here at once — the row, `meta.json`, and a pending push — and carried to
     * Drive by [RemoteRecordings.pushTitles], so every device reads the same title back.
     *
     * @return false when there is nothing to rename: no such recording, or one still recording.
     */
    suspend fun rename(recordingId: String, title: String?): Boolean = locked {
        val record = record(recordingId) ?: return@locked false
        if (record.meta.status == RecordingStatus.RECORDING) return@locked false
        val meta = record.meta.copy(title = title?.trim()?.takeIf { it.isNotEmpty() })
        db.transaction {
            writeMeta(meta)
            queries.kvSet(TITLE_PREFIX + recordingId, meta.title.orEmpty())
        }
        MetaWriter.write(deps.fileSystem, record.dir, meta)
        deps.logger.log(Logger.Level.INFO, "rec.rename", mapOf("recordingId" to recordingId))
        true
    }

    /** Titles renamed here that Drive has not been told about yet, by recording id ("" = none). */
    suspend fun pendingTitles(): Map<String, String> = locked {
        queries.kvSelectPrefix(TITLE_PREFIX).executeAsList().associate { it.key.removePrefix(TITLE_PREFIX) to it.value_ }
    }

    /** The push landed. Cleared only if no newer rename was written over it meanwhile. */
    suspend fun titlePushed(recordingId: String, title: String): Unit = locked {
        queries.kvDeleteIfValue(TITLE_PREFIX + recordingId, title)
    }

    /**
     * A title read back from Drive (the folder's `description`): applied when it differs from the
     * row's and no rename of this device's own is waiting to go the other way. The rename that is
     * still pending wins, since it is the newer of the two on this device.
     *
     * @return true when the row changed.
     */
    suspend fun applyTitle(recordingId: String, title: String): Boolean = locked {
        val record = record(recordingId) ?: return@locked false
        if (record.meta.status == RecordingStatus.RECORDING) return@locked false
        if (queries.kvGet(TITLE_PREFIX + recordingId).executeAsOneOrNull() != null) return@locked false
        if (record.meta.title == title) return@locked false
        val meta = record.meta.copy(title = title)
        writeMeta(meta)
        MetaWriter.write(deps.fileSystem, record.dir, meta)
        true
    }

    suspend fun ids(): Set<String> = locked { queries.selectRecordingIds().executeAsList().toSet() }

    /**
     * A recording uploaded before the row kept its folder (migration 3): the listing names the
     * folder, so the row learns it here — and a rename of it can be pushed. Never over a known one.
     */
    suspend fun rememberFolder(recordingId: String, folderId: String): Unit = locked {
        queries.updateRecordingFolderIfUnknown(folderId, recordingId)
    }

    /** Every recording whose Drive folder is known — this device's uploads and the adopted ones. */
    suspend fun driveFolders(): Map<String, String> = locked {
        queries.selectRecordingFolders().executeAsList().associate { it.id to it.drive_folder_id!! }
    }

    /** The Drive file of each adopted part, keyed by `(part, track)` — what playback fetches by. */
    suspend fun driveFileIds(recordingId: String): Map<Pair<Int, Track>, String> = locked {
        queries.selectPartsByRecording(recordingId).executeAsList()
            .mapNotNull { row ->
                val track = Track.entries.firstOrNull { it.wire == row.track } ?: return@mapNotNull null
                row.drive_file_id?.let { (row.part.toInt() to track) to it }
            }
            .toMap()
    }

    /**
     * Emits whenever the `recording` table changes. The ledgers watch the job table for progress;
     * this is for the rows a pull adds or drops without a job ever existing.
     */
    fun observe(): Flow<Unit> = queries.countRecordings().asFlow().mapToOne(deps.io).map { }

    /** Drops the row, its parts and the whole directory — the orphan purge, and nothing else. */
    suspend fun delete(recordingId: String) {
        delete(recordingId, deleteDrive = false)
    }

    /**
     * "녹음 삭제" (docs/03): the parts, `meta.json`, the result files and the directory, plus the
     * `recording`, `part`, `job` and `step_run` rows — nothing cascades, so every table is named.
     *
     * [deleteDrive] is the other half of the dialog, and the one whose default is off: the files
     * in Drive are the user's own and something downstream may already have consumed the folder,
     * so the irreversible choice is never the default one. A Drive that refuses does not hold up
     * the local deletion — once the local copy is gone there is nothing left to retry from — and
     * says so through [DeleteResult.Deleted.driveError] instead.
     *
     * A `RUNNING` job is reading the very files this would delete, so that is [DeleteResult.Busy]
     * and nothing is touched. Every other status is deleted along with the recording.
     *
     * Keeping the Drive folder leaves a folder that a pull would list and adopt back (docs/03 "다른
     * 기기의 녹음"), so that choice is remembered ([ignored]) in the same transaction.
     *
     * That check and every row deletion are one transaction, and `JobStore.claimRunning` is
     * another: SQLite has a single writer, so one of the two commits first and the other sees it.
     * Either the job is `RUNNING` and this is [DeleteResult.Busy], or the rows are gone and the
     * claim finds nothing to run — never both. The files and Drive come after the commit, when
     * nothing can still claim them — the files without leaving the locked pass, so that no
     * cancellation can strand a directory the rows no longer name.
     */
    suspend fun delete(recordingId: String, deleteDrive: Boolean): DeleteResult {
        val removal = locked {
            val outcome = db.transactionWithResult {
                val row = queries.selectRecordingById(recordingId).executeAsOneOrNull()
                    ?: return@transactionWithResult Removal.NotFound
                val running = queries.selectJobsByRecording(recordingId).executeAsList()
                    .any { it.status == JobStatus.RUNNING.name }
                if (running) return@transactionWithResult Removal.Busy
                val folderId = driveFolderId(recordingId) ?: row.drive_folder_id
                if (!deleteDrive && folderId != null) queries.kvSet(IGNORED_PREFIX + recordingId, folderId)
                // A rename that never reached Drive goes with the recording: pushed later, it would
                // land on whatever another device has since named the folder.
                queries.kvDelete(TITLE_PREFIX + recordingId)
                queries.deleteStepRunsByRecording(recordingId)
                queries.deleteJobsByRecording(recordingId)
                queries.deletePartsByRecording(recordingId)
                queries.deleteRecording(recordingId)
                Removal.Done(row.dir.toPath(), if (deleteDrive) folderId else null)
            }
            // In the same locked pass as the commit, and not in a second one: past the commit
            // nothing points at the directory any more, and [locked]'s body cannot suspend, so a
            // cancellation of the caller cannot land between the two and orphan the files.
            if (outcome is Removal.Done) deps.fileSystem.deleteRecursively(outcome.dir, mustExist = false)
            outcome
        }
        when (removal) {
            Removal.NotFound -> return DeleteResult.NotFound
            Removal.Busy -> return DeleteResult.Busy
            is Removal.Done -> Unit
        }

        var driveDeleted = false
        var driveError: String? = null
        removal.folderId?.let { folderId ->
            try {
                drive.delete(folderId)
                driveDeleted = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                driveError = e.message ?: e::class.simpleName
                deps.logger.log(
                    Logger.Level.WARN,
                    "rec.delete.drive",
                    mapOf("recordingId" to recordingId, "folderId" to folderId, "reason" to driveError),
                )
            }
        }
        deps.logger.log(
            Logger.Level.INFO,
            "rec.delete",
            mapOf("recordingId" to recordingId, "deleteDrive" to deleteDrive, "driveDeleted" to driveDeleted),
        )
        return DeleteResult.Deleted(driveDeleted, driveError)
    }

    /** What the transaction of [delete] settled, and what the work after it still needs. */
    private sealed interface Removal {
        data object NotFound : Removal

        data object Busy : Removal

        data class Done(val dir: Path, val folderId: String?) : Removal
    }

    /**
     * The recording's own `{base}/` folder on Drive (ADR-014), from what the `drive.upload` step
     * left behind — its output when it finished or parked, its resume state when it neither did.
     * Not the folder cache: that maps the rendered *path* (`recly/2026/2026-08`), which every other
     * recording of the month shares and which must never be deleted along with one of them.
     *
     * Read inside [delete]'s transaction, off the rows it is about to remove.
     */
    private fun driveFolderId(recordingId: String): String? =
        queries.selectStepRunsByRecording(recordingId).executeAsList()
            .firstNotNullOfOrNull { row ->
                val output = row.output_json?.let { recJson.parseToJsonElement(it) as JsonObject }
                output?.get("folderId")?.jsonPrimitive?.contentOrNull
                    ?: DriveUploadState.from(row.state_json?.let { recJson.parseToJsonElement(it) as JsonObject })
                        .folderId
            }

    suspend fun addPart(recordingId: String, part: Part): Unit = locked {
        val record = requireRecord(recordingId)
        val parts = record.meta.parts
            .filterNot { it.part == part.part && it.track == part.track }
            .plus(part)
            .sortedWith(compareBy({ it.part }, { it.track }))
        val meta = record.meta.copy(parts = parts)
        db.transaction {
            insertPart(recordingId, part)
            writeMeta(meta)
        }
        MetaWriter.write(deps.fileSystem, record.dir, meta)
    }

    /**
     * [silenced] arrives in one go at stop: the shell watches the transitions while recording
     * (Android `isClientSilenced`, Apple interruptions) and only the closed set is worth writing.
     *
     * [gaps] the same way, for the audio that is missing rather than merely silent: a macOS engine
     * restart after a device change, a re-created system tap (docs/12). Both lists are replace-or-
     * keep — an empty one leaves whatever the meta already carries, so a recovery pass that knows
     * nothing about either does not erase what the recorder wrote.
     */
    suspend fun finalize(
        recordingId: String,
        endedAt: Instant,
        durationSec: Double,
        title: String? = null,
        silenced: List<Range> = emptyList(),
        gaps: List<Range> = emptyList(),
    ): RecordingRecord = locked {
        val record = requireRecord(recordingId)
        val meta = record.meta.copy(
            endedAt = endedAt.isoUtc(),
            durationSec = durationSec,
            title = title ?: record.meta.title,
            gaps = if (gaps.isEmpty()) record.meta.gaps else gaps,
            silenced = if (silenced.isEmpty()) record.meta.silenced else silenced,
            status = RecordingStatus.FINALIZED,
        )
        writeMeta(meta)
        MetaWriter.write(deps.fileSystem, record.dir, meta)
        deps.logger.log(
            Logger.Level.INFO,
            "rec.finalize",
            mapOf("recordingId" to recordingId, "durationSec" to durationSec, "parts" to meta.parts.size),
        )
        record.copy(meta = meta)
    }

    /**
     * The mobile title arrives *after* the stop: docs/03 has the user name a recording once it has
     * actually ended, which is one screen later than [finalize]. The same dialog asks how many
     * people were in the room (docs/03 `context.participants`, docs/08's speaker hint), so both
     * answers land in one write.
     *
     * Only while they can still change anything, though — a job that is RUNNING has read the meta
     * and may already be pushing it to Drive, and a DONE one has. Returns false when nothing was
     * applied, so the UI can say so instead of silently losing it.
     *
     * @param title the name to give it; null leaves the one it has (the dialog's 건너뛰기).
     * @param participants the count the user picked; null is "모름" and leaves the meta alone.
     */
    suspend fun updateTitle(recordingId: String, title: String?, participants: Int? = null): Boolean = locked {
        val record = record(recordingId) ?: return@locked false
        if (record.meta.status != RecordingStatus.FINALIZED) return@locked false
        // An adopted recording's meta is Drive's, and Drive's is the one every device shows.
        if (record.remote) return@locked false
        val settled = queries.selectJobsByRecording(recordingId).executeAsList()
            .any { it.status == JobStatus.RUNNING.name || it.status == JobStatus.DONE.name }
        if (settled) return@locked false
        val meta = record.meta.copy(
            title = title ?: record.meta.title,
            context = participants
                ?.let { (record.meta.context ?: Context()).copy(participants = it) }
                ?: record.meta.context,
        )
        writeMeta(meta)
        MetaWriter.write(deps.fileSystem, record.dir, meta)
        true
    }

    /**
     * The status of every job of a recording. Callers use it to tell a recording that still needs
     * one from a recording whose job has already read the meta (docs/03).
     */
    suspend fun jobStatuses(recordingId: String): List<JobStatus> = locked {
        queries.selectJobsByRecording(recordingId).executeAsList().map { JobStatus.valueOf(it.status) }
    }

    suspend fun get(id: String): RecordingRecord? = locked { record(id) }

    suspend fun list(limit: Int): List<RecordingRecord> = locked {
        queries.selectRecordings(limit.toLong()).executeAsList().map {
            RecordingRecord(
                it.id,
                recJson.decodeFromString(it.meta_json),
                it.dir.toPath(),
                it.drive_folder_id,
                it.remote == 1L,
                pendingTypes(it.remote_pending),
            )
        }
    }

    /**
     * Deletes the files of the parts a purge has claimed (`deleted = 1`, written by
     * `JobStore.claimPurge`); `meta.json` and the rows stay (docs/03 "로컬 저장"). Idempotent, and
     * a file that is already gone is not an error — this may run twice after a crash.
     */
    suspend fun purgeParts(recordingId: String): Unit = locked {
        val record = record(recordingId)
        if (record != null) {
            queries.selectPartsByRecording(recordingId).executeAsList()
                .filter { it.deleted == 1L }
                .forEach { deps.fileSystem.delete(record.dir / it.file_, mustExist = false) }
        }
    }

    /**
     * The other direction: a part fetched back from Drive (`AudioParts`) is on the device again.
     * [from] is the temp file the verified bytes were written to — beside the recording directories
     * rather than in one of them, so that [delete] cannot take it mid-download; this renames it to
     * the name the part's row gives it and marks the row present, so the retention sweep gives the
     * part its window over from the new file.
     *
     * The rename and the mark are one [locked] pass, and [purgeParts] and [delete] are the only
     * other things that touch a recording's files — also under the same lock. So the three orders
     * below are the only three there are, and none of them leaves a row and its file disagreeing:
     * - a purge before this one: the file it took is written back and the row says present again;
     * - a purge after it: the row is present, and a purge only takes the rows a claim marked
     *   deleted, so the file that has just landed stays;
     * - a [delete] before it: the recording is gone, and this writes nothing — it drops [from] and
     *   returns null for the caller to treat the part as missing.
     *
     * @return where the part now lives, or null when the recording or its part row has gone.
     */
    suspend fun restorePart(recordingId: String, part: Int, track: Track, from: Path): Path? = locked {
        val record = record(recordingId)
        val row = record?.let { queries.selectPart(recordingId, part.toLong(), track.wire).executeAsOneOrNull() }
        if (record == null || row == null) {
            deps.fileSystem.delete(from, mustExist = false)
            return@locked null
        }
        val path = record.dir / row.file_
        deps.fileSystem.createDirectories(record.dir)
        deps.fileSystem.atomicMove(from, path)
        queries.markPartPresent(recordingId, part.toLong(), track.wire)
        path
    }

    private suspend fun <T> locked(body: () -> T): T = withContext(deps.io) { mutex.withLock { body() } }

    private fun record(id: String): RecordingRecord? =
        queries.selectRecordingById(id).executeAsOneOrNull()?.let {
            RecordingRecord(
                it.id,
                recJson.decodeFromString(it.meta_json),
                it.dir.toPath(),
                it.drive_folder_id,
                it.remote == 1L,
                pendingTypes(it.remote_pending),
            )
        }

    /** The column is the marker verbatim — comma-joined types, or NULL for "nothing is coming". */
    private fun pendingTypes(column: String?): Set<String> =
        column?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    private fun requireRecord(id: String): RecordingRecord =
        record(id) ?: throw IllegalArgumentException("unknown recording '$id'")

    private fun insertPart(recordingId: String, part: Part) {
        queries.insertPart(
            recordingId,
            part.part.toLong(),
            part.track.wire,
            part.file,
            part.bytes,
            part.sha256,
            null,
        )
    }

    private fun writeMeta(meta: RecordingMeta) {
        queries.updateRecordingMeta(
            meta.title,
            meta.endedAt,
            meta.durationSec,
            recJson.encodeToString(meta),
            meta.status.wire,
            meta.recordingId,
        )
    }

    companion object {
        /** Under [CoreDeps.dataDir]: where an adopted recording's directory goes, keyed by id like
         * a watch's (docs/03 "로컬 저장"), since the shell's `{base}` layout is the shell's. */
        const val RECORDINGS: String = "recordings"

        /** `kv` rows: `remote/ignored/{recordingId}` → the Drive folder id a pull must skip. */
        private const val IGNORED_PREFIX: String = "remote/ignored/"

        /** `kv` rows: `title/pending/{recordingId}` → the title Drive still has to be told. */
        private const val TITLE_PREFIX: String = "title/pending/"
    }
}
