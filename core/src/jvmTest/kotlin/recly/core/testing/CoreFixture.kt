@file:OptIn(ExperimentalTime::class)

package recly.core.testing

import app.cash.sqldelight.db.SqlDriver
import kotlin.time.ExperimentalTime
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import recly.core.DriverFactory
import recly.core.ReclyCore
import recly.core.drive.ScriptedTokenProvider
import recly.core.drive.mockTransport
import recly.core.job.EnqueueResult
import recly.core.model.Context
import recly.core.model.Part
import recly.core.model.Platform
import recly.core.model.RecordingMeta
import recly.core.model.RecordingStatus
import recly.core.model.Source
import recly.core.model.Track
import recly.core.model.recJson
import recly.core.platform.CoreDeps
import recly.core.platform.DeviceInfo
import recly.core.platform.TokenProvider
import recly.core.recording.MetaWriter
import recly.core.storage.LocalFolder
import recly.core.transcribe.LocalEngineInfo
import recly.core.transcribe.LocalEngineStatus
import recly.core.transcribe.LocalTranscriptionEngine
import recly.core.transcribe.LocalTranscriptionProgress
import recly.core.transcribe.LocalTranscriptionRequest
import recly.core.transcribe.LocalTranscriptionResult
import recly.core.transcribe.SttSegment
import recly.core.transcribe.TranscribeRunner
import recly.core.transcribe.Transcript
import recly.core.transcribe.TranscriptNormalizer

/**
 * A whole [ReclyCore] the way a shell holds one, on a fake Drive, a fake disk and one clock — for the
 * tests of the facade's own calls (highlights, re-transcription, editing, import, search, export).
 */
class CoreFixture(
    val engine: FakeLocalEngine? = FakeLocalEngine(),
    tokenProvider: TokenProvider = ScriptedTokenProvider(),
    platform: Platform = Platform.MACOS,
    localFolder: ((FakeFileSystem) -> LocalFolder)? = null,
) {
    val drive = FakeDrive()
    val clock = FakeClock()
    val fs = FakeFileSystem(clock)
    val logger = FakeLogger()
    val store = MapSecureStore()
    val deps = CoreDeps(
        clock = clock,
        logger = logger,
        secureStore = store,
        tokenProvider = tokenProvider,
        transport = mockTransport(drive, fs),
        fileSystem = fs,
        audio = FakeAudioTools(fs),
        dataDir = "/data".toPath(),
        device = DeviceInfo(DEVICE_ID, platform, DEVICE_NAME),
        io = kotlinx.coroutines.Dispatchers.Unconfined,
        localTranscription = engine ?: recly.core.transcribe.UnavailableLocalTranscriptionEngine(),
        localFolder = localFolder?.invoke(fs),
    )
    val driver: SqlDriver = inMemoryDriver()
    val core = ReclyCore(deps, object : DriverFactory {
        override fun create(): SqlDriver = driver
    })

    /** The month folder every other device's recording of the fake account sits in (ADR-020). */
    private val month by lazy { drive.put("2026-08", "root", ByteArray(0), FakeDrive.FOLDER_MIME) }

    fun dirOf(meta: RecordingMeta): Path = "/data/recordings/${MetaWriter.baseName(meta)}".toPath()

    /** A finished recording of this device, its audio on disk, no job yet. */
    suspend fun record(
        id: String = ID,
        title: String? = null,
        parts: Int = 1,
        participants: Int? = null,
        startedAt: String = "2026-08-26T01:00:00.000Z",
    ): RecordingMeta {
        val bare = testMeta(recordingId = id, title = title, startedAt = startedAt)
            .copy(context = participants?.let { Context(participants = it) })
        val meta = bare.copy(parts = (1..parts).map { testPart(bare, it).copy(bytes = SEEDED_AUDIO.length.toLong(), sha256 = SEEDED_AUDIO_SHA256) })
        core.recordings.create(meta, dirOf(meta))
        seedFiles(fs, dirOf(meta), meta)
        core.recordings.finalize(id, START, durationSec = parts * 900.0)
        return core.recordings.get(id)!!.meta
    }

    /** [record], then the fixed plan run until nothing is left to do. */
    suspend fun recordAndRun(id: String = ID, title: String? = null, participants: Int? = null): RecordingMeta {
        record(id, title, participants = participants)
        check(core.enqueue(id) is EnqueueResult.Enqueued)
        drain()
        return core.recordings.get(id)!!.meta
    }

    /** Job passes until the queue settles: a local transcription takes one pass to start and one to publish. */
    suspend fun drain(passes: Int = 4) {
        repeat(passes) {
            core.runDueJobs(clock.now())
            clock.advance(kotlin.time.Duration.parse("1m"))
        }
    }

    /** What another device of the account leaves on Drive: the stamped folder, its parts, `meta.json`, and maybe a transcript. */
    fun otherDevice(
        id: String,
        title: String? = null,
        transcript: ((RecordingMeta) -> Transcript)? = null,
        folderProperties: Map<String, String> = emptyMap(),
    ): OtherDevice {
        val bare = testMeta(recordingId = id, source = Source.PHONE, title = title)
        val base = MetaWriter.baseName(bare)
        val meta = bare.copy(
            parts = listOf(
                Part(1, Track.MONO, MetaWriter.partFileName(base, 1, Track.MONO), SEEDED_AUDIO.length.toLong(), SEEDED_AUDIO_SHA256, 0.0, 900.0),
            ),
            endedAt = "2026-08-26T01:15:00.000Z",
            durationSec = 900.0,
            status = RecordingStatus.FINALIZED,
        )
        val folderId = drive.putFolder(base, month, mapOf("recordingId" to id) + folderProperties, title)
        meta.parts.forEach { drive.put(it.file, folderId, SEEDED_AUDIO.encodeToByteArray()) }
        drive.put(MetaWriter.metaFileName(base), folderId, recJson.encodeToString(meta).encodeToByteArray(), "application/json")
        transcript?.invoke(meta)?.let {
            drive.put(TranscribeRunner.jsonFileName(base), folderId, recJson.encodeToString(it).encodeToByteArray(), "application/json")
            drive.put(TranscribeRunner.textFileName(base), folderId, TranscriptNormalizer.text(it).encodeToByteArray(), "text/plain")
        }
        return OtherDevice(id, folderId, meta)
    }

    class OtherDevice(val recordingId: String, val folderId: String, val meta: RecordingMeta)

    companion object {
        const val ID: String = "01J9ABCDEF0123456789ABCDEF"
    }
}

/**
 * An on-device engine that is always ready and says [text] once per request, from [speaker] when it
 * diarizes. [requests] is what the core asked of it.
 */
class FakeLocalEngine(
    var supportsDiarization: Boolean = false,
    var text: String = "hello",
    var speakers: List<String> = listOf("spk-a", "spk-b"),
) : LocalTranscriptionEngine {
    val requests = mutableListOf<LocalTranscriptionRequest>()

    override suspend fun status(language: String) =
        LocalEngineInfo(LocalEngineStatus.READY, "fake-local", "test-1", supportsDiarization = supportsDiarization)

    override suspend fun prepare(language: String) = status(language)

    override suspend fun transcribe(request: LocalTranscriptionRequest, progress: LocalTranscriptionProgress): LocalTranscriptionResult {
        requests += request
        speakers.forEachIndexed { index, speaker ->
            progress.checkpoint(
                SttSegment(index.toDouble(), index + 1.0, speaker.takeIf { request.diarize }, "$text ${index + 1}"),
                index + 1.0,
            )
        }
        return LocalTranscriptionResult(emptyList())
    }
}
