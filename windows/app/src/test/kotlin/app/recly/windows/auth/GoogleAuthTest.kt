@file:OptIn(ExperimentalTime::class)

package app.recly.windows.auth

import app.recly.windows.FixedClock
import app.recly.windows.MemorySecureStore
import app.recly.windows.SilentLogger
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.StringTable
import app.recly.windows.i18n.UiMessage
import app.recly.windows.i18n.message
import app.recly.windows.i18n.text
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import recly.core.platform.AuthRequiredException
import recly.core.platform.HttpBody
import recly.core.platform.HttpPlan
import recly.core.platform.HttpResult
import recly.core.platform.SecureStore
import recly.core.platform.Transport

/**
 * Deliverable 7: the whole docs/06 Windows flow, end to end — a real loopback receiver on a real
 * ephemeral port, a stand-in for the browser that fetches the redirect the way Chrome would, and a
 * fake token endpoint that checks the PKCE proof rather than taking the code's word for it.
 *
 * `runBlocking` and not `runTest`: the receiver's consent timeout is real time, and a virtual clock
 * that skips ahead while the HTTP round trip is in flight would expire the sign-in mid-test.
 */
class GoogleAuthTest {

    @Test
    fun `a sign-in exchanges the code for a refresh token this device keeps`() = runBlocking {
        val endpoint = FakeTokenEndpoint()
        val store = MemorySecureStore()
        val tokens = JvmTokenProvider(store, FixedClock(), endpoint.client(), SilentLogger)
        val auth = GoogleAuth(tokens, endpoint.client(), SilentLogger, browser = { visit(endpoint, it) })

        val result = auth.signIn()

        assertEquals(SignInResult.Ok, result)
        // The S256 proof is what the endpoint checked; without it the loopback code alone would be
        // enough for anything else on this machine that saw it.
        assertTrue(endpoint.verifierMatchedChallenge, "the token endpoint verified code_verifier")
        assertEquals(REFRESH, store.get(SecureStore.TOKENS, "google_refresh_token")?.decodeToString())
        assertEquals(ACCESS, tokens.accessToken())
        assertTrue(auth.isSignedIn())
    }

    @Test
    fun `the authorize URL asks for exactly the two docs 06 scopes, offline, with S256`() {
        val endpoint = FakeTokenEndpoint()
        val auth = GoogleAuth(
            JvmTokenProvider(MemorySecureStore(), FixedClock(), endpoint.client(), SilentLogger),
            endpoint.client(),
            SilentLogger,
        )

        val query = query(auth.authorizeUrl("http://127.0.0.1:1234", "challenge", "state", returning = false))

        // ADR-009: a second scope would put the app into Google's verification process.
        assertEquals("https://www.googleapis.com/auth/drive.file", query["scope"])
        assertEquals("S256", query["code_challenge_method"])
        assertEquals("offline", query["access_type"])
        assertEquals("code", query["response_type"])
        // docs/06: no `prompt` on a first sign-in — Google asks the first time a project wants
        // access anyway, and an installed client is handed a refresh token either way. Forcing
        // consent here is a screen for a user who already granted this on another device.
        assertNull(query["prompt"])
        // Documented as unsupported for installed apps, so asking for it is noise.
        assertNull(query["include_granted_scopes"])
    }

    @Test
    fun `a device that already holds a grant is asked to pick an account, not to consent again`() {
        val endpoint = FakeTokenEndpoint()
        val auth = GoogleAuth(
            JvmTokenProvider(MemorySecureStore(), FixedClock(), endpoint.client(), SilentLogger),
            endpoint.client(),
            SilentLogger,
        )

        val query = query(auth.authorizeUrl("http://127.0.0.1:1234", "challenge", "state", returning = true))

        // The only reason to sign in again from a PC that already uploads is to change the account.
        assertEquals("select_account", query["prompt"])
    }

    @Test
    fun `a re-sign-in that answers without a refresh token keeps the one this device holds`() = runBlocking {
        // Google only sends a refresh token with a *new* grant; a second pass through the consent
        // URL on the same grant answers without one, and the stored token is still the one that works.
        val endpoint = FakeTokenEndpoint(withRefreshToken = false)
        val store = MemorySecureStore()
        store.put(SecureStore.TOKENS, "google_refresh_token", REFRESH.encodeToByteArray())
        val tokens = JvmTokenProvider(store, FixedClock(), endpoint.client(), SilentLogger)
        var consentUrl = ""
        val auth = GoogleAuth(
            tokens,
            endpoint.client(),
            SilentLogger,
            browser = { url ->
                consentUrl = url
                visit(endpoint, url)
            },
        )

        assertEquals(SignInResult.Ok, auth.signIn())

        assertEquals("select_account", query(consentUrl)["prompt"], "the store is what decides the prompt")
        assertEquals(REFRESH, store.get(SecureStore.TOKENS, "google_refresh_token")?.decodeToString())
    }

    @Test
    fun `only the first redirect is answered`() = runBlocking {
        // The loopback port is open to anything on this machine for as long as the sign-in lasts.
        val endpoint = FakeTokenEndpoint()
        val auth = GoogleAuth(
            JvmTokenProvider(MemorySecureStore(), FixedClock(), endpoint.client(), SilentLogger),
            endpoint.client(),
            SilentLogger,
            browser = { url ->
                visit(endpoint, url)
                visit(endpoint, url) { redirect, state -> "$redirect?code=second&state=$state" }
            },
        )

        assertEquals(SignInResult.Ok, auth.signIn())

        assertEquals(CODE, endpoint.exchangedCode, "the second redirect was not the one exchanged")
        assertEquals(1, endpoint.exchanges)
    }

    @Test
    fun `a browser that never opens gives up instead of hanging`() = runBlocking {
        // `Desktop.browse` blocks until the OS has found a browser, and on a machine with none it
        // blocks for good — the sign-in has to end anyway.
        val endpoint = FakeTokenEndpoint()
        val auth = GoogleAuth(
            JvmTokenProvider(MemorySecureStore(), FixedClock(), endpoint.client(), SilentLogger),
            endpoint.client(),
            SilentLogger,
            browser = { awaitCancellation() },
            browserTimeout = 200.milliseconds,
        )

        assertIs<SignInResult.Failed>(auth.signIn())
        assertEquals(0, endpoint.exchanges)
    }

    @Test
    fun `a consent screen that comes back with an error is a decline, not a crash`() = runBlocking {
        val endpoint = FakeTokenEndpoint()
        val auth = GoogleAuth(
            JvmTokenProvider(MemorySecureStore(), FixedClock(), endpoint.client(), SilentLogger),
            endpoint.client(),
            SilentLogger,
            browser = { url ->
                visit(endpoint, url) { redirect, state -> "$redirect?error=access_denied&state=$state" }
            },
        )

        val result = auth.signIn()

        assertIs<SignInResult.Failed>(result)
        // docs/07 rule 4: Google's diagnostic is Google's words, carried as they arrived rather
        // than turned into a sentence this app would then have to translate.
        assertEquals(UiMessage.Text("access_denied"), result.reason)
        assertEquals(0, endpoint.exchanges, "a decline never reaches the token endpoint")
    }

    @Test
    fun `a code arriving with the wrong state is refused`() = runBlocking {
        // The loopback port is open to anything on this machine; `state` is what says the code came
        // from the sign-in this process started.
        val endpoint = FakeTokenEndpoint()
        val auth = GoogleAuth(
            JvmTokenProvider(MemorySecureStore(), FixedClock(), endpoint.client(), SilentLogger),
            endpoint.client(),
            SilentLogger,
            browser = { url ->
                visit(endpoint, url) { redirect, _ -> "$redirect?code=stolen&state=somebody-else" }
            },
        )

        val result = auth.signIn()

        assertIs<SignInResult.Failed>(result)
        // docs/07: the loopback's own verdict is a key, so the tray says it in the app's language.
        assertEquals(Str.AUTH_STATE_MISMATCH.message(), result.reason)
        assertEquals("이 로그인의 리디렉션이 아닙니다", result.reason.text(StringTable.of(StringTable.KOREAN)))
        assertEquals(0, endpoint.exchanges)
    }

    @Test
    fun `a first sign-in with no refresh token fails in the app's own words`() = runBlocking {
        // Google documents a refresh token as always coming back for an installed client; without
        // one there is nothing to refresh with, so this is a failure and not a quiet half-sign-in.
        val endpoint = FakeTokenEndpoint(withRefreshToken = false)
        val store = MemorySecureStore()
        val auth = GoogleAuth(
            JvmTokenProvider(store, FixedClock(), endpoint.client(), SilentLogger),
            endpoint.client(),
            SilentLogger,
            browser = { visit(endpoint, it) },
        )

        val result = auth.signIn()

        assertIs<SignInResult.Failed>(result)
        assertEquals(Str.AUTH_NO_REFRESH_TOKEN.message(), result.reason)
        assertNull(store.get(SecureStore.TOKENS, "google_refresh_token"))
    }

    @Test
    fun `an access token is renewed a minute before it expires and not before`() = runBlocking {
        val endpoint = FakeTokenEndpoint()
        val clock = FixedClock()
        val tokens = JvmTokenProvider(MemorySecureStore(), clock, endpoint.client(), SilentLogger)
        tokens.adopt(TokenResponse(ACCESS, expiresInSec = 120, refreshToken = REFRESH))

        assertEquals(ACCESS, tokens.accessToken())
        assertEquals(0, endpoint.refreshes)

        // docs/06: the margin is a minute, so with 61 seconds left the cached token still stands.
        clock.instant = clock.instant + 59.seconds
        assertEquals(ACCESS, tokens.accessToken())
        assertEquals(0, endpoint.refreshes)

        clock.instant = clock.instant + 2.seconds
        assertEquals(ACCESS, tokens.accessToken())
        assertEquals(1, endpoint.refreshes)
    }

    @Test
    fun `a revoked grant asks for a sign-in instead of retrying forever`() = runBlocking {
        // `invalid_grant` is the grant itself being gone (docs/06 "refresh token 한도").
        val endpoint = FakeTokenEndpoint(refreshError = "invalid_grant")
        val store = MemorySecureStore()
        val tokens = JvmTokenProvider(store, FixedClock(), endpoint.client(), SilentLogger)
        store.put(SecureStore.TOKENS, "google_refresh_token", REFRESH.encodeToByteArray())

        val thrown = runCatching { tokens.accessToken() }.exceptionOrNull()

        assertIs<AuthRequiredException>(thrown)
        assertNull(store.get(SecureStore.TOKENS, "google_refresh_token"), "the dead token is dropped")
    }

    /**
     * docs/03 "연결 해제": the half of a disconnect that is Google's. It is the *refresh* token that
     * goes — the durable half, and the one the endpoint is documented to take.
     */
    @Test
    fun `a disconnect hands the refresh token to the revocation endpoint and forgets it`() = runBlocking {
        val endpoint = FakeTokenEndpoint()
        val store = MemorySecureStore()
        val tokens = JvmTokenProvider(store, FixedClock(), endpoint.client(), SilentLogger)
        tokens.adopt(TokenResponse(ACCESS, expiresInSec = 3600, refreshToken = REFRESH))
        val auth = GoogleAuth(tokens, endpoint.client(), SilentLogger)

        assertEquals(RevokeResult.Revoked, auth.revokeAccess())

        assertEquals(REFRESH, endpoint.revokedToken)
        assertNull(store.get(SecureStore.TOKENS, "google_refresh_token"))
        assertNull(store.get(SecureStore.TOKENS, "google_access_token"))
    }

    /**
     * docs/03: a revoke Google refused leaves the grant standing, and only the user's account
     * permissions page can take it down — so it is reported rather than swallowed. The identity is
     * dropped by the caller either way (`ShellModel.runDisconnect`), because this PC is done with it.
     */
    @Test
    fun `a revoke the endpoint refuses is reported and keeps nothing quiet`() = runBlocking {
        val endpoint = FakeTokenEndpoint(revokeStatus = 400)
        val tokens = JvmTokenProvider(MemorySecureStore(), FixedClock(), endpoint.client(), SilentLogger)
        tokens.adopt(TokenResponse(ACCESS, expiresInSec = 3600, refreshToken = REFRESH))
        val auth = GoogleAuth(tokens, endpoint.client(), SilentLogger)

        assertIs<RevokeResult.Failed>(auth.revokeAccess())
    }

    /** A retry of a disconnect whose local half failed has no grant of its own left to take away. */
    @Test
    fun `a device with no refresh token has nothing to revoke`() = runBlocking {
        val endpoint = FakeTokenEndpoint()
        val tokens = JvmTokenProvider(MemorySecureStore(), FixedClock(), endpoint.client(), SilentLogger)
        val auth = GoogleAuth(tokens, endpoint.client(), SilentLogger)

        assertEquals(RevokeResult.NotSignedIn, auth.revokeAccess())
        assertNull(endpoint.revokedToken)
    }

    @Test
    fun `a 401 drops the access token and keeps the refresh token`() = runBlocking {
        val endpoint = FakeTokenEndpoint()
        val store = MemorySecureStore()
        val tokens = JvmTokenProvider(store, FixedClock(), endpoint.client(), SilentLogger)
        tokens.adopt(TokenResponse(ACCESS, expiresInSec = 3600, refreshToken = REFRESH))

        tokens.invalidate()

        assertNull(store.get(SecureStore.TOKENS, "google_access_token"))
        assertNotNull(store.get(SecureStore.TOKENS, "google_refresh_token"))
        // The next ask mints a new one rather than handing back what Drive just refused.
        assertEquals(ACCESS, tokens.accessToken())
        assertEquals(1, endpoint.refreshes)
    }

    /** Stands in for the browser: reads the consent URL and calls the redirect back, as Chrome does. */
    private suspend fun visit(
        endpoint: FakeTokenEndpoint,
        url: String,
        answer: (redirect: String, state: String) -> String = { redirect, state -> "$redirect?code=$CODE&state=$state" },
    ) = withContext(Dispatchers.IO) {
        val query = query(url)
        endpoint.challenge = query.getValue("code_challenge")
        val connection = URI(answer(query.getValue("redirect_uri"), query.getValue("state")))
            .toURL().openConnection() as HttpURLConnection
        runCatching { connection.inputStream.use { it.readBytes() } }
            .onFailure { connection.errorStream?.use { stream -> stream.readBytes() } }
        connection.disconnect()
    }

    private fun query(url: String): Map<String, String> = URI(url).query.split("&").associate {
        val (key, value) = it.split("=", limit = 2)
        key to URLDecoder.decode(value, Charsets.UTF_8)
    }

    private companion object {
        const val CODE = "4/authorization-code"
        const val ACCESS = "ya29.access"
        const val REFRESH = "1//refresh"
    }
}

/**
 * `oauth2.googleapis.com/token` as a [Transport]: it answers the two form posts docs/06 makes, and
 * it holds the flow to its side of the PKCE bargain.
 */
private class FakeTokenEndpoint(
    private val refreshError: String? = null,
    /** Google sends one with a new grant only; a second pass on the same grant answers without. */
    private val withRefreshToken: Boolean = true,
    /** docs/03 "연결 해제": what `/revoke` answers, when it is not the documented empty 200. */
    private val revokeStatus: Int = 200,
) : Transport {

    var exchanges = 0
        private set
    /** The token `/revoke` was handed, so a test can check it was the durable half. */
    var revokedToken: String? = null
        private set
    var exchangedCode: String? = null
        private set
    var refreshes = 0
        private set
    var verifierMatchedChallenge = false
        private set

    /** What the consent URL carried, so the exchange can be checked against it. */
    var challenge: String? = null

    fun client() = TokenEndpoint(
        this,
        OAuthEndpoints(
            authorize = "https://accounts.example/authorize",
            token = "https://oauth2.example/token",
            revoke = "https://oauth2.example/revoke",
            clientId = "client-id.apps.googleusercontent.com",
            clientSecret = "client-secret",
        ),
    )

    override suspend fun execute(plan: HttpPlan): HttpResult {
        val form = (plan.body as HttpBody.Text).text.split("&").associate {
            val (key, value) = it.split("=", limit = 2)
            key to URLDecoder.decode(value, Charsets.UTF_8)
        }
        if (plan.url.endsWith("/revoke")) {
            revokedToken = form["token"]
            // Google answers an empty 200; a refusal carries an `error` the shell reports.
            return if (revokeStatus in 200..299) {
                HttpResult(revokeStatus, emptyMap(), ByteArray(0))
            } else {
                json("""{"error":"invalid_token"}""", status = revokeStatus)
            }
        }
        return when (form["grant_type"]) {
            "authorization_code" -> {
                exchanges++
                exchangedCode = form.getValue("code")
                verifierMatchedChallenge = Pkce.challenge(form.getValue("code_verifier")) == challenge
                val refresh = if (withRefreshToken) ",\"refresh_token\":\"1//refresh\"" else ""
                json("""{"access_token":"ya29.access","expires_in":3600$refresh}""")
            }

            "refresh_token" -> {
                refreshes++
                if (refreshError != null) {
                    json("""{"error":"$refreshError"}""", status = 400)
                } else {
                    json("""{"access_token":"ya29.access","expires_in":3600}""")
                }
            }

            else -> json("""{"error":"unsupported_grant_type"}""", status = 400)
        }
    }

    private fun json(body: String, status: Int = 200) =
        HttpResult(status, mapOf("content-type" to listOf("application/json")), body.encodeToByteArray())
}
