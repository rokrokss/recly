package app.recly.recording

import kotlin.test.Test
import kotlin.test.assertEquals

class SegmentTimerTest {

    private var now = 1_000L
    private val timer = SegmentTimer { now }

    @Test
    fun `a segment is as long as the gap between its own start and its boundary`() {
        now = 12_600
        assertEquals(11.6, timer.advance(now))
    }

    @Test
    fun `the next segment measures from the boundary, not from the recording's start`() {
        timer.advance(13_000)

        assertEquals(12.0, timer.advance(25_000))
    }

    @Test
    fun `a boundary read late still reports the length at the boundary, not zero`() {
        // The callback fires at 13_000 and the coroutine only runs at 20_000; passing the boundary
        // in is the whole point — reading the clock inside would call the segment 0 seconds long.
        now = 20_000

        assertEquals(12.0, timer.advance(13_000))
    }

    @Test
    fun `the open segment can be read without closing it`() {
        // D6: a highlight reads the written-audio clock in the middle of a segment.
        timer.advance(13_000)
        now = 20_500

        assertEquals(7.5, timer.elapsedSec())
        assertEquals(8.0, timer.advance(21_000), "the read did not move the boundary")
    }

    @Test
    fun `a clock that went backwards is zero, never negative`() {
        assertEquals(0.0, timer.advance(500))
    }
}
