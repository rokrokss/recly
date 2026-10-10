package recly.core.message

/**
 * What the core has to say to a person, as keys rather than sentences (docs/07 §5). A shell turns
 * a key into a sentence in the app's language; the core never builds one.
 *
 * The keys travel as plain strings, because the places that carry them are a database column
 * (`step_run.last_error`) and an exception message: [code] is that wire form and
 * [CoreMessageRef.parse] reads it back. Some keys take one argument, and it is never translated —
 * it is a secret name, an HTTP status, or a diagnostic the shell shows as it stands.
 *
 * docs/07 §5 compatibility: a row written before this existed holds a sentence, not a key, and
 * [CoreMessageRef.parse] answers null for it so the shell can show it unchanged.
 */
enum class CoreMessage {
    LOCAL_TRANSCRIPTION_UNAVAILABLE,
    LOCAL_MODEL_REQUIRED,
    LOCAL_DIARIZATION_UNAVAILABLE,
    /** docs/15: a transcription integration is unavailable in this App Store region. */
    PROVIDER_REGION_RESTRICTED,

    /** docs/15 "iPhone providers": the App Store shell does not offer this transcription provider. */
    PROVIDER_NOT_OFFERED,

    /** StoreKit has not supplied a region; wait without sending audio or spending retries. */
    STOREFRONT_UNAVAILABLE,

    /** docs/15: waiting for explicit permission to send data to an external destination. */
    TRANSFER_CONSENT_REQUIRED,

    /** Interactive sign-in is what unblocks this; waiting is not. */
    NEEDS_AUTH,

    /** Signed in, but the Drive grant is gone and the user has to allow it again. */
    DRIVE_REAUTH,

    /** Play Services wants the consent screen and nothing here can show it. */
    DRIVE_CONSENT_REQUIRED,

    /**
     * docs/10 "Drive out of space": Drive answered 403 `storageQuotaExceeded`, so no retry helps until
     * the user frees space or buys some. No argument; the detail is what Drive said.
     */
    DRIVE_STORAGE_FULL,

    /**
     * docs/03 "Storage location": iCloud Drive cannot be used from this device right now — no iCloud account,
     * iCloud Drive turned off for Recly, or a build without the iCloud entitlement. The upload waits
     * and looks again; nothing in the app can change it, so the sentence says where to.
     */
    ICLOUD_UNAVAILABLE,

    /** The same as [DRIVE_STORAGE_FULL] for iCloud: the system would not upload for lack of space. */
    ICLOUD_STORAGE_FULL,

    /**
     * The recording is in the iCloud folder and the system is still uploading it. A wait, not a
     * failure: iCloud uploads in the background on its own schedule (docs/03 "Storage location").
     */
    ICLOUD_UPLOADING,

    /**
     * docs/03 "Storage location": the local folder cannot be used — none is picked, it is gone (a removed
     * drive, a deleted folder), or on Android the access granted to it was taken back. The upload
     * waits and looks again; picking the folder in settings is what fixes it.
     */
    FOLDER_UNAVAILABLE,

    /** The user backed out of the consent screen. */
    SIGN_IN_CANCELLED,

    /** Argument: the `secretRef` this device holds no value for (docs/05 "Secrets"). */
    MISSING_SECRET,

    /** Argument: what the folder template says wrong. */
    FOLDER_TEMPLATE,

    /** Argument: the code of the failure that spent the last attempt. */
    RETRY_BUDGET_SPENT,

    /** Argument: the step type nothing in this build knows how to run. */
    NO_RUNNER,

    /** Argument: the step id the job's workflow snapshot does not define. */
    STEP_MISSING,

    /**
     * Argument: the step `type` in a job's stored workflow snapshot that this build cannot decode —
     * a job a newer app queued (docs/10 "job snapshot"). The snapshot itself is left untouched, so the
     * job runs as it was written once this device is updated.
     */
    UNSUPPORTED_STEP,

    /** Anything else a step failed on. Argument: the diagnostic, shown as it stands. */
    STEP_FAILED,

    /**
     * docs/08 "Errors": the transcription provider refused the key (401/403). Not
     * retried — the key is what has to change, so the shell offers to check it.
     * Detail: which call, and the status it answered with.
     */
    AUTH_REJECTED,

    /** docs/08 "Errors": the provider is out of quota or rate-limiting (429, 402). Detail: the call. */
    QUOTA,

    /**
     * docs/08 "Errors": trouble at the provider's end — 5xx, a dropped connection, a body that will
     * not parse, a submission it declared failed. Retried. Detail: what it said.
     */
    PROVIDER_ERROR,

    /** docs/08 "Errors": a 4xx that rejected the audio itself. Detail: the call and the status. */
    UNSUPPORTED_AUDIO,

    /** docs/08 "Errors": the recording has no mono or mix track to transcribe. Detail: what it has. */
    NO_INPUT_TRACK,

    /**
     * docs/08 "Errors": `resultTimeoutSec` passed with the submission still unfinished, so the next
     * attempt submits the audio again. Detail: the reference that was being polled.
     */
    RESULT_TIMEOUT,

    /**
     * docs/03 "Naming rules": a file picked for import holds no audio the platform can decode — a document, an
     * image, a protected track. Nothing was kept. Detail: what the platform said, when it said anything.
     */
    IMPORT_UNSUPPORTED,

    /** The same for a file that could not be read at all — gone, refused, broken. Detail: the platform's error. */
    IMPORT_UNREADABLE,

    /** Something changed the settings while they were open here — a second window, or an import. */
    STALE,

    /**
     * docs/15 §10: a summary needs the ChatGPT sign-in, and there is none here — never made, signed out,
     * or refused by OpenAI on refresh. Signing in again in Settings is what fixes it.
     */
    CHATGPT_SIGN_IN_REQUIRED,

    /** The ChatGPT plan's usage limit for apps is reached; it resets on OpenAI's schedule, not Recly's. */
    CHATGPT_USAGE_LIMIT,

    /** The account signed in, but its plan does not let apps use it (the token lacks the plan scope). */
    CHATGPT_PLAN_REQUIRED,
    ;

    /**
     * The wire form: `NEEDS_AUTH`, `MISSING_SECRET:speech_api`, or either of those followed by
     * `|` and a [CoreMessageRef.detail] — a diagnostic the shell shows verbatim under the sentence.
     */
    fun code(arg: String? = null, detail: String? = null): String = buildString {
        append(name)
        if (arg != null) {
            append(SEPARATOR)
            append(arg)
        }
        if (detail != null) {
            append(DETAIL)
            append(detail)
        }
    }

    companion object {
        internal const val SEPARATOR: Char = ':'
        internal const val DETAIL: Char = '|'
    }
}

/**
 * One parsed [CoreMessage.code]. [detail] is never translated: it is a response body, a parser
 * complaint, whatever the shell wants to show under the sentence for someone debugging.
 */
data class CoreMessageRef(
    val message: CoreMessage,
    val arg: String? = null,
    val detail: String? = null,
) {
    companion object {
        /**
         * Null for anything that is not a key, which is docs/07 §5's compatibility rule: an older
         * build stored a sentence and the shell shows it as it stands.
         *
         * That rule has teeth only if the keys are hard to counterfeit. Builds before the keys
         * existed wrote a bare `MISSING_SECRET` into the same column, which would otherwise read
         * as the new wire form with no secret name at all. So the key that names a secret is only
         * a key when the argument really is a `secretRef` (docs/02).
         */
        fun parse(code: String): CoreMessageRef? {
            val head = code.substringBefore(CoreMessage.DETAIL)
            val detail = if (head.length < code.length) code.substring(head.length + 1) else null
            val name = head.substringBefore(CoreMessage.SEPARATOR)
            val message = CoreMessage.entries.firstOrNull { it.name == name } ?: return null
            val arg = if (head.length > name.length) head.substring(name.length + 1) else null
            if (message in NAMES_A_SECRET && arg?.matches(SECRET_REF) != true) return null
            return CoreMessageRef(message, arg, detail)
        }

        private val NAMES_A_SECRET = setOf(CoreMessage.MISSING_SECRET)

        /** docs/02 `secretRef`, which is what a secret name is allowed to be. */
        private val SECRET_REF = Regex("^[a-z][a-z0-9_]{0,31}$")
    }
}
