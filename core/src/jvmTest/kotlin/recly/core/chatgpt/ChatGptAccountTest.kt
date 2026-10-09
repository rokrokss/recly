@file:OptIn(ExperimentalTime::class)

package recly.core.chatgpt

import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import recly.core.platform.HttpPlan
import recly.core.platform.HttpResult
import recly.core.platform.Transport
import recly.core.testing.testDeps
import recly.core.chatgpt.ChatGptHarness.Companion.CLIENT
import recly.core.chatgpt.ChatGptHarness.Companion.EMAIL
import recly.core.chatgpt.ChatGptHarness.Companion.REDIRECT
import recly.core.message.CoreMessage

class ChatGptAccountTest {
    private val h = ChatGptHarness()

    @Test
    fun `a first sign-in registers this device, keeps the issued client id and lists the plan's models`() = runBlocking {
        val signIn = h.account.beginSignIn(REDIRECT)
        val query = Url(signIn.authorizationUrl).parameters
        assertEquals("https://auth.openai.com/api/accounts/authorize", signIn.authorizationUrl.substringBefore('?'))
        assertEquals("dynamic_agent_client", query["client_id"])
        assertEquals("Recly", query["agent_name_hint"])
        assertEquals(ChatGptAccount.SCOPE, query["scope"])
        assertEquals("https://api.openai.com/v1", query["resource"])
        assertEquals(REDIRECT, query["redirect_uri"])
        assertEquals("S256", query["code_challenge_method"])
        assertEquals(signIn.state, query["state"])
        assertTrue(query["ext_agent_host_id"]!!.startsWith("urn:uuid:"))
        assertNull(query["login_hint"])
        h.account.cancelSignIn()

        assertEquals(ChatGptResult.Done(welcome = true), h.signIn())
        val exchange = form(h.server.request(0).text)
        assertEquals("authorization_code", exchange["grant_type"])
        assertEquals(CLIENT, exchange["client_id"], "the issued id, not the registration entrypoint")
        assertEquals(REDIRECT, exchange["redirect_uri"])
        assertEquals("https://api.openai.com/v1", exchange["resource"])
        assertTrue(exchange["code_verifier"]!!.length >= 43)
        assertEquals("Bearer access-1", h.server.request(1).headers["Authorization"])
        assertEquals(
            ChatGptConnection.SignedIn(EMAIL, listOf(ChatGptModel("gpt-a", "GPT A"), ChatGptModel("gpt-b", "GPT B")), "gpt-a"),
            h.account.observe().value,
        )
        assertEquals(setOf("host", "registration", "session", "session.a.0"), h.held().keys)
        assertTrue(h.store.entries.keys.none { it.startsWith("tokens/") }, "a Drive disconnect must not reach it")
        assertTrue("chatgpt.signin" in h.logger.events)
    }

    @Test
    fun `signing in again reuses the client id and the host, with a login hint and no app name`() = runBlocking {
        h.signIn()
        val host = h.held()["host"]
        h.server.reply("""{"issuer":"https://auth.openai.com","revocation_endpoint":"https://auth.openai.com/oauth/revoke"}""")
        h.server.reply("", status = 200)
        assertEquals(ChatGptResult.Done(), h.account.signOut())
        val revoke = form(h.server.requests.last().text)
        assertEquals("refresh-1", revoke["token"])
        assertEquals(CLIENT, revoke["client_id"])
        assertEquals(setOf("host", "registration"), h.held().keys, "signing out keeps the registration for the next sign-in")
        assertEquals(ChatGptConnection.SignedOut, h.account.observe().value)

        val signIn = h.account.beginSignIn(REDIRECT)
        val query = Url(signIn.authorizationUrl).parameters
        assertEquals(CLIENT, query["client_id"])
        assertEquals(EMAIL, query["login_hint"])
        assertEquals(host, query["ext_agent_host_id"])
        assertNull(query["agent_name_hint"])
        h.server.reply(h.tokens("access-2", "refresh-2", nonce = query["nonce"]))
        h.server.reply(ChatGptHarness.MODELS)
        // A returning sign-in's callback may carry no client id at all.
        assertEquals(ChatGptResult.Done(welcome = false), h.account.finishSignIn("$REDIRECT?code=code-2&state=${signIn.state}"))
        assertEquals(CLIENT, form(h.server.requests[h.server.requests.size - 2].text)["client_id"])
    }

    @Test
    fun `a stray callback is refused without ending the sign-in, and a declined one cancels it`() = runBlocking {
        val signIn = h.account.beginSignIn(REDIRECT)
        assertEquals(
            ChatGptResult.Failed(CoreMessage.PROVIDER_ERROR.code(detail = "state mismatch")),
            h.account.finishSignIn("$REDIRECT?code=x&state=someone-else"),
        )
        assertEquals(
            ChatGptResult.Failed(CoreMessage.SIGN_IN_CANCELLED.code()),
            h.account.finishSignIn("$REDIRECT?error=access_denied&state=${signIn.state}"),
        )
        assertTrue(h.server.requests.isEmpty())
        assertEquals(setOf("host"), h.held().keys)
    }

    @Test
    fun `a grant without the plan scope saves nothing`() = runBlocking {
        val signIn = h.account.beginSignIn(REDIRECT)
        val nonce = Url(signIn.authorizationUrl).parameters["nonce"]
        h.server.reply(h.tokens("access-1", "refresh-1", nonce = nonce, scope = "openid profile email offline_access"))
        h.server.reply("""{"issuer":"https://auth.openai.com","revocation_endpoint":"https://auth.openai.com/oauth/revoke"}""")
        h.server.reply("", status = 200)
        assertEquals(
            ChatGptResult.Failed(CoreMessage.CHATGPT_PLAN_REQUIRED.code()),
            h.account.finishSignIn("$REDIRECT?code=c&state=${signIn.state}&client_id=$CLIENT"),
        )
        assertEquals(setOf("host", "registration"), h.held().keys, "the client id is kept, so a retry does not register again")
        assertEquals("refresh-1", form(h.server.requests.last().text)["token"], "the tokens it cannot use are revoked")
    }

    @Test
    fun `an ID token from another sign-in is refused`() = runBlocking {
        val signIn = h.account.beginSignIn(REDIRECT)
        h.server.reply(h.tokens("access-1", "refresh-1", nonce = "not-this-sign-in"))
        assertEquals(
            ChatGptResult.Failed(CoreMessage.PROVIDER_ERROR.code(detail = "id token nonce")),
            h.account.finishSignIn("$REDIRECT?code=c&state=${signIn.state}&client_id=$CLIENT"),
        )
    }

    @Test
    fun `the refresh token rotates, and an unusable one ends the sign-in but keeps the registration`() = runBlocking {
        h.signIn()
        h.clock.advance(3600.seconds)
        h.server.reply(h.tokens("access-2", "refresh-2", idToken = false))
        h.server.reply(ChatGptHarness.MODELS)
        assertTrue(h.account.refresh() is ChatGptConnection.SignedIn)
        val refresh = form(h.server.requests[2].text)
        assertEquals("refresh_token", refresh["grant_type"])
        assertEquals("refresh-1", refresh["refresh_token"])
        assertEquals(CLIENT, refresh["client_id"])
        assertNull(refresh["scope"], "omitted to keep the grant")
        assertEquals("b:1", h.held()["session"], "the next generation, written whole before the old one goes")
        assertEquals(setOf("host", "registration", "session", "session.b.0"), h.held().keys)
        assertTrue("refresh-2" in h.held()["session.b.0"]!!)
        assertEquals("Bearer access-2", h.server.requests[3].headers["Authorization"])

        h.clock.advance(3600.seconds)
        h.server.reply("""{"error":"refresh_token_invalidated"}""", status = 400)
        assertEquals(ChatGptConnection.Expired(EMAIL), h.account.refresh())
        assertEquals(setOf("host", "registration"), h.held().keys)
        assertTrue("chatgpt.refresh.refused" in h.logger.events)
        // Settings opened later still says OpenAI ended it, and signing out clears that.
        assertEquals(ChatGptConnection.Expired(EMAIL), ChatGptAccount(h.db, h.deps).refresh())
        assertEquals(ChatGptResult.Done(), h.account.signOut())
        assertEquals(ChatGptConnection.SignedOut, h.account.refresh())
    }

    @Test
    fun `a refresh that leaves out the scope keeps the grant`() = runBlocking {
        h.signIn()
        h.clock.advance(3600.seconds)
        h.server.reply("""{"access_token":"access-2","refresh_token":"refresh-2","expires_in":3600}""")
        h.server.reply(ChatGptHarness.MODELS)
        assertTrue(h.account.refresh() is ChatGptConnection.SignedIn)
    }

    @Test
    fun `a sign-out during the model list wins`() = runBlocking {
        h.signIn()
        val reached = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val account = ChatGptAccount(h.db, testDeps(
            clock = h.clock, secureStore = h.store,
            transport = object : Transport {
                override suspend fun execute(plan: HttpPlan): HttpResult {
                    reached.complete(Unit)
                    gate.await()
                    return HttpResult(200, emptyMap(), ChatGptHarness.MODELS.encodeToByteArray())
                }
            },
        ))
        val refreshing = async(Dispatchers.Default) { account.refresh() }
        reached.await()
        // What signOut leaves, written while the list is on its way.
        h.store.entries.keys.filter { it.startsWith("chatgpt/session") }.forEach { h.store.entries.remove(it) }
        gate.complete(Unit)
        assertEquals(ChatGptConnection.SignedOut, refreshing.await())
    }

    @Test
    fun `a working token is not refreshed before earliest_refresh_at`() = runBlocking {
        h.signIn(earliestRefreshAt = h.clock.now().epochSeconds + 3580)
        h.clock.advance(3550.seconds)
        h.server.reply(ChatGptHarness.MODELS)
        h.account.refresh()
        assertEquals("Bearer access-1", h.server.requests.last().headers["Authorization"])
        assertEquals(3, h.server.requests.size, "no token request")
    }

    @Test
    fun `a 401 refreshes once and tries again, and a second one ends the sign-in`() = runBlocking {
        h.signIn()
        h.server.reply("""{"detail":"Unauthorized"}""", status = 401)
        h.server.reply(h.tokens("access-2", "refresh-2", idToken = false))
        h.server.reply(ChatGptHarness.MODELS)
        assertTrue(h.account.refresh() is ChatGptConnection.SignedIn)
        assertEquals("Bearer access-2", h.server.requests.last().headers["Authorization"])

        h.server.reply("""{"detail":"Unauthorized"}""", status = 401)
        h.server.reply(h.tokens("access-3", "refresh-3", idToken = false))
        h.server.reply("""{"detail":"Unauthorized"}""", status = 401)
        assertEquals(ChatGptConnection.Expired(EMAIL), h.account.refresh())
        assertFalse("session" in h.held())
    }

    @Test
    fun `a session longer than one Windows credential is kept in pieces, and shrinks cleanly`() = runBlocking {
        h.signIn(access = "a".repeat(4500))
        assertEquals(setOf("host", "registration", "session", "session.a.0", "session.a.1", "session.a.2"), h.held().keys)
        h.clock.advance(3600.seconds)
        h.server.reply(h.tokens("short", "refresh-2", idToken = false))
        h.server.reply(ChatGptHarness.MODELS)
        h.account.refresh()
        assertEquals(setOf("host", "registration", "session", "session.b.0"), h.held().keys)
        assertEquals("Bearer short", h.server.requests.last().headers["Authorization"])
    }

    @Test
    fun `the chosen model is kept while the plan lists it`() = runBlocking {
        h.signIn()
        h.account.selectModel("gpt-b")
        assertEquals("gpt-b", (h.account.observe().value as ChatGptConnection.SignedIn).model)
        h.server.reply("""{"models":[{"slug":"gpt-a","display_name":"GPT A","visibility":"list"}]}""")
        assertEquals("gpt-a", (h.account.refresh() as ChatGptConnection.SignedIn).model)
    }

    @Test
    fun `an App Store build shows nothing until the storefront allows it`() = runBlocking {
        fun account(country: String) = ChatGptAccount(
            h.db,
            recly.core.testing.testDeps(
                secureStore = h.store,
                transcriptionPolicy = recly.core.transcribe.TranscriptionPolicy(
                    object : recly.core.transcribe.AppStoreRegion {
                        override suspend fun countryCode() = country
                    },
                ),
            ),
        )
        val china = account("CHN")
        assertEquals(ChatGptConnection.Unavailable, china.observe().value)
        assertEquals(ChatGptConnection.Unavailable, china.refresh())
        val korea = account("KOR")
        assertEquals(ChatGptConnection.Unavailable, korea.observe().value, "not before the storefront is known")
        assertEquals(ChatGptConnection.SignedOut, korea.refresh())
    }

    @Test
    fun `usage and eligibility errors get their own messages`() {
        fun code(status: Int, body: String) =
            ChatGptAccount.failure(recly.core.platform.HttpResult(status, emptyMap(), body.encodeToByteArray())).reason
        assertEquals(CoreMessage.CHATGPT_USAGE_LIMIT.code(), code(429, """{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}"""))
        assertEquals(CoreMessage.CHATGPT_PLAN_REQUIRED.code(), code(403, """{"error":{"code":"subscription_sharing_user_not_eligible"}}"""))
        assertEquals(CoreMessage.CHATGPT_SIGN_IN_REQUIRED.code(), code(401, """{"error":{"code":"subscription_sharing_invalid_user"}}"""))
        assertEquals(CoreMessage.PROVIDER_ERROR.code(detail = "503 subscription_sharing_usage_unavailable"), code(503, """{"error":{"code":"subscription_sharing_usage_unavailable"}}"""))
        assertEquals(CoreMessage.PROVIDER_ERROR.code(detail = "403 Region not supported"), code(403, """{"detail":"Region not supported"}"""))
    }
}
