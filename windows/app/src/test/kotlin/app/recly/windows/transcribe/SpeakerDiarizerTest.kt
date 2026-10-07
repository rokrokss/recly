package app.recly.windows.transcribe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import recly.core.transcribe.SpeakerTurn

/** The arithmetic around sherpa's diarizer, which needs neither its models nor a recording. */
class SpeakerDiarizerTest {

    private fun cut(total: Int, chunk: Int, block: Int = 15, max: Int = 20): List<Pair<Long, Int>> {
        val cutter = BlockCutter(block, max)
        val blocks = mutableListOf<Pair<Long, FloatArray>>()
        var sent = 0
        while (sent < total) {
            val n = minOf(chunk, total - sent)
            blocks += cutter.add(FloatArray(n) { (sent + it).toFloat() })
            sent += n
        }
        cutter.finish()?.let { blocks += it }
        // Every sample once, in order, on the stream's own index.
        blocks.forEach { (start, samples) -> samples.forEachIndexed { i, v -> assertEquals((start + i).toFloat(), v) } }
        return blocks.map { (start, samples) -> start to samples.size }
    }

    @Test
    fun `a recording under the limit is one block`() {
        assertEquals(listOf(0L to 19), cut(total = 19, chunk = 4))
        assertEquals(listOf(0L to 20), cut(total = 20, chunk = 7))
    }

    @Test
    fun `a longer one is cut into blocks and the last takes the tail`() {
        assertEquals(listOf(0L to 15, 15L to 6), cut(total = 21, chunk = 4))
        assertEquals(listOf(0L to 15, 15L to 15, 30L to 18), cut(total = 48, chunk = 3))
        // A chunk larger than a block is cut through, and the rest is still a whole last block.
        assertEquals(listOf(0L to 15, 15L to 20), cut(total = 35, chunk = 35))
        assertEquals(listOf(0L to 15, 15L to 15, 30L to 6), cut(total = 36, chunk = 36))
    }

    @Test
    fun `nothing in is nothing out`() {
        assertNull(BlockCutter(15, 20).finish())
    }

    @Test
    fun `a piece no turn names takes the speaker before it, or the first turn's`() {
        val labels = SpeakerLabels(listOf(SpeakerTurn(5.0, 9.0, "1"), SpeakerTurn(1.0, 4.0, "0")))
        assertEquals("0", labels.of(""))
        assertEquals("1", labels.of("1"))
        assertEquals("1", labels.of(""))
    }
}
