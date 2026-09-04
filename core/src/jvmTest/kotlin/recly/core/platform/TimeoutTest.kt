package recly.core.platform

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.HttpRequestData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import okio.fakefilesystem.FakeFileSystem
import recly.core.drive.KtorTransport

/**
 * `HttpPlan.timeoutSec` as [KtorTransport] applies it. A plan's budget has to reach **both** of
 * Ktor's per-request knobs: `requestTimeoutMillis` bounds the whole call, and `socketTimeoutMillis`
 * bounds how long it may sit idle.
 *
 * The second one is the one that bites. docs/08's `clova` provider is synchronous — the response
 * body is the transcript — so the server sends nothing at all for up to fifteen minutes, and the
 * client's own 60 s read timeout would abort the request long before the answer arrived.
 */
class TimeoutTest {
    private var sent: HttpRequestData? = null

    private val transport = KtorTransport(
        HttpClient(MockEngine { request -> sent = request; respondOk() }) { install(HttpTimeout) },
        FakeFileSystem(),
    )

    @Test
    fun `a plan's budget bounds the whole call and the idle time alike`() = runBlocking {
        transport.execute(HttpPlan(method = "POST", url = URL, timeoutSec = 900))

        val timeout = timeout()
        assertEquals(900_000L, timeout.requestTimeoutMillis)
        assertEquals(900_000L, timeout.socketTimeoutMillis)
    }

    @Test
    fun `a plan with no budget leaves the client's own defaults alone`() = runBlocking {
        transport.execute(HttpPlan(method = "GET", url = URL))

        // No per-request capability at all: the client's `install(HttpTimeout)` values stand.
        assertNull(sent!!.getCapabilityOrNull(HttpTimeoutCapability))
    }

    private fun timeout(): HttpTimeoutConfig =
        sent!!.getCapabilityOrNull(HttpTimeoutCapability) ?: error("no per-request timeout was set")

    private companion object {
        const val URL = "https://clovaspeech-gw.ncloud.com/external/v1/1/a/recognizer/upload"
    }
}
