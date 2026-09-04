@file:OptIn(ExperimentalTime::class)

package app.recly.android.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonObject
import recly.core.job.JobStatus
import recly.core.job.StepRun
import recly.core.job.StepStatus
import recly.core.message.CoreMessage

/**
 * docs/10 "사용자가 고칠 수 있는 실패와 그 알림": which failures call the user, which ones do not, and
 * what a queue full of them adds up to. Lane P1 acceptance 7 (one notification per reason, with the
 * count in it) and 8 (a webhook 500 never notifies) are both decided here.
 */
class JobAlertsTest {

    @Test
    fun `a parked job names its own reason`() {
        assertEquals(AlertReason.NEEDS_AUTH, alertReasonOf(JobStatus.NEEDS_AUTH, null))
        assertEquals(AlertReason.NEEDS_SPACE, alertReasonOf(JobStatus.NEEDS_SPACE, null))
    }

    @Test
    fun `a job that is still being carried calls nobody`() {
        listOf(JobStatus.PENDING, JobStatus.RUNNING, JobStatus.WAITING, JobStatus.DONE).forEach { status ->
            assertNull(alertReasonOf(status, CoreMessage.WEBHOOK_HTTP.code("500")), "$status alerted")
        }
    }

    /** docs/10: "재시도로 낫는 실패는 알리지 않는다." A 5xx is inside the backoff, not at the end of it. */
    @Test
    fun `a webhook 500 on the retry path never notifies`() {
        assertNull(alertReasonOf(JobStatus.WAITING, CoreMessage.WEBHOOK_HTTP.code("500")))
        // And even once it has run out of attempts: what spent them was something a retry could
        // have fixed, so there is nothing for the user to do about it but try again.
        val spent = CoreMessage.RETRY_BUDGET_SPENT.code(CoreMessage.WEBHOOK_HTTP.code("500"))
        assertNull(alertReasonOf(JobStatus.FAILED, spent))
    }

    /**
     * The one `Executor.failed` actually writes. When the attempt that fails is the last one in the
     * budget it goes straight to `end()` with the *raw* reason — `RETRY_BUDGET_SPENT` only wraps a
     * budget that was already spent before the step ran — so an exhausted 500 lands as
     * `FAILED` + `WEBHOOK_HTTP:500`, indistinguishable from a 403 except by the number.
     */
    @Test
    fun `a retryable status that exhausted its budget still notifies nobody`() {
        listOf("500", "502", "503", "408", "425", "429").forEach { status ->
            assertNull(
                alertReasonOf(JobStatus.FAILED, CoreMessage.WEBHOOK_HTTP.code(status)),
                "webhook $status alerted",
            )
        }
    }

    @Test
    fun `a webhook 4xx is the users to fix`() {
        listOf("400", "401", "403", "404", "410", "422").forEach { status ->
            assertEquals(
                AlertReason.WEBHOOK,
                alertReasonOf(JobStatus.FAILED, CoreMessage.WEBHOOK_HTTP.code(status, detail = "nope")),
                "webhook $status did not alert",
            )
        }
    }

    /**
     * docs/04 does not follow a redirect and `WebhookRunner` calls it terminal — "a webhook URL that
     * moved is a configuration change the user has to make" — so it is the user's like a 4xx.
     */
    @Test
    fun `a webhook redirect is the users to fix too`() {
        assertEquals(AlertReason.WEBHOOK, alertReasonOf(JobStatus.FAILED, CoreMessage.WEBHOOK_HTTP.code("302")))
    }

    /** docs/07 §5: an argument that is not a status is an older build's wording, and says nothing. */
    @Test
    fun `a webhook code with no readable status alerts nothing`() {
        assertNull(alertReasonOf(JobStatus.FAILED, CoreMessage.WEBHOOK_HTTP.code()))
        assertNull(alertReasonOf(JobStatus.FAILED, CoreMessage.WEBHOOK_HTTP.code("forbidden")))
    }

    @Test
    fun `the key failures point at the key`() {
        assertEquals(
            AlertReason.MISSING_SECRET,
            alertReasonOf(JobStatus.FAILED, CoreMessage.MISSING_SECRET.code("openai_key")),
        )
        assertEquals(
            AlertReason.INVALID_SECRET,
            alertReasonOf(JobStatus.FAILED, CoreMessage.INVALID_SECRET.code("hook_secret")),
        )
        assertEquals(
            AlertReason.AUTH_REJECTED,
            alertReasonOf(JobStatus.FAILED, CoreMessage.AUTH_REJECTED.code(detail = "401")),
        )
    }

    /** docs/10: a 429 waits quietly, but a budget spent on one is the user's plan or their bill. */
    @Test
    fun `a quota that spent the retry budget is the users to fix`() {
        val spent = CoreMessage.RETRY_BUDGET_SPENT.code(CoreMessage.QUOTA.code(detail = "poll 429"))

        assertEquals(AlertReason.QUOTA, alertReasonOf(JobStatus.FAILED, spent))
    }

    /** docs/07 §5: a sentence an older build wrote is not a key, and nothing is claimed about it. */
    @Test
    fun `prose from an older build alerts nothing`() {
        assertNull(alertReasonOf(JobStatus.FAILED, "the upload failed"))
        assertNull(alertReasonOf(JobStatus.FAILED, null))
    }

    /** Acceptance 7: three jobs blocked on one reason are one line, and the line says three. */
    @Test
    fun `the same reason on three jobs is one alert with a count of three`() {
        val alerts = foldAlerts(
            listOf(
                AlertSource(AlertReason.NEEDS_SPACE, "w1"),
                AlertSource(AlertReason.NEEDS_SPACE, "w1"),
                AlertSource(AlertReason.NEEDS_SPACE, "w1"),
                AlertSource(null, "w2"),
            ),
        )

        assertEquals(listOf(JobAlert(AlertReason.NEEDS_SPACE, 3, "w1")), alerts)
    }

    @Test
    fun `two reasons are two lines and nothing is folded across them`() {
        val alerts = foldAlerts(
            listOf(
                AlertSource(AlertReason.WEBHOOK, "hook"),
                AlertSource(AlertReason.NEEDS_AUTH, null),
                AlertSource(AlertReason.NEEDS_AUTH, null),
            ),
        )

        assertEquals(
            listOf(JobAlert(AlertReason.NEEDS_AUTH, 2), JobAlert(AlertReason.WEBHOOK, 1, "hook")),
            alerts,
        )
    }

    /**
     * docs/10:124-135: the editor the fix is in, carried on the line and on the notification. With
     * several workflows on one reason the first is the one that opens; the count still says how
     * many there are.
     */
    @Test
    fun `an alert carries the first affected workflow`() {
        val alerts = foldAlerts(
            listOf(
                AlertSource(AlertReason.QUOTA, "morning"),
                AlertSource(AlertReason.QUOTA, "standup"),
            ),
        )

        assertEquals(listOf(JobAlert(AlertReason.QUOTA, 2, "morning")), alerts)
    }

    /** A queue with nothing wrong in it leaves no banner and no notification standing. */
    @Test
    fun `a clean queue has no alerts`() {
        assertEquals(emptyList(), foldAlerts(listOf(AlertSource(null, "w1"), AlertSource(null, null))))
    }

    /**
     * `onError: continue` lets a job run past a failed step, so the first FAILED row is not the one
     * that ended the job — the aborting step is the last failure with nothing successful after it.
     * Picking the first would report a webhook nobody has to fix in place of the missing key.
     */
    @Test
    fun `the aborting step is the one that is reported and not an earlier continue`() {
        val steps = listOf(
            step(0, StepStatus.FAILED, CoreMessage.WEBHOOK_HTTP.code("500")),
            step(1, StepStatus.SUCCEEDED, null),
            step(2, StepStatus.FAILED, CoreMessage.MISSING_SECRET.code("openai_key")),
            step(3, StepStatus.PENDING, null),
        )

        assertEquals(CoreMessage.MISSING_SECRET.code("openai_key"), blockingError(steps))
        assertEquals(AlertReason.MISSING_SECRET, alertReasonOf(JobStatus.FAILED, blockingError(steps)))
    }

    /** Two failures and nothing succeeded in between: the later one is still the one that stopped it. */
    @Test
    fun `the later of two failures is the one that stopped the job`() {
        val steps = listOf(
            step(0, StepStatus.FAILED, CoreMessage.WEBHOOK_HTTP.code("500")),
            step(1, StepStatus.FAILED, CoreMessage.QUOTA.code(detail = "transcribe 429")),
        )

        assertEquals(CoreMessage.QUOTA.code(detail = "transcribe 429"), blockingError(steps))
    }

    /** Nothing is holding it up: the last thing anything complained about is all there is to say. */
    @Test
    fun `a job with no failing step falls back to the last complaint`() {
        val steps = listOf(
            step(0, StepStatus.SUCCEEDED, CoreMessage.WEBHOOK_HTTP.code("500")),
            step(1, StepStatus.SUCCEEDED, null),
        )

        assertEquals(CoreMessage.WEBHOOK_HTTP.code("500"), blockingError(steps))
        assertNull(blockingError(emptyList()))
    }

    private fun step(ordinal: Int, status: StepStatus, lastError: String?): StepRun = StepRun(
        id = "s$ordinal",
        jobId = "j",
        stepId = "step$ordinal",
        ordinal = ordinal,
        status = status,
        attempts = 0,
        nextAttemptAt = null,
        lastError = lastError,
        state = null,
        output = if (status == StepStatus.SUCCEEDED) JsonObject(emptyMap()) else null,
    )

    /** docs/10: every reason has somewhere to go, and it is never just "open the app". */
    @Test
    fun `every reason has a fix screen behind it`() {
        assertEquals(FixSurface.SIGN_IN, AlertReason.NEEDS_AUTH.fix)
        assertEquals(FixSurface.DRIVE_STORAGE, AlertReason.NEEDS_SPACE.fix)
        assertEquals(FixSurface.SECRETS, AlertReason.MISSING_SECRET.fix)
        assertEquals(FixSurface.SECRETS, AlertReason.INVALID_SECRET.fix)
        assertEquals(FixSurface.SECRETS, AlertReason.AUTH_REJECTED.fix)
        assertEquals(FixSurface.EDITOR, AlertReason.QUOTA.fix)
        assertEquals(FixSurface.EDITOR, AlertReason.WEBHOOK.fix)
    }
}
