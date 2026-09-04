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
    /** Interactive sign-in is what unblocks this; waiting is not. */
    NEEDS_AUTH,

    /** Signed in, but the Drive grant is gone and the user has to allow it again. */
    DRIVE_REAUTH,

    /** Play Services wants the consent screen and nothing here can show it. */
    DRIVE_CONSENT_REQUIRED,

    /**
     * docs/10 "Drive 용량 초과": Drive answered 403 `storageQuotaExceeded`, so no retry helps until
     * the user frees space or buys some. No argument; the detail is what Drive said.
     */
    DRIVE_STORAGE_FULL,

    /** The user backed out of the consent screen. */
    SIGN_IN_CANCELLED,

    /** Argument: the `secretRef` this device holds no value for (docs/05 "시크릿"). */
    MISSING_SECRET,

    /** Argument: the `secretRef` whose stored value is not a usable signing key. */
    INVALID_SECRET,

    /** Argument: the HTTP status the webhook answered with. */
    WEBHOOK_HTTP,

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
     * a job a newer app queued (docs/10 "잡 스냅샷"). The snapshot itself is left untouched, so the
     * job runs as it was written once this device is updated.
     */
    UNSUPPORTED_STEP,

    /** Anything else a step failed on. Argument: the diagnostic, shown as it stands. */
    STEP_FAILED,

    /**
     * docs/08 "오류": the transcription provider refused the key (401/403). Not
     * retried — the key is what has to change, so the shell offers to check it.
     * Detail: which call, and the status it answered with.
     */
    AUTH_REJECTED,

    /** docs/08 "오류": the provider is out of quota or rate-limiting (429, 402). Detail: the call. */
    QUOTA,

    /**
     * docs/08 "오류": trouble at the provider's end — 5xx, a dropped connection, a body that will
     * not parse, a submission it declared failed. Retried. Detail: what it said.
     */
    PROVIDER_ERROR,

    /** docs/08 "오류": a 4xx that rejected the audio itself. Detail: the call and the status. */
    UNSUPPORTED_AUDIO,

    /** docs/08 "오류": the recording has no mono or mix track to transcribe. Detail: what it has. */
    NO_INPUT_TRACK,

    /**
     * docs/08 "오류": `resultTimeoutSec` passed with the submission still unfinished, so the next
     * attempt submits the audio again. Detail: the reference that was being polled.
     */
    RESULT_TIMEOUT,

    /** Something changed the workflow while it was open here — a second window, or an import. */
    STALE,

    /** Argument: the `schema` of a document this build is too old to read — an imported file
     * written by a newer build (docs/05 "워크플로우 가져오기"). */
    UNSUPPORTED_SCHEMA,
    ;

    /**
     * The wire form: `NEEDS_AUTH`, `MISSING_SECRET:webhook_secret`, or either of those followed by
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
         * existed wrote a bare `MISSING_SECRET` and `INVALID_SECRET: <complaint>` into the same
         * column, and both would otherwise read as the new wire form — one with no secret name at
         * all, the other with a sentence where the name goes. So the two keys that name a secret
         * are only a key when the argument really is a `secretRef` (docs/02).
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

        private val NAMES_A_SECRET = setOf(CoreMessage.MISSING_SECRET, CoreMessage.INVALID_SECRET)

        /** docs/02 `secretRef`, which is what a secret name is allowed to be. */
        private val SECRET_REF = Regex("^[a-z][a-z0-9_]{0,31}$")
    }
}
