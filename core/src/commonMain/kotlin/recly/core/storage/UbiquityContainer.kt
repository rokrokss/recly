package recly.core.storage

/**
 * The app's iCloud Drive container as an Apple shell opens it (docs/13 "iCloud"): the `Documents`
 * folder of `iCloud.app.recly`, which the Files app and Finder show as "Recly". Every path is
 * relative to that folder, `/`-separated, with no leading slash; `""` is the folder itself.
 *
 * The shell owns what the system asks of an app that writes there — file coordination, the metadata
 * query that also lists what is not downloaded yet, conflict versions — and the core owns what the
 * files mean ([ICloudFiles]). Files cross as paths and small text as strings: a `ByteArray` crosses
 * the Swift boundary one element at a time.
 */
interface UbiquityContainer {
    /**
     * Whether the container can be used now. False without an iCloud account, with iCloud Drive
     * turned off for the app, or in a build without the iCloud entitlement. Never throws.
     */
    suspend fun available(): Boolean

    /** Every file under `Documents`, including the ones whose content is not on this device yet. */
    suspend fun files(): List<UbiquityFile>

    /** The file at [path], or null when there is none. */
    suspend fun file(path: String): UbiquityFile?

    suspend fun isDirectory(path: String): Boolean

    /** Makes [path] and every missing folder above it. */
    suspend fun makeDirectories(path: String)

    /** Copies the local file [source], an absolute path, to [path], replacing what is there. */
    suspend fun importFile(source: String, path: String)

    suspend fun writeText(path: String, text: String)

    /**
     * A small file's text. Null when there is no such file, or when its content is not on this
     * device and does not arrive within [waitSeconds] — the download has been asked for, so a later
     * call finds it.
     */
    suspend fun readText(path: String, waitSeconds: Int): String?

    /**
     * Copies [path] to the local file [destination], downloading it first when only its metadata is
     * here. False when there is no such file; throws when the download does not finish within
     * [waitSeconds].
     */
    suspend fun exportFile(path: String, destination: String, waitSeconds: Int): Boolean

    /** The hex md5 of a file whose content is on this device; null when there is none, or it is not here. */
    suspend fun md5(path: String): String?

    /** Removes the file or folder at [path] from every device. Nothing at [path] is not an error. */
    suspend fun delete(path: String)
}

/** One file in the container, as the system's metadata query describes it. */
data class UbiquityFile(
    val path: String,
    val size: Long,
    /** Epoch milliseconds of the last change: what a cached read of the file is keyed on. */
    val modifiedAt: Long,
    /** Whether its content is on this device. */
    val downloaded: Boolean,
    /** Whether iCloud holds this version of it. */
    val uploaded: Boolean,
    /**
     * Why the system could not upload it: [QUOTA] when the iCloud account is out of space, the
     * system's own description otherwise; null when nothing failed.
     */
    val uploadFailure: String?,
) {
    companion object {
        const val QUOTA: String = "quota"
    }
}
