package app.recly.windows.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * docs/09 "모션" and "유동 타이포": the numbers behind `ProcessingButton` and the type scale. The
 * button is a shape; these functions are the promise — a processing state nobody can miss, a
 * completion badge that does not outstay the window, and type that grows with the window rather
 * than jumping at a breakpoint.
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

    /** The phase the button draws, from the operation's own outcome. */
    @Test
    fun `a running operation holds the processing state however long it takes`() {
        assertEquals(
            ProcessingPhase.PROCESSING,
            processingPhase(succeeded = null, workMs = 0, elapsedMs = 10_000),
        )
    }

    @Test
    fun `only a success wears the badge, and a failure goes straight back`() {
        // Instant work: the "…" is held to 400ms, then the check fills the rest of the 800.
        assertEquals(
            ProcessingPhase.PROCESSING,
            processingPhase(succeeded = true, workMs = 0, elapsedMs = 399),
        )
        assertEquals(
            ProcessingPhase.DONE,
            processingPhase(succeeded = true, workMs = 0, elapsedMs = 500),
        )
        assertEquals(
            ProcessingPhase.IDLE,
            processingPhase(succeeded = true, workMs = 0, elapsedMs = 800),
        )
        // A failure is the screen's news, not the button's.
        assertEquals(
            ProcessingPhase.IDLE,
            processingPhase(succeeded = false, workMs = 0, elapsedMs = 400),
        )
    }

    /** docs/09 "유동 타이포": a continuous ramp between the two window widths, clamped at both ends. */
    @Test
    fun `the type scale is fluid between 640dp and 1280dp`() {
        assertEquals(1f, fluidScale(400f))
        assertEquals(1f, fluidScale(640f))
        assertEquals(1.15f, fluidScale(1280f))
        assertEquals(1.15f, fluidScale(2560f))
        assertEquals(1.075f, fluidScale(960f), 0.0001f)
    }

    /** The six sizes of docs/09, and the timer being the one that is read across a room. */
    @Test
    fun `the mono scale is the documented one, multiplied`() {
        val mono = monoType(fluidScale(1280f))
        assertEquals(Type.SMALL * 1.15f, mono.small.fontSize.value, 0.0001f)
        assertEquals(Type.TIMER * 1.15f, mono.timer.fontSize.value, 0.0001f)
        assertEquals(Type.BODY, monoType(1f).body.fontSize.value, 0.0001f)
    }
}
