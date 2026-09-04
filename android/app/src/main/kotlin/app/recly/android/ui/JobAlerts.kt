package app.recly.android.ui

import androidx.annotation.StringRes
import app.recly.android.R
import recly.core.job.JobStatus
import recly.core.job.StepRun
import recly.core.job.StepStatus
import recly.core.message.CoreMessage
import recly.core.message.CoreMessageRef

/**
 * docs/10 "사용자가 고칠 수 있는 실패와 그 알림": the failures a person has to do something about,
 * and the screen that lets them do it. Everything else — 5xx, the network, a 429 the runner is
 * still waiting out — is a retry the app does not call anybody about.
 *
 * The reasons are per *job*, not per step: a job the queue has stopped carrying is what the user
 * counts, and the same reason on five jobs is one line and one notification with a count on it.
 */
enum class AlertReason(@param:StringRes val label: Int, val fix: FixSurface) {
    NEEDS_AUTH(R.string.alert_needs_auth, FixSurface.SIGN_IN),
    NEEDS_SPACE(R.string.alert_needs_space, FixSurface.DRIVE_STORAGE),
    MISSING_SECRET(R.string.alert_missing_secret, FixSurface.SECRETS),
    INVALID_SECRET(R.string.alert_invalid_secret, FixSurface.SECRETS),
    AUTH_REJECTED(R.string.alert_auth_rejected, FixSurface.SECRETS),
    QUOTA(R.string.alert_quota, FixSurface.EDITOR),
    WEBHOOK(R.string.alert_webhook, FixSurface.EDITOR),
}

/**
 * Where the fix is. docs/10: "탭하면 고칠 수 있는 화면으로 간다 — 로그인 화면, 시크릿 폼, 워크플로우
 * 편집기. '앱 열기'로 끝내지 않는다." [DRIVE_STORAGE] is the one that leaves the app, because the
 * space is Google's to give back (<https://drive.google.com/settings/storage>).
 */
enum class FixSurface { SIGN_IN, DRIVE_STORAGE, SECRETS, EDITOR }

/**
 * One reason and how many jobs are stuck on it — the banner line, and the notification body.
 *
 * [workflowId] is the workflow of the first job that reported this reason, so the fix surfaces that
 * are a workflow ([FixSurface.EDITOR]) open the definition that has to change rather than the list
 * of them (docs/10 "탭하면 고칠 수 있는 화면으로 간다"). Null when nothing in the fold named one.
 */
data class JobAlert(val reason: AlertReason, val count: Int, val workflowId: String? = null)

/** One job's side of the fold: why it is stuck, and the workflow it was running. */
data class AlertSource(val reason: AlertReason?, val workflowId: String?)

/** docs/10 "Drive 용량 초과": where "free some up" actually happens. */
const val DRIVE_STORAGE_URL: String = "https://drive.google.com/settings/storage"

/**
 * The reason a job is stuck, or null when nothing about it is the user's to fix.
 *
 * [lastError] is the `step_run.last_error` of the step holding the job up — a `CoreMessage` code
 * (docs/07 §5), or a sentence an older build wrote, which parses to nothing and so alerts nothing.
 */
fun alertReasonOf(status: JobStatus, lastError: String?): AlertReason? = when (status) {
    JobStatus.NEEDS_AUTH -> AlertReason.NEEDS_AUTH
    JobStatus.NEEDS_SPACE -> AlertReason.NEEDS_SPACE
    // Only a job the queue has given up on. A step that is still inside its retry budget is
    // `WAITING`, and docs/10 says plainly that those are not worth a notification.
    JobStatus.FAILED -> terminalReason(lastError)
    else -> null
}

private fun terminalReason(lastError: String?): AlertReason? {
    val ref = lastError?.let { CoreMessageRef.parse(it) } ?: return null
    return when (ref.message) {
        CoreMessage.MISSING_SECRET -> AlertReason.MISSING_SECRET
        CoreMessage.INVALID_SECRET -> AlertReason.INVALID_SECRET
        CoreMessage.AUTH_REJECTED -> AlertReason.AUTH_REJECTED
        CoreMessage.QUOTA -> AlertReason.QUOTA
        CoreMessage.WEBHOOK_HTTP -> AlertReason.WEBHOOK.takeIf { terminalWebhook(ref.arg) }
        // docs/10: a spent budget is the user's problem only when what spent it was the provider's
        // quota. Anything else ran out of attempts against something a retry could have fixed.
        CoreMessage.RETRY_BUDGET_SPENT ->
            AlertReason.QUOTA.takeIf { spentOn(ref) == CoreMessage.QUOTA }

        else -> null
    }
}

/** The code of the failure that spent the last attempt (`Executor` nests it as the argument). */
private fun spentOn(ref: CoreMessageRef): CoreMessage? =
    ref.arg?.let { CoreMessageRef.parse(it) }?.message

/**
 * True when the webhook's answer was one nothing but the user can change.
 *
 * docs/04 "응답 처리" retries 408 · 425 · 429 · 5xx and fails on everything else, so "terminal" is
 * that set's complement: a 4xx the URL or the signing secret has to fix, and the 3xx the plan
 * deliberately did not follow (`WebhookRunner.outcome` — "a webhook URL that moved is a
 * configuration change the user has to make"). docs/10 puts those on the user.
 *
 * The status has to be read here because `Executor.failed` writes the **raw**
 * `WEBHOOK_HTTP:<status>` when the attempt that fails is the last one in the budget — only a budget
 * already spent before the step ran is wrapped in `RETRY_BUDGET_SPENT` (`Executor.runStep`). So a
 * 500 that ran out of attempts reaches this file looking exactly like a 403 that never had any, and
 * the code is the only thing that tells them apart.
 *
 * A status that will not parse is an older build's wording: nothing is claimed about it.
 */
private fun terminalWebhook(status: String?): Boolean {
    val code = status?.toIntOrNull() ?: return false
    return code < 500 && code !in RETRIED_STATUS
}

/** docs/04: what the webhook step waits out rather than fails on. */
private val RETRIED_STATUS = setOf(408, 425, 429)

/**
 * The step that stopped the job, and failing that the last complaint anything made — the
 * `last_error` both the list row and [alertReasonOf] read.
 *
 * A step with `onError: continue` fails and the job carries on (`Executor.end`), so the *first*
 * failed step is not the one that ended the job: the one that did is the last failure with nothing
 * successful after it. The core keeps no job-level reason — `Job` has no `lastError` column — so it
 * has to be read back off the step rows in ordinal order.
 */
fun blockingError(steps: List<StepRun>): String? {
    val ordered = steps.sortedBy { it.ordinal }
    val lastSuccess = ordered.indexOfLast { it.status == StepStatus.SUCCEEDED }
    return ordered.filterIndexed { index, step -> index > lastSuccess && step.status in HOLDING_UP }
        .lastOrNull()?.lastError
        ?: ordered.mapNotNull { it.lastError }.lastOrNull()
}

private val HOLDING_UP = setOf(StepStatus.FAILED, StepStatus.NEEDS_AUTH, StepStatus.NEEDS_SPACE)

/** The reasons across the whole queue, folded one entry per reason, in [AlertReason] order. */
fun foldAlerts(sources: List<AlertSource>): List<JobAlert> = AlertReason.entries.mapNotNull { reason ->
    val affected = sources.filter { it.reason == reason }
    if (affected.isEmpty()) {
        null
    } else {
        JobAlert(reason, affected.size, affected.firstNotNullOfOrNull { it.workflowId })
    }
}
