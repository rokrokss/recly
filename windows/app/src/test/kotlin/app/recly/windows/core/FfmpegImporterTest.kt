package app.recly.windows.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import recly.core.recording.TranscodeResult

/**
 * docs/03 "Naming rules": an import's transcode, with real ffmpeg — what it cuts, how long it says each part
 * is, and the two refusals. Skips itself where ffmpeg is not installed, like [FfmpegAudioToolsTest].
 */
class FfmpegImporterTest {
    private val dir: File = Files.createTempDirectory("recly-import").toFile().apply { deleteOnExit() }

    @Test
    fun `a stereo file becomes mono 16 kHz parts of the asked length`() = runBlocking {
        if (!hasFfmpeg()) return@runBlocking println("SKIPPED: no ffmpeg on PATH")
        val source = File(dir, "in.wav")
        run("ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=7:sample_rate=44100", "-ac", "2", source.path)
        val out = File(dir, "out").apply { mkdirs() }

        val result = FfmpegImporter(Dispatchers.IO, "ffmpeg").transcode(source.path, out.path, segmentSec = 3)

        val parts = assertIs<TranscodeResult.Done>(result).parts
        assertEquals(listOf("part000.m4a", "part001.m4a", "part002.m4a"), parts.map { it.file })
        assertTrue(abs(parts.sumOf { it.durationSec } - 7.0) < 0.2, "the parts add up to ${parts.sumOf { it.durationSec }}")
        assertTrue(abs(parts.first().durationSec - 3.0) < 0.1)
        val stream = run("ffprobe", "-v", "error", "-show_entries", "stream=codec_name,sample_rate,channels", "-of", "csv=p=0", File(out, "part000.m4a").path)
        assertEquals("aac,16000,1", stream.trim())
    }

    @Test
    fun `a video with no sound has no audio track, and a document is not a format`() = runBlocking {
        if (!hasFfmpeg()) return@runBlocking println("SKIPPED: no ffmpeg on PATH")
        val video = File(dir, "silent.mp4")
        run("ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=duration=1", "-pix_fmt", "yuv420p", video.path)
        val document = File(dir, "notes.pdf").apply { writeText("not media") }
        val importer = FfmpegImporter(Dispatchers.IO, "ffmpeg")

        assertEquals(TranscodeResult.Unsupported, importer.transcode(video.path, File(dir, "a").apply { mkdirs() }.path, 900))
        assertEquals(TranscodeResult.Unreadable, importer.transcode(document.path, File(dir, "b").apply { mkdirs() }.path, 900))
        assertEquals(TranscodeResult.Unreadable, importer.transcode(File(dir, "gone.wav").path, File(dir, "c").apply { mkdirs() }.path, 900))
    }

    private fun run(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        process.outputStream.close()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "${command.toList()}: $text" }
        return text
    }

    private fun hasFfmpeg(): Boolean = runCatching { run("ffmpeg", "-version"); run("ffprobe", "-version"); true }.getOrDefault(false)
}
