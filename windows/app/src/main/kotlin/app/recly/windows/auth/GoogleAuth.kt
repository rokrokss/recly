@file:OptIn(ExperimentalTime::class)

package app.recly.windows.auth

import app.recly.windows.core.Host
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.StringTable
import app.recly.windows.i18n.Strings
import app.recly.windows.i18n.UiMessage
import app.recly.windows.i18n.message
import app.recly.windows.i18n.text
import java.awt.Desktop
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import recly.core.platform.Logger

/** What [GoogleAuth.revokeAccess] managed — docs/03's "연결 해제", minus the core's own clean-up. */
sealed interface RevokeResult {
    data object Revoked : RevokeResult

    /** Nothing to revoke: the local half of the disconnect is still worth doing. */
    data object NotSignedIn : RevokeResult

    data class Failed(val reason: String) : RevokeResult
}

sealed interface SignInResult {
    data object Ok : SignInResult

    /** The client id was never filled in (README): the flow cannot even be started. */
    data object NoClient : SignInResult

    /**
     * [reason] is what went wrong, as a name rather than a sentence (docs/07 rule 3): what this app
     * decided is a key, and what Google said is its own words carried in a [UiMessage.Text]. The
     * tray shows it under [app.recly.windows.i18n.Str.STATUS_SIGN_IN_FAILED].
     */
    data class Failed(val reason: UiMessage) : SignInResult
}

/**
 * docs/06 Windows: system browser + PKCE + a loopback redirect. The refresh token that comes back
 * is the only durable half — it goes to the secure store through [JvmTokenProvider], and every
 * access token after this is minted from it without the user seeing anything.
 *
 * `access_type=offline` is what asks for that refresh token; for an installed client Google returns
 * one either way ("refresh tokens are always returned for installed applications"), so nothing here
 * forces a consent screen to get it — see [authorizeUrl].
 */
class GoogleAuth(
    private val tokens: JvmTokenProvider,
    private val endpoint: TokenEndpoint,
    private val logger: Logger,
    /** The language the browser page the redirect lands on is written in (docs/07). */
    strings: () -> Strings = { StringTable.of(StringTable.BASE) },
    private val receiver: LoopbackReceiver = LoopbackReceiver(logger, strings),
    /** How the consent URL is opened. A test hands in its own instead of a browser. */
    private val browser: suspend (String) -> Unit = { openInSystemBrowser(it) },
    private val random: SecureRandom = SecureRandom(),
    /** How long the loopback waits for the consent screen. The acceptance harness waits longer. */
    private val consentTimeout: Duration = CONSENT_TIMEOUT,
    /** How long [browser] gets to hand the URL over before the sign-in gives up on it. */
    private val browserTimeout: Duration = BROWSER_TIMEOUT,
) {
    /** True once this device holds a refresh token — the settings window offers sign-out, not sign-in. */
    suspend fun isSignedIn(): Boolean = tokens.isSignedIn()

    suspend fun signIn(): SignInResult {
        if (endpoint.endpoints.clientId.startsWith(PLACEHOLDER)) {
            logger.log(Logger.Level.WARN, "auth.signIn.noClient")
            return SignInResult.NoClient
        }
        val verifier = Pkce.verifier(random)
        val state = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(STATE_BYTES).also { random.nextBytes(it) })
        // Whether this device already has a grant is the whole of the `prompt` decision below.
        val returning = tokens.isSignedIn()
        return try {
            var redirectUri = ""
            val code = receiver.awaitCode(state, consentTimeout, browserTimeout) { uri ->
                redirectUri = uri
                browser(authorizeUrl(uri, Pkce.challenge(verifier), state, returning))
            }
            val response = endpoint.exchange(code, verifier, redirectUri)
            if (response.refreshToken == null && !returning) {
                // Google documents a refresh token as always coming back for an installed client, so
                // this is not a state to paper over: without one there is nothing to refresh with.
                return SignInResult.Failed(Str.AUTH_NO_REFRESH_TOKEN.message())
            }
            // A re-sign-in that answers without one keeps the token this device already holds.
            tokens.adopt(response)
            logger.log(Logger.Level.INFO, "auth.signIn.ok")
            SignInResult.Ok
        } catch (e: AuthDeclinedException) {
            // The log is English whatever the app is in (CONTRIBUTING), so the reason is read off
            // the base table rather than the user's.
            val reason = e.reason.text(StringTable.of(StringTable.BASE))
            logger.log(Logger.Level.WARN, "auth.signIn.declined", mapOf("reason" to reason))
            SignInResult.Failed(e.reason)
        } catch (e: Exception) {
            logger.log(Logger.Level.ERROR, "auth.signIn.failed", error = e)
            SignInResult.Failed(UiMessage.Text(e.message ?: e::class.simpleName.orEmpty()))
        }
    }

    /**
     * Drops the grant this device holds, and deliberately does *not* call Google's `/revoke`:
     * revocation is per project, not per device — "Revocation removes all OAuth 2.0 scopes
     * previously granted to a project, invalidating any issued access or refresh tokens for all
     * clients registered under that project" — so signing out of the PC would sign the user out of
     * their phone and watch too (docs/06 Windows).
     */
    suspend fun signOut() {
        tokens.signOut()
        logger.log(Logger.Level.INFO, "auth.signOut")
    }

    /**
     * docs/03 "연결 해제" · docs/06: the half of a disconnect that is Google's — the grant this
     * project holds goes back, which is exactly what [signOut] deliberately does not do and what
     * the warning dialog told the user would happen to their other devices as well.
     *
     * The identity is dropped whatever the endpoint answers: the user asked for this PC to be done
     * with the account, and a revoke that failed leaves the grant standing for them to take down in
     * their Google account permissions — which is what [RevokeResult.Failed] is reported as.
     */
    suspend fun revokeAccess(): RevokeResult {
        val token = tokens.refreshToken() ?: return RevokeResult.NotSignedIn
        return try {
            endpoint.revoke(token)
            tokens.signOut()
            logger.log(Logger.Level.INFO, "auth.revoke.ok")
            RevokeResult.Revoked
        } catch (e: Exception) {
            logger.log(Logger.Level.WARN, "auth.revoke.failed", error = e)
            RevokeResult.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    /**
     * The consent URL. [returning] is whether this device already holds a refresh token, and it
     * decides `prompt`:
     *
     * - no grant here yet — **no `prompt` at all**. Google shows the consent screen "only the first
     *   time your project requests access", and warns to "include `prompt=consent` only when
     *   necessary"; forcing it would put the consent screen in front of a user who granted this on
     *   their phone last week, for nothing — an installed client is handed a refresh token either
     *   way ("refresh tokens are always returned for installed applications").
     * - a grant already here — `select_account`, because the only reason to sign in again is to
     *   change which account this PC uploads as.
     *
     * No `include_granted_scopes`: Google documents incremental authorization as unsupported for
     * installed apps. No `login_hint` either — the app asks for no profile scope (ADR-009), so it
     * never learns the address that parameter wants (docs/06 Windows).
     */
    internal fun authorizeUrl(
        redirectUri: String,
        challenge: String,
        state: String,
        returning: Boolean,
    ): String {
        val parameters = buildMap {
            put("client_id", endpoint.endpoints.clientId)
            put("redirect_uri", redirectUri)
            put("response_type", "code")
            put("scope", OAuthEndpoints.SCOPES.joinToString(" "))
            put("code_challenge", challenge)
            put("code_challenge_method", "S256")
            put("state", state)
            put("access_type", "offline")
            if (returning) put("prompt", "select_account")
        }
        return endpoint.endpoints.authorize + "?" +
            parameters.entries.joinToString("&") { "${urlEncode(it.key)}=${urlEncode(it.value)}" }
    }

    private companion object {
        const val PLACEHOLDER = "REPLACE_ME"
        const val STATE_BYTES = 16

        /** Long enough to find the password manager, short enough that a forgotten tab expires. */
        val CONSENT_TIMEOUT = 5.minutes

        /** Handing a URL to the shell is not a round trip; anything this long is a browser missing. */
        val BROWSER_TIMEOUT = 30.seconds
    }
}

/**
 * `Desktop.browse` where the JDK has it, and the shell's own opener where it does not — a JVM
 * started without a display has no `Desktop`, and on Windows `rundll32` is the documented way in.
 */
internal fun openInSystemBrowser(url: String) {
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        Desktop.getDesktop().browse(URI(url))
        return
    }
    val command = if (Host.isWindows) {
        listOf("rundll32", "url.dll,FileProtocolHandler", url)
    } else {
        listOf("open", url)
    }
    ProcessBuilder(command).start()
}
