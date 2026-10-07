package recly.core.storage

import kotlinx.serialization.json.JsonObject
import okio.Path
import recly.core.drive.DriveApi
import recly.core.drive.DriveFile
import recly.core.drive.DriveFileMeta
import recly.core.drive.UploadState
import recly.core.platform.CoreDeps

/**
 * Every storage behind one [CloudFiles]: a call that names a file or a folder goes to the storage
 * the id belongs to ([StorageKind.ofId]), so a folder id kept on a row reaches the right one even
 * after the setting changed (docs/03 "Storage location"). The calls that name nothing — a listing, the
 * root, the upload sizes — are Drive's; a caller that wants another one's asks [forKind] for it.
 *
 * [icloud] is null where there is no iCloud container: Android, Windows, the watches, and an Apple
 * build without the entitlement. [folder] is null where the shell offers no local folder: the
 * watches. An id of either reaching such a device fails the way an unreachable
 * storage does ([StorageUnavailableException]).
 */
class CloudStorage(
    private val drive: CloudFiles,
    private val icloud: CloudFiles?,
    private val folder: CloudFiles? = null,
) : CloudFiles {
    override val multipartLimit: Long get() = drive.multipartLimit
    override val orderedUploads: Boolean get() = drive.orderedUploads
    override val rootId: String get() = drive.rootId

    override fun forKind(kind: StorageKind): CloudFiles? = when (kind) {
        StorageKind.DRIVE -> drive
        StorageKind.ICLOUD -> icloud
        StorageKind.FOLDER -> folder
    }

    private fun at(id: String): CloudFiles {
        val kind = StorageKind.ofId(id)
        return forKind(kind) ?: throw StorageUnavailableException(kind, "no $kind storage on this device")
    }

    override suspend fun createFolder(
        name: String,
        parentId: String,
        description: String?,
        appProperties: Map<String, String>,
    ): DriveFile = at(parentId).createFolder(name, parentId, description, appProperties)

    override suspend fun completeFolder(folder: DriveFile, description: String?, appProperties: Map<String, String>) =
        at(folder.id).completeFolder(folder, description, appProperties)

    override suspend fun findChildren(parentId: String, name: String, mimeType: String?): List<DriveFile> =
        at(parentId).findChildren(parentId, name, mimeType)

    override suspend fun recordingFolders(): List<JsonObject> = drive.recordingFolders()

    override suspend fun children(parentId: String): List<DriveFile> = at(parentId).children(parentId)

    override suspend fun getFile(id: String, fields: String): JsonObject? = at(id).getFile(id, fields)

    override suspend fun download(id: String): ByteArray = at(id).download(id)

    override suspend fun delete(id: String) = at(id).delete(id)

    override suspend fun multipartUpload(meta: DriveFileMeta, bytes: ByteArray): DriveFile =
        at(meta.parents.first()).multipartUpload(meta, bytes)

    override suspend fun updateDescription(fileId: String, description: String) =
        at(fileId).updateDescription(fileId, description)

    override suspend fun updateAppProperties(fileId: String, appProperties: Map<String, String>) =
        at(fileId).updateAppProperties(fileId, appProperties)

    override suspend fun updateMedia(
        fileId: String,
        bytes: ByteArray,
        mimeType: String,
        appProperties: Map<String, String>,
    ): DriveFile = at(fileId).updateMedia(fileId, bytes, mimeType, appProperties)

    override suspend fun uploadResumable(
        meta: DriveFileMeta,
        path: Path,
        total: Long,
        state: UploadState?,
        saveState: suspend (UploadState) -> Unit,
    ): DriveFile = at(meta.parents.first()).uploadResumable(meta, path, total, state, saveState)

    override suspend fun settled(fileIds: List<String>): Boolean =
        fileIds.groupBy(StorageKind::ofId).values.all { ids -> at(ids.first()).settled(ids) }

    companion object {
        /**
         * Drive always; iCloud where the shell handed the core a container, and a local folder where
         * it handed one (docs/01 `CoreDeps`).
         */
        fun of(deps: CoreDeps): CloudStorage = CloudStorage(
            DriveApi(deps),
            deps.ubiquity?.let { ICloudFiles(it, deps) },
            deps.localFolder?.let { FolderFiles(it, deps) },
        )
    }
}
