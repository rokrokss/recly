@file:OptIn(ExperimentalTime::class)

package app.recly.android.auth

import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import recly.core.message.CoreMessage
import recly.core.platform.AuthRequiredException
import recly.core.platform.Clock
import recly.core.platform.SecureStore
import recly.core.platform.TokenProvider

/**
 * docs/06 Android: there is no refresh token to hold. A token that is within [REFRESH_MARGIN] of
 * expiry is re-fetched from [Authorizer], which is silent for an account that already granted the
 * two scopes and needs an activity otherwise.
 *
 * Access token and expiry are the only things persisted, in [SecureStore.TOKENS], so a cold start
 * inside a Worker does not have to go to Play Services first.
 *
 * The mutex keeps two jobs from authorizing at once; the core runs steps concurrently.
 */
class AndroidTokenProvider(
    private val authorizer: Authorizer,
    private val secureStore: SecureStore,
    private val clock: Clock,
) : TokenProvider {

    private data class Token(val value: String, val expiresAt: Instant)

    private val mutex = Mutex()

    @Volatile private var cached: Token? = null

    override suspend fun accessToken(): String = mutex.withLock {
        val held = cached ?: read()?.also { cached = it }
        if (held != null && clock.now() < held.expiresAt - REFRESH_MARGIN) return@withLock held.value
        // Play Services caches the grant, so re-authorizing on a near-expired token returns that
        // same token with a fresh hour claimed on it. Evict first or the refresh is a no-op.
        if (held != null) authorizer.clearToken(held.value)
        when (val result = authorizer.authorize()) {
            is AuthorizeResult.Granted -> remember(Token(result.accessToken, result.expiresAt)).value
            AuthorizeResult.NeedsConsent ->
                // docs/07 §5: a key, not a sentence — the app screen turns it into words.
                throw AuthRequiredException(CoreMessage.DRIVE_REAUTH)
            is AuthorizeResult.Failed -> throw AuthorizationFailedException(result.reason)
        }
    }

    /**
     * After a 401 the stored token is worthless, so it goes — from memory, from the store, and
     * from Play Services' cache, which is the only one of the three that would otherwise hand it
     * back.
     */
    override suspend fun invalidate(): Unit = mutex.withLock {
        val held = cached ?: read()
        if (held != null) authorizer.clearToken(held.value)
        forget()
    }

    /** The interactive half ([GoogleAuth]) already holds a fresh grant; this adopts it. */
    suspend fun adopt(accessToken: String, expiresAt: Instant): Unit = mutex.withLock {
        remember(Token(accessToken, expiresAt))
        Unit
    }

    private suspend fun read(): Token? {
        val value = secureStore.get(SecureStore.TOKENS, KEY_ACCESS)?.decodeToString() ?: return null
        val expiresAt = secureStore.get(SecureStore.TOKENS, KEY_EXPIRES_AT)
            ?.decodeToString()
            ?.toLongOrNull()
            ?: return null
        return Token(value, Instant.fromEpochMilliseconds(expiresAt))
    }

    private suspend fun remember(token: Token): Token {
        secureStore.put(SecureStore.TOKENS, KEY_ACCESS, token.value.encodeToByteArray())
        secureStore.put(
            SecureStore.TOKENS,
            KEY_EXPIRES_AT,
            token.expiresAt.toEpochMilliseconds().toString().encodeToByteArray(),
        )
        cached = token
        return token
    }

    private suspend fun forget() {
        cached = null
        secureStore.delete(SecureStore.TOKENS, KEY_ACCESS)
        secureStore.delete(SecureStore.TOKENS, KEY_EXPIRES_AT)
    }

    companion object {
        /** docs/06: refresh once the token is within a minute of dying. */
        val REFRESH_MARGIN = 60.seconds

        private const val KEY_ACCESS = "google_access_token"
        private const val KEY_EXPIRES_AT = "google_access_token_expires_at"
    }
}

/**
 * Transient authorization trouble — offline, Play Services missing. Distinct from
 * [AuthRequiredException] on purpose: this one is worth retrying, that one needs the user.
 */
class AuthorizationFailedException(reason: String) : Exception(reason)
