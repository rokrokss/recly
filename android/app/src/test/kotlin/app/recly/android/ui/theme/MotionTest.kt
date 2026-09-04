package app.recly.android.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * docs/09 "모션": the numbers behind `ProcessingButton`. The button itself is a shape; these two
 * functions are the promise — a processing state nobody can miss, and a completion badge that does
 * not outstay the window.
 */
class MotionTest {

    @Test
    fun `instant work still shows the processing state for the minimum`() {
        assertEquals(400L, processingHoldMs(workMs = 0))
        assertEquals(300L, processingHoldMs(workMs = 100))
        assertEquals(1L, processingHoldMs(workMs = 399))
    }

    @Test
    fun `work that took the minimum or longer is not padded`() {
        assertEquals(0L, processingHoldMs(workMs = 400))
        assertEquals(0L, processingHoldMs(workMs = 5_000))
    }

    @Test
    fun `the whole window is at most the maximum`() {
        listOf(0L, 50L, 200L, 399L, 400L).forEach { work ->
            val shown = work + processingHoldMs(work)
            assertTrue(shown >= Motion.PROCESSING_MIN_MS, "$work ms was shown for only $shown ms")
            val total = shown + doneBadgeMs(work)
            assertEquals(Motion.PROCESSING_MAX_MS, total, "$work ms did not fill the 800ms window")
        }
    }

    @Test
    fun `work past the window still gets a visible completion badge`() {
        assertEquals(Motion.BADGE_FADE_MS.toLong(), doneBadgeMs(workMs = 5_000))
    }

    /**
     * docs/09 "모션": `reduce motion` 시 "즉시 전환 + 텍스트 상태만" — the transition goes, the text
     * state stays. Both windows used to collapse to zero, which took away the only thing a user
     * with animations off had left to tell them the tap was heard.
     */
    @Test
    fun `reduce motion keeps the text states, because they are not motion`() {
        assertEquals(Motion.PROCESSING_MIN_MS, processingHoldMs(workMs = 0))
        assertTrue(doneBadgeMs(workMs = 0) >= Motion.BADGE_FADE_MS)
    }

    /** The system side of it: only a scale of zero is "remove animations". */
    @Test
    fun `an animator duration scale of zero is reduce motion`() {
        assertTrue(systemReduceMotion(0f))
        assertFalse(systemReduceMotion(0.5f))
        assertFalse(systemReduceMotion(1f))
        assertFalse(systemReduceMotion(10f))
    }

    /** docs/09 "유동 타이포": a continuous ramp, clamped at both ends. */
    @Test
    fun `the type scale is fluid between 360dp and 600dp`() {
        assertEquals(1f, fluidScale(320f))
        assertEquals(1f, fluidScale(360f))
        assertEquals(1.15f, fluidScale(600f))
        assertEquals(1.15f, fluidScale(1200f))
        assertEquals(1.075f, fluidScale(480f), 0.0001f)
    }
}
