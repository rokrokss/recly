package recly.core.platform

import recly.core.message.CoreMessage

/** Refreshing the token is the shell's job; the core only asks for a valid one. */
interface TokenProvider {
    /** @throws AuthRequiredException when the user must sign in again. */
    suspend fun accessToken(): String

    /** Called after a 401 so the next [accessToken] does not return the rejected token. */
    suspend fun invalidate()
}

/**
 * Interactive sign-in needed: the job parks in `NEEDS_AUTH` instead of burning retries.
 *
 * The message ends up in `step_run.last_error` and from there on a screen, so it is a
 * [CoreMessage] code (docs/07 §5) rather than a sentence.
 */
class AuthRequiredException(message: String) : Exception(message) {
    constructor(message: CoreMessage, arg: String? = null) : this(message.code(arg))
}
