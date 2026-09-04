package recly.core.recording

import okio.FileSystem
import okio.HashingSource
import okio.Path
import okio.ByteString.Companion.toByteString
import okio.Source
import okio.blackholeSink
import okio.buffer

/** sha256 verifies watch→phone transfers, md5 is what Drive gives back (docs/03). */
object PartHasher {
    suspend fun sha256(fs: FileSystem, path: Path): String = hash(fs, path, HashingSource::sha256)

    /** The same digest over bytes that are already in hand — what a fetched part is checked with. */
    fun sha256(bytes: ByteArray): String = bytes.toByteString().sha256().hex()

    suspend fun md5(fs: FileSystem, path: Path): String = hash(fs, path, HashingSource::md5)

    private fun hash(fs: FileSystem, path: Path, wrap: (Source) -> HashingSource): String {
        val hashing = wrap(fs.source(path))
        val buffered = hashing.buffer()
        try {
            buffered.readAll(blackholeSink())
        } finally {
            buffered.close()
        }
        return hashing.hash.hex()
    }
}
