package app.recly.windows.core

import app.recly.windows.helper.CaptureHelper
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import recly.core.platform.AudioTools

/**
 * docs/08 "오디오 준비" on the desktop: the bundled ffmpeg (ADR-019) joins the parts with the
 * concat demuxer and `-c copy`, so the AAC frames are moved and never re-encoded.
 *
 * The binary is the same one the capture helper is handed — [CaptureHelper.ffmpeg] — which on a
 * development host with nothing bundled falls back to `ffmpeg` on `PATH`.
 */
class FfmpegAudioTools(
    private val fileSystem: FileSystem,
    private val io: CoroutineDispatcher,
    private val ffmpeg: String = CaptureHelper.ffmpeg(),
) : AudioTools {
    override suspend fun concat(parts: List<Path>, out: Path) = withContext(io) {
        // Checked here because ffmpeg does not check it for us: a part the concat demuxer cannot
        // open is a warning, not an exit code — it writes the parts it did read, exits 0, and the
        // step would go on to transcribe a truncated recording (measured on ffmpeg 8.1).
        parts.firstOrNull { !fileSystem.exists(it) }?.let { error("cannot remux: '$it' is not there") }
        // The list file is what `-f concat` reads; single quotes are its escape, and a path that
        // contains one would break the parse — ffmpeg's own documented escape is `'\''`.
        val list = out.parent!! / "${out.name}.concat.txt"
        fileSystem.write(list) {
            parts.forEach { writeUtf8("file '${it.toString().replace("'", "'\\''")}'\n") }
        }
        try {
            val process = ProcessBuilder(
                ffmpeg,
                "-hide_banner",
                "-nostdin",
                "-y",
                "-f", "concat",
                // The parts are absolute paths outside the list file's directory.
                "-safe", "0",
                "-i", list.toString(),
                "-c", "copy",
                out.toString(),
            ).redirectErrorStream(true).start()
            process.outputStream.close()
            val log = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(TIMEOUT_MIN, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                error("ffmpeg did not finish within $TIMEOUT_MIN min")
            }
            if (process.exitValue() != 0) {
                error("ffmpeg exited ${process.exitValue()}: ${log.takeLast(LOG_EXCERPT)}")
            }
        } finally {
            fileSystem.delete(list, mustExist = false)
        }
    }

    private companion object {
        /** A two-hour recording is a few hundred megabytes of copying, not of encoding. */
        const val TIMEOUT_MIN = 10L
        const val LOG_EXCERPT = 500
    }
}
