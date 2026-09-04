@file:OptIn(ExperimentalTime::class)

package recly.core

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import recly.core.db.RecDatabase
import recly.core.drive.ScriptedTokenProvider
import recly.core.drive.mockTransport
import recly.core.job.EnqueueResult
import recly.core.job.JobStatus
import recly.core.job.Retention
import recly.core.model.Platform
import recly.core.model.isoUtc
import recly.core.platform.CoreDeps
import recly.core.platform.DeviceInfo
import recly.core.platform.SecureStore
import recly.core.platform.TokenProvider
import recly.core.recording.MetaWriter
import recly.core.sync.WorkflowRepository
import recly.core.sync.WorkflowStore
import recly.core.testing.FakeClock
import recly.core.testing.FakeDrive
import recly.core.testing.FakeLogger
import recly.core.testing.FakeWebhook
import recly.core.testing.MapSecureStore
import recly.core.testing.RoutingTransport
import recly.core.testing.SEEDED_AUDIO
import recly.core.testing.SEEDED_AUDIO_SHA256
import recly.core.testing.START
import recly.core.testing.TEST_APP_VERSION
import recly.core.testing.seedFiles
import recly.core.testing.testMeta
import recly.core.testing.testPart

/**
 * The assembly root, exercised the way a shell uses it: build it from [CoreDeps] and a driver,
 * then record → enqueue → run.
 */
class ReclyCoreTest {
    private val drive = FakeDrive()
    private val webhook = FakeWebhook()
    private val clock = FakeClock()

    /** Dated by the same clock as the queue: the retention sweep reads the parts' mtimes. */
    private val fs = FakeFileSystem(clock)
    private val logger = FakeLogger()

    /** Both halves of "the token is gone" write here, in the order disconnect performed them. */
    private val tokenCalls = mutableListOf<String>()

    /** The shell's store, named so a test can make it refuse to be listed. */
    private val store = RecordingSecureStore(tokenCalls)

    private val deps = CoreDeps(
        clock = clock,
        logger = logger,
        secureStore = store,
        tokenProvider = RecordingTokenProvider(tokenCalls),
        transport = RoutingTransport(webhook.url, webhook.transport(fs), mockTransport(drive, fs)),
        fileSystem = fs,
        audio = recly.core.testing.FakeAudioTools(fs),
        dataDir = "/data".toPath(),
        device = DeviceInfo("7c1e4b2a", Platform.MACOS, "MacBook Pro"),
        appVersion = TEST_APP_VERSION,
        io = Dispatchers.Unconfined,
    )

    private val driver: SqlDriver =
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { RecDatabase.Schema.create(it) }

    private val core = ReclyCore(
        deps,
        object : DriverFactory {
            override fun create(): SqlDriver = driver
        },
    )

    /** The same rows the facade writes, for the assertions it exposes no reader for. */
    private val queries get() = RecDatabase(driver).recQueries

    @Test
    fun `a recording enqueued through the facade runs its default workflow to DONE`() = runBlocking<Unit> {
        val meta = testMeta(parts = listOf(testPart(testMeta(), 1)))
        val dir = "/data/recordings/${MetaWriter.baseName(meta)}".toPath()
        core.recordings.create(meta, dir)
        seedFiles(fs, dir, meta)
        core.recordings.finalize(meta.recordingId, START, durationSec = 900.0)

        // docs/05 "첫 기기": the facade hands the executor a document even on a fresh install, and
        // the device that seeds it points its own default at one of the starters (ADR-016).
        assertEquals(
            listOf("Memo"),
            core.workflows.seed(WorkflowRepository.MEMO_ID).workflows.map { it.name },
        )
        assertEquals(WorkflowRepository.MEMO_ID, core.workflows.deviceDefault())
        val enqueued = core.enqueue(meta.recordingId)

        assertIs<EnqueueResult.Enqueued>(enqueued)
        val summary = core.runDueJobs(START)

        assertEquals(listOf(enqueued.jobId), summary.jobIds)
        val job = core.jobs.observe().first().single { it.id == enqueued.jobId }
        assertEquals(JobStatus.DONE, job.status)
        // The upload really happened: the recording's folder and its meta are on the fake Drive.
        assertNotNull(drive.byName(MetaWriter.baseName(meta)))
        assertNotNull(drive.byName(MetaWriter.metaFileName(MetaWriter.baseName(meta))))
    }

    /**
     * ADR-017 after the 2026-09-03 decision, end to end: the parts of an uploaded recording outlive
     * the job that uploaded them, the sweep takes them a week on, and the detail screen fetches one
     * back from Drive to play it — which starts its window over.
     */
    @Test
    fun `the audio is a week-long cache, fetched back from Drive when the screen wants it`() =
        runBlocking<Unit> {
            val part = testPart(testMeta(), 1).copy(sha256 = SEEDED_AUDIO_SHA256)
            val meta = testMeta(parts = listOf(part))
            val dir = "/data/recordings/${MetaWriter.baseName(meta)}".toPath()
            core.recordings.create(meta, dir)
            seedFiles(fs, dir, meta)
            core.recordings.finalize(meta.recordingId, START, durationSec = 900.0)
            core.workflows.seed(WorkflowRepository.MEMO_ID)
            core.enqueue(meta.recordingId)

            core.runDueJobs()

            assertTrue(fs.exists(dir / part.file), "a DONE job leaves the audio where it is")
            // The parts being on disk no longer means Drive has not got them; this is what does.
            assertTrue(core.uploaded(meta.recordingId))
            assertEquals(setOf(meta.recordingId), core.uploadedRecordings())

            clock.advance(Retention.WINDOW)
            core.runDueJobs()

            assertFalse(fs.exists(dir / part.file), "the sweep takes it a week on")

            val audio = core.audio(meta.recordingId)

            assertEquals(listOf(dir / part.file), audio.paths)
            assertEquals(emptyList(), audio.missing)
            assertEquals(SEEDED_AUDIO, fs.read(dir / part.file) { readUtf8() })

            // A fetched part is dated from the file that was just written, not from the upload.
            clock.advance(Retention.WINDOW - 1.days)
            core.runDueJobs()
            assertTrue(fs.exists(dir / part.file), "the fetched part lost its window")

            clock.advance(1.days)
            core.runDueJobs()
            assertFalse(fs.exists(dir / part.file))
        }

    /** docs/05: the document is this device's own and never leaves it — a run reads it locally and
     * nothing about running the queue puts it in Drive. */
    @Test
    fun `runDueJobs runs off the local document and publishes nothing`() = runBlocking<Unit> {
        core.workflows.current()

        core.runDueJobs(START)

        assertNull(drive.byName("workflows.json"))
        assertTrue(core.transfer.purgeOrphans(START).isEmpty())
        // The watch summary is the definition's id and name and nothing else (ADR-016).
        assertEquals(
            listOf("Memo"),
            core.workflows.summary().map { it.name },
        )
    }

    /**
     * docs/03 "로그아웃 vs 연결 해제": disconnect empties this device of the account and of the work
     * queue, and of nothing else. The recordings are the user's own — an original that never got
     * uploaded is not deleted by a decision about an account (principle 3) — and neither are the
     * files in Drive, the workflows, the device default or the secrets: those are this device's own
     * configuration now, and nothing could fetch them back.
     */
    @Test
    fun `disconnect clears the tokens, the queue and the caches, and keeps everything else`() =
        runBlocking<Unit> {
            val uploaded = testMeta(parts = listOf(testPart(testMeta(), 1)))
            val dir = "/data/recordings/${MetaWriter.baseName(uploaded)}".toPath()
            core.recordings.create(uploaded, dir)
            seedFiles(fs, dir, uploaded)
            core.recordings.finalize(uploaded.recordingId, START, durationSec = 900.0)
            core.workflows.seed(WorkflowRepository.MEMO_ID)
            core.enqueue(uploaded.recordingId)
            core.runDueJobs(START)

            // A second recording that never went up: its audio is the only copy there is.
            val pending = testMeta(recordingId = "01J9ZZZZZZ0123456789ABCDEF", startedAt = "2026-08-26T02:00:00.000Z")
            val pendingMeta = pending.copy(parts = listOf(testPart(pending, 1)))
            val pendingDir = "/data/recordings/${MetaWriter.baseName(pendingMeta)}".toPath()
            core.recordings.create(pendingMeta, pendingDir)
            seedFiles(fs, pendingDir, pendingMeta)

            deps.secureStore.put(SecureStore.SECRETS, "webhook_secret", "whsec_x".encodeToByteArray())
            deps.secureStore.put(SecureStore.TOKENS, "refresh", "1//refresh".encodeToByteArray())
            assertTrue(core.jobs.list().isNotEmpty())
            assertNotNull(queries.selectFolderCache("recly").executeAsOneOrNull())
            assertEquals(WorkflowRepository.MEMO_ID, core.workflows.deviceDefault())

            core.disconnect(alsoDeleteRecordings = false)

            assertEquals(emptyList(), core.jobs.list())
            assertNull(deps.secureStore.get(SecureStore.TOKENS, "refresh"))
            assertNull(queries.selectFolderCache("recly").executeAsOneOrNull())
            // The device's own configuration is not the account's to take.
            assertEquals("whsec_x", core.secrets.get("webhook_secret"))
            assertNotNull(queries.syncGet(WorkflowStore.LOCAL_DOC).executeAsOneOrNull())
            assertEquals(WorkflowRepository.MEMO_ID, core.workflows.deviceDefault())

            assertNotNull(core.recordings.get(uploaded.recordingId), "the recording rows stay")
            assertNotNull(core.recordings.get(pendingMeta.recordingId))
            assertTrue(fs.exists(pendingDir / pendingMeta.parts.single().file), "an un-uploaded original stays")
            assertEquals(emptyList(), drive.deleted, "the files in Drive are the user's own")
        }

    /**
     * The namespace is only half of the account: the shell's [TokenProvider] holds the access token
     * in memory as well, and a `runDueJobs` right after a disconnect would otherwise be handed that
     * copy over an account the user has just let go of. It is dropped first — after it, there is
     * nothing left to read the namespace and cache it again on the way out.
     */
    @Test
    fun `disconnect drops the shell's cached token before it empties the namespace`() = runBlocking<Unit> {
        deps.secureStore.put(SecureStore.TOKENS, "refresh", "1//refresh".encodeToByteArray())

        core.disconnect(alsoDeleteRecordings = false)

        assertEquals(RecordingTokenProvider.INVALIDATE, tokenCalls.firstOrNull(), tokenCalls.toString())
        assertTrue(tokenCalls.contains("delete:${SecureStore.TOKENS}"), tokenCalls.toString())
        assertNull(deps.secureStore.get(SecureStore.TOKENS, "refresh"))
    }

    /**
     * A store that will not be *listed* fails the disconnect closed. The sweep is a `names` then a
     * `delete` each, so a listing read as "none" would delete nothing and return a
     * [DisconnectResult] saying the device was emptied — over a device still holding every token.
     * The throw travels instead, and the shells turn it into a clean-up that is still owed
     * (`REVOKED_CLEANUP_OWED`) with the retry on screen.
     */
    @Test
    fun `disconnect fails rather than reporting a namespace it could not list`() = runBlocking<Unit> {
        deps.secureStore.put(SecureStore.TOKENS, "refresh", "1//refresh".encodeToByteArray())
        store.namesFails = IllegalStateException("keychain -34018")

        assertFailsWith<IllegalStateException> { core.disconnect(alsoDeleteRecordings = false) }

        store.namesFails = null
        assertEquals(
            listOf("refresh"),
            deps.secureStore.names(SecureStore.TOKENS),
            "nothing in the namespace was touched",
        )
        assertFalse(tokenCalls.any { it.startsWith("delete:") }, tokenCalls.toString())
    }

    @Test
    fun `disconnect with the recordings box also takes the recordings`() = runBlocking<Unit> {
        val meta = testMeta(parts = listOf(testPart(testMeta(), 1)))
        val dir = "/data/recordings/${MetaWriter.baseName(meta)}".toPath()
        core.recordings.create(meta, dir)
        seedFiles(fs, dir, meta)

        val result = core.disconnect(alsoDeleteRecordings = true)

        assertEquals(DisconnectResult(deletedRecordings = 1, busyRecordings = emptyList()), result)
        assertNull(core.recordings.get(meta.recordingId))
        assertFalse(fs.exists(dir))
        assertEquals(emptyList(), drive.deleted, "still never Drive")
    }

    /**
     * docs/03: a `RUNNING` job is reading the very files "녹음도 함께 삭제" would delete, so that one
     * recording — and the queue rows that run is written against — outlives the disconnect, and the
     * result says which, so the screen can say so instead of losing it silently.
     */
    @Test
    fun `disconnect keeps a recording whose job is running, and names it`() = runBlocking<Unit> {
        val busy = testMeta(parts = listOf(testPart(testMeta(), 1)))
        val busyDir = "/data/recordings/${MetaWriter.baseName(busy)}".toPath()
        core.recordings.create(busy, busyDir)
        seedFiles(fs, busyDir, busy)
        core.recordings.finalize(busy.recordingId, START, durationSec = 900.0)
        core.workflows.seed(WorkflowRepository.MEMO_ID)
        core.enqueue(busy.recordingId)
        val busyJob = core.jobs.list().single()
        queries.updateJobStatus(JobStatus.RUNNING.name, null, START.isoUtc(), busyJob.id)

        val idle = testMeta(recordingId = "01J9ZZZZZZ0123456789ABCDEF", startedAt = "2026-08-26T02:00:00.000Z")
        val idleMeta = idle.copy(parts = listOf(testPart(idle, 1)))
        val idleDir = "/data/recordings/${MetaWriter.baseName(idleMeta)}".toPath()
        core.recordings.create(idleMeta, idleDir)
        seedFiles(fs, idleDir, idleMeta)

        val result = core.disconnect(alsoDeleteRecordings = true)

        assertEquals(DisconnectResult(deletedRecordings = 1, busyRecordings = listOf(busy.recordingId)), result)
        assertNotNull(core.recordings.get(busy.recordingId), "the busy recording stays")
        assertTrue(fs.exists(busyDir / busy.parts.single().file))
        assertEquals(listOf(busyJob.id), core.jobs.list().map { it.id }, "and so does the job that is reading it")
        assertTrue(core.jobs.steps(busyJob.id).isNotEmpty())

        assertNull(core.recordings.get(idleMeta.recordingId), "the rest go")
        assertFalse(fs.exists(idleDir))
        assertNull(deps.secureStore.get(SecureStore.TOKENS, "refresh"))
    }

    /** docs/05 "시크릿": the values are this device's, they go in and out through `core.secrets`,
     * and nothing about them ever reaches Drive. */
    @Test
    fun `secrets are read and written on the device and nowhere else`() = runBlocking<Unit> {
        core.secrets.put("clova_key", "sk-a")
        core.secrets.put("openai_key", "sk-o")

        assertEquals(listOf("clova_key", "openai_key"), core.secrets.names())
        assertEquals("sk-a", core.secrets.get("clova_key"))
        core.secrets.delete("clova_key")
        assertNull(core.secrets.get("clova_key"))
        assertEquals(listOf("openai_key"), core.secrets.names())
        assertNull(drive.byName("secrets.enc"))
    }
}

/**
 * The shell's keychain, plus a note of which namespace every deletion emptied — "연결 해제" is the
 * one caller that has to do those in an order.
 */
private class RecordingSecureStore(
    private val calls: MutableList<String>,
    private val backing: MapSecureStore = MapSecureStore(),
) : SecureStore by backing {
    /** What [names] throws instead of answering — a keychain that will not be listed at all. */
    var namesFails: Throwable? = null

    override suspend fun delete(ns: String, key: String) {
        calls += "delete:$ns"
        backing.delete(ns, key)
    }

    override suspend fun names(ns: String): List<String> {
        namesFails?.let { throw it }
        return backing.names(ns)
    }
}

/** A shell token provider that notes the [invalidate] which drops its in-memory copy. */
private class RecordingTokenProvider(private val calls: MutableList<String>) : TokenProvider {
    override suspend fun accessToken(): String = ScriptedTokenProvider.FIRST

    override suspend fun invalidate() {
        calls += INVALIDATE
    }

    companion object {
        const val INVALIDATE = "invalidate"
    }
}
