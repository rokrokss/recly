package recly.core.storage

import kotlin.random.Random
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.toByteString
import okio.Path
import recly.core.drive.DriveApi
import recly.core.drive.DriveFile
import recly.core.drive.DriveFileMeta
import recly.core.drive.DriveNotFound
import recly.core.drive.UploadState
import recly.core.platform.CoreDeps

/**
 * docs/03 "Storage location" — Local folder: the folder the user picked on this device, held to the same
 * shapes as Drive so the upload step, the transcript and the titles run unchanged on top of it.
 *
 * - An id is [StorageKind.FOLDER_PREFIX] plus the path under the picked folder.
 * - The folder is this device's alone: it is never listed ([recordingFolders] is empty), so nothing
 *   is kept on a folder — no description, no `appProperties`, no property file next to the
 *   recording. The title is in the `meta.json` the folder already has.
 * - "Uploaded" is the copy itself: [settled] is true as soon as a file is in place.
 *
 * Every call asks the folder first; one that is not picked, or cannot be reached (a removed drive,
 * a revoked Android grant), is [StorageUnavailableException].
 */
class FolderFiles(
    private val folder: LocalFolder,
    private val deps: CoreDeps,
) : CloudFiles {
    /** Every file goes in from its path: a copy, never bytes held in memory. */
    override val multipartLimit: Long = 0

    /** Never read: a local folder is not listed. */
    override val orderedUploads: Boolean = true

    override val rootId: String = StorageKind.FOLDER_PREFIX

    override fun forKind(kind: StorageKind): CloudFiles? = if (kind == StorageKind.FOLDER) this else null

    override suspend fun createFolder(
        name: String,
        parentId: String,
        description: String?,
        appProperties: Map<String, String>,
    ): DriveFile {
        ready()
        val path = join(pathOf(parentId), segment(name))
        folder.makeDirectories(path)
        return DriveFile(idOf(path), name, md5 = null, webViewLink = null)
    }

    override suspend fun findChildren(parentId: String, name: String, mimeType: String?): List<DriveFile> {
        ready()
        val path = join(pathOf(parentId), segment(name))
        if (mimeType == DriveApi.FOLDER_MIME) {
            return if (folder.isDirectory(path)) listOf(DriveFile(idOf(path), name, null, null)) else emptyList()
        }
        val size = folder.size(path) ?: return emptyList()
        return listOf(DriveFile(idOf(path), name, folder.md5(path), null, size))
    }

    /** This device's alone: another device never reads it, so there is nothing to list. */
    override suspend fun recordingFolders(): List<JsonObject> = emptyList()

    /** Only a listing reads a folder's children, and a local folder is not listed. */
    override suspend fun children(parentId: String): List<DriveFile> = emptyList()

    override suspend fun getFile(id: String, fields: String): JsonObject? {
        ready()
        val path = pathOf(id)
        if (folder.isDirectory(path)) {
            return buildJsonObject {
                put("id", id)
                put("name", nameOf(path))
                put("trashed", false)
            }
        }
        val size = folder.size(path) ?: return null
        return buildJsonObject {
            put("id", id)
            put("name", nameOf(path))
            put("size", size.toString())
            folder.md5(path)?.let { put("md5Checksum", it) }
        }
    }

    override suspend fun download(id: String): ByteArray {
        ready()
        val temp = temp()
        try {
            if (!folder.exportFile(pathOf(id), temp.toString())) {
                throw DriveNotFound("folder: '${pathOf(id)}' is gone")
            }
            return deps.fileSystem.read(temp) { readByteArray() }
        } finally {
            deps.fileSystem.delete(temp, mustExist = false)
        }
    }

    override suspend fun delete(id: String) {
        ready()
        folder.delete(pathOf(id))
    }

    override suspend fun multipartUpload(meta: DriveFileMeta, bytes: ByteArray): DriveFile {
        ready()
        val path = join(pathOf(meta.parents.first()), segment(meta.name))
        put(path, bytes)
        return DriveFile(idOf(path), meta.name, bytes.toByteString().md5().hex(), null, bytes.size.toLong())
    }

    /** The title lives in `meta.json`, which the rename rewrites; a folder that is never listed keeps nothing. */
    override suspend fun updateDescription(fileId: String, description: String) = Unit

    /** The pending marker is for another device's list, and a local folder is in none. */
    override suspend fun updateAppProperties(fileId: String, appProperties: Map<String, String>) = Unit

    override suspend fun updateMedia(fileId: String, bytes: ByteArray, mimeType: String): DriveFile {
        ready()
        val path = pathOf(fileId)
        put(path, bytes)
        return DriveFile(fileId, nameOf(path), bytes.toByteString().md5().hex(), null, bytes.size.toLong())
    }

    override suspend fun uploadResumable(
        meta: DriveFileMeta,
        path: Path,
        total: Long,
        state: UploadState?,
        saveState: suspend (UploadState) -> Unit,
    ): DriveFile {
        ready()
        val target = join(pathOf(meta.parents.first()), segment(meta.name))
        folder.importFile(path.toString(), target)
        // Read back from the folder, so a copy that did not land whole fails the step's md5 check.
        val size = folder.size(target) ?: throw DriveNotFound("folder: '$target' did not land")
        return DriveFile(idOf(target), meta.name, folder.md5(target), null, size)
    }

    /** The copy is the upload: a file in the folder is where it is going to be. */
    override suspend fun settled(fileIds: List<String>): Boolean = true

    private suspend fun ready() {
        if (!folder.available()) {
            throw StorageUnavailableException(StorageKind.FOLDER, "the local folder cannot be used")
        }
    }

    /** Bytes as a file: through a local temp and [LocalFolder.importFile]. */
    private suspend fun put(path: String, bytes: ByteArray) {
        val temp = temp()
        try {
            deps.fileSystem.write(temp) { write(bytes) }
            folder.importFile(temp.toString(), path)
        } finally {
            deps.fileSystem.delete(temp, mustExist = false)
        }
    }

    private fun temp(): Path {
        val dir = deps.dataDir / TEMP_DIR
        deps.fileSystem.createDirectories(dir)
        return dir / "${Random.nextLong().toULong().toString(16)}.tmp"
    }

    private fun pathOf(id: String): String {
        require(id.startsWith(StorageKind.FOLDER_PREFIX)) { "not a local folder id: '$id'" }
        return id.removePrefix(StorageKind.FOLDER_PREFIX)
    }

    private fun idOf(path: String): String = StorageKind.FOLDER_PREFIX + path

    private fun join(parent: String, name: String): String = if (parent.isEmpty()) name else "$parent/$name"

    private fun nameOf(path: String): String = path.substringAfterLast('/')

    /** A name is one path segment: nothing may climb out of the folder it is made in. */
    private fun segment(name: String): String {
        require(name.isNotEmpty() && '/' !in name && '\\' !in name && name != "." && name != "..") {
            "not a file name: '$name'"
        }
        return name
    }

    private companion object {
        const val TEMP_DIR = "folder-tmp"
    }
}
