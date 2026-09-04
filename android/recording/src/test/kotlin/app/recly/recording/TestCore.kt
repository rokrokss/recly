@file:OptIn(ExperimentalTime::class)

package app.recly.recording

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import java.io.IOException
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import recly.core.DriverFactory
import recly.core.ReclyCore
import recly.core.db.RecDatabase
import recly.core.model.AudioSettings
import recly.core.model.Codec
import recly.core.model.Container
import recly.core.model.Part
import recly.core.model.Platform
import recly.core.model.RecordingMeta
import recly.core.model.RecordingStatus
import recly.core.model.Source
import recly.core.model.Track
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
import recly.core.sync.WorkflowRepository

/**
 * A real [ReclyCore] on the JVM — the in-memory JDBC driver and a fake disk. The recovery scan is
 * mostly `RecordingRepository` and `enqueue` talking to each other, and testing it against doubles
 * would test the doubles.
 */
/** Kept by the caller when a test needs to plant a row the public API cannot make, like a DONE job. */
fun testDriver(): SqlDriver =
    JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { RecDatabase.Schema.create(it) }

fun testCore(
    fileSystem: FileSystem,
    logger: Logger = RecordingLogger(),
    driver: SqlDriver = testDriver(),
): ReclyCore = ReclyCore(
    CoreDeps(
        clock = object : Clock {
            override fun now(): Instant = Instant.parse("2026-08-26T01:00:00.000Z")
        },
        logger = logger,
        secureStore = NoSecureStore,
        tokenProvider = NoTokenProvider,
        transport = NoTransport,
        fileSystem = fileSystem,
        audio = NoAudioTools,
        dataDir = "/data".toPath(),
        device = DeviceInfo("7c1e4b2a-0d3f-4a7e-9b1c-2f5e8d6a4c10", Platform.ANDROID, "Pixel"),
        appVersion = "0.1.0",
        // Unconfined keeps the JDBC driver and the fake disk on the test's own thread.
        io = Dispatchers.Unconfined,
    ),
    object : DriverFactory {
        override fun create(): SqlDriver = driver
    },
)

/**
 * The shell, as `RecorderService` and [RecordingRecovery] see it. It records every hand-over so a
 * test can say which stop asked for what, and it does the phone's half of `onRecordingReady` —
 * `enqueue` — because that is what the recovery tests then check for on the job table.
 */
class TestHost(
    private val core: ReclyCore,
    /** Off for a test whose recordings are ids and nothing else — `enqueue` needs a real row. */
    private val enqueues: Boolean = true,
) : RecorderHost {
    val ready: MutableList<Pair<String, Boolean>> = mutableListOf()

    override suspend fun core(): ReclyCore = core

    override suspend fun onRecordingReady(recordingId: String, enqueue: Boolean) {
        ready += recordingId to enqueue
        if (enqueues && enqueue) {
            // The phone seeds the docs/05 starters and points its own default at 메모 the first time
            // a screen opens (ADR-016), which is long before any recording is handed over. Without
            // it nothing resolves and every recovery here would be testing NO_WORKFLOW instead.
            core.workflows.seed(WorkflowRepository.MEMO_ID)
            core.enqueue(recordingId)
        }
    }
}

/**
 * A disk that can refuse to read a named file — the only way to make `addPart` fail on demand, and
 * so the only way to test what a stop does when a part cannot be filed.
 */
class FlakyFileSystem(delegate: FileSystem) : ForwardingFileSystem(delegate) {
    val failReadsOf: MutableSet<String> = mutableSetOf()
    val failWritesOf: MutableSet<String> = mutableSetOf()

    override fun source(file: Path): okio.Source {
        if (file.name in failReadsOf) throw IOException("read refused: ${file.name}")
        return super.source(file)
    }

    override fun sink(file: Path, mustCreate: Boolean): okio.Sink {
        if (file.name in failWritesOf) throw IOException("write refused: ${file.name}")
        return super.sink(file, mustCreate)
    }
}

/**
 * The container reader, which on a bare JVM there is no `MediaMetadataRetriever` to be: a segment's
 * length is its size over the bitrate, and a test names the files whose container cannot be read at
 * all — the tail the process died inside, which has no moov and so no length.
 */
internal class TestDurations(
    private val fs: FileSystem,
    /** The 32 kbps of [phoneMeta]. */
    private val bytesPerSec: Int = 4_000,
) : DurationProbe {
    val unreadable: MutableSet<String> = mutableSetOf()

    override fun seconds(file: Path): Double? {
        if (file.name in unreadable) return null
        val bytes = fs.metadataOrNull(file)?.size ?: return null
        return bytes.toDouble() / bytesPerSec
    }
}

class RecordingLogger : Logger {
    val events: MutableList<String> = mutableListOf()
    val fields: MutableList<Map<String, Any?>> = mutableListOf()

    override fun log(level: Logger.Level, event: String, fields: Map<String, Any?>, error: Throwable?) {
        events += event
        this.fields += fields
    }

    fun fieldsOf(event: String): List<Map<String, Any?>> =
        events.withIndex().filter { it.value == event }.map { fields[it.index] }
}

/** Anything a recovery reaches for here is a bug in the test, not a scenario. */
private object NoSecureStore : SecureStore {
    override suspend fun get(ns: String, key: String): ByteArray? = null

    override suspend fun put(ns: String, key: String, value: ByteArray) = Unit

    override suspend fun delete(ns: String, key: String) = Unit

    override suspend fun names(ns: String): List<String> = emptyList()
}

private object NoTokenProvider : TokenProvider {
    override suspend fun accessToken(): String = throw NotImplementedError()

    override suspend fun invalidate() = Unit
}

private object NoTransport : Transport {
    override suspend fun execute(plan: HttpPlan): HttpResult = throw NotImplementedError()
}

/** Nothing in the recovery scan transcribes, so a call here is a bug in the test. */
private object NoAudioTools : AudioTools {
    override suspend fun concat(parts: List<Path>, out: Path): Unit = throw NotImplementedError()
}

fun phoneMeta(
    recordingId: String = "01M10N83M5TAQ396AT8F9PGWFX",
    parts: List<Part> = emptyList(),
    status: RecordingStatus = RecordingStatus.RECORDING,
): RecordingMeta = RecordingMeta(
    schema = 1,
    recordingId = recordingId,
    source = Source.PHONE,
    platform = Platform.ANDROID,
    deviceId = "7c1e4b2a-0d3f-4a7e-9b1c-2f5e8d6a4c10",
    deviceName = "Pixel",
    workflowId = null,
    startedAt = "2026-08-26T01:00:00.000Z",
    timezone = "Asia/Seoul",
    audio = AudioSettings(Codec.AAC_LC, Container.M4A, 16_000, 1, 32, 900),
    tracks = listOf(Track.MONO),
    parts = parts,
    status = status,
)
