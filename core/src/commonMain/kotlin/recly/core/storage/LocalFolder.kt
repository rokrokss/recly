package recly.core.storage

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import recly.core.recording.PartHasher

/**
 * docs/03 "Storage location" — Local folder: a folder the user picked on this device, as the shell reaches
 * it. Every path is relative to that folder, `/`-separated, with no leading slash; `""` is the
 * folder itself.
 *
 * The shell owns where the folder is and how it is reached — a path on the Mac and Windows
 * ([PathFolder]), a bookmarked folder picked in Files on the iPhone, a document tree the user granted
 * on Android — and the core owns what goes in it
 * ([FolderFiles]). Ids keep only the path under the folder, so a folder the user moved and picked
 * again is still found, and picking another one sends every later write there.
 */
interface LocalFolder {
    /** Whether a folder is picked and can be used now. Never throws. */
    suspend fun available(): Boolean

    suspend fun isDirectory(path: String): Boolean

    /** The size of the file at [path], or null when there is none. */
    suspend fun size(path: String): Long?

    /** Makes [path] and every missing folder above it. */
    suspend fun makeDirectories(path: String)

    /**
     * Copies the local file [source], an absolute path, to [path], replacing what is there and
     * making the folders above it that are missing.
     */
    suspend fun importFile(source: String, path: String)

    /** Copies [path] to the local file [destination], an absolute path. False when there is no such file. */
    suspend fun exportFile(path: String, destination: String): Boolean

    /** The hex md5 of the file at [path]; null when there is none. */
    suspend fun md5(path: String): String?

    /** Removes the file or folder at [path], with everything in it. Nothing at [path] is not an error. */
    suspend fun delete(path: String)
}

/**
 * [LocalFolder] at a path on this device: the Mac and Windows shells, where the app writes wherever
 * the user can (the Mac app is not sandboxed, docs/12). [root] is the folder the user picked, read
 * on every call so a change in settings applies at once; null or blank while none is picked.
 */
class PathFolder(
    private val fileSystem: FileSystem,
    private val root: () -> String?,
) : LocalFolder {
    override suspend fun available(): Boolean = try {
        val dir = dir()
        dir != null && fileSystem.metadataOrNull(dir)?.isDirectory == true
    } catch (e: Exception) {
        false
    }

    override suspend fun isDirectory(path: String): Boolean = fileSystem.metadataOrNull(at(path))?.isDirectory == true

    override suspend fun size(path: String): Long? =
        fileSystem.metadataOrNull(at(path))?.takeIf { it.isRegularFile }?.size

    override suspend fun makeDirectories(path: String) = fileSystem.createDirectories(at(path))

    override suspend fun importFile(source: String, path: String) {
        val target = at(path)
        target.parent?.let(fileSystem::createDirectories)
        // Through a sibling and a rename: a copy cut short never stands under the real name.
        val partial = target.parent!! / "${target.name}.partial"
        fileSystem.copy(source.toPath(), partial)
        fileSystem.atomicMove(partial, target)
    }

    override suspend fun exportFile(path: String, destination: String): Boolean {
        val source = at(path)
        if (fileSystem.metadataOrNull(source)?.isRegularFile != true) return false
        fileSystem.copy(source, destination.toPath())
        return true
    }

    override suspend fun md5(path: String): String? {
        val file = at(path)
        if (fileSystem.metadataOrNull(file)?.isRegularFile != true) return null
        return PartHasher.md5(fileSystem, file)
    }

    override suspend fun delete(path: String) = fileSystem.deleteRecursively(at(path), mustExist = false)

    private fun dir(): Path? = root()?.takeIf { it.isNotBlank() }?.toPath()

    /** Segment by segment, so a `/`-separated path lands the same way under a Windows root. */
    private fun at(path: String): Path {
        val dir = dir() ?: throw StorageUnavailableException(StorageKind.FOLDER, "no local folder is picked")
        return path.split('/').filter { it.isNotEmpty() }.fold(dir) { parent, segment -> parent / segment }
    }
}
