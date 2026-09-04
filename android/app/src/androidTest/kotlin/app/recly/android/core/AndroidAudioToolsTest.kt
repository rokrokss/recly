package app.recly.android.core

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ConcatTimingTest` checks the pts arithmetic off-device; this checks the thing the arithmetic is
 * arithmetic *for*. `MediaExtractor` and `MediaMuxer` are framework code with no double, so the
 * only place the join can be seen working is on a device (docs/20 M7 left it unverified).
 *
 * Two AAC-LC parts are encoded here rather than checked in, so what is joined was written by one
 * encoder with one set of settings — which is the assumption `AndroidAudioTools` makes about the
 * parts of one recording (docs/03). What is asserted is docs/08 "오디오 준비": one AAC track, a
 * duration that is the sum of the parts' to within a frame, and a file that decodes end to end.
 */
@RunWith(AndroidJUnit4::class)
class AndroidAudioToolsTest {

    @Test
    fun twoPartsJoinIntoOneTrackAsLongAsBoth() {
        val dir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "concat-test",
        ).apply {
            deleteRecursively()
            mkdirs()
        }

        val first = File(dir, "p001.m4a").also { encode(it, seconds = 2.0, toneHz = 440.0) }
        val second = File(dir, "p002.m4a").also { encode(it, seconds = 2.0, toneHz = 880.0) }
        val out = File(dir, "joined.m4a")

        runBlocking {
            AndroidAudioTools(Dispatchers.IO).concat(
                listOf(first.toOkioPath(), second.toOkioPath()),
                out.toOkioPath(),
            )
        }

        val firstUs = durationUs(first)
        val secondUs = durationUs(second)
        val joinedUs = durationUs(out)
        val decoded = decode(out)
        val frameUs = ConcatTiming.frameDurationUs(SAMPLE_RATE)
        val expectedFrames = joinedUs / frameUs
        Log.i(
            TAG,
            "part1=${firstUs}us part2=${secondUs}us sum=${firstUs + secondUs}us " +
                "joined=${joinedUs}us frame=${frameUs}us bytes=${out.length()} " +
                "decodedFrames=${decoded.frames} decodedSamples=${decoded.samples} " +
                "expectedFrames=$expectedFrames",
        )

        assertEquals(1, trackCount(out), "the join must produce exactly one track")
        assertEquals(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            trackFormat(out).getString(MediaFormat.KEY_MIME),
            "the AAC frames must be copied, not re-encoded",
        )
        assertEquals(SAMPLE_RATE, trackFormat(out).getInteger(MediaFormat.KEY_SAMPLE_RATE))
        assertTrue(
            abs(joinedUs - (firstUs + secondUs)) <= frameUs,
            "joined ${joinedUs}us is not the sum ${firstUs + secondUs}us to within one " +
                "${frameUs}us frame",
        )
        // End to end: every frame in the file went into the decoder and came back out as audio.
        // The decoder is not asked for a sample count to the sample — an AAC decoder carries the
        // encoder's delay and no gapless metadata is applied here — only that nothing was dropped.
        assertEquals(
            expectedFrames,
            decoded.frames.toLong(),
            "the decoder did not read every frame of the joined file",
        )
        assertTrue(
            decoded.samples >= (decoded.frames - 1) * ConcatTiming.FRAME_SAMPLES,
            "the decoder produced only ${decoded.samples} samples for ${decoded.frames} frames",
        )
    }

    /** Writes [seconds] of a [toneHz] sine as a 16 kHz mono 32 kbps AAC-LC `m4a`. */
    private fun encode(out: File, seconds: Double, toneHz: Double) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1)
            .apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_SAMPLE_BYTES)
            }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        val info = MediaCodec.BufferInfo()
        val totalSamples = (seconds * SAMPLE_RATE).toLong()
        var fed = 0L
        var sentEos = false
        try {
            while (true) {
                if (!sentEos) {
                    val index = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = encoder.getInputBuffer(index)!!
                        val samples = minOf(
                            (buffer.capacity() / 2).toLong(),
                            totalSamples - fed,
                        ).toInt()
                        repeat(samples) { i ->
                            val t = (fed + i).toDouble() / SAMPLE_RATE
                            buffer.putShort((sin(2 * Math.PI * toneHz * t) * 12_000).toInt().toShort())
                        }
                        val presentationTimeUs = fed * 1_000_000L / SAMPLE_RATE
                        val eos = fed + samples >= totalSamples
                        encoder.queueInputBuffer(
                            index,
                            0,
                            samples * 2,
                            presentationTimeUs,
                            if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0,
                        )
                        fed += samples
                        sentEos = eos
                    }
                }
                val index = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    continue
                }
                if (index < 0) continue
                val buffer = encoder.getOutputBuffer(index)!!
                // The codec config is the `esds` the muxer already took from the output format.
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    muxer.writeSampleData(track, buffer, info)
                }
                encoder.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        } finally {
            encoder.stop()
            encoder.release()
            muxer.stop()
            muxer.release()
        }
    }

    /** What came out of a full decode of the joined file. */
    private data class Decoded(val frames: Int, val samples: Long)

    /** Decodes every frame of [file], start to end-of-stream. */
    private fun decode(file: File): Decoded {
        val extractor = MediaExtractor().apply { setDataSource(file.path) }
        val format = extractor.getTrackFormat(0)
        extractor.selectTrack(0)
        val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(format, null, null, 0)
        decoder.start()

        val info = MediaCodec.BufferInfo()
        var pcmBytes = 0L
        var read = 0
        var sentEos = false
        try {
            while (true) {
                if (!sentEos) {
                    val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                index,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            sentEos = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            read++
                            extractor.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (index >= 0) {
                    pcmBytes += info.size
                    decoder.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            decoder.stop()
            decoder.release()
            extractor.release()
        }
        Log.i(TAG, "decoded frames=$read pcmBytes=$pcmBytes from ${file.name}")
        // 16-bit mono, which is what the AAC decoder gives back for this stream.
        return Decoded(frames = read, samples = pcmBytes / 2)
    }

    private fun durationUs(file: File): Long = trackFormat(file).getLong(MediaFormat.KEY_DURATION)

    private fun trackCount(file: File): Int {
        val extractor = MediaExtractor().apply { setDataSource(file.path) }
        return try {
            extractor.trackCount
        } finally {
            extractor.release()
        }
    }

    private fun trackFormat(file: File): MediaFormat {
        val extractor = MediaExtractor().apply { setDataSource(file.path) }
        return try {
            extractor.getTrackFormat(0)
        } finally {
            extractor.release()
        }
    }

    private companion object {
        const val TAG = "ConcatTest"
        const val SAMPLE_RATE = 16_000
        const val MAX_SAMPLE_BYTES = 16 * 1024
        const val TIMEOUT_US = 10_000L
    }
}
