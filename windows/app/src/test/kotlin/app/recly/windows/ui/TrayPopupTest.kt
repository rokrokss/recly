package app.recly.windows.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import recly.core.job.JobStatus

/**
 * The expanded row's Retry is an offer to fix something, not a way to hurry a job along: [retryable]
 * is the seam that says which rows have anything to fix. A row that is still on its way — `PENDING`,
 * `RUNNING`, `WAITING` with its own `next_run_at` — would have been asked to start over, and a
 * recording with no job at all has no upload to ask for now that "Upload now" is gone.
 */
class TrayPopupTest {

    @Test
    fun `the failures offer a retry`() {
        assertTrue(retryable(JobStatus.FAILED))
        // Parked rather than failed, both of them: the user signs in, or frees the space, and asks
        // for the job again from the row it stopped on.
        assertTrue(retryable(JobStatus.NEEDS_AUTH))
        assertTrue(retryable(JobStatus.NEEDS_SPACE))
    }

    @Test
    fun `a job on its way does not`() {
        assertFalse(retryable(JobStatus.PENDING))
        assertFalse(retryable(JobStatus.RUNNING))
        assertFalse(retryable(JobStatus.WAITING))
    }

    @Test
    fun `and neither does a job with nothing left to do`() {
        assertFalse(retryable(JobStatus.DONE))
        // docs/03: too short to be worth a workflow, which a retry would not change.
        assertFalse(retryable(JobStatus.SKIPPED_SHORT))
        // NO_WORKFLOW: the recording never got a job, so there is nothing to retry.
        assertFalse(retryable(null))
    }
}
