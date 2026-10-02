@file:OptIn(kotlin.time.ExperimentalTime::class)

package recly.core.testing

import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path.Companion.toPath
import recly.core.storage.UbiquityContainer
import recly.core.storage.UbiquityFile

/**
 * The app's iCloud folder as one device sees it, in memory. A file this device writes is here and
 * not yet uploaded — the system has not got to it — until the test says iCloud took it ([settle]);
 * a file another device uploaded ([arrive]) is listed with its size before its content is here.
 *
 * [device] is the device's own file system, which [importFile] reads from and [exportFile] writes to.
 */
class FakeUbiquityContainer(private val device: FileSystem, private val clock: FakeClock) : UbiquityContainer {
    class Entry(
        var bytes: ByteArray,
        var modifiedAt: Long,
        var downloaded: Boolean,
        var uploaded: Boolean,
        var uploadFailure: String? = null,
    )

    val entries = linkedMapOf<String, Entry>()
    val directories = mutableSetOf("")
    var available = true

    /** Downloads [exportFile] and [readText] ask for, in order. */
    val downloads = mutableListOf<String>()

    /** What [readText] answers for a file whose content has not come down: nothing yet, like the real one. */
    var downloadsArrive = true

    /** False while the listing of iCloud has not caught up: only a file on this device answers. */
    var listingComplete = true

    override suspend fun available(): Boolean = available

    override suspend fun files(): List<UbiquityFile> {
        check(listingComplete) { LISTING_INCOMPLETE }
        return entries.map { (path, entry) -> file(path, entry) }
    }

    override suspend fun file(path: String): UbiquityFile? {
        val entry = entries[path]
        // As the real one: a file on this device answers at once, anything else asks the listing.
        if (entry?.downloaded != true) check(listingComplete) { LISTING_INCOMPLETE }
        return entry?.let { file(path, it) }
    }

    override suspend fun isDirectory(path: String): Boolean = path in directories

    override suspend fun makeDirectories(path: String) {
        var walked = ""
        for (segment in path.split('/').filter { it.isNotEmpty() }) {
            walked = if (walked.isEmpty()) segment else "$walked/$segment"
            directories += walked
        }
    }

    override suspend fun importFile(source: String, path: String) {
        write(path, device.read(source.toPath()) { readByteArray() })
    }

    override suspend fun writeText(path: String, text: String) = write(path, text.encodeToByteArray())

    override suspend fun readText(path: String, waitSeconds: Int): String? {
        val entry = entries[path] ?: return null
        if (!entry.downloaded) {
            downloads += path
            if (!downloadsArrive) return null
            entry.downloaded = true
        }
        return entry.bytes.decodeToString()
    }

    override suspend fun exportFile(path: String, destination: String, waitSeconds: Int): Boolean {
        val entry = entries[path] ?: return false
        if (!entry.downloaded) {
            downloads += path
            if (!downloadsArrive) error("'$path' did not come down in ${waitSeconds}s")
            entry.downloaded = true
        }
        device.write(destination.toPath()) { write(entry.bytes) }
        return true
    }

    override suspend fun md5(path: String): String? =
        entries[path]?.takeIf { it.downloaded }?.bytes?.toByteString()?.md5()?.hex()

    override suspend fun delete(path: String) {
        entries.keys.removeAll { it == path || it.startsWith("$path/") }
        directories.removeAll { it == path || it.startsWith("$path/") }
    }

    /** iCloud took everything written so far. */
    fun settle() = entries.values.forEach { it.uploaded = true }

    /** The account is out of space: the system will not upload [path]. */
    fun refuse(path: String) {
        entries.getValue(path).uploadFailure = UbiquityFile.QUOTA
    }

    /** A file another device uploaded: listed at its size, content not on this device. */
    fun arrive(path: String, bytes: ByteArray) {
        makeDirectoriesNow(path.substringBeforeLast('/', ""))
        entries[path] = Entry(bytes, clock.now().toEpochMilliseconds(), downloaded = false, uploaded = true)
    }

    fun text(path: String): String = entries.getValue(path).bytes.decodeToString()

    private fun write(path: String, bytes: ByteArray) {
        makeDirectoriesNow(path.substringBeforeLast('/', ""))
        // A change is a new version: its time moves even within one test instant.
        val previous = entries[path]?.modifiedAt ?: 0
        val modifiedAt = maxOf(clock.now().toEpochMilliseconds(), previous + 1)
        entries[path] = Entry(bytes, modifiedAt, downloaded = true, uploaded = false)
    }

    private fun makeDirectoriesNow(path: String) {
        var walked = ""
        for (segment in path.split('/').filter { it.isNotEmpty() }) {
            walked = if (walked.isEmpty()) segment else "$walked/$segment"
            directories += walked
        }
    }

    private companion object {
        const val LISTING_INCOMPLETE = "the iCloud file list is not complete yet"
    }

    private fun file(path: String, entry: Entry) = UbiquityFile(
        path = path,
        size = entry.bytes.size.toLong(),
        modifiedAt = entry.modifiedAt,
        downloaded = entry.downloaded,
        uploaded = entry.uploaded,
        uploadFailure = entry.uploadFailure,
    )
}
