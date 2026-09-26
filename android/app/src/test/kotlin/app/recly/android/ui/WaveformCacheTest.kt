package app.recly.android.ui

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath

/**
 * The detail's waveform comes from the peaks the core kept for the recording when they fit the
 * parts about to be drawn, and only otherwise from a decode — which is then kept. While either is
 * on its way the row is the loader, never a flat line.
 */
class WaveformCacheTest {

    private val decodes = mutableListOf<Int>()
    private val saved = mutableListOf<FloatArray>()

    private fun load(expected: Int, kept: List<Float>?, decoded: FloatArray = FloatArray(expected) { 0.5f }) = runBlocking {
        RecordingWaveform.load(
            expected = expected,
            cached = { kept },
            decode = { decoded.also { decodes += it.size } },
            save = { saved += it },
        )
    }

    @Test
    fun `the window count is each part's own share rounded up`() {
        // 900 s is 3600 windows; 2.1 s is 8.4, so 9 — the same cut `fit` pads and truncates to.
        assertEquals(3609, RecordingWaveform.windows(listOf(900.0, 2.1)))
        assertEquals(RecordingWaveform.fit(FloatArray(3), 2.1).size, RecordingWaveform.windows(listOf(2.1)))
        assertEquals(0, RecordingWaveform.windows(emptyList()))
    }

    @Test
    fun `kept peaks that fit are drawn without a decode`() {
        val peaks = load(expected = 3, kept = listOf(0.25f, 1f, 0.5f))

        assertContentEquals(floatArrayOf(0.25f, 1f, 0.5f), peaks)
        assertEquals(emptyList(), decodes, "no decode")
        assertEquals(emptyList(), saved, "nothing to keep again")
    }

    @Test
    fun `kept peaks of other parts are decoded again and kept`() {
        val peaks = load(expected = 4, kept = listOf(0.25f, 1f, 0.5f))

        assertEquals(4, peaks.size)
        assertEquals(listOf(4), decodes)
        assertContentEquals(peaks, saved.single())
    }

    @Test
    fun `no kept peaks is a decode that is kept`() {
        load(expected = 2, kept = null)

        assertEquals(listOf(2), decodes)
        assertEquals(1, saved.size)
    }

    @Test
    fun `a kept file that cannot be read or written never costs the picture`() = runBlocking {
        val peaks = RecordingWaveform.load(
            expected = 2,
            cached = { throw IOException("unreadable") },
            decode = { floatArrayOf(0.5f, 1f) },
            save = { throw IOException("disk full") },
        )

        assertContentEquals(floatArrayOf(0.5f, 1f), peaks)
    }

    @Test
    fun `the row is the loader while the peaks are on their way, never a flat line`() {
        val audio = RecordingPlaylist.Selection(listOf("/rec/p1.m4a".toPath()), listOf(60.0))
        val detail = DetailState(recordingId = "r", title = null, audio = audio)

        assertEquals(WaveformSlot.LOADING, waveformSlot(detail.copy(waveformLoading = true)))
        assertEquals(WaveformSlot.WAVEFORM, waveformSlot(detail.copy(waveform = FloatArray(240) { 0.5f })))
        // No part here yet: the Drive fetch's loader, whose progress speaks for it.
        assertEquals(WaveformSlot.FETCHING, waveformSlot(DetailState(recordingId = "r", title = null)))
    }
}
