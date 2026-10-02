package app.recly.android.ui

import androidx.annotation.StringRes
import app.recly.android.R
import recly.core.job.Job
import recly.core.job.JobStatus
import recly.core.job.StepRun
import recly.core.job.StepStatus
import recly.core.message.CoreMessage
import recly.core.message.CoreMessageRef

/**
 * docs/10 "Failures the user can fix, and their notices": the failures a person has to do something about,
 * and the screen that lets them do it. Everything else — 5xx, the network, a 429 the runner is
 * still waiting out — is a retry the app does not call anybody about.
 *
 * The reasons are per *job*, not per step: a job the queue has stopped carrying is what the user
 * counts, and the same reason on five jobs is one line and one notification with a count on it.
 */
enum class AlertReason(
    @param:StringRes val label: Int,
    val fix: FixSurface,
    /**
     * A job parked on something to come — a sign-in, space, the speech model — rather than one that
     * failed. The banner draws it in the warning its badge is; red is for failures.
     */
    val wait: Boolean = false,
) {
    LOCAL_TRANSCRIPTION_UNAVAILABLE(R.string.core_local_transcription_unavailable, FixSurface.PROCESSING),
    LOCAL_MODEL_REQUIRED(R.string.core_local_model_required, FixSurface.PROCESSING, wait = true),
    LOCAL_DIARIZATION_UNAVAILABLE(R.string.core_local_diarization_unavailable, FixSurface.PROCESSING),
    NEEDS_AUTH(R.string.alert_needs_auth, FixSurface.SIGN_IN, wait = true),
    NEEDS_SPACE(R.string.alert_needs_space, FixSurface.DRIVE_STORAGE, wait = true),
    MISSING_SECRET(R.string.alert_missing_secret, FixSurface.SECRETS),
    AUTH_REJECTED(R.string.alert_auth_rejected, FixSurface.SECRETS),
    QUOTA(R.string.alert_quota, FixSurface.PROCESSING),
    ;

    /**
     * docs/09 screen principle 2: the banner row's badge is the state as a code. A wait on the model is the
     * job's own status, as on the iPhone — the long message code squeezed the line.
     */
    val code: String get() = if (this == LOCAL_MODEL_REQUIRED) "NEEDS_MODEL" else name
}

/**
 * Where the fix is. docs/10: "탭하면 고칠 수 있는 화면으로 간다 — '앱 열기'로 끝내지 않는다."
 * [SECRETS] and [PROCESSING] are the processing settings, where the keys live too. [DRIVE_STORAGE]
 * is the one that leaves the app, because the space is Google's to give back
 * (<https://drive.google.com/settings/storage>).
 *
 * [label] is the banner's button: the surface names itself, because a fix that does not say which
 * screen it opens is the same promise with the answer left out — the iPhone's `FixSurface.label`.
 */
enum class FixSurface(@param:StringRes val label: Int) {
    SIGN_IN(R.string.drive_connect),
    DRIVE_STORAGE(R.string.jobs_open_storage),
    SECRETS(R.string.job_reason_check_key),
    PROCESSING(R.string.processing_title),
}

/** One reason and how many jobs are stuck on it — the banner line, and the notification body. */
data class JobAlert(val reason: AlertReason, val count: Int)

/** docs/10 "Drive out of space": where "free some up" actually happens. */
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
    // A wait, not a failure: the list's banner starts the download, the notification opens settings.
    JobStatus.NEEDS_MODEL -> AlertReason.LOCAL_MODEL_REQUIRED
    // Only a job the queue has given up on. A step that is still inside its retry budget is
    // `WAITING`, and docs/10 says plainly that those are not worth a notification.
    JobStatus.FAILED -> terminalReason(lastError)
    else -> null
}

private fun terminalReason(lastError: String?): AlertReason? {
    val ref = lastError?.let { CoreMessageRef.parse(it) } ?: return null
    return when (ref.message) {
        CoreMessage.LOCAL_TRANSCRIPTION_UNAVAILABLE -> AlertReason.LOCAL_TRANSCRIPTION_UNAVAILABLE
        CoreMessage.LOCAL_DIARIZATION_UNAVAILABLE -> AlertReason.LOCAL_DIARIZATION_UNAVAILABLE
        CoreMessage.MISSING_SECRET -> AlertReason.MISSING_SECRET
        CoreMessage.AUTH_REJECTED -> AlertReason.AUTH_REJECTED
        CoreMessage.QUOTA -> AlertReason.QUOTA
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

private val HOLDING_UP = setOf(StepStatus.FAILED, StepStatus.NEEDS_AUTH, StepStatus.NEEDS_SPACE, StepStatus.NEEDS_MODEL)

/**
 * docs/10 rule 3: the list's banner, folded over **every** job the queue is carrying — the same
 * fold `JobAlertNotifier` makes for the notifications. The list is not the queue: it is the newest
 * recordings with the newest job of each, and a job blocked on a missing key further back is still
 * blocked (the iPhone's `JobAlerts.sources(core:)`). A parked job says why in its own status; only
 * a FAILED one has to be asked for its [steps], and asking every job on every change would be a
 * query per row per change.
 */
suspend fun queueAlerts(jobs: List<Job>, steps: suspend (jobId: String) -> List<StepRun>): List<JobAlert> =
    foldAlerts(
        jobs.map { job ->
            val error = if (job.status == JobStatus.FAILED) blockingError(steps(job.id)) else null
            alertReasonOf(job.status, error)
        },
    )

/** The reasons across the whole queue (one per job), folded one entry per reason, in [AlertReason] order. */
fun foldAlerts(reasons: List<AlertReason?>): List<JobAlert> = AlertReason.entries.mapNotNull { reason ->
    val affected = reasons.count { it == reason }
    if (affected == 0) null else JobAlert(reason, affected)
}
