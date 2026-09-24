package recly.core.testing

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import okio.FileSystem
import recly.core.drive.KtorTransport
import recly.core.platform.HttpPlan
import recly.core.platform.HttpResult
import recly.core.platform.Transport

/** An HTTP endpoint that answers whatever the test tells it to and counts what it was sent. */
class FakeEndpoint(val url: String) {
    /** The URL of every request, in arrival order. */
    val received = mutableListOf<String>()

    var status: Int = 200
    var responseBody: String = ""

    private fun engine(): MockEngine = MockEngine { request ->
        received += request.url.toString()
        respond(responseBody, HttpStatusCode.fromValue(status), Headers.build { append("Content-Type", "application/json") })
    }

    /** A real [KtorTransport], so the shipping request and response handling is what runs. */
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
