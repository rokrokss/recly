@file:OptIn(ExperimentalTime::class)

package recly.core.storage

import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.toByteString
import okio.Path
import recly.core.drive.DriveApi
import recly.core.drive.DriveFile
import recly.core.drive.DriveFileMeta
import recly.core.drive.DriveNotFound
import recly.core.drive.UploadState
import recly.core.job.StepFailure
import recly.core.message.CoreMessage
import recly.core.model.isoUtc
import recly.core.platform.CoreDeps

/**
 * docs/03 "저장 위치" — iCloud (ADR-024): the app's iCloud Drive folder, held to the same shapes as
 * Drive so the upload step, the shared list and the titles run unchanged on top of it.
 *
 * - An id is [StorageKind.ICLOUD_PREFIX] plus the path under the container's `Documents`, so a folder
 *   is found again by its path and a file by its name in it.
 * - What Drive keeps on a folder — its `description` (the title) and `appProperties` (the recording
 *   id and the pending marker) — is a file in the folder, `{folder}.folder.json`
 *   ([PROPERTIES_SUFFIX]), with a `createdTime` of its own because the listing is what reads it.
 * - "Uploaded" is a local copy into the container followed by the system's own upload: [settled]
 *   says when iCloud holds the bytes, and an account out of space says so there.
 * - Nothing arrives in order (iCloud syncs file by file), so [orderedUploads] is false and a reader
 *   checks the parts a `meta.json` names before it trusts the folder.
 *
 * Every call asks the container first; one that cannot be used is [StorageUnavailableException].
 */
class ICloudFiles(
    private val container: UbiquityContainer,
    private val deps: CoreDeps,
) : CloudFiles {
    /** Every file goes in from its path: a copy in the container, never bytes held in memory. */
    override val multipartLimit: Long = 0

    override val orderedUploads: Boolean = false

    override val rootId: String = StorageKind.ICLOUD_PREFIX

    override fun forKind(kind: StorageKind): CloudFiles? = if (kind == StorageKind.ICLOUD) this else null

    private val mutex = Mutex()
    private val properties = mutableMapOf<String, Cached<JsonObject?>>()
    private val digests = mutableMapOf<String, Cached<String>>()
    private var listing: Pair<Instant, List<UbiquityFile>>? = null

    override suspend fun createFolder(
        name: String,
        parentId: String,
        description: String?,
        appProperties: Map<String, String>,
    ): DriveFile {
        ready()
        val path = join(pathOf(parentId), segment(name))
        container.makeDirectories(path)
        if (description != null || appProperties.isNotEmpty()) writeProperties(path, description, appProperties)
        return DriveFile(idOf(path), name, md5 = null, webViewLink = null)
    }

    override suspend fun completeFolder(folder: DriveFile, description: String?, appProperties: Map<String, String>) {
        if (description == null && appProperties.isEmpty()) return
        ready()
        writeProperties(pathOf(folder.id), description, appProperties)
    }

    override suspend fun findChildren(parentId: String, name: String, mimeType: String?): List<DriveFile> {
        ready()
        val path = join(pathOf(parentId), segment(name))
        if (mimeType == DriveApi.FOLDER_MIME) {
            return if (container.isDirectory(path)) listOf(DriveFile(idOf(path), name, null, null)) else emptyList()
        }
        val file = fileAt(path) ?: return emptyList()
        return listOf(DriveFile(idOf(path), name, md5Of(file), null, file.size))
    }

    override suspend fun recordingFolders(): List<JsonObject> {
        ready()
        return files().filter { isProperties(it.path) }.mapNotNull { file ->
            val folder = parentOf(file.path)
            val stored = propertiesOf(file) ?: return@mapNotNull null
            val appProperties = stored[APP_PROPERTIES] as? JsonObject ?: return@mapNotNull null
            if (appProperties[RECORDING_ID] == null) return@mapNotNull null
            buildJsonObject {
                put("id", idOf(folder))
                put("name", nameOf(folder))
                put(APP_PROPERTIES, appProperties)
                stored[CREATED_TIME]?.let { put(CREATED_TIME, it) }
                stored[DESCRIPTION]?.let { put(DESCRIPTION, it) }
            }
        }
    }

    override suspend fun children(parentId: String): List<DriveFile> {
        ready()
        val folder = pathOf(parentId)
        return files()
            .filter { parentOf(it.path) == folder && !isProperties(it.path) }
            .map { DriveFile(idOf(it.path), nameOf(it.path), md5 = null, webViewLink = null, size = it.size) }
    }

    override suspend fun getFile(id: String, fields: String): JsonObject? {
        ready()
        val path = pathOf(id)
        if (container.isDirectory(path)) {
            return buildJsonObject {
                put("id", id)
                put("name", nameOf(path))
                put("trashed", false)
            }
        }
        val file = fileAt(path) ?: return null
        return buildJsonObject {
            put("id", id)
            put("name", nameOf(path))
            put("size", file.size.toString())
            md5Of(file)?.let { put("md5Checksum", it) }
        }
    }

    override suspend fun download(id: String): ByteArray {
        ready()
        val temp = temp()
        try {
            if (!container.exportFile(pathOf(id), temp.toString(), DOWNLOAD_WAIT_SEC)) {
                throw DriveNotFound("icloud: '${pathOf(id)}' is gone")
            }
            return deps.fileSystem.read(temp) { readByteArray() }
        } finally {
            deps.fileSystem.delete(temp, mustExist = false)
        }
    }

    override suspend fun delete(id: String) {
        ready()
        container.delete(pathOf(id))
        forget()
    }

    override suspend fun multipartUpload(meta: DriveFileMeta, bytes: ByteArray): DriveFile {
        ready()
        val path = join(pathOf(meta.parents.first()), segment(meta.name))
        put(path, bytes)
        return DriveFile(idOf(path), meta.name, bytes.toByteString().md5().hex(), null, bytes.size.toLong())
    }

    override suspend fun updateDescription(fileId: String, description: String) =
        updateProperties(pathOf(fileId)) { it + (DESCRIPTION to JsonPrimitive(description)) }

    override suspend fun updateAppProperties(fileId: String, appProperties: Map<String, String>) =
        updateProperties(pathOf(fileId)) { stored ->
            val merged = (stored[APP_PROPERTIES] as? JsonObject).orEmpty() + appProperties.mapValues { JsonPrimitive(it.value) }
            stored + (APP_PROPERTIES to JsonObject(merged))
        }

    override suspend fun updateMedia(fileId: String, bytes: ByteArray, mimeType: String): DriveFile {
        ready()
        val path = pathOf(fileId)
        put(path, bytes)
        return DriveFile(fileId, nameOf(path), bytes.toByteString().md5().hex(), null, bytes.size.toLong())
    }

    /** One copy into the container: the system uploads from there, and [settled] says when it has. */
    override suspend fun uploadResumable(
        meta: DriveFileMeta,
        path: Path,
        total: Long,
        state: UploadState?,
        saveState: suspend (UploadState) -> Unit,
    ): DriveFile {
        ready()
        val target = join(pathOf(meta.parents.first()), segment(meta.name))
        container.importFile(path.toString(), target)
        forget()
        // Read back from the container, so a copy that did not land whole fails the step's md5 check.
        val written = fileAt(target) ?: throw DriveNotFound("icloud: '$target' did not land")
        return DriveFile(idOf(target), meta.name, md5Of(written), null, written.size)
    }

    override suspend fun settled(fileIds: List<String>): Boolean {
        ready()
        var all = true
        for (id in fileIds) {
            val path = pathOf(id)
            // Deleted from the folder since this step put it there: the upload starts over.
            val file = fileAt(path) ?: throw DriveNotFound("icloud: '$path' is gone")
            if (file.uploadFailure == UbiquityFile.QUOTA) {
                throw StepFailure(
                    retryable = false,
                    reason = CoreMessage.ICLOUD_STORAGE_FULL.code(detail = path),
                    needsSpace = true,
                )
            }
            if (!file.uploaded) all = false
        }
        return all
    }

    private suspend fun ready() {
        if (!container.available()) {
            throw StorageUnavailableException(StorageKind.ICLOUD, "iCloud Drive is not available to this app")
        }
    }

    /**
     * The container's files. One listing serves a whole pull — which reads every recording folder's
     * children — and a write forgets it ([forget]).
     */
    private suspend fun files(): List<UbiquityFile> {
        val now = deps.clock.now()
        mutex.withLock { listing?.takeIf { now - it.first < LISTING_TTL }?.let { return it.second } }
        val fresh = listed { container.files() }
        mutex.withLock { listing = now to fresh }
        return fresh
    }

    private suspend fun forget() = mutex.withLock { listing = null }

    private suspend fun fileAt(path: String): UbiquityFile? = listed { container.file(path) }

    /**
     * A container that cannot say what it holds — its listing of iCloud is not complete yet, or
     * iCloud went away in the middle — is a wait, as in [ready], not a failure: a step that spent an
     * attempt on it would run out of them on an account that is only slow to list.
     */
    private suspend fun <T> listed(read: suspend () -> T): T = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw StorageUnavailableException(StorageKind.ICLOUD, "iCloud could not say what it holds: ${e.message}")
    }

    /** Bytes as a file: through a local temp and [UbiquityContainer.importFile], so nothing is decoded. */
    private suspend fun put(path: String, bytes: ByteArray) {
        val temp = temp()
        try {
            deps.fileSystem.write(temp) { write(bytes) }
            container.importFile(temp.toString(), path)
        } finally {
            deps.fileSystem.delete(temp, mustExist = false)
        }
        forget()
    }

    private fun temp(): Path {
        val dir = deps.dataDir / TEMP_DIR
        deps.fileSystem.createDirectories(dir)
        return dir / "${Random.nextLong().toULong().toString(16)}.tmp"
    }

    /** md5 of a file's content, read once per version: a waiting upload step asks for it every pass. */
    private suspend fun md5Of(file: UbiquityFile): String? {
        mutex.withLock { digests[file.path]?.takeIf { it.matches(file) }?.let { return it.value } }
        val md5 = container.md5(file.path) ?: return null
        mutex.withLock { digests[file.path] = Cached(file.size, file.modifiedAt, md5) }
        return md5
    }

    /** A folder's property file, read once per version. Null while its content is still on its way. */
    private suspend fun propertiesOf(file: UbiquityFile): JsonObject? {
        mutex.withLock { properties[file.path]?.takeIf { it.matches(file) }?.let { return it.value } }
        val text = container.readText(file.path, PROPERTIES_WAIT_SEC) ?: return null
        val parsed = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        mutex.withLock { properties[file.path] = Cached(file.size, file.modifiedAt, parsed) }
        return parsed
    }

    /**
     * The property file of a folder this device makes. A folder that already has one keeps it:
     * Drive writes a folder's description and properties once, when it makes the folder, and a re-run
     * that finds the folder by name leaves them as they are.
     */
    private suspend fun writeProperties(folder: String, description: String?, appProperties: Map<String, String>) {
        val path = propertiesPath(folder)
        if (fileAt(path) != null) return
        val stored = buildJsonObject {
            put(CREATED_TIME, deps.clock.now().isoUtc())
            description?.let { put(DESCRIPTION, it) }
            put(APP_PROPERTIES, JsonObject(appProperties.mapValues { JsonPrimitive(it.value) }))
        }
        container.writeText(path, json.encodeToString(JsonObject.serializer(), stored))
        forget()
    }

    /** Read, change and write a folder's property file — one at a time, so two changes do not race. */
    private suspend fun updateProperties(folder: String, change: (Map<String, JsonElement>) -> Map<String, JsonElement>) {
        ready()
        val path = propertiesPath(folder)
        mutex.withLock {
            val text = container.readText(path, PROPERTIES_WAIT_SEC)
            if (text == null && fileAt(path) != null) {
                throw StepFailure(retryable = true, reason = CoreMessage.STEP_FAILED.code("icloud: '$path' is not here yet"))
            }
            val stored = text?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }.orEmpty()
            container.writeText(path, json.encodeToString(JsonObject.serializer(), JsonObject(change(stored))))
            listing = null
        }
    }

    private fun isProperties(path: String): Boolean {
        val folder = parentOf(path)
        return folder.isNotEmpty() && nameOf(path) == nameOf(folder) + PROPERTIES_SUFFIX
    }

    private fun propertiesPath(folder: String): String = "$folder/${nameOf(folder)}$PROPERTIES_SUFFIX"

    private fun pathOf(id: String): String {
        require(id.startsWith(StorageKind.ICLOUD_PREFIX)) { "not an iCloud id: '$id'" }
        return id.removePrefix(StorageKind.ICLOUD_PREFIX)
    }

    private fun idOf(path: String): String = StorageKind.ICLOUD_PREFIX + path

    private fun join(parent: String, name: String): String = if (parent.isEmpty()) name else "$parent/$name"

    private fun nameOf(path: String): String = path.substringAfterLast('/')

    private fun parentOf(path: String): String = path.substringBeforeLast('/', "")

    /** A name is one path segment: nothing may climb out of the folder it is made in. */
    private fun segment(name: String): String {
        require(name.isNotEmpty() && '/' !in name && '\\' !in name && name != "." && name != "..") {
            "not a file name: '$name'"
        }
        return name
    }

    private class Cached<T>(val size: Long, val modifiedAt: Long, val value: T) {
        fun matches(file: UbiquityFile): Boolean = file.size == size && file.modifiedAt == modifiedAt
    }

    companion object {
        /** `{folder}.folder.json`: what Drive keeps on the folder itself (docs/03 "저장 위치"). */
        const val PROPERTIES_SUFFIX: String = ".folder.json"

        private const val DESCRIPTION = "description"
        private const val APP_PROPERTIES = "appProperties"
        private const val CREATED_TIME = "createdTime"
        private const val RECORDING_ID = "recordingId"
        private const val TEMP_DIR = "icloud-tmp"

        /** A property file is a few hundred bytes; a listing waits this long for one to arrive. */
        private const val PROPERTIES_WAIT_SEC = 10

        /** A part is 3.6 MB; playback waits this long for one to come down (docs/03 "로컬 저장"). */
        private const val DOWNLOAD_WAIT_SEC = 120

        private val LISTING_TTL = 3.seconds

        private val json = Json { ignoreUnknownKeys = true }
    }
}
