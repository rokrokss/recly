@file:OptIn(ExperimentalTime::class)

package recly.core.testing

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import recly.core.db.RecDatabase
import recly.core.model.AudioSettings
import recly.core.model.Codec
import recly.core.model.Container
import recly.core.model.OnError
import recly.core.model.Part
import recly.core.model.Platform
import recly.core.model.RecordingMeta
import recly.core.model.RecordingStatus
import recly.core.model.Retry
import recly.core.model.Source
import recly.core.model.Step
import recly.core.model.Track
import recly.core.model.Workflow
import recly.core.model.WorkflowsDocument
import recly.core.platform.AudioTools
import recly.core.platform.Clock
import recly.core.platform.CoreDeps
import recly.core.platform.DeviceInfo
import recly.core.platform.HttpPlan
import recly.core.platform.HttpResult
import recly.core.platform.Logger
import recly.core.platform.SecureStore
import recly.core.platform.TokenProvider
import recly.core.platform.Transport
import recly.core.recording.MetaWriter
import recly.core.workflow.WorkflowParser

val START: Instant = Instant.parse("2026-08-26T01:00:00.000Z")

/** The `step_run` ULID a harness runs a step under; the webhook sends it as `webhook-id`. */
const val STEP_RUN_ID: String = "01J9STEPR0N0123456789ABCDE"

/** The one device every test runs as, unless it is a test about two of them. */
const val DEVICE_ID: String = "7c1e4b2a-0d3f-4a7e-9b1c-2f5e8d6a4c10"

const val DEVICE_NAME: String = "MacBook Pro"

/** What the webhook `user-agent` reports in tests. */
const val TEST_APP_VERSION: String = "1.0.0"

/**
 * Also a [kotlin.time.Clock], which is what `FakeFileSystem` dates its files by: the retention
 * sweep reads part mtimes, so the disk and the queue have to be on the same clock.
 */
class FakeClock(var instant: Instant = START) : Clock, kotlin.time.Clock {
    override fun now(): Instant = instant

    fun advance(by: Duration) {
        instant += by
    }
}

class FakeLogger : Logger {
    data class Entry(val level: Logger.Level, val event: String, val fields: Map<String, Any?>)

    val entries = mutableListOf<Entry>()
    val events: List<String> get() = entries.map { it.event }

    override fun log(level: Logger.Level, event: String, fields: Map<String, Any?>, error: Throwable?) {
        entries += Entry(level, event, fields)
    }

    fun fieldsOf(event: String): List<Map<String, Any?>> =
        entries.filter { it.event == event }.map { it.fields }
}

/** The shell services this lane does not exercise: any call is a bug in the test, not a scenario. */
object UnusedSecureStore : SecureStore {
    override suspend fun get(ns: String, key: String): ByteArray? = throw NotImplementedError()

    override suspend fun put(ns: String, key: String, value: ByteArray) = throw NotImplementedError()

    override suspend fun delete(ns: String, key: String) = throw NotImplementedError()

    override suspend fun names(ns: String): List<String> = throw NotImplementedError()
}

/** The shell's keychain, in a map. Namespaced the same way the real one is. */
class MapSecureStore(initial: Map<String, String> = emptyMap()) : SecureStore {
    val entries: MutableMap<String, ByteArray> =
        initial.mapValues { it.value.encodeToByteArray() }
            .mapKeys { "${SecureStore.SECRETS}/${it.key}" }
            .toMutableMap()

    override suspend fun get(ns: String, key: String): ByteArray? = entries["$ns/$key"]

    override suspend fun put(ns: String, key: String, value: ByteArray) {
        entries["$ns/$key"] = value
    }

    override suspend fun delete(ns: String, key: String) {
        entries.remove("$ns/$key")
    }

    override suspend fun names(ns: String): List<String> =
        entries.keys.filter { it.startsWith("$ns/") }.map { it.removePrefix("$ns/") }
}

object UnusedTokenProvider : TokenProvider {
    override suspend fun accessToken(): String = throw NotImplementedError()

    override suspend fun invalidate() = throw NotImplementedError()
}

object UnusedTransport : Transport {
    override suspend fun execute(plan: HttpPlan): HttpResult = throw NotImplementedError()
}

/**
 * The shell's muxer, in a fake: it writes the parts' bytes one after another, which is what a
 * lossless AAC concatenation looks like from the core's side. [calls] records what it was asked
 * to join, so a test can assert that a single-part recording is never remuxed at all.
 */
class FakeAudioTools(private val fs: FileSystem) : AudioTools {
    val calls = mutableListOf<List<Path>>()

    override suspend fun concat(parts: List<Path>, out: Path) {
        calls += parts
        out.parent?.let { fs.createDirectories(it) }
        fs.write(out) { parts.forEach { part -> write(fs.read(part) { readByteArray() }) } }
    }
}

/** The driver on its own, for a test that has to write a row no query in `Rec.sq` covers. */
fun inMemoryDriver(): SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
    RecDatabase.Schema.create(it)
}

fun inMemoryDatabase(): RecDatabase = RecDatabase(inMemoryDriver())

/**
 * The deps every lane starts from: the shell services it does not exercise throw, and the ones it
 * does are handed in. Every harness in the suite is one of these with a few of them replaced.
 */
fun testDeps(
    clock: Clock = FakeClock(),
    fileSystem: FileSystem = FakeFileSystem(),
    logger: Logger = FakeLogger(),
    secureStore: SecureStore = UnusedSecureStore,
    tokenProvider: TokenProvider = UnusedTokenProvider,
    transport: Transport = UnusedTransport,
    audio: AudioTools = FakeAudioTools(fileSystem),
    deviceId: String = DEVICE_ID,
    platform: Platform = Platform.MACOS,
    /** Unconfined keeps the in-memory JDBC driver and FakeFileSystem on the test's own thread;
     * the concurrency tests hand in a real multi-threaded dispatcher instead. */
    io: CoroutineDispatcher = Dispatchers.Unconfined,
): CoreDeps = CoreDeps(
    clock = clock,
    logger = logger,
    secureStore = secureStore,
    tokenProvider = tokenProvider,
    transport = transport,
    fileSystem = fileSystem,
    audio = audio,
    dataDir = "/data".toPath(),
    device = DeviceInfo(deviceId, platform, DEVICE_NAME),
    appVersion = TEST_APP_VERSION,
    io = io,
)

fun testMeta(
    recordingId: String = "01J9ABCDEF0123456789ABCDEF",
    source: Source = Source.DESKTOP,
    startedAt: String = "2026-08-26T01:00:00.000Z",
    timezone: String = "Asia/Seoul",
    title: String? = null,
    workflowId: String? = null,
    parts: List<Part> = emptyList(),
    status: RecordingStatus = RecordingStatus.RECORDING,
): RecordingMeta = RecordingMeta(
    schema = 1,
    recordingId = recordingId,
    source = source,
    platform = Platform.MACOS,
    deviceId = DEVICE_ID,
    deviceName = DEVICE_NAME,
    workflowId = workflowId,
    title = title,
    startedAt = startedAt,
    timezone = timezone,
    audio = AudioSettings(Codec.AAC_LC, Container.M4A, 16_000, 1, 32, 900),
    tracks = listOf(Track.MONO),
    parts = parts,
    status = status,
)

fun testPart(meta: RecordingMeta, number: Int, track: Track = Track.MONO): Part = Part(
    part = number,
    track = track,
    file = MetaWriter.partFileName(MetaWriter.baseName(meta), number, track),
    bytes = 3_601_234,
    sha256 = "0".repeat(64),
    startOffsetSec = (number - 1) * 900.0,
    durationSec = 900.0,
)

fun testWorkflow(
    id: String = "01J9ABCDEF0123456789ABCDEF",
    name: String = "회의",
    updatedAt: String = "2026-08-26T01:00:00.000Z",
    minDurationSec: Int = 0,
    steps: List<Step> = listOf(driveStep("up")),
): Workflow = Workflow(id, name, updatedAt, minDurationSec, steps)

fun driveStep(
    id: String,
    onError: OnError = OnError.ABORT,
    retry: Retry = Retry(),
): Step = Step.DriveUpload(id = id, onError = onError, retry = retry)

/** A `transcribe` step for the queue tests; the runner behind it is scripted, not real. */
fun transcribeStep(
    id: String,
    onError: OnError = OnError.ABORT,
    retry: Retry = Retry(),
): Step = Step.Transcribe(id = id, onError = onError, retry = retry, provider = "assemblyai", secretRef = "stt_key")

fun webhookStep(
    id: String,
    onError: OnError = OnError.ABORT,
    retry: Retry = Retry(),
): Step = Step.Webhook(id = id, onError = onError, retry = retry, url = "https://example.com/rec")

fun testDocument(vararg workflows: Workflow): WorkflowsDocument = WorkflowsDocument(
    schema = WorkflowParser.SCHEMA,
    revision = 1,
    updatedAt = "2026-08-26T01:00:00.000Z",
    updatedBy = "7c1e4b2a",
    workflows = workflows.toList(),
)

/** Writes the part files a purge is supposed to delete. */
fun seedFiles(fs: FileSystem, dir: Path, meta: RecordingMeta) {
    fs.createDirectories(dir)
    meta.parts.forEach { fs.write(dir / it.file) { writeUtf8(SEEDED_AUDIO) } }
}

/** What [seedFiles] writes into every part file. */
const val SEEDED_AUDIO: String = "audio"

/** Its sha256 — what a part row carries when the test is about fetching that file back. */
const val SEEDED_AUDIO_SHA256: String = "6ed8919ce20490a5e3ad8630a4fab69475297abd07db73918dd5f36fcfaeb11b"
