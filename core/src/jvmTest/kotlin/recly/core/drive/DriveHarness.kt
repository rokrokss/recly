@file:OptIn(ExperimentalTime::class)

package recly.core.drive

import io.ktor.client.HttpClient
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonObject
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import recly.core.db.RecDatabase
import recly.core.job.Job
import recly.core.job.JobStatus
import recly.core.job.StepContext
import recly.core.job.StepOutcome
import recly.core.job.StepOutput
import recly.core.model.AudioSettings
import recly.core.model.Codec
import recly.core.model.Container
import recly.core.model.Part
import recly.core.model.Platform
import recly.core.model.RecordingMeta
import recly.core.model.RecordingStatus
import recly.core.model.Source
import recly.core.model.Step
import recly.core.model.Track
import recly.core.platform.TokenProvider
import recly.core.recording.MetaWriter
import recly.core.recording.PartHasher
import recly.core.recording.RecordingRecord
import recly.core.recording.RecordingRepository
import recly.core.testing.DEVICE_ID
import recly.core.testing.DEVICE_NAME
import recly.core.testing.FakeClock
import recly.core.testing.FakeDrive
import recly.core.testing.FakeLogger
import recly.core.testing.FakeWebhook
import recly.core.testing.MapSecureStore
import recly.core.testing.RoutingTransport
import recly.core.testing.START
import recly.core.testing.STEP_RUN_ID
import recly.core.testing.inMemoryDatabase
import recly.core.testing.testDeps
import recly.core.testing.testWorkflow

/** [MockEngine][io.ktor.client.engine.mock.MockEngine] is an engine, not a factory, so the client is built here. */
internal fun mockTransport(drive: FakeDrive, fs: FileSystem): KtorTransport =
    KtorTransport(HttpClient(drive.engine()), fs)

/**
 * A shell that hands out one token and only swaps it when it is told the old one was rejected —
 * so a retry after 401 is only different from the request that failed if [invalidate] was called.
 * With [rotates] off it models a shell whose refresh does nothing, which must end in `NEEDS_AUTH`.
 */
class ScriptedTokenProvider(private val rotates: Boolean = true) : TokenProvider {
    var invalidations = 0
        private set

    var token = FIRST
        private set

    override suspend fun accessToken(): String = token

    override suspend fun invalidate() {
        invalidations++
        if (rotates) token = "token${invalidations + 1}"
    }

    companion object {
        const val FIRST = "token1"
        const val SECOND = "token2"
    }
}

/**
 * A finalized recording on a fake disk, a [FakeDrive] behind a real [KtorTransport], and a runner
 * wired to both. Everything the `drive.upload` tests need except the scenario.
 */
class DriveHarness(
    platform: Platform = Platform.ANDROID,
    val partCount: Int = 3,
    val partBytes: Long = RESUMABLE_BYTES,
    val tracks: List<Track> = listOf(Track.MONO),
    val title: String? = "주간 회의",
    val tokens: ScriptedTokenProvider = ScriptedTokenProvider(),
    /** Set to give the workflow's `webhook` step a real endpoint (the drive→webhook end to end). */
    val webhook: FakeWebhook? = null,
) {
    val drive = FakeDrive()
    val clock = FakeClock()

    /** Dated by the same clock as the queue: the retention sweep reads the parts' mtimes. */
    val fs = FakeFileSystem(clock)
    val secrets = MapSecureStore()
    val logger = FakeLogger()
    val db: RecDatabase = inMemoryDatabase()

    val deps = testDeps(
        clock = clock,
        fileSystem = fs,
        logger = logger,
        secureStore = secrets,
        tokenProvider = tokens,
        transport = webhook
            ?.let { RoutingTransport(it.url, it.transport(fs), mockTransport(drive, fs)) }
            ?: mockTransport(drive, fs),
        platform = platform,
    )

    val recordings = RecordingRepository(db, deps)
    val store = DriveStore(db, deps)
    val api = DriveApi(deps)
    val runner = DriveUploadRunner(api, FolderResolver(api, store, deps), store, deps)

    val recordingId = "01J9ABCDEF0123456789ABCDEF"
    val base: String
    val dir: Path
    val workflow = testWorkflow(steps = listOf(Step.DriveUpload(id = "up")))

    /** What the executor would have persisted in `step_run.state_json`. */
    var state: JsonObject? = null

    /** Every state the current [run] saved, in order — the resume points a kill could land on. */
    val saves = mutableListOf<JsonObject>()

    private val meta: RecordingMeta

    init {
        val skeleton = meta(emptyList())
        base = MetaWriter.baseName(skeleton)
        dir = "/data/recordings/$base".toPath()
        fs.createDirectories(dir)
        val parts = buildList {
            for (number in 1..partCount) {
                tracks.forEachIndexed { index, track ->
                    val name = MetaWriter.partFileName(base, number, track)
                    val content = ByteArray(partBytes.toInt()) { (it * 31 + number * 7 + index).toByte() }
                    fs.write(dir / name) { write(content) }
                    add(
                        Part(
                            part = number,
                            track = track,
                            file = name,
                            bytes = partBytes,
                            sha256 = sha256(dir / name),
                            startOffsetSec = (number - 1) * 900.0,
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
    }

    /** Registers the recording in the DB — needed only by the tests that go through the executor. */
    suspend fun register() {
        recordings.create(meta, dir)
    }

    suspend fun run(step: Step.DriveUpload = workflow.steps.first() as Step.DriveUpload): StepOutput {
        saves.clear()
        val snapshot = workflow.copy(steps = listOf(step))
        return done(
            StepContext(
                job = Job(
                    id = "01J9JOB0000000000000000000",
                    recordingId = recordingId,
                    workflowId = workflow.id,
                    workflow = snapshot,
                    status = JobStatus.RUNNING,
                    createdAt = START,
                    updatedAt = START,
                    nextRunAt = null,
                ),
                workflow = snapshot,
                stepRunId = STEP_RUN_ID,
                step = step,
                recording = RecordingRecord(recordingId, meta, dir),
                prior = emptyMap(),
                state = state,
                saveState = {
                    state = it
                    saves += it
                },
                saveOutput = {},
                deps = deps,
            ),
        )
    }

    /** `drive.upload` never polls, so every outcome it can return is a [StepOutcome.Done]. */
    private suspend fun done(ctx: StepContext): StepOutput = (runner.run(ctx) as StepOutcome.Done).output

    /** True if some state this run saved had that file with nothing to delete and nothing uploaded. */
    fun savedCleanSlateFor(key: String): Boolean = saves.any {
        val file = DriveUploadState.from(it).files[key]
        file != null && file.pendingDelete == null && file.fileId == null && file.sessionUri == null
    }

    fun partName(number: Int, track: Track = tracks.first()): String =
        MetaWriter.partFileName(base, number, track)

    fun metaName(): String = MetaWriter.metaFileName(base)

    fun folderCreations(): List<String> = drive.requests
        .filter { it.method == "POST" && it.path == "/drive/v3/files" }
        .map { it.body.decodeToString() }

    fun sessionStarts(): List<FakeDrive.Recorded> =
        drive.requests.filter { it.path == "/upload/drive/v3/files" && it.uploadType == "resumable" }

    fun multipartUploads(): List<FakeDrive.Recorded> =
        drive.requests.filter { it.path == "/upload/drive/v3/files" && it.uploadType == "multipart" }

    fun queryRequests(): List<FakeDrive.Recorded> = drive.requests.filter {
        it.url.startsWith(FakeDrive.SESSION_PREFIX) && it.headers["Content-Range"]?.startsWith("bytes */") == true
    }

    private fun sha256(path: Path): String = kotlinx.coroutines.runBlocking { PartHasher.sha256(fs, path) }

    private fun meta(parts: List<Part>) = RecordingMeta(
        schema = 1,
        recordingId = recordingId,
        source = Source.DESKTOP,
        platform = Platform.MACOS,
        deviceId = DEVICE_ID,
        deviceName = DEVICE_NAME,
        workflowId = workflow.id,
        title = title,
        startedAt = "2026-08-26T01:00:00.000Z",
        timezone = "Asia/Seoul",
        audio = AudioSettings(Codec.AAC_LC, Container.M4A, 16_000, 1, 32, 900),
        tracks = tracks,
        parts = parts,
        status = RecordingStatus.RECORDING,
    )

    companion object {
        /** Just past the 5 MB multipart limit, so these go resumable: six 1 MiB chunks. */
        const val RESUMABLE_BYTES = 5L * 1024 * 1024 + 512
        const val SMALL_BYTES = 4096L
    }
}
