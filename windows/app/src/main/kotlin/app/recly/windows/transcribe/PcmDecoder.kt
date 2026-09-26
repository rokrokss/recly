package app.recly.windows.transcribe

import app.recly.windows.helper.CaptureHelper
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val SAMPLE_RATE = 16_000

/**
 * docs/05 "고정 처리 설정 도입": a recording decoded from [startSec] to the 16 kHz mono float PCM the speech model
 * reads, by the bundled ffmpeg (ADR-019) as a separate process, one chunk at a time so an hour never
 * sits in memory. Closing it stops ffmpeg.
 */
internal class PcmDecoder(private val path: String, startSec: Double, ffmpeg: String = CaptureHelper.ffmpeg()) : AutoCloseable {
    private val process = ProcessBuilder(
        ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error",
        "-ss", "%.3f".format(java.util.Locale.ROOT, startSec), "-i", path,
        "-f", "f32le", "-ac", "1", "-ar", "$SAMPLE_RATE", "-",
    ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    private val input: InputStream = process.inputStream
    private val bytes = ByteArray(CHUNK_BYTES)

    /** The next chunk, or null once the file is exhausted. */
    fun read(): FloatArray? {
        var filled = 0
        while (filled < bytes.size) {
            val read = input.read(bytes, filled, bytes.size - filled)
            if (read < 0) break
            filled += read
        }
        if (filled < 4) {
            check(process.waitFor() == 0) { "ffmpeg exited ${process.exitValue()} decoding '$path'" }
            return null
        }
        val floats = ByteBuffer.wrap(bytes, 0, filled - filled % 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(floats.remaining()).also { floats.get(it) }
    }

    override fun close() {
        process.destroy()
        input.close()
    }

    private companion object {
        /** Two seconds of audio. */
        const val CHUNK_BYTES = SAMPLE_RATE * 4 * 2
    }
}
