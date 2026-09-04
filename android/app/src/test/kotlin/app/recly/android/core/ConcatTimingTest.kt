package app.recly.android.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pts bookkeeping `AndroidAudioTools` joins parts with (docs/08 "오디오 준비"). `MediaMuxer`
 * itself is Android framework code with no unit-test double, so what is checked off-device is the
 * arithmetic — which is the part that can be wrong in a way nobody notices until a transcript's
 * timestamps are off.
 */
class ConcatTimingTest {
    @Test
    fun `one AAC frame at 16 kHz is 64 ms`() {
        assertEquals(64_000L, ConcatTiming.frameDurationUs(16_000))
        assertEquals(21_333L, ConcatTiming.frameDurationUs(48_000))
    }

    @Test
    fun `the first part is written where it says it is`() {
        assertEquals(0L, ConcatTiming.sampleTimeUs(0, 0))
        assertEquals(900_000_000L, ConcatTiming.sampleTimeUs(0, 900_000_000))
    }

    @Test
    fun `the next part starts one frame after the last sample of this one`() {
        // A 900 s part at 16 kHz: the last frame starts at 900 s − 64 ms and lasts 64 ms.
        val lastSampleUs = 900_000_000L - 64_000L

        val next = ConcatTiming.nextOffsetUs(0, lastSampleUs, 16_000)

        assertEquals(900_000_000L, next, "the join is gapless and does not overlap")
    }

    @Test
    fun `three parts stay on one continuous axis`() {
        val partUs = 900_000_000L
        val lastSampleUs = partUs - 64_000L

        val second = ConcatTiming.nextOffsetUs(0, lastSampleUs, 16_000)
        val third = ConcatTiming.nextOffsetUs(second, lastSampleUs, 16_000)

        assertEquals(partUs, second)
        assertEquals(2 * partUs, third)
        // A sample 12 s into the third part is 1812 s into the joined file.
        assertEquals(2 * partUs + 12_000_000L, ConcatTiming.sampleTimeUs(third, 12_000_000L))
    }

    @Test
    fun `the frame the padding starts in is the first one left out`() {
        val frame = ConcatTiming.frameDurationUs(16_000)
        // Two seconds is 31.25 frames, so the frame at 1.984 s is more padding than audio.
        val presented = 2_000_000L

        assertTrue(ConcatTiming.keeps(30 * frame, frame, presented), "1.920–1.984 s is inside")
        assertFalse(ConcatTiming.keeps(31 * frame, frame, presented), "1.984–2.048 s is mostly padding")
    }

    @Test
    fun `a part whose container does not say how long it is keeps everything`() {
        val frame = ConcatTiming.frameDurationUs(16_000)

        assertTrue(ConcatTiming.keeps(900_000_000L, frame, Long.MAX_VALUE))
    }

    @Test
    fun `a format with no sample rate falls back on what docs 03 records at`() {
        assertEquals(
            ConcatTiming.frameDurationUs(ConcatTiming.DEFAULT_SAMPLE_RATE),
            ConcatTiming.frameDurationUs(16_000),
        )
    }
}
