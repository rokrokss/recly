package app.recly.windows.ui

import app.recly.windows.record.RecordingOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * docs/03, the ordering the tray depends on: a recording waiting for its name and the next start
 * are decided in one place, so neither can step over the other (Sol review 2 #5).
 */
class TitleGateTest {

    @Test
    fun `a prompt published while a start is preparing refuses that start`() = runTest {
        // The shell reads the workflow summaries before it starts recording; the recording that
        // ended a moment ago can publish its prompt in exactly that window.
        val gate = TitleGate()
        assertNull(gate.pending, "the tray offered the start because nothing was waiting")

        assertTrue(gate.publish(OUTCOME))
        var started = false
        val id = gate.ifIdle {
            started = true
            "rec-2"
        }

        assertNull(id)
        assertFalse(started, "the recorder is never reached")
    }

    @Test
    fun `a start goes ahead while nothing is waiting to be named`() = runTest {
        val gate = TitleGate()

        assertEquals("rec-2", gate.ifIdle { "rec-2" })
    }

    @Test
    fun `only one recording waits for a name at a time`() = runTest {
        // The second one is queued as it stands rather than taking the prompt — losing a name
        // nobody typed, never a job.
        val gate = TitleGate()

        assertTrue(gate.publish(OUTCOME))
        assertFalse(gate.publish(OUTCOME.copy(recordingId = "rec-2")))

        assertEquals(OUTCOME, gate.pending)
    }

    @Test
    fun `the waiting recording is handed over once`() = runTest {
        val gate = TitleGate()
        gate.publish(OUTCOME)

        assertEquals(OUTCOME, gate.take())
        assertNull(gate.take(), "a second answer has nothing left to answer for")
        assertNull(gate.pending)
    }

    private companion object {
        val OUTCOME = RecordingOutcome(recordingId = "rec-1", parts = 3, durationSec = 2.0)
    }
}
