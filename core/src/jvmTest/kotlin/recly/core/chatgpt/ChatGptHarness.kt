@file:OptIn(ExperimentalTime::class)

package recly.core.chatgpt

import io.ktor.http.Url
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.encodeUtf8
import okio.fakefilesystem.FakeFileSystem
import recly.core.transcribe.ScriptedServer
import recly.core.testing.FakeClock
import recly.core.testing.FakeLogger
import recly.core.testing.MapSecureStore
import recly.core.testing.inMemoryDatabase
import recly.core.testing.testDeps

/** A [ChatGptAccount] against a scripted OpenAI, with the keychain and the clock in reach. */
internal class ChatGptHarness(requireTransferConsent: Boolean = false, locale: String = "en") {
    val server = ScriptedServer()
    val fs = FakeFileSystem()
    val clock = FakeClock()
    val logger = FakeLogger()
    val store = MapSecureStore()
    val db = inMemoryDatabase()
    val deps = testDeps(
        clock = clock,
        fileSystem = fs,
        logger = logger,
        secureStore = store,
        transport = server.transport(fs),
        requireTransferConsent = requireTransferConsent,
        locale = locale,
    )
    val account = ChatGptAccount(db, deps)

    /** The whole first sign-in: authorize, the callback, the exchange, and the model list after it. */
    suspend fun signIn(
        access: String = "access-1",
        refresh: String = "refresh-1",
        expiresIn: Long = 3600,
        earliestRefreshAt: Long? = null,
        models: String = MODELS,
    ): ChatGptResult {
        val signIn = account.beginSignIn(REDIRECT)
        val nonce = Url(signIn.authorizationUrl).parameters["nonce"]!!
        server.reply(tokens(access, refresh, expiresIn, earliestRefreshAt, nonce = nonce))
        server.reply(models)
        return account.finishSignIn("$REDIRECT?code=code-1&state=${signIn.state}&client_id=$CLIENT")
    }

    fun tokens(
        access: String,
        refresh: String?,
        expiresIn: Long = 3600,
        earliestRefreshAt: Long? = null,
        nonce: String? = null,
        scope: String = ChatGptAccount.SCOPE,
        subject: String = SUBJECT,
        idToken: Boolean = true,
    ): String = buildJsonObject {
        put("access_token", access)
        refresh?.let { put("refresh_token", it) }
        if (idToken) put("id_token", idToken(subject, nonce))
        put("token_type", "Bearer")
        put("expires_in", expiresIn)
        put("scope", scope)
        earliestRefreshAt?.let { put("earliest_refresh_at", it) }
    }.toString()

    fun idToken(subject: String, nonce: String?): String {
        val claims = buildJsonObject {
            put("iss", ChatGptAccount.AUTH)
            put("aud", CLIENT)
            put("sub", subject)
            put("email", EMAIL)
            put("exp", clock.now().epochSeconds + 3600)
            nonce?.let { put("nonce", it) }
        }
        fun part(text: String) = text.encodeUtf8().base64Url().trimEnd('=')
        return part("""{"alg":"RS256"}""") + "." + part(claims.toString()) + ".signature"
    }

    /** What the keychain holds for this account, by key, as text. */
    fun held(): Map<String, String> = store.entries.filterKeys { it.startsWith("chatgpt/") }
        .mapKeys { it.key.removePrefix("chatgpt/") }.mapValues { it.value.decodeToString() }

    companion object {
        const val REDIRECT = "http://127.0.0.1:50123/auth/callback"
        const val CLIENT = "oaiapp_test"
        const val SUBJECT = "user-1"
        const val EMAIL = "me@example.com"
        const val MODELS = """{"models":[
            {"slug":"gpt-a","display_name":"GPT A","visibility":"list"},
            {"slug":"gpt-internal","display_name":"Internal","visibility":"hide"},
            {"slug":"gpt-b","display_name":"GPT B","visibility":"list"},
            {"slug":"gpt-5.6-luna","display_name":"Hidden by Recly","visibility":"list"}]}"""
    }
}

/** A form body as its fields. */
internal fun form(text: String): Map<String, String> = text.split('&').associate {
    val (name, value) = it.split('=', limit = 2)
    java.net.URLDecoder.decode(name, "UTF-8") to java.net.URLDecoder.decode(value, "UTF-8")
}
