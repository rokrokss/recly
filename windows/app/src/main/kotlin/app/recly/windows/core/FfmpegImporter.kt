package app.recly.windows.core

import app.recly.windows.helper.CaptureHelper
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import recly.core.recording.AudioImporter
import recly.core.recording.ImportedPart
import recly.core.recording.TranscodeResult

/**
 * docs/03 "Naming rules" on the desktop: the bundled ffmpeg (ADR-019, a separate process) turns a picked
 * audio or video file into ADR-006 parts — AAC-LC `.m4a`, 16 kHz mono 32 kbps — with its segment muxer,
 * whose CSV list gives each part's start and end as ffmpeg cut it, so no probe is needed for the lengths.
 *
 * A file ffmpeg opens but finds no audio stream in is [TranscodeResult.Unsupported] (no audio track); one
 * it cannot open at all — not a media file, gone, refused — is [TranscodeResult.Unreadable].
 */
class FfmpegImporter(
    private val io: CoroutineDispatcher,
    private val ffmpeg: String = CaptureHelper.ffmpeg(),
) : AudioImporter {
    override suspend fun transcode(sourcePath: String, outDir: String, segmentSec: Int): TranscodeResult = withContext(io) {
        val dir = File(outDir)
        val list = File(dir, LIST)
        val log = File(dir, LOG)
        val process = ProcessBuilder(
            ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error", "-y",
            "-i", sourcePath,
            "-map", "0:a:0", "-vn", "-sn", "-dn",
            "-ac", "1", "-ar", "16000", "-c:a", "aac", "-b:a", "32k",
            "-f", "segment", "-segment_time", segmentSec.toString(), "-segment_format", "ipod",
            "-reset_timestamps", "1", "-segment_list", list.path, "-segment_list_type", "csv",
            File(dir, "part%03d.m4a").path,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(log).start()
        try {
            // Polled, so a cancelled import stops the encode rather than waiting out an hour of video.
            while (!process.waitFor(POLL_MS, TimeUnit.MILLISECONDS)) coroutineContext.ensureActive()
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(POLL_MS, TimeUnit.MILLISECONDS)
            }
        }
        if (process.exitValue() != 0) {
            val said = log.takeIf { it.exists() }?.readText().orEmpty()
            return@withContext if (NO_AUDIO in said) TranscodeResult.Unsupported else TranscodeResult.Unreadable
        }
        val parts = list.takeIf { it.exists() }?.readLines().orEmpty().mapNotNull(::part)
        if (parts.isEmpty()) TranscodeResult.Unsupported else TranscodeResult.Done(parts)
    }

    /** `part000.m4a,0.000000,900.032000`: the file, and how long it is from ffmpeg's own cut times. */
    private fun part(line: String): ImportedPart? {
        val fields = line.split(',')
        if (fields.size < 3) return null
        val start = fields[fields.size - 2].toDoubleOrNull() ?: return null
        val end = fields.last().toDoubleOrNull() ?: return null
        return ImportedPart(fields.dropLast(2).joinToString(","), end - start)
    }

    private companion object {
        const val LIST = "parts.csv"
        const val LOG = "ffmpeg.log"
        const val POLL_MS = 200L
        /** What ffmpeg 8 says when `-map 0:a:0` finds no audio stream. */
        const val NO_AUDIO = "matches no streams"
    }
}
