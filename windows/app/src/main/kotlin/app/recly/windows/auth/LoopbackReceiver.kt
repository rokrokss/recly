package app.recly.windows.auth

import app.recly.windows.i18n.Str
import app.recly.windows.i18n.Strings
import app.recly.windows.i18n.StringTable
import app.recly.windows.i18n.UiMessage
import app.recly.windows.i18n.message
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import recly.core.platform.Logger

/**
 * The consent screen came back with something other than a code (`access_denied`, a bad `state`).
 *
 * [reason] is the line the tray ends up showing, so it is a key where this app is the one that
 * decided, and Google's own words where they are what came back (docs/07 rule 4).
 */
class AuthDeclinedException(val reason: UiMessage) : Exception()

/**
 * docs/06 Windows: a Ktor CIO server on `127.0.0.1` at an ephemeral port, up only for as long as
 * one sign-in takes.
 *
 * Loopback, never `localhost` (RFC 8252 §8.3: "the use of localhost is NOT RECOMMENDED… avoids
 * inadvertently listening on network interfaces other than the loopback interface"). The port is
 * the OS's to pick (`port = 0`) — §7.3 requires the authorization server to allow any port for a
 * loopback redirect, and Google's Desktop client type registers none (docs/06).
 *
 * [strings] is read when the page is served, so the tab the user is left looking at is in the
 * language the app is in right now (docs/07 rule 3).
 */
class LoopbackReceiver(
    private val logger: Logger,
    private val strings: () -> Strings = { StringTable.of(StringTable.BASE) },
) {

    /**
     * The browser open runs here rather than under the sign-in: `Desktop.browse` blocks until the OS
     * has found a browser, and a blocking call is not something a timeout can take back. Detached,
     * so the sign-in can give up on it and the thread ends by itself.
     */
    private val opens = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Starts the server, hands [onReady] the redirect URI it is listening on — that is where the
     * browser is opened — and answers with the `code` the consent screen sends back.
     *
     * [browserTimeout] bounds [onReady] alone: handing a URL to the shell is not a round trip, and
     * a machine with no browser at all can block in it for good. The wait is what gives up; the
     * thread the open is left on ends whenever the OS finally answers it.
     *
     * The server always comes down (RFC 8252 §8.3 — "open the network port only when starting the
     * authorization request and close it once the response is returned"): on the code, on a
     * decline, on either timeout.
     */
    suspend fun awaitCode(
        state: String,
        timeout: Duration,
        browserTimeout: Duration,
        onReady: suspend (redirectUri: String) -> Unit,
    ): String {
        val code = CompletableDeferred<Result<String>>()
        val server = embeddedServer(CIO, port = 0, host = HOST) {
            routing {
                get("/") {
                    // Exactly one redirect is answered. The port is open to anything on this
                    // machine, and a second request — a reload, or somebody guessing the port — is
                    // not the sign-in this process started, whatever it carries.
                    if (code.isCompleted) {
                        call.respondText(page(DONE), ContentType.Text.Html, HttpStatusCode.OK)
                        return@get
                    }
                    val parameters = call.request.queryParameters
                    val received = parameters["code"]
                    val error = parameters["error"]
                    val answered = when {
                        // RFC 8252 §8.9: reject a response whose state is not the pending one.
                        parameters["state"] != state ->
                            Result.failure(AuthDeclinedException(Str.AUTH_STATE_MISMATCH.message()))

                        // Google's diagnostic, and it is shown as it arrived (docs/07 rule 4).
                        error != null -> Result.failure(AuthDeclinedException(UiMessage.Text(error)))
                        received != null -> Result.success(received)
                        else -> Result.failure(AuthDeclinedException(Str.AUTH_NO_CODE.message()))
                    }
                    call.respondText(
                        page(if (answered.isSuccess) OK else DECLINED),
                        ContentType.Text.Html,
                        if (answered.isSuccess) HttpStatusCode.OK else HttpStatusCode.BadRequest,
                    )
                    code.complete(answered)
                }
            }
        }
        server.start(wait = false)
        return try {
            val port = server.engine.resolvedConnectors().first().port
            logger.log(Logger.Level.INFO, "auth.loopback.listening", mapOf("port" to port))
            val opened = opens.async { onReady("http://$HOST:$port") }
            withTimeout(browserTimeout) { opened.await() }
            withTimeout(timeout) { code.await() }.getOrThrow()
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = STOP_TIMEOUT_MS)
        }
    }

    /**
     * The browser tab the user is left looking at; the app itself is already going on.
     *
     * One inline document and nothing else — no stylesheet, no image, no script. The URL of this
     * page carries the authorization code, and anything fetched from it would carry that URL out in
     * a `Referer` header. (RFC 8252 says nothing about this page; that is the reason it is plain.)
     */
    private fun page(message: Str): String =
        "<!doctype html><meta charset=\"utf-8\"><title>Recly</title>" +
            "<body style=\"font-family:sans-serif;padding:3rem\"><p>${strings()[message]}</p></body>"

    private companion object {
        const val HOST = "127.0.0.1"
        const val STOP_TIMEOUT_MS = 1_000L

        val OK = Str.AUTH_PAGE_OK
        val DECLINED = Str.AUTH_PAGE_DECLINED
        val DONE = Str.AUTH_PAGE_DONE
    }
}
