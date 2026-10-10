package app.recly.windows.core

import app.recly.windows.SilentLogger
import app.recly.windows.ui.PcmProcess
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath

/**
 * docs/08 "Audio preparation" on the desktop: two AAC parts in, one file out, and the output as long as
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

        FfmpegAudioTools(FileSystem.SYSTEM, Dispatchers.Unconfined, SilentLogger, FFMPEG).concat(listOf(first, second), out)

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
            FfmpegAudioTools(FileSystem.SYSTEM, Dispatchers.Unconfined, SilentLogger, FFMPEG)
                .concat(listOf(first, dir / "missing.m4a"), dir / "joined.m4a")
        }.exceptionOrNull()

        assertTrue(failure != null, "a missing part went unnoticed")
        assertTrue("is not there" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    // --- docs/08 "Me and others": a track's levels ---------------------------------------------------

    /**
     * The loudest sample of every window, from the file's start, 0–1: a window is `windowSec` of 16 kHz PCM
     * (the waveform decoder's own rate), and the short tail is a window of its own.
     */
    @Test
    fun `levels are the loudest sample of each window`() = runBlocking {
        val spawned = mutableListOf<Pair<Path, Double>>()
        val tools = tools { path, seekSec ->
            spawned += path to seekSec
            PcmProcess(pcm(List(4000) { 16_384 } + List(4000) { -8_192 } + List(800) { 0 } + List(800) { 32_767 }))
        }

        val levels = tools.levels(PART, windowSec = 0.25)

        assertEquals(listOf(PART to 0.0), spawned, "one decode, from the start of the file")
        assertContentEquals(listOf(0.5f, 0.25f, 32_767 / 32_768f), levels)
        // The window the core asks for, not the waveform's.
        assertContentEquals(listOf(0.5f, 32_767 / 32_768f), tools.levels(PART, windowSec = 0.5))
    }

    @Test
    fun `a file ffmpeg cannot read is no levels, not a failure`() = runBlocking {
        assertNull(tools { _, _ -> PcmProcess(ByteArray(0), exit = 1) }.levels(PART, windowSec = 0.25))
        assertNull(tools { _, _ -> throw IOException("ffmpeg: not found") }.levels(PART, windowSec = 0.25))
    }

    /** The real decode: a tone with a silent stretch in it reads loud, quiet, loud. */
    @Test
    fun `levels of a real file follow its sound`() = runBlocking {
        if (!hasFfmpeg()) {
            println("SKIPPED: no ffmpeg on PATH, so there is nothing to decode with")
            return@runBlocking
        }
        val file = createTempDirectory() / "p001_mic.m4a"
        run(
            FFMPEG, "-hide_banner", "-nostdin", "-y",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=3:sample_rate=16000",
            "-af", "volume='if(between(t,1,2),0,0.5)':eval=frame",
            "-ac", "1", "-c:a", "aac", "-b:a", "32k",
            file.toString(),
        )

        val levels = FfmpegAudioTools(FileSystem.SYSTEM, Dispatchers.Unconfined, SilentLogger, FFMPEG).levels(file, windowSec = 0.25)!!

        assertTrue(abs(levels.size - 12) <= 1, "3 s is 12 windows, not ${levels.size}")
        // lavfi's sine is 1/8 of full scale, halved here. Away from the onset and the edges of the silent second,
        // where AAC smears the step across a frame.
        assertTrue(levels.subList(1, 4).all { abs(it - 0.0625f) < 0.02f }, "the tone: $levels")
        assertTrue(levels.subList(5, 7).all { it < 0.005f }, "the silence: $levels")
        assertTrue(levels.subList(9, 11).all { abs(it - 0.0625f) < 0.02f }, "the tone again: $levels")
    }

    private fun tools(spawn: (Path, Double) -> Process) =
        FfmpegAudioTools(FileSystem.SYSTEM, Dispatchers.Unconfined, SilentLogger, FFMPEG, spawn)

    /** The samples as `-f s16le` writes them: 16-bit little-endian, one channel. */
    private fun pcm(samples: List<Int>): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = (sample and 0xFF).toByte()
            bytes[index * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return bytes
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
        val PART = "/recordings/r1/p001_mic.m4a".toPath()
    }
}
