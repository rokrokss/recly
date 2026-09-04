package app.recly.windows.i18n

import recly.core.message.CoreMessage
import recly.core.message.CoreMessageRef

/**
 * docs/07 §5: the core hands the shell a key, the shell owns the sentence. This is the whole of
 * that translation on the desktop — every `step_run.last_error`, every `AuthRequiredException`
 * message and every sync failure reason goes through [coreMessage]. The phone's `CoreMessages`,
 * with a [Str] where its resource id is.
 */
object CoreMessages {

    /** Exhaustive by construction: a new key does not compile until it has a sentence. */
    fun keyOf(message: CoreMessage): Str = when (message) {
        CoreMessage.NEEDS_AUTH -> Str.CORE_NEEDS_AUTH
        CoreMessage.DRIVE_REAUTH -> Str.CORE_DRIVE_REAUTH
        CoreMessage.DRIVE_CONSENT_REQUIRED -> Str.CORE_DRIVE_CONSENT_REQUIRED
        CoreMessage.DRIVE_STORAGE_FULL -> Str.CORE_DRIVE_STORAGE_FULL
        CoreMessage.SIGN_IN_CANCELLED -> Str.CORE_SIGN_IN_CANCELLED
        CoreMessage.MISSING_SECRET -> Str.CORE_MISSING_SECRET
        CoreMessage.INVALID_SECRET -> Str.CORE_INVALID_SECRET
        CoreMessage.WEBHOOK_HTTP -> Str.CORE_WEBHOOK_HTTP
        CoreMessage.FOLDER_TEMPLATE -> Str.CORE_FOLDER_TEMPLATE
        CoreMessage.RETRY_BUDGET_SPENT -> Str.CORE_RETRY_BUDGET_SPENT
        CoreMessage.NO_RUNNER -> Str.CORE_NO_RUNNER
        CoreMessage.STEP_MISSING -> Str.CORE_STEP_MISSING
        CoreMessage.UNSUPPORTED_STEP -> Str.CORE_UNSUPPORTED_STEP
        CoreMessage.STEP_FAILED -> Str.CORE_STEP_FAILED
        CoreMessage.AUTH_REJECTED -> Str.CORE_AUTH_REJECTED
        CoreMessage.QUOTA -> Str.CORE_QUOTA
        CoreMessage.PROVIDER_ERROR -> Str.CORE_PROVIDER_ERROR
        CoreMessage.UNSUPPORTED_AUDIO -> Str.CORE_UNSUPPORTED_AUDIO
        CoreMessage.NO_INPUT_TRACK -> Str.CORE_NO_INPUT_TRACK
        CoreMessage.RESULT_TIMEOUT -> Str.CORE_RESULT_TIMEOUT
        CoreMessage.STALE -> Str.CORE_STALE
        CoreMessage.UNSUPPORTED_SCHEMA -> Str.CORE_UNSUPPORTED_SCHEMA
    }

    /**
     * True for the keys whose sentence has a `%1$s` in it. The ones listed below are the rest —
     * their sentence stands on its own, so they are looked up without an argument.
     */
    fun takesArgument(message: CoreMessage): Boolean = when (message) {
        CoreMessage.NEEDS_AUTH,
        CoreMessage.DRIVE_REAUTH,
        CoreMessage.DRIVE_CONSENT_REQUIRED,
        CoreMessage.DRIVE_STORAGE_FULL,
        CoreMessage.SIGN_IN_CANCELLED,
        CoreMessage.STALE,
        // docs/08 "오류": what to do about it is the whole sentence, and the provider's own line is
        // the code's detail — shown under it, never inside it.
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
 * Never translated, and never part of the sentence: the window puts it under one, in monospace.
 */
fun coreMessageDetail(code: String): String? = CoreMessageRef.parse(code)?.detail

fun coreMessage(message: CoreMessage, arg: String? = null): UiMessage {
    val key = CoreMessages.keyOf(message)
    if (!CoreMessages.takesArgument(message)) return UiMessage.Res(key)
    // The argument of this one key is itself a code — the failure that spent the last attempt — so
    // it stays a name too, nested inside this one.
    val detail: Any = when {
        arg == null -> ""
        message == CoreMessage.RETRY_BUDGET_SPENT -> coreMessage(arg)
        else -> arg
    }
    return UiMessage.Res(key, listOf(detail))
}
