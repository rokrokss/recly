package app.recly.android.transcribe

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ResamplerTest {
    private fun tone(rate: Int, seconds: Double, hz: Double = 440.0) =
        FloatArray((rate * seconds).toInt()) { sin(2 * PI * hz * it / rate).toFloat() }

    @Test
    fun `sixteen kilohertz passes through untouched`() {
        val input = tone(SAMPLE_RATE, 0.1)
        assertSame(input, Resampler(SAMPLE_RATE).process(input))
    }

    @Test
    fun `the fallback rate comes out at sixteen kilohertz with the same tone`() {
        val out = Resampler(44_100).process(tone(44_100, 1.0))
        assertTrue(abs(out.size - SAMPLE_RATE) <= 1, "${out.size} samples")
        val expected = tone(SAMPLE_RATE, 1.0)
        for (i in 0 until out.size - 1) assertEquals(expected[i], out[i], 0.01f)
    }

    @Test
    fun `chunk boundaries do not change the output`() {
        val input = tone(44_100, 0.5)
        val whole = Resampler(44_100).process(input)
        val chunked = Resampler(44_100).let { r ->
            listOf(0 until 1_000, 1_000 until 1_001, 1_001 until input.size)
                .flatMap { r.process(input.sliceArray(it)).toList() }
        }
        assertEquals(whole.size, chunked.size)
        whole.indices.forEach { assertEquals(whole[it], chunked[it], 1e-6f) }
    }
}
