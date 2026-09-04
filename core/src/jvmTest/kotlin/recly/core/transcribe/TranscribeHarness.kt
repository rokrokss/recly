@file:OptIn(ExperimentalTime::class)

package recly.core.transcribe

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import recly.core.drive.DriveApi
import recly.core.drive.KtorTransport
import recly.core.drive.ScriptedTokenProvider
import recly.core.drive.mockTransport
import recly.core.job.Job
import recly.core.job.JobStatus
import recly.core.job.StepContext
import recly.core.job.StepOutcome
import recly.core.job.StepOutput
import recly.core.model.AudioSettings
import recly.core.model.Codec
import recly.core.model.Container
import recly.core.model.Context
import recly.core.model.Part
import recly.core.model.Platform
import recly.core.model.RecordingMeta
import recly.core.model.RecordingStatus
import recly.core.model.Source
import recly.core.model.Speakers
import recly.core.model.Step
import recly.core.model.Track
import recly.core.platform.Transport
import recly.core.recording.MetaWriter
import recly.core.recording.RecordingRecord
import recly.core.testing.DEVICE_ID
import recly.core.testing.DEVICE_NAME
import recly.core.testing.FakeAudioTools
import recly.core.testing.FakeClock
import recly.core.testing.FakeDrive
import recly.core.testing.FakeLogger
import recly.core.testing.MapSecureStore
import recly.core.testing.RoutingTransport
import recly.core.testing.START
import recly.core.testing.STEP_RUN_ID
import recly.core.testing.driveStep
import recly.core.testing.testDeps
import recly.core.testing.testWorkflow

internal const val STT_KEY = "stt_key"

/** A request a fake provider answered, kept whole so a test can assert on the body it was sent. */
internal class Recorded(
    val method: String,
    val url: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    val text: String get() = body.decodeToString()

    fun json(): JsonObject = kotlinx.serialization.json.Json.parseToJsonElement(text) as JsonObject

    companion object {
        suspend fun of(request: HttpRequestData): Recorded = Recorded(
            method = request.method.value,
            url = request.url.toString(),
            path = request.url.encodedPath,
            // Ktor keeps the body's content type on the body, not among the request headers, and
            // the boundary a multipart body picked is only visible there.
            headers = request.headers.entries().associate { it.key to it.value.first() } +
                request.body.contentType?.let { mapOf("Content-Type" to it.toString()) }.orEmpty(),
            body = request.body.toByteArray(),
        )
    }
}

private class Fault(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
    var remaining: Int,
    val match: (Recorded) -> Boolean,
)

/**
 * AssemblyAI's three endpoints, scripted: upload takes the bytes, submit hands out an id, and the
 * poll answers `processing` [pendingPolls] times before [completed].
 */
internal class FakeStt {
    val requests = mutableListOf<Recorded>()
    var uploadUrl = "https://cdn.assemblyai.com/upload/abcd"
    var transcriptId = "t-0001"
    var pendingPolls = 1
    var completed: String = TWO_SPEAKERS

    /** When set, the transport itself blows up — a reset connection, not an HTTP answer. */
    var networkFailure = false

    private val faults = mutableListOf<Fault>()
    private var polls = 0

    fun failNext(
        status: Int,
        body: String = "",
        headers: Map<String, String> = emptyMap(),
        times: Int = 1,
        match: (Recorded) -> Boolean = { true },
    ) {
        faults += Fault(status, headers, body, times, match)
    }

    fun submitted(): Recorded = requests.first { it.method == "POST" && it.path == "/v2/transcript" }

    /** How many times the audio was handed to the provider — a retry must not poll a dead ref. */
    fun submits(): Int = requests.count { it.method == "POST" && it.path == "/v2/transcript" }

    fun uploaded(): Recorded = requests.first { it.path == "/v2/upload" }

    fun polls(): List<Recorded> = requests.filter { it.method == "GET" }

    fun engine(): MockEngine = MockEngine { request ->
        val recorded = record(request)
        if (networkFailure) throw java.io.IOException("connection reset")
        val fault = faults.firstOrNull { it.remaining > 0 && it.match(recorded) }
        when {
            fault != null -> {
                fault.remaining--
                respond(fault.body, HttpStatusCode.fromValue(fault.status), headers(fault.headers))
            }

            recorded.path == "/v2/upload" -> json("""{"upload_url":"$uploadUrl"}""")
            recorded.method == "POST" -> json("""{"id":"$transcriptId","status":"queued"}""")
            else -> {
                polls++
                if (polls <= pendingPolls) json("""{"id":"$transcriptId","status":"processing"}""") else json(completed)
            }
        }
    }

    fun transport(fs: FileSystem): Transport = KtorTransport(HttpClient(engine()) { install(HttpTimeout) }, fs)

    private suspend fun record(request: HttpRequestData): Recorded = Recorded.of(request).also { requests += it }

    companion object {
        /** Two speakers, one word list, times in milliseconds — the shape the API documents. */
        const val TWO_SPEAKERS = """
            {"id":"t-0001","status":"completed","language_code":"ko","audio_duration":1810.0,
             "text":"안녕하세요 반갑습니다",
             "utterances":[
               {"start":0,"end":3200,"speaker":"A","text":"안녕하세요",
                "words":[{"start":0,"end":1200,"text":"안녕하세요"}]},
               {"start":3600,"end":9100,"speaker":"B","text":"반갑습니다"},
               {"start":905000,"end":906800,"speaker":"A","text":"두 번째 파트"}
             ]}
        """
    }
}

private fun MockRequestHandleScope.json(body: String): HttpResponseData =
    respond(body, HttpStatusCode.OK, headers(mapOf("Content-Type" to "application/json")))

private fun headers(map: Map<String, String>): Headers = Headers.build { map.forEach { (k, v) -> append(k, v) } }

/**
 * A finalized recording on a fake disk, a [FakeDrive] holding the folder a `drive.upload` step
 * already made, and a fake STT endpoint behind the real [KtorTransport]. Everything the
 * `transcribe` tests need except the scenario.
 */
internal class TranscribeHarness(
    val partCount: Int = 2,
    val tracks: List<Track> = listOf(Track.MONO),
    val participants: Int? = null,
    val title: String? = "주간 회의",
    /** Pads every part file out to this many bytes, for the tests about a provider's size ceiling. */
    private val partBytes: Int = 0,
    /** A second STT endpoint in front of the AssemblyAI one, for a test about another provider. */
    private val extraSttHost: String? = null,
    /** Swapped out by the one test that asserts on what the runner puts in the [SttContext]. */
    private val providers: (String) -> SttProvider? = SttProviders::create,
) {
    val drive = FakeDrive()
    val stt = FakeStt()

    /** Answers [extraSttHost] when one was named; unused otherwise. */
    val other = ScriptedServer()
    val fs = FakeFileSystem()
    val clock = FakeClock()
    val logger = FakeLogger()
    val secrets = MapSecureStore(mapOf(STT_KEY to "aai-key"))
    val audio = FakeAudioTools(fs)

    val deps = testDeps(
        clock = clock,
        fileSystem = fs,
        logger = logger,
        secureStore = secrets,
        tokenProvider = ScriptedTokenProvider(),
        transport = RoutingTransport(
            extraSttHost ?: "https://nothing.invalid",
            other.transport(fs),
            RoutingTransport("https://api.assemblyai.com", stt.transport(fs), mockTransport(drive, fs)),
        ),
        audio = audio,
    )

    val api = DriveApi(deps)
    val transcribe = TranscribeRunner(api, deps, providers)

    val recordingId = "01J9ABCDEF0123456789ABCDEF"
    val meta: RecordingMeta
    val base: String
    val dir: Path
    val folderId: String

    /** What the executor would have persisted in `step_run.state_json`. */
    var state: JsonObject? = null

    init {
        val skeleton = meta(emptyList())
        base = MetaWriter.baseName(skeleton)
        dir = "/data/recordings/$base".toPath()
        fs.createDirectories(dir)
        val parts = buildList {
            for (number in 1..partCount) {
                tracks.forEach { track ->
                    val name = MetaWriter.partFileName(base, number, track)
                    val content = "audio-$number-${track.name}"
                    fs.write(dir / name) {
                        writeUtf8(content)
                        if (partBytes > content.length) write(ByteArray(partBytes - content.length))
                    }
                    add(
                        Part(
                            part = number,
                            track = track,
                            file = name,
                            bytes = 16,
                            sha256 = "0".repeat(64),
                            startOffsetSec = (number - 1) * 905.0,
                            durationSec = 900.0,
                        ),
                    )
                }
            }
        }
        meta = meta(parts).copy(
            endedAt = "2026-08-26T01:45:00.000Z",
            durationSec = 2700.0,
            status = RecordingStatus.FINALIZED,
        )
        MetaWriter.write(fs, dir, meta)
        folderId = drive.put(base, "root", ByteArray(0), FakeDrive.FOLDER_MIME)
    }

    fun transcribeStep(
        id: String = "stt",
        provider: String = AssemblyAiProvider.NAME,
        diarize: Boolean = true,
        speakers: Speakers = Speakers(),
        invokeUrl: String? = null,
    ): Step.Transcribe = Step.Transcribe(
        id = id,
        provider = provider,
        secretRef = STT_KEY,
        invokeUrl = invokeUrl,
        diarize = diarize,
        speakers = speakers,
    )

    /** The output a successful `drive.upload` step left behind. */
    fun uploadOutput(): StepOutput = StepOutput(
        buildJsonObject {
            put("folderId", folderId)
            put("path", "recly/2026/2026-08/$base")
            putJsonArray("files") {}
        },
    )

    suspend fun run(step: Step, prior: Map<String, StepOutput> = mapOf("up" to uploadOutput())): StepOutcome {
        val workflow = testWorkflow(steps = listOf(driveStep("up"), step))
        return transcribe.run(
            StepContext(
                job = Job(
                    id = "01J9JOB0000000000000000000",
                    recordingId = recordingId,
                    workflowId = workflow.id,
                    workflow = workflow,
                    status = JobStatus.RUNNING,
                    createdAt = START,
                    updatedAt = START,
                    nextRunAt = null,
                ),
                workflow = workflow,
                stepRunId = STEP_RUN_ID,
                step = step,
                recording = RecordingRecord(recordingId, meta, dir),
                prior = prior,
                state = state,
                saveState = { state = it },
                saveOutput = {},
                deps = deps,
            ),
        )
    }

    /** Submits, then polls until the provider answers — what two executor passes would do. */
    suspend fun runToDone(step: Step): StepOutput {
        var outcome = run(step)
        while (outcome is StepOutcome.Waiting) outcome = run(step)
        return (outcome as StepOutcome.Done).output
    }

    fun submits(): Int = stt.submits()

    fun driveContent(name: String): String? = drive.byName(name)?.content?.decodeToString()

    fun localContent(name: String): String = fs.read(dir / name) { readUtf8() }

    private fun meta(parts: List<Part>) = RecordingMeta(
        schema = 1,
        recordingId = recordingId,
        source = Source.DESKTOP,
        platform = Platform.MACOS,
        deviceId = DEVICE_ID,
        deviceName = DEVICE_NAME,
        workflowId = "01J9ABCDEF0123456789ABCDEF",
        title = title,
        startedAt = "2026-08-26T01:00:00.000Z",
        timezone = "Asia/Seoul",
        audio = AudioSettings(Codec.AAC_LC, Container.M4A, 16_000, 1, 32, 900),
        tracks = tracks,
        parts = parts,
        context = participants?.let { Context(participants = it) },
        status = RecordingStatus.RECORDING,
    )
}
