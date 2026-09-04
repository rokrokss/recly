package app.recly.android.ui

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.Path

/**
 * docs/09 화면 원칙 2: the shape of the recording under the player bar's clock — what the detail
 * draws a playhead across, and what a drag on it seeks through. RecKit's `RecordingWaveform` and
 * the Windows shell's, in the same two halves, so the three draw the same picture.
 *
 * [bins] and [Windows] are the arithmetic the drawing is, and can be checked without a file or a
 * screen; [peaks] is the decode that has to open every part. What the page holds between them is
 * one `FloatArray` of peaks on the recording's own timeline, 0…1, one per [WINDOW_SEC] window.
 */
object RecordingWaveform {

    /**
     * The default window: 0.25 s. Finer would be more windows
     * than a bar can be drawn for on any screen this runs on.
     */
    const val WINDOW_SEC = 0.25

    /**
     * The peaks resampled to exactly the number of bars there is room for, and normalised so the
     * loudest one fills the row. A recording that was quiet throughout is still drawn as a shape
     * rather than as a flat line — the bars say where the sound is, not how many decibels it was.
     *
     * Nothing to draw (no peaks, or no room) is empty, which the bar answers with its baseline.
     * More bars than windows repeats a window across the bars that fall inside it, rather than
     * leaving a gap or reading off the end.
     */
    fun bins(peaks: FloatArray, count: Int): FloatArray {
        if (count <= 0 || peaks.isEmpty()) return FloatArray(0)
        // Each bar is the loudest window under it: a peak that survives downsampling is what makes
        // a waveform readable, where an average would flatten every transient into the same grey.
        val bins = FloatArray(count) { index ->
            val start = index * peaks.size / count
            val end = minOf(peaks.size, maxOf(start + 1, (index + 1) * peaks.size / count))
            var loudest = 0f
            for (window in start until end) loudest = maxOf(loudest, peaks[window])
            loudest
        }
        val loudest = bins.max()
        if (loudest <= 0f) return bins
        for (index in bins.indices) bins[index] /= loudest
        return bins
    }

    /**
     * The samples of one part counted into windows, across whatever chunks the decoder hands them
     * over in: a window is [WINDOW_SEC] of the part and not of a buffer. Pure, and the half of the
     * decode worth checking — where the cut falls is arithmetic, and `MediaCodec` is not something
     * a unit test has.
     *
     * One number per frame: a stereo part is deinterleaved to its first channel before it gets
     * here, because a peak is about where the sound is and not about which side it was on.
     */
    class Windows(windowFrames: Int) {

        private val perWindow = windowFrames.coerceAtLeast(1)
        private val peaks = ArrayList<Float>()
        private var loudest = 0f
        private var counted = 0

        /** The first [count] samples of [samples] — the rest of the array is a decoder's slack. */
        fun add(samples: ShortArray, count: Int) {
            for (index in 0 until count) {
                // `abs` of the widened sample and not of the `Short`: -32768 has no positive of its
                // own, and full scale is what it is.
                loudest = maxOf(loudest, abs(samples[index].toInt()) / 32768f)
                counted += 1
                if (counted == perWindow) {
                    peaks += loudest
                    loudest = 0f
                    counted = 0
                }
            }
        }

        /** The tail of the part is a window like any other, however short it came out. */
        fun finish(): FloatArray {
            if (counted > 0) {
                peaks += loudest
                loudest = 0f
                counted = 0
            }
            return peaks.toFloatArray()
        }
    }

    /**
     * One part's windows on the recording's own clock: exactly the `durationSec / windowSec` of
     * them `meta.json` says the part is, rounded up — padded with silence, or truncated. What the
     * codec produced is not counted on, because the seconds the clock and the transcript below are
     * on are `meta.json`'s.
     */
    fun fit(part: FloatArray, durationSec: Double, windowSec: Double = WINDOW_SEC): FloatArray {
        val windows = ceil(durationSec / windowSec).toInt().coerceAtLeast(0)
        return FloatArray(windows) { part.getOrElse(it) { 0f } }
    }

    /**
     * The parts of one selection decoded end to end, as the loudest sample in each [windowSec]
     * window, each part [fit] to its own share of the timeline — so the peaks are
     * `selection.totalSec` long however the files decode.
     *
     * Off the main thread, because this reads every byte of the recording, and asked between
     * buffers whether it is still wanted: a part is fifteen minutes (docs/03), which is fifteen
     * minutes of decoding for a bar that has already been replaced.
     *
     * A part that could not be decoded throws rather than shortening the timeline: half a shape
     * under a whole clock would put the recording at the wrong seconds, and the bar has a baseline
     * to draw instead.
     */
    suspend fun peaks(
        selection: RecordingPlaylist.Selection,
        windowSec: Double = WINDOW_SEC,
    ): FloatArray = withContext(Dispatchers.IO) {
        val peaks = ArrayList<Float>()
        selection.paths.forEachIndexed { index, path ->
            fit(decode(path, windowSec), selection.durations[index], windowSec).forEach { peaks += it }
        }
        peaks.toFloatArray()
    }

    /**
     * One part, from its start: `MediaExtractor` reads the AAC frames and `MediaCodec` turns them
     * into 16-bit PCM, which is the same pair `AndroidAudioTools` joins parts with — minus the
     * decoder, which a lossless join does not need and a picture of the sound does.
     */
    private suspend fun decode(path: Path, windowSec: Double): FloatArray {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(path.toString())
            val track = audioTrack(extractor) ?: error("no audio track in '$path'")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("no mime in '$path'")
            // 16-bit and not the float the codec may otherwise pick: one shape per sample is all
            // the peaks need, and the `Short` path is the one the accumulator counts in.
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            // Held before it is configured, and not as the value of the whole expression: a
            // `configure` this device refuses — a part that came back malformed, a codec whose
            // slots are taken — would otherwise throw with nothing assigned, and the codec it did
            // hand out would never reach the [release] below. A few of those and the next detail
            // opened has no decoder left to be given.
            val codec = MediaCodec.createDecoderByType(mime)
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()
            return read(codec, extractor, windowSec)
        } finally {
            decoder?.let {
                runCatching { it.stop() }
                it.release()
            }
            extractor.release()
        }
    }

    /**
     * The decoder's own loop: frames in until the extractor is out of them, buffers out until the
     * codec says the stream ended. The window is sized from the *output* format, because that is
     * the rate the samples about to be counted are at.
     */
    private suspend fun read(
        decoder: MediaCodec,
        extractor: MediaExtractor,
        windowSec: Double,
    ): FloatArray {
        val info = MediaCodec.BufferInfo()
        var windows: Windows? = null
        var channels = 1
        // Reused across buffers: channel 0 of whatever the codec just handed over.
        var mono = ShortArray(0)
        var queued = false
        while (true) {
            coroutineContext.ensureActive()
            if (!queued) {
                val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val buffer = decoder.getInputBuffer(index) ?: error("no input buffer")
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        queued = true
                    } else {
                        decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val index = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
            if (index < 0) continue
            if (windows == null) {
                val output = decoder.outputFormat
                channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                windows = Windows((output.getInteger(MediaFormat.KEY_SAMPLE_RATE) * windowSec).roundToInt())
            }
            val buffer = decoder.getOutputBuffer(index)
            if (buffer != null && info.size > 0) {
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                // The codec writes PCM in the machine's own byte order, whatever the file's was.
                val samples = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                val frames = samples.remaining() / channels
                if (mono.size < frames) mono = ShortArray(frames)
                for (frame in 0 until frames) mono[frame] = samples.get(frame * channels)
                windows.add(mono, frames)
            }
            decoder.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
        return windows?.finish() ?: FloatArray(0)
    }

    private fun audioTrack(extractor: MediaExtractor): Int? = (0 until extractor.trackCount)
        .firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }

    /** Long enough not to spin the loop on an empty codec, short enough to notice a cancellation. */
    private const val TIMEOUT_US = 10_000L
}
