package app.recly.windows.auth

import app.recly.windows.SilentLogger
import app.recly.windows.i18n.Str
import app.recly.windows.i18n.StringTable
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import recly.core.chatgpt.ChatGptSignIn

/**
 * docs/15 §10 "Sign in with ChatGPT": the loopback OpenAI redirects to, on a real ephemeral port with a
 * stand-in for the browser that calls it the way Chrome would. The callback is the one request on
 * `/auth/callback` whose `state` is the sign-in's; anything else is a 404 and the wait goes on.
 *
 * `runBlocking` and not `runTest`, for [GoogleAuthTest]'s reason: the timeouts are real time.
 */
class LoopbackReceiverTest {

    private val receiver = LoopbackReceiver(SilentLogger)
    private val strings = StringTable.of(StringTable.BASE)

    @Test
    fun `the callback with the sign-in's state comes back whole and the browser is told it worked`() = runBlocking {
        var redirect = ""
        var answer: Answer? = null

        val url = receiver.awaitCallback(TIMEOUT, BROWSER_TIMEOUT, begin = { uri -> redirect = uri; signIn() }) {
            answer = get("$redirect?code=the-code&state=$STATE&client_id=app_123")
        }

        // The only redirect OpenAI accepts for a loopback (ChatGptAccount.LOOPBACK).
        assertTrue(Regex("^http://127\\.0\\.0\\.1:\\d{1,5}/auth/callback$").matches(redirect), redirect)
        assertEquals("$redirect?code=the-code&state=$STATE&client_id=app_123", url)
        assertEquals(200, answer?.status)
        assertTrue(answer!!.body.contains(strings[Str.CHATGPT_PAGE_OK]), answer!!.body)
    }

    @Test
    fun `another state or another path is a 404 and the wait goes on`() = runBlocking {
        var redirect = ""
        val answers = mutableListOf<Answer>()

        val url = receiver.awaitCallback(TIMEOUT, BROWSER_TIMEOUT, begin = { uri -> redirect = uri; signIn() }) {
            val origin = redirect.removeSuffix("/auth/callback")
            answers += get("$redirect?code=stolen&state=somebody-else")
            answers += get("$redirect?code=no-state")
            answers += get("$origin/?code=the-code&state=$STATE")
            answers += get("$origin/favicon.ico")
            answers += get("$redirect?code=the-code&state=$STATE")
        }

        assertEquals(listOf(404, 404, 404, 404, 200), answers.map { it.status })
        assertEquals("$redirect?code=the-code&state=$STATE", url)
    }

    @Test
    fun `a declined sign-in still hands the callback back, on the declined page`() = runBlocking {
        // The core is what tells access_denied (a cancel, silent) from any other error.
        var redirect = ""
        var answer: Answer? = null

        val url = receiver.awaitCallback(TIMEOUT, BROWSER_TIMEOUT, begin = { uri -> redirect = uri; signIn() }) {
            answer = get("$redirect?error=access_denied&state=$STATE")
        }

        assertEquals("$redirect?error=access_denied&state=$STATE", url)
        assertEquals(400, answer?.status)
        assertTrue(answer!!.body.contains(strings[Str.AUTH_PAGE_DECLINED]), answer!!.body)
    }

    @Test
    fun `only the first callback is answered`() = runBlocking {
        var redirect = ""
        val answers = mutableListOf<Answer>()

        val url = receiver.awaitCallback(TIMEOUT, BROWSER_TIMEOUT, begin = { uri -> redirect = uri; signIn() }) {
            answers += get("$redirect?code=first&state=$STATE")
            answers += get("$redirect?code=second&state=$STATE")
        }

        assertEquals("$redirect?code=first&state=$STATE", url)
        assertEquals(listOf(200, 200), answers.map { it.status })
        assertTrue(answers[1].body.contains(strings[Str.AUTH_PAGE_DONE]), answers[1].body)
    }

    @Test
    fun `a sign-in nobody finishes gives up and closes the port`() = runBlocking {
        var redirect = ""

        assertFailsWith<TimeoutCancellationException> {
            receiver.awaitCallback(300.milliseconds, BROWSER_TIMEOUT, begin = { uri -> redirect = uri; signIn() }) {
                assertEquals(404, get("$redirect?code=stolen&state=somebody-else").status)
            }
        }

        // RFC 8252 §8.3: the port is open only while the sign-in is.
        assertFailsWith<ConnectException> { get("$redirect?code=late&state=$STATE") }
        Unit
    }

    private fun signIn() = ChatGptSignIn("https://auth.example/authorize", STATE)

    private class Answer(val status: Int, val body: String)

    /** What a browser does with the redirect: a GET, and whatever page comes back. */
    private suspend fun get(url: String): Answer = withContext(Dispatchers.IO) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            val status = connection.responseCode
            val stream = if (status < 400) connection.inputStream else connection.errorStream
            Answer(status, stream?.use { it.readBytes().decodeToString() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val STATE = "the-state"
        val TIMEOUT = 10.seconds
        val BROWSER_TIMEOUT = 10.seconds
    }
}
