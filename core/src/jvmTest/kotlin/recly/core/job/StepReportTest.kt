@file:OptIn(ExperimentalTime::class)

package recly.core.job

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import recly.core.message.CoreMessage

/** M7-L3 deliverable 3: what the shells branch on when a job is waiting or has failed. */
class StepReportTest {

    private val now = Instant.parse("2026-08-26T02:00:00.000Z")

    private fun step(state: String? = null, error: String? = null) = StepRun(
        id = "01J9STEPR0N0123456789ABCDE",
        jobId = "01J9JOB0000000000000000000",
        stepId = "stt",
        ordinal = 1,
        status = StepStatus.PENDING,
        attempts = 0,
        nextAttemptAt = null,
        lastError = error,
        state = state?.let { buildJsonObject { put("submittedAt", it) } },
        output = null,
    )

    @Test
    fun `the two failures a key can fix are the ones that offer to check it`() {
        assertTrue(StepReport.needsKey(CoreMessage.MISSING_SECRET.code("clova_key")))
        assertTrue(StepReport.needsKey(CoreMessage.AUTH_REJECTED.code(detail = "assemblyai.submit HTTP 401")))
    }

    @Test
    fun `nothing else is about a key`() {
        assertFalse(StepReport.needsKey(null))
        assertFalse(StepReport.needsKey(CoreMessage.QUOTA.code(detail = "rtzr.transcribe HTTP 429")))
        assertFalse(StepReport.needsKey("drive.upload failed: HTTP 500"))
        // A message that merely mentions one is not one: the code is the whole of the line.
        assertFalse(StepReport.needsKey("the provider says AUTH_REJECTED"))
        // docs/07 §5: an older build wrote a bare `MISSING_SECRET`, which is not a key.
        assertFalse(StepReport.needsKey("MISSING_SECRET"))
    }

    @Test
    fun `the wait is counted from the submission, not from the last poll`() {
        val steps = listOf(step(state = "2026-08-26T01:23:00.000Z"))

        assertEquals(37, StepReport.waitingMinutes(steps, now))
    }

    @Test
    fun `a step that has submitted nothing is not waiting for a transcript`() {
        assertNull(StepReport.waitingMinutes(listOf(step()), now), "no state at all")
        assertNull(StepReport.waitingMinutes(emptyList(), now), "no steps at all")
        assertNull(StepReport.waitingMinutes(listOf(step(state = "not a time")), now))
    }

    @Test
    fun `a clock that has gone backwards reports no wait rather than a negative one`() {
        val steps = listOf(step(state = (now + 5.minutes).toString()))

        assertEquals(0, StepReport.waitingMinutes(steps, now))
    }
}
