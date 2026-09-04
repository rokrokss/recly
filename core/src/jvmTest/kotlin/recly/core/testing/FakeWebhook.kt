package recly.core.testing

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import okio.FileSystem
import recly.core.drive.KtorTransport
import recly.core.platform.HttpPlan
import recly.core.platform.HttpResult
import recly.core.platform.Transport

/**
 * A webhook endpoint that answers whatever the test tells it to and keeps every request verbatim —
 * header casing included, which docs/04 pins.
 */
class FakeWebhook(val url: String = "https://hooks.example.com/rec") {
    class Received(
        val method: String,
        val url: String,
        /** In arrival order, with the case the client actually put on the wire. */
        val headers: List<Pair<String, String>>,
        val contentType: String?,
        val body: ByteArray,
    ) {
        val headerNames: List<String> get() = headers.map { it.first }
        val text: String get() = body.decodeToString()

        fun header(name: String): String? =
            headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
    }

    val received = mutableListOf<Received>()

    var status: Int = 200
    var responseHeaders: Map<String, String> = emptyMap()
    var responseBody: String = ""

    fun engine(): MockEngine = MockEngine { request ->
        received += Received(
            method = request.method.value,
            url = request.url.toString(),
            headers = request.headers.entries().flatMap { entry -> entry.value.map { entry.key to it } },
            contentType = request.body.contentType?.toString(),
            body = request.body.toByteArray(),
        )
        respond(
            responseBody,
            HttpStatusCode.fromValue(status),
            Headers.build { responseHeaders.forEach { (k, v) -> append(k, v) } },
        )
    }

    /** A real [KtorTransport], so redirect and timeout handling are the shipping code paths. */
    fun transport(fs: FileSystem): Transport =
        KtorTransport(HttpClient(engine()) { install(HttpTimeout) }, fs)
}

/** Sends the plans whose URL starts with [prefix] to [matching] and everything else to [other]. */
class RoutingTransport(
    private val prefix: String,
    private val matching: Transport,
    private val other: Transport,
) : Transport {
    override suspend fun execute(plan: HttpPlan): HttpResult =
        if (plan.url.startsWith(prefix)) matching.execute(plan) else other.execute(plan)
}
