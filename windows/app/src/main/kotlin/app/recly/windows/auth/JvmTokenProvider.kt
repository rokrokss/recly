@file:OptIn(ExperimentalTime::class)

package app.recly.windows.auth

import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import recly.core.message.CoreMessage
import recly.core.platform.AuthRequiredException
import recly.core.platform.Clock
import recly.core.platform.Logger
import recly.core.platform.SecureStore
import recly.core.platform.TokenProvider

/**
 * docs/06 Windows: unlike the phone, this shell holds the refresh token itself, so a token within
 * [REFRESH_MARGIN] of expiry is renewed here without anything interactive happening. The refresh
 * token lives in [SecureStore.TOKENS] — Credential Manager on Windows.
 *
 * The access token is stored beside it so a cold start does not have to spend a round trip before
 * the first job can run.
 *
 * The mutex keeps two jobs from refreshing at once; the core runs steps concurrently.
 */
class JvmTokenProvider(
    private val store: SecureStore,
    private val clock: Clock,
    private val endpoint: TokenEndpoint,
    private val logger: Logger,
) : TokenProvider {

    private data class Token(val value: String, val expiresAt: Instant)

    private val mutex = Mutex()

    @Volatile private var cached: Token? = null

    override suspend fun accessToken(): String = mutex.withLock {
        val held = cached ?: readAccess()?.also { cached = it }
        if (held != null && clock.now() < held.expiresAt - REFRESH_MARGIN) return@withLock held.value
        val refreshToken = store.get(SecureStore.TOKENS, KEY_REFRESH)?.decodeToString()
            ?: throw AuthRequiredException(CoreMessage.NEEDS_AUTH)
        val response = try {
            endpoint.refresh(refreshToken)
        } catch (e: TokenEndpointException) {
            // `invalid_grant` is the grant itself being gone — revoked, expired after six months of
            // silence, or the account's 100-token limit rolling this one off (docs/06). Nothing to
            // retry: the stored token is worthless and only a sign-in replaces it.
            if (e.error == "invalid_grant") {
                forget()
                logger.log(Logger.Level.WARN, "auth.refresh.invalidGrant")
                throw AuthRequiredException(CoreMessage.DRIVE_REAUTH)
            }
            throw e
        }
        logger.log(Logger.Level.INFO, "auth.refresh.ok", mapOf("expiresInSec" to response.expiresInSec))
        remember(response).value
    }

    /**
     * After a 401 the token is worthless: it goes from memory and from the store, so the next
     * [accessToken] refreshes instead of handing back the one Drive just rejected. The refresh
     * token stays — it is not what was refused.
     */
    override suspend fun invalidate(): Unit = mutex.withLock {
        cached = null
        store.delete(SecureStore.TOKENS, KEY_ACCESS)
        store.delete(SecureStore.TOKENS, KEY_EXPIRES_AT)
    }

    suspend fun isSignedIn(): Boolean = store.get(SecureStore.TOKENS, KEY_REFRESH) != null

    /**
     * The refresh token itself, for the one caller that needs the value rather than its effect:
     * docs/03's "연결 해제" hands it to Google's revocation endpoint ([TokenEndpoint.revoke]). Null
     * once this device has none, which is what makes a second disconnect skip the revoke.
     */
    suspend fun refreshToken(): String? = store.get(SecureStore.TOKENS, KEY_REFRESH)?.decodeToString()

    /**
     * The interactive half ([GoogleAuth]) has a fresh grant; this is where it lands. A response
     * without a refresh token leaves the stored one alone — a re-sign-in on a device that already
     * has one is answered that way, and it is still the token that works.
     */
    suspend fun adopt(response: TokenResponse): Unit = mutex.withLock {
        remember(response)
        Unit
    }

    suspend fun signOut(): Unit = mutex.withLock { forget() }

    private suspend fun readAccess(): Token? {
        val value = store.get(SecureStore.TOKENS, KEY_ACCESS)?.decodeToString() ?: return null
        val expiresAt = store.get(SecureStore.TOKENS, KEY_EXPIRES_AT)?.decodeToString()?.toLongOrNull()
            ?: return null
        return Token(value, Instant.fromEpochMilliseconds(expiresAt))
    }

    private suspend fun remember(response: TokenResponse): Token {
        val token = Token(response.accessToken, clock.now() + response.expiresInSec.seconds)
        store.put(SecureStore.TOKENS, KEY_ACCESS, token.value.encodeToByteArray())
        store.put(
            SecureStore.TOKENS,
            KEY_EXPIRES_AT,
            token.expiresAt.toEpochMilliseconds().toString().encodeToByteArray(),
        )
        response.refreshToken?.let { store.put(SecureStore.TOKENS, KEY_REFRESH, it.encodeToByteArray()) }
        cached = token
        return token
    }

    private suspend fun forget() {
        cached = null
        store.delete(SecureStore.TOKENS, KEY_ACCESS)
        store.delete(SecureStore.TOKENS, KEY_EXPIRES_AT)
        store.delete(SecureStore.TOKENS, KEY_REFRESH)
    }

    companion object {
        /** docs/06: refresh once the token is within a minute of dying. */
        val REFRESH_MARGIN = 60.seconds

        private const val KEY_ACCESS = "google_access_token"
        private const val KEY_EXPIRES_AT = "google_access_token_expires_at"
        private const val KEY_REFRESH = "google_refresh_token"
    }
}
