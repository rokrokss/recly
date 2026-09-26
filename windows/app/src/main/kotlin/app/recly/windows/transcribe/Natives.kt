package app.recly.windows.transcribe

import java.io.InputStream
import okio.FileSystem
import okio.HashingSink
import okio.Path
import okio.Source
import okio.blackholeSink
import okio.buffer
import okio.source
import okio.use

/**
 * sherpa-onnx's two libraries, extracted into the data folder once rather than into a new temp
 * folder on every launch. That folder is the user's to write, so each load first checks every
 * library against the copy inside the app and writes it again when they differ; the folders an
 * older build extracted are removed.
 */
internal class Natives(
    private val fileSystem: FileSystem,
    private val dir: Path,
    private val bundled: (String) -> InputStream?,
) {
    private val names = listOf(System.mapLibraryName("onnxruntime"), System.mapLibraryName("sherpa-onnx-jni"))

    fun load() {
        fileSystem.createDirectories(dir)
        for (name in names) {
            val target = dir / name
            val expected = sha256(open(name).source())
            if (fileSystem.exists(target) && sha256(fileSystem.source(target)) == expected) continue
            val temp = dir / "$name.tmp"
            open(name).source().use { source -> fileSystem.write(temp) { writeAll(source) } }
            fileSystem.atomicMove(temp, target)
        }
        // A library still mapped by another running copy of the app cannot be deleted; the next load tries again.
        dir.parent?.let { parent ->
            fileSystem.list(parent).filter { it != dir }.forEach { runCatching { fileSystem.deleteRecursively(it) } }
        }
        System.setProperty("sherpa_onnx.native.path", dir.toString())
    }

    private fun open(name: String): InputStream = bundled(name) ?: error("sherpa-onnx has no '$name' here")

    private fun sha256(source: Source): String = HashingSink.sha256(blackholeSink()).use { sink ->
        source.buffer().use { it.readAll(sink) }
        sink.hash.hex()
    }
}
