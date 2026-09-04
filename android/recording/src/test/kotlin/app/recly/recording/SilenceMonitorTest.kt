package app.recly.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import recly.core.model.Range

/**
 * The Android half ([SilenceMonitor.start]/[SilenceMonitor.stop]) needs a device; what is testable
 * here is the part that turns a stream of callback states into the meta's `silenced` ranges.
 */
class SilenceMonitorTest {

    private var elapsed = 0.0
    private val monitor = SilenceMonitor { elapsed }

    @Test
    fun `a silenced stretch becomes one range`() {
        monitor.onSilenced(true, 120.0)
        monitor.onSilenced(false, 125.5)

        assertEquals(listOf(Range(120.0, 125.5, "mic_taken")), monitor.ranges())
    }

    @Test
    fun `repeats of the same state are not transitions`() {
        monitor.onSilenced(true, 120.0)
        monitor.onSilenced(true, 121.0)
        monitor.onSilenced(true, 122.0)
        monitor.onSilenced(false, 125.0)
        monitor.onSilenced(false, 126.0)

        assertEquals(listOf(Range(120.0, 125.0, "mic_taken")), monitor.ranges())
    }

    @Test
    fun `a range that resumes where the last one ended is merged into it`() {
        monitor.onSilenced(true, 10.0)
        monitor.onSilenced(false, 20.0)
        // The callback flapped: silence came back at the very instant the last range closed.
        monitor.onSilenced(true, 20.0)
        monitor.onSilenced(false, 30.0)

        assertEquals(listOf(Range(10.0, 30.0, "mic_taken")), monitor.ranges())
    }

    @Test
    fun `separated ranges stay separate`() {
        monitor.onSilenced(true, 10.0)
        monitor.onSilenced(false, 20.0)
        monitor.onSilenced(true, 40.0)
        monitor.onSilenced(false, 50.0)

        assertEquals(
            listOf(Range(10.0, 20.0, "mic_taken"), Range(40.0, 50.0, "mic_taken")),
            monitor.ranges(),
        )
    }

    @Test
    fun `a zero length blip is not a range`() {
        monitor.onSilenced(true, 10.0)
        monitor.onSilenced(false, 10.0)

        assertTrue(monitor.ranges().isEmpty())
    }

    @Test
    fun `silence that never ended is closed at the elapsed time it is asked about`() {
        monitor.onSilenced(true, 30.0)
        elapsed = 42.0

        monitor.onSilenced(false, elapsed)

        assertEquals(listOf(Range(30.0, 42.0, "mic_taken")), monitor.ranges())
    }

    @Test
    fun `a recording that was never silenced has no ranges`() {
        monitor.onSilenced(false, 12.0)

        assertTrue(monitor.ranges().isEmpty())
    }
}
