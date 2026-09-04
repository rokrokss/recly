package app.recly.windows.ui

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import okio.Path
import okio.Path.Companion.toPath

/**
 * docs/09 화면 원칙 2: the shape under the detail's clock. [RecordingWaveform.bins] is the half that
 * has to be right at every width the row can be, and [RecordingWaveform.peaks] the half that turns
 * what ffmpeg wrote into that shape — both without a file or a screen, which is why they are apart
 * from the player at all. RecKit's `RecordingWaveformTests` pins the same arithmetic.
 */
class RecordingWaveformTest {

    private val dir: Path = "/recordings/01J9REC".toPath()

    // --- The bars actually drawn ------------------------------------------------------------------

    /**
     * Fewer bars than windows: each bar is the loudest window under it, so a transient is still
     * visible at the widths where several seconds share one column.
     */
    @Test
    fun `fewer bars than windows take the loudest of each`() {
        // Halves and quarters throughout, so what the assertion checks is the resampling and not
        // the last bit of a `Float` division.
        val bins = RecordingWaveform.bins(floatArrayOf(0.125f, 1f, 0.25f, 0.5f, 0.375f, 0.0625f), count = 3)

        assertContentEquals(floatArrayOf(1f, 0.5f, 0.375f), bins, "the loudest of each pair, normalised")
    }

    /**
     * More bars than windows: the window is repeated across the bars that fall inside it rather
     * than read off the end of the peaks.
     */
    @Test
    fun `more bars than windows repeat the window`() {
        assertContentEquals(
            floatArrayOf(1f, 1f, 1f, 0.5f, 0.5f),
            RecordingWaveform.bins(floatArrayOf(1f, 0.5f), count = 5),
        )
    }

    /**
     * The bars fill the row whatever the recording's own level was: what a waveform says is where
     * the sound is, not how many decibels it had.
     */
    @Test
    fun `the loudest bar fills the row`() {
        assertContentEquals(
            floatArrayOf(1f, 0.5f),
            RecordingWaveform.bins(floatArrayOf(0.03125f, 0.015625f), count = 2),
        )
    }

    /**
     * …except a recording with nothing in it at all, which has no loudest bar to normalise by and
     * stays flat rather than being blown up into a full-height wall of noise.
     */
    @Test
    fun `silence stays silent`() {
        assertContentEquals(
            floatArrayOf(0f, 0f, 0f),
            RecordingWaveform.bins(floatArrayOf(0f, 0f, 0f), count = 3),
        )
    }

    /**
     * Nothing decoded yet, and a row with no room in it: both are the baseline the bar draws
     * instead, and neither is a crash.
     */
    @Test
    fun `nothing to draw is no bars`() {
        assertTrue(RecordingWaveform.bins(FloatArray(0), count = 8).isEmpty())
        assertTrue(RecordingWaveform.bins(floatArrayOf(1f, 0.5f), count = 0).isEmpty())
        assertTrue(RecordingWaveform.bins(floatArrayOf(1f, 0.5f), count = -3).isEmpty())
    }

    /** One bar for the whole recording is the narrowest the row ever gets, and it is its loudest. */
    @Test
    fun `one bar is the whole recording`() {
        assertContentEquals(floatArrayOf(1f), RecordingWaveform.bins(floatArrayOf(0.1f, 0.9f, 0.3f), count = 1))
    }

    /**
     * A width that does not divide the windows evenly still gets exactly as many bars as it asked
     * for, and every window is under one of them.
     */
    @Test
    fun `every width gets the bars it asked for`() {
        val peaks = FloatArray(7) { 0.5f }
        for (count in 1..20) {
            assertEquals(count, RecordingWaveform.bins(peaks, count).size, "$count bars")
        }
    }

    // --- The PCM the decoder writes ---------------------------------------------------------------

    /**
     * A window is a quarter second of the *recording* — 4000 samples at 16 kHz — however the pipe
     * happened to break the bytes up. The stream here hands over seven bytes at a time, which cuts
     * both a sample and a window in half.
     */
    @Test
    fun `the windows are cut at four thousand samples across the reads`() {
        val pcm = pcm(List(4000) { 16_384 } + List(4000) { 8_192 })

        val peaks = RecordingWaveform.peaks(Dribble(pcm, 7))

        assertContentEquals(floatArrayOf(0.5f, 0.25f), peaks)
    }

    /** The tail of the recording is a window like any other, however short it came out. */
    @Test
    fun `the tail is a window of its own`() {
        val peaks = RecordingWaveform.peaks(Dribble(pcm(List(4000) { 8_192 } + List(100) { 16_384 }), 7))

        assertContentEquals(floatArrayOf(0.25f, 0.5f), peaks)
    }

    /**
     * The peak is how far the sample is from silence in either direction — and the deepest trough a
     * 16-bit sample has is `-32768`, which has no positive of its own.
     */
    @Test
    fun `a trough is as loud as a crest`() {
        assertContentEquals(floatArrayOf(0.5f), RecordingWaveform.peaks(Dribble(pcm(List(10) { -16_384 }), 7)))
        assertContentEquals(floatArrayOf(1f), RecordingWaveform.peaks(Dribble(pcm(List(10) { -32_768 }), 7)))
    }

    // --- One selection, end to end ----------------------------------------------------------------

    /**
     * The windows are counted against the durations `meta.json` recorded, because those are the
     * seconds the clock and the transcript below are on: a part that decoded long is truncated to
     * its rows, and one that decoded short is padded with silence, so the shape and the clock stay
     * the same length.
     */
    @Test
    fun `each part is as many windows as its meta duration`() {
        val spawns = CopyOnWriteArrayList<Pair<Path, Double>>()
        val decoded = mapOf(
            // Three windows of PCM for a part `meta.json` says is half a second: one too many.
            dir / "p001_mono.m4a" to pcm(List(12_000) { 16_384 }),
            // And nothing at all for a quarter-second part.
            dir / "p002_mono.m4a" to ByteArray(0),
        )

        val peaks = RecordingWaveform.peaks(
            RecordingPlaylist.Selection(decoded.keys.toList(), listOf(0.5, 0.25)),
            spawn = { path, seekSec ->
                spawns += path to seekSec
                PcmProcess(decoded.getValue(path))
            },
        )

        assertContentEquals(floatArrayOf(0.5f, 0.5f, 0f), peaks, "two windows and a padded one")
        assertEquals(listOf(0.0, 0.0), spawns.map { it.second }, "every part is decoded from its start")
    }

    /**
     * A part ffmpeg could not read is a hole in the timeline rather than a short waveform: half a
     * shape under a whole clock would put the recording at the wrong seconds, so there is no
     * waveform at all.
     */
    @Test
    fun `a part that could not be decoded is no waveform`() {
        assertFailsWith<IllegalStateException> {
            RecordingWaveform.peaks(
                RecordingPlaylist.Selection(listOf(dir / "p001_mono.m4a"), listOf(0.5)),
                spawn = { _, _ -> PcmProcess(ByteArray(0), exit = 1) },
            )
        }
    }

    /** A bar that has already moved on: the decode is dropped where it is rather than run out. */
    @Test
    fun `a decode nobody is waiting for stops`() {
        val spawns = CopyOnWriteArrayList<Path>()

        val peaks = RecordingWaveform.peaks(
            RecordingPlaylist.Selection(listOf(dir / "p001_mono.m4a"), listOf(0.5)),
            spawn = { path, _ ->
                spawns += path
                PcmProcess(pcm(List(8_000) { 16_384 }))
            },
            cancelled = { true },
        )

        assertTrue(peaks.isEmpty())
        assertTrue(spawns.isEmpty(), "a part was decoded for a bar that is not there any more")
    }

    // --- The player's own copy of it --------------------------------------------------------------

    /**
     * What the bar actually reads: the decode runs on a thread of the player's, and the peaks
     * appear on the state the Canvas draws from when it is through.
     */
    @Test
    fun `preparing a selection fills the waveform`() {
        val player = RecordingPlayer(spawn = { _, _ -> PcmProcess(pcm(List(8_000) { 16_384 })) })

        player.prepare(RecordingPlaylist.Selection(listOf(dir / "p001_mono.m4a"), listOf(0.5)))

        // The peaks, not the bars: normalising is the drawing's ([RecordingWaveform.bins]).
        val peaks = await("nothing was decoded") { player.waveform.takeIf { it.isNotEmpty() } }
        assertContentEquals(floatArrayOf(0.5f, 0.5f), peaks)
    }

    /** The one thing a test may wait for: a thread the player started getting as far as its work. */
    private fun <T : Any> await(what: String, read: () -> T?): T {
        val deadline = System.nanoTime() + AWAIT_NANOS
        while (System.nanoTime() < deadline) {
            read()?.let { return it }
            Thread.sleep(POLL_MS)
        }
        throw AssertionError(what)
    }

    /** The samples as `-f s16le` writes them: 16-bit little-endian, one channel. */
    private fun pcm(samples: List<Int>): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = (sample and 0xFF).toByte()
            bytes[index * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private companion object {
        const val POLL_MS = 5L
        const val AWAIT_NANOS = 5_000_000_000L
    }
}

/**
 * A pipe that hands over a few bytes at a time, which is what a pipe does: the windows have to be
 * counted across the reads rather than inside one of them.
 */
private class Dribble(private val bytes: ByteArray, private val at: Int) : InputStream() {

    private var offset = 0

    override fun read(): Int = if (offset < bytes.size) bytes[offset++].toInt() and 0xFF else -1

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (offset >= bytes.size) return -1
        val n = minOf(at, len, bytes.size - offset)
        bytes.copyInto(b, off, offset, offset + n)
        offset += n
        return n
    }
}

/** An ffmpeg that has already written its part and exited, which is what a decode of one is. */
private class PcmProcess(pcm: ByteArray, private val exit: Int = 0) : Process() {

    private val stdout = ByteArrayInputStream(pcm)

    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

    override fun getInputStream(): InputStream = stdout

    override fun getErrorStream(): InputStream = InputStream.nullInputStream()

    override fun waitFor(): Int = exit

    override fun exitValue(): Int = exit

    override fun destroy() = Unit
}
