@file:OptIn(ExperimentalTime::class)

package recly.core.recording

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOne
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okio.Path
import recly.core.db.RecDatabase
import recly.core.drive.DriveUploadState
import recly.core.job.JobStatus
import recly.core.model.Context
import recly.core.model.DriveLocation
import recly.core.model.Highlight
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
import recly.core.storage.CloudFiles
import recly.core.storage.CloudStorage
import recly.core.storage.StorageKind

/**
 * A recording row plus the directory its parts and `meta.json` live in.
 *
 * [driveFolderId] is the recording's `{base}/` folder on Drive once one is known — made by this
 * device's upload, or read by a pull. A [remote] recording is one another device uploaded and this
 * one adopted from Drive (docs/03 "Recordings from other devices"): it has no job here and never gets one — Drive
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
     * docs/03 "Recordings from other devices": the step types the device that is running the workflow still has to
     * run after its upload (`transcribe`), read off the folder's marker. Empty when that
     * device is done, when the marker is too old to believe, or when a local workflow job remains authoritative.
     */
    val remotePending: Set<String> = emptySet(),
    /** A complete Drive copy restored without recreating or replaying workflow jobs. */
    val driveSynced: Boolean = false,
) {
    /**
     * docs/03 "Storage location": where the recording's folder is — Google Drive, iCloud or a local folder —
     * read off its id; null until one is known.
     */
    val storage: StorageKind? get() = driveFolderId?.let(StorageKind::ofId)

    /**
     * The folder's path under the app's iCloud folder, for a shell that shows it in Finder; null for
     * a recording on Drive or not uploaded yet. Not while another device is still uploading into it.
     */
    val icloudFolderPath: String?
        get() = driveFolderId?.takeIf { !remoteUploading && StorageKind.ofId(it) == StorageKind.ICLOUD }
            ?.removePrefix(StorageKind.ICLOUD_PREFIX)

    /**
     * The folder's path under the local folder the user picked, for a shell that shows it in Finder
     * or Explorer; null for a recording elsewhere or not copied yet (docs/03 "Storage location").
     */
    val localFolderPath: String?
        get() = driveFolderId?.takeIf { StorageKind.ofId(it) == StorageKind.FOLDER }
            ?.removePrefix(StorageKind.FOLDER_PREFIX)

    /**
     * A watch transfer in flight (docs/03 "Watch → phone transfer contract"): the phone opens the row when the
     * first part arrives and replaces it wholesale when `meta.json` lands. A phone never *records*
     * with source `watch` — it only ever receives one — so a local row of this shape can only be
     * that transfer, still coming in.
     */
    val receiving: Boolean get() = !remote && meta.source == Source.WATCH && meta.status == RecordingStatus.RECORDING

    /**
     * A file the user picked is still being transcoded into this recording (docs/03 "Naming rules",
     * `source: import`): the row is there, its parts are not yet — they arrive all at once when the import
     * completes, or the row goes if it fails.
     */
    val importing: Boolean get() = !remote && meta.source == Source.IMPORT && meta.status == RecordingStatus.RECORDING

    /**
     * Another device is still uploading (docs/03 "Recordings from other devices"): its folder is on Drive with no
     * `meta.json` in it yet — the meta goes up last — so what this row carries is the placeholder a
     * pull built out of the folder's name.
     */
    val remoteUploading: Boolean get() = remote && meta.status == RecordingStatus.RECORDING

    /**
     * The Drive folder a ledger row can open (docs/03 "Recordings from other devices", docs/09 screen principle 2): the
     * link `drive.upload` wrote into `meta.json`, or the folder's canonical URL when only its id is
     * known — an adopted row was read out of that very folder, and one uploaded before the meta
     * carried a link still names it. Not while another device is still uploading into it: that
     * row offers no link until the upload is in.
     */
    val driveFolderUrl: String?
        get() = if (remoteUploading || storage == StorageKind.ICLOUD || storage == StorageKind.FOLDER) null
        else meta.drive?.folderUrl ?: driveFolderId?.let { "https://drive.google.com/drive/folders/$it" }
}

/** What "Delete recording" (docs/03) did, or why it did nothing. */
sealed interface DeleteResult {
    /**
     * Everything local is gone. [driveDeleted] is true only when the user asked for the Drive
     * folder too and Drive agreed; [driveError] is what it said when it did not — the local files
     * are gone either way, so there is nothing left to retry with and the screen has to say so.
     */
    data class Deleted(val driveDeleted: Boolean, val driveError: String? = null) : DeleteResult

    /**
     * Nothing was deleted: an on-device transcription of the recording did not stop in time, or —
     * with no executor to stop it ([RecordingRepository.executor]) — a job of it is `RUNNING`.
     */
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
    /**
     * Only [delete] with `deleteDrive` uses it, and only to delete the folder — on Drive, in iCloud or
     * in the local folder, wherever its id says it is (docs/03 "Storage location").
     */
    private val drive: CloudFiles = CloudStorage.of(deps),
) {
    private val queries get() = db.recQueries
    private val mutex = Mutex()
    private val directories = RecordingDirectory(deps.dataDir, deps.device.platform)
    internal var beforeCapture: suspend (RecordingMeta) -> Unit = {}
    internal var afterCapture: suspend (String) -> Unit = {}
    internal var localTranscription: recly.core.transcribe.LocalTranscriptionService? = null

    /** The queue that runs this device's jobs, which [delete] stops for the recording it deletes. */
    internal var executor: recly.core.job.Executor? = null

    /** A complete on-device copy, independent of Drive authorization or job status. */
    @Throws(Throwable::class)
    suspend fun hasLocalAudio(record: RecordingRecord): Boolean = withContext(deps.io) {
        record.meta.status == RecordingStatus.FINALIZED && record.meta.parts.isNotEmpty() &&
            record.meta.parts.all { part ->
                val file = deps.fileSystem.metadataOrNull(record.dir / part.file)
                file?.isRegularFile == true && file.size == part.bytes
            }
    }

    suspend fun create(meta: RecordingMeta, dir: Path) {
        beforeCapture(meta)
        try {
            locked {
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
                directories.stored(dir),
                recJson.encodeToString(meta),
                meta.status.wire,
            )
            meta.parts.forEach { insertPart(meta.recordingId, it) }
        }
            MetaWriter.write(deps.fileSystem, dir, meta)
            }
        } catch (error: Throwable) {
            afterCapture(meta.recordingId)
            throw error
        }
    }

    /**
     * The watch→phone path (docs/03 "Watch → phone transfer contract"): parts arrive before `meta.json` does, so
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
                directories.stored(dir),
                recJson.encodeToString(meta),
                meta.status.wire,
                meta.recordingId,
            )
            meta.parts.forEach { insertPart(meta.recordingId, it) }
        }
    }

    /**
     * docs/03 "Recordings from other devices": a recording read back from Drive, where another device put it. The
     * row is what the list shows; the parts are written as already purged (`deleted = 1`) with the
     * Drive file id each was found under, so [recly.core.job.JobStore.enqueue] never opens a job
     * for it and `AudioParts` can fetch one back when it is played. `meta.json` is written like
     * [create]'s, so the directory exists for every scan that lists one.
     *
     * Nothing is written when the recording is already here — the one this device made, or the
     * one an earlier pull adopted — or when the user deleted it here while keeping its folder
     * ([ignored]); that check is inside the transaction, so a "Delete local only" that lands during a pull
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
                directories.stored(dir),
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

    /** Local rows without jobs are reconciled with Drive instead of being replaced or re-uploaded. */
    suspend fun driveRestoreCandidates(): Set<String> = locked {
        queries.selectDriveRestoreCandidates().executeAsList().toSet()
    }

    suspend fun synced(): Map<String, String> = locked {
        queries.selectSyncedRecordings().executeAsList().associate { it.id to it.drive_folder_id!! }
    }

    /** Restore file references only after matching every part, without changing local audio/meta.
     * The job check is transactional: a workflow queued while Drive was being read always wins. */
    suspend fun restoreDriveCopy(meta: RecordingMeta, folderId: String,
                                 fileIds: Map<Pair<Int, Track>, String>, pending: String?): Boolean = locked {
        db.transactionWithResult {
            val record = record(meta.recordingId) ?: return@transactionWithResult false
            if (record.remote || record.meta.status != RecordingStatus.FINALIZED ||
                queries.selectJobsByRecording(record.id).executeAsList().isNotEmpty()) return@transactionWithResult false
            val parts = record.meta.parts
            if (parts.isEmpty() || parts.size != meta.parts.size) return@transactionWithResult false
            val remoteParts = meta.parts.associateBy { it.part to it.track }
            if (remoteParts.size != parts.size || parts.any { part ->
                    val remote = remoteParts[part.part to part.track]
                    remote == null || remote.file != part.file || remote.sha256 != part.sha256 ||
                        remote.bytes != part.bytes || fileIds[part.part to part.track].isNullOrBlank()
                }) return@transactionWithResult false
            parts.forEach { part ->
                queries.restorePartDriveFile(fileIds.getValue(part.part to part.track), record.id,
                                             part.part.toLong(), part.track.wire)
            }
            queries.restoreRecordingDriveCopy(folderId, pending, record.id)
            true
        }
    }

    /** A different account or a deleted folder invalidates the remote copy, never the local take. */
    suspend fun forgetDriveCopy(recordingId: String): Unit = locked {
        db.transaction {
            if (queries.selectRecordingById(recordingId).executeAsOneOrNull()?.remote == 0L) {
                queries.clearRecordingDriveCopy(recordingId)
                queries.clearPartDriveFiles(recordingId)
            }
        }
    }

    /** Every recording adopted from Drive, with the folder it was read from. */
    suspend fun adopted(): Map<String, String> = locked {
        queries.selectAdoptedRecordings().executeAsList().associate { it.id to it.drive_folder_id!! }
    }

    /**
     * The adopted rows that are still only a placeholder (docs/03 "Recordings from other devices"): another device's
     * folder is on Drive but its `meta.json` is not, so all this row knows is what the folder's name
     * says. Kept apart from [adopted] because these are the two rows a pull may write over — the
     * meta landing completes one ([adopt]), and 24 hours without it abandons the other ([drop]).
     */
    suspend fun provisional(): Map<String, String> = locked {
        queries.selectProvisionalRecordings().executeAsList().associate { it.id to it.drive_folder_id!! }
    }

    /**
     * What the device running the workflow says is still to come, off the folder's marker (docs/03
     * "Recordings from other devices"): the comma-joined step types, or null for nothing. Remote rows and verified jobless local copies have one; existing local job rows remain authoritative.
     *
     * @return true when the row changed, so an unchanged marker does not wake every ledger on
     * `recordings.observe()` once a pass.
     */
    suspend fun setRemotePending(recordingId: String, pending: String?): Boolean = locked {
        val row = queries.selectRecordingById(recordingId).executeAsOneOrNull() ?: return@locked false
        if ((row.remote != 1L && row.drive_synced != 1L) || row.remote_pending == pending) return@locked false
        queries.updateRemotePending(pending, recordingId)
        true
    }

    /** Whether any other device is still uploading or still has steps to run — what [RemoteRecordings]
     * asks to decide how often to look (docs/03 "Recordings from other devices"). */
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
            forgetPending(recordingId)
            queries.deleteStepRunsByRecording(recordingId)
            queries.deleteJobsByRecording(recordingId)
            queries.deletePartsByRecording(recordingId)
            queries.deleteRecording(recordingId)
            directories.resolve(row.dir)
        } ?: return@locked false
        deps.fileSystem.deleteRecursively(removed, mustExist = false)
        true
    }

    /**
     * docs/03 "Recordings from other devices": the folders a pull must not adopt, by recording id. Written by
     * [delete] when the user kept the Drive folder ("Delete local only"): the row is gone but the folder is
     * still listed, and without this the next pull would put the recording straight back.
     */
    suspend fun ignored(): Map<String, String> = locked {
        queries.kvSelectPrefix(IGNORED_PREFIX).executeAsList().associate { it.key.removePrefix(IGNORED_PREFIX) to it.value_ }
    }

    /** The folder is gone from Drive, so there is nothing left to keep out. */
    suspend fun unignore(recordingId: String): Unit = locked { queries.kvDelete(IGNORED_PREFIX + recordingId) }

    /**
     * "Disconnect": a device wiped of its recordings starts over with what Drive has. Only the folders of
     * [kind] are forgotten — disconnecting Drive says nothing about the iCloud folder (docs/03 "Storage location").
     */
    suspend fun clearIgnored(kind: StorageKind): Unit = locked {
        queries.kvSelectPrefix(IGNORED_PREFIX).executeAsList()
            .filter { StorageKind.ofId(it.value_) == kind }
            .forEach { queries.kvDelete(it.key) }
    }

    /**
     * The detail screen's rename (docs/03 "Titles"): any finalized recording, this device's own or an
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
     * docs/03 "Metadata": a moment marked on this device — the recorder's button, a tile, the popover, an App
     * Intent — written into the row and `meta.json` at once. Cheap and safe from any thread: one
     * locked write. A mark within [Highlight.MERGE_SEC] of one already there is the same mark, so a
     * double tap adds one.
     *
     * While the recording is still running nothing leaves the device (the meta goes up with the
     * upload). Marked on a finished recording, it is also a pending write for Drive, like
     * [setHighlights].
     *
     * @return true when a mark was added; false for a repeat, a full list ([Highlight.MAX]), a time
     * before the start, or a recording that is not this device's.
     */
    @Throws(Throwable::class)
    suspend fun addHighlight(recordingId: String, atSec: Double): Boolean = locked {
        val record = record(recordingId) ?: return@locked false
        val current = record.meta.highlights
        if (record.remote || !atSec.isFinite() || atSec < 0 || current.size >= Highlight.MAX) return@locked false
        if (current.any { kotlin.math.abs(it.atSec - atSec) < Highlight.MERGE_SEC }) return@locked false
        writeHighlights(record, Highlight.normalize(current.map { it.atSec } + atSec))
        true
    }

    /**
     * The detail screen's highlight editor: the whole list at once, normalized ([Highlight.normalize]).
     * Any recording this device lists — its own or another device's (docs/03 "Recordings from other
     * devices") — except a placeholder still being uploaded elsewhere. Written here at once, and carried
     * to the folder's `meta.json` by [RemoteRecordings.pushMeta].
     *
     * @return false when there is nothing to write to.
     */
    @Throws(Throwable::class)
    suspend fun setHighlights(recordingId: String, atSecs: List<Double>): Boolean = locked {
        val record = record(recordingId) ?: return@locked false
        if (record.remoteUploading) return@locked false
        val highlights = Highlight.normalize(atSecs)
        if (highlights != record.meta.highlights) writeHighlights(record, highlights)
        true
    }

    private fun writeHighlights(record: RecordingRecord, highlights: List<Highlight>) {
        val meta = record.meta.copy(highlights = highlights)
        db.transaction {
            writeMeta(meta)
            // What is still being recorded goes up with its upload; anything later has to be sent.
            if (meta.status != RecordingStatus.RECORDING) queries.kvSet(META_PREFIX + record.id, pendingStamp())
        }
        MetaWriter.write(deps.fileSystem, record.dir, meta)
        deps.logger.log(
            Logger.Level.INFO,
            "rec.highlights",
            mapOf("recordingId" to record.id, "count" to highlights.size),
        )
    }

    /** Recordings whose `meta.json` changed here after it went up, by id, with the stamp of that change. */
    suspend fun pendingMeta(): Map<String, String> = locked {
        queries.kvSelectPrefix(META_PREFIX).executeAsList().associate { it.key.removePrefix(META_PREFIX) to it.value_ }
    }

    /** The meta push landed. Cleared only if no newer change was written over it meanwhile. */
    suspend fun metaPushed(recordingId: String, stamp: String): Unit = locked {
        queries.kvDeleteIfValue(META_PREFIX + recordingId, stamp)
    }

    /**
     * docs/08 "Editing": a transcript edited here that the recording's folder has not received yet, by id,
     * with the stamp of the edit. Written by the editor, cleared by [transcriptPushed].
     */
    suspend fun pendingTranscripts(): Map<String, String> = locked {
        queries.kvSelectPrefix(TRANSCRIPT_PREFIX).executeAsList()
            .associate { it.key.removePrefix(TRANSCRIPT_PREFIX) to it.value_ }
    }

    internal suspend fun transcriptPending(recordingId: String): String = locked {
        pendingStamp().also { queries.kvSet(TRANSCRIPT_PREFIX + recordingId, it) }
    }

    suspend fun transcriptPushed(recordingId: String, stamp: String): Unit = locked {
        queries.kvDeleteIfValue(TRANSCRIPT_PREFIX + recordingId, stamp)
    }

    /**
     * docs/08 "Editing": which version of a recording's transcript this device last took in, as the
     * folder's `transcriptAt` stamp said — what a pull compares against to know a newer one is there.
     */
    internal suspend fun transcriptSeen(): Map<String, String> = locked {
        queries.kvSelectPrefix(SEEN_PREFIX).executeAsList().associate { it.key.removePrefix(SEEN_PREFIX) to it.value_ }
    }

    internal suspend fun setTranscriptSeen(recordingId: String, version: String): Unit = locked {
        queries.kvSet(SEEN_PREFIX + recordingId, version)
    }

    /** docs/08 "Summaries": the summaries made or edited here that the recording's folder has not received. */
    suspend fun pendingSummaries(): Map<String, String> = locked {
        queries.kvSelectPrefix(SUMMARY_PREFIX).executeAsList()
            .associate { it.key.removePrefix(SUMMARY_PREFIX) to it.value_ }
    }

    internal suspend fun summaryPending(recordingId: String): String = locked {
        pendingStamp().also { queries.kvSet(SUMMARY_PREFIX + recordingId, it) }
    }

    suspend fun summaryPushed(recordingId: String, stamp: String): Unit = locked {
        queries.kvDeleteIfValue(SUMMARY_PREFIX + recordingId, stamp)
    }

    /** The folder's `summaryAt` this device took in, per recording. */
    internal suspend fun summarySeen(): Map<String, String> = locked {
        queries.kvSelectPrefix(SUMMARY_SEEN_PREFIX).executeAsList().associate { it.key.removePrefix(SUMMARY_SEEN_PREFIX) to it.value_ }
    }

    internal suspend fun setSummarySeen(recordingId: String, version: String): Unit = locked {
        queries.kvSet(SUMMARY_SEEN_PREFIX + recordingId, version)
    }

    /**
     * One writer of a recording's summary at a time — a new one, an edit, a copy read from its folder — so a
     * copy read from the folder never lands over an edit made meanwhile (docs/08 "Summaries").
     */
    private val summaryWrites = Mutex()

    internal suspend fun <T> summaryWrite(block: suspend () -> T): T = summaryWrites.withLock { block() }

    private fun forgetPending(recordingId: String) {
        for (prefix in listOf(TITLE_PREFIX, META_PREFIX, TRANSCRIPT_PREFIX, SEEN_PREFIX, SUMMARY_PREFIX, SUMMARY_SEEN_PREFIX)) {
            queries.kvDelete(prefix + recordingId)
        }
    }

    /** Unique per write, so a push that read an older change never clears a newer one. */
    private fun pendingStamp(): String = kotlin.random.Random.nextLong().toULong().toString(16)

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

    /**
     * `drive.upload` found or made the recording's folder (docs/03 "Metadata" — `drive`): the row and
     * `meta.json` learn its id and link before the meta itself goes up, so the copy in Drive carries
     * them too. Idempotent — a re-run with the same folder writes nothing.
     *
     * @return true when the meta changed.
     */
    suspend fun setDriveFolder(recordingId: String, folderId: String, folderUrl: String): Boolean = locked {
        val record = record(recordingId) ?: return@locked false
        val location = DriveLocation(folderId, folderUrl)
        if (record.meta.drive == location) return@locked false
        val meta = record.meta.copy(drive = location)
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

    /** Audio inputs change independently of transcription; title-only edits keep playback intact. */
    fun observeAudio(recordingId: String): Flow<RecordingRecord?> = observe().map { get(recordingId) }
        .distinctUntilChanged { before, after ->
            before?.dir == after?.dir && before?.meta?.status == after?.meta?.status &&
                before?.meta?.parts == after?.meta?.parts && before?.meta?.tracks == after?.meta?.tracks
        }

    /** Drops the row, its parts and the whole directory — the orphan purge, and nothing else. */
    suspend fun delete(recordingId: String) {
        delete(recordingId, deleteDrive = false)
    }

    /**
     * "Delete recording" (docs/03): the parts, `meta.json`, the result files and the directory, plus the
     * `recording`, `part`, `job` and `step_run` rows — nothing cascades, so every table is named.
     *
     * [deleteDrive] is the other half of the dialog, and the one whose default is off: the files
     * in Drive are the user's own and something downstream may already have consumed the folder,
     * so the irreversible choice is never the default one. A Drive that refuses does not hold up
     * the local deletion — once the local copy is gone there is nothing left to retry from — and
     * says so through [DeleteResult.Deleted.driveError] instead.
     *
     * A job of the recording that is running right now — an upload — is stopped first
     * (`Executor.stopping`): its run is cancelled, no new one starts while this runs, and so a
     * `RUNNING` job goes with the recording like every other status. The dialog that asked has
     * already said what audio exists only here; deleting it is the user's answer. Without an
     * [executor] (the watch, a test of the repository alone) nothing can stop a run, and a
     * `RUNNING` job is [DeleteResult.Busy] with nothing touched.
     *
     * Keeping the Drive folder leaves a folder that a pull would list and adopt back (docs/03 "Recordings from other devices"), so that choice is remembered ([ignored]) in the same transaction.
     *
     * Every row deletion is one transaction, and `JobStore.claimRunning` is another: SQLite has a
     * single writer, so one of the two commits first and the other sees it — a claim that comes
     * second finds nothing to run. The files and Drive come after the commit, when nothing can
     * still claim them — the files without leaving the locked pass, so that no cancellation can
     * strand a directory the rows no longer name.
     */
    suspend fun delete(recordingId: String, deleteDrive: Boolean): DeleteResult {
        val executor = executor
        val remove: suspend () -> DeleteResult = {
            localTranscription?.deleting(recordingId) { deleteInternal(recordingId, deleteDrive, executor != null) }
                ?: deleteInternal(recordingId, deleteDrive, executor != null)
        }
        return executor?.stopping(recordingId, remove) ?: remove()
    }

    /** [stopped]: no run of the recording is in flight or can start (`Executor.stopping`). */
    private suspend fun deleteInternal(recordingId: String, deleteDrive: Boolean, stopped: Boolean): DeleteResult {
        val removal = locked {
            val outcome = db.transactionWithResult {
                val row = queries.selectRecordingById(recordingId).executeAsOneOrNull()
                    ?: return@transactionWithResult Removal.NotFound
                val running = queries.selectJobsByRecording(recordingId).executeAsList()
                    .any { it.status == JobStatus.RUNNING.name }
                if (running && !stopped) return@transactionWithResult Removal.Busy
                val folderId = driveFolderId(recordingId) ?: row.drive_folder_id
                if (!deleteDrive && folderId != null) queries.kvSet(IGNORED_PREFIX + recordingId, folderId)
                // A rename, highlights or an edit that never reached Drive go with the recording: pushed
                // later, they would land on whatever another device has since put in the folder.
                forgetPending(recordingId)
                queries.syncDelete("processing/recording/" + recordingId)
                queries.deleteStepRunsByRecording(recordingId)
                queries.deleteJobsByRecording(recordingId)
                queries.deletePartsByRecording(recordingId)
                queries.deleteRecording(recordingId)
                Removal.Done(directories.resolve(row.dir), if (deleteDrive) folderId else null)
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
     * The end of an import (docs/03 "Naming rules"): every transcoded part moved in from where it was
     * staged under its part name, registered, and the recording finalized — one locked pass, so no scan
     * of the directory ever sees some of the parts of an import that is not complete.
     */
    internal suspend fun completeImport(
        recordingId: String,
        staged: List<Pair<Path, Part>>,
        endedAt: Instant,
        durationSec: Double,
    ): Unit = locked {
        val record = requireRecord(recordingId)
        require(record.importing) { "'$recordingId' is not an import in progress" }
        deps.fileSystem.createDirectories(record.dir)
        staged.forEach { (from, part) -> deps.fileSystem.atomicMove(from, record.dir / part.file) }
        val meta = record.meta.copy(
            parts = staged.map { it.second },
            endedAt = endedAt.isoUtc(),
            durationSec = durationSec,
            status = RecordingStatus.FINALIZED,
        )
        db.transaction {
            meta.parts.forEach { insertPart(recordingId, it) }
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
    ): RecordingRecord {
        val finalized = locked {
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
        afterCapture(recordingId)
        return finalized
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
     * @param title the name to give it; null leaves the one it has (the dialog's "Skip").
     * @param participants the count the user picked; null is "Unknown" and leaves the meta alone.
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
                directories.resolve(it.dir),
                it.drive_folder_id,
                it.remote == 1L,
                pendingTypes(it.remote_pending),
                it.drive_synced == 1L,
            )
        }
    }

    /**
     * Deletes the files of the parts a purge has claimed (`deleted = 1`, written by
     * `JobStore.claimPurge`); `meta.json` and the rows stay (docs/03 "Local storage"). Idempotent, and
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

    /**
     * The peaks a shell decoded for this recording before ([WaveformPeaks]), or null when there are
     * none, the file is from another version, or the recording is gone. The shell checks the count
     * against the parts it would decode and decodes again when they disagree.
     */
    @Throws(Throwable::class)
    suspend fun waveform(recordingId: String): List<Float>? = locked {
        val record = record(recordingId) ?: return@locked null
        val file = record.dir / WaveformPeaks.FILE
        if (!deps.fileSystem.exists(file)) return@locked null
        WaveformPeaks.decode(deps.fileSystem.read(file) { readByteArray() })
    }

    /**
     * Keeps [peaks] beside the recording's parts. Written whole and moved into place, under the lock
     * [delete] takes, so a recording deleted meanwhile gets no file.
     */
    @Throws(Throwable::class)
    suspend fun saveWaveform(recordingId: String, peaks: List<Float>): Unit = locked {
        val record = record(recordingId) ?: return@locked
        val file = record.dir / WaveformPeaks.FILE
        val temp = record.dir / "${WaveformPeaks.FILE}.tmp"
        deps.fileSystem.createDirectories(record.dir)
        deps.fileSystem.write(temp) { write(WaveformPeaks.encode(peaks)) }
        deps.fileSystem.atomicMove(temp, file)
    }

    /**
     * The summary saved for this recording (docs/08 "Summaries"), as the JSON the core wrote, or null.
     * Like the waveform it sits beside the parts, is not uploaded, survives the 7-day audio cleanup and
     * goes with the recording.
     */
    @Throws(Throwable::class)
    suspend fun summary(recordingId: String): String? = locked {
        val record = record(recordingId) ?: return@locked null
        val file = record.dir / SUMMARY_FILE
        if (!deps.fileSystem.exists(file)) return@locked null
        deps.fileSystem.read(file) { readUtf8() }
    }

    /** Written whole and moved into place under the lock [delete] takes, as [saveWaveform] is. */
    @Throws(Throwable::class)
    suspend fun saveSummary(recordingId: String, json: String): Unit = locked {
        val record = record(recordingId) ?: return@locked
        val file = record.dir / SUMMARY_FILE
        val temp = record.dir / "$SUMMARY_FILE.tmp"
        deps.fileSystem.createDirectories(record.dir)
        deps.fileSystem.write(temp) { writeUtf8(json) }
        deps.fileSystem.atomicMove(temp, file)
    }

    private suspend fun <T> locked(body: () -> T): T = withContext(deps.io) { mutex.withLock { body() } }

    private fun record(id: String): RecordingRecord? =
        queries.selectRecordingById(id).executeAsOneOrNull()?.let {
            RecordingRecord(
                it.id,
                recJson.decodeFromString(it.meta_json),
                directories.resolve(it.dir),
                it.drive_folder_id,
                it.remote == 1L,
                pendingTypes(it.remote_pending),
                it.drive_synced == 1L,
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
         * a watch's (docs/03 "Local storage"), since the shell's `{base}` layout is the shell's. */
        const val RECORDINGS: String = "recordings"

        /** `kv` rows: `remote/ignored/{recordingId}` → the Drive folder id a pull must skip. */
        private const val IGNORED_PREFIX: String = "remote/ignored/"

        /** `kv` rows: `title/pending/{recordingId}` → the title Drive still has to be told. */
        private const val TITLE_PREFIX: String = "title/pending/"

        /** `kv` rows: `meta/pending/{recordingId}` → a stamp; the folder's `meta.json` is older than the row. */
        private const val META_PREFIX: String = "meta/pending/"

        /** `kv` rows: `transcript/pending/{recordingId}` → a stamp; an edit the folder has not received. */
        private const val TRANSCRIPT_PREFIX: String = "transcript/pending/"

        /** `kv` rows: `transcript/seen/{recordingId}` → the folder's `transcriptAt` this device took in. */
        private const val SEEN_PREFIX: String = "transcript/seen/"

        /** Beside the parts: the summary of this recording, `Summary` as JSON (docs/08 "Summaries"). */
        private const val SUMMARY_FILE: String = "summary.v1.json"

        /** `kv` rows: `summary/pending/{recordingId}` → a stamp; a summary the folder has not received. */
        private const val SUMMARY_PREFIX: String = "summary/pending/"

        /** `kv` rows: `summary/seen/{recordingId}` → the folder's `summaryAt` this device took in. */
        private const val SUMMARY_SEEN_PREFIX: String = "summary/seen/"
    }
}
