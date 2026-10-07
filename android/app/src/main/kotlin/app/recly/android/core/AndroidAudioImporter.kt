package app.recly.android.core

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import app.recly.android.transcribe.PcmDecoder
import app.recly.android.transcribe.SAMPLE_RATE
import java.io.File
import java.io.IOException
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import recly.core.recording.AudioImporter
import recly.core.recording.ImportedPart
import recly.core.recording.TranscodeResult

/**
 * docs/03 "Naming rules", the platform half of an import: whatever the file is — audio or the sound of a
 * video — decoded by [PcmDecoder] to the 16 kHz mono the recorder writes, and encoded again by
 * `MediaCodec` as AAC-LC at 32 kbps into `.m4a` parts of [segmentSec] each, so an import is a recording
 * like the ones this phone makes (ADR-006). Only one part's encoder is open at a time, and the PCM
 * passes through in the decoder's own chunks.
 */
class AndroidAudioImporter(private val dispatcher: CoroutineDispatcher) : AudioImporter {

    override suspend fun transcode(sourcePath: String, outDir: String, segmentSec: Int): TranscodeResult = withContext(dispatcher) {
        when (probe(sourcePath)) {
            Probe.UNREADABLE -> return@withContext TranscodeResult.Unreadable
            Probe.NO_AUDIO -> return@withContext TranscodeResult.Unsupported
            Probe.AUDIO -> Unit
        }
        val pcm = try {
            PcmDecoder(sourcePath, 0.0)
        } catch (e: IOException) {
            // A track no decoder on this phone takes — a protected or unusual codec.
            return@withContext TranscodeResult.Unsupported
        } catch (e: IllegalArgumentException) {
            return@withContext TranscodeResult.Unsupported
        } catch (e: IllegalStateException) {
            return@withContext TranscodeResult.Unsupported
        }
        val perPart = segmentSec.toLong() * SAMPLE_RATE
        val parts = mutableListOf<ImportedPart>()
        pcm.use {
            var chunk: FloatArray? = pcm.read()
            var at = 0
            while (chunk != null) {
                val name = "part-${parts.size + 1}.m4a"
                val encoder = PartEncoder(File(outDir, name).path)
                try {
                    while (chunk != null && encoder.samples < perPart) {
                        currentCoroutineContext().ensureActive()
                        val take = minOf(chunk.size - at, (perPart - encoder.samples).toInt())
                        encoder.write(chunk, at, take)
                        at += take
                        if (at == chunk.size) {
                            chunk = pcm.read()
                            at = 0
                        }
                    }
                    encoder.finish()
                } finally {
                    encoder.release()
                }
                parts += ImportedPart(name, encoder.samples.toDouble() / SAMPLE_RATE)
            }
        }
        if (parts.isEmpty()) TranscodeResult.Unsupported else TranscodeResult.Done(parts)
    }

    private enum class Probe { UNREADABLE, NO_AUDIO, AUDIO }

    /** Whether the file opens at all, and whether there is a sound track in it to take. */
    private fun probe(path: String): Probe {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            val audio = (0 until extractor.trackCount).any {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            if (audio) Probe.AUDIO else Probe.NO_AUDIO
        } catch (e: IOException) {
            Probe.UNREADABLE
        } catch (e: IllegalArgumentException) {
            Probe.UNREADABLE
        } finally {
            extractor.release()
        }
    }
}

/**
 * One part: an AAC-LC encoder feeding a muxer. The muxer's track is added from the encoder's first
 * output format, which carries the codec config — the config buffers themselves are not samples and
 * are not written.
 */
private class PartEncoder(path: String) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var ended = false

    /** Mono samples handed to the encoder so far, which is the part's length. */
    var samples = 0L
        private set

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, INPUT_BYTES)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun write(pcm: FloatArray, from: Int, count: Int) {
        var at = from
        val end = from + count
        while (at < end) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) {
                drain(endOfStream = false)
                continue
            }
            val buffer = codec.getInputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
            buffer.clear()
            val n = minOf(end - at, buffer.remaining() / 2)
            for (i in 0 until n) {
                buffer.putShort((pcm[at + i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
            }
            codec.queueInputBuffer(index, 0, n * 2, samples * 1_000_000 / SAMPLE_RATE, 0)
            samples += n
            at += n
            drain(endOfStream = false)
        }
    }

    /** End of input, then everything the encoder still holds into the file. */
    fun finish() {
        var index = codec.dequeueInputBuffer(TIMEOUT_US)
        while (index < 0) {
            drain(endOfStream = false)
            index = codec.dequeueInputBuffer(TIMEOUT_US)
        }
        codec.queueInputBuffer(index, 0, 0, samples * 1_000_000 / SAMPLE_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(endOfStream = true)
        if (track >= 0) muxer.stop()
    }

    fun release() {
        runCatching { codec.stop() }
        codec.release()
        muxer.release()
    }

    private fun drain(endOfStream: Boolean) {
        while (!ended) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)!!
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!config && info.size > 0 && track >= 0) {
                        buffer.position(info.offset).limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) ended = true
                }
            }
        }
    }

    private companion object {
        /** docs/03 "Audio settings": what the recorder writes. */
        const val BIT_RATE = 32_000
        const val INPUT_BYTES = 16 * 1024
        const val TIMEOUT_US = 10_000L
    }
}
