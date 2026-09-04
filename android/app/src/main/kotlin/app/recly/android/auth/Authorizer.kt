@file:OptIn(ExperimentalTime::class)

package app.recly.android.auth

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.suspendCancellableCoroutine
import recly.core.platform.Clock
import recly.core.platform.Logger

/**
 * The `AuthorizationClient` half of docs/06, behind an interface so [AndroidTokenProvider]'s
 * expiry logic is testable without Play Services.
 */
interface Authorizer {
    /**
     * Silent re-authorization: there is no activity here (the caller may be a Worker), so a grant
     * that needs the consent screen comes back as [AuthorizeResult.NeedsConsent] instead of being
     * resolved.
     */
    suspend fun authorize(): AuthorizeResult

    /**
     * Evicts [token] from Play Services' own grant cache. Without this, `authorize()` hands the
     * *same* near-expired token straight back — with a fresh hour claimed on it — and a 401 loop
     * never breaks. Best effort: a failure here is logged, never thrown.
     */
    suspend fun clearToken(token: String)
}

sealed interface AuthorizeResult {
    data class Granted(val accessToken: String, val expiresAt: Instant) : AuthorizeResult

    /** `hasResolution()`: the user must approve in an activity. docs/06 parks the job in NEEDS_AUTH. */
    data object NeedsConsent : AuthorizeResult

    /** Offline, no Play Services, no account — transient, so the core keeps its retries. */
    data class Failed(val reason: String) : AuthorizeResult
}

/** The two ADR-009 scopes. Nothing else is ever requested: anything more turns the app sensitive. */
internal val DRIVE_SCOPES: List<Scope> = listOf(
    Scope("https://www.googleapis.com/auth/drive.file"),
)

internal fun driveAuthorizationRequest(): AuthorizationRequest = AuthorizationRequest.Builder()
    .setRequestedScopes(DRIVE_SCOPES)
    .build()

/**
 * `AuthorizationResult` carries no expiry, so we take docs/06 at its word: an access token from
 * `AuthorizationClient` lasts an hour from the moment it is handed over.
 */
internal val ACCESS_TOKEN_TTL = 1.hours

internal fun AuthorizationResult.toAuthorizeResult(now: Instant): AuthorizeResult {
    if (hasResolution()) return AuthorizeResult.NeedsConsent
    val token = accessToken ?: return AuthorizeResult.Failed("authorization returned no access token")
    return AuthorizeResult.Granted(token, now + ACCESS_TOKEN_TTL)
}

/** Application-context authorization: silent or nothing (docs/06 "액티비티가 없는 WorkManager 컨텍스트"). */
class PlayAuthorizer(
    private val context: Context,
    private val clock: Clock,
    private val logger: Logger,
) : Authorizer {
    override suspend fun authorize(): AuthorizeResult = try {
        Identity.getAuthorizationClient(context)
            .authorize(driveAuthorizationRequest())
            .await()
            .toAuthorizeResult(clock.now())
    } catch (e: Exception) {
        AuthorizeResult.Failed(e.message ?: e::class.java.simpleName)
    }

    override suspend fun clearToken(token: String) {
        try {
            Identity.getAuthorizationClient(context)
                .clearToken(ClearTokenRequest.builder().setToken(token).build())
                .await()
        } catch (e: Exception) {
            // The token is being thrown away either way; failing to evict it only costs us one
            // more round trip the next time the core asks.
            logger.log(Logger.Level.WARN, "auth.clearToken.failed", error = e)
        }
    }
}

/**
 * Play Services speaks `Task`, and `kotlinx-coroutines-play-services` is a whole dependency for
 * this one bridge.
 */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
