package app.recly.android.auth

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * docs/15 §10 "Sign in with ChatGPT": the redirect OpenAI sends the browser to, on this phone. OpenAI accepts
 * only `http://127.0.0.1:<port>/auth/callback`, so the app listens there itself, for as long as one sign-in
 * takes — the Windows receiver's rules (`windows/…/auth/LoopbackReceiver.kt`) on a plain socket:
 *
 * - `127.0.0.1`, never `localhost` or every interface, on a port the OS picks;
 * - the one request on [PATH] whose `state` is the sign-in's is the callback, answered at once with a page,
 *   and handed back whole; anything else — another path, another state, a favicon — is a 404, and the
 *   listening goes on;
 * - exactly one callback: after it, [PATH] answers [Page.DONE] until [close].
 *
 * Each connection is read on its own, with a short timeout: a browser opens connections it may never send
 * anything on, and one of those must not hold up the redirect behind it.
 */
class LoopbackReceiver private constructor(
    private val server: ServerSocket,
    private val page: (Page) -> String,
) : Closeable {

    enum class Page { SIGNED_IN, DECLINED, DONE }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val callback = CompletableDeferred<String>()
    private val claimed = AtomicBoolean(false)

    val port: Int get() = server.localPort

    /** What `beginSignIn` is given. */
    val redirectUri: String get() = "http://$HOST:$port$PATH"

    /** The whole URL of the callback for [state] — the query is what the core reads the code from. */
    suspend fun awaitCallback(state: String): String {
        scope.launch {
            while (isActive) {
                // Closing the socket is what ends a blocked accept.
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                launch { client.use { answer(it, state) } }
            }
        }
        return callback.await()
    }

    override fun close() {
        server.close()
        scope.cancel()
    }

    private fun answer(client: Socket, state: String) {
        val target = try {
            client.soTimeout = READ_TIMEOUT_MS
            requestTarget(client.getInputStream())
        } catch (e: IOException) {
            null
        } ?: return
        val path = target.substringBefore('?')
        val query = query(target.substringAfter('?', ""))
        // Two tabs racing with the same URL: the first one claims it.
        val first = path == PATH && query["state"] == state && claimed.compareAndSet(false, true)
        val html = when {
            first -> page(if (query.containsKey("error")) Page.DECLINED else Page.SIGNED_IN)
            path == PATH && claimed.get() -> page(Page.DONE)
            else -> null
        }
        respond(client, html)
        // Handed on only once the browser has its page: the exchange that follows takes seconds.
        if (first) callback.complete("http://$HOST:$port$target")
    }

    private fun respond(client: Socket, html: String?) {
        val body = html.orEmpty().encodeToByteArray()
        val head = buildString {
            append(if (html != null) "HTTP/1.1 200 OK\r\n" else "HTTP/1.1 404 Not Found\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${body.size}\r\n")
            // The page's own URL carries the code: nothing keeps it, and nothing it links to is told it.
            append("Cache-Control: no-store\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("Connection: close\r\n\r\n")
        }
        try {
            client.getOutputStream().apply {
                write(head.encodeToByteArray())
                write(body)
                flush()
            }
        } catch (e: IOException) {
            // The browser went away; there is no one to tell.
        }
    }

    companion object {
        const val HOST = "127.0.0.1"
        const val PATH = "/auth/callback"
        private const val READ_TIMEOUT_MS = 10_000
        private const val MAX_HEAD = 16 * 1024

        /** Binds now, so the port is known before the sign-in is started with it. */
        fun open(page: (Page) -> String): LoopbackReceiver =
            LoopbackReceiver(ServerSocket(0, 8, InetAddress.getByName(HOST)), page)

        /**
         * The request-target of a `GET`, after reading the whole head — a socket closed with bytes left unread
         * is reset, and the browser would show that instead of the page. Null for anything else.
         */
        internal fun requestTarget(input: InputStream): String? {
            val head = ByteArrayOutputStream()
            var last = 0
            while (head.size() < MAX_HEAD) {
                val byte = input.read()
                if (byte < 0) return null
                head.write(byte)
                last = (last shl 8) or byte
                if (last == END_OF_HEAD) break
            }
            if (last != END_OF_HEAD) return null
            val parts = head.toString(Charsets.ISO_8859_1.name()).substringBefore("\r\n").split(' ')
            return parts.takeIf { it.size == 3 && it[0] == "GET" && it[1].startsWith('/') }?.get(1)
        }

        private fun query(query: String): Map<String, String> =
            query.split('&').filter { it.isNotEmpty() }.associate { pair ->
                val name = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                runCatching { URLDecoder.decode(name, "UTF-8") to URLDecoder.decode(value, "UTF-8") }.getOrDefault(name to value)
            }

        /** `\r\n\r\n` as the last four bytes read. */
        private const val END_OF_HEAD = 0x0D0A0D0A
    }
}

/**
 * The browser tab the user is left looking at: one inline document and nothing else — no stylesheet, no image,
 * no script, because anything fetched from a page whose URL carries the code could carry that URL out
 * (the Windows receiver's reason). [link] is "Return to Recly": its label and where it goes.
 */
internal fun loopbackPage(text: String, link: Pair<String, String>?): String = buildString {
    append("<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
    append("<title>Recly</title><body style=\"font-family:sans-serif;padding:2rem;line-height:1.5\"><p>")
    append(escape(text))
    append("</p>")
    link?.let { (label, href) -> append("<p><a href=\"").append(escape(href)).append("\">").append(escape(label)).append("</a></p>") }
    append("</body>")
}

private fun escape(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
