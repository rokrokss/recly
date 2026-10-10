package app.recly.android.auth

import java.net.ConnectException
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * docs/15 §10: the ChatGPT sign-in's loopback, on a real socket — the one callback whose state is the sign-in's
 * is answered and handed back whole; anything else is a 404 and the wait goes on; after the callback, the
 * path only says the sign-in was handled.
 */
class LoopbackReceiverTest {

    @Test
    fun `the callback with the sign-in's state gets its page and is handed back whole`() = withReceiver { receiver, callback ->
        assertEquals("http://127.0.0.1:${receiver.port}/auth/callback", receiver.redirectUri)

        val (status, body) = get(receiver.port, "/auth/callback?code=abc&state=s1")

        assertEquals(200, status)
        assertEquals("page SIGNED_IN", body)
        assertEquals("http://127.0.0.1:${receiver.port}/auth/callback?code=abc&state=s1", withTimeout(5_000) { callback.await() })
    }

    @Test
    fun `another state, another path or a favicon is a 404, and the wait goes on`() = withReceiver { receiver, callback ->
        assertEquals(404, get(receiver.port, "/auth/callback?code=abc&state=someone-else").first)
        assertEquals(404, get(receiver.port, "/auth/callback?code=abc").first)
        assertEquals(404, get(receiver.port, "/favicon.ico").first)
        assertEquals(404, get(receiver.port, "/?code=abc&state=s1").first)
        assertFalse(callback.isCompleted)

        assertEquals(200, get(receiver.port, "/auth/callback?code=abc&state=s1").first)
        assertTrue(withTimeout(5_000) { callback.await() }.endsWith("state=s1"))
    }

    @Test
    fun `a declined consent gets its own page and still finishes the wait`() = withReceiver { receiver, callback ->
        val (status, body) = get(receiver.port, "/auth/callback?error=access_denied&state=s1")

        assertEquals(200, status)
        assertEquals("page DECLINED", body)
        assertTrue(withTimeout(5_000) { callback.await() }.contains("error=access_denied"))
    }

    @Test
    fun `there is one callback - a second request only hears that it was handled`() = withReceiver { receiver, callback ->
        get(receiver.port, "/auth/callback?code=first&state=s1")
        val first = withTimeout(5_000) { callback.await() }

        assertEquals(200 to "page DONE", get(receiver.port, "/auth/callback?code=second&state=s1"))
        assertEquals(200 to "page DONE", get(receiver.port, "/auth/callback?code=third&state=other"))
        assertEquals(404, get(receiver.port, "/favicon.ico").first)
        assertTrue(first.contains("code=first"))
    }

    @Test
    fun `a connection that never sends anything does not hold up the callback`() = withReceiver { receiver, callback ->
        Socket(LoopbackReceiver.HOST, receiver.port).use {
            delay(100)
            assertEquals(200, get(receiver.port, "/auth/callback?code=abc&state=s1").first)
            withTimeout(5_000) { callback.await() }
        }
    }

    @Test
    fun `closed, the port takes nothing more`() {
        val receiver = LoopbackReceiver.open { "page ${it.name}" }
        val port = receiver.port
        receiver.close()

        assertFailsWith<ConnectException> { Socket(LoopbackReceiver.HOST, port).close() }
    }

    @Test
    fun `the page is one inline document whose text cannot become markup`() {
        val html = loopbackPage("a < b & \"c\"", "Return" to "intent://chatgpt#Intent;scheme=app.recly;end")

        assertTrue("a &lt; b &amp; &quot;c&quot;" in html)
        assertTrue("<a href=\"intent://chatgpt#Intent;scheme=app.recly;end\">Return</a>" in html)
        assertFalse("<script" in html || "<link" in html || "<img" in html || "src=" in html)
        assertFalse("<a " in loopbackPage("done", null))
    }

    /** A receiver waiting for the callback of state `s1`, closed afterwards whatever happened. */
    private fun withReceiver(block: suspend (LoopbackReceiver, Deferred<String>) -> Unit) = runBlocking {
        LoopbackReceiver.open { "page ${it.name}" }.use { receiver ->
            val callback = async(Dispatchers.IO) { receiver.awaitCallback("s1") }
            try {
                block(receiver, callback)
            } finally {
                callback.cancel()
            }
        }
    }

    /** One `GET`, as a browser sends it: the status and the body, read to the end the server closes. */
    private suspend fun get(port: Int, target: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        Socket(LoopbackReceiver.HOST, port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write("GET $target HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAccept: text/html\r\n\r\n".encodeToByteArray())
            val response = socket.getInputStream().readBytes().decodeToString()
            response.substringAfter(' ').substringBefore(' ').toInt() to response.substringAfter("\r\n\r\n")
        }
    }
}
