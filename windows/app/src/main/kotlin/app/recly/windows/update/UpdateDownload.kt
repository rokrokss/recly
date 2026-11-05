package app.recly.windows.update

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * docs/14 "Updates": the MSI the signed feed names, brought into [dir] (`<dataDir>\updates`) — written under a
 * temporary name while its size and SHA-256 are held against the feed's, and only then given its own name,
 * `Recly-<installerVersion>.msi`. The MSIs of earlier updates go once the new one is in place.
 */
class UpdateDownload(private val dir: Path, private val http: UpdateHttp = UpdateHttp()) {

    /** The verified MSI. Blocks; throws [UpdateException]. */
    fun fetch(update: AvailableUpdate): Path {
        val target = dir.resolve("Recly-${update.installerVersion}.msi")
        // The one an install that did not go through left here, while it is still the one the feed signed.
        if (Files.isRegularFile(target) && matches(target, update)) {
            removeOthers(target)
            return target
        }
        val temp = writing { Files.createDirectories(dir).let { Files.createTempFile(dir, "Recly-${update.installerVersion}-", PART) } }
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            writing { Files.newOutputStream(temp) }.use { sink ->
                val announced = try {
                    http.get(update.url) { body, length ->
                        // A server that says up front it is sending another file is not sending this one.
                        if (length >= 0 && length != update.size) throw UpdateException(UpdateFailure.SIGNATURE, "size")
                        written = copy(body, sink, digest, update.size)
                        length
                    }
                } catch (e: HttpStatusException) {
                    throw UpdateException(UpdateFailure.DOWNLOAD, "http ${e.status}", e)
                } catch (e: IOException) {
                    throw UpdateException(UpdateFailure.DOWNLOAD, e::class.simpleName ?: "io", e)
                }
                // Short of what the server announced is a connection that broke off, not a different file.
                if (announced >= 0 && written < announced) throw UpdateException(UpdateFailure.DOWNLOAD, "truncated")
            }
            if (written != update.size) throw UpdateException(UpdateFailure.SIGNATURE, "size")
            if (hex(digest.digest()) != update.sha256) throw UpdateException(UpdateFailure.SIGNATURE, "sha256")
            writing { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        } finally {
            runCatching { Files.deleteIfExists(temp) }
        }
        removeOthers(target)
        return target
    }

    /**
     * [body] into [sink] and [digest], never past [limit]: a file longer than the signed size is refused as it comes
     * rather than written to its end. A read that fails is the download's failure; a write that fails is the disk's.
     */
    private fun copy(body: InputStream, sink: OutputStream, digest: MessageDigest, limit: Long): Long {
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val read = body.read(buffer)
            if (read < 0) return total
            total += read
            if (total > limit) throw UpdateException(UpdateFailure.SIGNATURE, "size")
            digest.update(buffer, 0, read)
            writing { sink.write(buffer, 0, read) }
        }
    }

    private fun matches(file: Path, update: AvailableUpdate): Boolean = runCatching {
        if (Files.size(file) != update.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        hex(digest.digest()) == update.sha256
    }.getOrDefault(false)

    /** Earlier MSIs, and parts a quit left behind; one that cannot go now goes the next time. */
    private fun removeOthers(keep: Path) {
        runCatching {
            Files.list(dir).use { files ->
                files.filter { it != keep && Files.isRegularFile(it) }
                    .filter { it.fileName.toString().let { name -> name.endsWith(".msi") || name.endsWith(PART) } }
                    .forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }

    private companion object {
        const val PART = ".part"
        const val BUFFER = 64 * 1024

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        /** A step that writes to this PC's disk: its failure is the install's, not the download's. */
        inline fun <T> writing(block: () -> T): T = try {
            block()
        } catch (e: IOException) {
            throw UpdateException(UpdateFailure.INSTALL, "write", e)
        }
    }
}
