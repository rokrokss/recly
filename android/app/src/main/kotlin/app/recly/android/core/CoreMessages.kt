package app.recly.android.core

import androidx.annotation.StringRes
import app.recly.android.R
import recly.core.message.CoreMessage
import recly.core.message.CoreMessageRef

/**
 * docs/07 §5: the core hands the shell a key, the shell owns the sentence. This is the whole of
 * that translation on Android — every `step_run.last_error`, every `AuthRequiredException` message
 * and every sign-in reason goes through [coreMessage].
 */
object CoreMessages {

    /** Exhaustive by construction: a new key does not compile until it has a sentence. */
    @StringRes
    fun resourceOf(message: CoreMessage): Int = when (message) {
        CoreMessage.NEEDS_AUTH -> R.string.core_needs_auth
        CoreMessage.DRIVE_REAUTH -> R.string.core_drive_reauth
        CoreMessage.DRIVE_CONSENT_REQUIRED -> R.string.core_drive_consent_required
        CoreMessage.DRIVE_STORAGE_FULL -> R.string.core_drive_storage_full
        CoreMessage.SIGN_IN_CANCELLED -> R.string.core_sign_in_cancelled
        CoreMessage.MISSING_SECRET -> R.string.core_missing_secret
        CoreMessage.INVALID_SECRET -> R.string.core_invalid_secret
        CoreMessage.WEBHOOK_HTTP -> R.string.core_webhook_http
        CoreMessage.FOLDER_TEMPLATE -> R.string.core_folder_template
        CoreMessage.RETRY_BUDGET_SPENT -> R.string.core_retry_budget_spent
        CoreMessage.NO_RUNNER -> R.string.core_no_runner
        CoreMessage.STEP_MISSING -> R.string.core_step_missing
        CoreMessage.UNSUPPORTED_STEP -> R.string.core_unsupported_step
        CoreMessage.STEP_FAILED -> R.string.core_step_failed
        CoreMessage.AUTH_REJECTED -> R.string.core_auth_rejected
        CoreMessage.QUOTA -> R.string.core_quota
        CoreMessage.PROVIDER_ERROR -> R.string.core_provider_error
        CoreMessage.UNSUPPORTED_AUDIO -> R.string.core_unsupported_audio
        CoreMessage.NO_INPUT_TRACK -> R.string.core_no_input_track
        CoreMessage.RESULT_TIMEOUT -> R.string.core_result_timeout
        CoreMessage.STALE -> R.string.core_stale
        CoreMessage.UNSUPPORTED_SCHEMA -> R.string.core_unsupported_schema
    }

    /** The keys whose sentence has a `%1$s` in it, so the rest are looked up without one. */
    fun takesArgument(message: CoreMessage): Boolean = when (message) {
        CoreMessage.NEEDS_AUTH,
        CoreMessage.DRIVE_REAUTH,
        CoreMessage.DRIVE_CONSENT_REQUIRED,
        CoreMessage.DRIVE_STORAGE_FULL,
        CoreMessage.SIGN_IN_CANCELLED,
        CoreMessage.STALE,
        // docs/08 "오류": what to do about it is the whole sentence, and the provider's own line
        // is the code's detail — shown under it, never inside it.
        CoreMessage.AUTH_REJECTED,
        CoreMessage.QUOTA,
        CoreMessage.PROVIDER_ERROR,
        CoreMessage.UNSUPPORTED_AUDIO,
        CoreMessage.NO_INPUT_TRACK,
        CoreMessage.RESULT_TIMEOUT,
        -> false

        else -> true
    }
}

/**
 * A code the core wrote, as a line that is still a name.
 *
 * Named and not resolved because a caller may hold it past a language change — a ViewModel
 * outlives the activity the language setting recreates (docs/07 rule 3).
 *
 * docs/07 §5 compatibility: a `last_error` written before the keys existed is a sentence, not a
 * key, and is carried as [UiMessage.Text] so it is shown exactly as it was stored rather than
 * replaced with a guess.
 */
fun coreMessage(code: String): UiMessage {
    val ref = CoreMessageRef.parse(code) ?: return UiMessage.Text(code)
    return coreMessage(ref.message, ref.arg)
}

/**
 * The diagnostic that came with [code], if any — a webhook's response body, a parser complaint.
 * Never translated, and never part of the sentence: the screen puts it under one.
 */
fun coreMessageDetail(code: String): String? = CoreMessageRef.parse(code)?.detail

fun coreMessage(message: CoreMessage, arg: String? = null): UiMessage {
    val id = CoreMessages.resourceOf(message)
    if (!CoreMessages.takesArgument(message)) return UiMessage.Res(id)
    // The argument of this one key is itself a code — the failure that spent the last attempt —
    // so it stays a name too, nested inside this one.
    val detail: Any = when {
        arg == null -> ""
        message == CoreMessage.RETRY_BUDGET_SPENT -> coreMessage(arg)
        else -> arg
    }
    return UiMessage.Res(id, listOf(detail))
}
