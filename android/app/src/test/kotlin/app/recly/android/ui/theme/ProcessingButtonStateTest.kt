package app.recly.android.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * docs/09 트렌드 2: the button says what the operation did, not what the clock did. The phase is
 * driven by the caller's real outcome — so a save that came back with validation errors never
 * wears a ✓, and work that is still running never stops looking like it.
 */
class ProcessingButtonStateTest {

    private fun phaseAt(succeeded: Boolean?, workMs: Long, elapsedMs: Long) =
        processingPhase(succeeded, workMs, elapsedMs)

    @Test
    fun `a success that came back early still shows the processing state until the minimum`() {
        assertEquals(ProcessingPhase.PROCESSING, phaseAt(succeeded = true, workMs = 100, elapsedMs = 100))
        assertEquals(ProcessingPhase.PROCESSING, phaseAt(succeeded = true, workMs = 100, elapsedMs = 399))
        assertEquals(ProcessingPhase.DONE, phaseAt(succeeded = true, workMs = 100, elapsedMs = 400))
        assertEquals(ProcessingPhase.DONE, phaseAt(succeeded = true, workMs = 100, elapsedMs = 799))
        assertEquals(ProcessingPhase.IDLE, phaseAt(succeeded = true, workMs = 100, elapsedMs = 800))
    }

    @Test
    fun `a failure holds the processing state out and then shows nothing`() {
        assertEquals(ProcessingPhase.PROCESSING, phaseAt(succeeded = false, workMs = 100, elapsedMs = 399))
        assertEquals(ProcessingPhase.IDLE, phaseAt(succeeded = false, workMs = 100, elapsedMs = 400))
        // The screen owns the error message; the button owns no badge for it, ever.
        assertTrue(
            (0L..2_000L step 10).none {
                phaseAt(succeeded = false, workMs = 100, elapsedMs = it) == ProcessingPhase.DONE
            },
        )
    }

    @Test
    fun `work that is still running keeps the processing state past the whole window`() {
        assertEquals(ProcessingPhase.PROCESSING, phaseAt(succeeded = null, workMs = 800, elapsedMs = 800))
        assertEquals(ProcessingPhase.PROCESSING, phaseAt(succeeded = null, workMs = 30_000, elapsedMs = 30_000))
    }

    @Test
    fun `work that overran the window is not held any longer and still gets its badge`() {
        assertEquals(ProcessingPhase.DONE, phaseAt(succeeded = true, workMs = 5_000, elapsedMs = 5_000))
        assertEquals(ProcessingPhase.DONE, phaseAt(succeeded = true, workMs = 5_000, elapsedMs = 5_149))
        assertEquals(ProcessingPhase.IDLE, phaseAt(succeeded = true, workMs = 5_000, elapsedMs = 5_150))
    }

    /**
     * docs/09 "모션" asks for "즉시 전환 + 텍스트 상태만" with reduce motion on, so the phases do not
     * depend on it at all: the button swaps its label, which is already an instant transition, and
     * the label is the state a user with animations off is left with. Nothing here takes a
     * reduce-motion flag any more — this is the test that says so.
     */
    @Test
    fun `the phases are the same whatever the animation setting is`() {
        assertEquals(ProcessingPhase.PROCESSING, phaseAt(succeeded = true, workMs = 0, elapsedMs = 0))
        assertEquals(ProcessingPhase.DONE, phaseAt(succeeded = true, workMs = 0, elapsedMs = 400))
        assertEquals(ProcessingPhase.IDLE, phaseAt(succeeded = true, workMs = 0, elapsedMs = 800))
    }
}
