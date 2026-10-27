package app.recly.windows.transcribe

import kotlin.test.Test
import kotlin.test.assertEquals
import okio.Path.Companion.toPath
import recly.core.transcribe.AudioSpan

/** The arithmetic around the pieces' voices (docs/08 "Length · size limits"), which needs neither the model nor ffmpeg. */
class SpeakerVoicesTest {
    private val file = "/rec/part2.m4a".toPath()

    @Test
    fun `a span is as many samples as its seconds at sixteen kilohertz`() {
        assertEquals(16_000, AudioSpan(file, 0.0, 1.0).sampleCount())
        assertEquals(76_000, AudioSpan(file, 895.25, 900.0).sampleCount())
        // The core's times are not on sample boundaries; the nearest one is taken.
        assertEquals(1, AudioSpan(file, 1.0, 1.00004).sampleCount())
        assertEquals(0, AudioSpan(file, 2.0, 1.0).sampleCount())
    }

    @Test
    fun `reading takes exactly the span across the decoder's chunks`() {
        val chunks = ArrayDeque(listOf(FloatArray(4) { it.toFloat() }, FloatArray(4) { 4f + it }, FloatArray(4) { 8f + it }))

        assertEquals((0 until 6).map { it.toFloat() }, readSamples(6, chunks::removeFirstOrNull).toList())
    }

    @Test
    fun `a file that ends first gives what it has`() {
        val chunks = ArrayDeque(listOf(FloatArray(3) { 1f }))

        assertEquals(listOf(1f, 1f, 1f), readSamples(10, chunks::removeFirstOrNull).toList())
        assertEquals(emptyList(), readSamples(0) { error("nothing to read") }.toList())
    }
}
