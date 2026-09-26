package recly.core.transcribe

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.HashingSink
import okio.Path
import okio.blackholeSink
import okio.buffer
import okio.use
import recly.core.platform.HttpPlan
import recly.core.platform.Transport

/** A file an on-device engine downloads once, pinned by size and SHA-256 (docs/15). */
data class ModelFile(val name: String, val url: String, val bytes: Long, val sha256: String)

/**
 * docs/05 "고정 처리 설정 도입": the model files an app-managed engine needs. Downloaded only from the settings
 * action, in ranged chunks so an interrupted download resumes where it stopped, and moved into
 * place only once its hash matches. Nothing from the recording goes out on this path.
 *
 * [dir] is one model's folder, and its parent holds nothing but models: once this one is whole,
 * any other folder there is a model an older build downloaded, and it is removed.
 */
class LocalModelStore(
    private val transport: Transport,
    private val fileSystem: FileSystem,
    private val dir: Path,
    private val files: List<ModelFile>,
    private val io: CoroutineDispatcher,
) {
    /** Size alone: a file only reaches its final name after its hash was checked. */
    fun installed(): Boolean = files.all { fileSystem.metadataOrNull(path(it.name))?.size == it.bytes }

    fun path(name: String): Path = dir / name

    @Throws(Throwable::class)
    suspend fun install() = withContext(io) {
        for (file in files) {
            fileSystem.createDirectories(path(file.name).parent!!)
            if (fileSystem.metadataOrNull(path(file.name))?.size != file.bytes) download(file)
        }
        fileSystem.list(dir.parent!!).filter { it != dir }.forEach { fileSystem.deleteRecursively(it) }
    }

    private suspend fun download(file: ModelFile) {
        val partial = dir / "${file.name}.part"
        var offset = fileSystem.metadataOrNull(partial)?.size ?: 0L
        if (offset > file.bytes) {
            fileSystem.delete(partial)
            offset = 0L
        }
        while (offset < file.bytes) {
            currentCoroutineContext().ensureActive()
            val last = minOf(offset + CHUNK_BYTES, file.bytes) - 1
            val result = transport.execute(HttpPlan("GET", file.url, mapOf("Range" to "bytes=$offset-$last"), timeoutSec = CHUNK_TIMEOUT_SEC))
            check(result.status == 206 && result.body.size.toLong() == last - offset + 1) {
                "model download of ${file.name} returned HTTP ${result.status}"
            }
            fileSystem.appendingSink(partial, mustExist = offset > 0).buffer().use { it.write(result.body) }
            offset += result.body.size
        }
        val hash = HashingSink.sha256(blackholeSink()).use { sink ->
            fileSystem.source(partial).buffer().use { it.readAll(sink) }
            sink.hash.hex()
        }
        if (hash != file.sha256) {
            fileSystem.delete(partial)
            error("model file ${file.name} failed verification")
        }
        fileSystem.atomicMove(partial, path(file.name))
    }

    private companion object {
        const val CHUNK_BYTES = 8L * 1024 * 1024
        const val CHUNK_TIMEOUT_SEC = 300
    }
}
