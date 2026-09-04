package app.recly.windows.ui

import app.recly.windows.i18n.Str
import app.recly.windows.ui.component.BadgeTone
import app.recly.windows.ui.component.LedgerStatus
import recly.core.job.JobStatus
import recly.core.job.StepRun
import recly.core.job.StepStatus
import recly.core.message.CoreMessage
import recly.core.message.CoreMessageRef

/**
 * docs/10 "사용자가 고칠 수 있는 실패와 그 알림": the failures a person has to do something about, and
 * the screen that lets them do it. Everything else — 5xx, the network, a 429 the runner is still
 * waiting out — is a retry the app does not call anybody about.
 *
 * The reasons are per *job*, not per step: a job the queue has stopped carrying is what the user
 * counts, and the same reason on five jobs is one banner line and one balloon with a count on it.
 *
 * The phone's `ui/JobAlerts.kt` and RecKit's `Jobs/JobAlerts.swift` are the same file; the three are
 * held together by having the same tests over the same rules rather than by sharing code the core
 * does not own.
 */
enum class AlertReason(val label: Str, val code: String, val fix: FixSurface) {
    NEEDS_AUTH(Str.ALERT_NEEDS_AUTH, "NEEDS_AUTH", FixSurface.SIGN_IN),
    NEEDS_SPACE(Str.ALERT_NEEDS_SPACE, "NEEDS_SPACE", FixSurface.DRIVE_STORAGE),
    MISSING_SECRET(Str.ALERT_MISSING_SECRET, "MISSING_SECRET", FixSurface.SECRETS),
    INVALID_SECRET(Str.ALERT_INVALID_SECRET, "INVALID_SECRET", FixSurface.SECRETS),
    AUTH_REJECTED(Str.ALERT_AUTH_REJECTED, "AUTH_REJECTED", FixSurface.SECRETS),
    QUOTA(Str.ALERT_QUOTA, "QUOTA", FixSurface.EDITOR),
    WEBHOOK(Str.ALERT_WEBHOOK, "WEBHOOK", FixSurface.EDITOR),
    ;

    /** docs/09 화면 원칙 2: the banner row wears the state as a code, in the tone of what it is. */
    fun badge(): LedgerStatus = LedgerStatus(code, BadgeTone.WARNING)
}

/**
 * Where the fix is. docs/10: "탭하면 고칠 수 있는 화면으로 간다 — 로그인 화면, 시크릿 폼, 워크플로우
 * 편집기. '앱 열기'로 끝내지 않는다." [DRIVE_STORAGE] is the one that leaves the app, because the
 * space is Google's to give back.
 */
enum class FixSurface(val label: Str) {
    SIGN_IN(Str.SIGN_IN),
    DRIVE_STORAGE(Str.JOBS_OPEN_STORAGE),
    SECRETS(Str.REASON_CHECK_KEY),
    EDITOR(Str.TRAY_EDIT_WORKFLOWS),
}

/** docs/10 "Drive 용량 초과": where "free some up" actually happens. */
const val DRIVE_STORAGE_URL: String = "https://drive.google.com/settings/storage"

/**
 * One reason and how many jobs are stuck on it — the banner line, and the balloon's body.
 *
 * [workflowId] is the workflow of the first job that reported this reason, so the fix surfaces that
 * are a workflow ([FixSurface.EDITOR]) open the definition that has to change rather than the list
 * of them. [secret] and [stepId] are what the blocking step named, so [FixSurface.SECRETS] opens
 * the form under *that step* with the key that is missing rather than an empty one.
 */
data class JobAlert(
    val reason: AlertReason,
    val count: Int,
    val workflowId: String? = null,
    val secret: String? = null,
    val stepId: String? = null,
)

/**
 * One job's side of the fold: why it is stuck, the workflow it was running, and — for the failures
 * a key holds up — the secret and the step the form has to open on.
 */
data class AlertSource(
    val reason: AlertReason?,
    val workflowId: String?,
    val secret: String? = null,
    val stepId: String? = null,
)

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
 * deliberately did not follow ("a webhook URL that moved is a configuration change the user has to
 * make"). docs/10 puts those on the user.
 *
 * The status has to be read here because `Executor.failed` writes the **raw** `WEBHOOK_HTTP:<status>`
 * when the attempt that fails is the last one in the budget — only a budget already spent before the
 * step ran is wrapped in `RETRY_BUDGET_SPENT`. So a 500 that ran out of attempts reaches this file
 * looking exactly like a 403 that never had any, and the code is the only thing that tells them
 * apart.
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
 * `last_error` both the ledger row and [alertReasonOf] read.
 *
 * A step with `onError: continue` fails and the job carries on, so the *first* failed step is not
 * the one that ended the job: the one that did is the last failure with nothing successful after it.
 * The core keeps no job-level reason — `Job` has no `lastError` column — so it has to be read back
 * off the step rows in ordinal order.
 */
fun blockingStep(steps: List<StepRun>): StepRun? {
    val ordered = steps.sortedBy { it.ordinal }
    val lastSuccess = ordered.indexOfLast { it.status == StepStatus.SUCCEEDED }
    val holdingUp = ordered
        .filterIndexed { index, step -> index > lastSuccess && step.status in HOLDING_UP }
        .lastOrNull()
    // A step that is holding the job up without having said why leaves the last complaint anything
    // made as all there is to report — and then that is the step the fix is about.
    if (holdingUp?.lastError != null) return holdingUp
    return ordered.lastOrNull { it.lastError != null } ?: holdingUp
}

/** The code [blockingStep] found, which is what the row shows and what the fold reads. */
fun blockingError(steps: List<StepRun>): String? = blockingStep(steps)?.lastError

private val HOLDING_UP = setOf(StepStatus.FAILED, StepStatus.NEEDS_AUTH, StepStatus.NEEDS_SPACE)

/** One job of the queue, folded down to what the banner and the balloon need of it. */
fun alertSource(status: JobStatus, workflowId: String?, steps: List<StepRun>): AlertSource {
    val blocking = blockingStep(steps)
    val reason = alertReasonOf(status, blocking?.lastError)
    return AlertSource(
        reason = reason,
        workflowId = workflowId,
        secret = if (reason == null) null else secretName(blocking?.lastError),
        stepId = if (reason == null) null else blocking?.stepId,
    )
}

/**
 * The `secretRef` a code names, for the form the fix opens. Only the two that carry one:
 * `CoreMessageRef.parse` refuses those unless the argument really is a `secretRef` (docs/02), so
 * what comes back here is a name the editor can look up.
 */
private fun secretName(lastError: String?): String? {
    val ref = lastError?.let { CoreMessageRef.parse(it) } ?: return null
    return when (ref.message) {
        CoreMessage.MISSING_SECRET, CoreMessage.INVALID_SECRET -> ref.arg
        else -> null
    }
}

/** The reasons across the whole queue, folded one entry per reason, in [AlertReason] order. */
fun foldAlerts(sources: List<AlertSource>): List<JobAlert> = AlertReason.entries.mapNotNull { reason ->
    val affected = sources.filter { it.reason == reason }
    if (affected.isEmpty()) {
        null
    } else {
        // The fix opens on one job's step, so the secret and the step it is entered under come from
        // the *same* source — the first that names a step — rather than from whichever job happened
        // to name each of them first.
        val fixOn = affected.firstOrNull { it.stepId != null } ?: affected.first()
        JobAlert(
            reason = reason,
            count = affected.size,
            workflowId = affected.firstNotNullOfOrNull { it.workflowId },
            secret = fixOn.secret,
            stepId = fixOn.stepId,
        )
    }
}
