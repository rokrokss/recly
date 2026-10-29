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

    private val standing = mutableListOf<Pair<(Recorded) -> Boolean, Reply>>()

    /** Runs as each request arrives, before it is answered: what a test looks at while the caller waits. */
    var beforeReply: (suspend (Recorded) -> Unit)? = null

    fun reply(
        body: String = "{}",
        status: Int = 200,
        headers: Map<String, String> = mapOf("Content-Type" to "application/json"),
    ): ScriptedServer = apply { queued += Reply(status, body, headers) }

    /** Every request [matching] picks out is answered so, outside the queue: a call that comes along with the ones a test scripts. */
    fun replyEach(
        matching: (Recorded) -> Boolean,
        body: String,
        headers: Map<String, String> = mapOf("Content-Type" to "application/json"),
    ): ScriptedServer = apply { standing += matching to Reply(200, body, headers) }

    fun request(index: Int): Recorded = requests[index]

    fun engine(): MockEngine = MockEngine { request ->
        // This request's own record: while [beforeReply] holds it, others arrive and become the last.
        val recorded = Recorded.of(request)
        requests += recorded
        beforeReply?.invoke(recorded)
        val reply = standing.firstOrNull { (matching, _) -> matching(recorded) }?.second
            ?: queued.removeFirstOrNull()
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
        vocabulary: List<String> = emptyList(),
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
            vocabulary = vocabulary,
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

/** Every `name="…"` section of a `multipart/form-data` body, in order — a list sent as a repeated field. */
internal fun Recorded.multipartParts(name: String): List<String> {
    val marker = "name=\"$name\""
    val values = mutableListOf<String>()
    var at = text.indexOf(marker)
    while (at >= 0) {
        val bodyAt = text.indexOf("\r\n\r\n", at) + 4
        val end = text.indexOf("\r\n--", bodyAt)
        values += text.substring(bodyAt, if (end < 0) text.length else end)
        at = text.indexOf(marker, bodyAt)
    }
    return values
}

/** The part headers `name` was written with — `filename`, `Content-Type` and the rest. */
internal fun Recorded.multipartHeaders(name: String): String {
    val marker = "name=\"$name\""
    val at = text.indexOf(marker)
    require(at >= 0) { "no part '$name' in\n$text" }
    val from = text.lastIndexOf("\r\n", at).let { if (it < 0) 0 else it + 2 }
    return text.substring(from, text.indexOf("\r\n\r\n", at))
}
