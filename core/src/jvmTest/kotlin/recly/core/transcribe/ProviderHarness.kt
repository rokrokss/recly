@file:OptIn(ExperimentalTime::class)

package recly.core.transcribe

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonObject
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import recly.core.drive.KtorTransport
import recly.core.model.Language
import recly.core.model.Speakers
import recly.core.model.Step
import recly.core.platform.Transport
import recly.core.testing.FakeClock
import recly.core.testing.FakeLogger
import recly.core.testing.MapSecureStore
import recly.core.testing.testDeps

/**
 * One provider endpoint, scripted: it answers the [reply]s a test queued in the order they were
 * queued and keeps every request whole, so the assertion can be on the body that went out.
 *
 * A queue rather than a router because that is what these tests are about — RTZR authenticates,
 * submits and then polls, and what matters is which call carried which token.
 */
internal class ScriptedServer {
    val requests = mutableListOf<Recorded>()

    private class Reply(val status: Int, val body: String, val headers: Map<String, String>)

    private val queued = ArrayDeque<Reply>()

    fun reply(
        body: String = "{}",
        status: Int = 200,
        headers: Map<String, String> = mapOf("Content-Type" to "application/json"),
    ): ScriptedServer = apply { queued += Reply(status, body, headers) }

    fun request(index: Int): Recorded = requests[index]

    fun engine(): MockEngine = MockEngine { request ->
        requests += Recorded.of(request)
        val reply = queued.removeFirstOrNull()
            ?: Reply(500, """{"error":"no scripted reply"}""", mapOf("Content-Type" to "application/json"))
        respond(
            reply.body,
            HttpStatusCode.fromValue(reply.status),
            Headers.build { reply.headers.forEach { (name, value) -> append(name, value) } },
        )
    }

    fun transport(fs: FileSystem): Transport = KtorTransport(HttpClient(engine()) { install(HttpTimeout) }, fs)
}

/**
 * A [ScriptedServer] with a file to upload and the deps a provider is called with — everything the
 * adapter tests need and nothing the runner needs.
 */
internal class ProviderHarness {
    val server = ScriptedServer()
    val fs = FakeFileSystem()
    val clock = FakeClock()
    val logger = FakeLogger()

    val audio = "/data/recordings/joined.m4a".toPath()

    val deps = testDeps(
        clock = clock,
        fileSystem = fs,
        logger = logger,
        secureStore = MapSecureStore(),
        transport = server.transport(fs),
    )

    init {
        fs.createDirectories(audio.parent!!)
        fs.write(audio) { writeUtf8(AUDIO) }
    }

    fun sttContext(
        provider: String,
        apiKey: String,
        language: Language = Language.KO,
        diarize: Boolean = true,
        speakers: Speakers = Speakers(),
        speakersExpected: Int? = null,
        model: String? = null,
        invokeUrl: String? = null,
        audioDurationSec: Double? = null,
        providerState: JsonObject? = null,
    ): SttContext = SttContext(
        step = Step.Transcribe(
            id = "stt",
            provider = provider,
            secretRef = "key",
            invokeUrl = invokeUrl,
            language = language,
            diarize = diarize,
            speakers = speakers,
            model = model,
        ),
        apiKey = apiKey,
        speakersExpected = speakersExpected,
        audioDurationSec = audioDurationSec,
        deps = deps,
        providerState = providerState,
    )

    companion object {
        const val AUDIO = "joined-aac-frames"
    }
}

/** The `name="…"` section of a `multipart/form-data` body, without its part headers. */
internal fun Recorded.multipartPart(name: String): String {
    val marker = "name=\"$name\""
    val at = text.indexOf(marker)
    require(at >= 0) { "no part '$name' in\n$text" }
    val bodyAt = text.indexOf("\r\n\r\n", at) + 4
    val end = text.indexOf("\r\n--", bodyAt)
    return text.substring(bodyAt, if (end < 0) text.length else end)
}

/** The part headers `name` was written with — `filename`, `Content-Type` and the rest. */
internal fun Recorded.multipartHeaders(name: String): String {
    val marker = "name=\"$name\""
    val at = text.indexOf(marker)
    require(at >= 0) { "no part '$name' in\n$text" }
    val from = text.lastIndexOf("\r\n", at).let { if (it < 0) 0 else it + 2 }
    return text.substring(from, text.indexOf("\r\n\r\n", at))
}
