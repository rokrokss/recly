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
import kotlinx.coroutines.launch
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
import recly.core.processing.ExternalTranscription
import recly.core.processing.ProcessingPlan
import recly.core.processing.ProcessingSaveResult
import recly.core.processing.ProcessingSettingsState
import recly.core.processing.ProcessingTranscription
import recly.core.processing.TranscriptionMode
import recly.core.recording.MetaWriter
import recly.core.testing.FakeClock
import recly.core.testing.FakeDrive
import recly.core.testing.FakeEndpoint
import recly.core.testing.FakeLogger
import recly.core.testing.MapSecureStore
import recly.core.testing.RoutingTransport
import recly.core.testing.SEEDED_AUDIO
import recly.core.testing.SEEDED_AUDIO_SHA256
import recly.core.testing.START
import recly.core.testing.seedFiles
import recly.core.testing.testMeta
import recly.core.testing.testPart

/**
 * The assembly root, exercised the way a shell uses it: build it from [CoreDeps] and a driver,
 * then record → enqueue → run.
 */
class ReclyCoreTest {
    private val drive = FakeDrive()
    /** The transcription provider's endpoint (`groq`, pointed here by its `invokeUrl`). */
    private val stt = FakeEndpoint("https://stt.example.com/v1").apply { responseBody = """{"text":"hello","duration":1.0}""" }
    private val clock = FakeClock()

    /** Dated by the same clock as the queue: the retention sweep reads the parts' mtimes. */
    private val fs = FakeFileSystem(clock)
    private val logger = FakeLogger()

    /** Both halves of "the token is gone" write here, in the order disconnect performed them. */
    private val tokenCalls = mutableListOf<String>()
    private val tokenProvider = RecordingTokenProvider(tokenCalls)

    /** The shell's store, named so a test can make it refuse to be listed. */
    private val store = RecordingSecureStore(tokenCalls)

    private val deps = CoreDeps(
        clock = clock,
        logger = logger,
        secureStore = store,
        tokenProvider = tokenProvider,
        transport = RoutingTransport(stt.url, stt.transport(fs), mockTransport(drive, fs)),
        fileSystem = fs,
        audio = recly.core.testing.FakeAudioTools(fs),
        dataDir = "/data".toPath(),
        device = DeviceInfo("7c1e4b2a", Platform.MACOS, "MacBook Pro"),
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
    fun `an open detail receives a transcript while its job is still running`() = runBlocking {
        val meta = testMeta(parts = listOf(testPart(testMeta(), 1)))
        val dir = "/data/recordings/${MetaWriter.baseName(meta)}".toPath()
        core.recordings.create(meta, dir)
        seedFiles(fs, dir, meta)
        core.recordings.finalize(meta.recordingId, START, durationSec = 900.0)
        val workflow = recly.core.model.Workflow(
            "workflow", "Transcribe then publish", START.isoUtc(), steps = listOf(
                recly.core.model.Step.DriveUpload("upload"),
                recly.core.model.Step.Transcribe("stt", provider = "assemblyai", secretRef = "test_key"),
                recly.core.model.Step.TranscriptPublish("publish"),
            ),
        )
        val store = recly.core.job.JobStore(RecDatabase(driver), deps)
        val job = store.enqueue(meta.recordingId, workflow, START, JobStatus.RUNNING)!!
        val seen = kotlinx.coroutines.channels.Channel<recly.core.transcribe.RecordingResult>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val observing = launch(Dispatchers.Unconfined) { core.observeResults(meta.recordingId).collect { seen.send(it) } }
        try {
            assertEquals(recly.core.transcribe.TranscriptAvailability.PENDING, kotlinx.coroutines.withTimeout(5000) { seen.receive() }.availability)
            fs.write(dir / recly.core.transcribe.TranscribeRunner.jsonFileName(MetaWriter.baseName(meta))) {
                writeUtf8("""{"schema":1,"recordingId":"${meta.recordingId}","track":"mono","language":"ko","provider":{"name":"assemblyai"},"createdAt":"${START.isoUtc()}","durationSec":1.0,"speakers":[],"segments":[{"start":0.0,"end":1.0,"speaker":"S1","text":"Arrived while open"}]}""")
            }
            val step = store.stepsOf(job.id).single { it.stepId == "stt" }
            store.updateStep(step.copy(status = recly.core.job.StepStatus.SUCCEEDED))
            val result = kotlinx.coroutines.withTimeout(5000) { seen.receive() }
            assertEquals(recly.core.transcribe.TranscriptAvailability.READY, result.availability)
            assertEquals("Arrived while open", result.transcript?.segments?.single()?.text)
            assertEquals(JobStatus.RUNNING, store.get(job.id)?.status, "publishing need not finish before the text appears")
        } finally { observing.cancel(); seen.close() }
    }


    @Test
    fun `a recording enqueued through the facade runs its fixed plan to DONE`() = runBlocking<Unit> {
        val meta = testMeta(parts = listOf(testPart(testMeta(), 1)))
        val dir = "/data/recordings/${MetaWriter.baseName(meta)}".toPath()
        core.recordings.create(meta, dir)
        seedFiles(fs, dir, meta)
        core.recordings.finalize(meta.recordingId, START, durationSec = 900.0)

        // docs/05: a fresh install has settings as soon as a recording starts, and the recording
        // froze them — no transcription here, where no on-device runtime is installed.
        val enqueued = core.enqueue(meta.recordingId)

        assertIs<EnqueueResult.Enqueued>(enqueued)
        val summary = core.runDueJobs(START)

        assertEquals(listOf(enqueued.jobId), summary.jobIds)
        val job = core.jobs.observe().first().single { it.id == enqueued.jobId }
        assertEquals(JobStatus.DONE, job.status)
        assertEquals(ProcessingPlan.ID, job.workflowId)
        assertEquals(listOf("upload"), job.workflow!!.steps.map { it.id })
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

    /**
     * docs/03 "로그아웃 vs 연결 해제": disconnect clears credentials and completed jobs while preserving
     * unfinished workflow progress. The recordings are the user's own — an original that never got
     * uploaded is not deleted by a decision about an account (principle 3) — and neither are the
     * files in Drive, the processing settings or the secrets: those are this device's own
     * configuration, and nothing could fetch them back.
     */
    @Test
    fun `disconnect and reconnect restore completed recordings and playback without replaying jobs`() = runBlocking {
        val part = testPart(testMeta(), 1).copy(sha256 = SEEDED_AUDIO_SHA256)
        val meta = testMeta(parts = listOf(part))
        val dir = "/data/recordings/${MetaWriter.baseName(meta)}".toPath()
        core.recordings.create(meta, dir)
        seedFiles(fs, dir, meta)
        core.recordings.finalize(meta.recordingId, START, durationSec = 900.0)
        core.enqueue(meta.recordingId)
        core.runDueJobs(START)
        clock.advance(8.days)
        core.runDueJobs(clock.now())
        assertFalse(fs.exists(dir / part.file))
        core.disconnect(alsoDeleteRecordings = false)

        // The test provider remains authorized: the next pull models the successful reconnection.
        val summary = core.pullRemoteRecordings()
        assertNull(summary.skipped, "disconnect must reset the recent-pull throttle")
        assertTrue(core.recordings.get(meta.recordingId)!!.driveSynced)
        assertTrue(core.uploaded(meta.recordingId))
        assertEquals(emptyList(), core.jobs.list())
        assertEquals(EnqueueResult.AlreadySynced, core.enqueue(meta.recordingId))
        val audio = core.audio(meta.recordingId)
        assertEquals(emptyList(), audio.missing)
        assertEquals(listOf(dir / part.file), audio.paths)
        assertEquals(SEEDED_AUDIO, fs.read(audio.paths.single()) { readUtf8() })

        core.disconnect(alsoDeleteRecordings = false)
        assertFalse(core.recordings.get(meta.recordingId)!!.driveSynced)
        assertTrue(core.recordings.driveFileIds(meta.recordingId).isEmpty())
        assertTrue(fs.exists(dir / part.file), "connection cleanup must keep downloaded/local audio")
        assertEquals(emptyList(), drive.deleted)
    }

    @Test
    fun `disconnect clears tokens, completed jobs and caches, and keeps recordings and settings`() =
        runBlocking<Unit> {
            val uploaded = testMeta(parts = listOf(testPart(testMeta(), 1)))
            val dir = "/data/recordings/${MetaWriter.baseName(uploaded)}".toPath()
            core.recordings.create(uploaded, dir)
            seedFiles(fs, dir, uploaded)
            core.recordings.finalize(uploaded.recordingId, START, durationSec = 900.0)
            core.enqueue(uploaded.recordingId)
            core.runDueJobs(START)

            // A second recording that never went up: its audio is the only copy there is.
            val pending = testMeta(recordingId = "01J9ZZZZZZ0123456789ABCDEF", startedAt = "2026-08-26T02:00:00.000Z")
            val pendingMeta = pending.copy(parts = listOf(testPart(pending, 1)))
            val pendingDir = "/data/recordings/${MetaWriter.baseName(pendingMeta)}".toPath()
            core.recordings.create(pendingMeta, pendingDir)
            seedFiles(fs, pendingDir, pendingMeta)

            deps.secureStore.put(SecureStore.SECRETS, "speech_api", "sk-x".encodeToByteArray())
            deps.secureStore.put(SecureStore.TOKENS, "refresh", "1//refresh".encodeToByteArray())
            assertTrue(core.jobs.list().isNotEmpty())
            assertNotNull(queries.selectFolderCache("recly").executeAsOneOrNull())
            val settings = assertIs<ProcessingSettingsState.Ready>(core.processingSettings.read())

            core.disconnect(alsoDeleteRecordings = false)

            assertEquals(emptyList(), core.jobs.list())
            assertNull(deps.secureStore.get(SecureStore.TOKENS, "refresh"))
            assertNull(queries.selectFolderCache("recly").executeAsOneOrNull())
            // The device's own configuration is not the account's to take.
            assertEquals("sk-x", core.secrets.get("speech_api"))
            assertEquals(settings, core.processingSettings.read())

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

    @Test
    fun `disconnect preserves progress across restart and resumes only remaining steps`() = runBlocking {
        val jobId = waitingTranscription()
        val before = core.jobs.steps(jobId)
        val jobBefore = core.jobs.list().single()
        val uploads = drive.uploadOrder()
        core.disconnect(alsoDeleteRecordings = false)
        assertEquals(JobStatus.NEEDS_AUTH, core.jobs.list().single().status)
        assertEquals(before, core.jobs.steps(jobId), "disconnect must not reset attempts, outputs or resume state")
        assertFalse(core.jobs.retry(jobId), "manual retry cannot bypass a disconnected account")
        core.runDueJobs(clock.now())
        assertEquals(1, stt.received.size, "even the fake still-valid token must not resume disconnected work")

        val restarted = ReclyCore(deps, object : DriverFactory {
            override fun create(): SqlDriver = driver
        })
        assertEquals(1, restarted.reconnectDrive())
        assertEquals(JobStatus.WAITING, restarted.jobs.list().single().status)
        assertEquals(jobBefore.nextRunAt, restarted.jobs.list().single().nextRunAt)
        assertEquals(before, restarted.jobs.steps(jobId))
        stt.status = 200
        clock.advance(1.days)
        restarted.runDueJobs(clock.now())
        assertEquals(JobStatus.DONE, restarted.jobs.list().single().status)
        assertEquals(2, stt.received.size)
        val base = MetaWriter.baseName(testMeta())
        val transcript = setOf(recly.core.transcribe.TranscribeRunner.jsonFileName(base), recly.core.transcribe.TranscribeRunner.textFileName(base))
        assertEquals(uploads, drive.uploadOrder().filterNot { it in transcript }, "successful upload must not repeat")
        assertEquals(before.first().output, restarted.jobs.steps(jobId).first().output)
    }

    @Test
    fun `another Drive account cannot resume a disconnected workflow or reset its retry state`() = runBlocking {
        val jobId = waitingTranscription()
        val steps = core.jobs.steps(jobId)
        core.disconnect(alsoDeleteRecordings = false)
        drive.accountId = "drive-owner-b"
        assertEquals(0, core.reconnectDrive())
        assertFalse(core.jobs.retry(jobId))
        clock.advance(1.days)
        core.runDueJobs(clock.now())
        assertEquals(JobStatus.NEEDS_AUTH, core.jobs.list().single().status)
        assertEquals(steps, core.jobs.steps(jobId))
        assertEquals(1, stt.received.size)
        drive.accountId = "drive-owner-a"
        assertEquals(1, core.reconnectDrive())
        stt.status = 200
        core.runDueJobs(clock.now())
        assertEquals(JobStatus.DONE, core.jobs.list().single().status)
        assertEquals(2, stt.received.size)
    }

    @Test
    fun `failed work remains failed after disconnect and a verified reconnect`() = runBlocking {
        val jobId = waitingTranscription()
        queries.updateJobStatus(JobStatus.FAILED.name, null, clock.now().isoUtc(), jobId)
        val before = core.jobs.steps(jobId)
        core.disconnect(alsoDeleteRecordings = false)
        core.reconnectDrive()
        clock.advance(1.days)
        core.runDueJobs(clock.now())
        assertEquals(JobStatus.FAILED, core.jobs.list().single().status)
        assertEquals(before, core.jobs.steps(jobId))
        assertEquals(1, stt.received.size, "reconnection is not an instruction to retry a terminal failure")
    }

    @Test
    fun `unverified reconnection preserves parked work until Drive becomes reachable`() = runBlocking {
        val jobId = waitingTranscription()
        val before = core.jobs.steps(jobId)
        core.disconnect(alsoDeleteRecordings = false)
        drive.failNext(503) { it.path == "/drive/v3/about" }
        assertFailsWith<recly.core.job.StepFailure> { core.reconnectDrive() }
        assertEquals(JobStatus.NEEDS_AUTH, core.jobs.list().single().status)
        assertEquals(before, core.jobs.steps(jobId))
        stt.status = 200
        clock.advance(1.days)
        core.runDueJobs(clock.now())
        assertEquals(JobStatus.DONE, core.jobs.list().single().status)
    }

    @Test
    fun `legacy progress resumes only after the existing folder owner is verified`() = runBlocking {
        val jobId = waitingTranscription()
        driver.execute(null, "UPDATE job SET drive_account_id = NULL", 0)
        queries.kvDelete("jobs.drive.account")
        core.disconnect(alsoDeleteRecordings = false)
        drive.accountId = "drive-owner-b"
        assertEquals(0, core.reconnectDrive())
        assertEquals(JobStatus.NEEDS_AUTH, core.jobs.list().single().status)
        drive.accountId = "drive-owner-a"
        assertEquals(1, core.reconnectDrive())
        assertEquals(JobStatus.WAITING, core.jobs.list().single().status)
        assertEquals("drive-owner-a", queries.selectJobById(jobId).executeAsOne().drive_account_id)
        assertTrue(drive.requests.any { it.query["fields"] == "owners(permissionId)" })
    }

    @Test
    fun `queued audio is bound before its first upload and cannot move to a different account`() = runBlocking {
        core.reconnectDrive()
        val meta = testMeta(parts = listOf(testPart(testMeta(), 1)))
        val dir = "/data/recordings/${MetaWriter.baseName(meta)}".toPath()
        core.recordings.create(meta, dir)
        seedFiles(fs, dir, meta)
        core.recordings.finalize(meta.recordingId, START, durationSec = 900.0)
        val jobId = (core.enqueue(meta.recordingId) as EnqueueResult.Enqueued).jobId
        core.disconnect(alsoDeleteRecordings = false)
        drive.accountId = "drive-owner-b"
        core.reconnectDrive()
        core.runDueJobs(START)
        assertTrue(drive.requests.none { it.path.startsWith("/upload/") })
        assertEquals(JobStatus.NEEDS_AUTH, core.jobs.list().single().status)
        drive.accountId = "drive-owner-a"
        core.reconnectDrive()
        core.runDueJobs(START)
        assertEquals(JobStatus.DONE, core.jobs.list().single { it.id == jobId }.status)
    }

    @Test
    fun `credentials replaced during verification cannot resume the old account work`() = runBlocking {
        val jobId = waitingTranscription()
        core.disconnect(alsoDeleteRecordings = false)
        drive.before += { request ->
            if (request.path == "/drive/v3/about") tokenProvider.token = "replacement-account-token"
        }
        assertFailsWith<recly.core.platform.AuthRequiredException> { core.reconnectDrive() }
        assertEquals(JobStatus.NEEDS_AUTH, core.jobs.list().single { it.id == jobId }.status)
        assertEquals(1, stt.received.size)
        drive.before.clear()
        drive.accountId = "drive-owner-b"
        core.runDueJobs(clock.now())
        assertEquals(JobStatus.NEEDS_AUTH, core.jobs.list().single { it.id == jobId }.status)
        assertEquals(1, stt.received.size)
    }

    /** A job whose upload succeeded and whose transcription is waiting out a provider 503. */
    private suspend fun waitingTranscription(): String {
        core.secrets.put("speech_api", "sk-test")
        val initial = core.initializeProcessing().document
        assertIs<ProcessingSaveResult.Saved>(core.processingSettings.save(initial.settings.copy(
            transcription = ProcessingTranscription(mode = TranscriptionMode.EXTERNAL,
                external = ExternalTranscription("groq", "speech_api", invokeUrl = stt.url)),
        ), initial.revision))
        val meta = testMeta(parts = listOf(testPart(testMeta(), 1)))
        val dir = "/data/recordings/${MetaWriter.baseName(meta)}".toPath()
        core.recordings.create(meta, dir)
        seedFiles(fs, dir, meta)
        core.recordings.finalize(meta.recordingId, START, durationSec = 900.0)
        val jobId = (core.enqueue(meta.recordingId) as EnqueueResult.Enqueued).jobId
        stt.status = 503
        core.runDueJobs(START)
        assertEquals(JobStatus.WAITING, core.jobs.list().single().status)
        assertEquals(1, stt.received.size)
        val step = core.jobs.steps(jobId).single { it.stepId == "transcribe" }
        recly.core.job.JobStore(RecDatabase(driver), deps).saveStepState(
            step.id, kotlinx.serialization.json.buildJsonObject {
                put("resume", kotlinx.serialization.json.JsonPrimitive("keep-this-reference"))
            },
        )
        return jobId
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
    var token: String = ScriptedTokenProvider.FIRST

    override suspend fun accessToken(): String = token

    override suspend fun invalidate() {
        calls += INVALIDATE
    }

    companion object {
        const val INVALIDATE = "invalidate"
    }
}
