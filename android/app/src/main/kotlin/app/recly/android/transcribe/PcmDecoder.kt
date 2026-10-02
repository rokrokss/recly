package app.recly.android.transcribe

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder

/**
 * docs/05 "Fixed processing settings": a recording decoded from [startSec] to the 16 kHz mono float PCM the speech model
 * reads, one chunk at a time so an hour never sits in memory. Recordings are 16 kHz already
 * (docs/03); the 44.1 kHz fallback goes through [Resampler].
 */
internal class PcmDecoder(path: String, startSec: Double) : AutoCloseable {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val startUs = (startSec * 1_000_000).toLong()
    private val info = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false
    private var channels = 1
    private var float = false
    private var resampler: Resampler? = null

    init {
        extractor.setDataSource(path)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run { extractor.release(); error("no audio track in '$path'") }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
    }

    /** The next chunk, or null once the file is exhausted. */
    fun read(): FloatArray? {
        while (!outputDone) {
            if (!inputDone) feed()
            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> format(codec.outputFormat)
                index >= 0 -> {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    val samples = drain(index)
                    if (samples.isNotEmpty()) return samples
                }
            }
        }
        return null
    }

    private fun feed() {
        val index = codec.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return
        val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
        if (size < 0) {
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
        } else {
            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    private fun format(format: MediaFormat) {
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        float = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
            format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
        resampler = Resampler(format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
    }

    /** One output buffer as mono samples, with anything before [startUs] dropped. */
    private fun drain(index: Int): FloatArray {
        val buffer = codec.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder())
        buffer.position(info.offset).limit(info.offset + info.size)
        val frames = info.size / (channels * if (float) 4 else 2)
        val mono = FloatArray(frames) {
            var sum = 0f
            repeat(channels) { sum += if (float) buffer.float else buffer.short / 32768f }
            sum / channels
        }
        codec.releaseOutputBuffer(index, false)
        val resampler = resampler ?: Resampler(SAMPLE_RATE).also { resampler = it }
        val skip = ((startUs - info.presentationTimeUs) * resampler.from / 1_000_000).coerceIn(0, frames.toLong()).toInt()
        return resampler.process(if (skip == 0) mono else mono.copyOfRange(skip, frames))
    }

    override fun close() {
        runCatching { codec.stop() }
        codec.release()
        extractor.release()
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}

const val SAMPLE_RATE = 16_000

/**
 * Linear interpolation to [SAMPLE_RATE], carrying its position across chunks. There is no
 * anti-alias filter: only the recorder's 44.1 kHz fallback ever reaches it, and speech carries
 * little above the 8 kHz the model keeps.
 */
internal class Resampler(val from: Int) {
    private val step = from.toDouble() / SAMPLE_RATE
    private var position = 0.0
    private var previous = 0f

    fun process(input: FloatArray): FloatArray {
        if (from == SAMPLE_RATE || input.isEmpty()) return input
        val out = FloatArray(((input.size - position) / step).toInt() + 1)
        var count = 0
        while (position < input.size - 1) {
            val index = kotlin.math.floor(position).toInt()
            val fraction = (position - index).toFloat()
            val a = if (index < 0) previous else input[index]
            out[count++] = a + (input[index + 1] - a) * fraction
            position += step
        }
        position -= input.size
        previous = input.last()
        return out.copyOf(count)
    }
}
