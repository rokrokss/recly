package recly.core.storage

import kotlinx.serialization.json.JsonObject
import okio.Path
import recly.core.drive.DriveFile
import recly.core.drive.DriveFileMeta
import recly.core.drive.UploadState

/**
 * The file operations the steps and the shared list need from the user's cloud storage
 * (docs/03 "Storage location"). Shaped after Drive v3 because Drive came first: a folder has a
 * `description` (the recording's title) and `appProperties` (its id and the pending marker), and a
 * file has an md5. [ICloudFiles] keeps the same shapes in a file of its own.
 *
 * [recly.core.drive.DriveApi] is Google Drive, [ICloudFiles] the app's iCloud Drive folder, and
 * [CloudStorage] routes an id to the one it belongs to ([StorageKind.ofId]).
 */
interface CloudFiles {
    /** Up to this size a file goes up in one request with its bytes in memory; bigger, from its path. */
    val multipartLimit: Long

    /**
     * Whether another device sees a folder's files in the order they were written. Drive does —
     * `meta.json` goes up last, so a folder that has it is complete (docs/03) — and iCloud Drive
     * does not, so a reader there checks the parts the meta names instead.
     */
    val orderedUploads: Boolean

    /** The folder a rendered `folder` template is walked down from. */
    val rootId: String

    /** The storage of [kind] behind this one, or null when it has none of that kind. */
    fun forKind(kind: StorageKind): CloudFiles?

    suspend fun createFolder(
        name: String,
        parentId: String,
        description: String? = null,
        appProperties: Map<String, String> = emptyMap(),
    ): DriveFile

    /**
     * A recording folder found by name rather than made: on Drive its description and properties
     * were written with it, atomically. An iCloud folder whose making stopped before its property
     * file was written gets that file here.
     */
    suspend fun completeFolder(folder: DriveFile, description: String?, appProperties: Map<String, String>) = Unit

    /** Every child of [parentId] with this name. Drive allows duplicates, so this is a list. */
    suspend fun findChildren(parentId: String, name: String, mimeType: String? = null): List<DriveFile>

    /** For the callers where any match will do — a folder, which is never made twice. */
    suspend fun findChild(parentId: String, name: String, mimeType: String? = null): DriveFile? =
        findChildren(parentId, name, mimeType).firstOrNull()

    /**
     * Every recording folder, from any device: the folders stamped with a `recordingId` (ADR-014),
     * each as Drive lists one — `id`, `name`, `appProperties`, `createdTime`, `description`.
     */
    suspend fun recordingFolders(): List<JsonObject>

    /** Every file in a folder. */
    suspend fun children(parentId: String): List<DriveFile>

    /** Null when the file or folder is gone. */
    suspend fun getFile(id: String, fields: String): JsonObject?

    suspend fun download(id: String): ByteArray

    suspend fun delete(id: String)

    suspend fun multipartUpload(meta: DriveFileMeta, bytes: ByteArray): DriveFile

    /** The folder's `description` — where a recording's title lives (ADR-014). */
    suspend fun updateDescription(fileId: String, description: String)

    /** Merges keys into a folder's `appProperties`; the ones it was made with stay. */
    suspend fun updateAppProperties(fileId: String, appProperties: Map<String, String>)

    /** Replaces an existing file's content, leaving its id and parents alone. */
    suspend fun updateMedia(fileId: String, bytes: ByteArray, mimeType: String): DriveFile

    suspend fun uploadResumable(
        meta: DriveFileMeta,
        path: Path,
        total: Long,
        state: UploadState?,
        saveState: suspend (UploadState) -> Unit,
    ): DriveFile

    /**
     * Whether the files a step wrote have reached the cloud. Drive answers a write only once it
     * holds the bytes, so for Drive this is always true; iCloud Drive takes them later, in the
     * system's own upload, and says so file by file.
     *
     * Throws a [recly.core.job.StepFailure] with `needsSpace` when the cloud would not take them
     * for lack of space.
     */
    suspend fun settled(fileIds: List<String>): Boolean
}

/**
 * The storage cannot be reached from this device right now: no iCloud account, iCloud Drive turned
 * off for the app, or a build without the iCloud entitlement. Waiting is what fixes it, not a
 * retry — the runners turn it into a wait that spends no attempt.
 */
class StorageUnavailableException(val kind: StorageKind, message: String) : Exception(message)
