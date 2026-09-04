package app.recly.android.core

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okio.Path
import recly.core.platform.AudioTools

/**
 * docs/08 "오디오 준비" on Android: `MediaExtractor` reads the AAC frames of each part and
 * `MediaMuxer` writes them into one `m4a`. Nothing is decoded — the segment boundaries are
 * frame-aligned (docs/03), so the copy is lossless and the timestamps stay meaningful.
 *
 * The one thing that has to be got right is time. Each part starts its own presentation clock at
 * zero, so every sample of part *n* is written [ConcatTiming.sampleTimeUs] later than it claims;
 * the offset the next part inherits is [ConcatTiming.nextOffsetUs].
 */
class AndroidAudioTools(private val io: CoroutineDispatcher) : AudioTools {
    override suspend fun concat(parts: List<Path>, out: Path) = withContext(io) {
        val muxer = MediaMuxer(out.toString(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val buffer = ByteBuffer.allocate(MAX_SAMPLE_BYTES)
        val info = MediaCodec.BufferInfo()
        var track = -1
        var started = false
        var offsetUs = 0L
        try {
            parts.forEach { part ->
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(part.toString())
                    val index = audioTrack(extractor)
                        ?: error("no audio track in '$part'")
                    extractor.selectTrack(index)
                    val format = extractor.getTrackFormat(index)
                    // The first part's format is the output's: every part of one recording was
                    // written by the same encoder with the same settings (docs/03).
                    if (!started) {
                        track = muxer.addTrack(format)
                        muxer.start()
                        started = true
                    }
                    val frameUs = ConcatTiming.frameDurationUs(sampleRate(format))
                    // What the part presents, which is not always what it holds: an AAC encoder
                    // pads the last frame, and the container trims it back. Copying the padding
                    // would push every later part down the joined time axis, and `transcript.json`
                    // maps its timestamps back through `parts[].startOffsetSec` (docs/08).
                    val presentedUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        format.getLong(MediaFormat.KEY_DURATION)
                    } else {
                        Long.MAX_VALUE
                    }
                    var lastUs = 0L
                    while (true) {
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        val sampleUs = extractor.sampleTime
                        if (!ConcatTiming.keeps(sampleUs, frameUs, presentedUs)) break
                        lastUs = sampleUs
                        info.offset = 0
                        info.size = size
                        info.presentationTimeUs = ConcatTiming.sampleTimeUs(offsetUs, lastUs)
                        info.flags = extractor.sampleFlags
                        muxer.writeSampleData(track, buffer, info)
                        extractor.advance()
                    }
                    offsetUs = ConcatTiming.nextOffsetUs(offsetUs, lastUs, sampleRate(format))
                } finally {
                    extractor.release()
                }
            }
        } finally {
            if (started) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    private fun audioTrack(extractor: MediaExtractor): Int? = (0 until extractor.trackCount)
        .firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }

    private fun sampleRate(format: MediaFormat): Int =
        if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else {
            ConcatTiming.DEFAULT_SAMPLE_RATE
        }

    private companion object {
        /** One AAC frame of 16 kHz mono is a few hundred bytes; this is room for anything. */
        const val MAX_SAMPLE_BYTES = 256 * 1024
    }
}

/**
 * The presentation-time arithmetic of [AndroidAudioTools], on its own so it can be tested without
 * a device: `MediaMuxer` needs one, and what is worth checking here is the bookkeeping.
 */
internal object ConcatTiming {
    /** docs/03 records at 16 kHz; only used when a part's format somehow omits the rate. */
    const val DEFAULT_SAMPLE_RATE = 16_000

    /** AAC-LC codes 1024 samples per frame, which is what one sample of this stream is. */
    const val FRAME_SAMPLES = 1024

    /** Where a sample of the part that starts at [offsetUs] lands on the joined time axis. */
    fun sampleTimeUs(offsetUs: Long, sampleUs: Long): Long = offsetUs + sampleUs

    /**
     * Where the next part starts: the end of this one, which is its last sample plus the frame
     * that sample represents. Leaving the frame out would overlap the parts by 64 ms at 16 kHz.
     */
    fun nextOffsetUs(offsetUs: Long, lastSampleUs: Long, sampleRate: Int): Long =
        offsetUs + lastSampleUs + frameDurationUs(sampleRate)

    /**
     * Whether the frame at [sampleUs] belongs in the join: kept when more than half of it falls
     * inside what the part presents ([presentedUs]), so the part contributes its own length to
     * within half a frame either way.
     */
    fun keeps(sampleUs: Long, frameUs: Long, presentedUs: Long): Boolean =
        sampleUs + frameUs / 2 < presentedUs

    fun frameDurationUs(sampleRate: Int): Long =
        FRAME_SAMPLES * MICROS_PER_SECOND / sampleRate.coerceAtLeast(1)

    private const val MICROS_PER_SECOND = 1_000_000L
}
