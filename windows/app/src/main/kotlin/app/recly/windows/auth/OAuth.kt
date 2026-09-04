package app.recly.windows.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import recly.core.platform.HttpBody
import recly.core.platform.HttpPlan
import recly.core.platform.Transport

/**
 * Where the flow goes and who it says it is. Defaults are Google's (docs/06 Windows); a test hands
 * in its own so the whole flow can run against a local endpoint.
 *
 * [SCOPES] is the whole grant and stays that way: both are non-sensitive, and adding one (a
 * calendar, say) would put the app into Google's verification process (docs/06 3).
 */
data class OAuthEndpoints(
    val authorize: String = "https://accounts.google.com/o/oauth2/v2/auth",
    val token: String = "https://oauth2.googleapis.com/token",
    /** docs/03 "연결 해제": where the grant this PC holds is handed back (docs/06 Windows). */
    val revoke: String = "https://oauth2.googleapis.com/revoke",
    val clientId: String = OAuthConfig.CLIENT_ID,
    /** Not a secret for a Desktop-type client — Google's own documentation says so (docs/06). */
    val clientSecret: String = OAuthConfig.CLIENT_SECRET,
) {
    companion object {
        val SCOPES = listOf(
            "https://www.googleapis.com/auth/drive.file",
        )
    }
}

/** What the token endpoint answered. [refreshToken] comes back on the first exchange only. */
data class TokenResponse(
    val accessToken: String,
    val expiresInSec: Long,
    val refreshToken: String?,
)

/**
 * The endpoint refused. [error] is the OAuth code (`invalid_grant`, …) when there is one:
 * `invalid_grant` on a refresh means the grant is gone for good and only a new sign-in fixes it,
 * which is a different thing from the network being down.
 */
class TokenEndpointException(
    val status: Int,
    val error: String?,
    message: String,
) : Exception(message)

/**
 * The one thing that talks to `oauth2.googleapis.com/token` — both the code exchange and the
 * refresh are the same form POST with a different `grant_type`.
 *
 * Over the core's [Transport] rather than an HTTP client of its own: the shell already owns one
 * (Ktor/OkHttp on the JVM) and a second would be a second set of timeouts to keep in step.
 */
class TokenEndpoint(
    private val transport: Transport,
    val endpoints: OAuthEndpoints = OAuthEndpoints(),
) {
    suspend fun exchange(code: String, verifier: String, redirectUri: String): TokenResponse = post(
        mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "code_verifier" to verifier,
            "redirect_uri" to redirectUri,
            "client_id" to endpoints.clientId,
            "client_secret" to endpoints.clientSecret,
        ),
    )

    suspend fun refresh(refreshToken: String): TokenResponse = post(
        mapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to endpoints.clientId,
            "client_secret" to endpoints.clientSecret,
        ),
    )

    /**
     * docs/03 "연결 해제": Google's revocation endpoint, which takes the token as a form field and
     * answers with an empty 200. Either token of the pair works and revoking one revokes the grant,
     * so the refresh token is what goes — it is the durable half, and the access token may already
     * have expired.
     *
     * **This is not what sign-out does** ([GoogleAuth.signOut]): revocation is per project, so it
     * takes the phone and the watch with it. Only the disconnect the user was warned about calls it.
     */
    suspend fun revoke(token: String) {
        val result = transport.execute(
            HttpPlan(
                method = "POST",
                url = endpoints.revoke,
                body = HttpBody.Text(encode(mapOf("token" to token)), "application/x-www-form-urlencoded"),
                timeoutSec = TIMEOUT_SEC,
            ),
        )
        if (result.status !in 200..299) {
            val error = runCatching { json.decodeFromString<JsonObject>(result.body.decodeToString()) }
                .getOrNull()?.get("error")?.jsonPrimitive?.content
            throw TokenEndpointException(result.status, error, "revoke ${result.status} ${error ?: ""}")
        }
    }

    private suspend fun post(form: Map<String, String>): TokenResponse {
        val result = transport.execute(
            HttpPlan(
                method = "POST",
                url = endpoints.token,
                body = HttpBody.Text(encode(form), "application/x-www-form-urlencoded"),
                timeoutSec = TIMEOUT_SEC,
            ),
        )
        val body = runCatching { json.decodeFromString<JsonObject>(result.body.decodeToString()) }.getOrNull()
        if (result.status !in 200..299) {
            val error = body?.get("error")?.jsonPrimitive?.content
            throw TokenEndpointException(result.status, error, "token endpoint ${result.status} ${error ?: ""}")
        }
        val access = body?.get("access_token")?.jsonPrimitive?.content
            ?: throw TokenEndpointException(result.status, null, "token endpoint answered without an access_token")
        return TokenResponse(
            accessToken = access,
            expiresInSec = body["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: DEFAULT_EXPIRY_SEC,
            refreshToken = body["refresh_token"]?.jsonPrimitive?.content,
        )
    }

    private fun encode(form: Map<String, String>): String = form.entries.joinToString("&") {
        "${urlEncode(it.key)}=${urlEncode(it.value)}"
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        const val TIMEOUT_SEC = 30
        /** Google always sends `expires_in`; an hour is what it sends. */
        const val DEFAULT_EXPIRY_SEC = 3600L
    }
}

/**
 * RFC 7636 S256. The verifier is what proves, at the token endpoint, that the code came back to the
 * process that asked for it — the loopback redirect is a port anything on the machine could have
 * been listening on, so without it the code alone would be enough.
 */
object Pkce {
    private const val VERIFIER_BYTES = 48

    fun verifier(random: SecureRandom = SecureRandom()): String =
        base64Url(ByteArray(VERIFIER_BYTES).also { random.nextBytes(it) })

    fun challenge(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray()))

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

internal fun urlEncode(value: String): String =
    java.net.URLEncoder.encode(value, Charsets.UTF_8)
