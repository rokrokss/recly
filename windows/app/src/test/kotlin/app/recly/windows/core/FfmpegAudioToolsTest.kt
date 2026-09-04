package app.recly.windows.core

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath

/**
 * docs/08 "오디오 준비" on the desktop: two AAC parts in, one file out, and the output as long as
 * the parts put together. Real ffmpeg, because the whole point of the implementation is what
 * ffmpeg does with `-f concat -c copy`.
 *
 * The fixtures are generated rather than checked in — an `m4a` in git would be a binary nobody can
 * review — and the test skips itself where ffmpeg is not installed, which is every machine that is
 * not a developer's (CI for this module runs on the packaging host, which has it).
 */
class FfmpegAudioToolsTest {
    @Test
    fun `two parts are joined losslessly and the result is as long as both`() = runBlocking {
        if (!hasFfmpeg()) {
            println("SKIPPED: no ffmpeg on PATH, so there is nothing to remux with")
            return@runBlocking
        }
        val dir = createTempDirectory()
        val first = dir / "part-001.m4a"
        val second = dir / "part-002.m4a"
        val out = dir / "joined.m4a"
        tone(first, seconds = 2)
        tone(second, seconds = 3)

        FfmpegAudioTools(FileSystem.SYSTEM, Dispatchers.Unconfined, FFMPEG).concat(listOf(first, second), out)

        assertTrue(FileSystem.SYSTEM.exists(out), "nothing was written")
        val joined = duration(out)
        // 2 s + 3 s, plus the one AAC frame of encoder priming the second part carries with it —
        // docs/08 allows the joined length to be the sum of the parts to within a frame.
        assertTrue(abs(joined - 5.0) < TOLERANCE_SEC, "joined is ${joined}s, expected about 5s")
        assertTrue(
            FileSystem.SYSTEM.list(dir).none { it.name.endsWith(".concat.txt") },
            "the list file the demuxer read is not left behind",
        )
    }

    @Test
    fun `a part that is not there fails rather than writing half a file`() = runBlocking {
        if (!hasFfmpeg()) {
            println("SKIPPED: no ffmpeg on PATH, so there is nothing to remux with")
            return@runBlocking
        }
        val dir = createTempDirectory()
        val first = dir / "part-001.m4a"
        tone(first, seconds = 1)

        val failure = runCatching {
            FfmpegAudioTools(FileSystem.SYSTEM, Dispatchers.Unconfined, FFMPEG)
                .concat(listOf(first, dir / "missing.m4a"), dir / "joined.m4a")
        }.exceptionOrNull()

        assertTrue(failure != null, "a missing part went unnoticed")
        assertTrue("is not there" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    /** An AAC-LC part the way docs/03 records them: 16 kHz mono, in an `m4a`. */
    private fun tone(path: Path, seconds: Int) {
        run(
            FFMPEG,
            "-hide_banner",
            "-nostdin",
            "-y",
            "-f", "lavfi",
            "-i", "sine=frequency=440:duration=$seconds:sample_rate=16000",
            "-ac", "1",
            "-c:a", "aac",
            "-b:a", "32k",
            path.toString(),
        )
    }

    private fun duration(path: Path): Double = run(
        FFPROBE,
        "-v", "error",
        "-show_entries", "format=duration",
        "-of", "csv=p=0",
        path.toString(),
    ).trim().toDouble()

    private fun run(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        process.outputStream.close()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)) { "${command.first()} did not finish" }
        check(process.exitValue() == 0) { "${command.toList()} exited ${process.exitValue()}: $text" }
        return text
    }

    private fun hasFfmpeg(): Boolean = runCatching {
        run(FFMPEG, "-version")
        run(FFPROBE, "-version")
        true
    }.getOrDefault(false)

    private fun createTempDirectory(): Path =
        File.createTempFile("recly-concat", "").let { file ->
            file.delete()
            file.mkdirs()
            file.deleteOnExit()
            file.toOkioPath()
        }

    private companion object {
        const val FFMPEG = "ffmpeg"
        const val FFPROBE = "ffprobe"
        const val TIMEOUT_SEC = 60L
        const val TOLERANCE_SEC = 0.3
    }
}
